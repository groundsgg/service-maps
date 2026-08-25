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
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import org.jboss.logging.Logger

@ApplicationScoped
class DeriveReconciler
@Inject
constructor(
    private val versions: MapVersionRepository,
    private val coordinator: DeriveCoordinator,
    private val jobs: DeriveJobGateway,
    private val blobs: DeriveArtifactStore,
    private val scheduler: ReconciliationScheduler = ExecutorReconciliationScheduler(),
    private val metrics: DeriveReconciliationMetrics? = null,
) {
    private val log = Logger.getLogger(DeriveReconciler::class.java)
    private val reconciling = AtomicBoolean(false)
    private val retryLock = Any()
    private val retries = mutableMapOf<DeriveIdentity, RetryState>()
    private val watchOpen = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var watch: AutoCloseable? = null
    private var watchReconnects = 0

    /** Startup and the scheduled poll share the same gate. The poll is the correctness path. */
    fun onStart(@Observes event: StartupEvent) {
        reconcile()
        openWatch()
    }

    @Scheduled(every = "{grounds.maps.derive.poll-interval:30s}")
    fun reconcile() {
        if (!reconciling.compareAndSet(false, true)) return
        val integrityFailures = mutableListOf<DeriveResultIntegrityException>()
        try {
            val candidates = versions.listReconcileCandidates()
            metrics?.activeCandidates(candidates.size)
            candidates.forEach { record ->
                try {
                    when (record.state) {
                        VersionState.DRAFT -> coordinator.coordinate(record.mapId, record.version)
                        VersionState.DERIVING -> {
                            val identity = identity(record)
                            if (claimDueRetry(identity)) reconcileDeriving(record)
                        }
                        // A failed attempt is terminal for automation. Task 9 owns the explicitly
                        // authorized manual retry that creates a fresh attempt.
                        VersionState.DERIVE_FAILED -> Unit
                        else -> Unit
                    }
                } catch (failure: DeriveResultIntegrityException) {
                    integrityFailures += failure
                } catch (failure: DeriveJobCreationException) {
                    scheduleRetrySafely(failure.claimed, integrityFailures)
                } catch (failure: Exception) {
                    if (record.state == VersionState.DERIVING)
                        scheduleRetrySafely(record, integrityFailures)
                    else log.warnf("derive_reconcile scope=batch outcome=ignored")
                }
            }
        } finally {
            reconciling.set(false)
        }
        if (integrityFailures.isNotEmpty()) {
            val first = integrityFailures.first()
            integrityFailures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    private fun reconcileDeriving(record: gg.grounds.domain.MapVersionRecord) {
        val identity = identity(record)
        val status = jobs.find(identity)
        when (status) {
            null,
            DeriveJobStatus.MISSING -> {
                coordinator.ensure(record, status)
                metrics?.repair()
            }
            DeriveJobStatus.FAILED ->
                versions.acceptFailure(
                    identity,
                    systemFailure("JOB_FAILED", "derive Job exhausted its backoff limit"),
                )
            DeriveJobStatus.SUCCEEDED -> acceptResult(identity)
            else -> Unit
        }
        clearRetry(identity)
    }

    private fun scheduleRetrySafely(
        record: gg.grounds.domain.MapVersionRecord,
        integrityFailures: MutableList<DeriveResultIntegrityException>,
    ) {
        try {
            scheduleRetry(record)
        } catch (failure: DeriveResultIntegrityException) {
            integrityFailures += failure
        } catch (_: Exception) {
            log.warnf("derive_reconcile scope=attempt outcome=retry_schedule_failed")
        }
    }

    private fun scheduleRetry(record: gg.grounds.domain.MapVersionRecord) {
        val identity = identity(record)
        val failures =
            synchronized(retryLock) {
                val current = retries[identity]
                if (current?.phase == RetryPhase.PENDING) return
                val next = (current?.failures ?: 0) + 1
                if (next <= RETRY_DELAYS.size)
                    retries[identity] = RetryState(next, RetryPhase.PENDING)
                else retries.remove(identity)
                next
            }
        if (failures > RETRY_DELAYS.size) {
            versions.acceptFailure(
                identity,
                systemFailure("RECONCILIATION_UNAVAILABLE", "reconciliation retry budget exhausted"),
            )
            log.warnf(
                "derive_reconcile scope=attempt outcome=failed code=RECONCILIATION_UNAVAILABLE"
            )
            return
        }
        val delay = RETRY_DELAYS[failures - 1]
        metrics?.retry()
        log.warnf(
            "derive_reconcile scope=attempt outcome=retry code=TRANSIENT delay_seconds=%d",
            delay.seconds,
        )
        try {
            scheduler.schedule(delay) {
                synchronized(retryLock) {
                    retries[identity]?.takeIf { it.phase == RetryPhase.PENDING }?.phase =
                        RetryPhase.DUE
                }
                reconcile()
            }
        } catch (failure: Exception) {
            synchronized(retryLock) {
                retries[identity]?.takeIf { it.phase == RetryPhase.PENDING }?.phase = RetryPhase.DUE
            }
            throw failure
        }
    }

    private fun identity(record: gg.grounds.domain.MapVersionRecord) =
        DeriveIdentity(
            record.mapId,
            record.version,
            requireNotNull(record.deriveAttempt),
            requireNotNull(record.sourceSha256),
        )

    private fun claimDueRetry(identity: DeriveIdentity): Boolean =
        synchronized(retryLock) {
            when (retries[identity]?.phase) {
                RetryPhase.PENDING,
                RetryPhase.PROCESSING -> false
                RetryPhase.DUE -> {
                    retries.getValue(identity).phase = RetryPhase.PROCESSING
                    true
                }
                null -> true
            }
        }

    private fun clearRetry(identity: DeriveIdentity) =
        synchronized(retryLock) { retries.remove(identity) }

    private data class RetryState(val failures: Int, var phase: RetryPhase)

    private enum class RetryPhase {
        PENDING,
        DUE,
        PROCESSING,
    }

    private fun openWatch() {
        if (closed.get()) return
        if (!watchOpen.compareAndSet(false, true)) return
        watch =
            runCatching { jobs.watch(onEvent = { reconcile() }, onClose = ::watchClosed) }
                .getOrElse {
                    watchClosed(it)
                    null
                }
        if (watch == null) watchOpen.set(false) else watchReconnects = 0
    }

    private fun watchClosed(cause: Throwable?) {
        watchOpen.set(false)
        watch = null
        if (closed.get()) return
        val delay = WATCH_DELAYS[watchReconnects.coerceAtMost(WATCH_DELAYS.lastIndex)]
        watchReconnects++
        scheduler.schedule(delay, ::openWatch)
        log.warnf("derive_watch outcome=closed delay_seconds=%d", delay.seconds)
    }

    @PreDestroy
    fun close() {
        closed.set(true)
        runCatching { watch?.close() }
        scheduler.close()
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

        private val RETRY_DELAYS =
            listOf(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2))
        private val WATCH_DELAYS = RETRY_DELAYS

        fun systemFailure(code: String, message: String) =
            DerivedFailure(
                DeriveFailureScope.SYSTEM,
                true,
                listOf(DeriveProblem(DeriveFailureScope.SYSTEM, null, code, null, message)),
            )
    }
}
