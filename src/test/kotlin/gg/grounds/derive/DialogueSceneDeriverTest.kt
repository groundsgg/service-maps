package gg.grounds.derive

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import com.sun.net.httpserver.HttpServer
import gg.grounds.catalog.DialogueProfileLoader
import gg.grounds.domain.*
import gg.grounds.scene.format.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class DialogueSceneDeriverTest {
    @TempDir lateinit var root: Path
    private val image = "worker@sha256:${"a".repeat(64)}"

    private fun resource(path: String) =
        javaClass.getResourceAsStream("/derive/dialogue/$path")!!.use { it.readAllBytes() }

    private fun digest(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun archive(files: Map<String, ByteArray>) =
        ByteArrayOutputStream()
            .also { out ->
                ZstdOutputStream(out).use { zstd ->
                    TarArchiveOutputStream(zstd).use { tar ->
                        files.toSortedMap().forEach { (path, bytes) ->
                            tar.putArchiveEntry(
                                TarArchiveEntry(path).apply {
                                    size = bytes.size.toLong()
                                    setModTime(0L)
                                }
                            )
                            tar.write(bytes)
                            tar.closeArchiveEntry()
                        }
                    }
                }
            }
            .toByteArray()

    private fun entries(bundle: Path): Map<String, ByteArray> =
        Files.newInputStream(bundle).use { input ->
            ZstdInputStream(input).use { zstd ->
                TarArchiveInputStream(zstd).use { tar ->
                    buildMap {
                        while (true) {
                            val entry = tar.nextEntry ?: break
                            if (!entry.isDirectory) put(entry.name, tar.readAllBytes())
                        }
                    }
                }
            }
        }

    private fun derive(
        files: Map<String, ByteArray>,
        context: DialogueDeriveContext,
        resolver: SceneCatalogResolver,
        normalization: (() -> AssetCatalog)? = null,
    ): SceneDerivationOutcome {
        val bytes = archive(files)
        return SceneDeriver(
                resolver,
                dialogueContext = context,
                normalizationAssets = normalization,
            )
            .derive(
                ByteArrayInputStream(bytes),
                digest(bytes),
                Files.createTempDirectory(root, "derive-"),
            )
    }

    private fun story(
        action: Boolean = false,
        otherStartProfile: Boolean = false,
        profileAssetVersion: String = "1",
        block: (Map<String, ByteArray>, DialogueDeriveContext, SceneCatalogResolver) -> Unit,
    ) {
        val mapper = ObjectMapper()
        val host = mapper.readTree(resource("context/host-catalog.json")) as ObjectNode
        val definition = mapper.readTree(resource("dialogues/mechanic.json")) as ObjectNode
        if (action) {
            (host.path("actions") as ObjectNode).set<ObjectNode>(
                "test:declare",
                mapper.readTree("""{"arguments":{},"durable":false}""") as ObjectNode,
            )
            definition.put("entry", "perform")
            (definition.path("nodes") as ObjectNode).set<ObjectNode>(
                "perform",
                mapper.readTree(
                    """{"type":"action","action":"test:declare","arguments":{},"onSuccess":"greeting","onRejected":"greeting","onUnavailable":"greeting","onUncertain":"greeting"}"""
                ) as ObjectNode,
            )
        }
        val hostBytes = mapper.writeValueAsBytes(host)
        val profileBytes =
            mapper.writeValueAsBytes(
                mapOf(
                    "schemaVersion" to 1,
                    "id" to "test",
                    "version" to "1",
                    "hostCatalogJson" to hostBytes.decodeToString(),
                    "assetCatalog" to
                        mapOf("id" to "test:assets", "version" to profileAssetVersion),
                    "actionCatalogs" to listOf(mapOf("id" to "test:actions", "version" to "1")),
                    "startActions" to emptyMap<String, Any>(),
                    "npcAssets" to
                        mapOf("test:mechanic" to mapOf("gestures" to mapOf("demo:wave" to "wave"))),
                )
            )
        val otherBytes =
            mapper.writeValueAsBytes(
                (mapper.readTree(profileBytes) as ObjectNode).apply {
                    put("id", "other")
                    set<ObjectNode>(
                        "startActions",
                        mapper.readTree(
                            """{"test:other_start":{"dialogueArgument":"dialogue","speakerArgument":"speaker"}}"""
                        ) as ObjectNode,
                    )
                }
            )
        val otherPin =
            DialogueProfilePin(
                "other",
                "1",
                digest(otherBytes),
                otherBytes.size.toLong(),
                URI("https://cdn.test/profile-other.json"),
            )
        val otherKey = ActionKey("test:other_start")
        val pin =
            DialogueProfilePin(
                "test",
                "1",
                digest(profileBytes),
                profileBytes.size.toLong(),
                URI("https://cdn.test/profile.json"),
            )
        val npc =
            Npc(
                LocalId("guide"),
                null,
                Transform(ORIGIN, ZERO_ROTATION, Vec3(1.0, 1.0, 1.0)),
                body = AssetKey("test:mechanic"),
                label = null,
                labelOffset = ORIGIN,
                look = LookBehavior.Fixed,
                initialAnimation = null,
                interactionBounds = LocalBounds(ORIGIN, Vec3(1.0, 1.0, 1.0)),
                proximity = null,
                bindings =
                    if (otherStartProfile)
                        listOf(
                            TriggerBinding(
                                SceneTrigger.RIGHT_CLICK,
                                emptyList(),
                                0,
                                0,
                                listOf(
                                    ApplicationAction(
                                        otherKey,
                                        mapOf(
                                            LocalId("dialogue") to StringArgument("missing"),
                                            LocalId("speaker") to StringArgument("missing"),
                                        ),
                                    )
                                ),
                            )
                        )
                    else emptyList(),
            )
        val scene =
            SceneDocument(
                1,
                SceneId("test:map"),
                SceneMetadata("Map", null, emptySet()),
                SceneCatalogReferences(
                    gg.grounds.scene.format.CatalogReference(CatalogId("test:assets"), "1"),
                    gg.grounds.scene.format.CatalogReference(CatalogId("test:actions"), "1"),
                ),
                emptyList(),
                listOf(npc),
            )
        val sceneBytes = (SceneJson.encode(scene) as SceneEncodeResult.Success).bytes
        val body = AssetKey("test:mechanic")
        val catalogs =
            ResolvedSceneCatalogs(
                AssetCatalog(
                    CatalogId("test:assets"),
                    "1",
                    CatalogVersionRange(CatalogId("test:assets"), "1", "1"),
                    mapOf(
                        body to
                            AssetDefinition(
                                body,
                                AssetKind.NPC_BODY,
                                setOf(LocalId("wave")),
                                null,
                                emptyMap(),
                            )
                    ),
                ),
                ActionCatalog(
                    CatalogId("test:actions"),
                    "1",
                    if (otherStartProfile)
                        mapOf(
                            otherKey to
                                ActionDefinition(
                                    otherKey,
                                    "Start",
                                    "",
                                    listOf("dialogue", "speaker").associate { name ->
                                        LocalId(name) to
                                            ActionParameter(
                                                LocalId(name),
                                                ActionParameterType.STRING,
                                                true,
                                                null,
                                                NoConstraints,
                                            )
                                    },
                                )
                        )
                    else emptyMap(),
                ),
            )
        val files =
            mapOf(
                "scene.json" to sceneBytes,
                "dialogues/mechanic.json" to mapper.writeValueAsBytes(definition),
                "context/host-catalog.json" to hostBytes,
                "texts/en.json" to resource("texts/en.json"),
                "texts/de.json" to resource("texts/de.json"),
                "grounds/dialogue.json" to
                    mapper.writeValueAsBytes(
                        mapOf(
                            "schemaVersion" to 1,
                            "profile" to
                                mapOf("id" to "test", "version" to "1", "sha256" to pin.sha256),
                            "speakers" to mapOf("mechanic" to "guide"),
                        )
                    ),
            )
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/profile") { exchange ->
            val bytes =
                if (exchange.requestURI.path == "/profile-other.json") otherBytes else profileBytes
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val transfer =
                object : WorkerHttpTransfer(true, 1048576) {
                    override fun download(uri: URI, destination: Path): String =
                        super.download(
                            URI("http://127.0.0.1:${server.address.port}${uri.path}"),
                            destination,
                        )
                }
            return block(
                files,
                DialogueDeriveContext(
                    DeriveExecutionContract(
                        2,
                        image,
                        if (otherStartProfile) listOf(pin, otherPin) else listOf(pin),
                        normalizationCatalog =
                            if (profileAssetVersion == "2")
                                gg.grounds.domain.CatalogReference("test:assets", "2")
                            else null,
                    ),
                    DialogueProfileLoader(transfer),
                ),
                SceneCatalogResolver { catalogs },
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun pureMapV2EmitsNoneProof() {
        val execution = DeriveExecutionContract(2, image, emptyList())
        val result =
            assertIs<SceneDerivationOutcome.Valid>(
                derive(
                    mapOf("level.dat" to "world".toByteArray()),
                    DialogueDeriveContext(execution, DialogueProfileLoader()),
                    SceneCatalogResolver { error("no Scene") },
                )
            )
        assertEquals(DialogueStatus.NONE, result.dialogue!!.status)
        assertEquals(execution.fingerprint(), result.dialogue.proof!!.executionSha256)
        assertEquals(2, CanonicalJson.readManifest(result.manifest).schemaVersion)
    }

    @Test
    fun registeredStartFromOtherApplicableProfileCannotEscapeValidation() =
        story(otherStartProfile = true) { files, context, resolver ->
            val result = assertIs<SceneDerivationOutcome.Invalid>(derive(files, context, resolver))
            assertTrue(
                result.problems.any {
                    it.code == "DIALOGUE_TRIGGER_PROFILE_MISMATCH" && it.file == "scene.json"
                }
            )
        }

    @Test
    fun validStoryPreservesSourceBytes() = story { files, context, resolver ->
        val result = assertIs<SceneDerivationOutcome.Valid>(derive(files, context, resolver))
        val contents = entries(result.bundle.path)
        files
            .filterKeys { it != "scene.json" }
            .forEach { (path, bytes) -> assertContentEquals(bytes, contents[path]) }
        assertEquals(DialogueStatus.VALID, result.dialogue!!.status)
        assertEquals(context.execution.fingerprint(), result.dialogue.proof!!.executionSha256)
        assertEquals(setOf("demo:mechanic"), result.dialogue.proof!!.dialogueIds.toSet())
        assertEquals(result.dialogue, CanonicalJson.readManifest(result.manifest).dialogue)
    }

    @Test
    fun badStoryWritesNoSuccessArtifacts() = story { files, context, resolver ->
        val bad =
            files +
                mapOf(
                    "dialogues/mechanic.json" to
                        files
                            .getValue("dialogues/mechanic.json")
                            .decodeToString()
                            .replace("\"next\":\"done\"", "\"next\":\"missing\"")
                            .toByteArray()
                )
        val result = assertIs<SceneDerivationOutcome.Invalid>(derive(bad, context, resolver))
        assertTrue(result.problems.any { it.severity == "ERROR" })
        assertFalse(
            Files.walk(root).use { paths ->
                paths.anyMatch {
                    it.fileName.toString() == "bundle.tar.zst" ||
                        it.fileName.toString() == "manifest.json"
                }
            }
        )
    }

    @Test
    fun absentConfiguredProfileIsSystemFailure() = story { files, _, resolver ->
        assertThrows<DialogueProfileConfigurationException> {
            derive(
                files,
                DialogueDeriveContext(
                    DeriveExecutionContract(2, image, emptyList()),
                    DialogueProfileLoader(),
                ),
                resolver,
            )
        }
    }

    @Test
    fun workerDoesNotExecuteDialogueActions() =
        story(action = true) { files, context, resolver ->
            // A declaration validates without a runtime provider, player session or action
            // registry.
            val result = assertIs<SceneDerivationOutcome.Valid>(derive(files, context, resolver))
            assertTrue(
                result.dialogue!!.proof!!.requirements.any {
                    it.kind == "action" && it.key == "test:declare"
                }
            )
        }

    @Test
    fun manifestDoesNotHashItself() = story { files, context, resolver ->
        val result = assertIs<SceneDerivationOutcome.Valid>(derive(files, context, resolver))
        val manifest = CanonicalJson.readManifest(result.manifest)
        assertFalse("grounds/derived-manifest.json" in manifest.bundleDigestInputs)
        assertFalse(
            manifest.dialogue!!.proof!!.files.any { it.path == "grounds/derived-manifest.json" }
        )
    }

    @Test
    fun finalNormalizedCatalogAndSceneAreUsedTogether() =
        story(profileAssetVersion = "2") { files, context, resolver ->
            val original =
                resolver
                    .resolve(
                        SceneCatalogReferences(
                            gg.grounds.scene.format.CatalogReference(CatalogId("test:assets"), "1"),
                            gg.grounds.scene.format.CatalogReference(CatalogId("test:actions"), "1"),
                        )
                    )
                    .assets
            val target =
                AssetCatalog(
                    original.id,
                    "2",
                    CatalogVersionRange(original.id, "1", "2"),
                    original.assets,
                )
            val result =
                assertIs<SceneDerivationOutcome.Valid>(derive(files, context, resolver) { target })
            assertEquals("2", result.scene.assetCatalog!!.version)
            assertEquals("2", result.dialogue!!.proof!!.assetCatalog!!.version)
            assertNotEquals(
                digest(files.getValue("scene.json")),
                result.dialogue.proof!!.sceneSha256,
            )
        }

    @Test
    fun configuredProfileOutageCannotAttestAbsence() = story { _, context, _ ->
        val failing =
            DialogueProfileLoader(
                object : WorkerHttpTransfer(false, 1048576) {
                    override fun download(uri: URI, destination: Path): String =
                        throw WorkerTransferException("profile unavailable")
                }
            )
        assertThrows<gg.grounds.catalog.CatalogTransferException> {
            derive(
                mapOf("level.dat" to "world".toByteArray()),
                context.copy(profileLoader = failing),
                SceneCatalogResolver { error("no Scene") },
            )
        }
    }
}
