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
class LodEntitledServingRepositoryIT {
    @Inject lateinit var db: DataSource
    @Inject lateinit var quotas: LodServingRepository
    @Inject lateinit var maps: MapRepository
    @Inject lateinit var versions: MapVersionRepository
    @Inject lateinit var pins: MapPinRepository
    @Inject lateinit var lits: MapLitRepository
    private val now = Instant.parse("2026-11-08T14:00:00Z")
    private val mapSha = "a".repeat(64)
    private val source = "b".repeat(64)
    private val registry = "c".repeat(64)
    private val generator = "d".repeat(64)
    private val budget = "entitled-test"
    private val generationBudget = "auto-test"
    private val tokenHash = "3".repeat(64)
    private lateinit var map: MapRecord

    @BeforeEach
    fun clear() {
        sql(
            "TRUNCATE map_lit,map_lod_auto_selection,map_lod_delivery_window,map_lod_delivery_global_window,map_lod_serving_debit,map_lod_serving_window,map_lod_serving_run,map_lod,map_lod_run,map_lod_budget,map_lod_delivery_budget"
        )
        db.connection.use { c -> c.createStatement().use { it.execute("TRUNCATE map CASCADE") } }
        map =
            maps.create(
                MapAddress("lobby", "mainlobby"),
                "LOD",
                MapKind.ARENA,
                false,
                MapTrust.FIRST_PARTY,
                "staff",
            )
        versions.commit(map.id, null, null, null, null, "staff")
        versions.publish(map.id, 1, BundleFacts(mapSha, null, 100, null, null), "staff")
        val light = UUID.randomUUID()
        lits.start(mapSha, 2, light)
        lits.succeed(mapSha, 2, light, LitFacts(source, 100, 1, 1, 1))
        pins.move("stage", map.id, 1, "staff")
        sql("INSERT INTO map_lod_budget(id) VALUES ('$generationBudget')")
        sql("INSERT INTO map_lod_delivery_budget(id,token_sha256) VALUES ('$budget','$tokenHash')")
    }

    private fun sql(statement: String) =
        db.connection.use { c -> c.createStatement().use { it.execute(statement) } }

    private fun artifact(registrySha: String = registry, auto: Boolean = true): UUID {
        val generation = UUID.randomUUID()
        val attempt = UUID.randomUUID()
        val identity = UUID.randomUUID().toString().replace("-", "").repeat(2)
        sql(
            """INSERT INTO map_lod_run(id,budget_id,environment,namespace,name,version,map_bundle_sha256,source_bundle_sha256,registry_sha256,generator_sha256,worker_image,created_at,expires_at,created_by)
            VALUES ('$generation','$generationBudget','stage','lobby','mainlobby',1,'$mapSha','$source','$registrySha','$generator','ghcr.io/test@sha256:${"e".repeat(64)}','$now','${now.plusSeconds(1800)}','fixture')"""
        )
        sql(
            """INSERT INTO map_lod(attempt,run_id,identity_sha256,trust,map_bundle_sha256,source_bundle_sha256,registry_sha256,generator_sha256,worker_image,state,started_at,updated_at,bundle_sha256,bundle_bytes,manifest_sha256,manifest_bytes,columns)
            VALUES ('$attempt','$generation','$identity','FIRST_PARTY','$mapSha','$source','$registrySha','$generator','ghcr.io/test@sha256:${"e".repeat(64)}','READY','$now','$now','${"1".repeat(64)}',4096,'${"2".repeat(64)}',256,1)"""
        )
        if (auto)
            sql(
                "INSERT INTO map_lod_auto_selection(identity_sha256,run_id,budget_id) VALUES ('$identity','$generation','$generationBudget')"
            )
        return attempt
    }

    private fun identity(registrySha: String = registry) =
        LodServingIdentity(mapSha, source, registrySha, generator)

    private fun select(registrySha: String = registry): LodServingSnapshot =
        quotas.discoverEntitled(budget, generationBudget, identity(registrySha))!!

    private fun publish() = quotas.reconcileEntitled(budget, generationBudget, now)

    @Test
    fun `entitled selection persists beyond thirty minutes and permits arbitrary network UUIDs`() {
        artifact()
        assertEquals(1, publish())
        val selected = select()
        assertEquals(LodServingMode.ENTITLED, selected.run.mode)
        assertNull(selected.run.expiresAt)
        assertTrue(selected.run.players.isEmpty())
        val at = now.plusSeconds(7200)
        assertEquals(
            1024,
            quotas
                .payload(selected.run.id, UUID.randomUUID(), mapSha, UUID.randomUUID(), 1024) { at }
                .bytes,
        )
        assertEquals(
            120000,
            quotas
                .remote(selected.run.id, UUID.randomUUID(), mapSha, UUID.randomUUID()) { at }
                .validForMillis,
        )
    }

