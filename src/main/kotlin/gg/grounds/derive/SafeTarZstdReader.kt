package gg.grounds.derive

import com.github.luben.zstd.ZstdInputStream
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveProblem
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

data class SpoolEntry(val path: String, val file: Path?, val directory: Boolean)

class ArchiveContentException(message: String, path: String? = null) : IOException(message) {
    val problem = DeriveProblem(DeriveFailureScope.CONTENT, path, "ARCHIVE", null, message)
}

/** Streams an untrusted tar.zst into a caller-owned, freshly-created worker directory. */
class SafeTarZstdReader(private val limits: ArchiveLimits = ArchiveLimits()) {
    fun read(source: InputStream, workerDirectory: Path): List<SpoolEntry> {
        require(Files.isDirectory(workerDirectory)) { "worker directory must exist" }
        Files.list(workerDirectory).use {
            require(!it.findAny().isPresent) { "worker directory must be empty" }
        }
        val compressed = LimitedInputStream(source, limits.maxCompressedBytes, "compressed archive")
        try {
            ZstdInputStream(compressed).use { zstd ->
                zstd.setContinuous(false)
                TarArchiveInputStream(TarHeaderValidatingInputStream(zstd), 512, 512).use { tar ->
                    val entries = mutableListOf<SpoolEntry>()
                    val paths = mutableSetOf<String>()
                    var expanded = 0L
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        if (entries.size >= limits.maxEntries) fail("archive has too many entries")
                        val name = normalize(entry.name)
                        if (!paths.add(name)) fail("archive contains duplicate path", name)
                        if (entry.isDirectory) {
                            createDirectory(workerDirectory, name)
                            entries += SpoolEntry(name, null, true)
                            continue
                        }
                        if (
                            entry.isSymbolicLink ||
                                entry.isLink ||
                                entry.isCharacterDevice ||
                                entry.isBlockDevice ||
                                entry.isFIFO ||
                                entry.isSparse ||
                                !entry.isFile
                        )
                            fail("archive contains unsupported entry type", name)
                        val declared = entry.size
                        if (declared < 0 || declared > limits.maxFileBytes)
                            fail("archive file exceeds size limit", name)
                        if (expanded > limits.maxExpandedBytes - declared)
                            fail("archive exceeds expanded size limit")
                        val target = target(workerDirectory, name)
                        target.parent?.let { Files.createDirectories(it) }
                        Files.newOutputStream(target, CREATE_NEW, WRITE).use { output ->
                            copyExact(tar, output, declared, name)
                        }
                        expanded += declared
                        entries += SpoolEntry(name, target, false)
                    }
                    // A valid tar stream ends at its two zero records. Any subsequent decoded byte
                    // is either tar junk or a second zstd frame and is never accepted.
                    if (zstd.read() != -1) fail("archive contains trailing data")
                    if (compressed.read() != -1) fail("archive contains trailing compressed data")
                    return entries
                }
            }
        } catch (failure: ArchiveContentException) {
            throw failure
        } catch (failure: IOException) {
            throw ArchiveContentException("invalid compressed archive: ${failure.message}")
        }
    }

    private fun normalize(raw: String): String {
        if (raw.isEmpty() || raw.indexOf('\u0000') >= 0 || raw.contains('\\'))
            fail("archive path is not portable")
        if (raw.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(raw))
            fail("archive path is absolute")
        val segments = raw.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) fail("archive path is unsafe")
        if (raw.toByteArray(Charsets.UTF_8).size > limits.maxPathBytes)
            fail("archive path exceeds size limit")
        return raw
    }

    private fun createDirectory(root: Path, name: String) {
        val directory = target(root, name)
        Files.createDirectories(directory)
    }

    private fun target(root: Path, name: String): Path {
        val target = root.resolve(name).normalize()
        if (!target.startsWith(root)) fail("archive path escapes worker directory", name)
        return target
    }

    private fun copyExact(
        input: InputStream,
        output: java.io.OutputStream,
        size: Long,
        path: String,
    ) {
        var remaining = size
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) fail("archive entry ends before its declared size", path)
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun fail(message: String, path: String? = null): Nothing =
        throw ArchiveContentException(message, path)
}

private class LimitedInputStream(
    input: InputStream,
    private val limit: Long,
    private val name: String,
) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) count(1)
        return value
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(bytes, offset, length)
        if (read > 0) count(read.toLong())
        return read
    }

    private fun count(increment: Long) {
        if (count > limit - increment) throw ArchiveContentException("$name exceeds size limit")
        count += increment
    }
}

/** Examines raw tar headers before commons-compress applies its unsafe-name normalization. */
private class TarHeaderValidatingInputStream(private val input: InputStream) : InputStream() {
    private var positionInRecord = 0
    private var remainingEntryBytes = 0L
    private var header = ByteArray(512)

    override fun read(): Int {
        val bytes = ByteArray(1)
        return if (read(bytes) == -1) -1 else bytes[0].toInt() and 0xff
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var copied = 0
        while (copied < length) {
            val wanted = minOf(length - copied, 512 - positionInRecord)
            val read = input.read(bytes, offset + copied, wanted)
            if (read < 0) return if (copied == 0) -1 else copied
            var index = 0
            while (index < read) {
                bytes[offset + copied + index].also { value ->
                    if (remainingEntryBytes == 0L) header[positionInRecord] = value
                }
                positionInRecord++
                index++
                if (positionInRecord == 512) {
                    if (remainingEntryBytes == 0L) {
                        validateRawName(header)
                        remainingEntryBytes = tarSize(header)
                    } else {
                        remainingEntryBytes = (remainingEntryBytes - 512).coerceAtLeast(0)
                    }
                    positionInRecord = 0
                }
            }
            copied += read
        }
        return copied
    }

    private fun validateRawName(header: ByteArray) {
        if (header.all { it == 0.toByte() }) return
        val name =
            header
                .copyOfRange(0, 100)
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .toString(Charsets.UTF_8)
        val prefix =
            header
                .copyOfRange(345, 500)
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .toString(Charsets.UTF_8)
        val path = listOf(prefix, name).filter(String::isNotEmpty).joinToString("/")
        if (
            path.startsWith('/') || path.contains('\\') || Regex("^[A-Za-z]:").containsMatchIn(path)
        ) {
            throw ArchiveContentException("archive path is not portable")
        }
    }

    private fun tarSize(header: ByteArray): Long {
        val raw =
            header.copyOfRange(124, 136).takeWhile { it != 0.toByte() && it != ' '.code.toByte() }
        return raw.fold(0L) { size, byte -> size * 8 + (byte.toInt() and 0xff) - '0'.code }
            .coerceAtLeast(0)
    }
}
