package gg.grounds.lit.worker

import com.google.gson.Gson
import com.sun.net.httpserver.HttpServer
import gg.grounds.runtime.core.world.MapLight
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.minestom.server.MinecraftServer
import net.minestom.server.instance.anvil.AnvilLoader
import net.minestom.server.instance.block.Block
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream

class LitWorkerTest {
    private lateinit var server: HttpServer
    private val served = ConcurrentHashMap<String, ByteArray>()
    private val uploaded = ConcurrentHashMap<String, ByteArray>()

    @BeforeTest
    fun start() {
        MinecraftServer.init()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            if (exchange.requestMethod == "PUT") {
                uploaded[path] = exchange.requestBody.readAllBytes()
                exchange.sendResponseHeaders(200, -1)
            } else {
                val body = served[path]
                if (body == null) exchange.sendResponseHeaders(404, -1)
                else {
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
            }
            exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun stop() {
        server.stop(0)
        MinecraftServer.stopCleanly()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun request(sha: String, format: Int = MapLight.FORMAT) =
        LitRequest(SCHEMA_VERSION, format, sha, url("/source"), url("/lit"), url("/result"))

    private fun worker() =
        LitWorker(Files.createTempDirectory("lit-work"), HttpClient.newHttpClient())

    private fun result(): LitResult =
        Gson().fromJson(String(uploaded.getValue("/result")), LitResult::class.java)

    @Test
    fun `a bundle is lit and uploaded, with what was kept`() {
        val bundle = bundle(unlitWorld())
        served["/source"] = bundle

        val code = worker().run(request(sha(bundle)))

        assertEquals(0, code)
        val result = result()
        assertNull(result.error)
        assertEquals(sha(bundle), result.bundleSha256)
        val lit = uploaded.getValue("/lit")
        assertEquals(sha(lit), result.litSha256)
        assertEquals(lit.size.toLong(), result.litSize)
        assertEquals(GROUND.size + AIR.size, result.presentChunks)
        assertEquals(GROUND.size, result.keptChunks)
        assertTrue(result.estLoadedMib!! >= 1)

        val world = unpack(lit)
        assertTrue(MapLight.isLit(world))
        assertEquals(GROUND.toSet(), MapLight.presentChunks(world.resolve("region")).toSet())
    }

    @Test
    fun `the same bundle lights to the same lit bundle`() {
        val bundle = bundle(unlitWorld())
        served["/source"] = bundle

        worker().run(request(sha(bundle)))
        val first = result().litSha256
        worker().run(request(sha(bundle)))

        assertEquals(first, result().litSha256)
    }

    @Test
    fun `a bundle that is not its digest is reported, not lit`() {
        served["/source"] = bundle(unlitWorld())

        assertEquals(0, worker().run(request("00".repeat(32))))

        assertNotNull(result().error)
        assertNull(uploaded["/lit"])
    }

    @Test
    fun `another light format is refused`() {
        val bundle = bundle(unlitWorld())
        served["/source"] = bundle

        worker().run(request(sha(bundle), format = MapLight.FORMAT + 1))

        assertTrue(result().error!!.contains("format"))
        assertNull(uploaded["/lit"])
    }

    private fun sha(bytes: ByteArray) =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun unpack(bundle: ByteArray): Path {
        val dir = Files.createTempDirectory("lit-unpacked")
        TarArchiveInputStream(ZstdCompressorInputStream(ByteArrayInputStream(bundle))).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val target = dir.resolve(entry.name)
                if (entry.isDirectory) Files.createDirectories(target)
                else {
                    Files.createDirectories(target.parent)
                    Files.copy(tar, target, StandardCopyOption.REPLACE_EXISTING)
                }
                entry = tar.nextEntry
            }
        }
        return dir
    }

    private fun bundle(world: Path): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(ZstdCompressorOutputStream(out)).use { tar ->
            Files.walk(world).use { paths ->
                for (path in paths.sorted().toList()) {
                    val name = world.relativize(path).toString()
                    if (name.isEmpty()) continue
                    if (Files.isDirectory(path)) tar.putArchiveEntry(TarArchiveEntry("$name/"))
                    else {
                        tar.putArchiveEntry(TarArchiveEntry(name).apply { size = Files.size(path) })
                        Files.copy(path, tar)
                    }
                    tar.closeArchiveEntry()
                }
            }
        }
        return out.toByteArray()
    }

    /** Stone up to y 64 over [GROUND], plus the all-air chunks [AIR], saved without light. */
    private fun unlitWorld(): Path {
        val dir = Files.createTempDirectory("lit-source")
        val instances = MinecraftServer.getInstanceManager()
        val instance = instances.createInstanceContainer(AnvilLoader(dir))
        instance.setGenerator { unit ->
            val start = unit.absoluteStart()
            if ((start.chunkX() to start.chunkZ()) in GROUND)
                unit.modifier().fillHeight(start.blockY(), 64, Block.STONE)
        }
        (GROUND + AIR).forEach { (x, z) -> instance.loadChunk(x, z).join() }
        instance.saveChunksToStorage().join()
        instances.unregisterInstance(instance)
        return dir
    }

    private companion object {
        val GROUND = (-1..1).flatMap { x -> (-1..1).map { z -> x to z } }
        val AIR = listOf(3 to 0)
    }
}