    @Test
    fun `background publication is idempotent and private while discovery is exact read only`() {
        artifact()
        assertNull(quotas.discoverEntitled(budget, generationBudget, identity()))
        assertEquals(0, count("map_lod_serving_run"))
        assertEquals(tokenHash, quotas.deliveryBudgetTokenSha256(budget))
        assertEquals(1, publish())
        assertEquals(0, publish())
        val selected = select()
        assertFalse(selected.artifact.published)
        assertNull(
            quotas.discoverEntitled(
                budget,
                generationBudget,
                identity().copy(sourceBundleSha256 = "f".repeat(64)),
            )
        )
        assertEquals(1, count("map_lod_serving_run"))
        assertEquals(0, count("map_lod_serving_debit"))
        assertEquals(0, selected.payloadReserved)
        assertEquals(0, selected.remoteReadsReserved)
    }

    @Test
    fun `unselected public or wrong format provenance cannot become entitled`() {
        val attempt = artifact(auto = false)
        assertEquals(0, publish())
        sql(
            "INSERT INTO map_lod_auto_selection(identity_sha256,run_id,budget_id) SELECT identity_sha256,run_id,'$generationBudget' FROM map_lod WHERE attempt='$attempt'"
        )
        sql("UPDATE map_lod SET published=true WHERE attempt='$attempt'")
        assertEquals(0, publish())
        sql("UPDATE map_lod SET published=false WHERE attempt='$attempt'")
        sql("UPDATE map_lit SET light_format=1 WHERE bundle_sha256='$mapSha'")
        assertEquals(0, publish())
        assertEquals(0, count("map_lod_serving_run"))
    }

    @Test
    fun `pin revocation refuses new admission but an existing one use source remains bounded`() {
        artifact()
        publish()
        val selected = select()
        val request = UUID.randomUUID()
        quotas.remote(selected.run.id, request, mapSha, UUID.randomUUID()) { now }
        sql("DELETE FROM map_pin WHERE map='${map.id}'")
        assertNull(quotas.discoverEntitled(budget, generationBudget, identity()))
        assertFalse(quotas.isCurrentEntitled(selected.run.id, budget, generationBudget))
        assertFailsWith<LodServingDenied> {
            quotas.payload(selected.run.id, UUID.randomUUID(), mapSha, UUID.randomUUID(), 1024) {
                now
            }
        }
        assertFailsWith<LodServingDenied> {
            quotas.remote(selected.run.id, UUID.randomUUID(), mapSha, UUID.randomUUID()) { now }
        }
        assertEquals(
            selected.artifact.attempt,
            quotas.consumeRemote(selected.run.id, request) { now.plusSeconds(1) }.artifact.attempt,
        )
        assertFailsWith<LodServingDenied> {
            quotas.consumeRemote(selected.run.id, request) { now.plusSeconds(2) }
        }
        assertEquals(1, count("map_lod_serving_debit"))
    }

