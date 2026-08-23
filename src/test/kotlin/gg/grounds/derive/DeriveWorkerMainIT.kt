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
            assertTrue(uploads[0].second.isNotEmpty())
            assertTrue(uploads[1].second.isNotEmpty())
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

    private fun workerServer(
        source: ByteArray,
        sourceStatus: Int = 200,
        bundleStatus: Int = 200,
        block: (HttpServer, MutableList<Pair<String, ByteArray>>) -> Unit,
    ) {
        val uploads = mutableListOf<Pair<String, ByteArray>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/source") { exchange -> respond(exchange, sourceStatus, source) }
            listOf("/bundle", "/manifest", "/result").forEach { path ->
                server.createContext(path) { exchange ->
                    uploads += path to exchange.requestBody.readBytes()
                    respond(exchange, if (path == "/bundle") bundleStatus else 200, ByteArray(0))
                }
            }
            server.start()
            block(server, uploads)
        } finally {
            server.stop(0)
        }
    }

    private fun run(server: HttpServer, request: DeriveRequest) {
        val file = Files.createTempFile("derive-request-", ".json")
        try {
            Files.write(file, CanonicalJson.write(request))
            assertEquals(
                0,
                DeriveWorkerMain.run(
                    arrayOf("--request-file", file.toString(), "--allow-loopback-http")
                ),
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
