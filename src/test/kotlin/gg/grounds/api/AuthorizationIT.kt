package gg.grounds.api

import gg.grounds.MinioResource
import gg.grounds.PostgresResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Who may do what. Every one of these passed before authorisation existed, which is exactly why
 * they are here: the interesting cases are the ones that used to succeed.
 */
@QuarkusTest
@QuarkusTestResource(PostgresResource::class)
@QuarkusTestResource(MinioResource::class)
// Ordered on purpose: the last two cases are one scenario in two identities — carol creates
// a map, dave must not find it — and an identity cannot change mid-test.
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AuthorizationIT {

    @Test
    @TestSecurity(user = "nobody")
    fun `a subject with no group cannot create a first-party map`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"address":"bedwars/not-yours","kind":"arena"}""")
            .`when`()
            .post("/v1/maps")
            .then()
            .statusCode(403)
    }

    /** Ownership, not a group: this is what lets a creator have their own corner. */
    @Test
    @TestSecurity(user = "alice")
    fun `a subject with no group owns its own namespace`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"address":"u/alice/treehouse","kind":"plot"}""")
            .`when`()
            .post("/v1/maps")
            .then()
            .statusCode(201)
            .body("trust", equalTo("UNTRUSTED"))
    }

    @Test
    @TestSecurity(user = "alice")
    fun `and only its own`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"address":"u/bob/treehouse","kind":"plot"}""")
            .`when`()
            .post("/v1/maps")
            .then()
            .statusCode(403)
    }

    /**
     * The split the whole design rests on: publishing a version changes nothing players see, moving
     * a pin changes what everyone loads. An author may do the first and not the second.
     */
    @Test
    @TestSecurity(user = "author", roles = ["map-authors"])
    fun `an author may publish a version but not put it in front of players`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"address":"bedwars/gated","kind":"arena"}""")
            .post("/v1/maps")
            .then()
            .statusCode(201)
        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/v1/maps/bedwars/gated/versions")
            .then()
            .statusCode(201)
        given()
            .contentType(ContentType.JSON)
            .body("""{"bundleSha256":"$DIGEST","sizeBytes":42}""")
            .post("/v1/maps/bedwars/gated/versions/1/publish")
            .then()
            .statusCode(200)

        given()
            .contentType(ContentType.JSON)
            .body("""{"version":1}""")
            .`when`()
            .post("/v1/maps/bedwars/gated/pins/stage")
            .then()
            .statusCode(403)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["grounds-staff"])
    fun `going live needs the group that grants it`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"address":"bedwars/golive","kind":"arena"}""")
            .post("/v1/maps")
            .then()
            .statusCode(201)
        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/v1/maps/bedwars/golive/versions")
            .then()
            .statusCode(201)
        given()
            .contentType(ContentType.JSON)
            .body("""{"bundleSha256":"$DIGEST","sizeBytes":42}""")
            .post("/v1/maps/bedwars/golive/versions/1/publish")
            .then()
            .statusCode(200)
        given()
            .contentType(ContentType.JSON)
            .body("""{"version":1}""")
            .`when`()
            .post("/v1/maps/bedwars/golive/pins/stage")
            .then()
            .statusCode(200)
    }

    /**
     * A creator's unfinished work is not everyone's business — and whether it exists at all is
     * answered with a 404 rather than a 403, because "forbidden" would confirm the name.
     */
    @Test
    @Order(1)
    @TestSecurity(user = "carol")
    fun `one creator cannot see another creator's map`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"address":"u/carol/secret","kind":"plot"}""")
            .post("/v1/maps")
            .then()
            .statusCode(201)

        given().`when`().get("/v1/maps/u/carol/secret").then().statusCode(200)
        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/v1/maps/u/carol/secret/versions")
            .then()
            .statusCode(201)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "dave")
    fun `and it is a 404, not a 403`() {
        given().`when`().get("/v1/maps/u/carol/secret").then().statusCode(404)
        given().`when`().get("/v1/maps/u/carol/secret/versions/1").then().statusCode(404)
        given()
            .`when`()
            .get("/v1/maps?namespace=u/carol")
            .then()
            .statusCode(200)
            .body("size()", equalTo(0))
    }

    private companion object {
        const val DIGEST = "abcdef00000000000000000000000000000000000000000000000000000042ab"
    }
}