    @Test
    fun `two immutable selections share irreversible quota and UUID pacing across replicas`() {
        artifact()
        artifact("f".repeat(64))
        assertEquals(2, publish())
        val runs = listOf(select().run.id, select("f".repeat(64)).run.id)
        val player = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val granted =
                pool
                    .invokeAll(
                        (0..7).map { n ->
                            Callable {
                                runCatching {
                                        PostgresLodServingRepository(db).payload(
                                            runs[n % 2],
                                            UUID.randomUUID(),
                                            mapSha,
                                            player,
                                            262144,
                                        ) {
                                            now
                                        }
                                    }
                                    .getOrNull()
                            }
                        }
                    )
                    .mapNotNull { it.get() }
            assertEquals(2, granted.size)
        } finally {
            pool.shutdownNow()
        }
        sql("UPDATE map_lod_delivery_budget SET payload_reserved=1073741824-262144")
        val budgetPool = Executors.newFixedThreadPool(2)
        try {
            val wins =
                budgetPool
                    .invokeAll(
                        runs.map { run ->
                            Callable {
                                runCatching {
                                        PostgresLodServingRepository(db).payload(
                                            run,
                                            UUID.randomUUID(),
                                            mapSha,
                                            UUID.randomUUID(),
                                            262144,
                                        ) {
                                            now.plusSeconds(1)
                                        }
                                    }
                                    .isSuccess
                            }
                        }
                    )
                    .count { it.get() }
            assertEquals(1, wins)
        } finally {
            budgetPool.shutdownNow()
        }
        assertEquals(
            LodServingRefusal.BUDGET,
            assertFailsWith<LodServingDenied> {
                    PostgresLodServingRepository(db).payload(
                        runs[1],
                        UUID.randomUUID(),
                        mapSha,
                        UUID.randomUUID(),
                        1,
                    ) {
                        now.plusSeconds(2)
                    }
                }
                .reason,
        )
        assertEquals(0, publish())
        assertEquals(1073741824L, select().payloadReserved)
        assertEquals(1073741824L, select("f".repeat(64)).payloadReserved)
    }

    @Test
    fun `shared global window caps many UUIDs at four MiB per fixed second`() {
        artifact()
        publish()
        val run = select().run.id
        val pool = Executors.newFixedThreadPool(8)
        try {
            val granted =
                pool
                    .invokeAll(
                        (0..31).map {
                            Callable {
                                runCatching {
                                        PostgresLodServingRepository(db).payload(
                                            run,
                                            UUID.randomUUID(),
                                            mapSha,
                                            UUID.randomUUID(),
                                            262144,
                                        ) {
                                            now
                                        }
                                    }
                                    .getOrNull()
                            }
                        }
                    )
                    .mapNotNull { it.get() }
            assertEquals(16, granted.size)
            assertEquals(4194304L, select().payloadReserved)
            assertEquals(16, count("map_lod_serving_debit"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `source exact identity and shared origin reservation survive map selections and restart`() {
        artifact()
        artifact("f".repeat(64))
        publish()
        val first = select().run.id
        val second = select("f".repeat(64)).run.id
        assertEquals(
            LodServingRefusal.SOURCE,
            assertFailsWith<LodServingDenied> {
                    quotas.remote(first, UUID.randomUUID(), "f".repeat(64), UUID.randomUUID()) {
                        now
                    }
                }
                .reason,
        )
        sql(
            "UPDATE map_lod_delivery_budget SET remote_reads_reserved=9998,remote_bytes_reserved=1073741824-8192"
        )
        quotas.remote(first, UUID.randomUUID(), mapSha, UUID.randomUUID()) { now }
        assertEquals(
            LodServingRefusal.BUDGET,
            assertFailsWith<LodServingDenied> {
                    PostgresLodServingRepository(db).remote(
                        second,
                        UUID.randomUUID(),
                        mapSha,
                        UUID.randomUUID(),
                    ) {
                        now
                    }
                }
                .reason,
        )
        assertEquals(10000, select().remoteReadsReserved)
        assertEquals(1073741824L, select().remoteBytesReserved)
    }

    @Test
    fun `auto approved exact READY reuse survives older generation budget and identical content pin moves`() {
        artifact()
        sql("INSERT INTO map_lod_budget(id) VALUES ('older-manual')")
        sql("UPDATE map_lod_run SET budget_id='older-manual'")
        assertEquals(1, publish())
        val run = select().run.id
        versions.commit(map.id, null, null, null, null, "staff")
        versions.publish(map.id, 2, BundleFacts(mapSha, null, 100, null, null), "staff")
        pins.move("stage", map.id, 2, "staff")
        assertEquals(run, select().run.id)
        assertEquals(0, publish())
        assertEquals(
            1024,
            quotas.payload(run, UUID.randomUUID(), mapSha, UUID.randomUUID(), 1024) { now }.bytes,
        )
    }

    @Test
    fun `missing or disabled operator budget never seeds credits and fences existing source leases`() {
        artifact()
        assertEquals(0, quotas.reconcileEntitled("missing", generationBudget, now))
        assertNull(quotas.deliveryBudgetTokenSha256("missing"))
        assertEquals(1, count("map_lod_delivery_budget"))
        publish()
        val run = select().run.id
        val request = UUID.randomUUID()
        quotas.remote(run, request, mapSha, UUID.randomUUID()) { now }
        sql("UPDATE map_lod_delivery_budget SET enabled=false")
        assertNull(quotas.deliveryBudgetTokenSha256(budget))
        assertNull(quotas.discoverEntitled(budget, generationBudget, identity()))
        assertFailsWith<LodServingDenied> {
            quotas.consumeRemote(run, request) { now.plusSeconds(1) }
        }
        assertFailsWith<LodServingDenied> {
            quotas.payload(run, UUID.randomUUID(), mapSha, UUID.randomUUID(), 1024) { now }
        }
        assertEquals(0, publish())
        assertEquals(2, quotas.find(run)!!.remoteReadsReserved)
    }

    private fun count(table: String): Int =
        db.connection.use { c ->
            c.createStatement().use { sql ->
                sql.executeQuery("SELECT count(*) FROM $table").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
}
