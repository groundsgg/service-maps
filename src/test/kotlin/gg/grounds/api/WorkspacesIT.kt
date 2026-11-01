package gg.grounds.api

import gg.grounds.MinioResource
import gg.grounds.PostgresResource
import gg.grounds.domain.WorkspaceRepository
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Instant
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The working-copy contract end to end against a real bucket: what a buildserver uploads is what a
 * restore downloads, and a snapshot is either the head in full or not at all.
 */
@QuarkusTest
@QuarkusTestResource(PostgresResource::class)
@QuarkusTestResource(MinioResource::class)
@TestSecurity(user = "buildserver-sa", roles = ["grounds-staff"])
class WorkspacesIT {

    @Inject lateinit var workspaces: WorkspaceRepository

    private val http = HttpClient.newHttpClient()

    @Test
    fun `a snapshot round-trips, and the second one uploads only what changed`() {
        val server = "round-trip"
        val level = "level-v1"
        val region = "region-v1"

        val first =
            open(
                    server,
                    null,
                    file("world/level.dat", level),
                    file("world/region/r.0.0.mca", region),
                )
                .statusCode(201)
                .body("snapshot", equalTo(1))
                .body("missing", hasSize<Any>(2))
                .extract()
                .jsonPath()
        upload(first, level, region)
        commit(server, 1).statusCode(200).body("head", equalTo(1))

        val second =
            open(
                    server,
                    1,
                    file("world/level.dat", level),
                    file("world/region/r.0.0.mca", "region-v2"),
                )
                .statusCode(201)
                .body("missing", hasSize<Any>(1))
                .body("missing[0].sha256", equalTo(sha256("region-v2")))
                .extract()
                .jsonPath()
        upload(second, "region-v2")
        commit(server, 2).statusCode(200)

        val head =
            given().get("/v1/workspaces/$server/head").then().statusCode(200).extract().jsonPath()
        assertEquals(2, head.getInt("snapshot"))
        val files = head.getList<Map<String, Any>>("files").associateBy { it["path"] }
        assertEquals(
            "region-v2",
            download(files.getValue("world/region/r.0.0.mca")["url"] as String),
        )
        assertEquals(level, download(files.getValue("world/level.dat")["url"] as String))
    }

    @Test
    fun `a server with no working copy answers 404`() {
        given().get("/v1/workspaces/never-synced/head").then().statusCode(404)
    }

    @Test
    fun `commit refuses while a blob it introduced is missing`() {
        val server = "missing-blob"
        open(server, null, file("world/level.dat", "never uploaded")).statusCode(201)
        commit(server, 1).statusCode(409)
        given().get("/v1/workspaces/$server/head").then().statusCode(404)
    }

    /** Two writers, or one that missed a commit: the second finds out before uploading anything. */
    @Test
    fun `a stale base is a conflict`() {
        val server = "stale-base"
        firstSnapshot(server, file("world/level.dat", "a"))
        open(server, null, file("world/level.dat", "b")).statusCode(409)
        open(server, 7, file("world/level.dat", "b")).statusCode(409)
    }

    @Test
    fun `a commit whose base moved is a conflict`() {
        val server = "moved-base"
        val stale =
            open(server, null, file("world/level.dat", "a")).statusCode(201).extract().jsonPath()
        val winner =
            open(server, null, file("world/level.dat", "a")).statusCode(201).extract().jsonPath()
        upload(winner, "a")
        commit(server, winner.getLong("snapshot")).statusCode(200)
        commit(server, stale.getLong("snapshot")).statusCode(409)
    }

    /**
     * A booting server that restored nothing looks exactly like this; only `shrink` says otherwise.
     */
    @Test
    fun `losing a world or most of the bytes needs shrink`() {
        val server = "shrink"
        val big = "x".repeat(1000)
        firstSnapshot(
            server,
            file("world/level.dat", big),
            file("world/dimensions/minecraft/crater/level.dat", "crater"),
        )

        open(server, 1, file("world/level.dat", big)).statusCode(409)
        open(server, 1, file("world/dimensions/minecraft/crater/level.dat", "crater"))
            .statusCode(409)
        open(server, 1, file("world/level.dat", big), shrink = true).statusCode(201)
    }

