package gg.grounds.lod.worker

import com.github.luben.zstd.ZstdInputStream
import dev.vox.lss.common.wire.WireSectionCursor
import dev.vox.lss.common.wire.WireSectionCursor.Layout
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.minestom.server.MinecraftServer
import net.minestom.server.instance.DynamicChunk
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.anvil.AnvilLoader
import net.minestom.server.instance.block.Block
import net.minestom.server.world.DimensionType
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.io.TempDir

class LodGeneratorTest {
    @TempDir lateinit var temp: Path

    private fun world(vararg positions: Pair<Int, Int>): Path {
        val world = Files.createDirectory(temp.resolve(UUID.randomUUID().toString()))
        val loader = AnvilLoader(world)
        val instance = InstanceContainer(UUID.randomUUID(), DimensionType.OVERWORLD)
        positions.forEach { (x, z) ->
            val chunk = DynamicChunk(instance, x, z)
            chunk.lockWriteLock()
            try { chunk.setBlock(x * 16, 64, z * 16, Block.STONE) }
            finally { chunk.unlockWriteLock() }
            loader.saveChunk(chunk)
            loader.unloadChunk(chunk)
        }
        return world
    }

    @Test
    fun `only stored columns are packed and negative coordinates use floor division`() {
        val input = world(-1 to -1, 0 to 0, 32 to 0)
        val output = temp.resolve("result")
        val manifest = LodGenerator().generate(input, output, SHA)
        assertEquals(3, manifest.columns)
        assertEquals(setOf(-1 to -1, 0 to 0, 1 to 0), manifest.tiles.map { it.x to it.z }.toSet())
        manifest.tiles.forEach { tile ->
            val file = output.resolve(tile.path)
            assertEquals(tile.sizeBytes, Files.size(file))
            assertEquals(tile.sha256, hash(Files.readAllBytes(file)))
            DataInputStream(ZstdInputStream(Files.newInputStream(file))).use { data ->
                assertContentEquals("GLODT001".toByteArray(), data.readNBytes(8))
                assertEquals(tile.x, data.readInt())
                assertEquals(tile.z, data.readInt())
                assertEquals(1, data.readInt())
                val cx = data.readInt()
                val cz = data.readInt()
                assertEquals(tile.x, Math.floorDiv(cx, 32))
                assertEquals(tile.z, Math.floorDiv(cz, 32))
                val body = data.readNBytes(data.readInt())
                val column = WireSectionCursor.parse(body, Layout.V20)
                assertTrue("minecraft:stone" in column.dictionary())
                assertEquals(-1, data.read())
            }
        }
    }

    @Test
    fun `generation is independent of region timestamps and output directory`() {
        val input = world(0 to 0, 1 to 1)
        val a = temp.resolve("a")
        val b = temp.resolve("b")
        LodGenerator().generate(input, a, SHA)
        Files.setLastModifiedTime(input.resolve("region/r.0.0.mca"), java.nio.file.attribute.FileTime.fromMillis(0))
        LodGenerator().generate(input, b, SHA)
        assertContentEquals(Files.readAllBytes(a.resolve("manifest.json")), Files.readAllBytes(b.resolve("manifest.json")))
    }

    @Test
    fun `output and chunk limits refuse rather than publish a partial result`() {
        val input = world(0 to 0, 1 to 0)
        val output = temp.resolve("refused")
        assertFailsWith<IllegalArgumentException> {
            LodGenerator(LodLimits(maxChunks = 1)).generate(input, output, SHA)
        }
        assertFalse(Files.exists(output))
        assertFailsWith<IllegalArgumentException> {
            LodGenerator(LodLimits(maxOutputBytes = 1)).generate(input, output, SHA)
        }
        assertFalse(Files.exists(output))
    }

    @Test
    fun `a malformed region location never causes generation of a missing chunk`() {
        val input = Files.createDirectory(temp.resolve("broken"))
        val region = Files.createDirectories(input.resolve("region"))
        Files.write(region.resolve("r.0.0.mca"), ByteArray(8192).also { it[3] = 1 })
        assertFailsWith<IllegalArgumentException> {
            LodGenerator().generate(input, temp.resolve("broken-output"), SHA)
        }
        assertFalse(Files.exists(temp.resolve("broken-output")))
    }

