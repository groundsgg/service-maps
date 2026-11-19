package gg.grounds.catalog

import com.sun.net.httpserver.HttpServer
import gg.grounds.derive.WorkerHttpTransfer
import gg.grounds.domain.DialogueProfilePin
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DialogueProfileLoaderTest {
    private fun profile(host: String) =
        com.fasterxml.jackson.databind
            .ObjectMapper()
            .writeValueAsBytes(
                mapOf(
                    "schemaVersion" to 1,
                    "id" to "grounds:test",
                    "version" to "1",
                    "hostCatalogJson" to host,
                    "assetCatalog" to mapOf("id" to "assets", "version" to "1"),
                    "actionCatalogs" to listOf(mapOf("id" to "actions", "version" to "1")),
                    "startActions" to emptyMap<String, Any>(),
                    "npcAssets" to emptyMap<String, Any>(),
                )
            )

    private val body =
        profile(
            """{"schemaVersion":1,"generation":"test","speakers":[],"conditions":{},"actions":{},"gestures":[],"capabilities":[],"durableOutcomes":false}"""
        )

    @Test
    fun rejectsInvalidSharedHostMetadata() {
        val invalid = profile("{}")
        server(bytes = invalid) { loader ->
            assertThrows<CatalogTransferException> {
                loader.load(pin(size = invalid.size.toLong(), sha = digest(invalid)))
            }
        }
    }

    private fun digest(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun pin(
        size: Long = body.size.toLong(),
        sha: String = digest(body),
        version: String = "1",
    ) = DialogueProfilePin("grounds:test", version, sha, size, URI("https://cdn.test/profile.json"))

    private fun server(
        code: Int = 200,
        bytes: ByteArray = body,
        block: (DialogueProfileLoader) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/profile") { exchange ->
            if (code == 302)
                exchange.responseHeaders.add(
                    "Location",
                    "http://127.0.0.1:${server.address.port}/forbidden",
                )
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/forbidden") { error("Redirect must never be followed") }
        server.start()
        try {
            // Keep real HTTP/limits/deadlines; only translate trusted HTTPS to test-only loopback.
            val transfer =
                object : WorkerHttpTransfer(true, 1048576) {
                    override fun download(uri: URI, destination: Path): String =
                        super.download(
                            URI("http://127.0.0.1:${server.address.port}/profile"),
                            destination,
                        )
                }
            block(DialogueProfileLoader(transfer))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun resolvesExactPinnedVersion() {
        server { loader -> assertEquals("1", loader.load(pin()).version) }
    }

    @Test
    fun checksDeclaredAndActualSize() {
        server { loader ->
            assertThrows<CatalogTransferException> {
                loader.load(pin(size = body.size.toLong() + 1))
            }
        }
    }

    @Test
    fun rejectsRedirectAndDigestMismatch() {
        server(code = 302) { loader ->
            assertThrows<CatalogTransferException> { loader.load(pin()) }
        }
        server { loader ->
            assertThrows<CatalogTransferException> { loader.load(pin(sha = "a".repeat(64))) }
        }
    }

    @Test
    fun rejectsMismatchingProfileIdentity() {
        server { loader ->
            assertThrows<CatalogTransferException> { loader.load(pin(version = "2")) }
        }
    }
}
