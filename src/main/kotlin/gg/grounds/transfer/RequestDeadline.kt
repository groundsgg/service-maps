package gg.grounds.transfer

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RequestDeadlineExceeded : RuntimeException("request deadline exceeded")

internal object RequestDeadlineScheduler {
    val shared: ScheduledExecutorService =
        ScheduledThreadPoolExecutor(1) { runnable ->
                Thread(runnable, "grounds-http-deadline").apply { isDaemon = true }
            }
            .apply { removeOnCancelPolicy = true }
}

/** A cancellable request-wide deadline backed by one daemon scheduler per process. */
class RequestDeadline(
    timeoutMillis: Long,
    scheduler: ScheduledExecutorService = RequestDeadlineScheduler.shared,
    onExpire: () -> Unit,
) : AutoCloseable {
    private val expired = AtomicBoolean(false)
    private val task: ScheduledFuture<*> =
        scheduler.schedule(
            {
                expired.set(true)
                onExpire()
            },
            timeoutMillis,
            TimeUnit.MILLISECONDS,
        )

    fun check() {
        if (expired.get()) throw RequestDeadlineExceeded()
    }

    override fun close() {
        task.cancel(false)
    }
}