    @Test
    fun `legacy Minestom record lengths are read only inside their allocated sectors`() {
        val input = world(0 to 0)
        val region = input.resolve("region/r.0.0.mca")
        java.io.RandomAccessFile(region.toFile(), "rw").use { file ->
            val location = file.readInt()
            file.seek((location ushr 8).toLong() * 4096)
            // Older Minestom stores a total-record length, including its own four bytes.
            // Keep the valid zlib stream and padding, but exercise the sector boundary.
            file.writeInt((location and 255) * 4096)
        }
        assertEquals(1, LodGenerator().generate(input, temp.resolve("legacy"), SHA).columns)
    }

    @Test
    fun `unknown source block names are rejected before the runtime loader can replace them`() {
        val palette = ListBinaryTag.from(listOf(CompoundBinaryTag.builder().putString("id", "missing:block").build()))
        val sections = ListBinaryTag.from(listOf(
            CompoundBinaryTag.builder().putByte("Y", 0).put(
                "block_states", CompoundBinaryTag.builder().put("palette", palette).build()
            ).build()
        ))
        val chunk = CompoundBinaryTag.builder().put("sections", sections).build()
        assertFailsWith<IllegalArgumentException> { AnvilSource.validatePalettes(chunk) }
    }

    private fun hash(bytes: ByteArray) =
        java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    @Test
    fun `protochunk status is refused instead of publishing an empty column`() {
        val chunk = CompoundBinaryTag.builder().putString("status", "minecraft:noise")
            .put("sections", ListBinaryTag.empty()).build()
        assertFailsWith<IllegalArgumentException> { AnvilSource.validatePalettes(chunk) }
    }

    @Test
    fun `wrong section element types cannot become an empty section list`() {
        val chunk = CompoundBinaryTag.builder()
            .put("sections", ListBinaryTag.from(listOf(net.kyori.adventure.nbt.StringBinaryTag.stringBinaryTag("bad")))).build()
        assertFailsWith<IllegalArgumentException> { AnvilSource.validatePalettes(chunk) }
    }

    @Test
    fun `duplicate section positions cannot overwrite earlier terrain`() {
        val section = CompoundBinaryTag.builder().putByte("Y", 0).build()
        val chunk = CompoundBinaryTag.builder().put("sections", ListBinaryTag.from(listOf(section, section))).build()
        assertFailsWith<IllegalArgumentException> { AnvilSource.validatePalettes(chunk) }
    }

    @Test
    fun `out of range and boundary light sections require an explicit encoder policy`() {
        for (y in listOf(-5, 20, 100)) {
            val section = CompoundBinaryTag.builder().putByte("Y", y.toByte())
                .putByteArray("SkyLight", ByteArray(2048) { -1 }).build()
            val chunk = CompoundBinaryTag.builder().put("sections", ListBinaryTag.from(listOf(section))).build()
            assertFailsWith<IllegalArgumentException> { AnvilSource.validatePalettes(chunk) }
        }
    }

    @Test
    fun `wrong container types cannot be silently replaced by air`() {
        val section = CompoundBinaryTag.builder().putByte("Y", 0).putInt("block_states", 7).build()
        val chunk = CompoundBinaryTag.builder().put("sections", ListBinaryTag.from(listOf(section))).build()
        assertFailsWith<IllegalArgumentException> { AnvilSource.validatePalettes(chunk) }
    }

    @Test
    fun `region coordinate aliases cannot validate a different file than the loader reads`() {
        val input = world(0 to 0)
        Files.copy(input.resolve("region/r.0.0.mca"), input.resolve("region/r.00.0.mca"))
        assertFailsWith<IllegalArgumentException> {
            LodGenerator().generate(input, temp.resolve("aliased"), SHA)
        }
        assertFalse(Files.exists(temp.resolve("aliased")))
    }

    companion object {
        private val SHA = "a".repeat(64)
        @JvmStatic @BeforeAll fun initialize() { MinecraftServer.init() }
    }
}
