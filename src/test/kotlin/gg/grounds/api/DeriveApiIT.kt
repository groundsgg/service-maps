package gg.grounds.api

import gg.grounds.PostgresResource
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveIdentity
import gg.grounds.domain.DeriveProblem
import gg.grounds.domain.DerivedFailure
import gg.grounds.domain.MapRepository
import gg.grounds.domain.MapVersionRepository
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

/** API boundary coverage for the persisted derive projection and retry state machine. */
@QuarkusTest
@QuarkusTestResource(PostgresResource::class)
@TestProfile(DeriveEnabledProfile::class)
@TestSecurity(user = "derive-staff", roles = ["grounds-staff"])
class DeriveApiIT {
    @Inject lateinit var maps: MapRepository
    @Inject lateinit var versions: MapVersionRepository

    @Test
    fun `exact version returns ordered derive projection and retry only accepts system failures`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"address":"bedwars/derive-api","kind":"arena"}""")
            .post("/v1/maps")
            .then()
            .statusCode(201)
        val map =
            requireNotNull(maps.find(gg.grounds.domain.MapAddress.parse("bedwars/derive-api")!!))
        val committed =
            versions.commit(
                map.id,
                "a".repeat(64),
                "tmp/uploads/00000000-0000-0000-0000-000000000001/source.tar.zst",
                null,
                null,
                "derive-staff",
            )
        val deriving =
            requireNotNull(
                versions.claimForDerive(map.id, committed.version, java.util.UUID.randomUUID())
            )
        versions.acceptFailure(
            DeriveIdentity(map.id, 1, requireNotNull(deriving.deriveAttempt), "a".repeat(64)),
            DerivedFailure(
                scope = DeriveFailureScope.SYSTEM,
                retryable = true,
                problems =
                    listOf(
                        DeriveProblem(
                            DeriveFailureScope.SYSTEM,
                            "scene.json",
                            "FIRST",
                            null,
                            "first",
                        ),
                        DeriveProblem(DeriveFailureScope.SYSTEM, null, "SECOND", "x:y", "second"),
                    ),
            ),
        )

        given()
            .get("/v1/maps/bedwars/derive-api/versions/1")
            .then()
            .statusCode(200)
            .body("scene.status", equalTo("INVALID"))
            .body("scene.problems[0].code", equalTo("FIRST"))
            .body("scene.problems[1].qualifiedIdentity", equalTo("x:y"))
            .body("deriveFailureScope", equalTo("SYSTEM"))
            .body("deriveRetryable", equalTo(true))

        given()
            .post("/v1/maps/bedwars/derive-api/versions/1/derive/retry")
            .then()
            .statusCode(202)
            .body("state", equalTo("DERIVING"))
            .body("scene.status", equalTo("PENDING"))

        given().post("/v1/maps/bedwars/derive-api/versions/99/derive/retry").then().statusCode(404)

        // This exists only to prove the endpoint is not a generic restart button.
        given().post("/v1/maps/bedwars/derive-api/versions/1/derive/retry").then().statusCode(409)
    }

    @Test
    fun `explicit derive request accepts a source commit and rejects legacy source publish`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"address":"bedwars/derive-legacy","kind":"arena"}""")
            .post("/v1/maps")
            .then()
            .statusCode(201)

        given()
            .contentType(ContentType.JSON)
            .body(
                """{"uploadId":"00000000-0000-0000-0000-000000000002","sourceSha256":"${"b".repeat(64)}","derive":true}"""
            )
            .post("/v1/maps/bedwars/derive-legacy/versions")
            .then()
            .statusCode(201)
            .body("state", equalTo("DERIVING"))

        given()
            .contentType(ContentType.JSON)
            .body("""{"bundleSha256":"${"c".repeat(64)}","sizeBytes":1}""")
            .post("/v1/maps/bedwars/derive-legacy/versions/1/publish")
            .then()
            .statusCode(409)
    }

    @Test
    fun `optional derivation keeps an omitted derive signal compatible with legacy publish`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"address":"bedwars/derive-optional","kind":"arena"}""")
            .post("/v1/maps")
            .then()
            .statusCode(201)

        given()
            .contentType(ContentType.JSON)
            .body(
                """{"uploadId":"00000000-0000-0000-0000-000000000003","sourceSha256":"${"d".repeat(64)}"}"""
            )
            .post("/v1/maps/bedwars/derive-optional/versions")
            .then()
            .statusCode(201)
            .body("state", equalTo("DRAFT"))

        given()
            .contentType(ContentType.JSON)
            .body("""{"bundleSha256":"${"e".repeat(64)}","sizeBytes":1}""")
            .post("/v1/maps/bedwars/derive-optional/versions/1/publish")
            .then()
            .statusCode(409)
            .body(
                "detail",
                equalTo("the uploaded object for version 1 is gone; upload and commit again"),
            )
    }
}

class DeriveEnabledProfile : QuarkusTestProfile {
    override fun getConfigOverrides() =
        mapOf(
            "grounds.maps.derive.enabled" to "true",
            "grounds.maps.derive.image" to "example.test/service-maps@sha256:${"a".repeat(64)}",
        )
}
