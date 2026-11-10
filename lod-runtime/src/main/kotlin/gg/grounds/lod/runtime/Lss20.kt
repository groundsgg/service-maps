package gg.grounds.lod.runtime

import dev.vox.lss.common.region.RegionSummaryWire
import dev.vox.lss.common.wire.WireBytes
import gg.grounds.lod.format.ColumnPosition

internal data class LodColumnRequest(val position: ColumnPosition, val timestamp: Long)

internal data class LodReply(val channel: String, val body: ByteArray)

/** LSS20 only. Pilot profile requires Zstd, exact MC version, no worldgen or legacy dialect. */
internal object Lss20 {
    const val HANDSHAKE = "lss:handshake_c2s"
    const val CLIENT_INFO = "lss:client_info"
    const val BATCH_REQUEST = "lss:batch_chunk_req"
    const val SUMMARY_REQUEST = "lss:region_summary_req"
    const val SESSION = "lss:session_config"
    const val VOXEL = "lss:voxel_column"
    const val BATCH_RESPONSE = "lss:batch_response"
    const val SUMMARY = "lss:region_summary"
    const val DIMENSION = "minecraft:overworld"
    val inbound = setOf(HANDSHAKE, CLIENT_INFO, BATCH_REQUEST, SUMMARY_REQUEST)
    val outbound = setOf(SESSION, VOXEL, BATCH_RESPONSE, SUMMARY)

    fun handshake(body: ByteArray): Int? {
        require(body.size in 2..10)
        val r = WireBytes.Reader(body)
        val version = r.readVarInt()
        val capabilities = r.readVarInt()
        require(r.remaining() == 0)
        return capabilities.takeIf { version == 20 && capabilities and 3 == 3 }
    }

    fun clientInfo(body: ByteArray): Int {
        require(body.size in 1..256)
        return WireBytes.Reader(body).readVarInt().also { require(it > 0) }
    }

    fun session(capabilities: Int, dataVersion: Int, enabled: Boolean = true): ByteArray =
        WireBytes.Writer(16)
            .writeVarInt(20)
            .writeByte(if (enabled) 1 else 0)
            .writeVarInt(96)
            .writeByte(0)
            .writeVarInt(dataVersion)
            .also { if (capabilities and 8 != 0) it.writeVarInt(0) }
            .toByteArray()

    fun batch(body: ByteArray): List<LodColumnRequest> {
        require(body.size in 1..16389)
        val r = WireBytes.Reader(body)
        val count = r.readVarInt()
        require(count in 0..1024 && r.remaining() == count * 16)
        return List(count) {
            val packed = r.readLong()
            LodColumnRequest(ColumnPosition((packed shr 32).toInt(), packed.toInt()), r.readLong())
        }
    }

    fun voxel(source: LoadedLod, position: ColumnPosition): ByteArray {
        val compressed = requireNotNull(source.columns[position])
        return WireBytes.Writer(compressed.size + 64)
            .writeInt(position.x)
            .writeInt(position.z)
            .writeUtf(DIMENSION)
            .writeLong(source.timestampSeconds)
            .writeByte(3)
            .writeByte(1)
            .writeVarInt(compressed.size)
            .writeBytes(compressed)
            .toByteArray()
    }

    fun reply(source: LoadedLod, request: LodColumnRequest): LodReply =
        when {
            request.position !in source.columns -> status(request.position, 2)
            request.timestamp >= source.timestampSeconds -> status(request.position, 1)
            else -> LodReply(VOXEL, voxel(source, request.position))
        }

    fun statusCode(source: LoadedLod, request: LodColumnRequest): Int? =
        when {
            request.position !in source.columns -> 2
            request.timestamp >= source.timestampSeconds -> 1
            else -> null
        }

    fun statusBatch(source: LoadedLod, requests: List<LodColumnRequest>): LodReply {
        require(requests.size in 1..64)
        val writer = WireBytes.Writer(1 + requests.size * 9).writeVarInt(requests.size)
        for (request in requests) {
            val code = requireNotNull(statusCode(source, request))
            val pos = request.position
            writer
                .writeByte(code)
                .writeLong((pos.x.toLong() shl 32) or (pos.z.toLong() and 0xffffffffL))
        }
        return LodReply(BATCH_RESPONSE, writer.toByteArray())
    }

    private fun status(pos: ColumnPosition, code: Int) =
        LodReply(
            BATCH_RESPONSE,
            WireBytes.Writer(10)
                .writeVarInt(1)
                .writeByte(code)
                .writeLong((pos.x.toLong() shl 32) or (pos.z.toLong() and 0xffffffffL))
                .toByteArray(),
        )

    fun summary(source: LoadedLod, body: ByteArray): ByteArray? {
        require(body.size <= 300)
        val req = RegionSummaryWire.decodeRequest(body)
        require(req.tileRadius() <= 8)
        if (req.dimension() != DIMENSION) return null
        val side = 2 * req.tileRadius() + 1
        val stamps =
            LongArray(side * side) { index ->
                val tile =
                    ColumnPosition(
                        req.centerTileX() - req.tileRadius() + index % side,
                        req.centerTileZ() - req.tileRadius() + index / side,
                    )
                if (tile in source.tiles) source.timestampSeconds else 0L
            }
        return RegionSummaryWire.encodeSummary(
            RegionSummaryWire.Summary(
                DIMENSION,
                req.centerTileX(),
                req.centerTileZ(),
                req.tileRadius(),
                stamps,
            )
        )
    }
}
