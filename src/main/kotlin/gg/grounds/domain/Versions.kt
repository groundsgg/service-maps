package gg.grounds.domain

import java.time.Instant
import java.util.UUID

/**
 * A version is allocated on commit and only becomes usable once the bundle behind it exists. The
 * state, not the presence of a hash, is what says which of those is true.
 *
 * `DRAFT` → `DERIVING` → `PUBLISHED`, with `IN_REVIEW` in front of published for untrusted content
 * and `REJECTED` / `DERIVE_FAILED` / `TAKEN_DOWN` as the terminal refusals. Phase 1 has no derive
 * Job, so a version goes from `DRAFT` straight to `PUBLISHED` when a caller supplies the bundle
 * metadata; that call becomes the derive Job's job later, and nothing else about the state machine
 * changes.
 */
enum class VersionState {
    DRAFT,
    DERIVING,
    DERIVE_FAILED,
    IN_REVIEW,
    PUBLISHED,
    REJECTED,
    TAKEN_DOWN,
}

data class MapVersionRecord(
    val mapId: UUID,
    val version: Int,
    val state: VersionState,
    val bundleSha256: String?,
    val sourceSha256: String?,
    val manifestSha256: String?,
    val parentVersion: Int?,
    val sizeBytes: Long?,
    val presentChunks: Int?,
    val estLoadedMib: Int?,
    val publishedBySub: String,
    val note: String?,
    val createdAt: Instant,
)

/** What a publish supplies about the assembled bundle. */
data class BundleFacts(
    val bundleSha256: String,
    val manifestSha256: String?,
    val sizeBytes: Long,
    val presentChunks: Int?,
    val estLoadedMib: Int?,
)

class VersionNotFoundException(mapId: UUID, version: Int) :
    RuntimeException("no version $version of map $mapId")

/** One digest, one byte string, one size. A second size for it is a lie about the object. */
class BlobSizeMismatchException(digest: String, recorded: Long, offered: Long) :
    RuntimeException("blob $digest is $recorded bytes, not $offered")

class VersionNotPublishableException(val state: VersionState) :
    RuntimeException("a version in state $state cannot be published")

interface MapVersionRepository {

    /**
     * Allocates the next integer for this map and records the commit.
     *
     * @param parentVersion what the editor started from, for provenance.
     */
    fun commit(
        mapId: UUID,
        sourceSha256: String?,
        sourceKey: String?,
        parentVersion: Int?,
        note: String?,
        bySub: String,
    ): MapVersionRecord

    /** Marks a version published and records what the bundle turned out to be. */
    fun publish(mapId: UUID, version: Int, facts: BundleFacts, bySub: String): MapVersionRecord

    fun find(mapId: UUID, version: Int): MapVersionRecord?

    fun list(mapId: UUID): List<MapVersionRecord>

    fun latestPublished(mapId: UUID): MapVersionRecord?
}
