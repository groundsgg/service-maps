package gg.grounds.lod

import gg.grounds.PostgresResource
import gg.grounds.domain.LodServingRepository
import gg.grounds.lod.format.*
import io.quarkus.test.common.TestResourceScope
import io.quarkus.test.common.WithTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*
import org.hamcrest.Matchers.*

@QuarkusTest
@WithTestResource(value = PostgresResource::class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
@TestProfile(LodServingDiscoveryProfile::class)
class LodServingDiscoveryHttpIT {
    @Inject lateinit var db: DataSource
    @Inject lateinit var quotas: LodServingRepository
    private val token = "discovery-server-only-" + "x".repeat(48)
    private val mapSha = "a".repeat(64)
    private val sourceSha = "b".repeat(64)
    private val registry = "c".repeat(64)
    private val generator = "d".repeat(64)
    private val discovery = "/v1/maps/lod-serving/discover"

    @Test
    fun `discovery authenticates before decoding and remains read only until background publication`() {
        sql(
            "INSERT INTO map_lod_delivery_budget(id,token_sha256) VALUES ('http-serving','${digest(token.toByteArray())}')"
        )
        for (secret in listOf<String?>(null, "wrong-" + "x".repeat(48))) {
            val call = given().contentType(ContentType.JSON).body("{")
            if (secret != null) call.header(LOD_PILOT_HEADER, secret)
            call
                .post(discovery)
                .then()
                .statusCode(403)
                .header("Cache-Control", containsString("no-store"))
        }
        given()
            .header(LOD_PILOT_HEADER, token)
            .contentType(ContentType.JSON)
            .body("{")
            .post(discovery)
            .then()
            .statusCode(400)
        val request =
            LodServingDiscoveryRequest(
                mapBundleSha256 = mapSha,
                sourceBundleSha256 = sourceSha,
                registrySha256 = registry,
                generatorSha256 = generator,
            )
        given()
            .header(LOD_PILOT_HEADER, token)
            .contentType(ContentType.JSON)
            .body(request)
            .post(discovery)
            .then()
            .statusCode(404)
        assertEquals(0, count("map_lod_serving_run"))
        assertEquals(0, count("map_lod"))
        val now = Instant.now().minusSeconds(7200)
        val map = UUID.randomUUID()
        val generation = UUID.randomUUID()
        val attempt = UUID.randomUUID()
        sql(
            "INSERT INTO map(id,namespace,name,display_name,kind,trust,owner_sub) VALUES ('$map','lobby','mainlobby','LOD','ARENA','FIRST_PARTY','fixture')"
        )
        sql(
            "INSERT INTO map_version(map,version,state,bundle_sha256,published_by_sub,scene_present) VALUES ('$map',1,'PUBLISHED','$mapSha','fixture',false)"
        )
        sql(
            "INSERT INTO map_pin(environment,map,version,moved_by_sub) VALUES ('stage','$map',1,'fixture')"
        )
        sql(
            "INSERT INTO map_lit(bundle_sha256,light_format,state,attempt,lit_sha256,lit_size) VALUES ('$mapSha',2,'LIT','${UUID.randomUUID()}','$sourceSha',4096)"
        )
        sql("INSERT INTO map_lod_budget(id) VALUES ('http-auto')")
        sql(
            """INSERT INTO map_lod_run(id,budget_id,environment,namespace,name,version,map_bundle_sha256,source_bundle_sha256,registry_sha256,generator_sha256,worker_image,created_at,expires_at,created_by)
            VALUES ('$generation','http-auto','stage','lobby','mainlobby',1,'$mapSha','$sourceSha','$registry','$generator','ghcr.io/test@sha256:${"e".repeat(64)}','$now','${now.plusSeconds(1800)}','fixture')"""
        )
        sql(
            """INSERT INTO map_lod(attempt,run_id,identity_sha256,trust,map_bundle_sha256,source_bundle_sha256,registry_sha256,generator_sha256,worker_image,state,started_at,updated_at,bundle_sha256,bundle_bytes,manifest_sha256,manifest_bytes,columns)
            VALUES ('$attempt','$generation','${"f".repeat(64)}','FIRST_PARTY','$mapSha','$sourceSha','$registry','$generator','ghcr.io/test@sha256:${"e".repeat(64)}','READY','$now','$now','${"1".repeat(64)}',4096,'${"2".repeat(64)}',256,1)"""
        )
        sql(
            "INSERT INTO map_lod_auto_selection(identity_sha256,run_id,budget_id) VALUES ('${"f".repeat(64)}','$generation','http-auto')"
        )
        // Request path cannot repair the READY -> registration crash gap.
        given()
            .header(LOD_PILOT_HEADER, token)
            .contentType(ContentType.JSON)
            .body(request)
            .post(discovery)
            .then()
            .statusCode(404)
        assertEquals(0, count("map_lod_serving_run"))
        assertEquals(1, quotas.reconcileEntitled("http-serving", "http-auto", now))
        val selection =
            given()
                .header(LOD_PILOT_HEADER, token)
                .contentType(ContentType.JSON)
                .body(request)
                .post(discovery)
                .then()
                .statusCode(200)
                .header("Cache-Control", containsString("no-store"))
                .body("mapBundleSha256", equalTo(mapSha))
                .body("sourceBundleSha256", equalTo(sourceSha))
                .body("registrySha256", equalTo(registry))
                .body("generatorSha256", equalTo(generator))
                .extract()
                .`as`(LodServingSelection::class.java)
        selection.validate()
        for (wrong in
            listOf(
                request.copy(sourceBundleSha256 = "9".repeat(64)),
                request.copy(mapBundleSha256 = "9".repeat(64)),
                request.copy(registrySha256 = "9".repeat(64)),
                request.copy(generatorSha256 = "9".repeat(64)),
            )) {
            given()
                .header(LOD_PILOT_HEADER, token)
                .contentType(ContentType.JSON)
                .body(wrong)
                .post(discovery)
                .then()
                .statusCode(404)
        }
        assertEquals(0, count("map_lod_serving_debit"))
        val snapshot = quotas.find(UUID.fromString(selection.runId))!!
        assertEquals(0, snapshot.remoteReadsReserved)
        assertEquals(0, snapshot.payloadReserved)
        assertFalse(snapshot.artifact.published)
        // The ordinary serving capability works with pilot mode disabled, without a UUID allowlist.
        val credits = "/v1/maps/lod-pilots/${selection.runId}/credits"
        given()
            .header(LOD_PILOT_HEADER, token)
            .contentType(ContentType.JSON)
            .body(
                LodPayloadRequest(
                    requestId = UUID.randomUUID().toString(),
                    mapBundleSha256 = mapSha,
                    playerId = UUID.randomUUID().toString(),
                    bytes = 1024,
                )
            )
            .post(credits)
            .then()
            .statusCode(200)
        sql("DELETE FROM map_pin WHERE map='$map'")
        given()
            .header(LOD_PILOT_HEADER, token)
            .contentType(ContentType.JSON)
            .body(request)
            .post(discovery)
            .then()
            .statusCode(404)
        given()
            .header(LOD_PILOT_HEADER, token)
            .contentType(ContentType.JSON)
            .body(
                LodSourceRequest(
                    requestId = UUID.randomUUID().toString(),
                    mapBundleSha256 = mapSha,
                    playerId = UUID.randomUUID().toString(),
                )
            )
            .post("/v1/maps/lod-pilots/${selection.runId}/source")
            .then()
            .statusCode(410)
        assertEquals(1, count("map_lod_serving_debit"))
    }

    private fun sql(statement: String) =
        db.connection.use { c -> c.createStatement().use { it.execute(statement) } }

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

class LodServingDiscoveryProfile : QuarkusTestProfile {
    override fun getConfigOverrides() =
        mapOf(
            "grounds.maps.lod.pilot.enabled" to "false",
            "grounds.maps.lod.serving.enabled" to "true",
            "grounds.maps.lod.serving.budget-id" to "http-serving",
            "grounds.maps.lod.serving.generation-budget-id" to "http-auto",
            "quarkus.scheduler.enabled" to "false",
        )
}
