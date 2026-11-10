package gg.grounds.lod.format

import com.github.luben.zstd.Zstd
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class LodBundleTest {
    @TempDir lateinit var temp: Path
    private val sha = "a".repeat(64)

    private fun raw(x: Int = -1, z: Int = -1, length: Int = 2): ByteArray =
        ByteArrayOutputStream()
            .also { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.writeBytes("GLODT001")
                    out.writeInt(-1)
                    out.writeInt(-1)
                    out.writeInt(1)
                    out.writeInt(x)
                    out.writeInt(z)
                    out.writeInt(length)
                    out.write(byteArrayOf(0, 0))
                }
            }
            .toByteArray()

    private fun fixture(raw: ByteArray = raw()): Pair<LodManifest, ByteArray> {
        val compressed = Zstd.compress(raw)
        return LodManifest(
            sourceBundleSha256 = sha,
            generatorSha256 = sha,
            registrySha256 = sha,
            columns = 1,
            sizeBytes = compressed.size.toLong(),
            columnBytes = 2,
            tiles =
                listOf(
                    LodTile(
                        -1,
                        -1,
                        "tiles/-1.-1.tile.zst",
                        digest(compressed),
                        compressed.size.toLong(),
                        raw.size.toLong(),
                        1,
                    )
                ),
        ) to compressed
    }

    @Test
    fun `bounded bundle roundtrip returns protocol columns and deterministic bytes`() {
        val (manifest, tile) = fixture()
        val source = Files.createDirectory(temp.resolve("source"))
        Files.createDirectories(source.resolve("tiles"))
        Files.write(source.resolve("manifest.json"), LodJson.write(manifest))
        Files.write(source.resolve(manifest.tiles.single().path), tile)
        val first = temp.resolve("a.zip")
        val second = temp.resolve("b.zip")
        LodBundle.pack(source, first)
        LodBundle.pack(source, second)
        assertContentEquals(Files.readAllBytes(first), Files.readAllBytes(second))
        val verified = LodBundle.open(first, digest(first))
        assertEquals(manifest, verified.manifest)
        assertContentEquals(
            byteArrayOf(0, 0),
            verified.readTile(-1, -1).getValue(ColumnPosition(-1, -1)),
        )
        assertFailsWith<IllegalArgumentException> { verified.readTile(3, 4) }
    }

    @Test
    fun `manifest refuses traversal aliases duplicate tiles and wrong totals`() {
        val (m, _) = fixture()
        m.validate()
        for (bad in
            listOf(
                m.copy(tiles = m.tiles.map { it.copy(path = "../tile") }),
                m.copy(tiles = m.tiles + m.tiles),
                m.copy(columns = 2),
                m.copy(sizeBytes = m.sizeBytes + 1),
                m.copy(generatorSha256 = "current"),
                m.copy(format = "dh"),
                m.copy(tileSizeChunks = 4),
                m.copy(tiles = m.tiles.map { it.copy(decodedBytes = Long.MAX_VALUE) }),
            )) assertFailsWith<IllegalArgumentException> { bad.validate() }
    }

    @Test
    fun `tile refuses out of bounds coordinates negative length and trailing data`() {
        for (raw in listOf(raw(0, 0), raw(length = -1), raw() + byteArrayOf(1))) {
            val (m, tile) = fixture(raw)
            assertFailsWith<IllegalArgumentException> { LodTiles.read(m.tiles.single(), tile) }
        }
    }

    @Test
    fun `tile refuses digest size and decompression claims before returning data`() {
        val (m, tile) = fixture()
        val t = m.tiles.single()
        assertFailsWith<IllegalArgumentException> {
            LodTiles.read(t.copy(sha256 = "b".repeat(64)), tile)
        }
        assertFailsWith<IllegalArgumentException> { LodTiles.read(t.copy(sizeBytes = 1), tile) }
        assertFailsWith<IllegalArgumentException> { LodTiles.read(t.copy(decodedBytes = 1), tile) }
        assertFailsWith<IllegalArgumentException> {
            LodTiles.read(t.copy(decodedBytes = t.decodedBytes + 1), tile)
        }
    }

    @Test
    fun `archive refuses extra entries wrong outer hash and oversized manifest`() {
        val (m, tile) = fixture()
        fun archive(extra: Boolean, body: ByteArray = LodJson.write(m)): Path {
            val path = Files.createTempFile(temp, "bad", ".zip")
            ZipOutputStream(Files.newOutputStream(path)).use { zip ->
                for ((name, bytes) in
                    listOf("manifest.json" to body, m.tiles.single().path to tile) +
                        if (extra) listOf("../evil" to byteArrayOf(1)) else emptyList()) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            return path
        }
        val good = archive(false)
        assertFailsWith<IllegalArgumentException> { LodBundle.open(good, "b".repeat(64)) }
        for (path in
            listOf(archive(true), archive(false, ByteArray(1024 * 1024 + 1)))) assertFailsWith<
            IllegalArgumentException
        > {
            LodBundle.open(path, digest(path))
        }
    }

    @Test
    fun `manifest parser rejects duplicate properties unexpected types fractions and extensions`() {
        val (m, _) = fixture()
        val valid = LodJson.write(m).decodeToString()
        for (bad in
            listOf(
                valid.replace(
                    "\"schemaVersion\": 1,",
                    "\"schemaVersion\": 1, \"schemaVersion\": 1,",
                ),
                valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1.5"),
                valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": \"1\""),
                valid.replaceFirst("{", "{\"extra\": true,"),
                valid.replace("2026.10.25b-26.3-grounds", "other-build"),
            )) assertFailsWith<IllegalArgumentException> { LodJson.read(bad.toByteArray()) }
    }
}
