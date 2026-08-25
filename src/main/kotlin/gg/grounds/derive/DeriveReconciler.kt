package gg.grounds.derive

import gg.grounds.blob.BlobStore
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveIdentity
import gg.grounds.domain.DeriveProblem
import gg.grounds.domain.DeriveResultIntegrityException
import gg.grounds.domain.DeriveResultRejectedException
import gg.grounds.domain.DerivedFacts
import gg.grounds.domain.DerivedFailure
import gg.grounds.domain.MapTrust
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
    private val metrics: DeriveReconciliationObserver? = null,
) {
    private val log = Logger.getLogger(DeriveReconciler::class.java)
    private val reconciling = AtomicBoolean(false)
    private val rerunRequested = AtomicBoolean(false)
    private val retryLock = Any()
    private val retries = mutableMapOf<DeriveIdentity, RetryState>()
    private val closed = AtomicBoolean(false)
    private val watchLock = Any()
    private var watchGeneration = 0L
    /** Non-null while a particular generation is opening or live. */
    private var activeWatchGeneration: Long? = null
    private var liveWatch: AutoCloseable? = null
    private var watchReconnects = 0

    /** Startup and the scheduled poll share the same gate. The poll is the correctness path. */
    fun onStart(@Observes event: StartupEvent) {
        reconcile()
        openWatch()
    }

    @Scheduled(every = "{grounds.maps.derive.poll-interval:30s}")
    fun reconcile() {
        if (!reconciling.compareAndSet(false, true)) {
            rerunRequested.set(true)
            return
        }
        val integrityFailures = mutableListOf<DeriveResultIntegrityException>()
        val startedAt = System.nanoTime()
        try {
            val candidates = versions.listReconcileCandidates()
            observe { metrics?.activeCandidates(candidates.size) }
            candidates.forEach { record ->
                val candidateStartedAt = System.nanoTime()
                try {
                    when (record.state) {
                        VersionState.DRAFT -> {
                            coordinator.coordinate(record.mapId, record.version)
                            logTransition(
                                record,
                                "coordinated",
                                ReconciliationWorkScope.DRAFT,
                                candidateStartedAt,
                            )
                        }
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
                    logTransition(
                        record,
                        "failed",
                        workScope(record),
                        candidateStartedAt,
                        "RESULT_INTEGRITY",
                    )
                } catch (failure: DeriveJobCreationException) {
                    scheduleRetrySafely(failure.claimed, integrityFailures)
                } catch (failure: Exception) {
                    if (record.state == VersionState.DERIVING)
                        scheduleRetrySafely(record, integrityFailures)
                    else
                        logTransition(
                            record,
                            "failed",
                            workScope(record),
                            candidateStartedAt,
                            "RECONCILIATION_UNAVAILABLE",
                        )
                }
            }
        } finally {
            observe {
                metrics?.duration(
                    ReconciliationTrigger.RECONCILE,
                    Duration.ofNanos(System.nanoTime() - startedAt),
                )
            }
            reconciling.set(false)
        }
        if (rerunRequested.compareAndSet(true, false)) requestPromptRerun()
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
                observe { metrics?.repair() }
                logTransition(record, "repaired", ReconciliationWorkScope.DERIVING)
            }
            DeriveJobStatus.FAILED -> {
                val acceptance =
                    versions.acceptFailureOutcome(
                        identity,
                        systemFailure("JOB_FAILED", "derive Job exhausted its backoff limit"),
                    )
                if (acceptance.transitioned)
                    observeTerminal(
                        TerminalAttempt(
                            ReconciliationAttemptOutcome.FAILED,
                            ReconciliationAttemptScope.SYSTEM,
                            "JOB_FAILED",
                        )
                    )
                logTransition(
                    record,
                    "failed",
                    ReconciliationWorkScope.DERIVING,
                    code = "JOB_FAILED",
                )
            }
            DeriveJobStatus.SUCCEEDED -> {
                acceptResult(identity, record.trust)?.let { terminal ->
                    observeTerminal(terminal)
                    logTransition(
                        record,
                        if (terminal.outcome == ReconciliationAttemptOutcome.SUCCEEDED) "succeeded"
                        else "failed",
                        ReconciliationWorkScope.DERIVING,
                        code = terminal.code,
                    )
                }
            }
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
            logTransition(record, "failed", workScope(record), code = "RETRY_SCHEDULE_FAILED")
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
            val acceptance =
                versions.acceptFailureOutcome(
                    identity,
                    systemFailure(
                        "RECONCILIATION_UNAVAILABLE",
                        "reconciliation retry budget exhausted",
                    ),
                )
            if (acceptance.transitioned)
                observeTerminal(
                    TerminalAttempt(
                        ReconciliationAttemptOutcome.FAILED,
                        ReconciliationAttemptScope.SYSTEM,
                        "RECONCILIATION_UNAVAILABLE",
                    )
                )
            logTransition(record, "failed", workScope(record), code = "RECONCILIATION_UNAVAILABLE")
            return
        }
        val delay = RETRY_DELAYS[failures - 1]
        observe { metrics?.retry() }
        logTransition(record, "retry", workScope(record), code = "TRANSIENT")
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

    private fun observeTerminal(terminal: TerminalAttempt) {
        observe { metrics?.attempt(terminal.outcome, terminal.scope) }
    }

    private fun workScope(record: gg.grounds.domain.MapVersionRecord) =
        when (record.state) {
            VersionState.DRAFT -> ReconciliationWorkScope.DRAFT
            else -> ReconciliationWorkScope.DERIVING
        }

    private fun logTransition(
        record: gg.grounds.domain.MapVersionRecord,
        outcome: String,
        scope: ReconciliationWorkScope,
        startedAt: Long? = null,
        code: String? = null,
    ) {
        val duration =
            startedAt?.let { " duration_ms=${(System.nanoTime() - it) / 1_000_000}" } ?: ""
        val resultCode = code?.let { " code=$it" } ?: ""
        log.infof(
            "derive_reconcile map_id=%s version=%d attempt=%s state=%s scope=%s outcome=%s%s%s",
            record.mapId,
            record.version,
            record.deriveAttempt ?: "none",
            record.state,
            scope.name.lowercase(),
            outcome,
            resultCode,
            duration,
        )
    }

    private fun requestPromptRerun() {
        try {
            scheduler.schedule(Duration.ZERO, ::reconcile)
        } catch (_: Exception) {
            // Due retries remain claimable by the next poll/watch trigger; never restore PENDING.
            log.warnf("derive_reconcile scope=batch outcome=rerun_schedule_failed")
        }
    }

    private fun observe(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            log.warnf("derive_reconcile scope=metrics outcome=ignored")
        }
    }

    private data class RetryState(val failures: Int, var phase: RetryPhase)

    private data class TerminalAttempt(
        val outcome: ReconciliationAttemptOutcome,
        val scope: ReconciliationAttemptScope,
        val code: String? = null,
    )

    private enum class ReconciliationWorkScope {
        DRAFT,
        DERIVING,
    }

    private enum class RetryPhase {
        PENDING,
        DUE,
        PROCESSING,
    }

    private fun openWatch() {
        val generation =
            synchronized(watchLock) {
                if (closed.get() || activeWatchGeneration != null) return
                val next = watchGeneration + 1
                watchGeneration = next
                activeWatchGeneration = next
                next
            }
        val opened =
            try {
                jobs.watch(
                    onEvent = { watchEvent(generation) },
                    onClose = { cause -> watchClosed(generation, cause) },
                )
            } catch (failure: Exception) {
                watchClosed(generation, failure)
                return
            }
        val mustClose =
            synchronized(watchLock) {
                when {
                    closed.get() || activeWatchGeneration != generation -> true
                    opened == null -> {
                        // A disabled/unavailable latency hint is not a watch loss. Polling remains
                        // authoritative and will continue to reconcile without reconnect churn.
                        activeWatchGeneration = null
                        false
                    }
                    else -> {
                        liveWatch = opened
                        false
                    }
                }
            }
        if (mustClose) runCatching { opened?.close() }
    }

    /** A labeled derive event is the only healthy signal that resets reconnect backoff. */
    private fun watchEvent(generation: Long) {
        val current =
            synchronized(watchLock) {
                if (closed.get() || activeWatchGeneration != generation) false
                else {
                    watchReconnects = 0
                    true
                }
            }
        if (current) reconcile()
    }

    private fun watchClosed(generation: Long, cause: Throwable?) {
        val delay =
            synchronized(watchLock) {
                if (closed.get() || activeWatchGeneration != generation) return
                activeWatchGeneration = null
                liveWatch = null
                WATCH_DELAYS[watchReconnects.coerceAtMost(WATCH_DELAYS.lastIndex)].also {
                    watchReconnects++
                }
            }
        try {
            scheduler.schedule(delay, ::openWatch)
        } catch (_: Exception) {
            log.warnf("derive_watch outcome=reconnect_schedule_failed")
            return
        }
        log.warnf("derive_watch outcome=closed delay_seconds=%d", delay.seconds)
    }

    @PreDestroy
    fun close() {
        val watch =
            synchronized(watchLock) {
                closed.set(true)
                activeWatchGeneration = null
                liveWatch.also { liveWatch = null }
            }
        runCatching { watch?.close() }
        runCatching { scheduler.close() }
    }

    private fun acceptResult(identity: DeriveIdentity, trust: MapTrust): TerminalAttempt? {
        return try {
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
                        trust,
                    )
                    val acceptance =
                        versions.acceptSuccessOutcome(
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
                    if (acceptance.transitioned)
                        TerminalAttempt(
                            ReconciliationAttemptOutcome.SUCCEEDED,
                            ReconciliationAttemptScope.NONE,
                        )
                    else null
                }
                is DeriveFailure -> {
                    requireResultIdentity(result, identity)
                    val acceptance =
                        versions.acceptFailureOutcome(
                            identity,
                            gg.grounds.domain.DerivedFailure(
                                result.scope,
                                result.retryable,
                                result.problems,
                            ),
                        )
                    if (acceptance.transitioned)
                        TerminalAttempt(
                            ReconciliationAttemptOutcome.FAILED,
                            result.scope.metricScope(),
                            result.problems.firstOrNull()?.code,
                        )
                    else null
                }
            }
        } catch (e: DeriveResultIntegrityException) {
            throw e
        } catch (_: DeriveResultRejectedException) {
            // Another tick accepted/retried this immutable version first; stale completion is
            // harmless.
            null
        } catch (e: DeriveArtifactUnavailableException) {
            throw e
        } catch (_: Exception) {
            recordUnavailableResult(identity)
        }
    }

    private fun recordUnavailableResult(identity: DeriveIdentity): TerminalAttempt? {
        return try {
            val acceptance =
                versions.acceptFailureOutcome(
                    identity,
                    systemFailure(
                        "RESULT_UNAVAILABLE",
                        "completed derive Job has no acceptable result marker",
                    ),
                )
            if (acceptance.transitioned)
                TerminalAttempt(
                    ReconciliationAttemptOutcome.FAILED,
                    ReconciliationAttemptScope.SYSTEM,
                    "RESULT_UNAVAILABLE",
                )
            else null
        } catch (e: DeriveResultIntegrityException) {
            throw e
        } catch (_: DeriveResultRejectedException) {
            // A later tick may already have accepted or retried this immutable version.
            null
        }
    }

    private fun DeriveFailureScope.metricScope() =
        when (this) {
            DeriveFailureScope.CONTENT -> ReconciliationAttemptScope.CONTENT
            DeriveFailureScope.SYSTEM -> ReconciliationAttemptScope.SYSTEM
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
