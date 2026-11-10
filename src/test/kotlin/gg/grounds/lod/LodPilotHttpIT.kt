package gg.grounds.lod

import gg.grounds.MinioResource
import gg.grounds.PostgresResource
import gg.grounds.domain.*
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
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.PutObjectRequest

@QuarkusTest
@WithTestResource(value = PostgresResource::class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
@WithTestResource(value = MinioResource::class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
@TestProfile(LodPilotHttpProfile::class)
class LodPilotHttpIT {
    @Inject lateinit var db: DataSource
    @Inject lateinit var quotas: LodServingRepository

    @Test
    fun `capability filter precedes JSON decoding and cannot authorize normal Maps APIs`() {
        val id = UUID.randomUUID()
        given()
            .contentType(ContentType.JSON)
            .body("{")
            .post("/v1/maps/lod-pilots/$id/credits")
            .then()
            .statusCode(403)
        given()
            .header(LOD_PILOT_HEADER, "x".repeat(60))
            .contentType(ContentType.JSON)
            .body("{")
            .post("/v1/maps/lod-pilots/$id/credits")
            .then()
            .statusCode(403)
        given().header(LOD_PILOT_HEADER, "x".repeat(60)).get("/v1/maps").then().statusCode(401)
    }

    @Test
    fun `authenticated source capability is source scoped private and one use without changing map auth`() {
        val now = Instant.now()
        val generation = UUID.randomUUID()
        val attempt = UUID.randomUUID()
        val id = UUID.randomUUID()
        val player = UUID.randomUUID()
        val sha = "a".repeat(64)
        val token = "http-contract-pilot-" + UUID.randomUUID()
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        val bundleSha = digest(bytes)
        MinioResource.client(requireNotNull(MinioResource.endpoint)).use { s3 ->
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(MinioResource.PRIVATE)
                    .key(LodArtifactStore.privateBundleKey(MapTrust.FIRST_PARTY, bundleSha))
                    .build(),
                RequestBody.fromBytes(bytes),
            )
        }
        db.connection.use { c ->
            c.createStatement().use { sql ->
                val budget = "http-${UUID.randomUUID()}"
                sql.execute("INSERT INTO map_lod_budget(id) VALUES ('$budget')")
                sql.execute(
                    """INSERT INTO map_lod_run(id,budget_id,environment,namespace,name,version,map_bundle_sha256,source_bundle_sha256,registry_sha256,generator_sha256,worker_image,created_at,expires_at,created_by)
                VALUES ('$generation','$budget','stage','lobby','http',1,'$sha','${"b".repeat(64)}','${"c".repeat(64)}','${"d".repeat(64)}','ghcr.io/test@sha256:${"e".repeat(64)}','$now','${now.plusSeconds(1800)}','fixture')"""
                )
                sql.execute(
                    """INSERT INTO map_lod(attempt,run_id,identity_sha256,trust,map_bundle_sha256,source_bundle_sha256,registry_sha256,generator_sha256,worker_image,state,started_at,updated_at,bundle_sha256,bundle_bytes,manifest_sha256,manifest_bytes,columns)
                VALUES ('$attempt','$generation','${"f".repeat(64)}','FIRST_PARTY','$sha','${"b".repeat(64)}','${"c".repeat(64)}','${"d".repeat(64)}','ghcr.io/test@sha256:${"e".repeat(64)}','READY','$now','$now','$bundleSha',4096,'${"2".repeat(64)}',256,1)"""
                )
            }
        }
        quotas.register(
            LodServingRun(
                id,
                attempt,
                digest(token.toByteArray()),
                setOf(player),
                now,
                now.plusSeconds(1800),
                "operator",
            )
        )
        val path = "/v1/maps/lod-pilots/$id/source"
        val request =
            LodSourceRequest(
                requestId = UUID.randomUUID().toString(),
                mapBundleSha256 = sha,
                playerId = player.toString(),
            )
        given()
            .header(LOD_PILOT_HEADER, token)
            .contentType(ContentType.JSON)
            .body(request.copy(mapBundleSha256 = "f".repeat(64)))
            .post(path)
            .then()
            .statusCode(404)
        given()
            .header(LOD_PILOT_HEADER, token)
            .contentType(ContentType.JSON)
            .body(request.copy(playerId = UUID.randomUUID().toString()))
            .post(path)
            .then()
            .statusCode(403)
        given()
            .header(LOD_PILOT_HEADER, token)
            .contentType(ContentType.JSON)
            .body(request)
            .post(path)
            .then()
            .statusCode(200)
            .header("Cache-Control", containsString("no-store"))
            .body("requestId", equalTo(request.requestId))
            .body("runId", equalTo(id.toString()))
            .body("bundleSha256", equalTo(bundleSha))
            .body("bundleUrl", equalTo("$path/${request.requestId}"))
            .body("validForMillis", lessThanOrEqualTo(120000))
        given()
            .header(LOD_PILOT_HEADER, token)
            .contentType(ContentType.JSON)
            .body(request)
            .post(path)
            .then()
            .statusCode(409)
        val download = "$path/${request.requestId}"
        given().get(download).then().statusCode(403)
        given().header(LOD_PILOT_HEADER, token).head(download).then().statusCode(405)
        given()
            .header(LOD_PILOT_HEADER, token)
            .header("Range", "bytes=0-10")
            .get(download)
            .then()
            .statusCode(416)
        val received =
            given()
                .header(LOD_PILOT_HEADER, token)
                .get(download)
                .then()
                .statusCode(200)
                .header("Cache-Control", containsString("no-store"))
                .header("Content-Length", equalTo(bytes.size.toString()))
                .extract()
                .asByteArray()
        org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, received)
        // Delete the origin: replay must still be denied before it can touch storage.
        MinioResource.client(requireNotNull(MinioResource.endpoint)).use { s3 ->
            s3.deleteObject {
                it.bucket(MinioResource.PRIVATE)
                    .key(LodArtifactStore.privateBundleKey(MapTrust.FIRST_PARTY, bundleSha))
            }
        }
        given().header(LOD_PILOT_HEADER, token).get(download).then().statusCode(410)
        given().header(LOD_PILOT_HEADER, token).get("/v1/maps").then().statusCode(401)
        db.connection.use { c ->
            c.createStatement().use {
                it.execute("UPDATE map_lod_serving_run SET enabled=false WHERE id='$id'")
            }
        }
        given()
            .header(LOD_PILOT_HEADER, token)
            .contentType(ContentType.JSON)
            .body(request.copy(requestId = UUID.randomUUID().toString()))
            .post(path)
            .then()
            .statusCode(410)
    }
}

class LodPilotHttpProfile : QuarkusTestProfile {
    override fun getConfigOverrides() = mapOf("grounds.maps.lod.pilot.enabled" to "true")
}
