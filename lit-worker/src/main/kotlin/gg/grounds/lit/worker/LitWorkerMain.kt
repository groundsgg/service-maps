package gg.grounds.lit.worker

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import gg.grounds.runtime.core.world.MapLight
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import kotlin.system.exitProcess
import net.minestom.server.MinecraftServer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream

/** What service-maps asks for (gg.grounds.lit.LitRequest there; same field names). */
data class LitRequest(
    val schemaVersion: Int,
    val format: Int,
    val bundleSha256: String,
    val sourceUrl: String,
    val litUrl: String,
    val resultUrl: String,
)

/** What it reports (gg.grounds.lit.LitResult there). */
data class LitResult(
    val schemaVersion: Int = SCHEMA_VERSION,
    val format: Int,
    val bundleSha256: String,
    val litSha256: String? = null,
    val litSize: Long? = null,
    val presentChunks: Int? = null,
    val keptChunks: Int? = null,
    val estLoadedMib: Int? = null,
    val error: String? = null,
)

const val SCHEMA_VERSION = 1

/** Refused beyond this: no published bundle is near it. */
const val MAX_BUNDLE_BYTES = 1L shl 30

/**
 * Memory a game server holds per loaded chunk of a lit world, measured over every pinned
 * SurvivalGames map: 22–40 KiB, ~30 on average.
 */
const val KIB_PER_LOADED_CHUNK = 30

private val gson: Gson = GsonBuilder().create()

/**
 * Lights one published bundle: downloads it (checked against its digest), lights it with
 * [MapLight.bake] — the same code and Minestom build the game servers use — packs the lit world
 * reproducibly, uploads it, and reports what it made. The result is uploaded last; its presence
 * means the work is done. A request this worker cannot serve (another light format, a bundle that
 * is not what it claims) gets a result with an error, not a crash, so the service can tell a bad
 * map from a broken worker.
 */
fun main() {
    val json =
        System.getenv("LIT_REQUEST_JSON")
            ?: run {
                System.err.println("LIT_REQUEST_JSON is not set")
                exitProcess(2)
            }
    val request = gson.fromJson(json, LitRequest::class.java)
    val work = Files.createTempDirectory("lit-")
    val code =
        LitWorker(work, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build())
            .run(request)
    work.toFile().deleteRecursively()
    exitProcess(code)
}

class LitWorker(private val work: Path, private val http: HttpClient) {

    /** Returns the process exit code: 0 once a result is uploaded, 1 when even that failed. */
    fun run(request: LitRequest): Int {
        val result =
            try {
                light(request)
            } catch (e: Exception) {
                e.printStackTrace()
                LitResult(
                    format = request.format,
                    bundleSha256 = request.bundleSha256,
                    error = "${e.javaClass.simpleName}: ${e.message}",
                )
            }
        return try {
            put(request.resultUrl, gson.toJson(result).toByteArray())
            println("lit result: ${gson.toJson(result)}")
            0
        } catch (e: Exception) {
            e.printStackTrace()
            1
        }
    }

    private fun light(request: LitRequest): LitResult {
        require(request.schemaVersion == SCHEMA_VERSION) {
            "unsupported request schema ${request.schemaVersion}"
        }
        require(request.format == MapLight.FORMAT) {
            "this worker lights format ${MapLight.FORMAT}, not ${request.format}"
        }
        val raw = work.resolve("raw")
        download(request.sourceUrl, request.bundleSha256, raw)
        val present = MapLight.presentChunks(raw.resolve("region")).size

        if (MinecraftServer.process() == null) MinecraftServer.init()
        val lit = work.resolve("lit")
        val kept = MapLight.bake(raw, lit)

        val bundle = work.resolve("lit.tar.zst")
        pack(lit, bundle)
        val sha = sha256(bundle)
        val size = Files.size(bundle)
        put(request.litUrl, bundle)
        return LitResult(
            format = request.format,
            bundleSha256 = request.bundleSha256,
            litSha256 = sha,
            litSize = size,
            presentChunks = present,
            keptChunks = kept,
            estLoadedMib = ((kept.toLong() * KIB_PER_LOADED_CHUNK + 1023) / 1024).toInt() + 1,
        )
    }

    /** Streams the bundle into [into], refusing it unless its digest is [sha256]. */
    private fun download(url: String, sha256: String, into: Path) {
        val response =
            http.send(
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
        require(response.statusCode() == 200) {
            "bundle download returned ${response.statusCode()}"
        }
        Files.createDirectories(into)
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        DigestInputStream(BufferedInputStream(response.body()), digest).use { raw ->
            // Not closed on its own: closing it would close [raw] before the digest saw it all.
            val tar = TarArchiveInputStream(ZstdCompressorInputStream(raw))
            var entry = tar.nextEntry
            while (entry != null) {
                val destination = into.resolve(entry.name).normalize()
                require(destination.startsWith(into)) { "archive entry escapes: ${entry.name}" }
                if (entry.isDirectory) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    written += Files.copy(tar, destination, StandardCopyOption.REPLACE_EXISTING)
                    require(written <= MAX_BUNDLE_BYTES) { "bundle unpacks beyond 1 GiB" }
                }
                entry = tar.nextEntry
            }
            raw.transferTo(OutputStream.nullOutputStream())
        }
        val actual = HexFormat.of().formatHex(digest.digest())
        require(actual.equals(sha256, ignoreCase = true)) {
            "bundle digest is $actual, not $sha256"
        }
    }

    /**
     * A reproducible tar.zst of [dir]: entries sorted, times and owners zeroed, so the same lit
     * world always has the same digest.
     */
    private fun pack(dir: Path, into: Path) {
        val paths = Files.walk(dir).use { s -> s.filter { it != dir }.sorted().toList() }
        TarArchiveOutputStream(ZstdCompressorOutputStream(Files.newOutputStream(into), 9)).use { tar
            ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for (path in paths) {
                val name =
                    dir.relativize(path).joinToString("/") +
                        if (Files.isDirectory(path)) "/" else ""
                val entry = TarArchiveEntry(name)
                entry.setModTime(FileTime.fromMillis(0))
                entry.userId = 0
                entry.groupId = 0
                entry.userName = ""
                entry.groupName = ""
                if (Files.isDirectory(path)) {
                    entry.mode = 0x41ed // drwxr-xr-x
                    tar.putArchiveEntry(entry)
                } else {
                    entry.mode = 0x81a4 // -rw-r--r--
                    entry.size = Files.size(path)
                    tar.putArchiveEntry(entry)
                    Files.copy(path, tar)
                }
                tar.closeArchiveEntry()
            }
        }
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(Files.newInputStream(file), digest).use {
            it.transferTo(OutputStream.nullOutputStream())
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun put(url: String, file: Path) = put(url, HttpRequest.BodyPublishers.ofFile(file))

    private fun put(url: String, body: ByteArray) =
        put(url, HttpRequest.BodyPublishers.ofByteArray(body))

    private fun put(url: String, body: HttpRequest.BodyPublisher) {
        val response =
            http.send(
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .PUT(body)
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        require(response.statusCode() in 200..299) { "upload returned ${response.statusCode()}" }
    }
}
