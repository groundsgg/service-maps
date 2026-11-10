package gg.grounds.lod.runtime

import gg.grounds.lod.format.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class LodEntitledRuntimeTest {
    private class Peer(override val id: UUID, val binding: LodBinding) : LodPeer {
        override val online = true
        var allowed = true
        val frames = ArrayDeque<LodDelivery>()

        override fun world() = binding

        override fun mayReceive() = allowed

        override fun enqueue(delivery: LodDelivery) {
            frames.add(delivery)
        }
    }

    @Test
    fun `entitled sessions accept unlisted UUID beyond thirty minutes and bound reconnect state`() {
        val root = Files.createTempDirectory("lod-entitled-")
        try {
            val f = LodFixture(root.resolve("input"))
            val now = AtomicLong(1)
            val calls = AtomicInteger()
            val delegate = f.control(AtomicInteger())
            val control =
                object : LodControl by delegate {
                    override fun payload(player: UUID, bytes: Int): LodLease<LodPayloadGrant> {
                        calls.incrementAndGet()
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
            LodCache(
                    control,
                    f.expectation,
                    root.resolve("cache"),
                    entitled = true,
                    nanos = now::get,
                )
                .use { cache ->
                    cache.load(f.player).get()
                    val gate = LodCreditGate(control, emptySet(), now::get, entitled = true)
                    LodSessionEngine(
                            cache,
                            gate,
                            f.expectation,
                            emptySet(),
                            42,
                            now::get,
                            entitled = true,
                        )
                        .use { engine ->
                            now.addAndGet(1801000000000L)
                            val peers =
                                (1..65).map {
                                    Peer(
                                        UUID.randomUUID(),
                                        LodBinding(
                                            Any(),
                                            f.expectation.mapSha256,
                                            f.expectation.sourceSha256,
                                        ),
                                    )
                                }
                            fun join(peer: Peer) {
                                engine.receive(peer, Lss20.HANDSHAKE, byteArrayOf(20, 3))
                                engine.receive(peer, Lss20.CLIENT_INFO, byteArrayOf(42))
                            }
                            peers.forEach(::join)
                            repeat(3) { engine.pump() }
                            assertEquals(64, peers.sumOf { it.frames.size })
                            assertTrue(peers.last().frames.isEmpty())
                            repeat(200) {
                                engine.remove(peers.first())
                                assertEquals(63, engine.sessionCount())
                                join(peers.first())
                                engine.pump()
                                peers.first().frames.removeLast().drop()
                                assertEquals(64, engine.sessionCount())
                                assertTrue(gate.trackedPlayers() <= 64)
                            }
                            assertTrue(
                                calls.get() >= 200,
                                "disconnect discards unused local grants, never refunds or reuses them",
                            )
                        }
                }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `entitled transient credit failures back off and permanent closure cannot revive`() {
        val now = AtomicLong(1)
        val calls = AtomicInteger()
        var failure: LodUnavailable? = LodUnavailable()
        val control =
            object : LodControl {
                override fun source(player: UUID): LodLease<LodSourceGrant> = error("unused")

                override fun download(lease: LodLease<LodSourceGrant>, into: Path) = error("unused")

                override fun payload(player: UUID, bytes: Int): LodLease<LodPayloadGrant> {
                    calls.incrementAndGet()
                    failure?.let { throw it }
                    return LodLease(
                        LodPayloadGrant(
                            runId = UUID.randomUUID().toString(),
                            requestId = UUID.randomUUID().toString(),
                            bytes = bytes,
                            validForMillis = 1000,
                        ),
                        now.get(),
                        1000,
                    )
                }
            }
        val gate = LodCreditGate(control, emptySet(), now::get, entitled = true)
        val player = UUID.randomUUID()
        assertNull(gate.reserve(player, 1000))
        repeat(100) { assertNull(gate.reserve(UUID.randomUUID(), 1000)) }
        assertEquals(1, calls.get())
        now.addAndGet(1000000000)
        failure = null
        assertNotNull(gate.reserve(player, 1000))
        now.addAndGet(1000000001)
        failure = LodUnavailable(true)
        assertNull(gate.reserve(player, 1000))
        now.addAndGet(1000000000000)
        failure = null
        assertNull(gate.reserve(player, 1000))
        assertEquals(3, calls.get())
    }

    @Test
    fun `entitled cache retries not ready source with backoff and stops after finite failures`() {
        val root = Files.createTempDirectory("lod-entitled-retry-")
        try {
            val f = LodFixture(root.resolve("input"))
            val now = AtomicLong(1)
            val calls = AtomicInteger()
            val delegate = f.control(AtomicInteger())
            var available = false
            val control =
                object : LodControl by delegate {
                    override fun source(player: UUID): LodLease<LodSourceGrant> {
                        calls.incrementAndGet()
                        if (!available) throw LodUnavailable()
                        return delegate.source(player)
                    }
                }
            LodCache(
                    control,
                    f.expectation,
                    root.resolve("cache"),
                    entitled = true,
                    nanos = now::get,
                )
                .use { cache ->
                    assertFails { cache.load(f.player).get() }
                    repeat(100) { assertFails { cache.load(f.player).get() } }
                    assertEquals(1, calls.get())
                    now.addAndGet(1000000000)
                    available = true
                    assertEquals(1, cache.load(f.player).get().columnCount)
                    assertEquals(2, calls.get())
                }
            available = false
            LodCache(
                    control,
                    f.expectation,
                    root.resolve("bounded"),
                    entitled = true,
                    nanos = now::get,
                )
                .use { cache ->
                    repeat(30) {
                        assertFails { cache.load(f.player).get() }
                        now.addAndGet(1000000000000)
                    }
                    assertEquals(
                        24,
                        calls.get(),
                    ) // two previous calls plus twenty-two bounded attempts
                }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `initial denied UUIDs cannot consume entitled session slots`() {
        val root = Files.createTempDirectory("lod-entitled-admission-")
        try {
            val f = LodFixture(root.resolve("input"))
            val sources = AtomicInteger()
            val credits = AtomicInteger()
            val now = AtomicLong(1)
            val delegate = f.control(AtomicInteger())
            val control =
                object : LodControl by delegate {
                    override fun source(player: UUID): LodLease<LodSourceGrant> {
                        sources.incrementAndGet()
                        return delegate.source(player)
                    }

                    override fun payload(player: UUID, bytes: Int): LodLease<LodPayloadGrant> {
                        credits.incrementAndGet()
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
            LodCache(
                    control,
                    f.expectation,
                    root.resolve("cache"),
                    entitled = true,
                    nanos = now::get,
                )
                .use { cache ->
                    val gate = LodCreditGate(control, emptySet(), now::get, entitled = true)
                    LodSessionEngine(
                            cache,
                            gate,
                            f.expectation,
                            emptySet(),
                            42,
                            now::get,
                            entitled = true,
                        )
                        .use { engine ->
                            fun declare(peer: Peer) {
                                engine.receive(peer, Lss20.HANDSHAKE, byteArrayOf(20, 3))
                                engine.receive(peer, Lss20.CLIENT_INFO, byteArrayOf(42))
                            }
                            repeat(64) {
                                declare(
                                    Peer(
                                            UUID.randomUUID(),
                                            LodBinding(
                                                Any(),
                                                f.expectation.mapSha256,
                                                f.expectation.sourceSha256,
                                            ),
                                        )
                                        .apply { allowed = false }
                                )
                            }
                            repeat(5) { engine.pump() }
                            assertEquals(0, sources.get())
                            assertEquals(0, credits.get())
                            assertEquals(0, engine.sessionCount())
                            val eligible =
                                Peer(
                                    UUID.randomUUID(),
                                    LodBinding(
                                        Any(),
                                        f.expectation.mapSha256,
                                        f.expectation.sourceSha256,
                                    ),
                                )
                            declare(eligible)
                            val loaded = cache.load(eligible.id).get()
                            assertEquals(1, loaded.columnCount)
                            repeat(5) { engine.pump() }
                            assertTrue(eligible.frames.isNotEmpty())
                            assertEquals(1, engine.sessionCount())
                        }
                }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cache shutdown callbacks never retain cache bookkeeping monitor`() {
        val root = Files.createTempDirectory("lod-cache-close-")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val observer = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val f = LodFixture(root.resolve("input"))
            val delegate = f.control(AtomicInteger())
            val control =
                object : LodControl by delegate {
                    override fun source(player: UUID): LodLease<LodSourceGrant> {
                        entered.countDown()
                        release.await()
                        return delegate.source(player)
                    }
                }
            val cache = LodCache(control, f.expectation, root.resolve("cache"), entitled = true)
            val pending = cache.load(f.player)
            check(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
            val completion =
                pending.handle { _, _ ->
                    observer
                        .submit<Boolean> { cache.readyToLoad() }
                        .get(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            cache.close()
            assertFalse(completion.get(1, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            release.countDown()
            observer.shutdownNow()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `disconnect never waits for control HTTP or retains its late local credit`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val now = AtomicLong(1)
        val player = UUID.randomUUID()
        val control =
            object : LodControl {
                override fun source(player: UUID): LodLease<LodSourceGrant> = error("unused")

                override fun download(lease: LodLease<LodSourceGrant>, into: Path) = error("unused")

                override fun payload(player: UUID, bytes: Int): LodLease<LodPayloadGrant> {
                    entered.countDown()
                    check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    return LodLease(
                        LodPayloadGrant(
                            runId = UUID.randomUUID().toString(),
                            requestId = UUID.randomUUID().toString(),
                            bytes = bytes,
                            validForMillis = 1000,
                        ),
                        now.get(),
                        1000,
                    )
                }
            }
        val gate = LodCreditGate(control, emptySet(), now::get, entitled = true)
        try {
            val reservation = executor.submit<LodSendPermit?> { gate.reserve(player, 1000) }
            check(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
            executor
                .submit { gate.remove(player) }
                .get(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            assertEquals(0, gate.trackedPlayers())
            release.countDown()
            assertNull(reservation.get(1, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, gate.trackedPlayers())
            assertNotNull(gate.reserve(player, 1000))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `not ready cache can become ready after more than two minutes without reinstall`() {
        val root = Files.createTempDirectory("lod-entitled-long-ready-")
        try {
            val f = LodFixture(root.resolve("input"))
            val now = AtomicLong(1)
            val calls = AtomicInteger()
            val delegate = f.control(AtomicInteger())
            val control =
                object : LodControl by delegate {
                    override fun source(player: UUID): LodLease<LodSourceGrant> {
                        if (calls.incrementAndGet() <= 8) throw LodUnavailable()
                        return delegate.source(player)
                    }
                }
            LodCache(
                    control,
                    f.expectation,
                    root.resolve("cache"),
                    entitled = true,
                    nanos = now::get,
                )
                .use { cache ->
                    for (delay in listOf(1L, 2, 4, 8, 16, 32, 60, 60)) {
                        assertFails { cache.load(f.player).get() }
                        now.addAndGet(delay * 1000000000)
                    }
                    assertTrue(now.get() > 127000000000)
                    assertEquals(1, cache.load(f.player).get().columnCount)
                    assertEquals(9, calls.get())
                }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `denied and wrong world peers spend no source or credit before live grant`() {
        val root = Files.createTempDirectory("lod-entitled-access-")
        try {
            val f = LodFixture(root.resolve("input"))
            val sources = AtomicInteger()
            val credits = AtomicInteger()
            val now = AtomicLong(1)
            val delegate = f.control(AtomicInteger())
            val control =
                object : LodControl by delegate {
                    override fun source(player: UUID): LodLease<LodSourceGrant> {
                        sources.incrementAndGet()
                        return delegate.source(player)
                    }

                    override fun payload(player: UUID, bytes: Int): LodLease<LodPayloadGrant> {
                        credits.incrementAndGet()
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
            LodCache(
                    control,
                    f.expectation,
                    root.resolve("cache"),
                    entitled = true,
                    nanos = now::get,
                )
                .use { cache ->
                    val gate = LodCreditGate(control, emptySet(), now::get, entitled = true)
                    LodSessionEngine(
                            cache,
                            gate,
                            f.expectation,
                            emptySet(),
                            42,
                            now::get,
                            entitled = true,
                        )
                        .use { engine ->
                            val denied =
                                Peer(
                                        UUID.randomUUID(),
                                        LodBinding(
                                            Any(),
                                            f.expectation.mapSha256,
                                            f.expectation.sourceSha256,
                                        ),
                                    )
                                    .apply { allowed = false }
                            val wrong =
                                Peer(
                                    UUID.randomUUID(),
                                    LodBinding(Any(), "f".repeat(64), f.expectation.sourceSha256),
                                )
                            for (peer in listOf(denied, wrong)) {
                                engine.receive(peer, Lss20.HANDSHAKE, byteArrayOf(20, 3))
                                engine.receive(peer, Lss20.CLIENT_INFO, byteArrayOf(42))
                            }
                            repeat(20) { engine.pump() }
                            assertEquals(0, sources.get())
                            assertEquals(0, credits.get())
                            denied.allowed = true
                            // Initial denial retains no negotiation state; declare after access
                            // exists.
                            engine.receive(denied, Lss20.HANDSHAKE, byteArrayOf(20, 3))
                            engine.receive(denied, Lss20.CLIENT_INFO, byteArrayOf(42))
                            var frame: LodDelivery? = null
                            for (attempt in 1..100) {
                                engine.pump()
                                val candidate = denied.frames.removeFirstOrNull()
                                if (candidate != null) {
                                    if (candidate.reply.body[1].toInt() == 1) {
                                        frame = candidate
                                        break
                                    }
                                    candidate.complete(candidate.authorize())
                                }
                                Thread.sleep(2)
                            }
                            val enabled = assertNotNull(frame)
                            denied.allowed = false
                            assertFalse(enabled.authorize())
                            enabled.drop()
                            assertEquals(1, sources.get())
                            engine.pump()
                            assertTrue(denied.frames.isEmpty()) // enable never reached the writer
                            denied.allowed = true
                            engine.pump()
                            val regranted = denied.frames.removeFirst()
                            assertEquals(1, regranted.reply.body[1].toInt())
                            assertTrue(regranted.authorize())
                            regranted.complete(true)
                            engine.receive(
                                denied,
                                Lss20.BATCH_REQUEST,
                                dev.vox.lss.common.wire.WireBytes.Writer(17)
                                    .writeVarInt(1)
                                    .writeLong(0)
                                    .writeLong(0)
                                    .toByteArray(),
                            )
                            engine.pump()
                            val terrain = denied.frames.removeFirst()
                            assertEquals(Lss20.VOXEL, terrain.reply.channel)
                            denied.allowed = false
                            assertFalse(terrain.authorize())
                            terrain.drop()
                        }
                }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
