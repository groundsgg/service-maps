package gg.grounds.derive

import com.github.luben.zstd.ZstdOutputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Date
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

/** Re-packs spooled files in a stable, metadata-free form. */
class DerivedBundleWriter {
    fun write(entries: List<SpoolEntry>, manifest: ByteArray): ByteArray {
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
        val tarBytes = ByteArrayOutputStream()
        TarArchiveOutputStream(tarBytes).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            (directories + files.keys).sorted().forEach { name ->
                if (name in directories) {
                    tar.putArchiveEntry(
                        TarArchiveEntry("$name/").apply {
                            userId = 0
                            groupId = 0
                            mode = 0b111_101_101
                            modTime = Date(0)
                        }
                    )
                    tar.closeArchiveEntry()
                    return@forEach
                }
                val entry = files.getValue(name)
                val bytes =
                    if (name == "grounds/derived-manifest.json") manifest
                    else Files.readAllBytes(entry.file!!)
                tar.putArchiveEntry(
                    TarArchiveEntry(name).apply {
                        size = bytes.size.toLong()
                        userId = 0
                        groupId = 0
                        mode = 0b110_100_100
                        modTime = Date(0)
                    }
                )
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return ByteArrayOutputStream()
            .also { output ->
                ZstdOutputStream(output, 3).use { zstd ->
                    zstd.setChecksum(false)
                    zstd.write(tarBytes.toByteArray())
                }
            }
            .toByteArray()
    }
}
