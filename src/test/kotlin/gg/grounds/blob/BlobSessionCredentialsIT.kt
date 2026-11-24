package gg.grounds.blob

import com.sun.net.httpserver.HttpServer
import gg.grounds.PostgresResource
import io.quarkus.test.common.*
import io.quarkus.test.junit.*
import jakarta.inject.Inject
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import org.junit.jupiter.api.Test

class SessionCredentialHttpResource : QuarkusTestResourceLifecycleManager {
    private lateinit var server: HttpServer

    override fun start(): Map<String, String> {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            token.set(exchange.requestHeaders.getFirst("X-Amz-Security-Token"))
            val body = "hello".toByteArray()
            exchange.responseHeaders.add("ETag", "\"fixture\"")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        return mapOf(
            "grounds.maps.r2.endpoint" to "http://127.0.0.1:${server.address.port}",
            "grounds.maps.r2.region" to "us-east-1",
            "grounds.maps.r2.access-key" to "test-access",
            "grounds.maps.r2.secret-key" to "test-secret",
            "grounds.maps.r2.private-bucket" to "private",
            "grounds.maps.r2.public-bucket" to "public",
            "grounds.maps.r2.ugc-bucket" to "ugc",
            "grounds.maps.r2.session-token" to "fixture-short-lived-session",
        )
    }

    override fun stop() {
        if (::server.isInitialized) server.stop(0)
    }

    companion object {
        val token = AtomicReference<String>()
    }
}

@QuarkusTest
@WithTestResource(value = PostgresResource::class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
@WithTestResource(
    value = SessionCredentialHttpResource::class,
    scope = TestResourceScope.RESTRICTED_TO_CLASS,
)
class BlobSessionCredentialsIT {
    @Inject lateinit var blobs: BlobStore

    @Test
    fun `temporary S3 credentials sign both private SDK reads and backend URLs`() {
        assertContentEquals("hello".toByteArray(), blobs.getPrivate("marker", 5))
        assertEquals("fixture-short-lived-session", SessionCredentialHttpResource.token.get())
        assertTrue(
            blobs.presignGet("marker").contains("X-Amz-Security-Token=fixture-short-lived-session")
        )
        assertTrue(
            blobs.presignPut("marker").contains("X-Amz-Security-Token=fixture-short-lived-session")
        )
    }
}
