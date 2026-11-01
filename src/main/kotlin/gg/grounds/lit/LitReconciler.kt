package gg.grounds.lit

import gg.grounds.blob.BlobMetadata
import gg.grounds.blob.BlobStore
import gg.grounds.domain.LitFacts
import gg.grounds.domain.LitRecord
import gg.grounds.domain.MapLitRepository
import gg.grounds.domain.MapTrust
import gg.grounds.pins.PinFilePublisher
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/** The blob operations lighting needs, narrow so the reconciler is testable without S3. */
interface LitBlobs {
    /** A readable URL for the published bundle [bundleSha256]. */
    fun sourceUrl(bundleSha256: String, trust: MapTrust): String

    /** A writable URL for one private work object. */
    fun workUrl(key: String): String

    fun getPrivate(key: String, maxBytes: Long): ByteArray

    fun headPrivate(key: String): BlobMetadata?

    /** Publishes a private work object under a public, content-addressed key. */
    fun promote(sourceKey: String, destinationKey: String, sizeBytes: Long, trust: MapTrust)
}

@ApplicationScoped
class BlobStoreLitBlobs @Inject constructor(private val blobs: BlobStore) : LitBlobs {
    override fun sourceUrl(bundleSha256: String, trust: MapTrust): String =
        blobs.presignGetPublic(BlobStore.bundleKey(bundleSha256), trust, URL_TTL)

    override fun workUrl(key: String): String = blobs.presignPut(key, URL_TTL)

    override fun getPrivate(key: String, maxBytes: Long): ByteArray =
        blobs.getPrivate(key, maxBytes)

    override fun headPrivate(key: String): BlobMetadata? = blobs.headPrivate(key)

    override fun promote(
        sourceKey: String,
        destinationKey: String,
        sizeBytes: Long,
        trust: MapTrust,
    ) = blobs.promotePrivateBundle(sourceKey, destinationKey, sizeBytes, trust)

    private companion object {
        /** Longer than a lighting Job may run, so its URLs never expire under it. */
        val URL_TTL: Duration = Duration.ofMinutes(60)
    }
}

/** Republishes an environment's pin file; the pin file publisher, in production. */
fun interface PinFiles {
    fun publish(environment: String): Boolean
}

@ApplicationScoped
class PublisherPinFiles @Inject constructor(private val publisher: PinFilePublisher) : PinFiles {
    override fun publish(environment: String): Boolean = publisher.publish(environment)
}

/**
 * Lights every pinned bundle once per light format, one bundle at a time.
 *
 * A pinned version whose bundle has no lit copy of [format] gets one: a Job runs the lit worker on
 * it, the lit bundle is promoted to the bundle's public bucket under its own digest, and every
 * environment pinning that bundle gets its pin file republished, now naming the lit copy. Versions
 * and pins never change. Backfilling everything already pinned is simply this loop's first passes;
 * raising [format] lights everything again.
 *
 * One bundle at a time keeps lighting to one bounded worker on the cluster. A bundle whose lighting
 * fails is tried again up to [MAX_ATTEMPTS] times; game servers light such a map themselves
 * meanwhile.
 */
