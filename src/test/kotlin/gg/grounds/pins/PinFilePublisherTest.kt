package gg.grounds.pins

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import gg.grounds.blob.BlobStore
import gg.grounds.domain.*
import gg.grounds.lit.MapLightPolicy
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class PinFilePublisherTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> stub(type: Class<T>, call: (String) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            call(method.name)
        } as T

    @Test
    fun `optional LOD lookup failure cannot block a new base map pin`() {
        val body = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val wire = exchange.requestBody.readAllBytes()
            val decoded =
                if (
                    exchange.requestHeaders
                        .getFirst("Content-Encoding")
                        .orEmpty()
                        .contains("aws-chunked")
                ) {
                    val output = java.io.ByteArrayOutputStream()
                    var cursor = 0
                    while (true) {
                        var end = cursor
                        while (
                            end + 1 < wire.size &&
                                !(wire[end] == 13.toByte() && wire[end + 1] == 10.toByte())
                        ) end++
                        val size =
                            wire
                                .copyOfRange(cursor, end)
                                .decodeToString()
                                .substringBefore(';')
                                .toInt(16)
                        cursor = end + 2
                        if (size == 0) break
                        output.write(wire, cursor, size)
                        cursor += size + 2
                    }
                    output.toByteArray()
                } else wire
            body.set(decoded.decodeToString())
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val pins =
                stub(MapPinRepository::class.java) { name ->
                    if (name == "pinned")
                        listOf(
                            PinnedMap(
                                MapAddress("lobby", "mainlobby"),
                                14,
                                "a".repeat(64),
                                100,
                                1,
                                MapTrust.FIRST_PARTY,
                            )
                        )
                    else error(name)
                }
            val lits =
                stub(MapLitRepository::class.java) { name ->
                    if (name == "litCopies") emptyMap<String, String>() else error(name)
                }
            val lods =
                stub(MapLodRepository::class.java) { name ->
                    if (name == "published")
                        throw IllegalStateException("optional table unavailable")
                    else error(name)
                }
            val blobs =
                BlobStore(
                    "http://127.0.0.1:${server.address.port}",
                    "us-east-1",
                    "test",
                    "test",
                    "private",
                    "public",
                    "ugc",
                    "https://maps.grounds.gg",
                    "https://ugc-content.grounds.gg",
                )
            val json = jacksonObjectMapper()
            val publisher =
                PinFilePublisher(
                    pins,
                    blobs,
                    json,
                    lits,
                    PinEvents(Optional.empty()),
                    lods,
                    true,
                    MapLightPolicy(true, true, 1),
                )
            assertTrue(publisher.publish("stage"))
            val entry = json.readTree(body.get())["maps"]["lobby/mainlobby"]
            assertEquals(14, entry["version"].asInt())
            assertFalse(entry.has("lod"))
        } finally {
            server.stop(0)
        }
    }
}
