package gg.grounds.domain

import java.time.Instant
import java.util.UUID

/**
 * Immutable serving selection. PILOT keeps its finite operator-selected measurement contract;
 * ENTITLED publication uses a separate durable delivery budget and host-side permission checks.
 */
data class LodServingRun(
    val id: UUID,
    val artifactAttempt: UUID,
    val tokenSha256: String,
    val players: Set<UUID>,
    val startsAt: Instant,
    val expiresAt: Instant?,
    val createdBy: String,
    val enabled: Boolean = true,
    val mode: LodServingMode = LodServingMode.PILOT,
    val budgetId: String? = null,
    val generationBudgetId: String? = null,
) {
    init {
        require(tokenSha256.matches(Regex("[0-9a-f]{64}")) && createdBy.isNotBlank())
        when (mode) {
            LodServingMode.PILOT ->
                require(
                    players.size in 1..5 &&
                        expiresAt != null &&
                        expiresAt > startsAt &&
                        expiresAt <= startsAt.plusSeconds(1800) &&
                        budgetId == null &&
                        generationBudgetId == null
                )
            LodServingMode.ENTITLED ->
                require(
                    players.isEmpty() &&
                        expiresAt == null &&
                        budgetId?.matches(Regex("[a-z0-9-]{1,64}")) == true &&
                        generationBudgetId?.matches(Regex("[a-z0-9-]{1,64}")) == true
                )
        }
    }
}

enum class LodServingMode {
    PILOT,
    ENTITLED,
}

data class LodServingIdentity(
    val mapBundleSha256: String,
    val sourceBundleSha256: String,
    val registrySha256: String,
    val generatorSha256: String,
) {
    init {
        require(
            listOf(mapBundleSha256, sourceBundleSha256, registrySha256, generatorSha256).all {
                it.matches(Regex("[a-f0-9]{64}"))
            }
        )
    }
}

data class LodServingSnapshot(
    val run: LodServingRun,
    val artifact: LodClaim,
    val payloadReserved: Long,
    val remoteReadsReserved: Int,
    val remoteBytesReserved: Long,
)

data class LodRemoteCredit(val snapshot: LodServingSnapshot, val validForMillis: Long) {
    val artifact: LodClaim
        get() = snapshot.artifact
}

data class LodPayloadCredit(val bytes: Int, val validForMillis: Long)

enum class LodServingRefusal {
    CLOSED,
    SOURCE,
    PLAYER,
    DUPLICATE,
    RATE,
    BUDGET,
}

class LodServingDenied(val reason: LodServingRefusal) :
    RuntimeException("LOD serving credit refused: $reason")

interface LodServingRepository {
    fun register(run: LodServingRun)

    fun deliveryBudgetTokenSha256(budgetId: String): String? = null

    /** Background-only, idempotent publication of ready private artifacts; never seeds credits. */
    fun reconcileEntitled(budgetId: String, generationBudgetId: String, now: Instant): Int = 0

    fun discoverEntitled(
        budgetId: String,
        generationBudgetId: String,
        identity: LodServingIdentity,
    ): LodServingSnapshot? = null

    fun isCurrentEntitled(runId: UUID, budgetId: String, generationBudgetId: String): Boolean =
        false

    fun find(runId: UUID): LodServingSnapshot?

    fun payload(
        runId: UUID,
        requestId: UUID,
        mapSha: String,
        player: UUID,
        bytes: Int,
        now: (() -> Instant)? = null,
    ): LodPayloadCredit

    fun remote(
        runId: UUID,
        requestId: UUID,
        mapSha: String,
        player: UUID,
        now: (() -> Instant)? = null,
    ): LodRemoteCredit

    /** Consume the reserved download before opening any upstream connection. */
    fun consumeRemote(
        runId: UUID,
        requestId: UUID,
        now: (() -> Instant)? = null,
    ): LodServingSnapshot
}
