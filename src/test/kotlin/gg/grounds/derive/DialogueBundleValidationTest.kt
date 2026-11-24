package gg.grounds.derive

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import gg.grounds.dialogue.json.DialogueCatalogLoader
import gg.grounds.domain.CatalogReference
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DialogueBundleValidationTest {
    private fun resource(path: String) =
        javaClass.getResourceAsStream("/derive/dialogue/$path")!!.use { it.readAllBytes() }

    private fun sources(
        definition: ByteArray = resource("dialogues/mechanic.json"),
        host: ByteArray = resource("context/host-catalog.json"),
    ) =
        DialogueInputScan.Present(
            "{}".toByteArray(),
            host,
            mapOf("mechanic.json" to definition),
            mapOf("en" to resource("texts/en.json"), "de" to resource("texts/de.json")),
            emptyList(),
        )

    private fun profile(host: ByteArray = resource("context/host-catalog.json")) =
        DialogueHostProfile(
            "test",
            "1",
            host,
            CatalogReference("assets", "1"),
            setOf(CatalogReference("actions", "1")),
            emptyMap(),
            emptyMap(),
        )

    @Test
    fun sameGraphAndTextFailuresAsPublicLoader() {
        listOf("\"next\": \"done\"" to "\"next\": \"missing\"", "mechanic.bye" to "missing.bye")
            .forEach { (old, new) ->
                val input =
                    sources(
                        String(resource("dialogues/mechanic.json")).replace(old, new).toByteArray()
                    )
                val public =
                    DialogueCatalogLoader()
                        .reload(
                            DialogueCatalogLoader.ContentSources(
                                input.dialogues,
                                input.texts,
                                input.hostBytes,
                            )
                        )
                assertFalse(public.isValid)
                val failure =
                    assertThrows<DialogueValidationException> {
                        DialogueBundleValidation.validate(input, profile())
                    }
                assertEquals(
                    public.problems().map { it.code() }.sorted(),
                    failure.problems.map { it.code }.sorted(),
                )
                assertTrue(
                    failure.problems.any {
                        it.file == "dialogues/mechanic.json" && it.pointer!!.startsWith("/nodes/")
                    }
                )
            }
    }

    @Test
    fun localeDiagnosticCannotBeMisattributedToSameNamedNestedDefinition() {
        val base = sources()
        val input =
            DialogueInputScan.Present(
                base.bindingsBytes,
                base.hostBytes,
                mapOf("texts/en.json" to base.dialogues.getValue("mechanic.json")),
                base.texts + ("en" to "{".toByteArray()),
                emptyList(),
            )
        val failure =
            assertThrows<DialogueValidationException> {
                DialogueBundleValidation.validate(input, profile())
            }
        assertTrue(failure.problems.any { it.file == "texts/en.json" })
        assertFalse(failure.problems.any { it.file == "dialogues/texts/en.json" })
    }

    @Test
    fun snapshotMismatchCannotValidate() {
        assertEquals(
            "DIALOGUE_HOST_MISMATCH",
            assertThrows<DialogueValidationException> {
                    DialogueBundleValidation.validate(sources(host = "{}".toByteArray()), profile())
                }
                .problems
                .single()
                .code,
        )
    }

    @Test
    fun extractsRolesGesturesProvidersAndOutcomes() {
        val mapper = ObjectMapper()
        val definition = mapper.readTree(resource("dialogues/mechanic.json")) as ObjectNode
        val nodes = definition.path("nodes") as ObjectNode
        nodes.set<ObjectNode>(
            "unused_action",
            mapper.readTree(
                """{"type":"action","action":"test:grant","arguments":{},"onSuccess":"done","onRejected":"done","onUnavailable":"done","onUncertain":"done"}"""
            ) as ObjectNode,
        )
        nodes.set<ObjectNode>(
            "unused_branch",
            mapper.readTree(
                """{"type":"branch","cases":[{"condition":{"key":"test:allowed","arguments":{}},"to":"done"}],"defaultTo":"done","unavailableTo":"done"}"""
            ) as ObjectNode,
        )
        val host = mapper.readTree(resource("context/host-catalog.json")) as ObjectNode
        (host.path("actions") as ObjectNode).set<ObjectNode>(
            "test:grant",
            mapper.readTree("""{"arguments":{},"durable":false}""") as ObjectNode,
        )
        (host.path("conditions") as ObjectNode).set<ObjectNode>(
            "test:allowed",
            mapper.readTree("""{"arguments":{},"durable":false}""") as ObjectNode,
        )
        val hostBytes = mapper.writeValueAsBytes(host)
        val content =
            DialogueBundleValidation.validate(
                sources(mapper.writeValueAsBytes(definition), hostBytes),
                profile(hostBytes),
            )
        assertTrue(content.requirements.any { it.kind == "speaker" && it.key == "mechanic" })
        assertTrue(
            content.requirements.any {
                it.kind == "gesture" && it.key == "demo:wave" && it.speaker == "mechanic"
            }
        )
        assertTrue(content.requirements.any { it.kind == "action" && it.key == "test:grant" })
        assertTrue(content.requirements.any { it.kind == "condition" && it.key == "test:allowed" })
        assertTrue(
            content.requirements.any { it.kind == "outcome" && it.key == "demo:lift_discussed" }
        )
        assertTrue(
            content.requirements.any {
                it.kind == "capability" && it.key == "dialogue.choices" && it.required
            }
        )
    }

    @Test
    fun warningDoesNotRejectCandidate() {
        val definition =
            String(resource("dialogues/mechanic.json"))
                .replace(
                    "\"nodes\": {",
                    "\"nodes\": {\"unused\": {\"type\":\"end\",\"reason\":\"completed\"},",
                )
        val content =
            DialogueBundleValidation.validate(sources(definition.toByteArray()), profile())
        assertEquals(setOf("demo:mechanic"), content.catalogue.entries().keys)
        assertTrue(
            content.problems.any { it.code == "NODE_UNREACHABLE" && it.severity == "WARNING" }
        )
    }
}
