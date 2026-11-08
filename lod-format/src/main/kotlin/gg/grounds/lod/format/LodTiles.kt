package gg.grounds.lod.format

import com.github.luben.zstd.ZstdInputStream
import dev.vox.lss.common.wire.WireSectionCursor
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/** Chunk coordinates, never native DH tile coordinates. */
data class ColumnPosition(val x: Int, val z: Int)

object LodTiles {
    fun read(tile: LodTile, compressed: ByteArray): Map<ColumnPosition, ByteArray> {
        require(
            tile.sizeBytes in 1..LodBounds.MAX_TILE_BYTES &&
                tile.decodedBytes in 20..LodBounds.MAX_DECODED_TILE_BYTES
        )
        require(compressed.size.toLong() == tile.sizeBytes && digest(compressed) == tile.sha256) {
            "tile integrity mismatch"
        }
        val raw =
            try {
                ZstdInputStream(ByteArrayInputStream(compressed)).use {
                    it.readNBytes((tile.decodedBytes + 1).toInt())
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("invalid compressed tile", e)
            }
        require(raw.size.toLong() == tile.decodedBytes) { "decoded tile size mismatch" }
        try {
            DataInputStream(ByteArrayInputStream(raw)).use { input ->
                require(input.readNBytes(8).contentEquals("GLODT001".toByteArray())) {
                    "invalid tile header"
                }
                require(input.readInt() == tile.x && input.readInt() == tile.z) { "wrong tile" }
                val count = input.readInt()
                require(count == tile.columns && count in 1..1024)
                val columns = LinkedHashMap<ColumnPosition, ByteArray>()
                repeat(count) {
                    val pos = ColumnPosition(input.readInt(), input.readInt())
                    require(
                        Math.floorDiv(pos.x, 32) == tile.x && Math.floorDiv(pos.z, 32) == tile.z
                    ) {
                        "column outside tile"
                    }
                    require(!columns.containsKey(pos)) { "duplicate column" }
                    val size = input.readInt()
                    require(size in 2..LodBounds.MAX_COLUMN_BYTES && size <= input.available()) {
                        "invalid column length"
                    }
                    val body = input.readNBytes(size)
                    val parsed = WireSectionCursor.parse(body, WireSectionCursor.Layout.V20)
                    require(
                        parsed.sections().size <= 24 &&
                            parsed.sections().map { it.sectionY() }.distinct().size ==
                                parsed.sections().size
                    )
                    require(
                        parsed.sections().all {
                            it.sectionY() in -4..19 &&
                                it.nonEmptyBlockCount() in 0..4096 &&
                                it.fluidCount() in 0..4096
                        }
                    )
                    columns[pos] = body
                }
                require(input.read() == -1) { "trailing tile data" }
                return columns
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid LOD tile", e)
        }
    }
}
