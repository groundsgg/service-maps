package gg.grounds.lod.worker

import com.github.luben.zstd.ZstdOutputStream
import com.google.gson.Gson
import com.sun.net.httpserver.HttpServer
import gg.grounds.lod.format.*
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.api.io.TempDir

class RemoteLodWorkerTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `worker uploads only two artifacts then the matching success marker`() =
        serve { server, received, source ->
            val request = request(server, source)
            assertTrue(RemoteLodWorker(temp, allowLoopbackHttp = true).run(request))
            assertEquals(
                listOf("GET /source", "PUT /bundle", "PUT /manifest", "PUT /result"),
                received.keys.toList(),
            )
            val result =
                Gson()
                    .fromJson(
                        received.getValue("PUT /result").decodeToString(),
                        LodJobResult::class.java,
                    )
            assertNull(result.error)
            assertEquals(request.attempt, result.attempt)
            assertEquals(GeneratorBuild.sha256, result.generatorSha256)
            assertEquals(digest(received.getValue("PUT /bundle")), result.bundleSha256)
            val path = temp.resolve("verified.zip")
            Files.write(path, received.getValue("PUT /bundle"))
            assertEquals(1, LodBundle.open(path, result.bundleSha256!!).manifest.columns)
        }

    @Test
    fun `wrong input and generator identities cannot publish terrain`() =
        serve { server, received, source ->
            for (req in
                listOf(
                    request(server, source).copy(sourceBundleSha256 = "b".repeat(64)),
                    request(server, source).copy(generatorSha256 = "c".repeat(64)),
                )) {
                received.clear()
                assertTrue(
                    RemoteLodWorker(
                            Files.createTempDirectory(temp, "attempt"),
                            allowLoopbackHttp = true,
                        )
                        .run(req)
                )
                assertFalse(received.keys.any { it == "PUT /bundle" || it == "PUT /manifest" })
                val result =
                    Gson()
                        .fromJson(
                            received.getValue("PUT /result").decodeToString(),
                            LodJobResult::class.java,
                        )
                assertNotNull(result.error)
            }
        }

    @Test
    fun `upload failure cannot produce a success marker and has no automatic retry`() =
        serve { server, received, source ->
            server.removeContext("/manifest")
            server.createContext("/manifest") { exchange ->
                received["PUT /manifest"] = exchange.requestBody.readAllBytes()
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
            assertTrue(RemoteLodWorker(temp, allowLoopbackHttp = true).run(request(server, source)))
            assertEquals(
                listOf("GET /source", "PUT /bundle", "PUT /manifest", "PUT /result"),
                received.keys.toList(),
            )
            val result =
                Gson()
                    .fromJson(
                        received.getValue("PUT /result").decodeToString(),
                        LodJobResult::class.java,
                    )
            assertNotNull(result.error)
            assertNull(result.bundleSha256)
        }

    private fun request(server: HttpServer, source: ByteArray): LodJobRequest {
        val base = "http://127.0.0.1:${server.address.port}"
        return LodJobRequest(
            attempt = UUID.randomUUID().toString(),
            mapBundleSha256 = "a".repeat(64),
            sourceBundleSha256 = digest(source),
            generatorSha256 = GeneratorBuild.sha256,
            workerImageSha256 = "d".repeat(64),
            sourceUrl = "$base/source",
            bundleUrl = "$base/bundle",
            manifestUrl = "$base/manifest",
            resultUrl = "$base/result",
        )
    }

    private fun serve(block: (HttpServer, LinkedHashMap<String, ByteArray>, ByteArray) -> Unit) {
        if (net.minestom.server.MinecraftServer.process() == null)
            net.minestom.server.MinecraftServer.init()
        val world = Files.createTempDirectory(temp, "fixture")
        val loader = net.minestom.server.instance.anvil.AnvilLoader(world)
        val instance =
            net.minestom.server.instance.InstanceContainer(
                UUID.randomUUID(),
                net.minestom.server.world.DimensionType.OVERWORLD,
            )
        val chunk = net.minestom.server.instance.DynamicChunk(instance, 0, 0)
        chunk.lockWriteLock()
        try {
            chunk.setBlock(0, 64, 0, net.minestom.server.instance.block.Block.STONE)
        } finally {
            chunk.unlockWriteLock()
        }
        loader.saveChunk(chunk)
        loader.unloadChunk(chunk)
        val source =
            ByteArrayOutputStream()
                .also { out ->
                    TarArchiveOutputStream(ZstdOutputStream(out)).use { tar ->
                        Files.walk(world).use { paths ->
                            paths
                                .filter { Files.isRegularFile(it) }
                                .sorted()
                                .forEach { file ->
                                    val entry =
                                        TarArchiveEntry(world.relativize(file).toString()).also {
                                            it.size = Files.size(file)
                                            it.modTime = java.util.Date(0)
                                        }
                                    tar.putArchiveEntry(entry)
                                    Files.copy(file, tar)
                                    tar.closeArchiveEntry()
                                }
                        }
                    }
                }
                .toByteArray()
        val received = LinkedHashMap<String, ByteArray>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        for (path in listOf("/source", "/bundle", "/manifest", "/result")) server.createContext(
            path
        ) { exchange ->
            val key = "${exchange.requestMethod} $path"
            received[if (received.containsKey(key)) "$key#duplicate" else key] =
                exchange.requestBody.readAllBytes()
            if (path == "/source") {
                exchange.sendResponseHeaders(200, source.size.toLong())
                exchange.responseBody.use { it.write(source) }
            } else exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            block(server, received, source)
        } finally {
            server.stop(0)
        }
    }
}
