package gg.grounds.derive

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.io.path.readBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkerHttpTransferTest {
    @Test
    fun `preserves a pre-existing destination when create-new rejects it`() {
        server { server ->
            server.createContext("/source") { exchange ->
                exchange.sendResponseHeaders(200, 1)
                exchange.responseBody.use { it.write(byteArrayOf(1)) }
            }
            server.start()
            val output = Files.createTempFile("source-", ".tmp")
            Files.write(output, byteArrayOf(9, 8, 7))

            assertThrows(WorkerTransferException::class.java) {
                WorkerHttpTransfer(true).download(uri(server, "/source"), output)
            }
            assertEquals(listOf<Byte>(9, 8, 7), output.readBytes().toList())
        }
    }

    @Test
    fun `removes a created partial destination when a chunked source exceeds its limit`() {
        server { server ->
            server.createContext("/source") { exchange ->
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(byteArrayOf(1, 2)) }
            }
            server.start()
            val output = Files.createTempFile("source-", ".tmp")
            Files.delete(output)

            assertThrows(WorkerSourceLimitException::class.java) {
                WorkerHttpTransfer(true, maxSourceBytes = 1)
                    .download(uri(server, "/source"), output)
            }
            assertFalse(Files.exists(output))
        }
    }

    @Test
    fun `accepts a source exactly at its compressed limit`() {
        val body = byteArrayOf(1, 2)
        server { server ->
            server.createContext("/source") { exchange ->
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            val output = Files.createTempFile("source-", ".tmp")
            Files.delete(output)

            assertEquals(
                digest(body),
                WorkerHttpTransfer(true, maxSourceBytes = body.size.toLong())
                    .download(uri(server, "/source"), output),
            )
            assertEquals(body.toList(), output.readBytes().toList())
        }
    }

    @Test
    fun `classifies a drip-fed source deadline as a transfer failure`() {
        server { server ->
            server.createContext("/source") { exchange ->
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(byteArrayOf(1))
                exchange.responseBody.flush()
                Thread.sleep(100)
                exchange.responseBody.close()
            }
            server.start()
            val output = Files.createTempFile("source-", ".tmp")
            Files.delete(output)

            val failure =
                assertThrows(WorkerTransferException::class.java) {
                    WorkerHttpTransfer(true, requestDeadlineMillis = 25)
                        .download(uri(server, "/source"), output)
                }
            assertEquals("request deadline exceeded", failure.message)
            assertFalse(Files.exists(output))
        }
    }

    @Test
    fun `cancels each completed request deadline without affecting a later request`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        try {
            server { server ->
                server.createContext("/source") { exchange ->
                    exchange.sendResponseHeaders(200, 1)
                    exchange.responseBody.use { it.write(byteArrayOf(1)) }
                }
                server.start()
                repeat(2) {
                    val output = Files.createTempFile("source-", ".tmp")
                    Files.delete(output)
                    WorkerHttpTransfer(true, deadlineScheduler = scheduler)
                        .download(uri(server, "/source"), output)
                }
            }
            scheduler.purge()
            assertEquals(0, scheduler.queue.size)
        } finally {
            scheduler.shutdownNow()
            scheduler.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `mutation accepting a declared oversized source is rejected before body read`() {
        var requested = false
        server { server ->
            server.createContext("/source") { exchange ->
                requested = true
                exchange.sendResponseHeaders(200, 1024)
                exchange.responseBody.close()
            }
            server.start()
            val output = Files.createTempFile("source-", ".tmp")
            Files.delete(output)
            assertThrows(WorkerTransferException::class.java) {
                WorkerHttpTransfer(true, maxSourceBytes = 1)
                    .download(URI("http://127.0.0.1:${server.address.port}/source"), output)
            }
            assertFalse(Files.exists(output))
        }
        assertTrue(requested)
    }

    private fun server(block: (HttpServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            block(server)
        } finally {
            server.stop(0)
        }
    }

    private fun uri(server: HttpServer, path: String) =
        URI("http://127.0.0.1:${server.address.port}$path")

    private fun digest(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
