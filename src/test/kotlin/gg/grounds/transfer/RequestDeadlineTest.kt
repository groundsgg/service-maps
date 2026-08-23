package gg.grounds.transfer

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequestDeadlineTest {
    @Test
    fun `check expires once when the sole scheduler thread is occupied past its monotonic deadline`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        val clock = AtomicLong(0)
        val expired = AtomicInteger()
        val occupied = CountDownLatch(1)
        val releaseScheduler = CountDownLatch(1)
        val callbackStarted = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        try {
            scheduler.execute {
                occupied.countDown()
                releaseScheduler.await()
            }
            assertTrue(occupied.await(1, TimeUnit.SECONDS))
            val deadline =
                RequestDeadline(
                    timeoutMillis = 1,
                    scheduler = scheduler,
                    nanoTime = clock::get,
                    onExpire = {
                        expired.incrementAndGet()
                        callbackStarted.countDown()
                        releaseCallback.await()
                    },
                )
            clock.set(TimeUnit.MILLISECONDS.toNanos(1))

            assertThrows(RequestDeadlineExceeded::class.java) { deadline.check() }
            assertThrows(RequestDeadlineExceeded::class.java) { deadline.close() }
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS))
            assertEquals(1, expired.get())
            releaseCallback.countDown()
            releaseScheduler.countDown()
            scheduler.purge()
            assertEquals(1, expired.get())
        } finally {
            releaseCallback.countDown()
            releaseScheduler.countDown()
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `close wins before the monotonic deadline and cancels its timer`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        val clock = AtomicLong(0)
        val expired = AtomicInteger()
        try {
            RequestDeadline(1, scheduler, expired::incrementAndGet, clock::get).use { it.check() }
            clock.set(TimeUnit.MILLISECONDS.toNanos(1))
            scheduler.purge()
            assertEquals(0, expired.get())
            assertEquals(0, scheduler.queue.size)
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `close reports expiry when the monotonic deadline wins before timer execution`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        val clock = AtomicLong(0)
        val expired = AtomicInteger()
        try {
            val deadline =
                RequestDeadline(
                    1,
                    scheduler,
                    expired::incrementAndGet,
                    clock::get,
                    Executor { it.run() },
                )
            clock.set(TimeUnit.MILLISECONDS.toNanos(1))

            assertThrows(RequestDeadlineExceeded::class.java) { deadline.close() }
            assertEquals(1, expired.get())
        } finally {
            scheduler.shutdownNow()
        }
    }
}
