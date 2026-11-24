package gg.grounds.lod.runtime

import com.github.luben.zstd.Zstd
import gg.grounds.lod.format.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

internal class LodFixture(private val root: Path, registrySha256: String = "c".repeat(64)) {
    val player: UUID = UUID.randomUUID()
    val expectation = LodExpectation("a".repeat(64), "b".repeat(64), registrySha256, "d".repeat(64))
    val archive: Path
    val grant: LodSourceGrant

    init {
        val raw =
            ByteArrayOutputStream()
                .also { bytes ->
                    DataOutputStream(bytes).use {
                        it.write("GLODT001".toByteArray())
                        it.writeInt(0)
                        it.writeInt(0)
                        it.writeInt(1)
                        it.writeInt(0)
                        it.writeInt(0)
                        it.writeInt(2)
                        it.write(byteArrayOf(0, 0))
                    }
                }
                .toByteArray()
        val compressed = Zstd.compress(raw, 1)
        Files.createDirectories(root.resolve("tiles"))
        Files.write(root.resolve("tiles/0.0.tile.zst"), compressed)
        val manifest =
            LodManifest(
                generatorSha256 = expectation.generatorSha256,
                sourceBundleSha256 = expectation.sourceSha256,
                registrySha256 = expectation.registrySha256,
                columns = 1,
                sizeBytes = compressed.size.toLong(),
                columnBytes = 2,
                tiles =
                    listOf(
                        LodTile(
                            0,
                            0,
                            "tiles/0.0.tile.zst",
                            digest(compressed),
                            compressed.size.toLong(),
                            raw.size.toLong(),
                            1,
                        )
                    ),
            )
        val manifestBytes = LodJson.write(manifest)
        Files.write(root.resolve("manifest.json"), manifestBytes)
        archive = root.resolve("fixture.zip")
        LodBundle.pack(root, archive)
        val run = UUID.randomUUID()
        val request = UUID.randomUUID()
        grant =
            LodSourceGrant(
                runId = run.toString(),
                requestId = request.toString(),
                mapBundleSha256 = expectation.mapSha256,
                sourceBundleSha256 = expectation.sourceSha256,
                registrySha256 = expectation.registrySha256,
                generatorSha256 = expectation.generatorSha256,
                manifestSha256 = digest(manifestBytes),
                bundleSha256 = digest(archive),
                bundleBytes = Files.size(archive),
                contentTimestampMillis = 1794146400123,
                bundleUrl = "/v1/maps/lod-pilots/$run/source/$request",
                validForMillis = 120000,
            )
    }

    fun control(downloads: AtomicInteger, grant: LodSourceGrant = this.grant) =
        object : LodControl {
            override fun source(player: UUID) = LodLease(grant, System.nanoTime(), 120000)

            override fun payload(player: UUID, bytes: Int): LodLease<LodPayloadGrant> =
                error("not used")

            override fun download(lease: LodLease<LodSourceGrant>, into: Path) {
                downloads.incrementAndGet()
                Files.copy(archive, into)
            }
        }
}

