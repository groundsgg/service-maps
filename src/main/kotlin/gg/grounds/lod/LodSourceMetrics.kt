package gg.grounds.lod

import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application-origin reads, not SDK retry billing or bytes acknowledged by a remote client. */
interface LodSourceObserver {
    fun opened() = Unit

    fun openFailed() = Unit

    fun finished(bytes: Long, complete: Boolean) = Unit
}

@ApplicationScoped
class LodSourceMetrics @Inject constructor(registry: MeterRegistry) : LodSourceObserver {
    private val opens = registry.counter("grounds.lod.source.opens")
    private val failures = registry.counter("grounds.lod.source.open.failures")
    private val bytes = registry.counter("grounds.lod.source.read.bytes")
    private val complete = registry.counter("grounds.lod.source.transfers", "outcome", "complete")
    private val partial = registry.counter("grounds.lod.source.transfers", "outcome", "partial")

    override fun opened() = opens.increment()

    override fun openFailed() = failures.increment()

    override fun finished(bytes: Long, complete: Boolean) {
        this.bytes.increment(bytes.toDouble())
        (if (complete) this.complete else partial).increment()
    }
}
