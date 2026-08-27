package gg.grounds.derive

import gg.grounds.blob.BlobMetadata
import gg.grounds.domain.DeriveIdentity
import gg.grounds.domain.MapTrust

/**
 * The reconciler needs only attempt-scoped private artifacts. Keeping that narrow makes its
 * acceptance rules testable without an S3 client (or a Kubernetes cluster).
 */
interface DeriveArtifactStore {
    /**
     * Reads a small control object without allowing an untrusted object to allocate unbounded heap.
     */
    fun getPrivate(key: String, maxBytes: Long): ByteArray

    fun headPrivate(key: String): BlobMetadata?

    /**
     * Promotes exactly the digest-pinned worker bundle before a version may become public. The
     * assigned private key is signed into the Job request; Task 8A proves that the immutable worker
     * Job is digest-pinned, so its streamed digest is the trust anchor for this content address.
     * The service intentionally does not download bundles.
     */
    fun promotePrivateBundle(
        sourceKey: String,
        destinationKey: String,
        expectedSizeBytes: Long,
        trust: MapTrust,
    )
}

/**
 * A transient backing-store failure; reconciliation retries this without accepting a terminal
 * result.
 */
class DeriveArtifactUnavailableException(cause: Throwable) : RuntimeException(cause)

/** K8s is deliberately isolated here: a reconciliation tick remains deterministic in unit tests. */
interface DeriveJobGateway {
    fun create(request: DeriveJobRequest)

    fun find(identity: DeriveIdentity): DeriveJobStatus?

    /** Read-only capability probe; implementations must not create or delete Jobs here. */
    fun ready(): Boolean = true

    /**
     * A bounded best-effort watch. It is only a latency hint: callers must continue polling for
     * correctness. The returned handle owns the live stream and must be closed by its owner.
     */
    fun watch(onEvent: () -> Unit, onClose: (Throwable?) -> Unit): AutoCloseable? = null
}

data class DeriveJobRequest(val identity: DeriveIdentity, val requestJson: String)

enum class DeriveJobStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    MISSING,
}
