package gg.grounds.domain

import java.time.Instant
import java.util.UUID

/** Where lighting one bundle stands. */
enum class LitState {
    LIGHTING,
    LIT,
    FAILED,
}

/** A pinned bundle that has no lit copy of the current format yet. */
data class LitCandidate(val bundleSha256: String, val trust: MapTrust)

/** One bundle's lit copy, in one light format. */
data class LitRecord(
    val bundleSha256: String,
    val lightFormat: Int,
    val state: LitState,
    val attempt: UUID,
    val attempts: Int,
    val litSha256: String?,
    val litSize: Long?,
    val startedAt: Instant,
)

/** What a finished lighting job reports about the lit copy and the map it was made from. */
data class LitFacts(
    val litSha256: String,
    val litSize: Long,
    /** Chunks the source bundle holds. */
    val presentChunks: Int,
    /** Chunks the lit copy keeps: every one with a block in it. */
    val keptChunks: Int,
    /** What a game server holds in memory for one round on this map. */
    val estLoadedMib: Int,
)

interface MapLitRepository {

    /** The bundle being lit now, if any: lighting runs one bundle at a time. */
    fun lighting(format: Int): LitRecord?

    /**
     * The next pinned bundle without a lit copy of [format], oldest pin first, skipping bundles
     * that already failed [maxAttempts] times.
     */
    fun nextCandidate(format: Int, maxAttempts: Int): LitCandidate?

    /** Marks [bundleSha256] as being lit by [attempt]. */
    fun start(bundleSha256: String, format: Int, attempt: UUID)

    /** Records the lit copy, and fills the memory facts of every version of that bundle. */
    fun succeed(bundleSha256: String, format: Int, attempt: UUID, facts: LitFacts)

    fun fail(bundleSha256: String, format: Int, attempt: UUID, reason: String)

    /** Every environment that has a version of [bundleSha256] pinned, for republishing. */
    fun environmentsPinning(bundleSha256: String): List<String>

    /** Every bundle's lit copy of [format]: bundle digest to lit bundle digest. */
    fun litCopies(format: Int): Map<String, String>

    /** The trust of the maps [bundleSha256] belongs to — which public bucket serves it. */
    fun trustOf(bundleSha256: String): MapTrust?
}
