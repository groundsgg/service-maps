package gg.grounds.derive

import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.concurrent.atomic.AtomicInteger

/** Reconciliation telemetry deliberately permits only bounded operational dimensions. */
@ApplicationScoped
class DeriveReconciliationMetrics @Inject constructor(private val registry: MeterRegistry) {
    private val activeCandidates = AtomicInteger()

    init {
        registry.gauge("derive.reconciliation.active_candidates", activeCandidates)
    }

    fun attempt(outcome: String, scope: String) {
        registry
            .counter("derive.reconciliation.attempts", "outcome", outcome, "scope", scope)
            .increment()
    }

    fun retry() = registry.counter("derive.reconciliation.retries").increment()

    fun repair() = registry.counter("derive.reconciliation.repairs").increment()

    fun activeCandidates(count: Int) {
        activeCandidates.set(count)
    }

    fun duration(trigger: String, block: () -> Unit) {
        registry.timer("derive.reconciliation.duration", "trigger", trigger).record(block)
    }
}
