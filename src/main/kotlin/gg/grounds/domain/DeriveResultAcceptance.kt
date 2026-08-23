package gg.grounds.domain

import java.util.UUID

/** Immutable facts that bind a worker result to one claimed derive attempt. */
data class DeriveIdentity(
    val mapId: UUID,
    val version: Int,
    val attempt: UUID,
    val sourceSha256: String,
    val assetCatalog: CatalogReference? = null,
) {
    init {
        require(version >= 0) { "version must be non-negative" }
        require(SHA_256.matches(sourceSha256)) { "sourceSha256 must be a lowercase SHA-256" }
    }
}

/** Facts persisted when a claimed derive attempt has been promoted successfully. */
data class DerivedFacts(
    val bundleSha256: String,
    val manifestSha256: String?,
    val sizeBytes: Long,
    val presentChunks: Int?,
    val estLoadedMib: Int?,
    val scene: SceneProjection,
) {
    init {
        require(SHA_256.matches(bundleSha256)) { "bundleSha256 must be a lowercase SHA-256" }
        require(manifestSha256 == null || SHA_256.matches(manifestSha256)) {
            "manifestSha256 must be a lowercase SHA-256"
        }
        require(sizeBytes >= 0) { "sizeBytes must be non-negative" }
        require(presentChunks == null || presentChunks >= 0) {
            "presentChunks must be non-negative"
        }
        require(estLoadedMib == null || estLoadedMib >= 0) { "estLoadedMib must be non-negative" }
        require(scene.status == SceneStatus.VALID || scene.status == SceneStatus.NONE) {
            "successful derivation requires a valid or absent scene"
        }
        require(scene.problems.isEmpty()) { "successful derivation cannot retain problems" }
        when (scene.status) {
            SceneStatus.NONE ->
                require(
                    scene.schemaVersion == null &&
                        scene.sha256 == null &&
                        scene.assetCatalog == null &&
                        scene.actionCatalog == null &&
                        scene.requiredActions.isEmpty()
                ) {
                    "an absent scene cannot retain scene facts"
                }
            SceneStatus.VALID ->
                require(
                    scene.schemaVersion != null &&
                        scene.sha256 != null &&
                        SHA_256.matches(scene.sha256) &&
                        scene.assetCatalog != null &&
                        scene.actionCatalog != null
                ) {
                    "a valid scene requires complete digest and catalog facts"
                }
        }
    }
}

/** A terminal worker failure. Scope and retryability are intentionally coupled. */
data class DerivedFailure(
    val scope: DeriveFailureScope,
    val retryable: Boolean,
    val problems: List<DeriveProblem>,
) {
    init {
        require(problems.isNotEmpty()) { "derive failure requires a problem" }
        require(retryable == (scope == DeriveFailureScope.SYSTEM)) {
            "only system failures are retryable"
        }
        require(problems.all { it.scope == scope }) {
            "failure problems must share the failure scope"
        }
    }
}

/** The result cannot be applied to the current immutable derive input or terminal state. */
open class DeriveResultRejectedException(message: String) : RuntimeException(message)

/** A repeated terminal result contradicts the immutable facts accepted for its attempt. */
class DeriveResultIntegrityException(message: String) : DeriveResultRejectedException(message)

private val SHA_256 = Regex("[0-9a-f]{64}")
