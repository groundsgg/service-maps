package gg.grounds.api

import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.MinioResource
import gg.grounds.PostgresResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request

@QuarkusTest
@QuarkusTestResource(PostgresResource::class)
@QuarkusTestResource(MinioResource::class)
@TestSecurity(user = "builder-sub")
class VersionLifecycleIT {

    private val json = ObjectMapper()

    @Test
    fun `upload, commit, publish, pin — and the pin file is what a server would read`() {
        createMap("bedwars/crater")

        // 1. The upload URL is presigned against the PRIVATE bucket, and an outside client
        //    with no credentials of its own can PUT to it.
        val upload =
            given()
                .`when`()
                .post("/v1/maps/bedwars/crater/uploads")
                .then()
                .statusCode(200)
                .body("uploadId", notNullValue())
                .extract()
                .path<String>("url")

        val put =
            HttpClient.newHttpClient()
                .send(
                    HttpRequest.newBuilder(URI.create(upload))
                        .PUT(HttpRequest.BodyPublishers.ofString("pretend this is a world zip"))
                        .build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
        assertEquals(200, put.statusCode(), "a presigned PUT must be accepted as-is")

        // 2. Committing allocates version 1 as a draft: no bundle yet, so not usable yet.
        given()
            .contentType(ContentType.JSON)
            .body("""{"sourceSha256":"aaaa","note":"first pass"}""")
            .`when`()
            .post("/v1/maps/bedwars/crater/versions")
            .then()
            .statusCode(201)
            .body("version", equalTo(1))
            .body("state", equalTo("DRAFT"))

        // 3. A draft cannot be pinned. This is the guard the foreign key cannot give us.
        given()
            .contentType(ContentType.JSON)
            .body("""{"version":1}""")
            .`when`()
            .post("/v1/maps/bedwars/crater/pins/stage")
            .then()
            .statusCode(409)

        // 4. Publishing records what the bundle turned out to be.
        given()
            .contentType(ContentType.JSON)
            .body(
                """{"bundleSha256":"$BUNDLE","sizeBytes":20971520,"presentChunks":4096,"estLoadedMib":812}"""
            )
            .`when`()
            .post("/v1/maps/bedwars/crater/versions/1/publish")
            .then()
            .statusCode(200)
            .body("state", equalTo("PUBLISHED"))
            .body("bundleSha256", equalTo(BUNDLE))

        // 5. Publishing twice is refused: a published version is immutable.
        given()
            .contentType(ContentType.JSON)
            .body("""{"bundleSha256":"$BUNDLE","sizeBytes":1}""")
            .`when`()
            .post("/v1/maps/bedwars/crater/versions/1/publish")
            .then()
            .statusCode(409)

        // 6. Now it pins, and the pin file is rewritten.
        given()
            .contentType(ContentType.JSON)
            .body("""{"version":1}""")
            .`when`()
            .post("/v1/maps/bedwars/crater/pins/stage")
            .then()
            .statusCode(200)
            .body("version", equalTo(1))
            .body("pinFilePublished", equalTo(true))

        val pinFile = readPublic("pins/stage.json")
        val tree = json.readTree(pinFile)
        val entry = tree["maps"]["bedwars/crater"]
        assertEquals(1, entry["version"].asInt())
        assertEquals(BUNDLE, entry["bundleSha256"].asText())
        assertEquals(
            "bundle/sha256/${BUNDLE.take(2)}/$BUNDLE.tar.zst",
            entry["bundleKey"].asText(),
            "a booting server must be able to append this to the CDN base and be done",
        )

        // 7. The upload stayed private. Nothing put it in the public bucket, and nothing
        //    should: only an approved copy crosses that line.
        val publicKeys = listPublic()
        assertTrue(publicKeys.none { it.startsWith("tmp/uploads/") }, "found $publicKeys")
    }

    @Test
    fun `a fork copies no bytes and is immediately pinnable`() {
        createMap("skywars/origin")
        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/v1/maps/skywars/origin/versions")
            .then()
            .statusCode(201)
        given()
            .contentType(ContentType.JSON)
            .body("""{"bundleSha256":"$BUNDLE","sizeBytes":1234}""")
            .post("/v1/maps/skywars/origin/versions/1/publish")
            .then()
            .statusCode(200)

        val before = listPublic().size

        given()
            .contentType(ContentType.JSON)
            .body("""{"target":"skywars/origin-winter"}""")
            .`when`()
            .post("/v1/maps/skywars/origin/forks")
            .then()
            .statusCode(201)
            .body("address", equalTo("skywars/origin-winter"))
            .body("forkedFrom", notNullValue())

        // The fork's first version carries the parent's digest — that is the whole reason it
        // costs nothing.
        given()
            .`when`()
            .get("/v1/maps/skywars/origin-winter/versions")
            .then()
            .statusCode(200)
            .body("[0].version", equalTo(1))
            .body("[0].state", equalTo("PUBLISHED"))
            .body("[0].bundleSha256", equalTo(BUNDLE))

        assertEquals(before, listPublic().size, "a fork must not write objects")

        given()
            .contentType(ContentType.JSON)
            .body("""{"version":1}""")
            .`when`()
            .post("/v1/maps/skywars/origin-winter/pins/stage")
            .then()
            .statusCode(200)
    }

    @Test
    fun `rolling back is the same operation as going live, and both are recorded`() {
        createMap("bedwars/two-versions")
        repeat(2) { i ->
            given()
                .contentType(ContentType.JSON)
                .body("{}")
                .post("/v1/maps/bedwars/two-versions/versions")
                .then()
                .statusCode(201)
            given()
                .contentType(ContentType.JSON)
                .body("""{"bundleSha256":"${BUNDLE.dropLast(1)}$i","sizeBytes":10}""")
                .post("/v1/maps/bedwars/two-versions/versions/${i + 1}/publish")
                .then()
                .statusCode(200)
        }

        for (version in listOf(2, 1)) {
            given()
                .contentType(ContentType.JSON)
                .body("""{"version":$version}""")
                .`when`()
                .post("/v1/maps/bedwars/two-versions/pins/stage")
                .then()
                .statusCode(200)
                .body("version", equalTo(version))
        }

        val entry = json.readTree(readPublic("pins/stage.json"))["maps"]["bedwars/two-versions"]
        assertEquals(1, entry["version"].asInt(), "the rollback is what the file must show")
    }

    /**
     * The upload id becomes part of an object key, so anything that is not an upload id is refused
     * rather than interpolated into one.
     */
    @Test
    fun `an upload id that is a path is refused`() {
        createMap("bedwars/traversal")
        given()
            .contentType(ContentType.JSON)
            .body("""{"uploadId":"../../bundle/sha256/ab/cd"}""")
            .`when`()
            .post("/v1/maps/bedwars/traversal/versions")
            .then()
            .statusCode(400)
    }

    /** A map named after a sub-resource would be unreachable, so the name is refused. */
    @Test
    fun `reserved names are refused`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"address":"bedwars/versions","kind":"arena"}""")
            .`when`()
            .post("/v1/maps")
            .then()
            .statusCode(400)
    }

    private fun createMap(address: String) {
        given()
            .contentType(ContentType.JSON)
            .body("""{"address":"$address","kind":"arena"}""")
            .`when`()
            .post("/v1/maps")
            .then()
            .statusCode(201)
    }

    private fun readPublic(key: String): String =
        MinioResource.client(requireNotNull(MinioResource.endpoint)).use { s3 ->
            s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(MinioResource.PUBLIC).key(key).build()
                )
                .asUtf8String()
        }

    private fun listPublic(): List<String> =
        MinioResource.client(requireNotNull(MinioResource.endpoint)).use { s3 ->
            s3.listObjectsV2(ListObjectsV2Request.builder().bucket(MinioResource.PUBLIC).build())
                .contents()
                .map { it.key() }
        }

    private companion object {
        const val BUNDLE = "c0ffee00000000000000000000000000000000000000000000000000000000ab"
    }
}
