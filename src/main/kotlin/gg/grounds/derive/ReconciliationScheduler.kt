package gg.grounds.derive

import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Narrow scheduler seam: retries are deterministic in tests and never block request threads. */
interface ReconciliationScheduler : AutoCloseable {
    fun schedule(delay: Duration, task: () -> Unit)
}

@ApplicationScoped
class ExecutorReconciliationScheduler : ReconciliationScheduler {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "derive-reconciliation").apply { isDaemon = true }
        }

    override fun schedule(delay: Duration, task: () -> Unit) {
        executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS)
    }

    override fun close() {
        executor.shutdownNow()
    }
}
