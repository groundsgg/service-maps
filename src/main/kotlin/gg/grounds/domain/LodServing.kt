package gg.grounds.domain

import java.time.Instant
import java.util.UUID

/**
 * Explicit finite measurement run; registration is an operator action, never a join side effect.
 */
data class LodServingRun(
    val id: UUID,
    val artifactAttempt: UUID,
    val tokenSha256: String,
    val players: Set<UUID>,
    val startsAt: Instant,
    val expiresAt: Instant,
    val createdBy: String,
    val enabled: Boolean = true,
) {
    init {
        require(tokenSha256.matches(Regex("[0-9a-f]{64}")) && players.size in 1..5)
        require(
            expiresAt > startsAt &&
                expiresAt <= startsAt.plusSeconds(1800) &&
                createdBy.isNotBlank()
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
    RuntimeException("LOD pilot credit refused: $reason")

interface LodServingRepository {
    fun register(run: LodServingRun)

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
