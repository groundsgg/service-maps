package gg.grounds.lod

import gg.grounds.blob.BlobStore
import gg.grounds.domain.*
import gg.grounds.lod.format.*
import io.smallrye.common.annotation.Blocking
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HEAD
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.eclipse.microprofile.openapi.annotations.Operation

fun interface LodPilotOrigin {
    fun open(artifact: LodClaim): InputStream
}

@ApplicationScoped
class R2LodPilotOrigin @Inject constructor(private val blobs: BlobStore) : LodPilotOrigin {
    override fun open(artifact: LodClaim): InputStream =
        blobs.openLodPrivate(
            LodArtifactStore.privateBundleKey(
                artifact.trust,
                requireNotNull(artifact.facts).bundleSha256,
            ),
            requireNotNull(artifact.facts).bundleBytes,
        )
}

/** Private credits/source for both modes. No creation/reset endpoint or terrain generation. */
@Path("/v1/maps/lod-pilots/{runId}")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@LodPilotAccess
@Blocking
class LodPilotResource(
    private val quotas: LodServingRepository,
    private val origin: LodPilotOrigin,
    private val clock: Clock,
    private val observer: LodSourceObserver = object : LodSourceObserver {},
) {
    @Inject
    constructor(
        quotas: LodServingRepository,
        origin: LodPilotOrigin,
        metrics: LodSourceMetrics,
    ) : this(quotas, origin, Clock.systemUTC(), metrics)

    @POST
    @Path("/credits")
    @Operation(hidden = true)
    fun credits(@Context context: ContainerRequestContext, request: LodPayloadRequest?): Response =
        payloadGrant(authorized(context), request)

    @POST
    @Path("/source")
    @Operation(hidden = true)
    fun source(@Context context: ContainerRequestContext, request: LodSourceRequest?): Response =
        sourceGrant(authorized(context), request)

    @HEAD
    @Path("/source/{requestId}")
    @Operation(hidden = true)
    fun headSource(): Response = Response.status(405).header("Cache-Control", "no-store").build()

    @GET
    @Path("/source/{requestId}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(hidden = true)
    fun download(
        @Context context: ContainerRequestContext,
        @PathParam("requestId") requestId: String,
    ): Response {
        val snapshot = authorized(context)
        if (context.getHeaderString("Range") != null) return Response.status(416).build()
        val id =
            try {
                UUID.fromString(requestId)
            } catch (_: IllegalArgumentException) {
                return Response.status(400).build()
            }
        if (!downloads.tryAcquire()) return Response.status(503).header("Retry-After", "1").build()
        var stream: InputStream? = null
        try {
            // Durable consume precedes the only origin read. Replays cannot reach R2.
            val reserved = quotas.consumeRemote(snapshot.run.id, id)
            observe { observer.opened() }
            stream =
                try {
                    origin.open(reserved.artifact)
                } catch (failure: Exception) {
                    observe { observer.openFailed() }
                    throw failure
                }
            val entity =
                PilotDownload(
                    stream,
                    requireNotNull(reserved.artifact.facts).bundleBytes,
                    { bytes, complete -> observe { observer.finished(bytes, complete) } },
                ) {
                    downloads.release()
                }
            return Response.ok(entity, MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Length", reserved.artifact.facts.bundleBytes)
                .header("Cache-Control", "no-store")
                .build()
        } catch (failure: Exception) {
            try {
                stream?.close()
            } finally {
                downloads.release()
            }
            return when (failure) {
                    is LodServingDenied ->
                        Response.status(
                            if (failure.reason == LodServingRefusal.SOURCE) 404 else 410
                        )
                    else -> Response.status(502)
                }
                .header("Cache-Control", "no-store")
                .build()
        }
    }

    internal fun payloadGrant(snapshot: LodServingSnapshot, request: LodPayloadRequest?): Response =
        result {
            requireNotNull(request).validate()
            val credit =
                quotas.payload(
                    snapshot.run.id,
                    UUID.fromString(request.requestId),
                    request.mapBundleSha256,
                    UUID.fromString(request.playerId),
                    request.bytes,
                )
            LodPayloadGrant(
                runId = snapshot.run.id.toString(),
                requestId = request.requestId,
                bytes = credit.bytes,
                validForMillis = credit.validForMillis,
            )
        }

    internal fun sourceGrant(snapshot: LodServingSnapshot, request: LodSourceRequest?): Response =
        result {
            requireNotNull(request).validate()
            val reserved =
                quotas.remote(
                    snapshot.run.id,
                    UUID.fromString(request.requestId),
                    request.mapBundleSha256,
                    UUID.fromString(request.playerId),
                )
            // Recheck after DB waits. The relative URL requires the same header and is consumed
            // once.
            val seconds =
                minOf(
                    reserved.validForMillis / 1000,
                    reserved.snapshot.run.expiresAt?.let {
                        Duration.between(clock.instant(), it).seconds
                    } ?: 120L,
                )
            if (seconds < 1) throw LodServingDenied(LodServingRefusal.CLOSED)
            val artifact = reserved.artifact
            val facts = requireNotNull(artifact.facts)
            LodSourceGrant(
                runId = reserved.snapshot.run.id.toString(),
                requestId = request.requestId,
                mapBundleSha256 = artifact.mapBundleSha256,
                sourceBundleSha256 = artifact.sourceBundleSha256,
                registrySha256 = artifact.registrySha256,
                generatorSha256 = artifact.generatorSha256,
                manifestSha256 = facts.manifestSha256,
                bundleSha256 = facts.bundleSha256,
                bundleBytes = facts.bundleBytes,
                contentTimestampMillis = artifact.contentTimestampMillis,
                bundleUrl =
                    "/v1/maps/lod-pilots/${reserved.snapshot.run.id}/source/${request.requestId}",
                validForMillis = seconds * 1000,
            )
        }

    private fun authorized(context: ContainerRequestContext): LodServingSnapshot =
        context.getProperty(LOD_PILOT_AUTHORIZATION) as? LodServingSnapshot
            ?: throw jakarta.ws.rs.ForbiddenException()

    private fun result(body: () -> Any): Response =
        try {
            Response.ok(body()).header("Cache-Control", "no-store").build()
        } catch (e: IllegalArgumentException) {
            Response.status(400).header("Cache-Control", "no-store").build()
        } catch (e: LodServingDenied) {
            val status =
                when (e.reason) {
                    LodServingRefusal.CLOSED,
                    LodServingRefusal.BUDGET -> 410
                    LodServingRefusal.SOURCE -> 404
                    LodServingRefusal.PLAYER -> 403
                    LodServingRefusal.DUPLICATE -> 409
                    LodServingRefusal.RATE -> 429
                }
            Response.status(status).header("Cache-Control", "no-store").build()
        }

    private fun observe(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            /* Telemetry never authorizes or retries a read. */
        }
    }

    companion object {
        // The chart admits one controller replica; DB budgets remain shared across replicas.
        private val downloads = Semaphore(2)
    }
}

/** Deadline and exact output bound apply even if the receiver disconnects or stops reading. */
internal class PilotDownload(
    private val source: InputStream,
    private var remaining: Long,
    private val observed: (Long, Boolean) -> Unit = { _, _ -> },
    private val released: () -> Unit,
) : InputStream() {
    private val expected = remaining
    private val transferred = AtomicLong()
    private val activeReads = AtomicInteger()
    private val reported = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val deadline = deadlines.schedule({ close() }, 30, TimeUnit.SECONDS)

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.size)
        if (length == 0) return 0
        if (remaining == 0L) return -1
        activeReads.incrementAndGet()
        try {
            if (closed.get()) throw IOException("LOD download closed")
            val count = source.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
            if (count < 0) throw IOException("Truncated LOD download")
            remaining -= count
            transferred.addAndGet(count.toLong())
            return count
        } finally {
            activeReads.decrementAndGet()
            reportWhenDrained()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            deadline.cancel(false)
            try {
                source.close()
            } finally {
                try {
                    released()
                } finally {
                    reportWhenDrained()
                }
            }
        }
    }

    private fun reportWhenDrained() {
        if (closed.get() && activeReads.get() == 0 && reported.compareAndSet(false, true)) {
            val bytes = transferred.get()
            try {
                observed(bytes, bytes == expected)
            } catch (_: Exception) {
                /* Observation only. */
            }
        }
    }

    companion object {
        private val deadlines =
            ScheduledThreadPoolExecutor(1) { task ->
                    Thread(task, "lod-download-deadline").apply { isDaemon = true }
                }
                .apply { removeOnCancelPolicy = true }
    }
}
