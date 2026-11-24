package gg.grounds.lod.runtime

import dev.vox.lss.common.wire.WireBytes
import gg.grounds.lod.format.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.event.instance.RemoveEntityFromInstanceEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerPacketOutEvent
import net.minestom.server.event.player.PlayerPluginMessageEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.instance.Instance
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.packet.server.common.PluginMessagePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import org.junit.jupiter.api.BeforeAll

class MinestomLodAdapterTest {
    private class Connection : PlayerConnection() {
        val queued = ArrayDeque<PluginMessagePacket>()

        override fun sendPacket(packet: SendablePacket) {
            if (packet is PluginMessagePacket) queued.addLast(packet)
        }

        override fun getRemoteAddress() = InetSocketAddress("127.0.0.1", 12345)
    }

    private class TestPlayer(connection: Connection, id: UUID, var location: Instance) :
        Player(connection, GameProfile(id, "Pilot")) {
        override fun getInstance() = location

        override fun isOnline() = true
    }

    @Test
    fun `host registry matches the pinned worker`() {
        assertEquals(
            "f530aa5e4b98fcdf671b86558d4c07f639f68965e25097e9d37490d342f520b6",
            MinestomLodAdapter.registrySha256(),
        )
    }

    @Test
    fun `queued Minestom terrain is guarded after world switch and shutdown`() = guarded(false)

    @Test
    fun `entitled Minestom terrain keeps world generation and writer shutdown fences`() =
        guarded(true)

    @Test
    fun `public entitled install performs no control HTTP before any client demand`() {
        val root = Files.createTempDirectory("lod-entitled-install-")
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        try {
            val f = LodFixture(root.resolve("input"), MinestomLodAdapter.registrySha256())
            val calls = AtomicInteger()
            val control =
                object : LodControl {
                    override fun source(player: UUID): LodLease<LodSourceGrant> {
                        calls.incrementAndGet()
                        error("unexpected HTTP")
                    }

                    override fun payload(player: UUID, bytes: Int): LodLease<LodPayloadGrant> {
                        calls.incrementAndGet()
                        error("unexpected HTTP")
                    }

                    override fun download(
                        lease: LodLease<LodSourceGrant>,
                        into: java.nio.file.Path,
                    ) {
                        calls.incrementAndGet()
                        error("unexpected HTTP")
                    }
                }
            MinestomLodAdapter.installEntitled(
                    EventNode.all("lazy-entitled"),
                    instance,
                    f.expectation,
                    control,
                    root.resolve("cache"),
                ) {
                    true
                }
                .use {
                    assertEquals(0, calls.get())
                    assertEquals(0, it.stats().queuedFrames)
                }
        } finally {
            MinecraftServer.getInstanceManager().unregisterInstance(instance)
            root.toFile().deleteRecursively()
        }
    }

