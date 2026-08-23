package gg.grounds.derive

import com.github.luben.zstd.ZstdOutputStream
import gg.grounds.catalog.CatalogTransferException
import gg.grounds.scene.format.ActionCatalog
import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.CatalogId
import gg.grounds.scene.format.CatalogVersionRange
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SceneDeriverTest {
    @Test
    fun `propagates archive source io failures as system failures`() {
        val source =
            object : ByteArrayInputStream(ByteArray(0)) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    throw IOException("source unavailable")
            }

        assertThrows(IOException::class.java) {
            SceneDeriver(emptyCatalogResolver)
                .derive(source, "a".repeat(64), Files.createTempDirectory("derive"))
        }
    }

    @Test
    fun `identical no-scene sources produce identical bundles and NONE`() {
        val source = archive(mapOf("level.dat" to "world".encodeToByteArray()))
        val one =
            assertInstanceOf(
                SceneDerivationOutcome.Valid::class.java,
                SceneDeriver(emptyCatalogResolver)
                    .derive(
                        ByteArrayInputStream(source),
                        "a".repeat(64),
                        Files.createTempDirectory("derive"),
                    ),
            )
        val two =
            assertInstanceOf(
                SceneDerivationOutcome.Valid::class.java,
                SceneDeriver(emptyCatalogResolver)
                    .derive(
                        ByteArrayInputStream(source),
                        "a".repeat(64),
                        Files.createTempDirectory("derive"),
                    ),
            )
        assertEquals(DerivedScene(false, null, null, null, null, emptyList()), one.scene)
        assertArrayEquals(Files.readAllBytes(one.bundle.path), Files.readAllBytes(two.bundle.path))
        assertEquals(one.bundle.sha256, two.bundle.sha256)
        assertEquals(one.bundle.size, two.bundle.size)
        assertEquals(one.bundle.size, Files.size(one.bundle.path))
        assertEquals(one.bundle.sha256, digest(Files.readAllBytes(one.bundle.path)))
        assertArrayEquals(one.manifest, two.manifest)
    }

    @Test
    fun `malformed authored scene is INVALID and is never omitted`() {
        val result =
            SceneDeriver(emptyCatalogResolver)
                .derive(
                    ByteArrayInputStream(archive(mapOf("scene.json" to "{".encodeToByteArray()))),
                    "a".repeat(64),
                    Files.createTempDirectory("derive"),
                )
        val failure = assertInstanceOf(SceneDerivationOutcome.Invalid::class.java, result)
        assertEquals("CONTENT", failure.problems.single().scope.name)
    }

    @Test
    fun `catalog transfer escapes to the worker system boundary`() {
        val failure =
            assertThrows(CatalogTransferException::class.java) {
                SceneDeriver(
                        SceneCatalogResolver { throw CatalogTransferException("catalog offline") }
                    )
                    .derive(
                        ByteArrayInputStream(
                            archive(mapOf("scene.json" to validScene.encodeToByteArray()))
                        ),
                        "a".repeat(64),
                        Files.createTempDirectory("derive"),
                    )
            }

        assertEquals("catalog offline", failure.message)
    }

    @Test
    fun `catalog content becomes a structured nonretryable scene problem`() {
        val result =
            SceneDeriver(
                    SceneCatalogResolver { throw IllegalArgumentException("catalog malformed") }
                )
                .derive(
                    ByteArrayInputStream(
                        archive(mapOf("scene.json" to validScene.encodeToByteArray()))
                    ),
                    "a".repeat(64),
                    Files.createTempDirectory("derive"),
                )

        val invalid = assertInstanceOf(SceneDerivationOutcome.Invalid::class.java, result)
        assertEquals("CONTENT", invalid.problems.single().scope.name)
        assertEquals("SCENE", invalid.problems.single().code)
    }

    @Test
    fun `directory scene is INVALID and produces no bundle`() {
        val tar = ByteArrayOutputStream()
        TarArchiveOutputStream(tar).use { output ->
            output.putArchiveEntry(TarArchiveEntry("scene.json", TarArchiveEntry.LF_DIR))
            output.closeArchiveEntry()
        }
        val compressed =
            ByteArrayOutputStream()
                .also { output -> ZstdOutputStream(output).use { it.write(tar.toByteArray()) } }
                .toByteArray()
        val result =
            SceneDeriver(emptyCatalogResolver)
                .derive(
                    ByteArrayInputStream(compressed),
                    "a".repeat(64),
                    Files.createTempDirectory("derive"),
                )
        assertInstanceOf(SceneDerivationOutcome.Invalid::class.java, result)
    }

    @Test
    fun `scene-specific byte limit is a content failure`() {
        val result =
            SceneDeriver(emptyCatalogResolver, ArchiveLimits(maxSceneBytes = 1))
                .derive(
                    ByteArrayInputStream(archive(mapOf("scene.json" to "{}".encodeToByteArray()))),
                    "a".repeat(64),
                    Files.createTempDirectory("derive"),
                )

        val failure = assertInstanceOf(SceneDerivationOutcome.Invalid::class.java, result)
        assertEquals("SCENE", failure.problems.single().code)
    }

    @Test
    fun `valid scene is canonicalized into the root and manifest is embedded`() {
        val result =
            assertInstanceOf(
                SceneDerivationOutcome.Valid::class.java,
                SceneDeriver(catalogResolver)
                    .derive(
                        ByteArrayInputStream(
                            archive(mapOf("scene.json" to validScene.encodeToByteArray()))
                        ),
                        "a".repeat(64),
                        Files.createTempDirectory("derive"),
                    ),
            )
        val root = Files.createTempDirectory("bundle")
        val files =
            SafeTarZstdReader().read(Files.newInputStream(result.bundle.path), root).associateBy {
                it.path
            }
        assertEquals(true, result.scene.present)
        assertEquals(
            result.manifest.toList(),
            Files.readAllBytes(files.getValue("grounds/derived-manifest.json").file).toList(),
        )
        assertEquals(true, Files.readAllBytes(files.getValue("scene.json").file).isNotEmpty())
    }

    @Test
    fun `canonical scene temp never collides with an authored logical filename`() {
        val result =
            assertInstanceOf(
                SceneDerivationOutcome.Valid::class.java,
                SceneDeriver(catalogResolver)
                    .derive(
                        ByteArrayInputStream(
                            archive(
                                mapOf(
                                    "scene.json" to validScene.encodeToByteArray(),
                                    "canonical-scene.json" to "authored".encodeToByteArray(),
                                )
                            )
                        ),
                        "a".repeat(64),
                        Files.createTempDirectory("derive"),
                    ),
            )
        val entries =
            SafeTarZstdReader()
                .read(Files.newInputStream(result.bundle.path), Files.createTempDirectory("bundle"))
                .associateBy { it.path }

        assertEquals(
            "authored",
            Files.readAllBytes(entries.getValue("canonical-scene.json").file).decodeToString(),
        )
        assertEquals(true, Files.readAllBytes(entries.getValue("scene.json").file).isNotEmpty())
    }

    private fun archive(files: Map<String, ByteArray>): ByteArray {
        val tar = ByteArrayOutputStream()
        TarArchiveOutputStream(tar).use { output ->
            files.forEach { (name, bytes) ->
                output.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                output.write(bytes)
                output.closeArchiveEntry()
            }
        }
        return ByteArrayOutputStream()
            .also { compressed -> ZstdOutputStream(compressed).use { it.write(tar.toByteArray()) } }
            .toByteArray()
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val emptyCatalogResolver = SceneCatalogResolver {
        error("scene catalog should not resolve")
    }
    private val catalogResolver = SceneCatalogResolver {
        ResolvedSceneCatalogs(
            AssetCatalog(
                CatalogId("test:assets"),
                "1",
                CatalogVersionRange(CatalogId("test:assets"), "1", "1"),
                emptyMap(),
            ),
            ActionCatalog(CatalogId("test:actions"), "1", emptyMap()),
        )
    }
    private val validScene =
        """{"schemaVersion":1,"id":"test:scene","metadata":{"name":"Scene","description":null,"tags":[]},"catalogs":{"assets":{"id":"test:assets","version":"1"},"actions":{"id":"test:actions","version":"1"}},"groups":[],"elements":[]}"""
}
