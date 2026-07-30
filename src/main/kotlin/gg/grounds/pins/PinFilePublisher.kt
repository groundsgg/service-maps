package gg.grounds.pins

import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.blob.BlobStore
import gg.grounds.domain.MapPinRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * Publishes `pins/<env>.json` to the public bucket.
 *
 * This file is the whole read path's control plane: a game server reads it off the CDN and then
 * fetches bundles by digest, so **no pod ever calls this service to boot**. That is also why the
 * file is rebuilt from the database in full on every move rather than patched — a full rebuild is
 * idempotent, so two moves racing each other converge on the database's state instead of
 * interleaving into something neither writer intended.
 *
 * A failed publish does not fail the move. The move is committed and audited; the file is a
 * projection, and a projection that is briefly behind is repaired by republishing. Losing the move
 * itself would be the unrecoverable outcome.
 */
@ApplicationScoped
class PinFilePublisher
@Inject
constructor(
    private val pins: MapPinRepository,
    private val blobs: BlobStore,
    private val json: ObjectMapper,
) {

    private val log = Logger.getLogger(PinFilePublisher::class.java)

    fun publish(environment: String): Boolean {
        val body =
            json.writeValueAsString(
                PinFile(
                    environment = environment,
                    maps =
                        pins.pinned(environment).associate { pin ->
                            pin.address.toString() to
                                PinnedEntry(
                                    version = pin.version,
                                    bundleSha256 = pin.bundleSha256,
                                    bundleKey = BlobStore.bundleKey(pin.bundleSha256),
                                    sizeBytes = pin.sizeBytes,
                                    estLoadedMib = pin.estLoadedMib,
                                )
                        },
                )
            )
        return try {
            blobs.putPublic(
                key = BlobStore.pinFileKey(environment),
                body = body,
                contentType = "application/json",
                // The one mutable object in the design, so it is the one object that must
                // not be cached for a year. Everything it points at is immutable.
                cacheControl = "public, max-age=30",
            )
            true
        } catch (e: Exception) {
            log.errorf(
                e,
                "could not publish the pin file for %s; the move itself stands",
                environment,
            )
            false
        }
    }
}

data class PinFile(val environment: String, val maps: Map<String, PinnedEntry>)

data class PinnedEntry(
    val version: Int,
    val bundleSha256: String,
    /** Ready to append to the CDN base, so a server needs no key-layout knowledge. */
    val bundleKey: String,
    val sizeBytes: Long?,
    val estLoadedMib: Int?,
)
