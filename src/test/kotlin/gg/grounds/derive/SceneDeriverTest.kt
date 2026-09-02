package gg.grounds.derive

import com.github.luben.zstd.ZstdOutputStream
import gg.grounds.catalog.CatalogContentException
import gg.grounds.catalog.CatalogTransferException
import gg.grounds.scene.format.*
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
    fun `catalog content exception becomes a structured nonretryable scene problem`() {
        val result =
            SceneDeriver(
                    SceneCatalogResolver {
                        throw CatalogContentException("catalog identity does not match")
                    }
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
        assertEquals("scene.json", invalid.problems.single().path)
        assertEquals(
            "scene catalog could not be resolved: catalog identity does not match",
            invalid.problems.single().message,
        )
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

    @Test
    fun `revision two navigator scene is valid and retains its exact catalog reference`() {
        val result = deriveNavigatorScene("2")

        val valid = assertInstanceOf(SceneDerivationOutcome.Valid::class.java, result)
        assertEquals("grounds:actions", valid.scene.actionCatalog?.id)
        assertEquals("2", valid.scene.actionCatalog?.version)
        assertEquals(listOf("grounds:lobby/open_navigator"), valid.scene.requiredActions)
    }

    @Test
    fun `revision one empty action catalog scene remains valid`() {
        val result = deriveNavigatorScene("1", includeNavigator = false)

        val valid = assertInstanceOf(SceneDerivationOutcome.Valid::class.java, result)
        assertEquals("1", valid.scene.actionCatalog?.version)
        assertEquals(emptyList<String>(), valid.scene.requiredActions)
    }

    @Test
    fun `wrong navigator key and nonempty navigator arguments are scene invalid`() {
        listOf(
                ApplicationAction(ActionKey("grounds:lobby/wrong"), emptyMap()),
                ApplicationAction(
                    ActionKey("grounds:lobby/open_navigator"),
                    mapOf(LocalId("unexpected") to StringArgument("value")),
                ),
            )
            .zip(listOf("UNKNOWN_ACTION", "INVALID_ACTION_ARGUMENT"))
            .forEach { (action, code) ->
                val invalid =
                    assertInstanceOf(
                        SceneDerivationOutcome.Invalid::class.java,
                        deriveNavigatorScene("2", action),
                    )
                assertEquals("CONTENT", invalid.problems.single().scope.name)
                assertEquals(code, invalid.problems.single().code)
            }
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

    private fun deriveNavigatorScene(
        revision: String,
        action: ApplicationAction =
            ApplicationAction(ActionKey("grounds:lobby/open_navigator"), emptyMap()),
        includeNavigator: Boolean = true,
    ): SceneDerivationOutcome {
        val document =
            SceneDocument(
                1,
                SceneId("grounds:lobby"),
                SceneMetadata("Lobby", null, emptySet()),
                SceneCatalogReferences(
                    CatalogReference(CatalogId("grounds:assets"), "1"),
                    CatalogReference(CatalogId("grounds:actions"), revision),
                ),
                emptyList(),
                if (includeNavigator)
                    listOf(
                        Npc(
                            LocalId("navigator"),
                            null,
                            Transform(ORIGIN, ZERO_ROTATION, Vec3(1.0, 1.0, 1.0)),
                            body = AssetKey("grounds:navigator"),
                            label = null,
                            labelOffset = ORIGIN,
                            look = LookBehavior.Fixed,
                            initialAnimation = null,
                            interactionBounds = LocalBounds(ORIGIN, Vec3(1.0, 1.0, 1.0)),
                            proximity = null,
                            bindings =
                                listOf(
                                    TriggerBinding(
                                        SceneTrigger.RIGHT_CLICK,
                                        emptyList(),
                                        0,
                                        0,
                                        listOf(action),
                                    )
                                ),
                        )
                    )
                else emptyList(),
            )
        val bytes =
            assertInstanceOf(SceneEncodeResult.Success::class.java, SceneJson.encode(document))
                .bytes
        return SceneDeriver(
                SceneCatalogResolver {
                    ResolvedSceneCatalogs(navigatorAssets, navigatorActions(revision))
                }
            )
            .derive(
                ByteArrayInputStream(archive(mapOf("scene.json" to bytes))),
                "a".repeat(64),
                Files.createTempDirectory("derive"),
            )
    }

    private val navigatorAssets =
        AssetCatalog(
            CatalogId("grounds:assets"),
            "1",
            CatalogVersionRange(CatalogId("grounds:assets"), "1", "1"),
            mapOf(
                AssetKey("grounds:navigator") to
                    AssetDefinition(
                        AssetKey("grounds:navigator"),
                        AssetKind.NPC_BODY,
                        emptySet(),
                        null,
                        emptyMap(),
                    )
            ),
        )

    private fun navigatorActions(revision: String) =
        ActionCatalog(
            CatalogId("grounds:actions"),
            revision,
            if (revision == "2")
                mapOf(
                    ActionKey("grounds:lobby/open_navigator") to
                        ActionDefinition(
                            ActionKey("grounds:lobby/open_navigator"),
                            "Open navigator",
                            "",
                            emptyMap(),
                        )
                )
            else emptyMap(),
        )

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
