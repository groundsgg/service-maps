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
import kotlin.test.*
import org.junit.jupiter.api.BeforeEach

@QuarkusTest
@WithTestResource(value = PostgresResource::class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class LodServingRepositoryIT {
    @Inject lateinit var db: DataSource
    @Inject lateinit var quotas: LodServingRepository
    private val now = Instant.parse("2026-11-08T14:00:00Z")
    private val mapSha = "a".repeat(64)
    private val players = (1..5).map { UUID.randomUUID() }.toSet()

    @BeforeEach
    fun clear() {
        db.connection.use { c ->
            c.createStatement().use {
                it.execute(
                    "TRUNCATE map_lod_serving_debit,map_lod_serving_window,map_lod_serving_run,map_lod,map_lod_run,map_lod_budget"
                )
            }
        }
    }

    private fun servingRun(): LodServingRun {
        val generation = UUID.randomUUID()
        val attempt = UUID.randomUUID()
        db.connection.use { c ->
            c.createStatement().use { sql ->
                sql.execute("INSERT INTO map_lod_budget(id) VALUES ('serving-test')")
                sql.execute(
                    """INSERT INTO map_lod_run(id,budget_id,environment,namespace,name,version,map_bundle_sha256,source_bundle_sha256,
                registry_sha256,generator_sha256,worker_image,created_at,expires_at,created_by) VALUES
                ('$generation','serving-test','stage','lobby','test',1,'$mapSha','${"b".repeat(64)}','${"c".repeat(64)}','${"d".repeat(64)}',
                'ghcr.io/test@sha256:${"e".repeat(64)}','$now','${now.plusSeconds(1800)}','fixture')"""
                )
                sql.execute(
                    """INSERT INTO map_lod(attempt,run_id,identity_sha256,trust,map_bundle_sha256,source_bundle_sha256,registry_sha256,generator_sha256,
                worker_image,state,started_at,updated_at,bundle_sha256,bundle_bytes,manifest_sha256,manifest_bytes,columns) VALUES
                ('$attempt','$generation','${"f".repeat(64)}','FIRST_PARTY','$mapSha','${"b".repeat(64)}','${"c".repeat(64)}','${"d".repeat(64)}',
                'ghcr.io/test@sha256:${"e".repeat(64)}','READY','$now','$now','${"1".repeat(64)}',4096,'${"2".repeat(64)}',256,1)"""
                )
            }
        }
        return LodServingRun(
                UUID.randomUUID(),
                attempt,
                "3".repeat(64),
                players,
                now,
                now.plusSeconds(1800),
                "operator",
            )
            .also(quotas::register)
    }

    @Test
    fun `two proxies cannot multiply a player's one second credits`() {
        val run = servingRun()
        val player = players.first()
        val pool = Executors.newFixedThreadPool(8)
        val grants =
            try {
                pool
                    .invokeAll(
                        (1..8).map {
                            Callable {
                                runCatching {
                                        PostgresLodServingRepository(db)
                                            .payload(
                                                run.id,
                                                UUID.randomUUID(),
                                                mapSha,
                                                player,
                                                256 * 1024,
                                                { now },
                                            )
                                    }
                                    .getOrNull()
                            }
                        }
                    )
                    .mapNotNull { it.get() }
            } finally {
                pool.shutdownNow()
            }
        assertEquals(2, grants.size)
        assertEquals(512 * 1024, grants.sumOf { it.bytes })
        assertEquals(512L * 1024, quotas.find(run.id)!!.payloadReserved)
    }

    @Test
    fun `duplicate responses rejoin and repository restart cannot replay granted credits`() {
        val run = servingRun()
        val player = players.first()
        val request = UUID.randomUUID()
        assertEquals(
            256 * 1024,
            quotas.payload(run.id, request, mapSha, player, 256 * 1024, { now }).bytes,
        )
        val restarted = PostgresLodServingRepository(db)
        assertEquals(
            LodServingRefusal.DUPLICATE,
            assertFailsWith<LodServingDenied> {
                    restarted.payload(run.id, request, mapSha, player, 256 * 1024, { now })
                }
                .reason,
        )
        restarted.payload(run.id, UUID.randomUUID(), mapSha, player, 256 * 1024, { now })
        assertEquals(
            LodServingRefusal.RATE,
            assertFailsWith<LodServingDenied> {
                    restarted.payload(
                        run.id,
                        UUID.randomUUID(),
                        mapSha,
                        player,
                        256 * 1024,
                        { now },
                    )
                }
                .reason,
        )
        assertEquals(512L * 1024, restarted.find(run.id)!!.payloadReserved)
    }

    @Test
    fun `global byte reservation remains bounded under competing players`() {
        val run = servingRun()
        db.connection.use { c ->
            c.createStatement().use {
                it.execute(
                    "UPDATE map_lod_serving_run SET payload_reserved=1073741824-262144 WHERE id='${run.id}'"
                )
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        val results =
            try {
                pool
                    .invokeAll(
                        players.take(2).map { p ->
                            Callable {
                                runCatching {
                                        quotas.payload(
                                            run.id,
                                            UUID.randomUUID(),
                                            mapSha,
                                            p,
                                            256 * 1024,
                                            { now },
                                        )
                                    }
                                    .isSuccess
                            }
                        }
                    )
                    .count { it.get() }
            } finally {
                pool.shutdownNow()
            }
        assertEquals(1, results)
        assertEquals(1024L * 1024 * 1024, quotas.find(run.id)!!.payloadReserved)
    }

    @Test
    fun `old credits cannot be carried into another window or after the run expires`() {
        val run = servingRun()
        val player = players.first()
        assertEquals(
            5,
            quotas
                .payload(run.id, UUID.randomUUID(), mapSha, player, 1024, { now.plusMillis(995) })
                .validForMillis,
        )
        assertEquals(
            5,
            quotas
                .payload(
                    run.id,
                    UUID.randomUUID(),
                    mapSha,
                    player,
                    1024,
                    { run.expiresAt.minusMillis(5) },
                )
                .validForMillis,
        )
        assertEquals(
            LodServingRefusal.CLOSED,
            assertFailsWith<LodServingDenied> {
                    quotas.payload(
                        run.id,
                        UUID.randomUUID(),
                        mapSha,
                        player,
                        1024,
                        { run.expiresAt },
                    )
                }
                .reason,
        )
        assertEquals(
            LodServingRefusal.CLOSED,
            assertFailsWith<LodServingDenied> {
                    quotas.payload(
                        run.id,
                        UUID.randomUUID(),
                        mapSha,
                        player,
                        1024,
                        { now.minusMillis(1) },
                    )
                }
                .reason,
        )
    }

    @Test
    fun `unknown players wrong maps and oversized allocations spend nothing`() {
        val run = servingRun()
        assertFailsWith<LodServingDenied> {
            quotas.payload(run.id, UUID.randomUUID(), mapSha, UUID.randomUUID(), 1024, { now })
        }
        assertFailsWith<LodServingDenied> {
            quotas.payload(
                run.id,
                UUID.randomUUID(),
                "f".repeat(64),
                players.first(),
                1024,
                { now },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            quotas.payload(
                run.id,
                UUID.randomUUID(),
                mapSha,
                players.first(),
                256 * 1024 + 1,
                { now },
            )
        }
        assertEquals(0, quotas.find(run.id)!!.payloadReserved)
        assertFailsWith<IllegalArgumentException> {
            run.copy(players = (players + UUID.randomUUID()))
        }
    }

    @Test
    fun `remote attempts and full expected source bytes are reserved before any fetch`() {
        val run = servingRun()
        db.connection.use { c ->
            c.createStatement().use {
                it.execute(
                    "UPDATE map_lod_serving_run SET remote_reads_reserved=9998,remote_bytes_reserved=1073741824-8192 WHERE id='${run.id}'"
                )
            }
        }
        val source = quotas.remote(run.id, UUID.randomUUID(), mapSha, players.first(), { now })
        assertEquals(4096, source.artifact.facts!!.bundleBytes)
        val restarted = PostgresLodServingRepository(db)
        assertFailsWith<LodServingDenied> {
            restarted.remote(run.id, UUID.randomUUID(), mapSha, players.first(), { now })
        }
        assertEquals(10000, restarted.find(run.id)!!.remoteReadsReserved)
        assertEquals(1024L * 1024 * 1024, restarted.find(run.id)!!.remoteBytesReserved)
    }

    @Test
    fun `a blocked reservation rechecks expiry after acquiring its database lock`() {
        val run = servingRun()
        val time = java.util.concurrent.atomic.AtomicReference(now)
        val clockRead = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        db.connection.use { c ->
            c.autoCommit = false
            c.createStatement().use {
                it.execute("SELECT 1 FROM map_lod_serving_run WHERE id='${run.id}' FOR UPDATE")
            }
            try {
                val pending =
                    pool.submit<LodPayloadCredit> {
                        entered.countDown()
                        quotas.payload(run.id, UUID.randomUUID(), mapSha, players.first(), 1024) {
                            clockRead.countDown()
                            time.get()
                        }
                    }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertFalse(clockRead.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                time.set(run.expiresAt)
                c.commit()
                val failure =
                    assertFailsWith<java.util.concurrent.ExecutionException> {
                        pending.get(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                assertEquals(LodServingRefusal.CLOSED, (failure.cause as LodServingDenied).reason)
            } finally {
                c.rollback()
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `tiny control requests cannot grow the debit ledger without a finite bound`() {
        val run = servingRun()
        db.connection.use { c ->
            c.createStatement().use {
                it.execute(
                    "UPDATE map_lod_serving_run SET payload_grants_reserved=8192 WHERE id='${run.id}'"
                )
            }
        }
        assertEquals(
            LodServingRefusal.BUDGET,
            assertFailsWith<LodServingDenied> {
                    quotas.payload(run.id, UUID.randomUUID(), mapSha, players.first(), 1) { now }
                }
                .reason,
        )
        assertEquals(0, quotas.find(run.id)!!.payloadReserved)
    }

    @Test
    fun `another serving run must be explicitly admitted after disabling the former one`() {
        val run = servingRun()
        assertFailsWith<java.sql.SQLException> { quotas.register(run.copy(id = UUID.randomUUID())) }
        db.connection.use { c ->
            c.createStatement().use {
                it.execute("UPDATE map_lod_serving_run SET enabled=false WHERE id='${run.id}'")
            }
        }
        val next = run.copy(id = UUID.randomUUID())
        quotas.register(next)
        assertFalse(quotas.find(run.id)!!.run.enabled)
        assertTrue(quotas.find(next.id)!!.run.enabled)
    }

    @Test
    fun `a source download is consumed once and expires independently of repeated metadata calls`() {
        val run = servingRun()
        val request = UUID.randomUUID()
        quotas.remote(run.id, request, mapSha, players.first()) { now }
        assertEquals(
            run.artifactAttempt,
            quotas.consumeRemote(run.id, request) { now.plusSeconds(1) }.artifact.attempt,
        )
        assertFailsWith<LodServingDenied> {
            quotas.consumeRemote(run.id, request) { now.plusSeconds(2) }
        }
        val expired = UUID.randomUUID()
        quotas.remote(run.id, expired, mapSha, players.first()) { now }
        assertEquals(
            LodServingRefusal.CLOSED,
            assertFailsWith<LodServingDenied> {
                    quotas.consumeRemote(run.id, expired) { now.plusSeconds(121) }
                }
                .reason,
        )
    }

    @Test
    fun `two controllers racing one download open only one origin lease`() {
        val run = servingRun()
        val request = UUID.randomUUID()
        quotas.remote(run.id, request, mapSha, players.first()) { now }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val winners =
                pool
                    .invokeAll(
                        (1..4).map {
                            Callable {
                                runCatching {
                                        PostgresLodServingRepository(db).consumeRemote(
                                            run.id,
                                            request,
                                        ) {
                                            now.plusSeconds(1)
                                        }
                                    }
                                    .isSuccess
                            }
                        }
                    )
                    .count { it.get() }
            assertEquals(1, winners)
            assertEquals(2, quotas.find(run.id)!!.remoteReadsReserved)
        } finally {
            pool.shutdownNow()
        }
    }
}
