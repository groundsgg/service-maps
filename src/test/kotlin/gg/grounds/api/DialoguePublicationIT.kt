package gg.grounds.api

import gg.grounds.PostgresResource
import gg.grounds.domain.*
import io.quarkus.test.common.WithTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DialogueRequiredProfile : QuarkusTestProfile {
    override fun getConfigOverrides() =
        mapOf(
            "grounds.maps.derive.enabled" to "true",
            "grounds.maps.derive.required" to "true",
            "grounds.maps.derive.image" to "worker@sha256:${"b".repeat(64)}",
            "grounds.maps.derive.request-schema-version" to "2",
            "grounds.maps.dialogue.validation-required" to "true",
        )
}

@QuarkusTest
@WithTestResource(PostgresResource::class)
@TestProfile(DialogueRequiredProfile::class)
@TestSecurity(user = "builder", roles = ["grounds-staff"])
class DialoguePublicationIT {
    @Inject lateinit var maps: MapRepository
    @Inject lateinit var versions: MapVersionRepository

    private fun map(): MapRecord =
        maps.create(
            MapAddress("dialogue", "gate-${UUID.randomUUID()}"),
            "test",
            MapKind.ARENA,
            false,
            MapTrust.FIRST_PARTY,
            "builder",
        )

    @Test
    fun requiredModeRejectsDeriveFalseAndDirectPublish() {
        val m = map()
        given()
            .contentType(ContentType.JSON)
            .body("""{"uploadId":"${UUID.randomUUID()}","derive":false}""")
            .post("/v1/maps/${m.address}/versions")
            .then()
            .statusCode(409)
        versions.commit(m.id, null, null, null, null, "builder")
        given()
            .contentType(ContentType.JSON)
            .body("""{"bundleSha256":"${"a".repeat(64)}","sizeBytes":1}""")
            .post("/v1/maps/${m.address}/versions/1/publish")
            .then()
            .statusCode(409)
        assertEquals(VersionState.DRAFT, versions.find(m.id, 1)!!.state)
    }

    @Test
    fun importMustUseSourceDerivation() {
        val m = map()
        given()
            .contentType(ContentType.JSON)
            .body("""{"sourceSha256":"${"a".repeat(64)}","derive":true}""")
            .post("/v1/maps/${m.address}/versions")
            .then()
            .statusCode(409)
        assertTrue(versions.list(m.id).isEmpty())
    }

    @Test
    fun changedBytesNeedNewProof() {
        val m = map()
        versions.commit(m.id, null, null, null, null, "builder")
        assertThrows(RuntimeException::class.java) {
            versions.publish(m.id, 1, BundleFacts("a".repeat(64), null, 1, null, null), "builder")
        }
        assertEquals(VersionState.DRAFT, versions.find(m.id, 1)!!.state)
    }
}
