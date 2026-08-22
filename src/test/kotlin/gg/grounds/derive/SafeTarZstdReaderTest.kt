package gg.grounds.derive

import com.github.luben.zstd.ZstdOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SafeTarZstdReaderTest {
    @Test
    fun `propagates source io failures rather than misclassifying them as archive content`() {
        val source =
            object : ByteArrayInputStream(ByteArray(0)) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    throw IOException("source unavailable")
            }

        assertThrows(IOException::class.java) {
                SafeTarZstdReader().read(source, Files.createTempDirectory("safe-tar-test"))
            }
            .also { error -> assertTrue(error !is ArchiveContentException) }
    }

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
                Files.list(root).use {
                    it.noneMatch { path -> !path.fileName.toString().startsWith(".source-") }
                }
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
            SafeTarZstdReader(ArchiveLimits(maxFileBytes = 1, maxSceneBytes = 1))
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

    @Test
    fun `rejects all unsafe physical entry kinds and malformed physical structure`() {
        listOf('1', '2', '3', '4', '6', 'S', 'K', 'g', '9').forEach { type ->
            assertThrows(ArchiveContentException::class.java) {
                SafeTarZstdReader()
                    .read(
                        ByteArrayInputStream(rawArchive(rawHeader("unsafe", type))),
                        Files.createTempDirectory("safe-tar-test"),
                    )
            }
        }
        listOf(
                rawArchive(rawHeader("file", '0'), terminators = 1),
                rawArchive(rawHeader("file", '0'), terminators = 3),
                rawArchive(rawHeader("file", '0'), trailing = rawHeader("later", '0')),
                rawArchive(rawHeader("file", '0', byteArrayOf(0)), padding = byteArrayOf(1)),
            )
            .forEach { archive ->
                assertThrows(ArchiveContentException::class.java) {
                    SafeTarZstdReader()
                        .read(
                            ByteArrayInputStream(archive),
                            Files.createTempDirectory("safe-tar-test"),
                        )
                }
            }
    }

    @Test
    fun `rejects appended zstd frames and physical extension limit bypasses`() {
        val normal = rawArchive(rawHeader("file", '0'))
        val appended = normal + ZstdOutputStream(ByteArrayOutputStream()).let { byteArrayOf() }
        assertThrows(ArchiveContentException::class.java) {
            SafeTarZstdReader(ArchiveLimits(maxCompressedBytes = 1))
                .read(ByteArrayInputStream(normal), Files.createTempDirectory("safe-tar-test"))
        }
        assertThrows(ArchiveContentException::class.java) {
            SafeTarZstdReader(ArchiveLimits(maxEntries = 1))
                .read(
                    ByteArrayInputStream(
                        rawArchive(
                            rawHeader("pax", 'x', "11 path=a\\n".encodeToByteArray()),
                            rawHeader("a", '0'),
                        )
                    ),
                    Files.createTempDirectory("safe-tar-test"),
                )
        }
        // A separately-compressed empty frame is bytes after the authoritative single frame.
        val emptyFrame =
            ByteArrayOutputStream().also { out -> ZstdOutputStream(out).use {} }.toByteArray()
        assertThrows(ArchiveContentException::class.java) {
            SafeTarZstdReader()
                .read(
                    ByteArrayInputStream(normal + emptyFrame),
                    Files.createTempDirectory("safe-tar-test"),
                )
        }
        assertTrue(appended.isNotEmpty())
    }

    @Test
    fun `rejects unsafe paths from real PAX and GNU extension payloads`() {
        listOf("/absolute", "../traversal", "dir\\backslash", "x".repeat(65)).forEach { path ->
            listOf('x', 'L').forEach { type ->
                val payload =
                    if (type == 'x') paxRecord("path", path) else "$path\u0000".encodeToByteArray()
                assertThrows(ArchiveContentException::class.java) {
                    SafeTarZstdReader(ArchiveLimits(maxPathBytes = 64))
                        .read(
                            ByteArrayInputStream(
                                rawArchiveEntries(
                                    rawHeader("extension", type, payload) to payload,
                                    rawHeader("ignored", '0') to byteArrayOf(),
                                )
                            ),
                            Files.createTempDirectory("safe-tar-test"),
                        )
                }
            }
        }
    }

    @Test
    fun `strictly parses PAX records and consumes pathless metadata once`() {
        listOf(
                "999999999999999999999 x=y\n".encodeToByteArray(),
                paxRecord("size", "1"),
                paxRecord("linkpath", "target"),
            )
            .forEach { payload ->
                assertThrows(ArchiveContentException::class.java) {
                    SafeTarZstdReader()
                        .read(
                            ByteArrayInputStream(
                                rawArchiveEntries(
                                    rawHeader("pax", 'x', payload) to payload,
                                    rawHeader("file", '0') to byteArrayOf(),
                                )
                            ),
                            Files.createTempDirectory("safe-tar-test"),
                        )
                }
            }
        val metadata = paxRecord("mtime", "0")
        assertThrows(ArchiveContentException::class.java) {
            SafeTarZstdReader()
                .read(
                    ByteArrayInputStream(
                        rawArchiveEntries(
                            rawHeader("pax", 'x', metadata) to metadata,
                            rawHeader("another", 'x', metadata) to metadata,
                        )
                    ),
                    Files.createTempDirectory("safe-tar-test"),
                )
        }
    }

    @Test
    fun `counts PAX payload against expanded limit and propagates spool io`() {
        val metadata = paxRecord("mtime", "0")
        assertThrows(ArchiveContentException::class.java) {
            SafeTarZstdReader(ArchiveLimits(maxExpandedBytes = 1))
                .read(
                    ByteArrayInputStream(
                        rawArchiveEntries(rawHeader("pax", 'x', metadata) to metadata)
                    ),
                    Files.createTempDirectory("safe-tar-test"),
                )
        }
        assertThrows(IOException::class.java) {
                SafeTarZstdReader(outputFactory = { throw IOException("disk unavailable") })
                    .read(
                        ByteArrayInputStream(archive(entry("file"))),
                        Files.createTempDirectory("safe-tar-test"),
                    )
            }
            .also { assertTrue(it !is ArchiveContentException) }
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

    private fun rawArchive(
        vararg headers: ByteArray,
        terminators: Int = 2,
        trailing: ByteArray = byteArrayOf(),
        padding: ByteArray? = null,
    ): ByteArray {
        val tar = ByteArrayOutputStream()
        headers.forEach { header ->
            tar.write(header)
            val size =
                header.copyOfRange(124, 136).toString(Charsets.US_ASCII).trim().toLongOrNull(8) ?: 0
            if (size > 0) tar.write(ByteArray(size.toInt()))
            val pad = ByteArray(((512 - size % 512) % 512).toInt())
            if (padding != null && pad.isNotEmpty()) padding.copyInto(pad)
            tar.write(pad)
        }
        repeat(terminators) { tar.write(ByteArray(512)) }
        tar.write(trailing)
        return ByteArrayOutputStream()
            .also { out -> ZstdOutputStream(out).use { it.write(tar.toByteArray()) } }
            .toByteArray()
    }

    private fun rawHeader(name: String, type: Char, content: ByteArray = byteArrayOf()): ByteArray {
        val header = ByteArray(512)
        name.encodeToByteArray().copyInto(header)
        "%011o".format(content.size).encodeToByteArray().copyInto(header, 124)
        header[135] = 0
        header[156] = type.code.toByte()
        header.fill(' '.code.toByte(), 148, 156)
        val checksum = header.sumOf { it.toInt() and 0xff }
        "%06o\u0000 ".format(checksum).encodeToByteArray().copyInto(header, 148)
        return header
    }

    private fun rawArchiveEntries(vararg entries: Pair<ByteArray, ByteArray>): ByteArray {
        val tar = ByteArrayOutputStream()
        entries.forEach { (header, content) ->
            tar.write(header)
            tar.write(content)
            tar.write(ByteArray(((512 - content.size % 512) % 512)))
        }
        tar.write(ByteArray(1024))
        return ByteArrayOutputStream()
            .also { out -> ZstdOutputStream(out).use { it.write(tar.toByteArray()) } }
            .toByteArray()
    }

    private fun paxRecord(key: String, value: String): ByteArray {
        var length = 0
        while (true) {
            val candidate = "$length $key=$value\n"
            if (candidate.encodeToByteArray().size == length) return candidate.encodeToByteArray()
            length = candidate.encodeToByteArray().size
        }
    }
}
