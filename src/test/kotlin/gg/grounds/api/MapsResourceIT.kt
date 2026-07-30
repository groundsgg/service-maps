package gg.grounds.api

import gg.grounds.PostgresResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(PostgresResource::class)
@TestSecurity(user = "builder-sub")
class MapsResourceIT {

    @Test
    fun `creates a map and reads it back by address`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """{"address":"bedwars/4x4-baumhaus","displayName":"4x4 Baumhaus","kind":"arena"}"""
            )
            .`when`()
            .post("/v1/maps")
            .then()
            .statusCode(201)
            .header("Location", equalTo("/v1/maps/bedwars/4x4-baumhaus"))
            .body("address", equalTo("bedwars/4x4-baumhaus"))
            .body("kind", equalTo("ARENA"))
            .body("trust", equalTo("FIRST_PARTY"))
            // Never from the request body: the authenticated subject owns the map.
            .body("ownerSub", equalTo("builder-sub"))

        given()
            .`when`()
            .get("/v1/maps/bedwars/4x4-baumhaus")
            .then()
            .statusCode(200)
            .body("displayName", equalTo("4x4 Baumhaus"))
    }

    @Test
    fun `a creator namespace is untrusted by construction and still routes`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"address":"u/hendrik/treehouse","kind":"plot"}""")
            .`when`()
            .post("/v1/maps")
            .then()
            .statusCode(201)
            .body("trust", equalTo("UNTRUSTED"))

        // Three path segments, one address — this is what the catch-all is for.
        given()
            .`when`()
            .get("/v1/maps/u/hendrik/treehouse")
            .then()
            .statusCode(200)
            .body("address", equalTo("u/hendrik/treehouse"))
    }

    @Test
    fun `the same address twice is a conflict, not a second map`() {
        val body = """{"address":"skywars/crater","kind":"arena"}"""
        given().contentType(ContentType.JSON).body(body).post("/v1/maps").then().statusCode(201)
        given()
            .contentType(ContentType.JSON)
            .body(body)
            .`when`()
            .post("/v1/maps")
            .then()
            .statusCode(409)
    }

    @Test
    fun `rejects a malformed address and an unknown kind`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"address":"Bedwars/../etc","kind":"arena"}""")
            .`when`()
            .post("/v1/maps")
            .then()
            .statusCode(400)

        given()
            .contentType(ContentType.JSON)
            .body("""{"address":"bedwars/valid","kind":"dungeon"}""")
            .`when`()
            .post("/v1/maps")
            .then()
            .statusCode(400)
    }

    @Test
    fun `missing map is a 404`() {
        given().`when`().get("/v1/maps/bedwars/does-not-exist").then().statusCode(404)
    }

    @Test
    fun `lists by namespace`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"address":"lobbies/spawn","kind":"lobby"}""")
            .post("/v1/maps")
            .then()
            .statusCode(201)

        given()
            .`when`()
            .get("/v1/maps?namespace=lobbies")
            .then()
            .statusCode(200)
            .body("address", hasItem("lobbies/spawn"))
    }
}
