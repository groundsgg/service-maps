package gg.grounds.lod.runtime

import com.google.gson.Gson
import com.sun.net.httpserver.HttpServer
import gg.grounds.lod.format.*
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class LodEntitledHttpClientTest {
    private val expected =
        LodExpectation("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64))
    private val capability = "secret-capability-" + "x".repeat(40)
    private val json = Gson()

    @Test
    fun `discovery is lazy authenticated exact identity and reused by grants`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val discovery = AtomicInteger()
        val run = UUID.randomUUID()
        server.createContext("/v1/maps/lod-serving/discover") { exchange ->
            discovery.incrementAndGet()
            assertEquals("POST", exchange.requestMethod)
            assertEquals(capability, exchange.requestHeaders.getFirst(LOD_PILOT_HEADER))
            val request =
                json.fromJson(exchange.requestBody.reader(), LodServingDiscoveryRequest::class.java)
            assertEquals(expected.mapSha256, request.mapBundleSha256)
            assertEquals(expected.sourceSha256, request.sourceBundleSha256)
            assertEquals(expected.registrySha256, request.registrySha256)
            assertEquals(expected.generatorSha256, request.generatorSha256)
            val bytes = json.toJson(selection(run)).toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/maps/lod-pilots/$run/credits") { exchange ->
            assertEquals(capability, exchange.requestHeaders.getFirst(LOD_PILOT_HEADER))
            val request =
                json.fromJson(exchange.requestBody.reader(), LodPayloadRequest::class.java)
            val bytes =
                json
                    .toJson(
                        LodPayloadGrant(
                            runId = run.toString(),
                            requestId = request.requestId,
                            bytes = request.bytes,
                            validForMillis = 1000,
                        )
                    )
                    .toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client =
                LodEntitledHttpClient(
                    URI("http://127.0.0.1:${server.address.port}"),
                    capability,
                    expected,
                    allowLoopbackHttp = true,
                )
            assertEquals(0, discovery.get())
            repeat(2) { assertEquals(1000, client.payload(UUID.randomUUID(), 1000).value.bytes) }
            assertEquals(1, discovery.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `discovery not ready and service errors are transient while auth and closed quotas stay permanent`() {
        for (status in listOf(404, 503, 401, 403, 410, 302)) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val calls = AtomicInteger()
            server.createContext("/v1/maps/lod-serving/discover") { exchange ->
                calls.incrementAndGet()
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            server.start()
            try {
                val client =
                    LodEntitledHttpClient(
                        URI("http://127.0.0.1:${server.address.port}"),
                        capability,
                        expected,
                        allowLoopbackHttp = true,
                    )
                val error = assertFailsWith<LodUnavailable> { client.source(UUID.randomUUID()) }
                assertEquals(status !in listOf(404, 503), error.permanent, "status $status")
                if (error.permanent) {
                    assertFailsWith<LodUnavailable> { client.source(UUID.randomUUID()) }
                    assertEquals(1, calls.get())
                }
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun `wrong discovery source never obtains credit or terrain`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/maps/lod-serving/discover") { exchange ->
            val bytes =
                json
                    .toJson(selection(UUID.randomUUID()).copy(sourceBundleSha256 = "f".repeat(64)))
                    .toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client =
                LodEntitledHttpClient(
                    URI("http://127.0.0.1:${server.address.port}"),
                    capability,
                    expected,
                    allowLoopbackHttp = true,
                )
            assertTrue(
                assertFailsWith<LodUnavailable> { client.payload(UUID.randomUUID(), 1000) }
                    .permanent
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `real HTTP not ready discovery backs off across UUIDs and later quota closure stays closed`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = AtomicInteger()
        val clock = java.util.concurrent.atomic.AtomicLong(1)
        val run = UUID.randomUUID()
        var status = 404
        server.createContext("/v1/maps/lod-serving/discover") { exchange ->
            calls.incrementAndGet()
            if (status == 200) {
                val bytes = json.toJson(selection(run)).toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
        }
        server.createContext("/v1/maps/lod-pilots/$run/credits") { exchange ->
            calls.incrementAndGet()
            if (status == 200) {
                val request =
                    json.fromJson(exchange.requestBody.reader(), LodPayloadRequest::class.java)
                val bytes =
                    json
                        .toJson(
                            LodPayloadGrant(
                                runId = run.toString(),
                                requestId = request.requestId,
                                bytes = request.bytes,
                                validForMillis = 1000,
                            )
                        )
                        .toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
        }
        server.start()
        try {
            val client =
                LodEntitledHttpClient(
                    URI("http://127.0.0.1:${server.address.port}"),
                    capability,
                    expected,
                    allowLoopbackHttp = true,
                    nanos = clock::get,
                )
            val gate = LodCreditGate(client, emptySet(), clock::get, entitled = true)
            val player = UUID.randomUUID()
            assertNull(gate.reserve(player, 1000))
            repeat(100) { assertNull(gate.reserve(UUID.randomUUID(), 1000)) }
            assertEquals(1, calls.get())
            clock.addAndGet(1000000000)
            status = 503
            assertNull(gate.reserve(player, 1000))
            clock.addAndGet(2000000000)
            status = 200
            assertNotNull(gate.reserve(player, 1000))
            assertEquals(4, calls.get())
            clock.addAndGet(1000000001)
            status = 410
            assertNull(gate.reserve(player, 1000))
            clock.addAndGet(1000000000000)
            status = 200
            assertNull(gate.reserve(UUID.randomUUID(), 1000))
            assertEquals(5, calls.get())
        } finally {
            server.stop(0)
        }
    }

    private fun selection(run: UUID) =
        LodServingSelection(
            runId = run.toString(),
            mapBundleSha256 = expected.mapSha256,
            sourceBundleSha256 = expected.sourceSha256,
            registrySha256 = expected.registrySha256,
            generatorSha256 = expected.generatorSha256,
        )
}