    private fun guarded(entitled: Boolean) {
        val root = Files.createTempDirectory("lod-minestom-")
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val other = MinecraftServer.getInstanceManager().createInstanceContainer()
        try {
            val f = LodFixture(root.resolve("input"), MinestomLodAdapter.registrySha256())
            val clock = AtomicLong(1)
            val downloads = AtomicInteger()
            val source = f.control(downloads)
            val control =
                object : LodControl by source {
                    override fun payload(player: UUID, bytes: Int) =
                        LodLease(
                            LodPayloadGrant(
                                runId = f.grant.runId,
                                requestId = UUID.randomUUID().toString(),
                                bytes = bytes,
                                validForMillis = 1000,
                            ),
                            clock.get(),
                            1000,
                        )
                }
            val parent = EventNode.all("test-lod")
            MinecraftServer.getGlobalEventHandler().addChild(parent)
            val adapter =
                MinestomLodAdapter(
                    parent,
                    instance,
                    f.expectation,
                    control,
                    if (entitled) emptySet() else setOf(f.player),
                    root.resolve("cache"),
                    false,
                    clock::get,
                    { id -> !entitled || id == f.player },
                    entitled = entitled,
                )
            val connection = Connection()
            val player = TestPlayer(connection, f.player, instance)
            fun receive(channel: String, body: ByteArray) =
                parent.call(PlayerPluginMessageEvent(player, channel, body))
            fun flush(): List<PluginMessagePacket> = buildList {
                while (connection.queued.isNotEmpty()) {
                    val packet = connection.queued.removeFirst()
                    val event = PlayerPacketOutEvent(player, packet)
                    MinecraftServer.getGlobalEventHandler().call(event)
                    if (!event.isCancelled) add(packet)
                }
            }
            if (entitled) {
                repeat(64) {
                    val denied = TestPlayer(Connection(), UUID.randomUUID(), instance)
                    parent.call(
                        PlayerPluginMessageEvent(denied, Lss20.HANDSHAKE, byteArrayOf(20, 3))
                    )
                    parent.call(
                        PlayerPluginMessageEvent(
                            denied,
                            Lss20.CLIENT_INFO,
                            WireBytes.Writer(5)
                                .writeVarInt(MinecraftServer.DATA_VERSION)
                                .toByteArray(),
                        )
                    )
                }
                adapter.pumpForTest()
                assertEquals(0, downloads.get())
                assertEquals(0, adapter.stats().queuedFrames)
            }
            receive(Lss20.HANDSHAKE, byteArrayOf(20, 3))
            receive(
                Lss20.CLIENT_INFO,
                WireBytes.Writer(5).writeVarInt(MinecraftServer.DATA_VERSION).toByteArray(),
            )
            var enabled = false
            val deadline = System.nanoTime() + 3000000000
            while (!enabled && System.nanoTime() < deadline) {
                adapter.pumpForTest()
                enabled = flush().any { it.channel() == Lss20.SESSION && it.data()[1].toInt() == 1 }
                Thread.sleep(2)
            }
            assertTrue(enabled)
            assertEquals(1, downloads.get())
            val request =
                WireBytes.Writer(17).writeVarInt(1).writeLong(0).writeLong(0).toByteArray()
            receive(Lss20.BATCH_REQUEST, request)
            adapter.pumpForTest()
            assertEquals(1, connection.queued.count { it.channel() in Lss20.outbound })
            repeat(5) {
                val overlapping = TestPlayer(Connection(), f.player, instance)
                parent.call(
                    PlayerPluginMessageEvent(overlapping, Lss20.HANDSHAKE, byteArrayOf(20, 3))
                )
                parent.call(
                    PlayerPluginMessageEvent(
                        overlapping,
                        Lss20.CLIENT_INFO,
                        WireBytes.Writer(5).writeVarInt(MinecraftServer.DATA_VERSION).toByteArray(),
                    )
                )
                repeat(4) { adapter.pumpForTest() }
                assertEquals(1, adapter.stats().queuedFrames)
                MinecraftServer.getGlobalEventHandler().call(PlayerDisconnectEvent(overlapping))
            }
            receive(Lss20.HANDSHAKE, byteArrayOf(20, 3))
            receive(
                Lss20.CLIENT_INFO,
                WireBytes.Writer(5).writeVarInt(MinecraftServer.DATA_VERSION).toByteArray(),
            )
            repeat(4) { adapter.pumpForTest() }
            MinecraftServer.getGlobalEventHandler()
                .call(RemoveEntityFromInstanceEvent(player.location, player))
            player.location = other
            MinecraftServer.getGlobalEventHandler().call(PlayerSpawnEvent(player, other, false))
            assertTrue(flush().isEmpty())
            MinecraftServer.getGlobalEventHandler()
                .call(RemoveEntityFromInstanceEvent(player.location, player))
            player.location = instance
            MinecraftServer.getGlobalEventHandler().call(PlayerSpawnEvent(player, instance, false))
            repeat(3) {
                adapter.pumpForTest()
                flush()
            }
            receive(Lss20.BATCH_REQUEST, request)
            adapter.pumpForTest()
            assertEquals(1, connection.queued.count { it.channel() in Lss20.outbound })
            adapter.close()
            MinecraftServer.getGlobalEventHandler().removeChild(parent)
            assertTrue(flush().isEmpty())
        } finally {
            MinecraftServer.getInstanceManager().unregisterInstance(instance)
            MinecraftServer.getInstanceManager().unregisterInstance(other)
            root.toFile().deleteRecursively()
        }
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun boot() {
            MinecraftServer.init()
        }
    }
}
