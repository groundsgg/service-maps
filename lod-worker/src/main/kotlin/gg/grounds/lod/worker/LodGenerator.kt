package gg.grounds.lod.worker

import com.github.luben.zstd.ZstdOutputStream
import dev.vox.lss.common.wire.IdentityCodec
import java.io.DataOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import net.minestom.server.MinecraftServer
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.anvil.AnvilLoader
import net.minestom.server.instance.block.Block
import net.minestom.server.world.DimensionType

data class LodLimits(
    val maxChunks: Int = 4096,
    val maxTiles: Int = 32,
    val maxOutputBytes: Long = 128L * 1024 * 1024,
    val maxTileBytes: Long = 16L * 1024 * 1024,
    val maxDecodedTileBytes: Long = 64L * 1024 * 1024,
    val maxSeconds: Long = 900,
) {
    init {
        require(maxChunks in 1..4096 && maxTiles in 1..32)
        require(maxOutputBytes in 1..128L * 1024 * 1024)
        require(maxTileBytes in 1..16L * 1024 * 1024)
        require(maxDecodedTileBytes in 1..64L * 1024 * 1024 && maxSeconds in 1..900)
    }
}

typealias LodTile = gg.grounds.lod.format.LodTile

typealias LodManifest = gg.grounds.lod.format.LodManifest

/** Local-only producer. Neither uploads files nor creates chunks, servers or connections. */
class LodGenerator(private val limits: LodLimits = LodLimits()) {
    fun generate(world: Path, output: Path, sourceBundleSha256: String): LodManifest {
        require(sourceBundleSha256.matches(Regex("[0-9a-f]{64}"))) { "invalid source digest" }
        require(!Files.exists(output)) { "output already exists" }
        require(
            !output.toAbsolutePath().normalize().startsWith(world.toAbsolutePath().normalize())
        ) {
            "output is inside source"
        }
        val columns = AnvilSource.columns(world, limits.maxChunks)
        val grouped = columns.groupBy { Math.floorDiv(it.x, 32) to Math.floorDiv(it.z, 32) }
        require(grouped.size <= limits.maxTiles) { "tile count exceeds limit" }
        val deadline = System.nanoTime() + limits.maxSeconds * 1_000_000_000
        Files.createDirectories(output.toAbsolutePath().parent)
        val staging = Files.createTempDirectory(output.toAbsolutePath().parent, ".lod-partial-")
        try {
            val loader = AnvilLoader(world)
            val instance = InstanceContainer(UUID.randomUUID(), DimensionType.OVERWORLD)
            val encoder = LssColumnEncoder()
            var total = 0L
            var columnBytes = 0L
            val tiles =
                grouped.map { (position, entries) ->
                    val (x, z) = position
                    val relative = "tiles/$x.$z.tile.zst"
                    val path = staging.resolve(relative)
                    Files.createDirectories(path.parent)
                    val bounded =
                        BoundedOutput(
                            Files.newOutputStream(path),
                            minOf(limits.maxTileBytes, limits.maxOutputBytes - total),
                        )
                    var decodedBytes = 20L
                    DataOutputStream(ZstdOutputStream(bounded, 3)).use { out ->
                        out.writeBytes("GLODT001")
                        out.writeInt(x)
                        out.writeInt(z)
                        out.writeInt(entries.size)
                        for (column in entries) {
                            require(System.nanoTime() < deadline) { "generator deadline exceeded" }
                            AnvilSource.validate(column)
                            val chunk =
                                requireNotNull(loader.loadChunk(instance, column.x, column.z)) {
                                    "stored chunk could not be loaded"
                                }
                            val bytes =
                                try {
                                    encoder.encode(chunk)
                                } finally {
                                    loader.unloadChunk(chunk)
                                }
                            decodedBytes += 12L + bytes.size
                            require(decodedBytes <= limits.maxDecodedTileBytes) {
                                "decoded tile exceeds limit"
                            }
                            columnBytes += bytes.size
                            out.writeInt(column.x)
                            out.writeInt(column.z)
                            out.writeInt(bytes.size)
                            out.write(bytes)
                        }
                    }
                    total += bounded.count
                    LodTile(x, z, relative, sha256(path), bounded.count, decodedBytes, entries.size)
                }
            val manifest =
                LodManifest(
                    sourceBundleSha256 = sourceBundleSha256,
                    registrySha256 = registryDigest(),
                    generatorSha256 = GeneratorBuild.sha256,
                    columns = columns.size,
                    sizeBytes = total,
                    columnBytes = columnBytes,
                    tiles = tiles,
                )
            val json = gg.grounds.lod.format.LodJson.write(manifest)
            require(json.size <= 1024 * 1024 && total + json.size <= limits.maxOutputBytes) {
                "manifest exceeds output budget"
            }
            Files.write(staging.resolve("manifest.json"), json)
            Files.move(staging, output.toAbsolutePath(), StandardCopyOption.ATOMIC_MOVE)
            return manifest
        } catch (failure: Throwable) {
            staging.toFile().deleteRecursively()
            throw failure
        }
    }

    fun identity(): Map<String, String> =
        mapOf(
            "format" to "lss20",
            "generatorSha256" to GeneratorBuild.sha256,
            "registrySha256" to registryDigest(),
            "minestom" to "2026.10.25b-26.3-grounds",
        )

    private fun registryDigest(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (id in 0 until Block.statesCount()) {
            val block = requireNotNull(Block.fromStateId(id))
            digest.update(
                (IdentityCodec.canonical(block.key().asString(), block.properties()) + "\n")
                    .toByteArray()
            )
        }
        MinecraftServer.getBiomeRegistry()
            .keys()
            .map { it.key().asString() }
            .sorted()
            .forEach { digest.update((it + "\n").toByteArray()) }
        return HexFormat.of().formatHex(digest.digest())
    }
}

internal class BoundedOutput(output: OutputStream, private val limit: Long) :
    FilterOutputStream(output) {
    var count = 0L
        private set

    override fun write(value: Int) {
        require(count < limit) { "compressed output exceeds limit" }
        out.write(value)
        count++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(length >= 0 && count + length <= limit) { "compressed output exceeds limit" }
        out.write(bytes, offset, length)
        count += length
    }
}

internal fun sha256(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}
