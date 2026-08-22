package gg.grounds.derive

import com.github.luben.zstd.ZstdOutputStream
import gg.grounds.scene.format.ActionCatalog
import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.CatalogId
import gg.grounds.scene.format.CatalogVersionRange
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class SceneDeriverTest {
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
        assertArrayEquals(one.bundle, two.bundle)
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
            SafeTarZstdReader().read(ByteArrayInputStream(result.bundle), root).associateBy {
                it.path
            }
        assertEquals(true, result.scene.present)
        assertEquals(
            result.manifest.toList(),
            Files.readAllBytes(files.getValue("grounds/derived-manifest.json").file).toList(),
        )
        assertEquals(true, Files.readAllBytes(files.getValue("scene.json").file).isNotEmpty())
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
