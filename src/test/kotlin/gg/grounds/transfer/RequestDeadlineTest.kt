package gg.grounds.transfer

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
        val occupied = CountDownLatch(1)
        val releaseScheduler = CountDownLatch(1)
        try {
            scheduler.execute {
                occupied.countDown()
                releaseScheduler.await()
            }
            assertTrue(occupied.await(1, TimeUnit.SECONDS))
            RequestDeadline(1, scheduler, expired::incrementAndGet, clock::get).use { it.check() }
            clock.set(TimeUnit.MILLISECONDS.toNanos(1))
            releaseScheduler.countDown()
            scheduler.purge()
            assertEquals(0, expired.get())
            assertEquals(0, scheduler.queue.size)
        } finally {
            releaseScheduler.countDown()
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

    @Test
    fun `close converts completion to expiry when the monotonic clock crosses after provisional completion`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        val clock = AtomicLong(0)
        val expired = AtomicInteger()
        val clockReads = AtomicInteger()
        try {
            val deadline =
                RequestDeadline(
                    timeoutMillis = 1,
                    scheduler = scheduler,
                    onExpire = expired::incrementAndGet,
                    nanoTime = {
                        if (clockReads.incrementAndGet() == 3)
                            clock.set(TimeUnit.MILLISECONDS.toNanos(1))
                        clock.get()
                    },
                    callbackExecutor = Executor { it.run() },
                )

            assertThrows(RequestDeadlineExceeded::class.java) { deadline.close() }
            assertEquals(1, expired.get())
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `observers wait for a provisional close and share its expired deadline decision`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        val callers = Executors.newFixedThreadPool(3)
        val clock = AtomicLong(0)
        val expired = AtomicInteger()
        val schedulerOccupied = CountDownLatch(1)
        val releaseScheduler = CountDownLatch(1)
        val completionEntered = CountDownLatch(1)
        val releaseCompletion = CountDownLatch(1)
        val checkCoordinating = CountDownLatch(1)
        val secondCloseCoordinating = CountDownLatch(1)
        val clockReads = AtomicInteger()
        try {
            scheduler.execute {
                schedulerOccupied.countDown()
                releaseScheduler.await()
            }
            assertTrue(schedulerOccupied.await(1, TimeUnit.SECONDS))
            val deadline =
                RequestDeadline(
                    timeoutMillis = 1,
                    scheduler = scheduler,
                    onExpire = expired::incrementAndGet,
                    nanoTime = {
                        when (clockReads.incrementAndGet()) {
                            3 -> {
                                completionEntered.countDown()
                                releaseCompletion.await()
                            }
                        }
                        clock.get()
                    },
                    callbackExecutor = Executor { it.run() },
                    onAwaitingCompletion = {
                        when (Thread.currentThread().name) {
                            "deadline-check" -> checkCoordinating.countDown()
                            "deadline-close" -> secondCloseCoordinating.countDown()
                        }
                    },
                )
            val firstClose =
                callers.submit<Unit> {
                    Thread.currentThread().name = "deadline-first-close"
                    deadline.close()
                }

            assertTrue(completionEntered.await(1, TimeUnit.SECONDS))
            clock.set(TimeUnit.MILLISECONDS.toNanos(1))
            val concurrentCheck =
                callers.submit<Unit> {
                    Thread.currentThread().name = "deadline-check"
                    deadline.check()
                }
            val secondClose =
                callers.submit<Unit> {
                    Thread.currentThread().name = "deadline-close"
                    deadline.close()
                }

            assertTrue(checkCoordinating.await(1, TimeUnit.SECONDS))
            assertTrue(secondCloseCoordinating.await(1, TimeUnit.SECONDS))
            assertTrue(!concurrentCheck.isDone)
            assertTrue(!secondClose.isDone)
            releaseCompletion.countDown()

            assertDeadlineExceeded(firstClose)
            assertDeadlineExceeded(concurrentCheck)
            assertDeadlineExceeded(secondClose)
            assertEquals(1, expired.get())
        } finally {
            releaseCompletion.countDown()
            releaseScheduler.countDown()
            callers.shutdownNow()
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `a clock exception during provisional close expires once and wakes observers`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        val callers = Executors.newFixedThreadPool(2)
        val clockReads = AtomicInteger()
        val expired = AtomicInteger()
        val schedulerOccupied = CountDownLatch(1)
        val releaseScheduler = CountDownLatch(1)
        val expiryStarted = CountDownLatch(1)
        val completionEntered = CountDownLatch(1)
        val releaseCompletion = CountDownLatch(1)
        val observerCoordinating = CountDownLatch(1)
        try {
            scheduler.execute {
                schedulerOccupied.countDown()
                releaseScheduler.await()
            }
            assertTrue(schedulerOccupied.await(1, TimeUnit.SECONDS))
            val deadline =
                RequestDeadline(
                    timeoutMillis = 1,
                    scheduler = scheduler,
                    onExpire = {
                        expired.incrementAndGet()
                        expiryStarted.countDown()
                    },
                    nanoTime = {
                        when (clockReads.incrementAndGet()) {
                            3 -> {
                                completionEntered.countDown()
                                releaseCompletion.await()
                                throw IllegalStateException("injected clock failure")
                            }
                        }
                        0
                    },
                    callbackExecutor = Executor { it.run() },
                    onAwaitingCompletion = { observerCoordinating.countDown() },
                )
            val closer = callers.submit<Unit> { deadline.close() }
            assertTrue(completionEntered.await(1, TimeUnit.SECONDS))
            val observer = callers.submit<Unit> { deadline.check() }
            assertTrue(observerCoordinating.await(1, TimeUnit.SECONDS))
            releaseCompletion.countDown()

            assertTrue(
                assertThrows(ExecutionException::class.java) { closer.get(1, TimeUnit.SECONDS) }
                    .cause is IllegalStateException
            )
            assertDeadlineExceeded(observer)
            assertEquals(1, expired.get())
        } finally {
            releaseCompletion.countDown()
            releaseScheduler.countDown()
            callers.shutdownNow()
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `interrupting a provisional closer expires once wakes observers and preserves its flag`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        val callers = Executors.newFixedThreadPool(2)
        val clockReads = AtomicInteger()
        val expired = AtomicInteger()
        val schedulerOccupied = CountDownLatch(1)
        val releaseScheduler = CountDownLatch(1)
        val closerEntered = CountDownLatch(1)
        val observerCoordinating = CountDownLatch(1)
        val closerThread = AtomicReference<Thread>()
        try {
            scheduler.execute {
                schedulerOccupied.countDown()
                releaseScheduler.await()
            }
            assertTrue(schedulerOccupied.await(1, TimeUnit.SECONDS))
            val deadline =
                RequestDeadline(
                    timeoutMillis = 1,
                    scheduler = scheduler,
                    onExpire = expired::incrementAndGet,
                    nanoTime = {
                        if (clockReads.incrementAndGet() == 3) {
                            closerEntered.countDown()
                            CountDownLatch(1).await()
                        }
                        0
                    },
                    callbackExecutor = Executor { it.run() },
                    onAwaitingCompletion = { observerCoordinating.countDown() },
                )
            val closer =
                callers.submit<Boolean> {
                    closerThread.set(Thread.currentThread())
                    try {
                        deadline.close()
                        false
                    } catch (_: InterruptedException) {
                        Thread.currentThread().isInterrupted
                    }
                }
            assertTrue(closerEntered.await(1, TimeUnit.SECONDS))
            val observer = callers.submit<Unit> { deadline.check() }
            assertTrue(observerCoordinating.await(1, TimeUnit.SECONDS))
            requireNotNull(closerThread.get()).interrupt()

            assertTrue(closer.get(1, TimeUnit.SECONDS))
            assertDeadlineExceeded(observer)
            assertEquals(1, expired.get())
        } finally {
            releaseScheduler.countDown()
            callers.shutdownNow()
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `interrupting a waiting observer expires once wakes closer and preserves its flag`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        val callers = Executors.newFixedThreadPool(2)
        val clockReads = AtomicInteger()
        val expired = AtomicInteger()
        val schedulerOccupied = CountDownLatch(1)
        val releaseScheduler = CountDownLatch(1)
        val expiryStarted = CountDownLatch(1)
        val closerEntered = CountDownLatch(1)
        val releaseCloser = CountDownLatch(1)
        val observerCoordinating = CountDownLatch(1)
        val observerThread = AtomicReference<Thread>()
        try {
            scheduler.execute {
                schedulerOccupied.countDown()
                releaseScheduler.await()
            }
            assertTrue(schedulerOccupied.await(1, TimeUnit.SECONDS))
            val deadline =
                RequestDeadline(
                    timeoutMillis = 1,
                    scheduler = scheduler,
                    onExpire = {
                        expired.incrementAndGet()
                        expiryStarted.countDown()
                    },
                    nanoTime = {
                        if (clockReads.incrementAndGet() == 3) {
                            closerEntered.countDown()
                            releaseCloser.await()
                        }
                        0
                    },
                    callbackExecutor = Executor { it.run() },
                    onAwaitingCompletion = { observerCoordinating.countDown() },
                )
            val closer = callers.submit<Unit> { deadline.close() }
            assertTrue(closerEntered.await(1, TimeUnit.SECONDS))
            val observer =
                callers.submit<Boolean> {
                    observerThread.set(Thread.currentThread())
                    try {
                        deadline.check()
                        false
                    } catch (_: RequestDeadlineExceeded) {
                        Thread.currentThread().isInterrupted
                    }
                }
            assertTrue(observerCoordinating.await(1, TimeUnit.SECONDS))
            requireNotNull(observerThread.get()).interrupt()
            assertTrue(expiryStarted.await(1, TimeUnit.SECONDS))
            releaseCloser.countDown()

            assertTrue(observer.get(1, TimeUnit.SECONDS))
            assertDeadlineExceeded(closer)
            assertEquals(1, expired.get())
        } finally {
            releaseCloser.countDown()
            releaseScheduler.countDown()
            callers.shutdownNow()
            scheduler.shutdownNow()
        }
    }

    private fun assertDeadlineExceeded(future: java.util.concurrent.Future<*>) {
        assertTrue(
            assertThrows(ExecutionException::class.java) { future.get(1, TimeUnit.SECONDS) }.cause
                is RequestDeadlineExceeded
        )
    }

    @Test
    fun `blocking expiry callback does not serialize a second expired request`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        val clock = AtomicLong(0)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        try {
            val first =
                RequestDeadline(
                    1,
                    scheduler,
                    {
                        firstStarted.countDown()
                        releaseFirst.await()
                    },
                    clock::get,
                )
            val second = RequestDeadline(1, scheduler, secondStarted::countDown, clock::get)
            clock.set(TimeUnit.MILLISECONDS.toNanos(1))

            assertThrows(RequestDeadlineExceeded::class.java) { first.check() }
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS))
            assertThrows(RequestDeadlineExceeded::class.java) { second.check() }
            assertTrue(secondStarted.await(1, TimeUnit.SECONDS))
        } finally {
            releaseFirst.countDown()
            scheduler.shutdownNow()
        }
    }
}
