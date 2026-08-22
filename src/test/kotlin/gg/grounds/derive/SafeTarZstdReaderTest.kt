package gg.grounds.derive

import com.github.luben.zstd.ZstdOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SafeTarZstdReaderTest {
    @Test
    fun `rejects unsafe names and special entries before writing them`() {
        listOf("../escape", "dir\\backslash", "dir//empty", "./dot", "C:/drive").forEach { name ->
            val root = Files.createTempDirectory("safe-tar-test")
            val error =
                assertThrows(ArchiveContentException::class.java) {
                    SafeTarZstdReader(ArchiveLimits(maxPathBytes = 64))
                        .read(ByteArrayInputStream(archive(entry(name))), root)
                }
            assertEquals("ARCHIVE", error.problem.code)
            assertTrue(
                Files.list(root).use { it.noneMatch { path -> path.fileName.toString() != "." } }
            )
        }
        assertThrows(ArchiveContentException::class.java) {
            SafeTarZstdReader()
                .read(
                    ByteArrayInputStream(archiveRawPath("/absolute")),
                    Files.createTempDirectory("safe-tar-test"),
                )
        }
    }

    @Test
    fun `rejects links duplicates non-files scene and trailing payload`() {
        val link = TarArchiveEntry("link", TarArchiveEntry.LF_SYMLINK).apply { linkName = "target" }
        listOf(
                archive(link),
                archive(entry("same"), entry("same")),
                archive(
                    TarArchiveEntry("scene.json", TarArchiveEntry.LF_SYMLINK).apply {
                        linkName = "target"
                    }
                ),
                archive(entry("world"), trailingTarBytes = byteArrayOf(1)),
                archive(entry("world")) + byteArrayOf(1),
            )
            .forEach { bytes ->
                assertThrows(ArchiveContentException::class.java) {
                    SafeTarZstdReader()
                        .read(
                            ByteArrayInputStream(bytes),
                            Files.createTempDirectory("safe-tar-test"),
                        )
                }
            }
    }

    @Test
    fun `enforces injectable entry path file and expanded limits`() {
        assertThrows(ArchiveContentException::class.java) {
            SafeTarZstdReader(ArchiveLimits(maxEntries = 1))
                .read(
                    ByteArrayInputStream(archive(entry("a"), entry("b"))),
                    Files.createTempDirectory("safe-tar-test"),
                )
        }
        assertThrows(ArchiveContentException::class.java) {
            SafeTarZstdReader(ArchiveLimits(maxPathBytes = 1))
                .read(
                    ByteArrayInputStream(archive(entry("ab"))),
                    Files.createTempDirectory("safe-tar-test"),
                )
        }
        assertThrows(ArchiveContentException::class.java) {
            SafeTarZstdReader(ArchiveLimits(maxFileBytes = 1))
                .read(
                    ByteArrayInputStream(archive(entry("a", "12".encodeToByteArray()))),
                    Files.createTempDirectory("safe-tar-test"),
                )
        }
        assertThrows(ArchiveContentException::class.java) {
            SafeTarZstdReader(ArchiveLimits(maxExpandedBytes = 1))
                .read(
                    ByteArrayInputStream(archive(entry("a", "12".encodeToByteArray()))),
                    Files.createTempDirectory("safe-tar-test"),
                )
        }
    }

    private fun entry(name: String, content: ByteArray = "x".encodeToByteArray()) =
        TarArchiveEntry(name).apply {
            size = content.size.toLong()
            userId = 10
            groupId = 10
        }

    private fun directory(name: String) =
        TarArchiveEntry(name, TarArchiveEntry.LF_DIR).apply { size = 0 }

    private fun archive(
        vararg entries: TarArchiveEntry,
        trailingTarBytes: ByteArray = byteArrayOf(),
    ): ByteArray {
        val tar = ByteArrayOutputStream()
        TarArchiveOutputStream(tar).use { output ->
            output.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            entries.forEach { entry ->
                output.putArchiveEntry(entry)
                if (entry.isFile) output.write(ByteArray(entry.size.toInt()) { 'x'.code.toByte() })
                output.closeArchiveEntry()
            }
        }
        return ByteArrayOutputStream()
            .also { compressed ->
                ZstdOutputStream(compressed).use { it.write(tar.toByteArray() + trailingTarBytes) }
            }
            .toByteArray()
    }

    private fun archiveRawPath(path: String): ByteArray {
        val compressed = archive(entry("ordinary"))
        val tar = com.github.luben.zstd.Zstd.decompress(compressed, 64 * 1024)
        path.encodeToByteArray().copyInto(tar, 0)
        tar.fill(0, path.length, 100)
        tar.fill(' '.code.toByte(), 148, 156)
        val checksum = tar.copyOfRange(0, 512).sumOf { it.toInt() and 0xff }
        "%06o\u0000 ".format(checksum).encodeToByteArray().copyInto(tar, 148)
        return com.github.luben.zstd.Zstd.compress(tar)
    }
}
