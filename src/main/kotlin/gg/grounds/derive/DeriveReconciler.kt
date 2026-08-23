package gg.grounds.derive

import gg.grounds.blob.BlobStore
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveIdentity
import gg.grounds.domain.DeriveProblem
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
                            )
                        )
                    )
            ) {
                is DeriveSuccess -> {
                    require(
                        result.mapId == identity.mapId &&
                            result.version == identity.version &&
                            result.attempt == identity.attempt &&
                            result.sourceSha256 == identity.sourceSha256
                    )
                    requireArtifactSize(
                        BlobStore.deriveBundleKey(
                            identity.mapId,
                            identity.version,
                            identity.attempt,
                        ),
                        result.bundleSize,
                    )
                    requireArtifactSize(
                        BlobStore.deriveManifestKey(
                            identity.mapId,
                            identity.version,
                            identity.attempt,
                        ),
                        result.manifestSize,
                    )
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
                is DeriveFailure ->
                    versions.acceptFailure(
                        identity,
                        gg.grounds.domain.DerivedFailure(
                            result.scope,
                            result.retryable,
                            result.problems,
                        ),
                    )
            }
        } catch (_: DeriveResultRejectedException) {
            // Another tick accepted/retried this immutable version first; stale completion is
            // harmless.
        } catch (_: Exception) {
            versions.acceptFailure(
                identity,
                systemFailure(
                    "RESULT_UNAVAILABLE",
                    "completed derive Job has no acceptable result marker",
                ),
            )
        }
    }

    private fun requireArtifactSize(key: String, expectedSize: Long) {
        val metadata =
            blobs.headPrivate(key)
                ?: throw IllegalStateException("derive result references missing private artifact")
        require(metadata.sizeBytes == expectedSize) { "derive result artifact size does not match" }
    }

    companion object {
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
