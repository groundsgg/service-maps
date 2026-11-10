package gg.grounds.lod.runtime

import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import gg.grounds.lod.format.*
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

open class LodUnavailable(val permanent: Boolean = false) :
    RuntimeException("LOD pilot unavailable")

/** Ordinary grant window pacing, distinct from an unavailable control service. */
class LodRateLimited : LodUnavailable()

/** Wrap-safe monotonic age, always measured from BEFORE the control request. */
class LodLease<T>(val value: T, private val started: Long, lifetimeMillis: Long) {
    private val lifetime = Math.multiplyExact(lifetimeMillis, 1000000)

    init {
        require(lifetimeMillis in 1..120000)
    }

    fun remaining(nanos: Long): Long {
        val elapsed = nanos - started
        return if (elapsed < 0 || elapsed >= lifetime) 0 else lifetime - elapsed
    }
}

interface LodControl {
    fun source(player: UUID): LodLease<LodSourceGrant>

    fun payload(player: UUID, bytes: Int): LodLease<LodPayloadGrant>

    fun download(lease: LodLease<LodSourceGrant>, into: Path)
}

/** Server-only client. Capabilities never enter URLs, logs, redirects or Minecraft packets. */
class LodPilotHttpClient(
    private val origin: URI,
    private val run: UUID,
    private val capability: String,
    private val mapSha256: String,
    allowLoopbackHttp: Boolean = false,
    private val nanos: () -> Long = System::nanoTime,
) : LodControl {
    private val root = "/v1/maps/lod-pilots/$run"
    private val json = GsonBuilder().disableJdkUnsafe().setStrictness(Strictness.STRICT).create()

    init {
        require(
            origin.host != null &&
                origin.userInfo == null &&
                origin.query == null &&
                origin.fragment == null
        )
        require(origin.path.isNullOrEmpty() || origin.path == "/")
        require(
            origin.scheme == "https" ||
                (allowLoopbackHttp &&
                    origin.scheme == "http" &&
                    InetAddress.getAllByName(origin.host).all { it.isLoopbackAddress })
        )
        require(capability.length in 32..256 && capability.all { it.code in 33..126 })
        require(isDigest(mapSha256))
    }

    override fun source(player: UUID): LodLease<LodSourceGrant> = safely {
        val id = UUID.randomUUID().toString()
        val start = nanos()
        val grant =
            post(
                "$root/source",
                LodSourceRequest(
                    requestId = id,
                    mapBundleSha256 = mapSha256,
                    playerId = player.toString(),
                ),
                LodSourceGrant::class.java,
            )
        grant.validate()
        require(
            grant.runId == run.toString() &&
                grant.requestId == id &&
                grant.mapBundleSha256 == mapSha256
        )
        LodLease(grant, start, grant.validForMillis).also { require(it.remaining(nanos()) > 0) }
    }

    override fun payload(player: UUID, bytes: Int): LodLease<LodPayloadGrant> = safely {
        require(bytes in 1..262144)
        val id = UUID.randomUUID().toString()
        val start = nanos()
        val grant =
            post(
                "$root/credits",
                LodPayloadRequest(
                    requestId = id,
                    mapBundleSha256 = mapSha256,
                    playerId = player.toString(),
                    bytes = bytes,
                ),
                LodPayloadGrant::class.java,
            )
        grant.validate()
        require(grant.runId == run.toString() && grant.requestId == id && grant.bytes == bytes)
        LodLease(grant, start, grant.validForMillis).also {
            if (it.remaining(nanos()) == 0L) throw LodRateLimited()
        }
    }

    override fun download(lease: LodLease<LodSourceGrant>, into: Path) = safely {
        val grant = lease.value
        grant.validate()
        require(grant.runId == run.toString() && grant.mapBundleSha256 == mapSha256)
        val remaining = lease.remaining(nanos())
        require(remaining > 0)
        var complete = false
        var owned = false
        try {
            transfer(grant.bundleUrl, "GET", null, minOf(remaining, 30000000000L)) { connection ->
                require(connection.contentLengthLong in -1..grant.bundleBytes)
                connection.inputStream.use { input ->
                    Files.newOutputStream(into, StandardOpenOption.CREATE_NEW).use { output ->
                        owned = true
                        var count = 0L
                        val buffer = ByteArray(65536)
                        while (true) {
                            val read =
                                input.read(
                                    buffer,
                                    0,
                                    minOf(buffer.size.toLong(), grant.bundleBytes - count + 1)
                                        .toInt(),
                                )
                            if (read < 0) break
                            count += read
                            require(count <= grant.bundleBytes && lease.remaining(nanos()) > 0)
                            output.write(buffer, 0, read)
                        }
                        require(count == grant.bundleBytes)
                    }
                }
            }
            require(lease.remaining(nanos()) > 0)
            complete = true
        } finally {
            if (!complete && owned) Files.deleteIfExists(into)
        }
    }

    private fun <T> post(path: String, value: Any, type: Class<T>): T =
        transfer(path, "POST", json.toJson(value).toByteArray(), 15000000000L) { connection ->
            connection.inputStream.use { input ->
                val bytes = input.readNBytes(16385)
                require(bytes.size <= 16384)
                requireNotNull(json.fromJson(bytes.toString(Charsets.UTF_8), type))
            }
        }

    private fun <T> transfer(
        path: String,
        method: String,
        body: ByteArray?,
        timeout: Long,
        action: (HttpURLConnection) -> T,
    ): T {
        // Path is constructed or validated against the exact grant shape; no caller-supplied URL.
        require(path.startsWith("$root/") && !path.contains('?') && !path.contains('#'))
        val connection = origin.resolve(path).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = method
        connection.setRequestProperty(LOD_PILOT_HEADER, capability)
        connection.setRequestProperty(
            "Accept",
            if (method == "POST") "application/json" else "application/octet-stream",
        )
        val deadline =
            deadlines.schedule({ connection.disconnect() }, timeout, TimeUnit.NANOSECONDS)
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            if (connection.responseCode == 429) throw LodRateLimited()
            if (connection.responseCode != 200) {
                throw LodUnavailable(connection.responseCode in setOf(400, 401, 403, 404, 409, 410))
            }
            return action(connection)
        } finally {
            deadline.cancel(false)
            connection.disconnect()
        }
    }

    private fun <T> safely(action: () -> T): T =
        try {
            action()
        } catch (e: LodUnavailable) {
            throw e
        } catch (_: Exception) {
            throw LodUnavailable()
        }

    companion object {
        private val deadlines =
            ScheduledThreadPoolExecutor(1) { task ->
                    Thread(task, "lod-client-deadline").apply { isDaemon = true }
                }
                .apply { removeOnCancelPolicy = true }
    }
}
