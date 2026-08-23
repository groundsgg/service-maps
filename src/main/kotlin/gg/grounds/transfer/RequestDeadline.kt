package gg.grounds.transfer

import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RequestDeadlineExceeded : RuntimeException("request deadline exceeded")

internal object RequestDeadlineScheduler {
    val shared: ScheduledExecutorService =
        ScheduledThreadPoolExecutor(1) { runnable ->
                Thread(runnable, "grounds-http-deadline").apply { isDaemon = true }
            }
            .apply { removeOnCancelPolicy = true }

    val callbacks: Executor = Executor { runnable -> Thread.ofVirtual().start(runnable) }
}

/** A cancellable request-wide deadline backed by shared daemon infrastructure. */
class RequestDeadline(
    timeoutMillis: Long,
    private val scheduler: ScheduledExecutorService = RequestDeadlineScheduler.shared,
    private val onExpire: () -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val callbackExecutor: Executor = RequestDeadlineScheduler.callbacks,
    private val afterCompletionTransition: () -> Unit = {},
) : AutoCloseable {
    private val timeoutMillis = timeoutMillis.also { require(it > 0) }
    private val deadlineNanos = nanoTime() + TimeUnit.MILLISECONDS.toNanos(this.timeoutMillis)
    private val state = AtomicReference(State.ACTIVE)
    private val task = AtomicReference<ScheduledFuture<*>?>()

    init {
        val scheduled = scheduler.schedule(::expire, this.timeoutMillis, TimeUnit.MILLISECONDS)
        task.set(scheduled)
        if (state.get() == State.EXPIRED) scheduled.cancel(false)
    }

    fun check() {
        expireIfDue()
        if (state.get() == State.EXPIRED) throw RequestDeadlineExceeded()
    }

    override fun close() {
        while (true) {
            expireIfDue()
            when (state.get()) {
                State.COMPLETED -> return
                State.EXPIRED -> throw RequestDeadlineExceeded()
                State.ACTIVE ->
                    if (state.compareAndSet(State.ACTIVE, State.COMPLETED)) {
                        afterCompletionTransition()
                        if (nanoTime() >= deadlineNanos) {
                            expireCompleted()
                            throw RequestDeadlineExceeded()
                        }
                        task.get()?.cancel(false)
                        return
                    }
            }
        }
    }

    private fun expireIfDue() {
        if (nanoTime() >= deadlineNanos) expire()
    }

    private fun expire() {
        if (state.compareAndSet(State.ACTIVE, State.EXPIRED)) {
            dispatchExpiry()
        }
    }

    private fun expireCompleted() {
        if (state.compareAndSet(State.COMPLETED, State.EXPIRED)) {
            dispatchExpiry()
        }
    }

    private fun dispatchExpiry() {
        task.get()?.cancel(false)
        callbackExecutor.execute(onExpire)
    }

    private enum class State {
        ACTIVE,
        COMPLETED,
        EXPIRED,
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10 * 60 * 1_000L
    }
}
