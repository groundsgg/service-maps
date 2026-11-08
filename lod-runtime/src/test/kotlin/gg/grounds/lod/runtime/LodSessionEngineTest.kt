package gg.grounds.lod.runtime

import dev.vox.lss.common.wire.WireBytes
import gg.grounds.lod.format.*
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class LodSessionEngineTest {
    private class Peer(override val id: UUID, var binding: LodBinding) : LodPeer {
        override val online = true
        val queued = ArrayDeque<LodDelivery>()

        override fun world() = binding

        override fun enqueue(delivery: LodDelivery) {
            queued.add(delivery)
        }

        fun flush(): LodReply? =
            queued.removeFirstOrNull()?.let {
                val allowed = it.authorize()
                it.complete(allowed)
                if (allowed) it.reply else null
            }
    }

    private fun fixture(
        test: (LodFixture, LodSessionEngine, Peer, AtomicLong, AtomicInteger) -> Unit
    ) {
        val root = Files.createTempDirectory("lod-session-")
        try {
            val f = LodFixture(root.resolve("input"))
            val now = AtomicLong(1)
            val grants = AtomicInteger()
            val sourceControl = f.control(AtomicInteger())
            val control =
                object : LodControl by sourceControl {
                    override fun payload(player: UUID, bytes: Int): LodLease<LodPayloadGrant> {
                        grants.incrementAndGet()
                        return LodLease(
                            LodPayloadGrant(
                                runId = f.grant.runId,
                                requestId = UUID.randomUUID().toString(),
                                bytes = bytes,
                                validForMillis = 1000,
                            ),
                            now.get(),
                            1000,
                        )
                    }
                }
            LodCache(control, f.expectation, root.resolve("cache")).use { cache ->
                cache.load(f.player).get()
                val gate = LodCreditGate(control, setOf(f.player), now::get)
                LodSessionEngine(cache, gate, f.expectation, setOf(f.player), 42, now::get).use {
                    engine ->
                    val peer =
                        Peer(
                            f.player,
                            LodBinding(Any(), f.expectation.mapSha256, f.expectation.sourceSha256),
                        )
                    test(f, engine, peer, now, grants)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun handshake(engine: LodSessionEngine, peer: Peer) {
        engine.receive(peer, Lss20.HANDSHAKE, byteArrayOf(20, 3))
        engine.receive(peer, Lss20.CLIENT_INFO, byteArrayOf(42))
        repeat(5) { engine.pump() }
        assertEquals(Lss20.SESSION, peer.flush()?.channel)
    }

    @Test
    fun `vanilla unknown players wrong map and mismatched client receive no terrain`() =
        fixture { f, engine, peer, _, grants ->
            engine.pump()
            assertTrue(peer.queued.isEmpty())
            engine.receive(peer, Lss20.HANDSHAKE, byteArrayOf(19, 3))
            repeat(3) { engine.pump() }
            assertTrue(peer.queued.isEmpty())
            val unknown = Peer(UUID.randomUUID(), peer.binding)
            engine.receive(unknown, Lss20.HANDSHAKE, byteArrayOf(20, 3))
            engine.receive(unknown, Lss20.CLIENT_INFO, byteArrayOf(42))
            engine.receive(peer, Lss20.HANDSHAKE, byteArrayOf(20, 3))
            engine.receive(peer, Lss20.CLIENT_INFO, byteArrayOf(43))
            repeat(5) { engine.pump() }
            assertTrue(peer.queued.isEmpty())
            assertTrue(unknown.queued.isEmpty())
            peer.binding = LodBinding(Any(), "f".repeat(64), f.expectation.sourceSha256)
            engine.receive(peer, Lss20.CLIENT_INFO, byteArrayOf(42))
            repeat(5) { engine.pump() }
            assertTrue(peer.queued.isEmpty())
            assertEquals(0, grants.get())
        }

    @Test
    fun `one outstanding frame bounds backpressure and stale world frame is cancelled`() =
        fixture { f, engine, peer, _, _ ->
            handshake(engine, peer)
            val request =
                WireBytes.Writer(17).writeVarInt(1).writeLong(0).writeLong(0).toByteArray()
            engine.receive(peer, Lss20.BATCH_REQUEST, request)
            repeat(100) { engine.pump() }
            assertEquals(1, peer.queued.size)
            peer.binding = LodBinding(Any(), null, null)
            assertNull(peer.flush())
            engine.pump()
            val disable = assertNotNull(peer.flush())
            assertContentEquals(byteArrayOf(20, 0, 96, 0, 42), disable.body)
            peer.binding = LodBinding(Any(), f.expectation.mapSha256, f.expectation.sourceSha256)
            repeat(5) { engine.pump() }
            assertContentEquals(byteArrayOf(20, 1, 96, 0, 42), assertNotNull(peer.flush()).body)
            engine.receive(peer, Lss20.BATCH_REQUEST, request)
            engine.pump()
            assertEquals(Lss20.VOXEL, peer.flush()?.channel)
        }

    @Test
    fun `expired queued frame and old connection cannot send or reset the budget`() =
        fixture { _, engine, peer, now, grants ->
            handshake(engine, peer)
            val request =
                WireBytes.Writer(17).writeVarInt(1).writeLong(0).writeLong(0).toByteArray()
            engine.receive(peer, Lss20.BATCH_REQUEST, request)
            engine.pump()
            now.addAndGet(1001000000)
            assertNull(peer.flush())
            val replacement = Peer(peer.id, peer.binding)
            handshake(engine, replacement)
            engine.receive(replacement, Lss20.BATCH_REQUEST, request)
            engine.pump()
            val frame = replacement.queued.removeFirst()
            engine.close()
            assertFalse(frame.authorize())
            assertFalse(frame.authorize())
            assertEquals(
                2,
                grants.get(),
            ) // reconnect consumed existing shared credits, no local reset
        }

    @Test
    fun `warm client receives up to date and missing positions never trigger generation`() =
        fixture { _, engine, peer, _, _ ->
            handshake(engine, peer)
            for ((position, stamp, code) in
                listOf(Triple(0L, 1794146400L, 1), Triple(1000L, 0L, 2))) {
                engine.receive(
                    peer,
                    Lss20.BATCH_REQUEST,
                    WireBytes.Writer(17)
                        .writeVarInt(1)
                        .writeLong(position)
                        .writeLong(stamp)
                        .toByteArray(),
                )
                repeat(3) { engine.pump() }
                val reply = assertNotNull(peer.flush())
                assertEquals(Lss20.BATCH_RESPONSE, reply.channel)
                val r = WireBytes.Reader(reply.body)
                assertEquals(1, r.readVarInt())
                assertEquals(code, r.readUnsignedByte())
                assertEquals(position, r.readLong())
            }
        }

    @Test
    fun `new capability declaration immediately fences an older configuration`() =
        fixture { _, engine, peer, _, _ ->
            engine.receive(peer, Lss20.HANDSHAKE, byteArrayOf(20, 11))
            engine.receive(peer, Lss20.CLIENT_INFO, byteArrayOf(42))
            repeat(5) { engine.pump() }
            assertEquals(1, peer.queued.size)
            engine.receive(peer, Lss20.HANDSHAKE, byteArrayOf(20, 3))
            assertNull(peer.flush())
            repeat(5) { engine.pump() }
            assertContentEquals(byteArrayOf(20, 1, 96, 0, 42), assertNotNull(peer.flush()).body)
        }

    @Test
    fun `empty want declaration stops every unadmitted column`() =
        fixture { _, engine, peer, _, _ ->
            handshake(engine, peer)
            val body =
                WireBytes.Writer(40)
                    .writeVarInt(2)
                    .writeLong(0)
                    .writeLong(0)
                    .writeLong(1)
                    .writeLong(0)
                    .toByteArray()
            engine.receive(peer, Lss20.BATCH_REQUEST, body)
            engine.pump()
            assertEquals(1, peer.queued.size)
            engine.receive(peer, Lss20.BATCH_REQUEST, byteArrayOf(0))
            engine.pump()
            assertEquals(Lss20.VOXEL, peer.flush()?.channel)
            repeat(5) { engine.pump() }
            assertTrue(peer.queued.isEmpty())
        }

    @Test
    fun `new want declaration replaces old backlog instead of merging`() =
        fixture { _, engine, peer, _, _ ->
            handshake(engine, peer)
            engine.receive(
                peer,
                Lss20.BATCH_REQUEST,
                WireBytes.Writer(40)
                    .writeVarInt(2)
                    .writeLong(0)
                    .writeLong(0)
                    .writeLong(1)
                    .writeLong(0)
                    .toByteArray(),
            )
            engine.pump()
            engine.receive(
                peer,
                Lss20.BATCH_REQUEST,
                WireBytes.Writer(20).writeVarInt(1).writeLong(2).writeLong(0).toByteArray(),
            )
            engine.pump()
            assertNotNull(peer.flush())
            engine.pump()
            val r = WireBytes.Reader(assertNotNull(peer.flush()).body)
            assertEquals(1, r.readVarInt())
            assertEquals(2, r.readUnsignedByte())
            assertEquals(2L, r.readLong())
        }
}
