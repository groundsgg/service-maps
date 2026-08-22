package gg.grounds.blob

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import gg.grounds.domain.MapTrust
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlobStorePromotionTest {

    @Test
    fun `object key helpers have only the documented upload and derive paths`() {
        val map = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val attempt = UUID.fromString("00000000-0000-0000-0000-000000000002")

        assertEquals("tmp/uploads/upload/source.tar.zst", BlobStore.uploadKey("upload"))
        assertEquals("tmp/uploads/upload/source.zip", BlobStore.legacyUploadKey("upload"))
        assertEquals(
            "tmp/derive/$map/3/$attempt/bundle.tar.zst",
            BlobStore.deriveBundleKey(map, 3, attempt),
        )
        assertEquals(
            "tmp/derive/$map/3/$attempt/derived-manifest.json",
            BlobStore.deriveManifestKey(map, 3, attempt),
        )
        assertEquals(
            "tmp/derive/$map/3/$attempt/result.json",
            BlobStore.deriveResultKey(map, 3, attempt),
        )
    }

    @Test
    fun `conditional copy binds the selected source version and creates only an absent destination`() {
        val request =
            conditionalCopyRequest(
                sourceBucket = "private",
                sourceKey = "tmp/derive/source",
                sourceVersionToken = "opaque-etag",
                destinationBucket = "public",
                destinationKey = "bundle/digest",
            )

        val headers = request.overrideConfiguration().orElseThrow().headers()
        assertEquals("opaque-etag", headers["x-amz-copy-source-if-match"]?.single())
        assertEquals("*", headers["cf-copy-destination-if-none-match"]?.single())
        assertFalse(headers.keys.any { it.contains("checksum", ignoreCase = true) })
    }

    @Test
    fun `precondition recovery accepts a destination with the expected size`() {
        assertTrue(
            resolveCopyPreconditionFailure(BlobMetadata(sizeBytes = 6), expectedSizeBytes = 6)
        )
    }

    @Test
    fun `precondition recovery rejects a conflicting destination`() {
        assertThrows(BlobIntegrityException::class.java) {
            resolveCopyPreconditionFailure(BlobMetadata(sizeBytes = 7), expectedSizeBytes = 6)
        }
    }

    @Test
    fun `precondition recovery fails closed when no valid destination exists`() {
        assertThrows(BlobCopyPreconditionException::class.java) {
            resolveCopyPreconditionFailure(null, expectedSizeBytes = 6)
        }
    }

    @Test
    fun `real sdk copy sends signed conditional headers and recovers a matching destination`() {
        CopyScript(recoverySize = 6).use { script ->
            assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                script.blobs.copyPrivateToPublic(
                    "source",
                    "destination",
                    expectedSizeBytes = 6,
                    MapTrust.FIRST_PARTY,
                )
            }

            script.assertConditionalCopy()
        }
    }

    @Test
    fun `real sdk copy rejects a conflicting destination after a precondition failure`() {
        CopyScript(recoverySize = 7).use { script ->
            assertThrows(BlobIntegrityException::class.java) {
                assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                    script.blobs.copyPrivateToPublic(
                        "source",
                        "destination",
                        expectedSizeBytes = 6,
                        MapTrust.FIRST_PARTY,
                    )
                }
            }

            script.assertConditionalCopy()
        }
    }

    @Test
    fun `real sdk copy fails closed when a precondition failure has no destination`() {
        CopyScript(recoverySize = null).use { script ->
            assertThrows(BlobCopyPreconditionException::class.java) {
                assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                    script.blobs.copyPrivateToPublic(
                        "source",
                        "destination",
                        expectedSizeBytes = 6,
                        MapTrust.FIRST_PARTY,
                    )
                }
            }

            script.assertConditionalCopy()
        }
    }

    private class CopyScript(private val recoverySize: Long?) : AutoCloseable {
        private val failure = AtomicReference<Throwable?>()
        private var destinationHeads = 0
        private val destinationHeadPaths = mutableListOf<String>()
        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                executor = Executors.newSingleThreadExecutor()
                createContext("/") { exchange -> handle(exchange) }
                start()
            }

        val blobs =
            BlobStore(
                endpoint = "http://${server.address.hostString}:${server.address.port}",
                region = "us-east-1",
                accessKey = "test-access-key",
                secretKey = "test-secret-key",
                privateBucket = "private",
                publicBucket = "public",
                ugcBucket = "ugc",
                contentBaseUrl = "https://content.test",
                ugcBaseUrl = "https://ugc.test",
            )

        private fun handle(exchange: HttpExchange) {
            try {
                exchange.requestBody.use { it.readAllBytes() }
                when (exchange.requestMethod to exchange.requestURI.path) {
                    "HEAD" to "/private/source" -> head(exchange, size = 6, etag = "\"opaque-v1\"")
                    "HEAD" to "/public/destination" -> destinationHead(exchange)
                    "PUT" to "/public/destination" -> copy(exchange)
                    else ->
                        throw AssertionError(
                            "unexpected request ${exchange.requestMethod} ${exchange.requestURI}"
                        )
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
                exchange.sendResponseHeaders(500, -1)
            } finally {
                exchange.close()
            }
        }

        private fun destinationHead(exchange: HttpExchange) {
            destinationHeads += 1
            destinationHeadPaths += exchange.requestURI.path
            if (destinationHeads == 1 || recoverySize == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                head(exchange, size = recoverySize, etag = "\"destination-v1\"")
            }
        }

        private fun copy(exchange: HttpExchange) {
            assertEquals(
                "\"opaque-v1\"",
                exchange.requestHeaders.getFirst("x-amz-copy-source-if-match"),
            )
            assertEquals("*", exchange.requestHeaders.getFirst("cf-copy-destination-if-none-match"))
            val authorization = requireNotNull(exchange.requestHeaders.getFirst("Authorization"))
            assertTrue(authorization.contains("x-amz-copy-source-if-match"))
            assertTrue(authorization.contains("cf-copy-destination-if-none-match"))
            val body = "<Error><Code>PreconditionFailed</Code></Error>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/xml")
            exchange.sendResponseHeaders(412, body.size.toLong())
            exchange.responseBody.write(body)
        }

        private fun head(exchange: HttpExchange, size: Long, etag: String) {
            exchange.responseHeaders.add("Content-Length", size.toString())
            exchange.responseHeaders.add("ETag", etag)
            exchange.sendResponseHeaders(200, -1)
        }

        fun assertConditionalCopy() {
            failure.get()?.let { throw AssertionError("loopback request assertion failed", it) }
            assertEquals(
                2,
                destinationHeads,
                "must re-head exactly the requested public destination",
            )
            assertEquals(listOf("/public/destination", "/public/destination"), destinationHeadPaths)
        }

        override fun close() {
            server.stop(0)
            (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
        }
    }
}
