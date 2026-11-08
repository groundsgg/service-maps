package gg.grounds.lod.runtime

import dev.vox.lss.common.wire.WireBytes
import gg.grounds.lod.format.*
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.EventListener
import net.minestom.server.event.EventNode
import net.minestom.server.event.instance.RemoveEntityFromInstanceEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerPacketOutEvent
import net.minestom.server.event.player.PlayerPluginMessageEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.instance.Instance
import net.minestom.server.network.ConnectionState
import net.minestom.server.network.NetworkBuffer
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.packet.server.common.PluginMessagePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import net.minestom.server.network.player.PlayerSocketConnection
import org.junit.jupiter.api.BeforeAll

class MinestomWriterRegressionTest {
    private class TestPlayer(connection: PlayerConnection, id: UUID, var location: Instance) :
        Player(connection, GameProfile(id, "WriterPilot")) {
        override fun getInstance() = location

        override fun isOnline() = true
    }

    private class WriterQueue(private val delegate: Any) {
        private fun call(name: String): Any? =
            delegate.javaClass.getMethod(name).let {
                it.isAccessible = true
                it.invoke(delegate)
            }

        fun peek() = call("peek") as? SendablePacket

        fun poll() = call("poll") as? SendablePacket

        val isEmpty: Boolean
            get() = call("isEmpty") as Boolean
    }

    private class Harness : AutoCloseable {
        val root = Files.createTempDirectory("lod-real-writer-")
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val other = MinecraftServer.getInstanceManager().createInstanceContainer()
        val f = LodFixture(root.resolve("input"), MinestomLodAdapter.registrySha256())
        val clock = AtomicLong(1)
        val grants = AtomicInteger()
        val channel = SocketChannel.open()
        val connection =
            PlayerSocketConnection(
                channel,
                InetSocketAddress("127.0.0.1", 12345),
                Thread.ofVirtual().unstarted {},
                Thread.ofVirtual().unstarted {},
            )
        val player = TestPlayer(connection, f.player, instance)
        val parent = EventNode.all("real-writer-test")
        val adapter: MinestomLodAdapter
        val queue: WriterQueue
        val serialized = mutableListOf<PluginMessagePacket>()

