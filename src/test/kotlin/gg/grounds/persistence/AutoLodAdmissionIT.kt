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
class AutoLodAdmissionIT {
    @Inject lateinit var db: DataSource
    @Inject lateinit var lods: MapLodRepository
    @Inject lateinit var maps: MapRepository
    @Inject lateinit var versions: MapVersionRepository
    @Inject lateinit var pins: MapPinRepository
    @Inject lateinit var lits: MapLitRepository
    private val now = Instant.parse("2026-11-10T12:00:00Z")
    private val settings =
        AutoLodSelection(
            "stage",
            MapAddress("lobby", "mainlobby"),
            "stage-lobby-auto-v1",
            "c".repeat(64),
            "d".repeat(64),
            "ghcr.io/test/worker@sha256:${"e".repeat(64)}",
        )
    private lateinit var map: MapRecord

    @BeforeEach
    fun clear() {
        db.connection.use { c ->
            c.createStatement().use { it.execute("TRUNCATE map,map_lit,map_lod_budget CASCADE") }
        }
        map =
            maps.create(
                settings.address,
                "Lobby",
                MapKind.LOBBY,
                false,
                MapTrust.FIRST_PARTY,
                "staff",
            )
    }

    private fun publish(version: Int, format: Int = 2, pin: Boolean = true): String {
        versions.commit(map.id, null, null, null, null, "staff")
        val sha = "%064x".format(version)
        versions.publish(map.id, version, BundleFacts(sha, null, 100, null, null), "staff")
        if (pin) pins.move("stage", map.id, version, "staff")
        val attempt = UUID.randomUUID()
        lits.start(sha, format, attempt)
        lits.succeed(sha, format, attempt, LitFacts("%064x".format(version + 100), 50, 1, 1, 1))
        return sha
    }

    @Test
    fun `automatic admission waits for format two and follows current pin rather than latest version`() {
        val sha = publish(1, format = 1)
        assertNull(lods.automaticRun(settings, now))
        val attempt = UUID.randomUUID()
        lits.start(sha, 2, attempt)
        assertNull(lods.automaticRun(settings, now))
        lits.succeed(sha, 2, attempt, LitFacts("b".repeat(64), 50, 1, 1, 1))
        publish(2, pin = false)
        val run = lods.automaticRun(settings, now)
        assertNotNull(run, "a published pinned first-party format two map must be admitted")
        val claim = lods.claim(run!!, now)!!
        assertEquals(sha, claim.mapBundleSha256)
        assertEquals("b".repeat(64), claim.sourceBundleSha256)
        assertEquals(settings.workerImage, claim.workerImage)
    }

    @Test
    fun `concurrent replicas admit one persistent selection and failed identity is never retried`() {
        publish(1)
        val pool = Executors.newFixedThreadPool(8)
        val ids =
            try {
                pool
                    .invokeAll(
                        (1..8).map {
                            Callable { PostgresMapLodRepository(db).automaticRun(settings, now) }
                        }
                    )
                    .map { it.get() }
            } finally {
                pool.shutdownNow()
            }
        assertEquals(1, ids.toSet().size)
        assertNotNull(ids.first())
        val run = ids.first()!!
        val claim = lods.claim(run, now)!!
        assertTrue(lods.failWhenStopped(claim.attempt, "worker failed") { true })
        assertEquals(
            run,
            PostgresMapLodRepository(db).automaticRun(settings, now.plusSeconds(3600)),
        )
        assertNull(lods.claim(run, now.plusSeconds(3600)))
        assertEquals(1, lods.budget(settings.budgetId)!!.jobsReserved)
        db.connection.use { c ->
            c.createStatement().use {
                it.executeQuery("SELECT count(*) FROM map_lod_run").use { r ->
                    r.next()
                    assertEquals(1, r.getInt(1))
                }
            }
        }
    }

    @Test
    fun `ready output reuses the same run and new map pushes retain fixed shared budget`() {
        publish(1)
        val first = lods.automaticRun(settings, now)!!
        val claim = lods.claim(first, now)!!
        assertTrue(
            lods.succeed(claim.attempt, LodFacts("1".repeat(64), 100, "2".repeat(64), 50, 1)) {
                now
            }
        )
        assertEquals(
            first,
            PostgresMapLodRepository(db).automaticRun(settings, now.plusSeconds(3600)),
        )
        assertNull(lods.claim(first, now))
        publish(2)
        val second = lods.automaticRun(settings, now.plusSeconds(1))!!
        val next = lods.claim(second, now.plusSeconds(1))!!
        lods.failWhenStopped(next.attempt, "failed") { true }
        publish(3)
        val third = lods.automaticRun(settings, now.plusSeconds(2))!!
        assertNull(lods.claim(third, now.plusSeconds(2)))
        assertEquals(2, lods.budget(settings.budgetId)!!.jobsReserved)
        assertEquals(
            2 * PostgresMapLodRepository.RESERVATION_BYTES,
            lods.budget(settings.budgetId)!!.bytesReserved,
        )
    }

