package gg.grounds.domain

import java.time.Instant
import java.util.UUID

/** An explicit, audited selection. Creating one never raises the campaign's fixed limits. */
data class LodRun(
    val id: UUID,
    val budgetId: String,
    val environment: String,
    val address: MapAddress,
    val version: Int,
    val mapBundleSha256: String,
    val sourceBundleSha256: String,
    val registrySha256: String,
    val generatorSha256: String,
    val workerImage: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val createdBy: String,
) {
    init {
        val sha = Regex("[a-f0-9]{64}")
        require(budgetId.matches(Regex("[a-z0-9-]{1,64}")) && version > 0)
        require(
            listOf(mapBundleSha256, sourceBundleSha256, registrySha256, generatorSha256)
                .all(sha::matches)
        )
        require(workerImage.matches(Regex("[^\\s]+@sha256:[a-f0-9]{64}")))
        require(
            expiresAt > createdAt &&
                expiresAt <= createdAt.plusSeconds(1800) &&
                createdBy.isNotBlank()
        )
    }
}

data class LodReservation(val jobsReserved: Int, val bytesReserved: Long)

data class LodFacts(
    val bundleSha256: String,
    val bundleBytes: Long,
    val manifestSha256: String,
    val manifestBytes: Long,
    val columns: Int,
) {
    init {
        require(listOf(bundleSha256, manifestSha256).all { it.matches(Regex("[0-9a-f]{64}")) })
        require(
            bundleBytes in 1..128L * 1024 * 1024 &&
                manifestBytes in 1..1024L * 1024 &&
                columns in 1..4096
        )
    }
}

data class LodClaim(
    val attempt: UUID,
    val runId: UUID,
    val trust: MapTrust,
    val mapBundleSha256: String,
    val sourceBundleSha256: String,
    val registrySha256: String,
    val generatorSha256: String,
    val workerImage: String,
    val startedAt: Instant,
    val facts: LodFacts? = null,
    val published: Boolean = false,
) {
    val jobName: String
        get() = "lod-$attempt"

    val contentTimestampMillis: Long
        get() = startedAt.toEpochMilli()

    val workerImageSha256: String
        get() = workerImage.substringAfterLast("@sha256:")

    fun workKey(name: String): String = "tmp/lod/$attempt/$name"
}

/** Operator-selected scope; canonical and prepared source hashes always come from the live pin. */
data class AutoLodSelection(
    val environment: String,
    val address: MapAddress,
    val budgetId: String,
    val registrySha256: String,
    val generatorSha256: String,
    val workerImage: String,
) {
    init {
        require(environment == "stage" && address == MapAddress("lobby", "mainlobby"))
        require(budgetId.matches(Regex("[a-z0-9-]{1,64}")))
        require(listOf(registrySha256, generatorSha256).all { it.matches(Regex("[a-f0-9]{64}")) })
        require(workerImage.matches(Regex("[^\\s]+@sha256:[a-f0-9]{64}")))
    }
}

data class LodProgress(val state: String, val columns: Int? = null, val bundleBytes: Long? = null)

object LodGenerationBounds {
    const val MAX_JOBS = 2
    const val MAX_RESERVED_BYTES = 1073741824L
    const val RESERVATION_BYTES = 3L * (128L * 1024 * 1024 + 1024 * 1024) + 64 * 1024
}

interface MapLodRepository {
    /** Read an exact immutable identity, including failed private attempts; never admits work. */
    fun progress(
        trust: MapTrust,
        map: String,
        source: String,
        registry: String,
        generator: String,
    ): LodProgress? = null

    /**
     * Durable selection only; generation remains subject to claim's shared quota and worker guard.
     */
    fun automaticRun(selection: AutoLodSelection, now: Instant): UUID? = null

    fun createRun(run: LodRun)

    /** Atomic admission and permanent reservation, across every service replica. */
    fun claim(runId: UUID, now: Instant): LodClaim?

    fun active(): LodClaim?

    fun budget(id: String): LodReservation?

    fun activateIfCurrent(attempt: UUID, now: () -> Instant, activate: () -> Unit): Boolean

    fun failWhenStopped(attempt: UUID, reason: String, stopped: () -> Boolean): Boolean

    fun succeed(attempt: UUID, facts: LodFacts, now: () -> Instant): Boolean

    fun ready(): List<LodClaim>

    fun markPublished(attempt: UUID)

    fun projectionPending(): List<LodClaim>

    fun projectionComplete(attempt: UUID)

    fun published(): List<LodClaim>
}
