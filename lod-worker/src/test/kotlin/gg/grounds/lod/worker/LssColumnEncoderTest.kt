package gg.grounds.lod.worker

import dev.vox.lss.common.wire.WireSectionCursor
import dev.vox.lss.common.wire.WireSectionCursor.Layout
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import net.minestom.server.MinecraftServer
import net.minestom.server.instance.DynamicChunk
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.block.Block
import net.minestom.server.world.DimensionType
import net.minestom.server.world.biome.Biome
import org.junit.jupiter.api.BeforeAll

class LssColumnEncoderTest {
    private fun chunk() =
        DynamicChunk(InstanceContainer(UUID.randomUUID(), DimensionType.OVERWORLD), 0, 0).also {
            val biome = MinecraftServer.getBiomeRegistry().getId(Biome.DESERT)
            it.sections.forEach { section -> section.biomePalette().fill(biome) }
        }

    private fun DynamicChunk.place(x: Int, y: Int, z: Int, block: Block) {
        lockWriteLock()
        try {
            setBlock(x, y, z, block)
        } finally {
            unlockWriteLock()
        }
    }

    @Test
    fun `empty terrain emits the upstream empty dictionary clear frame`() {
        assertContentEquals(byteArrayOf(0, 0), LssColumnEncoder().encode(chunk()))
    }

    @Test
    fun `block identities biomes and voxel positions survive upstream decoding`() {
        val chunk = chunk()
        chunk.place(3, 5, 7, Block.STONE)
        val decoded = WireSectionCursor.parse(LssColumnEncoder().encode(chunk), Layout.V20)
        assertEquals(listOf(0), decoded.sections().map { it.sectionY() })
        val section = decoded.sections().single()
        assertEquals(1, section.nonEmptyBlockCount())
        assertEquals(0, section.fluidCount())
        val values =
            WireSectionCursor.unpack(section.blocks().data(), section.blocks().bits(), 4096)
        val palette = section.blocks().palette()
        assertEquals("minecraft:stone", decoded.dictionary()[palette[values[5 * 256 + 7 * 16 + 3]]])
        assertEquals("minecraft:air", decoded.dictionary()[palette[values[0]]])
        assertEquals("minecraft:desert", decoded.dictionary()[section.biomes().palette().single()])
    }

    @Test
    fun `fluid counts include waterlogged states and canonical properties are sorted`() {
        val chunk = chunk()
        chunk.place(0, 0, 0, Block.WATER)
        chunk.place(1, 0, 0, Block.LAVA)
        chunk.place(2, 0, 0, Block.OAK_STAIRS.withProperty("waterlogged", "true"))
        val decoded = WireSectionCursor.parse(LssColumnEncoder().encode(chunk), Layout.V20)
        assertEquals(3, decoded.sections().single().nonEmptyBlockCount())
        assertEquals(3, decoded.sections().single().fluidCount())
        assertTrue(
            "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=true]" in
                decoded.dictionary()
        )
    }

    @Test
    fun `sky light retains only one air section either side of content`() {
        val chunk = chunk()
        chunk.place(0, 0, 0, Block.STONE)
        for (y in -2..2) chunk.getSection(y).setSkyLight(ByteArray(2048) { -1 })
        val decoded = WireSectionCursor.parse(LssColumnEncoder().encode(chunk), Layout.V20)
        assertEquals(listOf(-1, 0, 1), decoded.sections().map { it.sectionY() })
        decoded.sections().forEach { assertContentEquals(ByteArray(2048) { -1 }, it.skyLight()) }
    }

    @Test
    fun `block lit air survives outside the terrain band`() {
        val chunk = chunk()
        chunk.place(0, 0, 0, Block.STONE)
        chunk.getSection(3).setBlockLight(ByteArray(2048) { 0x77 })
        val decoded = WireSectionCursor.parse(LssColumnEncoder().encode(chunk), Layout.V20)
        assertEquals(listOf(0, 3), decoded.sections().map { it.sectionY() })
        assertContentEquals(ByteArray(2048) { 0x77 }, decoded.sections().last().blockLight())
    }

    @Test
    fun `same terrain has the same bytes across instances`() {
        val a = chunk()
        val b = chunk()
        a.place(0, -32, 0, Block.GRASS_BLOCK)
        b.place(0, -32, 0, Block.GRASS_BLOCK)
        assertContentEquals(LssColumnEncoder().encode(a), LssColumnEncoder().encode(b))
    }

    @Test
    fun `unknown biome refuses the column instead of changing its identity`() {
        val chunk = chunk()
        chunk.place(0, 0, 0, Block.STONE)
        chunk.getSection(0).biomePalette().fill(999999)
        assertFailsWith<IllegalArgumentException> { LssColumnEncoder().encode(chunk) }
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun initialize() {
            MinecraftServer.init()
        }
    }
}
