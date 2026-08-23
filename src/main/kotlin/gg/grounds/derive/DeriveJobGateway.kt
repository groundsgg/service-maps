package gg.grounds.derive

import gg.grounds.blob.BlobMetadata
import gg.grounds.domain.DeriveIdentity

/**
 * The reconciler needs only attempt-scoped private artifacts. Keeping that narrow makes its
 * acceptance rules testable without an S3 client (or a Kubernetes cluster).
 */
interface DeriveArtifactStore {
    fun getPrivate(key: String): ByteArray

    fun headPrivate(key: String): BlobMetadata?
}

/** K8s is deliberately isolated here: a reconciliation tick remains deterministic in unit tests. */
interface DeriveJobGateway {
    fun create(request: DeriveJobRequest)

    fun find(identity: DeriveIdentity): DeriveJobStatus?

    /** Read-only capability probe; implementations must not create or delete Jobs here. */
    fun ready(): Boolean = true
}

data class DeriveJobRequest(val identity: DeriveIdentity, val requestJson: String)

enum class DeriveJobStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    MISSING,
}