class LodCacheTest {
    @Test
    fun `parallel players share one fetch and verified disk survives another cache instance`() {
        val root = Files.createTempDirectory("lod-cache-")
        try {
            val fixture = LodFixture(root.resolve("input"))
            val downloads = AtomicInteger()
            val cacheDir = root.resolve("cache")
            val pool = Executors.newFixedThreadPool(5)
            try {
                LodCache(fixture.control(downloads), fixture.expectation, cacheDir).use { cache ->
                    val futures =
                        (1..5).map { pool.submit<LoadedLod> { cache.load(fixture.player).get() } }
                    val loaded = futures.map { it.get() }
                    assertTrue(loaded.all { it === loaded.first() })
                    assertEquals(1794146400, loaded.first().timestampSeconds)
                    assertEquals(1, loaded.first().columns.size)
                    assertContentEquals(
                        byteArrayOf(0, 0),
                        Zstd.decompress(loaded.first().columns.values.first(), 2),
                    )
                }
                LodCache(fixture.control(downloads), fixture.expectation, cacheDir).use {
                    it.load(fixture.player).get()
                }
                assertEquals(1, downloads.get())
            } finally {
                pool.shutdownNow()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `wrong source and corrupt body cannot become a usable cache`() {
        val root = Files.createTempDirectory("lod-cache-")
        try {
            val fixture = LodFixture(root.resolve("input"))
            val downloads = AtomicInteger()
            LodCache(
                    fixture.control(
                        downloads,
                        fixture.grant.copy(sourceBundleSha256 = "f".repeat(64)),
                    ),
                    fixture.expectation,
                    root.resolve("wrong"),
                )
                .use { assertFails { it.load(fixture.player).get() } }
            assertEquals(0, downloads.get())
            Files.write(fixture.archive, byteArrayOf(1, 2, 3))
            LodCache(fixture.control(downloads), fixture.expectation, root.resolve("corrupt")).use {
                assertFails { it.load(fixture.player).get() }
                assertFails { it.load(fixture.player).get() }
            }
            assertEquals(1, downloads.get()) // no implicit retry of failed source initialization
            assertFalse(
                Files.walk(root.resolve("corrupt")).use { files ->
                    files.anyMatch { it.fileName.toString().endsWith(".part") }
                }
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `restart recovers abandoned fixed staging before cache initialization`() {
        val root = Files.createTempDirectory("lod-cache-recover-")
        try {
            val f = LodFixture(root.resolve("input"))
            val cacheDir = Files.createDirectories(root.resolve("cache"))
            Files.write(cacheDir.resolve("terrain.part"), ByteArray(4096))
            LodCache(f.control(AtomicInteger()), f.expectation, cacheDir).use {
                it.load(f.player).get()
            }
            assertFalse(Files.exists(cacheDir.resolve("terrain.part")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `another process cannot download into a cache with an active owner`() {
        val root = Files.createTempDirectory("lod-cache-owner-")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val attempts = AtomicInteger()
        try {
            val f = LodFixture(root.resolve("input"))
            val delegate = f.control(AtomicInteger())
            val control =
                object : LodControl by delegate {
                    override fun download(lease: LodLease<LodSourceGrant>, into: Path) {
                        attempts.incrementAndGet()
                        entered.countDown()
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        delegate.download(lease, into)
                    }
                }
            LodCache(control, f.expectation, root.resolve("cache")).use { first ->
                LodCache(control, f.expectation, root.resolve("cache")).use { second ->
                    val running = first.load(f.player)
                    check(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    try {
                        assertFailsWith<java.util.concurrent.ExecutionException> {
                            second.load(f.player).get(1, java.util.concurrent.TimeUnit.SECONDS)
                        }
                        assertEquals(1, attempts.get())
                    } finally {
                        release.countDown()
                        running.get()
                    }
                }
            }
        } finally {
            release.countDown()
            root.toFile().deleteRecursively()
        }
    }
}

class LodCreditGateTest {
    @Test
    fun `credits expire cannot be reused after failure and never authorize another UUID`() {
        val player = UUID.randomUUID()
        val now = AtomicLong(1)
        val calls = AtomicInteger()
        var closed = false
        val control =
            object : LodControl {
                override fun source(player: UUID): LodLease<LodSourceGrant> = error("not used")

                override fun download(lease: LodLease<LodSourceGrant>, into: Path) =
                    error("not used")

                override fun payload(player: UUID, bytes: Int): LodLease<LodPayloadGrant> {
                    calls.incrementAndGet()
                    if (closed) throw LodUnavailable(true)
                    return LodLease(
                        LodPayloadGrant(
                            runId = UUID.randomUUID().toString(),
                            requestId = UUID.randomUUID().toString(),
                            bytes = bytes,
                            validForMillis = 100,
                        ),
                        now.get(),
                        100,
                    )
                }
            }
        val gate = LodCreditGate(control, setOf(player), now::get)
        val first = assertNotNull(gate.reserve(player, 1024))
        assertTrue(first.valid(now.get()))
        assertNull(gate.reserve(UUID.randomUUID(), 1024))
        now.addAndGet(101000000)
        assertFalse(first.valid(now.get()))
        assertNotNull(gate.reserve(player, 1024))
        assertEquals(2, calls.get())
        now.addAndGet(101000000)
        closed = true
        assertNull(gate.reserve(player, 1024))
        now.addAndGet(1000000000)
        assertNull(gate.reserve(player, 1024))
        assertEquals(3, calls.get())
    }

    @Test
    fun `unavailable controller never creates a self perpetuating retry loop`() {
        val player = UUID.randomUUID()
        val now = AtomicLong(1)
        val calls = AtomicInteger()
        val control =
            object : LodControl {
                override fun source(player: UUID): LodLease<LodSourceGrant> = error("unused")

                override fun download(lease: LodLease<LodSourceGrant>, into: Path) = error("unused")

                override fun payload(player: UUID, bytes: Int): LodLease<LodPayloadGrant> {
                    calls.incrementAndGet()
                    throw LodUnavailable()
                }
            }
        val gate = LodCreditGate(control, setOf(player), now::get)
        repeat(100) {
            assertNull(gate.reserve(player, 1024))
            now.addAndGet(1000000000)
        }
        assertEquals(1, calls.get())
    }
}
