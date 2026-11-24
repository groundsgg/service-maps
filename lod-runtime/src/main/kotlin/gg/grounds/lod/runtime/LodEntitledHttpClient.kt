package gg.grounds.lod.runtime

import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import gg.grounds.lod.format.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Lazy server-only exact-identity discovery. Construction performs no control HTTP. */
class LodEntitledHttpClient(
    private val origin: URI,
    private val capability: String,
    private val expected: LodExpectation,
    private val allowLoopbackHttp: Boolean = false,
    private val nanos: () -> Long = System::nanoTime,
) : LodControl {
    private val json = GsonBuilder().disableJdkUnsafe().setStrictness(Strictness.STRICT).create()
    private var delegate: LodPilotHttpClient? = null
    private var closed = false

    init {
        // Reuse the finite client's origin/capability validation without issuing any request.
        LodPilotHttpClient(origin, UUID(0, 0), capability, expected.mapSha256, allowLoopbackHttp)
    }

    override fun source(player: UUID): LodLease<LodSourceGrant> = checked {
        client().source(player)
    }

    override fun payload(player: UUID, bytes: Int): LodLease<LodPayloadGrant> = checked {
        client().payload(player, bytes)
    }

    override fun download(lease: LodLease<LodSourceGrant>, into: Path) = checked {
        client().download(lease, into)
    }

    @Synchronized
    private fun client(): LodPilotHttpClient {
        if (closed) throw LodUnavailable(true)
        delegate?.let {
            return it
        }
        val selection = discover()
        selection.validate()
        require(
            selection.mapBundleSha256 == expected.mapSha256 &&
                selection.sourceBundleSha256 == expected.sourceSha256 &&
                selection.registrySha256 == expected.registrySha256 &&
                selection.generatorSha256 == expected.generatorSha256
        )
        return LodPilotHttpClient(
                origin,
                UUID.fromString(selection.runId),
                capability,
                expected.mapSha256,
                allowLoopbackHttp,
                nanos,
                permanentValidationFailures = true,
            )
            .also { delegate = it }
    }

    private fun discover(): LodServingSelection {
        val connection =
            origin.resolve("/v1/maps/lod-serving/discover").toURL().openConnection()
                as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = "POST"
        connection.setRequestProperty(LOD_PILOT_HEADER, capability)
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json")
        val body =
            json
                .toJson(
                    LodServingDiscoveryRequest(
                        mapBundleSha256 = expected.mapSha256,
                        sourceBundleSha256 = expected.sourceSha256,
                        registrySha256 = expected.registrySha256,
                        generatorSha256 = expected.generatorSha256,
                    )
                )
                .toByteArray(Charsets.UTF_8)
        val deadline = deadlines.schedule({ connection.disconnect() }, 15, TimeUnit.SECONDS)
        try {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status != 200)
                throw LodUnavailable(status !in setOf(404, 408, 429) && status !in 500..599)
            return connection.inputStream.use {
                val bytes = it.readNBytes(16385)
                require(bytes.size <= 16384)
                requireNotNull(
                    json.fromJson(bytes.toString(Charsets.UTF_8), LodServingSelection::class.java)
                )
            }
        } finally {
            deadline.cancel(false)
            connection.disconnect()
        }
    }

    private fun <T> checked(action: () -> T): T {
        synchronized(this) { if (closed) throw LodUnavailable(true) }
        try {
            return action()
        } catch (error: Exception) {
            val failure =
                when (error) {
                    is LodUnavailable -> error
                    is IOException -> LodUnavailable()
                    else -> LodUnavailable(true)
                }
            if (failure.permanent) synchronized(this) { closed = true }
            throw failure
        }
    }

    companion object {
        private val deadlines =
            ScheduledThreadPoolExecutor(1) { task ->
                    Thread(task, "lod-discovery-deadline").apply { isDaemon = true }
                }
                .apply { removeOnCancelPolicy = true }
    }
}
