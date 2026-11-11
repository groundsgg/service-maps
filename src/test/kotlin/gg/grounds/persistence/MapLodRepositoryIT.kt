package gg.grounds.persistence

import gg.grounds.PostgresResource
import gg.grounds.domain.*
import io.quarkus.test.common.TestResourceScope
import io.quarkus.test.common.WithTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
@WithTestResource(value = PostgresResource::class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class MapLodRepositoryIT {
    @Inject lateinit var db: DataSource
    @Inject lateinit var lods: MapLodRepository
    @Inject lateinit var maps: MapRepository
    @Inject lateinit var versions: MapVersionRepository
    @Inject lateinit var pins: MapPinRepository
    @Inject lateinit var lits: MapLitRepository
    private val now = Instant.parse("2026-11-08T12:00:00Z")

    @BeforeEach
    fun clear() {
        db.connection.use { c ->
            c.createStatement().use {
                it.execute(
                    "TRUNCATE map_lod_auto_selection,map_lod_serving_debit,map_lod_serving_window,map_lod_serving_run,map_lod,map_lod_run,map_lod_budget"
                )
            }
        }
    }

    private fun input(): Pair<MapAddress, String> {
        val address = MapAddress("lod-test", "map-${UUID.randomUUID()}")
        val map = maps.create(address, "LOD", MapKind.ARENA, false, MapTrust.FIRST_PARTY, "staff")
        versions.commit(map.id, null, null, null, null, "staff")
        val sha = "%064x".format(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE)
        versions.publish(map.id, 1, BundleFacts(sha, null, 100, null, null), "staff")
        pins.move("stage", map.id, 1, "staff")
        val attempt = UUID.randomUUID()
        lits.start(sha, 1, attempt)
        lits.succeed(sha, 1, attempt, LitFacts("b".repeat(64), 50, 1, 1, 1))
        return address to sha
    }

    private fun run(input: Pair<MapAddress, String> = input()): LodRun =
        LodRun(
            id = UUID.randomUUID(),
            budgetId = "pilot",
            environment = "stage",
            address = input.first,
            version = 1,
            mapBundleSha256 = input.second,
            sourceBundleSha256 = "b".repeat(64),
            registrySha256 = "c".repeat(64),
            generatorSha256 = "d".repeat(64),
            workerImage = "ghcr.io/groundsgg/service-maps@sha256:${"e".repeat(64)}",
            createdAt = now,
            expiresAt = now.plusSeconds(1800),
            createdBy = "staff",
        )

    @Test
    fun `progress is read only and exact across canonical source registry generator and trust`() {
        val run = run().also(lods::createRun)
        val claim = lods.claim(run.id, now)!!
        val reserved = lods.budget(run.budgetId)
        fun read(
            trust: MapTrust = MapTrust.FIRST_PARTY,
            map: String = run.mapBundleSha256,
            source: String = run.sourceBundleSha256,
            registry: String = run.registrySha256,
            generator: String = run.generatorSha256,
        ) = lods.progress(trust, map, source, registry, generator)
        assertEquals("GENERATING", read()!!.state)
        assertNull(read(trust = MapTrust.UNTRUSTED))
        assertNull(read(map = "f".repeat(64)))
        assertNull(read(source = "f".repeat(64)))
        assertNull(read(registry = "f".repeat(64)))
        assertNull(read(generator = "f".repeat(64)))
        assertTrue(
            lods.succeed(claim.attempt, LodFacts("1".repeat(64), 100, "2".repeat(64), 50, 1)) {
                now
            }
        )
        assertEquals(LodProgress("READY", 1, 100), read())
        assertEquals(reserved, lods.budget(run.budgetId))
    }

    @Test
    fun `selected format two source is admitted beside the older lit transport`() {
        val input = input()
        val source = "f".repeat(64)
        val attempt = UUID.randomUUID()
        lits.start(input.second, 2, attempt)
        lits.succeed(input.second, 2, attempt, LitFacts(source, 50, 1, 1, 1))
        val selected = run(input).copy(sourceBundleSha256 = source)
        lods.createRun(selected)

        val claim = lods.claim(selected.id, now)
        assertNotNull(claim, "the selected prepared format two transport must be admissible")
        assertEquals(source, claim!!.sourceBundleSha256)
        assertEquals(input.second, claim.mapBundleSha256)
        assertEquals(1, lods.budget("pilot")!!.jobsReserved)
    }

    @Test
    fun `concurrent reconcilers can claim only one globally active job`() {
        val runs = (1..8).map { run().also(lods::createRun) }
        val pool = Executors.newFixedThreadPool(8)
        val claims =
            try {
                pool.invokeAll(runs.map { r -> Callable { lods.claim(r.id, now) } }).mapNotNull {
                    it.get()
                }
            } finally {
                pool.shutdownNow()
            }
        assertEquals(1, claims.size)
        assertEquals(claims.single().attempt, lods.active()!!.attempt)
        assertEquals(1, lods.budget("pilot")!!.jobsReserved)
    }

    @Test
    fun `failure cannot retry automatically and a restart cannot reset campaign reservations`() {
        val first = run().also(lods::createRun)
        val claim = lods.claim(first.id, now)!!
        assertTrue(lods.failWhenStopped(claim.attempt, "test") { true })
        assertNull(lods.claim(first.id, now.plusSeconds(1)))
        val second = run().also(lods::createRun)
        val next = lods.claim(second.id, now)!!
        lods.failWhenStopped(next.attempt, "test") { true }
        val third = run().also(lods::createRun)
        assertNull(PostgresMapLodRepository(db).claim(third.id, now))
        assertEquals(2, lods.budget("pilot")!!.jobsReserved)
        assertTrue(lods.budget("pilot")!!.bytesReserved <= 1024L * 1024 * 1024)
    }

    @Test
    fun `wrong map source unlit changed pin and expired run cannot start jobs`() {
        val candidate = run()
        for (r in
            listOf(
                candidate.copy(id = UUID.randomUUID(), sourceBundleSha256 = "f".repeat(64)),
                candidate.copy(id = UUID.randomUUID(), version = 2),
                candidate.copy(id = UUID.randomUUID(), mapBundleSha256 = "f".repeat(64)),
            )) {
            lods.createRun(r)
            assertNull(lods.claim(r.id, now))
        }
        lods.createRun(candidate)
        assertNull(lods.claim(candidate.id, candidate.expiresAt))
        assertEquals(0, lods.budget("pilot")!!.jobsReserved)
    }

    @Test
    fun `stale attempt cannot publish and ready identity is reused without another job`() {
        val selection = input()
        val run = run(selection).also(lods::createRun)
        val claim = lods.claim(run.id, now)!!
        val facts = LodFacts("1".repeat(64), 100, "2".repeat(64), 50, 1)
        assertFalse(lods.succeed(UUID.randomUUID(), facts) { now })
        assertTrue(lods.succeed(claim.attempt, facts) { now })
        assertFalse(lods.failWhenStopped(claim.attempt, "late") { true })
        val duplicate = run(selection).also(lods::createRun)
        assertNull(lods.claim(duplicate.id, now.plusSeconds(1)))
        assertEquals(1, lods.budget("pilot")!!.jobsReserved)
        assertEquals(claim.startedAt.toEpochMilli(), lods.ready().single().contentTimestampMillis)
        assertTrue(lods.published().isEmpty())
        lods.markPublished(claim.attempt)
        assertEquals(selection.second, lods.published().single().mapBundleSha256)
    }

    @Test
    fun `activation and stop transition share a fence and old claims cannot resume`() {
        val r = run().also(lods::createRun)
        val c = lods.claim(r.id, now)!!
        val activated = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val stopEntered = java.util.concurrent.CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val starting =
                pool.submit<Boolean> {
                    lods.activateIfCurrent(c.attempt, { now }) {
                        activated.countDown()
                        assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    }
                }
            assertTrue(activated.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val stopping =
                pool.submit<Boolean> {
                    lods.failWhenStopped(c.attempt, "stop") {
                        stopEntered.countDown()
                        true
                    }
                }
            assertFalse(stopEntered.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(starting.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(stopping.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(
                lods.activateIfCurrent(c.attempt, { now }) { fail("stale attempt activated") }
            )
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `expired claims cannot activate or accept even when caller had an earlier snapshot`() {
        val r = run().also(lods::createRun)
        val c = lods.claim(r.id, now)!!
        assertFalse(
            lods.activateIfCurrent(c.attempt, { now.plusSeconds(901) }) {
                fail("expired activation")
            }
        )
        assertFalse(
            lods.succeed(c.attempt, LodFacts("1".repeat(64), 100, "2".repeat(64), 50, 1)) {
                now.plusSeconds(901)
            }
        )
        assertNotNull(lods.active())
    }
}
