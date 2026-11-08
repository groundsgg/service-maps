package gg.grounds.lod

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.luben.zstd.Zstd
import gg.grounds.blob.BlobMetadata
import gg.grounds.domain.*
import gg.grounds.lod.format.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class LodArtifactStoreTest {
    @TempDir lateinit var temp: Path
    private val sha = "a".repeat(64)
    private val json = jacksonObjectMapper()
    private val claim =
        LodClaim(
            UUID.randomUUID(),
            UUID.randomUUID(),
            MapTrust.UNTRUSTED,
            sha,
            "b".repeat(64),
            "c".repeat(64),
            "d".repeat(64),
            "ghcr.io/test/image@sha256:${"e".repeat(64)}",
            Instant.parse("2026-11-08T12:00:00Z"),
        )

    private class Blobs : LodBlobs {
        val files = mutableMapOf<String, ByteArray>()
        val promoted = mutableListOf<Pair<String, MapTrust>>()

        override fun sourceUrl(sha: String, trust: MapTrust) = "https://source/${trust.name}/$sha"

        override fun workUrl(key: String) = "https://work/$key"

        override fun read(key: String, max: Long): ByteArray =
            files.getValue(key).also { require(it.size <= max) }

        override fun download(key: String, into: Path, max: Long): BlobMetadata {
            val bytes = read(key, max)
            Files.write(into, bytes)
            return BlobMetadata(bytes.size.toLong(), "etag:${digest(bytes)}")
        }

        override fun promote(
            key: String,
            destination: String,
            size: Long,
            trust: MapTrust,
            expectedETag: String,
        ) {
            require(expectedETag == "etag:${digest(files.getValue(key))}")
            promoted += destination to trust
        }
    }

    private fun fixture(blobs: Blobs, c: LodClaim = claim): LodFacts {
        val directory = Files.createTempDirectory(temp, "artifact")
        val raw =
            ByteArrayOutputStream()
                .also { bytes ->
                    DataOutputStream(bytes).use { out ->
                        out.writeBytes("GLODT001")
                        out.writeInt(0)
                        out.writeInt(0)
                        out.writeInt(1)
                        out.writeInt(0)
                        out.writeInt(0)
                        out.writeInt(2)
                        out.write(byteArrayOf(0, 0))
                    }
                }
                .toByteArray()
        val compressed = Zstd.compress(raw)
        val tile =
            LodTile(
                0,
                0,
                "tiles/0.0.tile.zst",
                digest(compressed),
                compressed.size.toLong(),
                raw.size.toLong(),
                1,
            )
        val manifest =
            LodManifest(
                sourceBundleSha256 = c.sourceBundleSha256,
                registrySha256 = c.registrySha256,
                generatorSha256 = c.generatorSha256,
                columns = 1,
                sizeBytes = tile.sizeBytes,
                columnBytes = 2,
                tiles = listOf(tile),
            )
        Files.createDirectories(directory.resolve("tiles"))
        Files.write(directory.resolve(tile.path), compressed)
        val bytes = LodJson.write(manifest)
        Files.write(directory.resolve("manifest.json"), bytes)
        val zip = directory.resolve("bundle.zip")
        LodBundle.pack(directory, zip)
        val facts = LodFacts(digest(zip), Files.size(zip), digest(bytes), bytes.size.toLong(), 1)
        val result =
            LodJobResult(
                format = "lss20",
                attempt = c.attempt.toString(),
                mapBundleSha256 = c.mapBundleSha256,
                sourceBundleSha256 = c.sourceBundleSha256,
                generatorSha256 = c.generatorSha256,
                workerImageSha256 = c.workerImageSha256,
                bundleSha256 = facts.bundleSha256,
                bundleBytes = facts.bundleBytes,
                manifestSha256 = facts.manifestSha256,
                manifestBytes = facts.manifestBytes,
                registrySha256 = c.registrySha256,
                columns = 1,
            )
        blobs.files[c.workKey("bundle.zip")] = Files.readAllBytes(zip)
        blobs.files[c.workKey("manifest.json")] = bytes
        blobs.files[c.workKey("result.json")] = json.writeValueAsBytes(result)
        return facts
    }

    @Test
    fun `acceptance verifies all files privately and publication preserves creator trust`() {
        val blobs = Blobs()
        val facts = fixture(blobs)
        val store = LodArtifactStore(blobs, json)
        assertEquals(facts, store.accept(claim))
        assertTrue(blobs.promoted.isEmpty())
        store.publish(claim.copy(facts = facts))
        assertEquals(
            listOf(
                "lod/lss20/${facts.bundleSha256}/bundle.zip" to MapTrust.UNTRUSTED,
                "lod/lss20/${facts.manifestSha256}/manifest.json" to MapTrust.UNTRUSTED,
            ),
            blobs.promoted,
        )
        val request = store.request(claim)
        assertEquals("https://source/UNTRUSTED/${claim.sourceBundleSha256}", request.sourceUrl)
    }

    @Test
    fun `another attempt source registry or generator cannot be attached to this map`() {
        for (wrong in
            listOf(
                claim.copy(attempt = UUID.randomUUID()),
                claim.copy(sourceBundleSha256 = "f".repeat(64)),
                claim.copy(registrySha256 = "f".repeat(64)),
                claim.copy(generatorSha256 = "f".repeat(64)),
                claim.copy(mapBundleSha256 = "f".repeat(64)),
            )) {
            val blobs = Blobs()
            fixture(blobs, wrong)
            // Present it under this attempt's assigned keys: matching keys alone prove nothing.
            if (wrong.attempt != claim.attempt)
                for (name in listOf("result.json", "manifest.json", "bundle.zip")) blobs.files[
                        claim.workKey(name)] = blobs.files.getValue(wrong.workKey(name))
            assertFailsWith<LodInvalidResult> { LodArtifactStore(blobs, json).accept(claim) }
            assertTrue(blobs.promoted.isEmpty())
        }
    }

    @Test
    fun `corrupt incomplete oversized or extended result cannot become ready`() {
        for (mutate in
            listOf<(Blobs) -> Unit>(
                { b -> b.files[claim.workKey("bundle.zip")] = byteArrayOf(1) },
                { b -> b.files[claim.workKey("manifest.json")] = "{}".toByteArray() },
                { b -> b.files[claim.workKey("result.json")] = ByteArray(65537) },
                { b ->
                    b.files[claim.workKey("result.json")] =
                        (b.files
                                .getValue(claim.workKey("result.json"))
                                .decodeToString()
                                .dropLast(1) + ",\"future\":1}")
                            .toByteArray()
                },
            )) {
            val blobs = Blobs()
            fixture(blobs)
            mutate(blobs)
            assertFailsWith<LodInvalidResult> { LodArtifactStore(blobs, json).accept(claim) }
            assertTrue(blobs.promoted.isEmpty())
        }
    }
}
