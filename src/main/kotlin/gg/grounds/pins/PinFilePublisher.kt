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

    fun publish(environment: String): Boolean =
        // Serialised per environment, because a full rebuild is idempotent but not
        // commutative in publication order: two moves can snapshot in one order and have
        // their PUTs land in the other, leaving the CDN permanently behind the database
        // with both callers told the write succeeded. Holding the lock across snapshot and
        // upload makes the last writer the last snapshot.
        //
        // This holds within one process. A second replica needs a conditional write or a
        // reconciler; until then this service runs single-replica, which is recorded in the
        // README rather than assumed.
        locks
            .computeIfAbsent(environment) { Any() }
            .let { lock ->
                synchronized(lock) {
                    try {
                        // Snapshot and serialisation are inside the try as well: a failure in
                        // either is still a failed projection, and letting it escape would turn
                        // an applied pin move into a 500.
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
                                                    // Absolute, because two hosts serve
                                                    // content now and a key alone no longer
                                                    // says which.
                                                    bundleUrl =
                                                        blobs.publicBaseFor(pin.trust) +
                                                            "/" +
                                                            BlobStore.bundleKey(pin.bundleSha256),
                                                    sizeBytes = pin.sizeBytes,
                                                    estLoadedMib = pin.estLoadedMib,
                                                )
                                        },
                                )
                            )
                        blobs.putPublic(
                            key = BlobStore.pinFileKey(environment),
                            body = body,
                            contentType = "application/json",
                            // The one mutable object in the design, so it is the one object that
                            // must not be cached for a year. Everything it points at is immutable.
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

    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
}

data class PinFile(val environment: String, val maps: Map<String, PinnedEntry>)

data class PinnedEntry(
    val version: Int,
    val bundleSha256: String,
    /** Absolute: first-party and creator content are served from different hosts. */
    val bundleUrl: String,
    val sizeBytes: Long?,
    val estLoadedMib: Int?,
)
