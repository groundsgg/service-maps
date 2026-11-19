package gg.grounds.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.luben.zstd.ZstdOutputStream
import com.sun.net.httpserver.HttpServer
import gg.grounds.MinioResource
import gg.grounds.PostgresResource
import gg.grounds.blob.BlobStore
import gg.grounds.catalog.DialogueProfileLoader
import gg.grounds.derive.*
import gg.grounds.domain.*
import io.quarkus.test.common.WithTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Actual presigned archive upload → standalone worker → promotion/SQL acceptance → REST read. */
@QuarkusTest
@WithTestResource(PostgresResource::class)
@WithTestResource(MinioResource::class)
@TestSecurity(user = "pipeline-builder", roles = ["grounds-staff"])
class DialoguePipelineIT {
    @Inject lateinit var maps: MapRepository
    @Inject lateinit var versions: MapVersionRepository
    @Inject lateinit var blobs: BlobStore
    @TempDir lateinit var temporary: Path
    private val mapper = ObjectMapper()
    private val image =
        "fixture@sha256:${"b".repeat(64)}" // In-process test; real OCI digests are exercised by

    // ci/verify-native.py.

    private fun resource(path: String) =
        javaClass.getResourceAsStream("/derive/$path")!!.use { it.readAllBytes() }

    private fun digest(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun story(): Map<String, ByteArray> =
        mapOf(
            "dialogues/mechanic.json" to resource("dialogue/dialogues/mechanic.json"),
            "texts/de.json" to resource("dialogue/texts/de.json"),
            "texts/en.json" to resource("dialogue/texts/en.json"),
            "context/host-catalog.json" to resource("dialogue/context/host-catalog.json"),
            "grounds/dialogue.json" to resource("dialogue-grounds/bindings.json"),
            "scene.json" to resource("dialogue-grounds/scene.json"),
        )

    private fun catalogJar(): ByteArray {
        val directory = Files.createTempDirectory(temporary, "catalog-")
        val diagnostics = ByteArrayOutputStream()
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(
            0,
            compiler.run(
                null,
                diagnostics,
                diagnostics,
                "--release",
                "21",
                "-d",
                directory.toString(),
                Path.of("ci/fixtures/GroundsAssetCatalog.java").toAbsolutePath().toString(),
            ),
            diagnostics.toString(),
        )
        return ByteArrayOutputStream()
            .also { output ->
                JarOutputStream(output).use { jar ->
                    Files.walk(directory).use { files ->
                        files
                            .filter { it.toString().endsWith(".class") }
                            .sorted()
                            .forEach { file ->
                                jar.putNextEntry(
                                    JarEntry(
                                            directory.relativize(file).toString().replace('\\', '/')
                                        )
                                        .apply { time = 0 }
                                )
                                jar.write(Files.readAllBytes(file))
                                jar.closeEntry()
                            }
                    }
                }
            }
            .toByteArray()
    }

    private fun archive(files: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream()
            .also { output ->
                ZstdOutputStream(output).use { compressed ->
                    TarArchiveOutputStream(compressed).use { tar ->
                        (files + ("region/r.0.0.mca" to ByteArray(8192))).toSortedMap().forEach {
                            (name, bytes) ->
                            tar.putArchiveEntry(
                                TarArchiveEntry(name).apply {
                                    size = bytes.size.toLong()
                                    setModTime(0L)
                                }
                            )
                            tar.write(bytes)
                            tar.closeArchiveEntry()
                        }
                    }
                }
            }
            .toByteArray()

    private data class Run(
        val map: MapRecord,
        val identity: DeriveIdentity,
        val snapshot: MapVersionRecord,
    )

    private fun runWorker(
        files: Map<String, ByteArray>,
        configured: Boolean = true,
        badOperatorHost: Boolean = false,
    ): Run {
        val profile =
            if (!badOperatorHost) resource("dialogue-grounds/profile.json")
            else {
                val value = mapper.readTree(resource("dialogue-grounds/profile.json")) as ObjectNode
                value.put("hostCatalogJson", "{}")
                mapper.writeValueAsBytes(value)
            }
        val jar = catalogJar()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/profile") { exchange ->
            exchange.sendResponseHeaders(200, profile.size.toLong())
            exchange.responseBody.use { it.write(profile) }
        }
        server.createContext("/catalog.jar") { exchange ->
            exchange.sendResponseHeaders(200, jar.size.toLong())
            exchange.responseBody.use { it.write(jar) }
        }
        server.start()
        try {
            val pin =
                DialogueProfilePin(
                    "grounds:dialogue-contract",
                    "1",
                    digest(profile),
                    profile.size.toLong(),
                    URI(
                        "https://maps-fixtures/dialogue/profiles/contract/1/${digest(profile)}/profile.json"
                    ),
                )
            val catalog =
                AssetCatalogCandidate(
                    "pinned",
                    "grounds:resourcepacks",
                    "e1-contract",
                    "gg.grounds:resourcepacks-catalog:e1-contract",
                    "catalog.jar",
                    URI("http://127.0.0.1:${server.address.port}/catalog.jar"),
                    digest(jar),
                    jar.size.toLong(),
                )
            val execution =
                DeriveExecutionContract(
                    2,
                    image,
                    if (configured) listOf(pin) else emptyList(),
                    listOf(catalog),
                )
            val map =
                maps.create(
                    MapAddress("grounds", "pipeline-${UUID.randomUUID()}"),
                    "Story",
                    MapKind.ARENA,
                    false,
                    MapTrust.FIRST_PARTY,
                    "pipeline-builder",
                )
            val upload =
                given().post("/v1/maps/${map.address}/uploads").then().statusCode(200).extract()
            val bytes = archive(files)
            val uploaded =
                HttpClient.newHttpClient()
                    .send(
                        HttpRequest.newBuilder(URI(upload.path<String>("url")))
                            .timeout(Duration.ofSeconds(10))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                            .build(),
                        HttpResponse.BodyHandlers.discarding(),
                    )
            assertEquals(200, uploaded.statusCode())
            val sourceKey = BlobStore.uploadKey(upload.path<String>("uploadId"))
            versions.commitWithDeriveRequest(
                map.id,
                digest(bytes),
                sourceKey,
                true,
                null,
                null,
                "pipeline-builder",
            )
            val id = DeriveIdentity(map.id, 1, UUID.randomUUID(), digest(bytes))
            versions.claimForDerive(map.id, 1, id.attempt)
            versions.bindExecution(id, execution)
            val ttl = Duration.ofMinutes(30)
            val request =
                DeriveRequest(
                    schemaVersion = 2,
                    mapId = map.id,
                    version = 1,
                    attempt = id.attempt,
                    sourceSha256 = id.sourceSha256,
                    sourceUrl = URI(blobs.presignGet(sourceKey, ttl)),
                    bundleUrl =
                        URI(
                            blobs.presignPut(BlobStore.deriveBundleKey(map.id, 1, id.attempt), ttl)
                        ),
                    manifestUrl =
                        URI(
                            blobs.presignPut(
                                BlobStore.deriveManifestKey(map.id, 1, id.attempt),
                                ttl,
                            )
                        ),
                    resultUrl =
                        URI(
                            blobs.presignPut(BlobStore.deriveResultKey(map.id, 1, id.attempt), ttl)
                        ),
                    catalogCandidates = execution.catalogCandidates,
                    workerImage = image,
                    dialogueProfiles = execution.dialogueProfiles,
                )
            val result =
                DeriveWorkerMain.run(
                    arrayOf("--request-env", "E1_REQUEST", "--allow-loopback-http"),
                    { WorkerHttpTransfer(it) },
                    { name ->
                        if (name == "E1_REQUEST") CanonicalJson.write(request).decodeToString()
                        else null
                    },
                    profileLoaderFactory = {
                        DialogueProfileLoader(
                            object : WorkerHttpTransfer(true, 1048576) {
                                override fun download(uri: URI, destination: Path): String =
                                    super.download(
                                        URI("http://127.0.0.1:${server.address.port}/profile"),
                                        destination,
                                    )
                            }
                        )
                    },
                )
            assertEquals(
                0,
                result,
                "The process acknowledges a written result marker; scope is checked separately",
            )
            return Run(map, id, versions.find(map.id, 1)!!)
        } finally {
            server.stop(0)
        }
    }

    private fun accept(run: Run) {
        val restricted =
            object : MapVersionRepository by versions {
                override fun listReconcileCandidates() = listOf(run.snapshot)
            }
        val jobs =
            object : DeriveJobGateway {
                override fun find(identity: DeriveIdentity) = DeriveJobStatus.SUCCEEDED

                override fun create(request: DeriveJobRequest) {
                    error("No Kubernetes writes in this test")
                }
            }
        val reconciler =
            DeriveReconciler(
                restricted,
                DeriveCoordinator(restricted, jobs, { emptyList() }, false),
                jobs,
                blobs,
            )
        try {
            reconciler.reconcile()
        } finally {
            reconciler.close()
        }
    }

    private fun assertFailure(run: Run, scope: String) {
        accept(run)
        given()
            .get("/v1/maps/${run.map.address}/versions/1")
            .then()
            .statusCode(200)
            .body("state", equalTo("DERIVE_FAILED"))
            .body("deriveFailureScope", equalTo(scope))
            .body("dialogue.status", equalTo("INVALID"))
            .body("dialogue.proof", equalTo(null))
    }

    @Test
    fun pureMapHasPositiveAbsenceProof() {
        val run = runWorker(emptyMap())
        accept(run)
        given()
            .get("/v1/maps/${run.map.address}/versions/1")
            .then()
            .statusCode(200)
            .body("state", equalTo("PUBLISHED"))
            .body("dialogue.status", equalTo("NONE"))
            .body("dialogue.proof.sourceSha256", equalTo(run.identity.sourceSha256))
            .body("dialogue.proof.files.size()", equalTo(0))
    }

    @Test
    fun completeStoryPreservesSourceProfileAndLibraryPinThroughApi() {
        val run = runWorker(story())
        accept(run)
        val version =
            given()
                .get("/v1/maps/${run.map.address}/versions/1")
                .then()
                .statusCode(200)
                .body("state", equalTo("PUBLISHED"))
                .body("dialogue.status", equalTo("VALID"))
                .body("dialogue.proof.sourceSha256", equalTo(run.identity.sourceSha256))
                .body(
                    "dialogue.proof.profile.sha256",
                    equalTo(digest(resource("dialogue-grounds/profile.json"))),
                )
                .body(
                    "dialogue.proof.contentPin",
                    equalTo("2c71e95da87ece2edd26edfe57393ca05fd6e397b0218150aadf9711d80736ac"),
                )
                .body("dialogue.proof.dialogueIds[0]", equalTo("demo:mechanic"))
                .extract()
        assertEquals(
            version.path<String>("scene.sha256"),
            version.path<String>("dialogue.proof.sceneSha256"),
        )
        val pins = versions.find(run.map.id, 1)!!.dialogue.proof!!.files.associateBy { it.path }
        story()
            .filterKeys { it != "scene.json" }
            .forEach { (file, bytes) -> assertEquals(digest(bytes), pins.getValue(file).sha256) }
    }

    @Test
    fun wrongTargetHasExactSharedDiagnostic() {
        val files = story().toMutableMap()
        val definition = mapper.readTree(files.getValue("dialogues/mechanic.json")) as ObjectNode
        (definition.path("nodes").path("farewell") as ObjectNode).put("next", "missing")
        files["dialogues/mechanic.json"] = mapper.writeValueAsBytes(definition)
        val run = runWorker(files)
        assertFailure(run, "CONTENT")
        val problem =
            versions.find(run.map.id, 1)!!.dialogue.problems.single {
                it.code == "REFERENCE_UNKNOWN"
            }
        assertEquals("dialogues/mechanic.json", problem.file)
        assertEquals("/nodes/farewell", problem.pointer)
        assertEquals("farewell", problem.nodeId)
    }

    @Test
    fun forgedHostSnapshotCannotPublish() {
        val files = story().toMutableMap()
        files["context/host-catalog.json"] = "{}".toByteArray()
        val run = runWorker(files)
        assertFailure(run, "CONTENT")
        assertEquals(
            "DIALOGUE_HOST_MISMATCH",
            versions.find(run.map.id, 1)!!.dialogue.problems.single().code,
        )
    }

    @Test
    fun absentOperatorConfigurationIsSystemFailure() {
        assertFailure(runWorker(story(), configured = false), "SYSTEM")
    }

    @Test
    fun invalidOperatorHostCannotAttestEvenPureMapAbsence() {
        assertFailure(runWorker(emptyMap(), badOperatorHost = true), "SYSTEM")
    }

    @Test
    fun oldWorkerResultCannotReplaceNewAttempt() {
        val run = runWorker(story())
        versions.acceptFailure(
            run.identity,
            DerivedFailure(
                DeriveFailureScope.SYSTEM,
                true,
                listOf(
                    DeriveProblem(DeriveFailureScope.SYSTEM, null, "RETRY", null, "fixture retry")
                ),
            ),
        )
        val next = versions.retrySystemFailure(run.map.id, 1)
        accept(run)
        val current = versions.find(run.map.id, 1)!!
        assertEquals(next.deriveAttempt, current.deriveAttempt)
        assertEquals(VersionState.DERIVING, current.state)
        assertNull(current.dialogue.proof)
    }
}
