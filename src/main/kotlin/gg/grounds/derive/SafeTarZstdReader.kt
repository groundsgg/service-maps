package gg.grounds.derive

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdInputStream
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveProblem
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileChannel.MapMode.READ_ONLY
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

data class SpoolEntry(val path: String, val file: Path?, val directory: Boolean)

class ArchiveContentException(message: String, path: String? = null) : IOException(message) {
    val problem = DeriveProblem(DeriveFailureScope.CONTENT, path, "ARCHIVE", null, message)
}

/** Reads physical tar records itself; Commons Compress is deliberately not on this input path. */
class SafeTarZstdReader(private val limits: ArchiveLimits = ArchiveLimits()) {
    fun read(source: InputStream, workerDirectory: Path): List<SpoolEntry> {
        require(Files.isDirectory(workerDirectory)) { "worker directory must exist" }
        Files.list(workerDirectory).use {
            require(!it.findAny().isPresent) { "worker directory must be empty" }
        }
        val compressed = Files.createTempFile(workerDirectory, ".source-", ".tar.zst")
        spoolCompressed(source, compressed)
        validateSingleFrame(compressed)
        return parse(compressed, workerDirectory)
    }

    private fun spoolCompressed(source: InputStream, target: Path) {
        Files.newOutputStream(target, WRITE).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                if (total > limits.maxCompressedBytes - read)
                    fail("compressed archive exceeds size limit")
                output.write(buffer, 0, read)
                total += read
            }
        }
    }

    private fun validateSingleFrame(path: Path) {
        FileChannel.open(path).use { channel ->
            val size = channel.size()
            if (size == 0L) fail("archive must contain exactly one complete zstd frame")
            val frameSize = Zstd.findFrameCompressedSize(channel.map(READ_ONLY, 0, size))
            if (Zstd.isError(frameSize) || frameSize != size)
                fail("archive must contain exactly one complete zstd frame")
        }
    }

    private fun parse(compressed: Path, root: Path): List<SpoolEntry> {
        Files.newInputStream(compressed).use { file ->
            ZstdInputStream(file).use { decoded ->
                decoded.setContinuous(false)
                try {
                    return PhysicalTar(decoded, root, limits).read()
                } catch (failure: ArchiveContentException) {
                    throw failure
                } catch (failure: IOException) {
                    throw ArchiveContentException("invalid compressed archive: ${failure.message}")
                }
            }
        }
    }

    private fun fail(message: String): Nothing = throw ArchiveContentException(message)
}

