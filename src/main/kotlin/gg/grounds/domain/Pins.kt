package gg.grounds.domain

import java.time.Instant
import java.util.UUID

data class PinRecord(
    val environment: String,
    val mapId: UUID,
    val version: Int,
    val movedBySub: String,
    val movedAt: Instant,
)

/** One row of the published pin file: everything a booting server needs, nothing more. */
data class PinnedMap(
    val address: MapAddress,
    val version: Int,
    val bundleSha256: String,
    val sizeBytes: Long?,
    val estLoadedMib: Int?,
    /** Decides which host serves it, so the pin file can name an absolute URL. */
    val trust: MapTrust,
)

class NotPublishedException(val version: Int) :
    RuntimeException("version $version is not published and cannot be pinned")

interface MapPinRepository {

    /**
     * Points an environment at a version. Rolling back and going live are the same operation, which
     * is what makes both instant and non-destructive.
     */
    fun move(environment: String, mapId: UUID, version: Int, bySub: String): PinRecord

    fun find(environment: String, mapId: UUID): PinRecord?

    /**
     * Every environment this map is live on. Without it the only way to see what players load is to
     * fetch the published pin file, which is a projection rather than the record.
     */
    fun findAll(mapId: UUID): List<PinRecord>

    /** What the pin file for this environment should contain, straight from the database. */
    fun pinned(environment: String): List<PinnedMap>
}
