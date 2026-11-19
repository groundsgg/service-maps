package gg.grounds.derive

import gg.grounds.dialogue.core.definition.DialogueDefinition
import gg.grounds.domain.CatalogReference as DomainCatalogReference
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveProblem
import gg.grounds.scene.format.*

/** Checks only Grounds bindings; general dialogue semantics remain in the public validator. */
object DialogueSceneReferences {
    fun validate(
        content: ValidatedDialogueContent,
        bindings: DialogueBindings,
        profile: DialogueHostProfile,
        scene: SceneDocument,
        catalogs: ResolvedSceneCatalogs,
        registeredStartActions: Set<String> = profile.startActions.keys,
    ): List<DeriveProblem> {
        val problems = mutableListOf<DeriveProblem>()
        fun problem(
            code: String,
            pointer: String,
            message: String,
            severity: String = "ERROR",
            file: String = "grounds/dialogue.json",
        ) {
            problems +=
                DeriveProblem(
                    DeriveFailureScope.CONTENT,
                    file,
                    code,
                    null,
                    message,
                    "dialogue",
                    severity,
                    file,
                    pointer,
                )
        }
        val assets =
            DomainCatalogReference(scene.catalogs.assets.id.value, scene.catalogs.assets.version)
        val actions =
            DomainCatalogReference(scene.catalogs.actions.id.value, scene.catalogs.actions.version)
        if (
            profile.assetCatalog != assets ||
                actions !in profile.actionCatalogs ||
                catalogs.assets.id != scene.catalogs.assets.id ||
                catalogs.assets.version != scene.catalogs.assets.version ||
                catalogs.actions.id != scene.catalogs.actions.id ||
                catalogs.actions.version != scene.catalogs.actions.version
        ) {
            problem(
                "DIALOGUE_CATALOG_MISMATCH",
                "/profile",
                "Host profile and final Scene catalogues do not match",
            )
            return problems
        }
        if (bindings.profile.id != profile.id || bindings.profile.version != profile.version)
            problem(
                "DIALOGUE_PROFILE_MISMATCH",
                "/profile",
                "Bindings name a different host profile",
            )
        val knownRoles = content.catalogue.entries().values.first().catalog().speakers()
        val resolved = mutableMapOf<String, Npc>()
        bindings.speakers.toSortedMap().forEach { (role, elementId) ->
            val pointer = "/speakers/${escape(role)}"
            if (role !in knownRoles)
                problem("DIALOGUE_SPEAKER_UNKNOWN", pointer, "Binding names an unknown host role")
            val element = scene.elements.singleOrNull { it.id.value == elementId }
            if (element !is Npc)
                problem(
                    "DIALOGUE_NPC_BINDING",
                    pointer,
                    "Speaker must bind an existing NPC element",
                )
            else {
                resolved[role] = element
                if (
                    catalogs.assets.assets[element.body]?.kind != AssetKind.NPC_BODY ||
                        element.body.value !in profile.npcAssets
                )
                    problem(
                        "DIALOGUE_NPC_ASSET",
                        pointer,
                        "NPC body is not supported by the host profile",
                    )
            }
        }
        content.requirements
            .filter { it.kind == "speaker" }
            .forEach {
                if (it.key !in bindings.speakers)
                    problem(
                        "DIALOGUE_NPC_BINDING",
                        "/speakers/${escape(it.key)}",
                        "Dialogue speaker has no explicit NPC binding",
                    )
            }
        content.requirements
            .filter { it.kind == "gesture" }
            .forEach { requirement ->
                val npc = resolved[requirement.speaker] ?: return@forEach
                val body = profile.npcAssets[npc.body.value] ?: return@forEach
                val animation = body[requirement.key]
                if (animation == null) {
                    problem(
                        "DIALOGUE_GESTURE_UNAVAILABLE",
                        "/speakers/${escape(requirement.speaker!!)}",
                        "Known gesture is unavailable for this NPC body",
                        if (requirement.required) "ERROR" else "WARNING",
                    )
                } else if (
                    catalogs.assets.assets[npc.body]?.animations?.any { it.value == animation } !=
                        true
                ) {
                    problem(
                        "DIALOGUE_GESTURE_INVALID",
                        "/speakers/${escape(requirement.speaker!!)}",
                        "Gesture mapping names a missing NPC animation",
                    )
                }
            }
        scene.elements.filterIsInstance<Npc>().forEach { npc ->
            npc.bindings.forEachIndexed { bindingIndex, binding ->
                binding.actions.forEachIndexed actionLoop@{ actionIndex, action ->
                    if (action !is ApplicationAction) return@actionLoop
                    val pointer =
                        "/elements/${scene.elements.indexOf(npc)}/bindings/$bindingIndex/actions/$actionIndex"
                    val mapping = profile.startActions[action.key.value]
                    if (mapping == null) {
                        if (action.key.value in registeredStartActions)
                            problem(
                                "DIALOGUE_TRIGGER_PROFILE_MISMATCH",
                                pointer,
                                "Registered dialogue start action is unsupported by the selected host profile",
                                file = "scene.json",
                            )
                        return@actionLoop
                    }
                    val declaration = catalogs.actions.actions[action.key]
                    val parameters = listOf(mapping.dialogueArgument, mapping.speakerArgument)
                    if (
                        declaration == null ||
                            parameters.any { name ->
                                declaration.parameters.values.none {
                                    it.id.value == name &&
                                        it.type == ActionParameterType.STRING &&
                                        it.required
                                }
                            }
                    ) {
                        problem(
                            "DIALOGUE_TRIGGER_CONTRACT",
                            pointer,
                            "Dialogue start action must declare required string arguments",
                            file = "scene.json",
                        )
                        return@actionLoop
                    }
                    fun value(name: String) =
                        (action.arguments.entries.find { it.key.value == name }?.value
                                as? StringArgument)
                            ?.value
                    val dialogue = value(mapping.dialogueArgument)
                    val speaker = value(mapping.speakerArgument)
                    val definition = content.catalogue.entries()[dialogue]?.definition()
                    val usedRoles =
                        definition
                            ?.nodes()
                            ?.values
                            ?.filterIsInstance<DialogueDefinition.Message>()
                            ?.map { it.speaker() } ?: emptyList()
                    if (definition == null || speaker !in resolved || speaker !in usedRoles)
                        problem(
                            "DIALOGUE_TRIGGER_REFERENCE",
                            pointer,
                            "Dialogue start action names an unknown dialogue or speaker binding",
                            file = "scene.json",
                        )
                }
            }
        }
        return DialogueDiagnostics.bounded(problems)
    }

    private fun escape(value: String) = value.replace("~", "~0").replace("/", "~1")
}
