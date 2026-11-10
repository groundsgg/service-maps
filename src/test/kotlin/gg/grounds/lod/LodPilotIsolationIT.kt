package gg.grounds.lod

import gg.grounds.MinioResource
import gg.grounds.PostgresResource
import gg.grounds.domain.*
import io.quarkus.test.common.TestResourceScope
import io.quarkus.test.common.WithTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest

@QuarkusTest
@WithTestResource(value = PostgresResource::class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
@WithTestResource(value = MinioResource::class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
@TestProfile(LodPilotIsolationProfile::class)
@TestSecurity(user = "operator", roles = ["grounds-staff"])
class LodPilotIsolationIT {
    @Inject lateinit var db: DataSource
    @Inject lateinit var jobs: Fabric8LodJobGateway
    @Inject lateinit var blobs: R2LodBlobs

    @Test
    fun `direct authenticated normal pin request cannot replace public pins in an isolated pilot`() {
        val map = UUID.randomUUID()
        val name = "isolated-${UUID.randomUUID()}"
        val sentinel = "all-real-maps-must-stay-in-this-pin-file"
        db.connection.use { c ->
            c.createStatement().use { sql ->
                sql.execute(
                    "INSERT INTO map(id,namespace,name,display_name,kind,owner_sub) VALUES ('$map','lobby','$name','isolation','LOBBY','operator')"
                )
                sql.execute(
                    "INSERT INTO map_version(map,version,state,bundle_sha256,manifest_sha256,size_bytes,present_chunks,est_loaded_mib,published_by_sub,scene_present) VALUES ('$map',13,'PUBLISHED','${"a".repeat(64)}','${"b".repeat(64)}',1,1,1,'operator',false)"
                )
            }
        }
        MinioResource.client(requireNotNull(MinioResource.endpoint)).use { s3 ->
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(MinioResource.PUBLIC)
                    .key("pins/stage.json")
                    .build(),
                RequestBody.fromString(sentinel),
            )
            given()
                .contentType(ContentType.JSON)
                .body("""{"version":13}""")
                .post("/v1/maps/lobby/$name/pins/stage")
                .then()
                .statusCode(404)
            assertEquals(
                sentinel,
                s3.getObjectAsBytes(
                        GetObjectRequest.builder()
                            .bucket(MinioResource.PUBLIC)
                            .key("pins/stage.json")
                            .build()
                    )
                    .asUtf8String(),
            )
        }
        given().get("/v1/maps").then().statusCode(404)
        given().contentType(ContentType.JSON).body("{").post("/v1/maps").then().statusCode(404)
        given().get("/v1/maps/lod-pilots/castle").then().statusCode(404)
        given().get("/q/health/ready").then().statusCode(200)
        given()
            .contentType(ContentType.JSON)
            .body("{")
            .post("/v1/maps/lod-pilots/${UUID.randomUUID()}/credits")
            .then()
            .statusCode(403)
    }

    @Test
    fun `pilot worker remains owned by its finite API pod even while suspended`() {
        val claim =
            LodClaim(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MapTrust.FIRST_PARTY,
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(64),
                "d".repeat(64),
                "ghcr.io/test@sha256:${"e".repeat(64)}",
                Instant.now(),
            )
        val job = jobs.job(claim, "{}")
        val owner = assertNotNull(job.metadata.ownerReferences).single()
        assertEquals("v1", owner.apiVersion)
        assertEquals("Pod", owner.kind)
        assertEquals("pilot-controller-fixture", owner.name)
        assertEquals("321ba5f0-b4f5-46bc-865c-d8013e2a552d", owner.uid)
        assertEquals(false, owner.blockOwnerDeletion)
        assertEquals(true, job.spec.suspend)
        assertEquals("Never", job.spec.template.spec.containers.single().imagePullPolicy)
    }

    @Test
    fun `private-only pilot uses anonymous immutable first party CDN input`() {
        val sha = "8".repeat(64)
        assertEquals(
            "https://content.test/bundle/sha256/88/$sha.tar.zst",
            blobs.sourceUrl(sha, MapTrust.FIRST_PARTY),
        )
        assertFailsWith<IllegalArgumentException> { blobs.sourceUrl(sha, MapTrust.UNTRUSTED) }
    }
}

class LodPilotIsolationProfile : QuarkusTestProfile {
    override fun getConfigOverrides() =
        mapOf(
            "grounds.maps.lod.pilot.isolated" to "true",
            "grounds.maps.lod.pilot.enabled" to "true",
            "grounds.maps.lod.pilot.owner-pod-name" to "pilot-controller-fixture",
            "grounds.maps.lod.pilot.owner-pod-uid" to "321ba5f0-b4f5-46bc-865c-d8013e2a552d",
            "grounds.maps.lod.node" to "existing-node",
            "quarkus.kubernetes-client.master-url" to "http://127.0.0.1:1",
        )
}
