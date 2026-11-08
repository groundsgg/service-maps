package gg.grounds.lod.worker

import dev.vox.lss.common.wire.IdentityCodec
import dev.vox.lss.common.wire.IdentityDictionary
import dev.vox.lss.common.wire.WireSectionCursor
import dev.vox.lss.common.wire.WireSectionCursor.Layout
import dev.vox.lss.common.wire.WireSectionCursor.WireColumn
import dev.vox.lss.common.wire.WireSectionCursor.WireContainer
import dev.vox.lss.common.wire.WireSectionCursor.WireSection
import net.minestom.server.MinecraftServer
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.light.Light
import net.minestom.server.instance.palette.Palette

/** Encodes protocol-20 column bodies, without a Minecraft connection or any cloud I/O. */
class LssColumnEncoder {
    private data class State(val identity: String, val air: Boolean, val fluid: Boolean)
    private data class SectionData(
        val y: Int,
        val blocks: IntArray,
        val biomes: IntArray,
        val nonAir: Int,
        val fluids: Int,
        val blockLight: ByteArray?,
        val skyLight: ByteArray?,
    )

    private val states = HashMap<Int, State>()

    fun encode(chunk: Chunk): ByteArray {
        chunk.lockReadLock()
        return try { encodeLocked(chunk) } finally { chunk.unlockReadLock() }
    }

    private fun encodeLocked(chunk: Chunk): ByteArray {
        require(chunk.maxSection - chunk.minSection in 1..64) { "unsupported section count" }
        val sections =
            (chunk.minSection until chunk.maxSection).map { y ->
                val section = chunk.getSection(y)
                val blocks = values(section.blockPalette())
                var nonAir = 0
                var fluids = 0
                for (id in blocks) {
                    val state = state(id)
                    if (!state.air) nonAir++
                    if (state.fluid) fluids++
                }
                SectionData(
                    y, blocks, values(section.biomePalette()), nonAir, fluids,
                    light(section.blockLight()), light(section.skyLight()),
                )
            }
        val content = sections.filter { it.nonAir > 0 }
        val low = content.minOfOrNull { it.y }
        val high = content.maxOfOrNull { it.y }
        val included =
            sections.filter {
                it.nonAir > 0 || it.blockLight != null ||
                    (it.skyLight != null && low != null && it.y >= low - 1 && it.y <= high!! + 1)
            }
        val dictionary = IdentityDictionary()
        val encoded =
            included.map {
                WireSection(
                    it.y, it.nonAir, it.fluids,
                    container(it.blocks, 4, dictionary) { id -> state(id).identity },
                    container(it.biomes, 1, dictionary) { id ->
                        requireNotNull(MinecraftServer.getBiomeRegistry().getKey(id)) {
                            "unknown biome id: $id"
                        }.key().asString()
                    },
                    it.blockLight, it.skyLight,
                )
            }
        val result = WireSectionCursor.emit(WireColumn(dictionary.entries(), encoded), Layout.V20)
        require(result.size <= MAX_COLUMN_BYTES) { "column exceeds protocol size bound" }
        return result
    }

    private fun state(id: Int): State =
        states.getOrPut(id) {
            val block = requireNotNull(Block.fromStateId(id)) { "unknown block state id: $id" }
            State(
                IdentityCodec.canonical(block.key().asString(), block.properties()),
                block.isAir, block.isFluid,
            )
        }

    private fun values(palette: Palette): IntArray {
        val dimension = palette.dimension()
        return IntArray(dimension * dimension * dimension).also { result ->
            palette.getAll { x, y, z, value ->
                result[(y * dimension + z) * dimension + x] = value
            }
        }
    }

    private fun container(
        values: IntArray,
        minimumBits: Int,
        dictionary: IdentityDictionary,
        identity: (Int) -> String,
    ): WireContainer {
        val palette = LinkedHashMap<Int, Int>()
        val indices = IntArray(values.size) { i ->
            palette.getOrPut(values[i]) { palette.size }
        }
        val entries = palette.keys.map { dictionary.indexOf(identity(it)) }.toIntArray()
        val bits =
            if (entries.size == 1) 0
            else maxOf(minimumBits, 32 - Integer.numberOfLeadingZeros(entries.size - 1))
        return WireContainer(
            bits, entries,
            if (bits == 0) LongArray(0) else WireSectionCursor.pack(indices, bits),
        )
    }

    private fun light(light: Light): ByteArray? {
        val bytes = light.array() ?: return null
        if (bytes.isEmpty()) return null
        require(bytes.size == 2048) { "invalid light layer size: ${bytes.size}" }
        return bytes.takeIf { layer -> layer.any { it != 0.toByte() } }?.clone()
    }

    companion object {
        const val MAX_COLUMN_BYTES = 2 * 1024 * 1024
    }
}
