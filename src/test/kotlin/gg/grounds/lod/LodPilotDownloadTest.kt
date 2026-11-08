package gg.grounds.lod

import com.sun.net.httpserver.HttpServer
import gg.grounds.blob.BlobStore
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class LodPilotDownloadTest {
    @Test
    fun `relay cannot write beyond the reservation and closes the origin once`() {
        var releases = 0
        val stream = PilotDownload(ByteArrayInputStream(ByteArray(100)), 12) { releases++ }
        assertEquals(12, stream.readAllBytes().size)
        stream.close()
        stream.close()
        assertEquals(1, releases)
        PilotDownload(ByteArrayInputStream(ByteArray(2)), 3) {}
            .use { assertFailsWith<IOException> { it.readAllBytes() } }
    }

    @Test
    fun `origin server failures have no SDK retries and broken headers fit two attempt envelope`() {
        for (brokenHeaders in listOf(false, true)) {
            val attempts = AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                attempts.incrementAndGet()
                if (!brokenHeaders) exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
            server.start()
            try {
                val blobs =
                    BlobStore(
                        "http://127.0.0.1:${server.address.port}",
                        "auto",
                        "test",
                        "test-secret",
                        "private",
                        "public",
                        "ugc",
                        "https://maps.test",
                        "https://ugc.test",
                    )
                assertFails { blobs.openLodPrivate("lod/test.zip", 4096).close() }
                assertTrue(attempts.get() in 1..2, "actual upstream attempts: ${attempts.get()}")
                if (!brokenHeaders) assertEquals(1, attempts.get())
            } finally {
                server.stop(0)
            }
        }
    }
}
