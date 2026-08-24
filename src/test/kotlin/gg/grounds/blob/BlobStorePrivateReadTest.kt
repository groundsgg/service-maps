package gg.grounds.blob

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlobStorePrivateReadTest {
    @Test
    fun `declared body is read through the real SDK`() =
        RawHttpScript("hello".encodeToByteArray(), Framing.DECLARED).use { script ->
            assertArrayEquals("hello".encodeToByteArray(), script.blobs.getPrivate("marker", 5))
            script.assertServerFinished()
        }

    @Test
    fun `declared oversize rejects headers before body and closes the SDK response`() =
        RawHttpScript(ByteArray(6), Framing.DECLARED, BodyDisposition.WITHHOLD).use { script ->
            script.withClient { client ->
                val result = client.submit<ByteArray> { script.blobs.getPrivate("marker", 5) }
                script.awaitHeaders()
                assertBlobIntegrityFailure(result, "private object marker exceeds 5 bytes")
                script.assertClientClosed()
            }
        }

    @Test
    fun `chunked body at exact limit succeeds`() =
        RawHttpScript(ByteArray(5) { it.toByte() }, Framing.CHUNKED, BodyDisposition.COMPLETE)
            .use { script ->
                assertEquals(5, script.blobs.getPrivate("marker", 5).size)
                script.assertServerFinished()
            }

    @Test
    fun `chunked max plus one fails at the hard limit and closes the SDK response`() =
        RawHttpScript(ByteArray(6), Framing.CHUNKED, BodyDisposition.STREAM_WITHOUT_TERMINATOR)
            .use { script ->
                script.withClient { client ->
                    val result = client.submit<ByteArray> { script.blobs.getPrivate("marker", 5) }
                    script.awaitHeaders()
                    assertBlobIntegrityFailure(result, "private object marker exceeds 5 bytes")
                    script.assertClientClosed()
                }
            }

    @Test
    fun `truncated declared body surfaces premature EOF and closes the SDK response`() =
        RawHttpScript(ByteArray(5), Framing.DECLARED, BodyDisposition.TRUNCATE).use { script ->
            script.withClient { client ->
                val result = client.submit<ByteArray> { script.blobs.getPrivate("marker", 8) }
                script.awaitHeaders()
                assertIOExceptionFailure(result, "Premature EOF")
                script.assertClientClosed()
            }
        }

    private fun assertBlobIntegrityFailure(
        result: Future<ByteArray>,
        expectedMessage: String,
    ): BlobIntegrityException {
        val execution =
            assertThrows(ExecutionException::class.java) {
                result.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            }
        val failure = execution.cause
        assertEquals(BlobIntegrityException::class.java, failure?.javaClass)
        assertEquals(expectedMessage, failure?.message)
        return failure as BlobIntegrityException
    }

    private fun assertIOExceptionFailure(result: Future<ByteArray>, expectedMessage: String) {
        val execution =
            assertThrows(ExecutionException::class.java) {
                result.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            }
        val failure = execution.cause
        assertEquals(IOException::class.java, failure?.javaClass)
        assertEquals(expectedMessage, failure?.message)
    }

    private inline fun RawHttpScript.withClient(block: (ExecutorService) -> Unit) {
        val client = Executors.newSingleThreadExecutor()
        try {
            block(client)
        } finally {
            client.shutdownNow()
            try {
                client.awaitTermination(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AssertionError("interrupted while closing test client", failure)
            }
        }
    }

    private enum class Framing {
        DECLARED,
        CHUNKED,
    }

    private enum class BodyDisposition {
        COMPLETE,
        WITHHOLD,
        STREAM_WITHOUT_TERMINATOR,
        TRUNCATE,
    }

    private class RawHttpScript(
        private val body: ByteArray,
        private val framing: Framing,
        private val disposition: BodyDisposition = BodyDisposition.COMPLETE,
    ) : AutoCloseable {
        private val server = ServerSocket()
        private val worker = Executors.newSingleThreadExecutor()
        private val headersSent = CompletableFuture<Unit>()
        private val clientClosed = CompletableFuture<Unit>()
        private val finished = CompletableFuture<Unit>()
        private val serverFailure = CompletableFuture<Throwable>()

        init {
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            server.soTimeout = TIMEOUT_MILLIS.toInt()
            worker.submit { serve() }
        }

        val blobs =
            BlobStore(
                endpoint = "http://${server.inetAddress.hostAddress}:${server.localPort}",
                region = "us-east-1",
                accessKey = "key",
                secretKey = "secret",
                privateBucket = "private",
                publicBucket = "public",
                ugcBucket = "ugc",
                contentBaseUrl = "https://content.test",
                ugcBaseUrl = "https://ugc.test",
            )

        fun awaitHeaders() {
            await(headersSent)
            assertNoServerFailure()
        }

        fun assertClientClosed() {
            await(clientClosed)
            assertNoServerFailure()
        }

        fun assertServerFinished() {
            await(finished)
            assertNoServerFailure()
        }

        private fun serve() {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = TIMEOUT_MILLIS.toInt()
                    consumeRequest(socket)
                    sendResponse(socket)
                    when (disposition) {
                        BodyDisposition.COMPLETE -> Unit
                        BodyDisposition.WITHHOLD,
                        BodyDisposition.STREAM_WITHOUT_TERMINATOR,
                        BodyDisposition.TRUNCATE -> observeClientClose(socket)
                    }
                }
                finished.complete(Unit)
            } catch (failure: IOException) {
                serverFailure.complete(failure)
            } finally {
                server.close()
            }
        }

        private fun consumeRequest(socket: Socket) {
            val request = ByteArrayOutputStream()
            while (request.size() < MAX_REQUEST_BYTES) {
                val next = socket.getInputStream().read()
                if (next < 0) throw IOException("client closed before HTTP request headers")
                request.write(next)
                if (request.toByteArray().endsWith(HEADER_TERMINATOR)) return
            }
            throw IOException("HTTP request headers exceeded $MAX_REQUEST_BYTES bytes")
        }

        private fun sendResponse(socket: Socket) {
            val output = socket.getOutputStream()
            val headers =
                when (framing) {
                    Framing.DECLARED ->
                        "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    Framing.CHUNKED ->
                        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                }
            output.write(headers.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            headersSent.complete(Unit)

            when (disposition) {
                BodyDisposition.WITHHOLD -> Unit
                BodyDisposition.STREAM_WITHOUT_TERMINATOR -> {
                    writeBody(output)
                    output.flush()
                }
                BodyDisposition.COMPLETE -> {
                    writeBody(output)
                    if (framing == Framing.CHUNKED)
                        output.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.flush()
                }
                BodyDisposition.TRUNCATE -> {
                    output.write(body, 0, body.size / 2)
                    output.flush()
                    socket.shutdownOutput()
                }
            }
        }

        private fun writeBody(output: java.io.OutputStream) {
            if (framing == Framing.CHUNKED) {
                output.write("${body.size.toString(16)}\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.write(body)
                output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
            } else {
                output.write(body)
            }
        }

        private fun observeClientClose(socket: Socket) {
            try {
                while (socket.getInputStream().read() >= 0) Unit
                clientClosed.complete(Unit)
            } catch (_: SocketException) {
                clientClosed.complete(Unit)
            }
        }

        private fun assertNoServerFailure() {
            assertTrue(!serverFailure.isDone, "raw server failed: ${serverFailure.getNow(null)}")
        }

        private fun await(signal: CompletableFuture<Unit>) {
            signal.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }

        override fun close() {
            server.close()
            worker.shutdownNow()
            try {
                worker.awaitTermination(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AssertionError("interrupted while closing raw HTTP server", failure)
            }
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 1_000L
        const val MAX_REQUEST_BYTES = 16 * 1024
        val HEADER_TERMINATOR = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)

        fun ByteArray.endsWith(suffix: ByteArray): Boolean =
            size >= suffix.size &&
                suffix.indices.all { this[size - suffix.size + it] == suffix[it] }
    }
}
