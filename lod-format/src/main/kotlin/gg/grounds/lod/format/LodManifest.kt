package gg.grounds.lod.format

import com.google.gson.*
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

object LodBounds {
    const val MAX_BUNDLE_BYTES = 128L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 1024 * 1024
    const val MAX_TILE_BYTES = 16L * 1024 * 1024
    const val MAX_DECODED_TILE_BYTES = 64L * 1024 * 1024
    const val MAX_COLUMN_BYTES = 2 * 1024 * 1024
}

data class LodTile(
    val x: Int,
    val z: Int,
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
    val decodedBytes: Long,
    val columns: Int,
)

data class LodManifest(
    val schemaVersion: Int = 1,
    val format: String = "lss20",
    val storageFormat: String = "grounds-column-tile-v1",
    val generator: String = "grounds-lod-worker-v1",
    val generatorSha256: String,
    val upstreamCommit: String = "45100da848135ac248cc4c70c32c6d98c7ef7b8f",
    val minestom: String = "2026.10.25b-26.3-grounds",
    /**
     * Actual encoder input, typically the prelit transport; canonical map identity lives in the
     * pin.
     */
    val sourceBundleSha256: String,
    val registrySha256: String,
    val dimension: String = "minecraft:overworld",
    val tileSizeChunks: Int = 32,
    val columns: Int,
    val sizeBytes: Long,
    val columnBytes: Long,
    val tiles: List<LodTile>,
) {
    fun validate() {
        require(
            schemaVersion == 1 && format == "lss20" && storageFormat == "grounds-column-tile-v1"
        ) {
            "unsupported LOD format"
        }
        require(minestom == "2026.10.25b-26.3-grounds") { "unsupported registry build" }
        require(dimension == "minecraft:overworld" && tileSizeChunks == 32) {
            "unsupported dimension or raster"
        }
        require(
            isDigest(sourceBundleSha256) && isDigest(registrySha256) && isDigest(generatorSha256)
        ) {
            "invalid identity digest"
        }
        require(
            generator == "grounds-lod-worker-v1" && upstreamCommit.matches(Regex("[0-9a-f]{40}"))
        ) {
            "invalid generator identity"
        }
        require(tiles.size in 0..32 && columns in 0..4096) { "coverage exceeds limit" }
        require(
            sizeBytes in 0..LodBounds.MAX_BUNDLE_BYTES &&
                columnBytes in 0..(32 * LodBounds.MAX_DECODED_TILE_BYTES)
        )
        require(tiles.map { it.x to it.z }.distinct().size == tiles.size) { "duplicate tile" }
        tiles.forEach { t ->
            require(t.x in -1_000_000..1_000_000 && t.z in -1_000_000..1_000_000)
            require(t.path == "tiles/${t.x}.${t.z}.tile.zst" && isDigest(t.sha256)) {
                "invalid tile identity"
            }
            require(
                t.columns in 1..1024 &&
                    t.sizeBytes in 1..LodBounds.MAX_TILE_BYTES &&
                    t.decodedBytes in 20..LodBounds.MAX_DECODED_TILE_BYTES
            )
        }
        require(
            tiles.sumOf { it.sizeBytes } == sizeBytes && tiles.sumOf { it.columns } == columns
        ) {
            "inconsistent totals"
        }
        require(tiles.sumOf { it.decodedBytes - 20L - 12L * it.columns } == columnBytes) {
            "inconsistent column bytes"
        }
    }
}

/** Explicit tree conversion: no reflective DTO allocation/Unsafe on the Native API path. */
object LodJson {
    private val gson =
        GsonBuilder()
            .disableJdkUnsafe()
            .setStrictness(Strictness.STRICT)
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create()
    private val fields =
        setOf(
            "schemaVersion",
            "format",
            "storageFormat",
            "generator",
            "generatorSha256",
            "upstreamCommit",
            "minestom",
            "sourceBundleSha256",
            "registrySha256",
            "dimension",
            "tileSizeChunks",
            "columns",
            "sizeBytes",
            "columnBytes",
            "tiles",
        )
    private val tileFields =
        setOf("x", "z", "path", "sha256", "sizeBytes", "decodedBytes", "columns")

