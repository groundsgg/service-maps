package gg.grounds.derive

import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DerivedBundleWriterTest {
    @Test
    fun `writes a large ordinary spool file as a digest-addressable artifact`() {
        val root = Files.createTempDirectory("derived-bundle")
        val ordinary = root.resolve("large.bin")
        Files.newOutputStream(ordinary).use { output ->
            val chunk = ByteArray(1024 * 1024) { (it % 251).toByte() }
            repeat(24) { output.write(chunk) }
        }
        val destination = root.resolve("bundle.tar.zst")

        val artifact =
            DerivedBundleWriter()
                .write(
                    listOf(SpoolEntry("world/large.bin", ordinary, false)),
                    "{}\n".encodeToByteArray(),
                    destination,
                )

        val bytes = Files.readAllBytes(destination)
        assertEquals(destination, artifact.path)
        assertEquals(bytes.size.toLong(), artifact.size)
        assertEquals(digest(bytes), artifact.sha256)
    }

    @Test
    fun `removes a partial output when writing the bundle fails`() {
        val root = Files.createTempDirectory("derived-bundle")
        val ordinary = root.resolve("ordinary.bin")
        Files.write(ordinary, ByteArray(4096) { 1 })
        val destination = root.resolve("partial.tar.zst")
        val writer = DerivedBundleWriter { path ->
            object : OutputStream() {
                private val delegate =
                    Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE_NEW)
                private var written = 0

                override fun write(value: Int) = write(byteArrayOf(value.toByte()))

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (written + length > 128) throw IOException("disk full")
                    delegate.write(bytes, offset, length)
                    written += length
                }

                override fun close() = delegate.close()
            }
        }

        val failure =
            assertThrows(IOException::class.java) {
                writer.write(
                    listOf(SpoolEntry("ordinary.bin", ordinary, false)),
                    "{}\n".encodeToByteArray(),
                    destination,
                )
            }

        assertEquals("disk full", failure.message)
        assertFalse(Files.exists(destination))
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
