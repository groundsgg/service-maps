package gg.grounds.blob

import gg.grounds.domain.MapTrust
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.concurrent.ConcurrentHashMap

/** Only public, content-addressed bundles; never private uploads or arbitrary object keys. */
interface PublicBundleObjects {
    fun head(sha256: String, trust: MapTrust): BlobMetadata?

    fun copy(sha256: String, source: MapTrust, target: MapTrust, sizeBytes: Long)
}

@ApplicationScoped
class BlobStorePublicBundleObjects @Inject constructor(private val blobs: BlobStore) :
    PublicBundleObjects {
    override fun head(sha256: String, trust: MapTrust) =
        blobs.headPublic(BlobStore.bundleKey(sha256), trust)

    override fun copy(sha256: String, source: MapTrust, target: MapTrust, sizeBytes: Long) =
        blobs.copyPublicObject(BlobStore.bundleKey(sha256), source, target, sizeBytes)
}

/** Materializes already-approved immutable content in the bucket serving its map. */
@ApplicationScoped
class PublishedBundles @Inject constructor(private val objects: PublicBundleObjects) {
    // Published references retain their immutable objects. Cache only verified positive HEADs;
    // missing objects and failures must be checked again on the next reconciliation.
    private val verified = ConcurrentHashMap<Pair<String, MapTrust>, BlobMetadata>()

    fun sourceTrust(sha256: String, preferred: MapTrust? = null): MapTrust =
        (listOfNotNull(preferred) + MapTrust.entries).distinct().firstOrNull {
            metadata(sha256, it) != null
        } ?: throw BlobIntegrityException("published source bundle is unavailable")

    fun available(sha256: String, trust: MapTrust, expectedSize: Long? = null): Boolean {
        val actual = metadata(sha256, trust) ?: return false
        if (expectedSize != null && actual.sizeBytes != expectedSize) {
            verified.remove(sha256 to trust)
            return false
        }
        return true
    }

    fun ensure(sha256: String, trust: MapTrust, expectedSize: Long? = null) {
        val target = metadata(sha256, trust)
        if (target != null) {
            requireSize(sha256, trust, target, expectedSize)
            return
        }
        val source = sourceTrust(sha256)
        val sourceMetadata = requireNotNull(metadata(sha256, source))
        requireSize(sha256, source, sourceMetadata, expectedSize)
        objects.copy(sha256, source, trust, sourceMetadata.sizeBytes)
        val copied =
            objects.head(sha256, trust)
                ?: throw BlobIntegrityException("published bundle copy is unavailable")
        requireSize(sha256, trust, copied, sourceMetadata.sizeBytes)
        verified[sha256 to trust] = copied
    }

    private fun metadata(sha256: String, trust: MapTrust): BlobMetadata? =
        verified[sha256 to trust]
            ?: objects.head(sha256, trust)?.also { verified[sha256 to trust] = it }

    private fun requireSize(
        sha256: String,
        trust: MapTrust,
        actual: BlobMetadata,
        expected: Long?,
    ) {
        if (expected != null && actual.sizeBytes != expected) {
            verified.remove(sha256 to trust)
            throw BlobIntegrityException("published bundle has an unexpected size")
        }
    }
}