    @Test
    fun `automatic reuse of existing private ready output records explicit budget provenance without reserving again`() {
        val sha = publish(1)
        val manual =
            LodRun(
                UUID.randomUUID(),
                "earlier-pilot",
                "stage",
                settings.address,
                1,
                sha,
                "%064x".format(101),
                settings.registrySha256,
                settings.generatorSha256,
                settings.workerImage,
                now,
                now.plusSeconds(1800),
                "staff",
            )
        lods.createRun(manual)
        val claim = lods.claim(manual.id, now)!!
        lods.succeed(claim.attempt, LodFacts("1".repeat(64), 100, "2".repeat(64), 50, 1)) { now }
        assertEquals(1, lods.ready().size, "ordinary manual artifacts remain publishable")
        assertEquals(manual.id, lods.automaticRun(settings, now))
        assertTrue(
            lods.ready().isEmpty(),
            "automatic adoption must keep private output out of public publication",
        )
        assertEquals(1, lods.budget("earlier-pilot")!!.jobsReserved)
        assertEquals(0, lods.budget(settings.budgetId)!!.jobsReserved)
        db.connection.use { c ->
            c.prepareStatement("SELECT budget_id FROM map_lod_auto_selection WHERE run_id=?").use {
                ps ->
                ps.setObject(1, manual.id)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(settings.budgetId, rs.getString(1))
                }
            }
        }
    }

    @Test
    fun `same content repush keeps the one selection claimable against the current pin`() {
        val sha = publish(1)
        val run = lods.automaticRun(settings, now)!!
        versions.commit(map.id, null, null, null, null, "staff")
        versions.publish(map.id, 2, BundleFacts(sha, null, 100, null, null), "staff")
        pins.move("stage", map.id, 2, "staff")
        assertEquals(run, lods.automaticRun(settings, now.plusSeconds(1)))
        assertNotNull(
            lods.claim(run, now.plusSeconds(1)),
            "identical pinned canonical and prepared bytes remain the selected input",
        )
    }

    @Test
    fun `restart after an hour resumes one unclaimed automatic selection without refreshing its audit or quotas`() {
        publish(1)
        val run = lods.automaticRun(settings, now)!!
        val restarted = PostgresMapLodRepository(db)
        val later = now.plusSeconds(3600)
        assertEquals(run, restarted.automaticRun(settings, later))
        val claim = restarted.claim(run, later)
        assertNotNull(
            claim,
            "an automatic selection must recover after an outage before the first claim",
        )
        assertEquals(later, claim!!.startedAt)
        assertNull(restarted.claim(run, later))
        assertEquals(1, restarted.budget(settings.budgetId)!!.jobsReserved)
        db.connection.use { c ->
            c.prepareStatement("SELECT created_at,expires_at FROM map_lod_run WHERE id=?").use { ps
                ->
                ps.setObject(1, run)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(now, rs.getTimestamp(1).toInstant())
                    assertEquals(now.plusSeconds(1800), rs.getTimestamp(2).toInstant())
                }
            }
        }
        assertFalse(
            restarted.activateIfCurrent(claim.attempt, { later.plusSeconds(901) }) {
                fail("worker deadline extended")
            }
        )
    }

    @Test
    fun `adoption excludes historical public projection without rewriting its published marker`() {
        val sha = publish(1)
        val manual =
            LodRun(
                UUID.randomUUID(),
                "earlier-pilot",
                "stage",
                settings.address,
                1,
                sha,
                "%064x".format(101),
                settings.registrySha256,
                settings.generatorSha256,
                settings.workerImage,
                now,
                now.plusSeconds(1800),
                "staff",
            )
        lods.createRun(manual)
        val claim = lods.claim(manual.id, now)!!
        lods.succeed(claim.attempt, LodFacts("1".repeat(64), 100, "2".repeat(64), 50, 1)) { now }
        lods.markPublished(claim.attempt)
        assertEquals(1, lods.projectionPending().size)
        assertEquals(manual.id, lods.automaticRun(settings, now))
        assertTrue(lods.projectionPending().isEmpty())
        assertTrue(
            lods.published().single().published,
            "historical publication is never reset to private",
        )
        assertEquals(0, lods.budget(settings.budgetId)!!.jobsReserved)
    }

    @Test
    fun `a changed trust or prepared lighting state fences an already selected automatic run`() {
        publish(1)
        val run = lods.automaticRun(settings, now)!!
        db.connection.use { c ->
            c.createStatement().use { it.executeUpdate("UPDATE map SET trust='UNTRUSTED'") }
        }
        assertNull(
            lods.claim(run, now),
            "automatic runs require first-party trust again when claiming",
        )
        db.connection.use { c ->
            c.createStatement().use {
                it.executeUpdate("UPDATE map SET trust='FIRST_PARTY'")
                it.executeUpdate("UPDATE map_lit SET state='FAILED' WHERE light_format=2")
            }
        }
        assertNull(lods.claim(run, now))
        assertEquals(0, lods.budget(settings.budgetId)!!.jobsReserved)
    }

    @Test
    fun `automatic selection is restricted to stage lobby mainlobby and first party inputs`() {
        assertThrows(IllegalArgumentException::class.java) {
            settings.copy(environment = "production")
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings.copy(address = MapAddress("lobby", "other"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings.copy(workerImage = "ghcr.io/test/worker:latest")
        }
        publish(1)
        db.connection.use { c ->
            c.createStatement().use { it.executeUpdate("UPDATE map SET trust='UNTRUSTED'") }
        }
        assertNull(lods.automaticRun(settings, now))
        assertNull(lods.budget(settings.budgetId))
    }
}