    @Test
    fun `paths that could escape the server root are refused`() {
        for (path in
            listOf("../etc/passwd", "/app/world/level.dat", "world//level.dat", "world/./x")) {
            open("paths", null, file(path, "x")).statusCode(400)
        }
        open("paths", null, file("world/level.dat", "a"), file("world/level.dat", "b"))
            .statusCode(400)
    }

    /** Thirty-five commits on one day: the newest thirty stay, and so does every blob they name. */
    @Test
    fun `retention keeps the newest snapshots and the blobs they name`() {
        val server = "retention"
        for (n in 1..35) {
            val opened =
                open(server, if (n == 1) null else n - 1L, file("world/level.dat", "level-$n"))
                    .statusCode(201)
                    .extract()
                    .jsonPath()
            upload(opened, "level-$n")
            commit(server, n.toLong()).statusCode(200)
        }

        assertEquals(5, workspaces.prune(server, Instant.now(), dryRun = true))
        assertEquals(5, workspaces.prune(server, Instant.now(), dryRun = false))
        val referenced = workspaces.forgetUnreferencedBlobs(server, dryRun = false)

        assertEquals((6..35).map { sha256("level-$it") }.toSet(), referenced)
        given()
            .get("/v1/workspaces/$server/head")
            .then()
            .statusCode(200)
            .body("snapshot", equalTo(35))
        // A forgotten blob is reported missing again rather than assumed present.
        open(server, 35, file("world/level.dat", "level-1"))
            .statusCode(201)
            .body("missing", hasSize<Any>(1))
    }

    @Test
    fun `a server id is a slug`() {
        given().get("/v1/workspaces/Not_A_Slug/head").then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "player", roles = [])
    fun `only the author groups may keep a working copy`() {
        given().get("/v1/workspaces/round-trip/head").then().statusCode(403)
        open("someone-else", null, file("world/level.dat", "a")).statusCode(403)
    }

    private fun firstSnapshot(server: String, vararg files: Map<String, Any>) {
        val opened = open(server, null, *files).statusCode(201).extract().jsonPath()
        upload(opened, *files.map { contentOf.getValue(it["sha256"] as String) }.toTypedArray())
        commit(server, opened.getLong("snapshot")).statusCode(200)
    }

    private fun open(
        server: String,
        base: Long?,
        vararg files: Map<String, Any>,
        shrink: Boolean = false,
    ) =
        given()
            .contentType(ContentType.JSON)
            .body(mapOf("base" to base, "files" to files.toList(), "shrink" to shrink))
            .post("/v1/workspaces/$server/snapshots")
            .then()

    private fun commit(server: String, snapshot: Long) =
        given().post("/v1/workspaces/$server/snapshots/$snapshot/commit").then()

    /** PUTs each of [contents] to the presigned URL the registry handed out for its digest. */
    private fun upload(opened: JsonPath, vararg contents: String) {
        val urls =
            opened.getList<Map<String, String>>("missing").associate { it["sha256"] to it["url"] }
        for (content in contents) {
            val url = urls[sha256(content)] ?: continue
            val response =
                http.send(
                    HttpRequest.newBuilder(URI.create(url))
                        .PUT(HttpRequest.BodyPublishers.ofString(content))
                        .build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
            assertEquals(200, response.statusCode())
        }
    }

    private fun download(url: String): String =
        http
            .send(
                HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            .body()

    private val contentOf = HashMap<String, String>()

    private fun file(path: String, content: String): Map<String, Any> {
        contentOf[sha256(content)] = content
        return mapOf("path" to path, "sha256" to sha256(content), "size" to content.length)
    }

    private fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
}
