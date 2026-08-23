package gg.grounds.derive

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkerHttpTransferTest {
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
}
