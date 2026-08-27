package gg.grounds.derive

import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Reconciliation telemetry deliberately permits only bounded operational dimensions. */
@ApplicationScoped
class DeriveReconciliationMetrics @Inject constructor(private val registry: MeterRegistry) :
    DeriveReconciliationObserver {
    private val activeCandidates = AtomicInteger()

    init {
        registry.gauge("derive.reconciliation.active_candidates", activeCandidates)
    }

    override fun attempt(outcome: ReconciliationAttemptOutcome, scope: ReconciliationAttemptScope) {
        registry
            .counter(
                "derive.reconciliation.attempts",
                "outcome",
                outcome.label,
                "scope",
                scope.label,
            )
            .increment()
    }

    override fun retry() = registry.counter("derive.reconciliation.retries").increment()

    override fun repair() = registry.counter("derive.reconciliation.repairs").increment()

    override fun activeCandidates(count: Int) {
        activeCandidates.set(count)
    }

    override fun duration(trigger: ReconciliationTrigger, elapsed: Duration) {
        registry.timer("derive.reconciliation.duration", "trigger", trigger.label).record(elapsed)
    }
}

enum class ReconciliationAttemptOutcome {
    SUCCEEDED,
    FAILED;

    val label
        get() = name.lowercase(Locale.ROOT)
}

enum class ReconciliationAttemptScope {
    NONE,
    CONTENT,
    SYSTEM;

    val label
        get() = name.lowercase(Locale.ROOT)
}

enum class ReconciliationTrigger {
    RECONCILE;

    val label
        get() = name.lowercase(Locale.ROOT)
}

/** Observation must never participate in reconciliation correctness or retry state. */
interface DeriveReconciliationObserver {
    fun attempt(outcome: ReconciliationAttemptOutcome, scope: ReconciliationAttemptScope) = Unit

    fun retry()

    fun repair()

    fun activeCandidates(count: Int)

    fun duration(trigger: ReconciliationTrigger, elapsed: Duration) = Unit
}