@ApplicationScoped
class LitReconciler(
    private val lits: MapLitRepository,
    private val jobs: LitJobGateway,
    private val blobs: LitBlobs,
    private val pinFiles: PinFiles,
    private val enabled: Boolean,
    private val format: Int,
    private val timeout: Duration,
) {
    @Inject
    constructor(
        lits: MapLitRepository,
        jobs: LitJobGateway,
        blobs: LitBlobs,
        pinFiles: PinFiles,
        @ConfigProperty(name = "grounds.maps.derive.enabled", defaultValue = "false")
        deriveEnabled: Boolean,
        @ConfigProperty(name = "grounds.maps.lit.enabled", defaultValue = "true")
        litEnabled: Boolean,
        @ConfigProperty(name = "grounds.maps.lit.format", defaultValue = "1") format: Int,
        @ConfigProperty(name = "grounds.maps.lit.timeout", defaultValue = "PT20M") timeout: Duration,
    ) : this(lits, jobs, blobs, pinFiles, deriveEnabled && litEnabled, format, timeout)

    private val log = Logger.getLogger(LitReconciler::class.java)

    @Scheduled(
        every = "{grounds.maps.lit.poll-interval:30s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun reconcile() {
        if (!enabled) return
        try {
            step(Instant.now())
        } catch (e: Exception) {
            log.warnf(e, "lighting reconciliation failed; retrying on the next poll")
        }
    }

    /** One pass: follow the bundle being lit, or start the next one. */
    internal fun step(now: Instant) {
        val current = lits.lighting(format)
        if (current != null) {
            follow(current, now)
            return
        }
        val next = lits.nextCandidate(format, MAX_ATTEMPTS) ?: return
        val attempt = UUID.randomUUID()
        lits.start(next.bundleSha256, format, attempt)
        val request =
            LitRequest(
                format = format,
                bundleSha256 = next.bundleSha256,
                sourceUrl = blobs.sourceUrl(next.bundleSha256, next.trust),
                litUrl = blobs.workUrl(litKey(next.bundleSha256, format, attempt)),
                resultUrl = blobs.workUrl(resultKey(next.bundleSha256, format, attempt)),
            )
        jobs.create(jobName(next.bundleSha256, format, attempt), LitJson.write(request))
        log.infof("lighting bundle %s (format %d, attempt %s)", next.bundleSha256, format, attempt)
    }

    private fun follow(current: LitRecord, now: Instant) {
        val name = jobName(current.bundleSha256, format, current.attempt)
        val age = Duration.between(current.startedAt, now)
        when (jobs.status(name)) {
            LitJobStatus.SUCCEEDED -> accept(current)
            LitJobStatus.FAILED -> fail(current, "the lighting job failed")
            LitJobStatus.RUNNING -> if (age > timeout) fail(current, "the lighting job timed out")
            // Creating the Job and seeing it can race; past that it is gone (cleaned up, or
            // never created because the service stopped in between).
            null -> if (age > MISSING_GRACE) fail(current, "the lighting job is missing")
        }
    }

    private fun accept(current: LitRecord) {
        val sha = current.bundleSha256
        val result =
            try {
                LitJson.readResult(
                    blobs.getPrivate(resultKey(sha, format, current.attempt), MAX_RESULT_BYTES)
                )
            } catch (e: Exception) {
                fail(current, "the lighting result is unreadable: ${e.message}")
                return
            }
        if (result.error != null) {
            fail(current, "the lit worker refused: ${result.error}")
            return
        }
        val facts =
            try {
                require(result.format == format && result.bundleSha256 == sha) {
                    "the result is for ${result.bundleSha256} format ${result.format}"
                }
                LitFacts(
                    litSha256 = requireNotNull(result.litSha256) { "no lit digest" },
                    litSize = requireNotNull(result.litSize) { "no lit size" },
                    presentChunks = requireNotNull(result.presentChunks) { "no chunk count" },
                    keptChunks = requireNotNull(result.keptChunks) { "no kept chunk count" },
                    estLoadedMib = requireNotNull(result.estLoadedMib) { "no memory estimate" },
                )
            } catch (e: IllegalArgumentException) {
                fail(current, "the lighting result is invalid: ${e.message}")
                return
            }
        val work = litKey(sha, format, current.attempt)
        val uploaded = blobs.headPrivate(work)
        if (uploaded?.sizeBytes != facts.litSize) {
            fail(current, "the lit bundle is missing or not ${facts.litSize} bytes")
            return
        }
        val trust = lits.trustOf(sha) ?: MapTrust.FIRST_PARTY
        blobs.promote(work, BlobStore.bundleKey(facts.litSha256), facts.litSize, trust)
        lits.succeed(sha, format, current.attempt, facts)
        log.infof(
            "lit bundle %s as %s (%d of %d chunks kept, ~%d MiB loaded)",
            sha,
            facts.litSha256,
            facts.keptChunks,
            facts.presentChunks,
            facts.estLoadedMib,
        )
        lits.environmentsPinning(sha).forEach { pinFiles.publish(it) }
    }

    private fun fail(current: LitRecord, reason: String) {
        lits.fail(current.bundleSha256, format, current.attempt, reason)
        log.warnf(
            "lighting bundle %s failed (attempt %d of %d): %s",
            current.bundleSha256,
            current.attempts,
            MAX_ATTEMPTS,
            reason,
        )
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val MAX_RESULT_BYTES = 64L * 1024
        val MISSING_GRACE: Duration = Duration.ofMinutes(2)

        fun litKey(bundleSha256: String, format: Int, attempt: UUID): String =
            "tmp/lit/$format/$bundleSha256/$attempt/lit.tar.zst"

        fun resultKey(bundleSha256: String, format: Int, attempt: UUID): String =
            "tmp/lit/$format/$bundleSha256/$attempt/result.json"

        /** A Job name: at most 63 characters, unique per bundle, format and attempt. */
        fun jobName(bundleSha256: String, format: Int, attempt: UUID): String =
            "lit-${bundleSha256.take(40)}-f$format-${attempt.toString().take(8)}"
    }
}
