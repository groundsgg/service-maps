package gg.grounds.lod.runtime

import com.google.gson.Gson
import com.sun.net.httpserver.HttpServer
import gg.grounds.lod.format.*
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class LodPilotHttpClientTest {
    private val run = UUID.randomUUID()
    private val player = UUID.randomUUID()
    private val sha = "a".repeat(64)
    private val capability = "private-test-capability-" + "x".repeat(40)
    private val clock = AtomicLong(1000000)
    private val gson = Gson()

    private fun fixture(block: (HttpServer, LodPilotHttpClient) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try {
            val client =
                LodPilotHttpClient(
                    URI("http://127.0.0.1:${server.address.port}"),
                    run,
                    capability,
                    sha,
                    allowLoopbackHttp = true,
                    nanos = clock::get,
                )
            block(server, client)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `late credits expire from request start not response arrival`() =
        fixture { server, client ->
            server.createContext("/v1/maps/lod-pilots/$run/credits") { exchange ->
                assertEquals(capability, exchange.requestHeaders.getFirst(LOD_PILOT_HEADER))
                val request =
                    gson.fromJson(exchange.requestBody.reader(), LodPayloadRequest::class.java)
                clock.addAndGet(1001000000)
                val bytes =
                    gson
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
            assertFailsWith<LodUnavailable> { client.payload(player, 262144) }
        }

    @Test
    fun `private source and body use only canonical same origin paths`() =
        fixture { server, client ->
            val sourceCalls = AtomicInteger()
            server.createContext("/v1/maps/lod-pilots/$run/source") { exchange ->
                sourceCalls.incrementAndGet()
                assertEquals(capability, exchange.requestHeaders.getFirst(LOD_PILOT_HEADER))
                if (exchange.requestMethod == "POST") {
                    val request =
                        gson.fromJson(exchange.requestBody.reader(), LodSourceRequest::class.java)
                    val bytes = gson.toJson(source(request.requestId)).toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } else {
                    assertEquals("GET", exchange.requestMethod)
                    assertNull(exchange.requestHeaders.getFirst("Range"))
                    exchange.sendResponseHeaders(200, 4)
                    exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3, 4)) }
                }
            }
            val lease = client.source(player)
            val dir = Files.createTempDirectory("lod-client-test-")
            try {
                val file = dir.resolve("bundle.zip")
                client.download(lease, file)
                assertContentEquals(byteArrayOf(1, 2, 3, 4), Files.readAllBytes(file))
                assertEquals(2, sourceCalls.get())
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test
    fun `foreign source URLs and wrong identities are refused`() = fixture { server, client ->
        server.createContext("/v1/maps/lod-pilots/$run/source") { exchange ->
            val request = gson.fromJson(exchange.requestBody.reader(), LodSourceRequest::class.java)
            val bytes =
                gson
                    .toJson(
                        source(request.requestId).copy(bundleUrl = "https://attacker.test/read")
                    )
                    .toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        assertFailsWith<LodUnavailable> { client.source(player) }
    }

    @Test
    fun `redirects do not forward capabilities`() = fixture { server, client ->
        val foreign = AtomicInteger()
        server.createContext("/foreign") { exchange ->
            foreign.incrementAndGet()
            exchange.close()
        }
        server.createContext("/v1/maps/lod-pilots/$run/source") { exchange ->
            exchange.responseHeaders.add("Location", "/foreign")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        assertFailsWith<LodUnavailable> { client.source(player) }
        assertEquals(0, foreign.get())
    }

    @Test
    fun `failed download never deletes an existing cache file`() = fixture { server, client ->
        server.createContext("/v1/maps/lod-pilots/$run/source") { exchange ->
            exchange.sendResponseHeaders(200, 4)
            exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3, 4)) }
        }
        val file = Files.createTempFile("lod-existing-", ".zip")
        Files.write(file, byteArrayOf(9, 8, 7))
        try {
            assertFailsWith<LodUnavailable> {
                client.download(
                    LodLease(source(UUID.randomUUID().toString()), clock.get(), 120000),
                    file,
                )
            }
            assertContentEquals(byteArrayOf(9, 8, 7), Files.readAllBytes(file))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `rate limited UUID cannot pause another UUID with valid credit`() =
        fixture { server, client ->
            val other = UUID.randomUUID()
            val denied = AtomicInteger()
            server.createContext("/v1/maps/lod-pilots/$run/credits") { exchange ->
                val request =
                    gson.fromJson(exchange.requestBody.reader(), LodPayloadRequest::class.java)
                if (request.playerId == player.toString()) {
                    denied.incrementAndGet()
                    exchange.sendResponseHeaders(429, -1)
                    exchange.close()
                } else {
                    val bytes =
                        gson
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
            }
            val gate = LodCreditGate(client, setOf(player, other), clock::get)
            assertNotNull(gate.reserve(other, 1024))
            assertNull(gate.reserve(player, 1024))
            assertNotNull(gate.reserve(other, 1024))
            repeat(20) {
                clock.addAndGet(1001000000)
                gate.reserve(player, 1024)
            }
            assertEquals(8, denied.get())
        }

    private fun source(request: String) =
        LodSourceGrant(
            runId = run.toString(),
            requestId = request,
            mapBundleSha256 = sha,
            sourceBundleSha256 = "b".repeat(64),
            registrySha256 = "c".repeat(64),
            generatorSha256 = "d".repeat(64),
            manifestSha256 = "e".repeat(64),
            bundleSha256 = "f".repeat(64),
            bundleBytes = 4,
            contentTimestampMillis = 1794146400000,
            bundleUrl = "/v1/maps/lod-pilots/$run/source/$request",
            validForMillis = 120000,
        )
}
