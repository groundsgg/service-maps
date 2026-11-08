package gg.grounds.lod.worker

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import com.github.luben.zstd.ZstdOutputStream
import org.junit.jupiter.api.io.TempDir

class SourceArchiveTest {
    @TempDir lateinit var temp: Path

    private fun archive(vararg entries: Pair<TarArchiveEntry, ByteArray>): Path {
        val file = temp.resolve("source.tar.zst")
        TarArchiveOutputStream(ZstdOutputStream(Files.newOutputStream(file))).use { tar ->
            entries.forEach { (entry, bytes) ->
                entry.size = bytes.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return file
    }

    @Test
    fun `a verified archive unpacks within its declared byte budget`() {
        val file = archive(TarArchiveEntry("map.json") to "{}".toByteArray())
        val output = temp.resolve("world")
        SourceArchive.unpack(file, sha256(file), output)
        assertEquals("{}", Files.readString(output.resolve("map.json")))
    }

    @Test
    fun `a wrong bundle digest creates no world`() {
        val file = archive(TarArchiveEntry("map.json") to "{}".toByteArray())
        val output = temp.resolve("world")
        assertFailsWith<IllegalArgumentException> { SourceArchive.unpack(file, "0".repeat(64), output) }
        assertFalse(Files.exists(output))
    }

    @Test
    fun `path traversal is refused and partial extraction removed`() {
        val file = archive(TarArchiveEntry("../escape") to byteArrayOf(1))
        val output = temp.resolve("world")
        assertFailsWith<IllegalArgumentException> { SourceArchive.unpack(file, sha256(file), output) }
        assertFalse(Files.exists(output))
        assertFalse(Files.exists(temp.resolve("escape")))
    }

    @Test
    fun `links cannot redirect extracted files`() {
        val entry = TarArchiveEntry("link", TarConstants.LF_SYMLINK).also { it.linkName = "../escape" }
        val file = archive(entry to byteArrayOf())
        val output = temp.resolve("world")
        assertFailsWith<IllegalArgumentException> { SourceArchive.unpack(file, sha256(file), output) }
        assertFalse(Files.exists(output))
    }

    @Test
    fun `duplicate normalized paths cannot overwrite the input`() {
        val file = archive(
            TarArchiveEntry("map.json") to "{}".toByteArray(),
            TarArchiveEntry("./map.json") to "bad".toByteArray(),
        )
        val output = temp.resolve("world")
        assertFailsWith<IllegalArgumentException> { SourceArchive.unpack(file, sha256(file), output) }
        assertFalse(Files.exists(output))
    }

    @Test
    fun `expanded size is checked before copying beyond its limit`() {
        val file = archive(TarArchiveEntry("large") to ByteArray(4096))
        val output = temp.resolve("world")
        assertFailsWith<IllegalArgumentException> {
            SourceArchive.unpack(file, sha256(file), output, maxExpandedBytes = 1024)
        }
        assertFalse(Files.exists(output))
    }

    @Test
    fun `GNU metadata is rejected before it can bypass returned entry limits`() {
        val entry = TarArchiveEntry("././@LongLink", TarConstants.LF_GNUTYPE_LONGNAME)
        val file = archive(entry to "map.json\u0000".toByteArray(), TarArchiveEntry("ignored") to "{}".toByteArray())
        val output = temp.resolve("world")
        assertFailsWith<IllegalArgumentException> { SourceArchive.unpack(file, sha256(file), output) }
        assertFalse(Files.exists(output))
    }

    @Test
    fun `PAX metadata requires explicit bounded support`() {
        val entry = TarArchiveEntry("pax", TarConstants.LF_PAX_EXTENDED_HEADER_LC)
        val file = archive(entry to "12 path=foo\n".toByteArray(), TarArchiveEntry("ignored") to "{}".toByteArray())
        val output = temp.resolve("world")
        assertFailsWith<IllegalArgumentException> { SourceArchive.unpack(file, sha256(file), output) }
        assertFalse(Files.exists(output))
    }

    @Test
    fun `decompressed archive headers consume the byte budget too`() {
        val file = archive(TarArchiveEntry("one/") to byteArrayOf(), TarArchiveEntry("two/") to byteArrayOf())
        val output = temp.resolve("world")
        assertFailsWith<IllegalArgumentException> {
            SourceArchive.unpack(file, sha256(file), output, maxExpandedBytes = 512)
        }
        assertFalse(Files.exists(output))
    }
}
