package gg.grounds.derive

import gg.grounds.dialogue.core.definition.DialogueDefinition
import gg.grounds.dialogue.json.DialogueCatalogLoader
import gg.grounds.dialogue.json.DialogueJsonCodec
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveProblem
import gg.grounds.domain.DialogueFilePin
import gg.grounds.domain.DialogueRequirement

class DialogueValidationException(problems: List<DeriveProblem>) :
    RuntimeException("Dialogue content validation failed") {
    val problems: List<DeriveProblem> = java.util.List.copyOf(problems)
}

class ValidatedDialogueContent(
    val catalogue: DialogueCatalogLoader.ValidatedCatalogue,
    files: List<DialogueFilePin>,
    requirements: List<DialogueRequirement>,
    problems: List<DeriveProblem>,
) {
    val files: List<DialogueFilePin> = java.util.List.copyOf(files)
    val requirements: List<DialogueRequirement> = java.util.List.copyOf(requirements)
    val problems: List<DeriveProblem> = java.util.List.copyOf(problems)
}

/** Only the public loader interprets format, graph, locale and provider validity. */
object DialogueBundleValidation {
    fun validate(
        inputs: DialogueInputScan.Present,
        profile: DialogueHostProfile,
    ): ValidatedDialogueContent {
        if (!inputs.hostBytes.contentEquals(profile.hostCatalogBytes))
            throw DialogueValidationException(
                listOf(
                    DeriveProblem(
                        DeriveFailureScope.CONTENT,
                        "context/host-catalog.json",
                        "DIALOGUE_HOST_MISMATCH",
                        null,
                        "Author context differs from the pinned host profile",
                        "dialogue",
                        "ERROR",
                        "context/host-catalog.json",
                        "/",
                    )
                )
            )
        // Context diagnostics and relative definition labels have separate namespaces in the public
        // codec.
        // Preflight context so an authored definition called texts/en.json cannot capture its
        // filename.
        val context = DialogueJsonCodec().prepareContext(inputs.texts, profile.hostCatalogBytes)
        if (!context.isValid)
            throw DialogueValidationException(
                DialogueDiagnostics.bounded(
                    context.problems().map { DialogueDiagnostics.fromLibrary(it) }
                )
            )
        val result =
            DialogueCatalogLoader()
                .reload(
                    DialogueCatalogLoader.ContentSources(
                        inputs.dialogues,
                        inputs.texts,
                        profile.hostCatalogBytes,
                    )
                )
        val problems =
            DialogueDiagnostics.bounded(
                result.problems().map { DialogueDiagnostics.fromLibrary(it, inputs.dialogues.keys) }
            )
        if (!result.isValid) throw DialogueValidationException(problems)
        val catalogue = requireNotNull(result.candidate())
        val requirements = linkedSetOf<DialogueRequirement>()
        fun condition(value: DialogueDefinition.Condition?) {
            if (value != null) requirements += DialogueRequirement("condition", value.key())
        }
        catalogue.entries().toSortedMap().values.forEach { validated ->
            val definition = validated.definition()
            definition.capabilities().required().forEach {
                requirements += DialogueRequirement("capability", it)
            }
            definition.capabilities().optional().forEach {
                requirements += DialogueRequirement("capability", it, required = false)
            }
            definition.nodes().toSortedMap().values.forEach { node ->
                when (node) {
                    is DialogueDefinition.Message -> {
                        requirements += DialogueRequirement("speaker", node.speaker())
                        node.gesture()?.let {
                            requirements +=
                                DialogueRequirement(
                                    "gesture",
                                    it,
                                    node.speaker(),
                                    "actor.gesture" in definition.capabilities().required(),
                                )
                        }
                        node.choices().forEach { condition(it.condition()) }
                    }
                    is DialogueDefinition.Branch ->
                        node.cases().forEach { condition(it.condition()) }
                    is DialogueDefinition.Action ->
                        requirements += DialogueRequirement("action", node.key())
                    is DialogueDefinition.Outcome ->
                        requirements +=
                            DialogueRequirement(
                                "outcome",
                                node.milestone(),
                                required = node.delivery() == DialogueDefinition.Delivery.DURABLE,
                            )
                    is DialogueDefinition.End -> Unit
                    else -> error("Unsupported validated dialogue node")
                }
            }
        }
        return ValidatedDialogueContent(
            catalogue,
            inputs.files,
            requirements.sortedWith(
                compareBy({ it.kind }, { it.key }, { it.speaker ?: "" }, { it.required })
            ),
            problems,
        )
    }
}
