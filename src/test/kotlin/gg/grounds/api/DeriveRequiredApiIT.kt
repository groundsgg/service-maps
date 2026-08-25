package gg.grounds.api

import gg.grounds.PostgresResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(PostgresResource::class)
@TestProfile(DeriveRequiredProfile::class)
@TestSecurity(user = "derive-required", roles = ["grounds-staff"])
class DeriveRequiredApiIT {
    @Test
    fun `required derivation rejects a source commit that omitted the compatibility signal`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"address":"bedwars/derive-required","kind":"arena"}""")
            .post("/v1/maps")
            .then()
            .statusCode(201)
        given()
            .contentType(ContentType.JSON)
            .body("""{"uploadId":"00000000-0000-0000-0000-000000000003"}""")
            .post("/v1/maps/bedwars/derive-required/versions")
            .then()
            .statusCode(409)
    }
}

class DeriveRequiredProfile : QuarkusTestProfile {
    override fun getConfigOverrides() =
        mapOf(
            "grounds.maps.derive.enabled" to "true",
            "grounds.maps.derive.required" to "true",
            "grounds.maps.derive.image" to "example.test/service-maps@sha256:${"a".repeat(64)}",
        )
}