        init {
            connection.setPlayer(player)
            connection.setServerState(ConnectionState.PLAY)
            connection.setClientState(ConnectionState.PLAY)
            @Suppress("UNCHECKED_CAST")
            queue =
                PlayerSocketConnection::class.java.getDeclaredField("packetQueue").let {
                    it.isAccessible = true
                    WriterQueue(it.get(connection))
                }
            val delegate = f.control(AtomicInteger())
            val control =
                object : LodControl by delegate {
                    override fun payload(player: UUID, bytes: Int): LodLease<LodPayloadGrant> {
                        grants.incrementAndGet()
                        return LodLease(
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
                }
            MinecraftServer.getGlobalEventHandler().addChild(parent)
            adapter =
                MinestomLodAdapter(
                    parent,
                    instance,
                    f.expectation,
                    control,
                    setOf(f.player),
                    root.resolve("cache"),
                    false,
                    clock::get,
                )
            receive(Lss20.HANDSHAKE, byteArrayOf(20, 3))
            receive(
                Lss20.CLIENT_INFO,
                WireBytes.Writer(5).writeVarInt(MinecraftServer.DATA_VERSION).toByteArray(),
            )
        }

        fun receive(channel: String, body: ByteArray) =
            parent.call(PlayerPluginMessageEvent(player, channel, body))

        fun awaitEnabledConfig(): PluginMessagePacket {
            val deadline = System.nanoTime() + 3000000000
            while (System.nanoTime() < deadline) {
                adapter.pumpForTest()
                val head = queue.peek()
                if (
                    head is PluginMessagePacket &&
                        head.channel() == Lss20.SESSION &&
                        head.data()[1].toInt() == 1
                )
                    return head
                if (head != null) consume(65536)
                Thread.sleep(1)
            }
            error("No enabled SessionConfig")
        }

        /** Exact pinned private writer: it dispatches the real event and rolls back on overflow. */
        fun attempt(capacity: Int): Pair<Boolean, Long> {
            val head = requireNotNull(queue.peek())
            val buffer = NetworkBuffer.staticBuffer(capacity.toLong())
            val method =
                PlayerSocketConnection::class
                    .java
                    .getDeclaredMethod(
                        "writeSendable",
                        NetworkBuffer::class.java,
                        SendablePacket::class.java,
                        Boolean::class.javaPrimitiveType,
                    )
            method.isAccessible = true
            val consumed = method.invoke(connection, buffer, head, false) as Boolean
            if (consumed) {
                assertSame(head, queue.poll())
                if (buffer.writeIndex() > 0 && head is PluginMessagePacket) serialized.add(head)
            }
            return consumed to buffer.writeIndex()
        }

        fun consume(capacity: Int) = attempt(capacity).also { assertTrue(it.first) }

        fun drain() {
            while (!queue.isEmpty) consume(131072)
        }

        fun move(to: Instance) {
            val from = player.location
            MinecraftServer.getGlobalEventHandler()
                .call(RemoveEntityFromInstanceEvent(from, player))
            player.location = to
            MinecraftServer.getGlobalEventHandler().call(PlayerSpawnEvent(player, to, false))
        }

        override fun close() {
            adapter.close()
            connection.disconnect()
            MinecraftServer.getGlobalEventHandler().call(PlayerDisconnectEvent(player))
            MinecraftServer.getGlobalEventHandler().removeChild(parent)
            channel.close()
            MinecraftServer.getInstanceManager().unregisterInstance(instance)
            MinecraftServer.getInstanceManager().unregisterInstance(other)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Fabric receives server channels before enabling terrain requests`() {
        Harness().use { h ->
            h.awaitEnabledConfig()
            val registration = h.serialized.lastOrNull { it.channel() == "minecraft:register" }
            assertNotNull(registration, "Fabric canSend requires the server channel advertisement")
            assertEquals(
                setOf(
                    "lss:handshake_c2s",
                    "lss:client_info",
                    "lss:batch_chunk_req",
                    "lss:region_summary_req",
                ),
                registration.data().toString(Charsets.UTF_8).split('\u0000').toSet(),
            )
            assertTrue(h.consume(65536).second > 0)
            h.drain()
        }
    }

    @Test
    fun `a packet that failed to fit remains owned and succeeds on writer retry`() {
        Harness().use { h ->
            val packet = h.awaitEnabledConfig()
            assertEquals(false to 0L, h.attempt(8))
            assertSame(packet, h.queue.peek())
            val retry = h.consume(65536)
            assertTrue(retry.second > 0, "valid config must be serialized on retry")
            h.drain()
            assertEquals(0, h.adapter.stats().queuedFrames)
        }
    }

    @Test
    fun `shutdown between failed write and retry still cancels the same carrier`() {
        Harness().use { h ->
            h.awaitEnabledConfig()
            assertFalse(h.attempt(8).first)
            h.adapter.close()
            assertEquals(0L, h.consume(65536).second)
            h.drain()
        }
    }

    @Test
    fun `committed ABA world transition fences an old frame without pumping`() {
        Harness().use { h ->
            h.awaitEnabledConfig()
            h.drain()
            h.receive(
                Lss20.BATCH_REQUEST,
                WireBytes.Writer(17).writeVarInt(1).writeLong(0).writeLong(0).toByteArray(),
            )
            h.adapter.pumpForTest()
            assertNotNull(h.queue.peek())
            h.move(h.other)
            h.move(h.instance)
            assertEquals(0L, h.consume(65536).second)
            h.drain()
        }
    }

    @Test
    fun `already cancelled event frees the UUID slot and retries configuration`() {
        Harness().use { h ->
            h.awaitEnabledConfig()
            val cancel =
                EventListener.builder(PlayerPacketOutEvent::class.java)
                    .filter {
                        it.player === h.player &&
                            (it.packet as? PluginMessagePacket)?.channel() == Lss20.SESSION
                    }
                    .expireCount(1)
                    .handler { it.isCancelled = true }
                    .build()
            MinecraftServer.getGlobalEventHandler().addListener(cancel)
            assertEquals(0L, h.consume(65536).second)
            h.drain()
            assertEquals(0, h.adapter.stats().queuedFrames)
            h.adapter.pumpForTest()
            assertNotNull(h.queue.peek(), "cancelled config must not be recorded delivered")
            MinecraftServer.getGlobalEventHandler().removeListener(cancel)
        }
    }

    @Test
    fun `an occupied old socket never spends new credit for replacement sessions`() {
        Harness().use { h ->
            h.awaitEnabledConfig()
            val replicas =
                List(2) {
                    val con =
                        object : PlayerConnection() {
                            override fun sendPacket(packet: SendablePacket) = Unit

                            override fun getRemoteAddress() = InetSocketAddress("127.0.0.1", 12346)
                        }
                    TestPlayer(con, h.f.player, h.instance)
                }
            repeat(600) { index ->
                val peer = replicas[index % 2]
                h.parent.call(PlayerPluginMessageEvent(peer, Lss20.HANDSHAKE, byteArrayOf(20, 3)))
                h.parent.call(
                    PlayerPluginMessageEvent(
                        peer,
                        Lss20.CLIENT_INFO,
                        WireBytes.Writer(5).writeVarInt(MinecraftServer.DATA_VERSION).toByteArray(),
                    )
                )
                repeat(4) { h.adapter.pumpForTest() }
            }
            assertEquals(1, h.adapter.stats().queuedFrames)
            assertEquals(1, h.grants.get(), "blocked socket must not burn additional grants")
        }
    }

    @Test
    fun `a later cancellation is observed before configuration completion`() {
        Harness().use { h ->
            h.awaitEnabledConfig()
            val late = EventNode.all("late-cancel").setPriority(Int.MAX_VALUE)
            val once = java.util.concurrent.atomic.AtomicBoolean()
            late.addListener(PlayerPacketOutEvent::class.java) { event ->
                if (
                    event.player === h.player &&
                        (event.packet as? PluginMessagePacket)?.channel() == Lss20.SESSION &&
                        once.compareAndSet(false, true)
                )
                    event.isCancelled = true
            }
            MinecraftServer.getGlobalEventHandler().addChild(late)
            try {
                assertEquals(0L, h.consume(65536).second)
                h.drain()
                assertTrue(once.get())
                h.adapter.pumpForTest()
                assertNotNull(h.queue.peek(), "a later-cancelled config cannot be committed")
            } finally {
                MinecraftServer.getGlobalEventHandler().removeChild(late)
            }
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
