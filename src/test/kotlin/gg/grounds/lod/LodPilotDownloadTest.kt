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
    fun `source accounting distinguishes completed partial and truncated streams once`() {
        fun transfer(available: Int, expected: Long, partial: Boolean): List<Pair<Long, Boolean>> {
            val seen = mutableListOf<Pair<Long, Boolean>>()
            var released = 0
            val stream =
                PilotDownload(
                    ByteArrayInputStream(ByteArray(available)),
                    expected,
                    { bytes, completed -> seen += bytes to completed },
                ) {
                    released++
                }
            if (partial) assertEquals(2, stream.readNBytes(2).size)
            else if (available < expected) assertFailsWith<IOException> { stream.readAllBytes() }
            else assertEquals(expected.toInt(), stream.readAllBytes().size)
            stream.close()
            stream.close()
            assertEquals(1, released)
            return seen
        }
        assertEquals(listOf(12L to true), transfer(100, 12, false))
        assertEquals(listOf(2L to false), transfer(100, 12, true))
        assertEquals(listOf(2L to false), transfer(2, 12, false))
    }

    @Test
    fun `observability failure cannot retain the source permit or fail close`() {
        var released = 0
        val stream =
            PilotDownload(
                ByteArrayInputStream(byteArrayOf(1)),
                1,
                { _, _ -> error("metrics unavailable") },
            ) {
                released++
            }
        assertEquals(1, stream.read())
        stream.close()
        assertEquals(1, released)
    }

    @Test
    fun `deadline close observes a still finishing read once after its bytes are known`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Boolean>>()
        val releases = AtomicInteger()
        val origin =
            object : java.io.InputStream() {
                override fun read(): Int = error("not used")

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown()
                    check(finish.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    bytes[offset] = 1
                    return 1
                }
            }
        val stream =
            PilotDownload(origin, 2, { bytes, complete -> seen += bytes to complete }) {
                releases.incrementAndGet()
            }
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val read = pool.submit<Int> { stream.read() }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            stream.close()
            assertTrue(seen.isEmpty())
            finish.countDown()
            assertEquals(1, read.get(5, java.util.concurrent.TimeUnit.SECONDS))
            stream.close()
            assertEquals(listOf(1L to false), seen.toList())
            assertEquals(1, releases.get())
        } finally {
            finish.countDown()
            stream.close()
            pool.shutdownNow()
        }
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
