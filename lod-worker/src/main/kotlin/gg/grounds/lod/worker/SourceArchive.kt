package gg.grounds.lod.worker

import com.github.luben.zstd.ZstdInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.io.InputStream
import java.io.FilterInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/** Verifies the exact input bytes before extracting any files into a new private directory. */
object SourceArchive {
    fun unpack(
        bundle: Path,
        expectedSha256: String,
        destination: Path,
        maxExpandedBytes: Long = 512L * 1024 * 1024,
    ) {
        require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "invalid source digest" }
        require(Files.size(bundle) <= 64L * 1024 * 1024) { "source bundle exceeds 64 MiB" }
        require(sha256(bundle) == expectedSha256) { "source bundle digest mismatch" }
        require(maxExpandedBytes in 1..512L * 1024 * 1024)
        require(!Files.exists(destination)) { "source extraction directory already exists" }
        Files.createDirectory(destination)
        try {
            val root = destination.toAbsolutePath().normalize()
            val paths = HashSet<Path>()
            var entries = 0
            var expanded = 0L
            StrictTar(BoundedInput(ZstdInputStream(Files.newInputStream(bundle)), maxExpandedBytes)).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    require(++entries <= 4096) { "too many source archive entries" }
                    require(!entry.isSymbolicLink && !entry.isLink && (entry.isDirectory || entry.isFile)) { "unsupported archive entry" }
                    require(!entry.name.contains('\\')) { "noncanonical source path" }
                    val target = root.resolve(entry.name).normalize()
                    require(target.startsWith(root) && (target != root || entry.isDirectory)) { "archive path escapes source" }
                    require(paths.add(target)) { "duplicate source archive path" }
                    if (entry.isDirectory) {
                        Files.createDirectories(target)
                        continue
                    }
                    require(entry.size >= 0 && entry.size <= 128L * 1024 * 1024 &&
                        expanded + entry.size <= maxExpandedBytes) { "source archive exceeds byte budget" }
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target).use { file ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = tar.read(buffer)
                            if (read < 0) break
                            require(expanded + read <= maxExpandedBytes) { "source archive exceeds byte budget" }
                            file.write(buffer, 0, read)
                            expanded += read
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            destination.toFile().deleteRecursively()
            throw failure
        }
    }
}

/** Count decompressed headers and metadata before the archive library can allocate from them. */
private class BoundedInput(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L
    override fun read(): Int {
        val value = super.read()
        if (value >= 0) require(++count <= limit) { "decompressed archive exceeds limit" }
        return value
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        val read = `in`.read(bytes, offset, length)
        if (read > 0) {
            count += read
            require(count <= limit) { "decompressed archive exceeds limit" }
        }
        return read
    }
    override fun skip(amount: Long): Long {
        var skipped = 0L
        val buffer = ByteArray(8192)
        while (skipped < amount) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), amount - skipped).toInt())
            if (read < 0) break
            skipped += read
        }
        return skipped
    }
}

/** Pilot input is ordinary USTAR. Reject extension headers before nextEntry processes them. */
private class StrictTar(input: InputStream) : TarArchiveInputStream(input) {
    private var headers = 0
    override fun readRecord(): ByteArray? {
        val record = super.readRecord() ?: return null
        require(++headers <= 4098) { "too many archive headers" }
        val type = record[156].toInt()
        require(type == 0 || type == '0'.code || type == '5'.code) { "archive metadata or entry type requires explicit support" }
        return record
    }
}
