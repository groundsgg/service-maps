package gg.grounds.lod.format

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A bounded transport of already-compressed tiles. No entry is ever extracted to disk. */
class LodBundle
private constructor(val path: Path, val manifest: LodManifest, val manifestSha256: String) {
    fun readTile(x: Int, z: Int): Map<ColumnPosition, ByteArray> {
        val tile =
            requireNotNull(manifest.tiles.find { it.x == x && it.z == z }) {
                "tile not in manifest"
            }
        val compressed = ZipFile(path.toFile()).use { zip -> read(zip, tile.path, tile.sizeBytes) }
        return LodTiles.read(tile, compressed)
    }

    companion object {
        fun pack(directory: Path, target: Path) {
            val manifestBytes = Files.readAllBytes(directory.resolve("manifest.json"))
            val manifest = LodJson.read(manifestBytes)
            val names = listOf("manifest.json") + manifest.tiles.map { it.path }
            require(
                manifest.sizeBytes +
                    manifestBytes.size +
                    names.sumOf { 100L + 2L * it.length } +
                    22 <= LodBounds.MAX_BUNDLE_BYTES
            )
            require(!Files.exists(target)) { "bundle target already exists" }
            try {
                ZipOutputStream(Files.newOutputStream(target)).use { zip ->
                    names.forEach { name ->
                        val file = directory.resolve(name)
                        val limit =
                            if (name == "manifest.json") LodBounds.MAX_MANIFEST_BYTES.toLong()
                            else LodBounds.MAX_TILE_BYTES
                        require(Files.size(file) <= limit)
                        val bytes = Files.readAllBytes(file)
                        if (name != "manifest.json") {
                            val tile = manifest.tiles.single { it.path == name }
                            require(
                                bytes.size.toLong() == tile.sizeBytes &&
                                    digest(bytes) == tile.sha256
                            )
                        }
                        val entry =
                            ZipEntry(name).apply {
                                method = ZipEntry.STORED
                                size = bytes.size.toLong()
                                compressedSize = size
                                crc = CRC32().also { it.update(bytes) }.value
                                setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
                            }
                        zip.putNextEntry(entry)
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
                require(Files.size(target) <= LodBounds.MAX_BUNDLE_BYTES)
            } catch (failure: Throwable) {
                Files.deleteIfExists(target)
                throw failure
            }
        }

        /**
         * Validates all compressed files before publication; decoding is reserved for the runtime.
         */
        fun open(path: Path, sha256: String): LodBundle {
            require(isDigest(sha256) && Files.size(path) <= LodBounds.MAX_BUNDLE_BYTES)
            require(digest(path) == sha256) { "bundle digest mismatch" }
            preflightDirectory(path)
            try {
                ZipFile(path.toFile()).use { zip ->
                    val entries = zip.entries().asSequence().take(34).toList()
                    require(
                        entries.size <= 33 &&
                            entries.map { it.name }.distinct().size == entries.size
                    )
                    require(entries.all { it.method == ZipEntry.STORED && !it.isDirectory }) {
                        "unexpected archive encoding"
                    }
                    val manifestBytes =
                        read(zip, "manifest.json", LodBounds.MAX_MANIFEST_BYTES.toLong())
                    val manifest = LodJson.read(manifestBytes)
                    require(
                        entries.map { it.name }.toSet() ==
                            (manifest.tiles.map { it.path } + "manifest.json").toSet()
                    ) {
                        "unexpected archive entries"
                    }
                    manifest.tiles.forEach { tile ->
                        val bytes = read(zip, tile.path, tile.sizeBytes)
                        require(
                            bytes.size.toLong() == tile.sizeBytes && digest(bytes) == tile.sha256
                        ) {
                            "tile integrity mismatch"
                        }
                    }
                    return LodBundle(path, manifest, digest(manifestBytes))
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("invalid LOD bundle", e)
            }
        }

        // Bound the central directory BEFORE ZipFile allocates its entry index. Only our
        // comment-free, single-disk, non-ZIP64 transport is admitted.
        private fun preflightDirectory(path: Path) {
            RandomAccessFile(path.toFile(), "r").use { file ->
                require(file.length() >= 22)
                file.seek(file.length() - 22)
                val footer = ByteArray(22)
                file.readFully(footer)
                val bytes = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN)
                require(
                    bytes.int == 0x06054b50 && bytes.short.toInt() == 0 && bytes.short.toInt() == 0
                )
                val diskEntries = bytes.short.toInt() and 0xffff
                val totalEntries = bytes.short.toInt() and 0xffff
                val directoryBytes = bytes.int.toLong() and 0xffffffffL
                val directoryOffset = bytes.int.toLong() and 0xffffffffL
                require(
                    bytes.short.toInt() == 0 && diskEntries == totalEntries && totalEntries in 1..33
                )
                require(
                    directoryBytes in 46..16384 &&
                        directoryOffset + directoryBytes == file.length() - 22
                ) {
                    "invalid archive directory"
                }
            }
        }

        private fun read(zip: ZipFile, name: String, max: Long): ByteArray {
            require(max in 0..LodBounds.MAX_TILE_BYTES)
            val entry = requireNotNull(zip.getEntry(name)) { "missing $name" }
            require(entry.size in 0..max && entry.compressedSize == entry.size) {
                "invalid entry size"
            }
            return zip.getInputStream(entry).use { input ->
                input.readNBytes((max + 1).toInt()).also { require(it.size.toLong() == entry.size) }
            }
        }
    }
}
