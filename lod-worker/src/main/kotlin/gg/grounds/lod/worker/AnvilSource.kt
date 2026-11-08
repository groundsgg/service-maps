package gg.grounds.lod.worker

import java.io.ByteArrayInputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import net.kyori.adventure.key.Key
import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.StringBinaryTag
import net.minestom.server.MinecraftServer
import net.minestom.server.codec.Transcoder
import net.minestom.server.instance.block.Block

/** Enumerates actual region records; absent chunks never enter the generator. */
object AnvilSource {
    data class Column(
        val region: Path,
        val x: Int,
        val z: Int,
        val offset: Long,
        val allocated: Int,
    )

    private val regionName = Regex("""r\.(-?\d+)\.(-?\d+)\.mca""")

    fun columns(world: Path, maxChunks: Int): List<Column> {
        val directory = world.resolve("region")
        require(Files.isDirectory(directory) && !Files.isSymbolicLink(directory)) {
            "no region directory"
        }
        val result = ArrayList<Column>()
        val positions = HashSet<Pair<Int, Int>>()
        Files.newDirectoryStream(directory, "*.mca").use { files ->
            for (file in files) {
                require(!Files.isSymbolicLink(file)) { "region symlinks are unsupported" }
                val match =
                    requireNotNull(regionName.matchEntire(file.fileName.toString())) {
                        "invalid region name"
                    }
                val rx = match.groupValues[1].toInt()
                val rz = match.groupValues[2].toInt()
                require(rx in -1_000_000..1_000_000 && rz in -1_000_000..1_000_000) {
                    "region outside coordinate range"
                }
                require(file.fileName.toString() == "r.$rx.$rz.mca") {
                    "noncanonical region filename"
                }
                RandomAccessFile(file.toFile(), "r").use { region ->
                    require(region.length() >= 8192) { "truncated region header" }
                    for (slot in 0 until 1024) {
                        val location = region.readInt()
                        if (location == 0) continue
                        val offset = (location ushr 8).toLong() * 4096
                        val size = (location and 255) * 4096
                        require(offset >= 8192 && size > 0 && offset + size <= region.length()) {
                            "invalid chunk location"
                        }
                        val x = rx * 32 + slot % 32
                        val z = rz * 32 + slot / 32
                        require(positions.add(x to z)) { "duplicate chunk coordinate" }
                        result += Column(file, x, z, offset, size)
                        require(result.size <= maxChunks) {
                            "stored chunk count exceeds $maxChunks"
                        }
                    }
                }
            }
        }
        require(result.isNotEmpty()) { "no stored chunks" }
        return result.sortedWith(
            compareBy<Column>(
                { Math.floorDiv(it.x, 32) },
                { Math.floorDiv(it.z, 32) },
                { it.z },
                { it.x },
            )
        )
    }

    fun validate(column: Column) {
        val nbt =
            RandomAccessFile(column.region.toFile(), "r").use { file ->
                file.seek(column.offset)
                val size = file.readInt()
                // The pinned Minestom writer includes the length word itself in this value
                // (compressed bytes + 5 rather than + 1). At a sector boundary its reader
                // consumes four bytes from the next record, ignored by zlib. Keep the same
                // valid stream readable without EVER reading outside this record's sectors.
                require(size in 2..column.allocated) { "invalid chunk record size" }
                val compression = file.readUnsignedByte()
                val raw = ByteArray(minOf(size - 1, column.allocated - 5))
                file.readFully(raw)
                val input = ByteArrayInputStream(raw)
                val decoded =
                    when (compression) {
                        1 -> GZIPInputStream(input)
                        2 -> InflaterInputStream(input)
                        3 -> input
                        else ->
                            throw IllegalArgumentException(
                                "unsupported region compression $compression"
                            )
                    }
                decoded.use { stream ->
                    BinaryTagIO.reader(8L * 1024 * 1024).read(stream).also {
                        require(stream.read() == -1) { "trailing decoded chunk data" }
                    }
                }
            }
        require(
            nbt.contains("xPos", BinaryTagTypes.INT) &&
                nbt.contains("zPos", BinaryTagTypes.INT) &&
                nbt.getInt("xPos") == column.x &&
                nbt.getInt("zPos") == column.z
        ) {
            "chunk coordinate mismatch"
        }
        validatePalettes(nbt)
    }

    /** Minestom can replace unknown names with defaults; refuse them before loading. */
    fun validatePalettes(chunk: CompoundBinaryTag) {
        require(!chunk.contains("status") || chunk.contains("status", BinaryTagTypes.STRING)) {
            "invalid chunk status"
        }
        require(chunk.getString("status") in setOf("", "minecraft:full")) {
            "unsupported chunk status"
        }
        require(chunk.contains("sections", BinaryTagTypes.LIST)) { "chunk sections are missing" }
        val sections = chunk.getList("sections")
        require(sections.isEmpty || sections.elementType() == BinaryTagTypes.COMPOUND) {
            "invalid section element type"
        }
        require(sections.size() <= 24) { "unsupported section count" }
        val positions = HashSet<Int>()
        for (tag in sections) {
            val section = tag as CompoundBinaryTag
            require(section.contains("Y", BinaryTagTypes.BYTE)) {
                "section Y is missing or invalid"
            }
            val y = section.getByte("Y").toInt()
            // This worker consumes the exact fixed-height overworld the runtime loads.
            // Boundary light sections need an explicit encoder path; never drop them silently.
            require(y in -4..19 && positions.add(y)) { "unsupported or duplicate section Y" }
            for (name in listOf("block_states", "biomes")) {
                require(
                    !section.contains(name) || section.contains(name, BinaryTagTypes.COMPOUND)
                ) {
                    "invalid $name container"
                }
            }
            for (name in listOf("BlockLight", "SkyLight")) {
                require(
                    !section.contains(name) ||
                        (section.contains(name, BinaryTagTypes.BYTE_ARRAY) &&
                            section.getByteArray(name).size == 2048)
                ) {
                    "invalid light layer"
                }
            }
            val states = section.getCompound("block_states")
            if (!states.isEmpty) {
                require(
                    states.contains("palette", BinaryTagTypes.LIST) &&
                        states.getList("palette").elementType() == BinaryTagTypes.COMPOUND
                ) {
                    "invalid block palette type"
                }
                val palette = states.getList("palette", BinaryTagTypes.COMPOUND)
                require(!palette.isEmpty) { "empty block palette" }
                for (entry in palette) {
                    val state = entry as CompoundBinaryTag
                    requireNotNull(
                        Block.COMPACT_STATE_CODEC.decode(Transcoder.NBT, state).orElse(null)
                    ) {
                        "unsupported source block state: $state"
                    }
                }
            }
            val biomes = section.getCompound("biomes")
            if (!biomes.isEmpty) {
                require(
                    biomes.contains("palette", BinaryTagTypes.LIST) &&
                        biomes.getList("palette").elementType() == BinaryTagTypes.STRING
                ) {
                    "invalid biome palette type"
                }
                val palette = biomes.getList("palette", BinaryTagTypes.STRING)
                require(!palette.isEmpty) { "empty biome palette" }
                for (biome in palette) require(
                    MinecraftServer.getBiomeRegistry()
                        .get(Key.key((biome as StringBinaryTag).value())) != null
                ) {
                    "unknown source biome"
                }
            }
        }
    }
}