private class PhysicalTar(
    private val input: InputStream,
    private val root: Path,
    private val limits: ArchiveLimits,
) {
    private val paths = mutableSetOf<String>()
    private val entries = mutableListOf<SpoolEntry>()
    private var expanded = 0L
    private var physicalEntries = 0
    private var pendingPaxPath: String? = null
    private var pendingLongPath: String? = null

    fun read(): List<SpoolEntry> {
        while (true) {
            val header = record() ?: fail("tar ends before terminator")
            if (header.all { it == 0.toByte() }) {
                val second = record() ?: fail("tar ends after one terminator record")
                if (!second.all { it == 0.toByte() }) fail("tar terminator is malformed")
                if (input.read() != -1) fail("archive contains trailing tar data")
                return entries
            }
            checksum(header)
            if (++physicalEntries > limits.maxEntries) fail("archive has too many entries")
            val size = octal(header, 124, 12, "entry size")
            countExpanded(size)
            when (header[156].toInt().toChar()) {
                '\u0000',
                '0' -> ordinary(header, size)
                '5' -> directory(header, size)
                'x' -> pax(size)
                'L' -> longName(size)
                '1',
                '2',
                '3',
                '4',
                '6',
                'S',
                'K',
                'g' -> {
                    discard(size)
                    fail("archive contains unsupported entry type")
                }
                else -> {
                    discard(size)
                    fail("archive contains unknown entry type")
                }
            }
        }
    }

    private fun ordinary(header: ByteArray, size: Long) {
        val name = path(header)
        if (size > limits.maxFileBytes) fail("archive file exceeds size limit", name)
        val target = target(name)
        target.parent?.let(Files::createDirectories)
        Files.newOutputStream(target, CREATE_NEW, WRITE).use { copyExactly(it, size, name) }
        padding(size)
        entries += SpoolEntry(name, target, false)
    }

    private fun directory(header: ByteArray, size: Long) {
        val name = path(header)
        discard(size)
        padding(size)
        Files.createDirectories(target(name))
        entries += SpoolEntry(name, null, true)
    }

    private fun pax(size: Long) {
        if (pendingPaxPath != null || pendingLongPath != null || size > PAX_LIMIT)
            fail("PAX metadata exceeds size limit")
        val bytes = bytes(size)
        padding(size)
        var offset = 0
        var path: String? = null
        while (offset < bytes.size) {
            val space =
                (offset until bytes.size).firstOrNull { bytes[it] == ' '.code.toByte() } ?: -1
            if (space <= offset) fail("PAX metadata record has malformed length")
            val count =
                bytes.copyOfRange(offset, space).toString(StandardCharsets.US_ASCII).toIntOrNull()
                    ?: fail("PAX metadata record has malformed length")
            if (
                count <= space - offset ||
                    offset + count > bytes.size ||
                    bytes[offset + count - 1] != '\n'.code.toByte()
            )
                fail("PAX metadata record has malformed length")
            val pair =
                strictString(bytes.copyOfRange(space + 1, offset + count - 1), "PAX metadata")
            val equals = pair.indexOf('=')
            if (equals <= 0) fail("PAX metadata record is malformed")
            val key = pair.substring(0, equals)
            val value = pair.substring(equals + 1)
            when (key) {
                "path" -> {
                    if (path != null) fail("PAX metadata has duplicate path")
                    path = normalize(value)
                }
                "linkpath",
                "size" -> fail("PAX metadata overrides are unsupported")
                "atime",
                "ctime",
                "mtime",
                "uid",
                "gid",
                "uname",
                "gname",
                "comment",
                "charset" -> Unit
                else ->
                    if (
                        key.contains("path", true) ||
                            key.contains("link", true) ||
                            key.contains("size", true)
                    )
                        fail("PAX metadata contains unsupported security-relevant field")
            }
            offset += count
        }
        pendingPaxPath = path
    }

    private fun longName(size: Long) {
        if (
            pendingPaxPath != null ||
                pendingLongPath != null ||
                size > limits.maxPathBytes.toLong() + 1
        )
            fail("GNU long name exceeds size limit")
        val bytes = bytes(size)
        padding(size)
        if (
            bytes.isEmpty() ||
                (bytes.last() != 0.toByte() && bytes.last() != '\n'.code.toByte()) ||
                bytes.dropLast(1).any { it == 0.toByte() || it == '\n'.code.toByte() }
        )
            fail("GNU long name is malformed")
        pendingLongPath = normalize(strictString(bytes.dropLast(1).toByteArray(), "GNU long name"))
    }

    private fun path(header: ByteArray): String {
        val raw =
            pendingPaxPath
                ?: pendingLongPath
                ?: run {
                    val name = nulString(header.copyOfRange(0, 100), "tar path")
                    val prefix = nulString(header.copyOfRange(345, 500), "tar path")
                    if (prefix.isEmpty()) name else "$prefix/$name"
                }
        pendingPaxPath = null
        pendingLongPath = null
        val normalized = normalize(raw)
        if (!paths.add(normalized)) fail("archive contains duplicate path", normalized)
        return normalized
    }

    private fun normalize(raw: String): String {
        if (raw.isEmpty() || raw.indexOf('\u0000') >= 0 || raw.contains('\\'))
            fail("archive path is not portable")
        if (raw.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(raw))
            fail("archive path is absolute")
        if (raw.split('/').any { it.isEmpty() || it == "." || it == ".." })
            fail("archive path is unsafe")
        if (raw.toByteArray(StandardCharsets.UTF_8).size > limits.maxPathBytes)
            fail("archive path exceeds size limit")
        return raw
    }

    private fun target(name: String): Path {
        val target = root.resolve(name).normalize()
        if (!target.startsWith(root)) fail("archive path escapes worker directory", name)
        return target
    }

    private fun bytes(size: Long): ByteArray {
        if (size > Int.MAX_VALUE) fail("archive payload is too large")
        return ByteArray(size.toInt()).also(::readFully)
    }

    private fun discard(size: Long) = copyExactly(null, size, null)

    private fun copyExactly(output: OutputStream?, size: Long, path: String?) {
        var remaining = size
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) fail("archive entry ends before its declared size", path)
            output?.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun padding(size: Long) {
        val count = ((512 - size % 512) % 512).toInt()
        if (count == 0) return
        val bytes = ByteArray(count)
        readFully(bytes)
        if (bytes.any { it != 0.toByte() }) fail("archive entry padding is non-zero")
    }

    private fun record(): ByteArray? {
        val bytes = ByteArray(512)
        var offset = 0
        while (offset < 512) {
            val read = input.read(bytes, offset, 512 - offset)
            if (read < 0) {
                if (offset == 0) return null
                fail("tar record is truncated")
            }
            offset += read
        }
        return bytes
    }

    private fun readFully(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read < 0) fail("archive entry ends before its declared size")
            offset += read
        }
    }

    private fun checksum(header: ByteArray) {
        val stored = octal(header, 148, 8, "tar checksum")
        val actual =
            header.indices
                .sumOf { if (it in 148..155) 32 else header[it].toInt() and 0xff }
                .toLong()
        if (stored != actual) fail("tar header checksum is invalid")
    }

    private fun octal(header: ByteArray, start: Int, length: Int, name: String): Long {
        var value = 0L
        var seen = false
        var ended = false
        for (byte in header.copyOfRange(start, start + length)) {
            val char = byte.toInt() and 0xff
            if (char == 0 || char == 32) {
                ended = true
                continue
            }
            if (ended || char !in 48..55 || value > (Long.MAX_VALUE - (char - 48)) / 8)
                fail("$name is malformed")
            value = value * 8 + char - 48
            seen = true
        }
        return if (seen) value else 0
    }

    private fun countExpanded(size: Long) {
        if (expanded > limits.maxExpandedBytes - size) fail("archive exceeds expanded size limit")
        expanded += size
    }

    private fun nulString(bytes: ByteArray, name: String) =
        strictString(bytes.takeWhile { it != 0.toByte() }.toByteArray(), name)

    private fun strictString(bytes: ByteArray, name: String): String =
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            fail("$name is not valid UTF-8")
        }

    private fun fail(message: String, path: String? = null): Nothing =
        throw ArchiveContentException(message, path)

    companion object {
        const val PAX_LIMIT = 64 * 1024L
    }
}
