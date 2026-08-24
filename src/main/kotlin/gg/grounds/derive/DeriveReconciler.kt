package gg.grounds.derive

import gg.grounds.blob.BlobStore
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveIdentity
import gg.grounds.domain.DeriveProblem
import gg.grounds.domain.DeriveResultIntegrityException
import gg.grounds.domain.DeriveResultRejectedException
import gg.grounds.domain.DerivedFacts
import gg.grounds.domain.DerivedFailure
import gg.grounds.domain.MapVersionRepository
import gg.grounds.domain.SceneProjection
import gg.grounds.domain.SceneStatus
import gg.grounds.domain.VersionState
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.security.MessageDigest
import java.time.Duration

@ApplicationScoped
class DeriveReconciler
@Inject
constructor(
    private val versions: MapVersionRepository,
    private val coordinator: DeriveCoordinator,
    private val jobs: DeriveJobGateway,
    private val blobs: DeriveArtifactStore,
) {
    @Scheduled(every = "{grounds.maps.derive.poll-interval:30s}")
    fun reconcile() {
        versions.listReconcileCandidates().forEach { record ->
            when (record.state) {
                VersionState.DRAFT -> coordinator.coordinate(record.mapId, record.version)
                VersionState.DERIVING -> reconcileDeriving(record)
                VersionState.DERIVE_FAILED ->
                    if (
                        record.deriveFailureScope == DeriveFailureScope.SYSTEM &&
                            record.deriveRetryable
                    )
                        versions.retrySystemFailure(record.mapId, record.version)
                else -> Unit
            }
        }
    }

    private fun reconcileDeriving(record: gg.grounds.domain.MapVersionRecord) {
        val identity =
            DeriveIdentity(
                record.mapId,
                record.version,
                requireNotNull(record.deriveAttempt),
                requireNotNull(record.sourceSha256),
            )
        val status = jobs.find(identity)
        when (status) {
            null,
            DeriveJobStatus.MISSING -> coordinator.ensure(record, status)
            DeriveJobStatus.FAILED ->
                versions.acceptFailure(
                    identity,
                    systemFailure("JOB_FAILED", "derive Job exhausted its backoff limit"),
                )
            DeriveJobStatus.SUCCEEDED -> acceptResult(identity)
            else -> Unit
        }
    }

    private fun acceptResult(identity: DeriveIdentity) {
        try {
            when (
                val result =
                    CanonicalJson.readResult(
                        blobs.getPrivate(
                            BlobStore.deriveResultKey(
                                identity.mapId,
                                identity.version,
                                identity.attempt,
                            ),
                            RESULT_MAX_BYTES,
                        )
                    )
            ) {
                is DeriveSuccess -> {
                    requireResultIdentity(result, identity)
                    requireArtifactSize(
                        BlobStore.deriveBundleKey(
                            identity.mapId,
                            identity.version,
                            identity.attempt,
                        ),
                        result.bundleSize,
                    )
                    val manifestKey =
                        BlobStore.deriveManifestKey(
                            identity.mapId,
                            identity.version,
                            identity.attempt,
                        )
                    requireArtifactSize(manifestKey, result.manifestSize)
                    val manifest = blobs.getPrivate(manifestKey, MANIFEST_MAX_BYTES)
                    require(manifest.size.toLong() == result.manifestSize) {
                        "derive manifest content size does not match result"
                    }
                    require(sha256(manifest) == result.manifestSha256) {
                        "derive manifest digest does not match result"
                    }
                    val derivedManifest = CanonicalJson.readManifest(manifest)
                    require(derivedManifest.sourceSha256 == identity.sourceSha256) {
                        "derive manifest source does not match claimed request"
                    }
                    require(derivedManifest.scene == result.scene) {
                        "derive manifest scene does not match result"
                    }
                    val scene = result.scene
                    val projection =
                        if (scene.present)
                            SceneProjection(
                                SceneStatus.VALID,
                                scene.schemaVersion,
                                scene.sha256,
                                scene.assetCatalog,
                                scene.actionCatalog,
                                scene.requiredActions,
                                emptyList(),
                            )
                        else
                            SceneProjection(
                                SceneStatus.NONE,
                                null,
                                null,
                                null,
                                null,
                                emptyList(),
                                emptyList(),
                            )
                    blobs.promotePrivateBundle(
                        BlobStore.deriveBundleKey(
                            identity.mapId,
                            identity.version,
                            identity.attempt,
                        ),
                        BlobStore.bundleKey(result.bundleSha256),
                        result.bundleSize,
                    )
                    versions.acceptSuccess(
                        identity.copy(assetCatalog = scene.assetCatalog),
                        DerivedFacts(
                            result.bundleSha256,
                            result.manifestSha256,
                            result.bundleSize,
                            null,
                            null,
                            projection,
                        ),
                        "derive-worker",
                    )
                }
                is DeriveFailure -> {
                    requireResultIdentity(result, identity)
                    versions.acceptFailure(
                        identity,
                        gg.grounds.domain.DerivedFailure(
                            result.scope,
                            result.retryable,
                            result.problems,
                        ),
                    )
                }
            }
        } catch (e: DeriveResultIntegrityException) {
            throw e
        } catch (_: DeriveResultRejectedException) {
            // Another tick accepted/retried this immutable version first; stale completion is
            // harmless.
        } catch (_: Exception) {
            recordUnavailableResult(identity)
        }
    }

    private fun recordUnavailableResult(identity: DeriveIdentity) {
        try {
            versions.acceptFailure(
                identity,
                systemFailure(
                    "RESULT_UNAVAILABLE",
                    "completed derive Job has no acceptable result marker",
                ),
            )
        } catch (e: DeriveResultIntegrityException) {
            throw e
        } catch (_: DeriveResultRejectedException) {
            // A later tick may already have accepted or retried this immutable version.
        }
    }

    private fun requireArtifactSize(key: String, expectedSize: Long) {
        val metadata =
            blobs.headPrivate(key)
                ?: throw IllegalStateException("derive result references missing private artifact")
        require(metadata.sizeBytes == expectedSize) { "derive result artifact size does not match" }
    }

    private fun requireResultIdentity(result: DeriveResult, identity: DeriveIdentity) {
        require(
            result.mapId == identity.mapId &&
                result.version == identity.version &&
                result.attempt == identity.attempt &&
                result.sourceSha256 == identity.sourceSha256
        ) {
            "derive result does not match claimed request"
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val RESULT_MAX_BYTES = 1L shl 20
        private const val MANIFEST_MAX_BYTES = 4L shl 20

        fun retryDelay(failures: Int): Duration =
            when {
                failures <= 0 -> Duration.ofSeconds(5)
                failures == 1 -> Duration.ofSeconds(30)
                else -> Duration.ofMinutes(2)
            }

        fun systemFailure(code: String, message: String) =
            DerivedFailure(
                DeriveFailureScope.SYSTEM,
                true,
                listOf(DeriveProblem(DeriveFailureScope.SYSTEM, null, code, null, message)),
            )
    }
}
