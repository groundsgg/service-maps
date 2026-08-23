package gg.grounds.derive

import com.github.luben.zstd.ZstdOutputStream
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeriveWorkerMainIT {
    @Test
    fun `reports a declared source compressed limit as nonretryable content`() =
        workerServer(byteArrayOf(1, 2)) { server, uploads ->
            run(
                server,
                request(server, digest(byteArrayOf(1, 2))),
                WorkerHttpTransfer(true, maxSourceBytes = 1),
            )

            val result = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
            assertEquals("CONTENT", result.scope.name)
            assertFalse(result.retryable)
        }

    @Test
    fun `reports a chunked source compressed limit as nonretryable content`() =
        workerServer(byteArrayOf(1, 2), sourceChunked = true) { server, uploads ->
            run(
                server,
                request(server, digest(byteArrayOf(1, 2))),
                WorkerHttpTransfer(true, maxSourceBytes = 1),
            )

            val result = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
            assertEquals("CONTENT", result.scope.name)
            assertFalse(result.retryable)
        }

    @Test
    fun `uploads bundle then manifest then success result for a valid loopback request`() =
        workerServer(archive(mapOf("level.dat" to "world".encodeToByteArray()))) { server, uploads
            ->
            run(
                server,
                request(server, digest(archive(mapOf("level.dat" to "world".encodeToByteArray())))),
            )

            assertEquals(listOf("/bundle", "/manifest", "/result"), uploads.map { it.first })
            val result = CanonicalJson.readResult(uploads.last().second) as DeriveSuccess
            assertEquals(DeriveResultKind.SUCCESS, result.kind)
            assertEquals(digest(uploads[0].second), result.bundleSha256)
            assertEquals(uploads[0].second.size.toLong(), result.bundleSize)
            assertEquals(digest(uploads[1].second), result.manifestSha256)
            assertEquals(uploads[1].second.size.toLong(), result.manifestSize)
        }

    @Test
    fun `reports digest mismatch as content failure without artifact uploads`() =
        workerServer(archive(mapOf("level.dat" to "world".encodeToByteArray()))) { server, uploads
            ->
            run(server, request(server, "0".repeat(64)))

            assertEquals(listOf("/result"), uploads.map { it.first })
            val result = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
            assertEquals("CONTENT", result.scope.name)
            assertFalse(result.retryable)
        }

    @Test
    fun `reports malformed source archive as content failure without artifact uploads`() =
        workerServer("not-a-zstd-frame".encodeToByteArray()) { server, uploads ->
            run(server, request(server, digest("not-a-zstd-frame".encodeToByteArray())))

            assertEquals(listOf("/result"), uploads.map { it.first })
            val result = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
            assertEquals("CONTENT", result.scope.name)
            assertFalse(result.retryable)
        }

    @Test
    fun `reports failed bundle upload as retryable system failure without manifest or success`() =
        workerServer(
            archive(mapOf("level.dat" to "world".encodeToByteArray())),
            bundleStatus = 500,
        ) { server, uploads ->
            run(
                server,
                request(server, digest(archive(mapOf("level.dat" to "world".encodeToByteArray())))),
            )

            assertEquals(listOf("/bundle", "/result"), uploads.map { it.first })
            val result = CanonicalJson.readResult(uploads.last().second) as DeriveFailure
            assertEquals("SYSTEM", result.scope.name)
            assertTrue(result.retryable)
            assertFalse(uploads.last().second.decodeToString().contains("secret=redact"))
        }

    @Test
    fun `failed manifest upload sends only the acknowledged system fallback marker`() =
        workerServer(
            archive(mapOf("level.dat" to "world".encodeToByteArray())),
            manifestStatus = 500,
        ) { server, uploads ->
            assertEquals(
                0,
                run(
                    server,
                    request(
                        server,
                        digest(archive(mapOf("level.dat" to "world".encodeToByteArray()))),
                    ),
                ),
            )

            assertEquals(listOf("/bundle", "/manifest", "/result"), uploads.map { it.first })
            val fallback = CanonicalJson.readResult(uploads.last().second) as DeriveFailure
            assertEquals("SYSTEM", fallback.scope.name)
            assertTrue(fallback.retryable)
        }

    @Test
    fun `failed result upload followed by failed fallback returns nonzero with no later artifact`() =
        workerServer(
            archive(mapOf("level.dat" to "world".encodeToByteArray())),
            resultStatuses = listOf(500, 500),
        ) { server, uploads ->
            assertEquals(
                1,
                run(
                    server,
                    request(
                        server,
                        digest(archive(mapOf("level.dat" to "world".encodeToByteArray()))),
                    ),
                    expectedExit = 1,
                ),
            )

            assertEquals(
                listOf("/bundle", "/manifest", "/result", "/result"),
                uploads.map { it.first },
            )
        }

    @Test
    fun `content marker upload failure returns nonzero and never uploads artifacts`() =
        workerServer(
            archive(mapOf("level.dat" to "world".encodeToByteArray())),
            resultStatuses = listOf(500),
        ) { server, uploads ->
            assertEquals(1, run(server, request(server, "0".repeat(64)), expectedExit = 1))

            assertEquals(listOf("/result"), uploads.map { it.first })
        }

    @Test
    fun `preflight rejects every unsigned request URL and unused catalog without HTTP`() {
        workerServer(ByteArray(0)) { server, uploads ->
            val valid = request(server, "0".repeat(64))
            val invalid =
                listOf<DeriveRequest.() -> DeriveRequest>(
                    { copy(sourceUrl = URI("http://example.invalid/source")) },
                    { copy(bundleUrl = URI("http://example.invalid/bundle")) },
                    { copy(manifestUrl = URI("http://example.invalid/manifest")) },
                    { copy(resultUrl = URI("http://example.invalid/result")) },
                    {
                        copy(
                            catalogCandidates =
                                listOf(
                                    AssetCatalogCandidate(
                                        "stable",
                                        "unused",
                                        "1",
                                        "unused:1",
                                        "unused.jar",
                                        URI("http://example.invalid/catalog"),
                                        "0".repeat(64),
                                        1,
                                    )
                                )
                        )
                    },
                )
            invalid.forEach { mutate -> assertEquals(2, runRequest(mutate(valid))) }
            assertTrue(uploads.isEmpty())
        }
    }

    @Test
    fun `reports transient source failure as retryable system failure`() =
        workerServer(ByteArray(0), sourceStatus = 503) { server, uploads ->
            run(server, request(server, "0".repeat(64)))

            assertEquals(listOf("/result"), uploads.map { it.first })
            val result = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
            assertEquals("SYSTEM", result.scope.name)
            assertTrue(result.retryable)
        }

    @Test
    fun `reports expired source URL as retryable system failure`() =
        workerServer(ByteArray(0), sourceStatus = 403) { server, uploads ->
            run(server, request(server, "0".repeat(64)))

            assertEquals(listOf("/result"), uploads.map { it.first })
            val result = CanonicalJson.readResult(uploads.single().second) as DeriveFailure
            assertEquals("SYSTEM", result.scope.name)
            assertTrue(result.retryable)
        }

    @Test
    fun `source redirect is rejected and produces only a system marker`() =
        workerServer(ByteArray(0), sourceStatus = 302) { server, uploads ->
            assertEquals(0, run(server, request(server, "0".repeat(64))))
            assertEquals(listOf("/result"), uploads.map { it.first })
            assertEquals(
                "SYSTEM",
                (CanonicalJson.readResult(uploads.single().second) as DeriveFailure).scope.name,
            )
        }

    @Test
    fun `artifact redirect stops the pipeline before manifest and success`() =
        workerServer(
            archive(mapOf("level.dat" to "world".encodeToByteArray())),
            bundleStatus = 302,
        ) { server, uploads ->
            assertEquals(
                0,
                run(
                    server,
                    request(
                        server,
                        digest(archive(mapOf("level.dat" to "world".encodeToByteArray()))),
                    ),
                ),
            )
            assertEquals(listOf("/bundle", "/result"), uploads.map { it.first })
        }

    @Test
    fun `result redirect is rejected then replaced by a final system fallback marker`() =
        workerServer(
            archive(mapOf("level.dat" to "world".encodeToByteArray())),
            resultStatuses = listOf(302, 200),
        ) { server, uploads ->
            assertEquals(
                0,
                run(
                    server,
                    request(
                        server,
                        digest(archive(mapOf("level.dat" to "world".encodeToByteArray()))),
                    ),
                ),
            )
            assertEquals(
                listOf("/bundle", "/manifest", "/result", "/result"),
                uploads.map { it.first },
            )
            assertEquals(
                "SYSTEM",
                (CanonicalJson.readResult(uploads.last().second) as DeriveFailure).scope.name,
            )
        }

    @Test
    fun `mutation contacting any URL before malformed request validation is rejected`() =
        workerServer(ByteArray(0)) { server, uploads ->
            val malformed =
                "{\"sourceUrl\":\"http://127.0.0.1:${server.address.port}/source\"}"
                    .encodeToByteArray()
            assertEquals(2, runRaw(malformed, "--request-file", "--allow-loopback-http"))
            assertTrue(uploads.isEmpty())
        }

    @Test
    fun `mutation accepting production HTTP preflight is rejected before source GET`() =
        workerServer(ByteArray(0)) { server, uploads ->
            val request = request(server, "0".repeat(64))
            val file = Files.createTempFile("derive-request-", ".json")
            try {
                Files.write(file, CanonicalJson.write(request))
                assertEquals(2, DeriveWorkerMain.run(arrayOf("--request-file", file.toString())))
            } finally {
                Files.deleteIfExists(file)
            }
            assertTrue(uploads.isEmpty())
        }

    @Test
    fun `invalid CLI invocations return nonzero before any HTTP request`() =
        workerServer(ByteArray(0)) { server, uploads ->
            val file = Files.createTempFile("derive-request-", ".json")
            try {
                Files.write(file, CanonicalJson.write(request(server, "0".repeat(64))))
                listOf(
                        arrayOf("--unknown"),
                        arrayOf("--request-file"),
                        arrayOf(
                            "--request-env",
                            "DERIVE_REQUEST_JSON",
                            "--request-file",
                            file.toString(),
                        ),
                        arrayOf("--request-file", file.toString()),
                    )
                    .forEach { args -> assertEquals(2, DeriveWorkerMain.run(args)) }
            } finally {
                Files.deleteIfExists(file)
            }
            assertTrue(uploads.isEmpty())
        }

    private fun workerServer(
        source: ByteArray,
        sourceStatus: Int = 200,
        sourceChunked: Boolean = false,
        bundleStatus: Int = 200,
        manifestStatus: Int = 200,
        resultStatuses: List<Int> = listOf(200),
        block: (HttpServer, MutableList<Pair<String, ByteArray>>) -> Unit,
    ) {
        val uploads = mutableListOf<Pair<String, ByteArray>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/source") { exchange ->
                if (sourceChunked && sourceStatus == 200) {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { it.write(source) }
                } else {
                    respond(exchange, sourceStatus, source)
                }
            }
            var resultAttempt = 0
            listOf("/bundle", "/manifest", "/result").forEach { path ->
                server.createContext(path) { exchange ->
                    uploads += path to exchange.requestBody.readBytes()
                    val status =
                        when (path) {
                            "/bundle" -> bundleStatus
                            "/manifest" -> manifestStatus
                            else ->
                                resultStatuses.getOrElse(resultAttempt++) { resultStatuses.last() }
                        }
                    respond(exchange, status, ByteArray(0))
                }
            }
            server.start()
            block(server, uploads)
        } finally {
            server.stop(0)
        }
    }

    private fun run(
        server: HttpServer,
        request: DeriveRequest,
        transfer: WorkerHttpTransfer = WorkerHttpTransfer(true),
        expectedExit: Int = 0,
    ): Int {
        val file = Files.createTempFile("derive-request-", ".json")
        try {
            Files.write(file, CanonicalJson.write(request))
            val exit =
                DeriveWorkerMain.run(
                    arrayOf("--request-file", file.toString(), "--allow-loopback-http"),
                    { transfer },
                )
            assertEquals(expectedExit, exit)
            return exit
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun runRequest(request: DeriveRequest): Int {
        val file = Files.createTempFile("derive-request-", ".json")
        try {
            Files.write(file, CanonicalJson.write(request))
            return DeriveWorkerMain.run(
                arrayOf("--request-file", file.toString(), "--allow-loopback-http")
            )
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun runRaw(bytes: ByteArray, vararg flags: String): Int {
        val file = Files.createTempFile("derive-request-", ".json")
        try {
            Files.write(file, bytes)
            return DeriveWorkerMain.run(
                arrayOf(
                    *flags.take(1).toTypedArray(),
                    file.toString(),
                    *flags.drop(1).toTypedArray(),
                )
            )
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun request(server: HttpServer, sourceSha256: String): DeriveRequest {
        val base = "http://127.0.0.1:${server.address.port}"
        return DeriveRequest(
            mapId = UUID.randomUUID(),
            version = 1,
            attempt = UUID.randomUUID(),
            sourceSha256 = sourceSha256,
            sourceUrl = URI("$base/source?secret=redact"),
            bundleUrl = URI("$base/bundle?secret=redact"),
            manifestUrl = URI("$base/manifest?secret=redact"),
            resultUrl = URI("$base/result?secret=redact"),
            catalogCandidates = emptyList(),
        )
    }

    private fun archive(files: Map<String, ByteArray>): ByteArray {
        val tar = ByteArrayOutputStream()
        TarArchiveOutputStream(tar).use { output ->
            files.forEach { (name, bytes) ->
                output.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                output.write(bytes)
                output.closeArchiveEntry()
            }
        }
        return ByteArrayOutputStream()
            .also { compressed -> ZstdOutputStream(compressed).use { it.write(tar.toByteArray()) } }
            .toByteArray()
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun respond(exchange: HttpExchange, status: Int, body: ByteArray) {
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}
