package gg.grounds.lod.runtime

import dev.vox.lss.common.region.RegionSummaryWire
import dev.vox.lss.common.wire.WireBytes
import gg.grounds.lod.format.ColumnPosition
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class Lss20Test {
    @Test
    fun `handshake is exactly two varints and requires voxel plus zstd`() {
        assertEquals(3, Lss20.handshake(byteArrayOf(20, 3)))
        assertEquals(11, Lss20.handshake(byteArrayOf(20, 11)))
        assertNull(Lss20.handshake(byteArrayOf(19, 3)))
        assertNull(Lss20.handshake(byteArrayOf(20, 1)))
        assertFails { Lss20.handshake(byteArrayOf(20, 3, 1)) }
        assertFails { Lss20.handshake(byteArrayOf(20)) }
    }

    @Test
    fun `session flags require capability and generation stays disabled`() {
        assertContentEquals(byteArrayOf(20, 1, 96, 0, 42), Lss20.session(3, 42))
        assertContentEquals(byteArrayOf(20, 1, 96, 0, 42, 0), Lss20.session(11, 42))
        assertEquals(42, Lss20.clientInfo(byteArrayOf(42, 0, 0)))
        assertFails { Lss20.clientInfo(ByteArray(300)) }
    }

    @Test
    fun `batch uses upstream x high bits preserves negatives and rejects malformed counts`() {
        val packed = (-36L shl 32) or (-45L and 0xffffffffL)
        val bytes =
            WireBytes.Writer(20).writeVarInt(1).writeLong(packed).writeLong(123).toByteArray()
        val request = Lss20.batch(bytes).single()
        assertEquals(ColumnPosition(-36, -45), request.position)
        assertEquals(123, request.timestamp)
        assertFails { Lss20.batch(bytes + byteArrayOf(0)) }
        assertFails { Lss20.batch(WireBytes.Writer(5).writeVarInt(1025).toByteArray()) }
        assertFails { Lss20.batch(byteArrayOf(1)) }
    }

    @Test
    fun `voxel and summary carry seconds and actual coverage`() {
        val root = Files.createTempDirectory("lss-wire-")
        try {
            val fixture = LodFixture(root.resolve("input"))
            LodCache(fixture.control(AtomicInteger()), fixture.expectation, root.resolve("cache"))
                .use { cache ->
                    val source = cache.load(fixture.player).get()
                    val r = WireBytes.Reader(Lss20.voxel(source, ColumnPosition(0, 0)))
                    assertEquals(0, r.readInt())
                    assertEquals(0, r.readInt())
                    assertEquals("minecraft:overworld", r.readUtf(256))
                    assertEquals(1794146400, r.readLong())
                    assertEquals(3, r.readUnsignedByte())
                    assertEquals(1, r.readUnsignedByte())
                    assertContentEquals(source.columns.values.first(), r.readBytes(r.readVarInt()))
                    assertEquals(0, r.remaining())
                    val request =
                        RegionSummaryWire.encodeRequest(
                            RegionSummaryWire.Request("minecraft:overworld", 0, 0, 1)
                        )
                    val summary =
                        RegionSummaryWire.decodeSummary(
                            assertNotNull(Lss20.summary(source, request))
                        )
                    assertEquals(1794146400, summary.stampSeconds()[4])
                    assertEquals(1, summary.stampSeconds().count { it > 0 })
                    assertNull(
                        Lss20.summary(
                            source,
                            RegionSummaryWire.encodeRequest(
                                RegionSummaryWire.Request("minecraft:the_nether", 0, 0, 1)
                            ),
                        )
                    )
                    assertFails {
                        Lss20.summary(
                            source,
                            RegionSummaryWire.encodeRequest(
                                RegionSummaryWire.Request("minecraft:overworld", 0, 0, 65)
                            ),
                        )
                    }
                }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