    fun write(m: LodManifest): ByteArray {
        m.validate()
        val root =
            JsonObject().apply {
                addProperty("schemaVersion", m.schemaVersion)
                addProperty("format", m.format)
                addProperty("storageFormat", m.storageFormat)
                addProperty("generator", m.generator)
                addProperty("generatorSha256", m.generatorSha256)
                addProperty("upstreamCommit", m.upstreamCommit)
                addProperty("minestom", m.minestom)
                addProperty("sourceBundleSha256", m.sourceBundleSha256)
                addProperty("registrySha256", m.registrySha256)
                addProperty("dimension", m.dimension)
                addProperty("tileSizeChunks", m.tileSizeChunks)
                addProperty("columns", m.columns)
                addProperty("sizeBytes", m.sizeBytes)
                addProperty("columnBytes", m.columnBytes)
                add(
                    "tiles",
                    JsonArray().apply {
                        m.tiles.forEach { t ->
                            add(
                                JsonObject().apply {
                                    addProperty("x", t.x)
                                    addProperty("z", t.z)
                                    addProperty("path", t.path)
                                    addProperty("sha256", t.sha256)
                                    addProperty("sizeBytes", t.sizeBytes)
                                    addProperty("decodedBytes", t.decodedBytes)
                                    addProperty("columns", t.columns)
                                }
                            )
                        }
                    },
                )
            }
        return (gson.toJson(root) + "\n").toByteArray().also {
            require(it.size <= LodBounds.MAX_MANIFEST_BYTES)
        }
    }

    fun read(bytes: ByteArray): LodManifest {
        require(bytes.size <= LodBounds.MAX_MANIFEST_BYTES) { "manifest exceeds limit" }
        try {
            val root =
                JsonReader(StringReader(bytes.toString(Charsets.UTF_8))).use { reader ->
                    reader.strictness = Strictness.STRICT
                    val nodes = intArrayOf(0)
                    val value = readTree(reader, 0, nodes)
                    require(reader.peek() == JsonToken.END_DOCUMENT) { "trailing JSON" }
                    value.asJsonObject.also {
                        require(it.keySet() == fields) { "unexpected manifest fields" }
                    }
                }
            val tiles =
                root.getAsJsonArray("tiles").map { value ->
                    val t = value.asJsonObject
                    require(t.keySet() == tileFields) { "unexpected tile fields" }
                    LodTile(
                        integer(t, "x"),
                        integer(t, "z"),
                        string(t, "path"),
                        string(t, "sha256"),
                        number(t, "sizeBytes"),
                        number(t, "decodedBytes"),
                        integer(t, "columns"),
                    )
                }
            return LodManifest(
                    integer(root, "schemaVersion"),
                    string(root, "format"),
                    string(root, "storageFormat"),
                    string(root, "generator"),
                    string(root, "generatorSha256"),
                    string(root, "upstreamCommit"),
                    string(root, "minestom"),
                    string(root, "sourceBundleSha256"),
                    string(root, "registrySha256"),
                    string(root, "dimension"),
                    integer(root, "tileSizeChunks"),
                    integer(root, "columns"),
                    number(root, "sizeBytes"),
                    number(root, "columnBytes"),
                    tiles,
                )
                .also { it.validate() }
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid LOD manifest", e)
        }
    }

    private fun string(o: JsonObject, key: String): String =
        o.getAsJsonPrimitive(key).also { require(it.isString) }.asString

    private fun number(o: JsonObject, key: String): Long =
        o.getAsJsonPrimitive(key).also { require(it.isNumber) }.asString.toLong()

    private fun integer(o: JsonObject, key: String): Int =
        number(o, key).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()

    private fun readTree(reader: JsonReader, depth: Int, nodes: IntArray): JsonElement {
        require(depth <= 8 && ++nodes[0] <= 1024) { "manifest structure exceeds limit" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT ->
                JsonObject().apply {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        require(key.length <= 64 && !has(key)) { "duplicate or oversized field" }
                        add(key, readTree(reader, depth + 1, nodes))
                    }
                    reader.endObject()
                }
            JsonToken.BEGIN_ARRAY ->
                JsonArray().apply {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        require(size() < 32) { "array exceeds tile limit" }
                        add(readTree(reader, depth + 1, nodes))
                    }
                    reader.endArray()
                }
            JsonToken.STRING ->
                JsonPrimitive(reader.nextString().also { require(it.length <= 4096) })
            JsonToken.NUMBER -> {
                val literal = reader.nextString()
                require(literal.matches(Regex("-?(0|[1-9][0-9]{0,18})"))) {
                    "expected integral number"
                }
                JsonPrimitive(literal.toLong())
            }
            else -> throw IllegalArgumentException("unexpected JSON token")
        }
    }
}

fun isDigest(value: String): Boolean = value.matches(Regex("[0-9a-f]{64}"))

fun digest(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

fun digest(path: Path): String {
    val hash = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
    }
    return HexFormat.of().formatHex(hash.digest())
}
