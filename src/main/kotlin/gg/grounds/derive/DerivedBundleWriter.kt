package gg.grounds.derive

import com.github.luben.zstd.ZstdOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Date
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

data class DerivedBundleArtifact(val path: Path, val sha256: String, val size: Long)

/** Re-packs spooled files in a stable, metadata-free form without retaining the bundle in heap. */
class DerivedBundleWriter(
    private val outputFactory: (Path) -> OutputStream = {
        Files.newOutputStream(it, CREATE_NEW, WRITE)
    }
) {
    fun write(entries: List<SpoolEntry>, manifest: ByteArray, output: Path): DerivedBundleArtifact {
        var created = false
        try {
            val files = entries.filterNot { it.directory }.associateBy { it.path }.toMutableMap()
            val directories = entries.filter { it.directory }.map { it.path }.toSortedSet()
            require(
                files.put(
                    "grounds/derived-manifest.json",
                    SpoolEntry("grounds/derived-manifest.json", null, false),
                ) == null
            ) {
                "source archive reserves grounds/derived-manifest.json"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val counting = CountingOutputStream(outputFactory(output).also { created = true })
            DigestOutputStream(counting, digest).use { raw ->
                ZstdOutputStream(raw, ZSTD_LEVEL).use { zstd ->
                    zstd.setChecksum(false)
                    TarArchiveOutputStream(zstd).use { tar ->
                        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                        (directories + files.keys).sorted().forEach { name ->
                            if (name in directories) {
                                tar.putArchiveEntry(directoryEntry(name))
                                tar.closeArchiveEntry()
                            } else {
                                val entry = files.getValue(name)
                                val size =
                                    if (name == "grounds/derived-manifest.json")
                                        manifest.size.toLong()
                                    else Files.size(entry.file!!)
                                tar.putArchiveEntry(fileEntry(name, size))
                                if (name == "grounds/derived-manifest.json") tar.write(manifest)
                                else
                                    Files.newInputStream(entry.file!!).use {
                                        it.copyTo(tar, BUFFER_SIZE)
                                    }
                                tar.closeArchiveEntry()
                            }
                        }
                    }
                }
            }
            return DerivedBundleArtifact(output, digest.digest().hex(), counting.count)
        } catch (failure: Throwable) {
            if (created) {
                try {
                    Files.deleteIfExists(output)
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }

    private fun directoryEntry(name: String) =
        TarArchiveEntry("$name/").apply {
            userId = 0
            groupId = 0
            mode = DIRECTORY_MODE
            modTime = Date(0)
        }

    private fun fileEntry(name: String, size: Long) =
        TarArchiveEntry(name).apply {
            this.size = size
            userId = 0
            groupId = 0
            mode = FILE_MODE
            modTime = Date(0)
        }

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(value: Int) {
            delegate.write(value)
            count++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            delegate.write(bytes, offset, length)
            count += length
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val ZSTD_LEVEL = 3
        const val BUFFER_SIZE = 64 * 1024
        const val DIRECTORY_MODE = 0b111_101_101
        const val FILE_MODE = 0b110_100_100
    }
}
