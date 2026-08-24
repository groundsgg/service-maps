package gg.grounds.blob

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlobStorePrivateReadTest {
    @Test
    fun `declared body is read through the real SDK`() =
        Script("hello".encodeToByteArray(), declared = true).use { script ->
            assertArrayEquals("hello".encodeToByteArray(), script.blobs.getPrivate("marker", 5))
            assertEquals(5, script.bytesWritten.get())
            assertTrue(script.closedSignal.await(1, TimeUnit.SECONDS))
        }

    @Test
    fun `declared oversize is rejected before body consumption`() =
        Script(ByteArray(6), declared = true, withholdBody = true).use { script ->
            Executors.newSingleThreadExecutor().use { pool ->
                val result = pool.submit<ByteArray> { script.blobs.getPrivate("marker", 5) }
                assertTrue(script.headersSent.await(1, TimeUnit.SECONDS))
                val failure =
                    assertThrows(ExecutionException::class.java) { result.get(1, TimeUnit.SECONDS) }
                assertTrue(failure.cause is BlobIntegrityException)
            }
            script.releaseBody.countDown()
            assertTrue(script.closedSignal.await(1, TimeUnit.SECONDS))
        }

    @Test
    fun `chunked body at exact limit succeeds`() =
        Script(ByteArray(5) { it.toByte() }, declared = false).use { script ->
            assertEquals(5, script.blobs.getPrivate("marker", 5).size)
            assertEquals(5, script.bytesWritten.get())
            assertEquals(1, script.closed.get())
        }

    @Test
    fun `chunked body over limit stops before unbounded allocation`() =
        Script(ByteArray(6), declared = false).use { script ->
            assertThrows(BlobIntegrityException::class.java) {
                script.blobs.getPrivate("marker", 5)
            }
            assertEquals(6, script.bytesWritten.get())
            assertEquals(1, script.closed.get())
        }

    @Test
    fun `mid read transport failure closes response`() =
        Script(ByteArray(5), declared = true, truncate = true).use { script ->
            assertThrows(Exception::class.java) { script.blobs.getPrivate("marker", 8) }
            assertEquals(true, script.closed.get() >= 1)
        }

    private class Script(
        private val body: ByteArray,
        private val declared: Boolean,
        private val truncate: Boolean = false,
        private val withholdBody: Boolean = false,
    ) : AutoCloseable {
        val bytesWritten = AtomicInteger()
        val closed = AtomicInteger()
        val closedSignal = CountDownLatch(1)
        val headersSent = CountDownLatch(1)
        val releaseBody = CountDownLatch(1)
        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                executor = Executors.newSingleThreadExecutor()
                createContext("/private/marker") { exchange -> respond(exchange) }
                start()
            }
        val blobs =
            BlobStore(
                endpoint = "http://${server.address.hostString}:${server.address.port}",
                region = "us-east-1",
                accessKey = "key",
                secretKey = "secret",
                privateBucket = "private",
                publicBucket = "public",
                ugcBucket = "ugc",
                contentBaseUrl = "https://content.test",
                ugcBaseUrl = "https://ugc.test",
            )

        private fun respond(exchange: HttpExchange) {
            try {
                exchange.requestBody.close()
                if (declared) exchange.responseHeaders.add("Content-Length", body.size.toString())
                exchange.sendResponseHeaders(200, if (declared) body.size.toLong() else 0)
                headersSent.countDown()
                exchange.responseBody.flush()
                if (withholdBody) releaseBody.await(2, TimeUnit.SECONDS)
                val written = if (truncate) body.size / 2 else body.size
                exchange.responseBody.write(body, 0, written)
                bytesWritten.addAndGet(written)
                if (truncate) exchange.responseBody.close() else Unit
            } finally {
                closed.incrementAndGet()
                closedSignal.countDown()
                exchange.close()
            }
        }

        override fun close() {
            server.stop(0)
            (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
        }
    }
}
