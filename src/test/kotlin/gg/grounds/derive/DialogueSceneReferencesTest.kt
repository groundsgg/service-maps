package gg.grounds.derive

import gg.grounds.domain.CatalogReference as DomainCatalogReference
import gg.grounds.domain.DialogueProfileReference
import gg.grounds.scene.format.*
import kotlin.test.*
import org.junit.jupiter.api.Test

class DialogueSceneReferencesTest {
    private val body = AssetKey("test:mechanic")
    private val transform = Transform(ORIGIN, ZERO_ROTATION, Vec3(1.0, 1.0, 1.0))
    private val start = ActionKey("test:start_dialogue")

    private fun resource(path: String) =
        javaClass.getResourceAsStream("/derive/dialogue/$path")!!.use { it.readAllBytes() }

    private fun profile(
        assetVersion: String = "1",
        gestures: Map<String, String> = mapOf("demo:wave" to "wave"),
    ) =
        DialogueHostProfile(
            "test",
            "1",
            resource("context/host-catalog.json"),
            DomainCatalogReference("test:assets", assetVersion),
            setOf(DomainCatalogReference("test:actions", "1")),
            mapOf(start.value to DialogueStartAction("dialogue", "speaker")),
            mapOf(body.value to gestures),
        )

    private fun content(profile: DialogueHostProfile = profile()) =
        DialogueBundleValidation.validate(
            DialogueInputScan.Present(
                "{}".toByteArray(),
                resource("context/host-catalog.json"),
                mapOf("mechanic.json" to resource("dialogues/mechanic.json")),
                mapOf("en" to resource("texts/en.json"), "de" to resource("texts/de.json")),
                emptyList(),
            ),
            profile,
        )

    private fun bindings(roles: Map<String, String> = mapOf("mechanic" to "guide")) =
        DialogueBindings(DialogueProfileReference("test", "1", "a".repeat(64)), roles)

    private fun npc(actions: List<SceneAction> = emptyList()) =
        Npc(
            LocalId("guide"),
            null,
            transform,
            body = body,
            label = null,
            labelOffset = ORIGIN,
            look = LookBehavior.Fixed,
            initialAnimation = null,
            interactionBounds = LocalBounds(ORIGIN, Vec3(1.0, 1.0, 1.0)),
            proximity = null,
            bindings =
                if (actions.isEmpty()) emptyList()
                else listOf(TriggerBinding(SceneTrigger.RIGHT_CLICK, emptyList(), 0, 0, actions)),
        )

    private fun scene(elements: List<SceneElement> = listOf(npc()), assetVersion: String = "1") =
        SceneDocument(
            1,
            SceneId("test:map"),
            SceneMetadata("test", null, emptySet()),
            SceneCatalogReferences(
                CatalogReference(CatalogId("test:assets"), assetVersion),
                CatalogReference(CatalogId("test:actions"), "1"),
            ),
            emptyList(),
            elements,
        )

    private fun catalogs(
        assetVersion: String = "1",
        animations: Set<LocalId> = setOf(LocalId("wave")),
    ) =
        ResolvedSceneCatalogs(
            AssetCatalog(
                CatalogId("test:assets"),
                assetVersion,
                CatalogVersionRange(CatalogId("test:assets"), "1", "2"),
                mapOf(
                    body to AssetDefinition(body, AssetKind.NPC_BODY, animations, null, emptyMap())
                ),
            ),
            ActionCatalog(
                CatalogId("test:actions"),
                "1",
                mapOf(
                    start to
                        ActionDefinition(
                            start,
                            "Start",
                            "",
                            mapOf(
                                LocalId("dialogue") to
                                    ActionParameter(
                                        LocalId("dialogue"),
                                        ActionParameterType.STRING,
                                        true,
                                        null,
                                        NoConstraints,
                                    ),
                                LocalId("speaker") to
                                    ActionParameter(
                                        LocalId("speaker"),
                                        ActionParameterType.STRING,
                                        true,
                                        null,
                                        NoConstraints,
                                    ),
                            ),
                        )
                ),
            ),
        )

    @Test
    fun missingRoleAndNonNpcElementFail() {
        assertTrue(
            DialogueSceneReferences.validate(
                    content(),
                    bindings(emptyMap()),
                    profile(),
                    scene(),
                    catalogs(),
                )
                .any { it.severity == "ERROR" }
        )
        val prop = Prop(LocalId("guide"), null, transform, asset = body, initialAnimation = null)
        assertTrue(
            DialogueSceneReferences.validate(
                    content(),
                    bindings(),
                    profile(),
                    scene(listOf(prop)),
                    catalogs(),
                )
                .any { it.code == "DIALOGUE_NPC_BINDING" }
        )
    }

    @Test
    fun gestureMustExistOnBoundBody() {
        assertTrue(
            DialogueSceneReferences.validate(
                    content(),
                    bindings(),
                    profile(),
                    scene(),
                    catalogs(animations = emptySet()),
                )
                .any { it.code == "DIALOGUE_GESTURE_INVALID" }
        )
    }

    @Test
    fun knownOptionalGestureMayReduceWithoutInventingAnimation() {
        val p = profile(gestures = emptyMap())
        val result =
            DialogueSceneReferences.validate(content(p), bindings(), p, scene(), catalogs())
        assertTrue(
            result.any { it.code == "DIALOGUE_GESTURE_UNAVAILABLE" && it.severity == "WARNING" }
        )
        assertFalse(result.any { it.severity == "ERROR" })
    }

    @Test
    fun profileCatalogMustMatchFinalScene() {
        assertTrue(
            DialogueSceneReferences.validate(
                    content(),
                    bindings(),
                    profile(),
                    scene(assetVersion = "2"),
                    catalogs("2"),
                )
                .any { it.code == "DIALOGUE_CATALOG_MISMATCH" }
        )
    }

    @Test
    fun triggerNeedsKnownDialogueAndRole() {
        listOf("missing" to "mechanic", "demo:mechanic" to "unknown").forEach { (dialogue, speaker)
            ->
            val action =
                ApplicationAction(
                    start,
                    mapOf(
                        LocalId("dialogue") to StringArgument(dialogue),
                        LocalId("speaker") to StringArgument(speaker),
                    ),
                )
            assertTrue(
                DialogueSceneReferences.validate(
                        content(),
                        bindings(),
                        profile(),
                        scene(listOf(npc(listOf(action)))),
                        catalogs(),
                    )
                    .any { it.code == "DIALOGUE_TRIGGER_REFERENCE" }
            )
        }
    }

    @Test
    fun validUnusedDialogueIsAllowed() {
        assertEquals(
            emptyList(),
            DialogueSceneReferences.validate(content(), bindings(), profile(), scene(), catalogs()),
        )
    }

    @Test
    fun normalizationUsesFinalAssetReferences() {
        val p = profile("2")
        assertEquals(
            emptyList(),
            DialogueSceneReferences.validate(
                content(p),
                bindings(),
                p,
                scene(assetVersion = "2"),
                catalogs("2"),
            ),
        )
    }
}
