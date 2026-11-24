package gg.grounds.domain

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DialogueProofTest {
    @Test
    fun rejectsIncompleteAndIncoherentStoryFileInventories() {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val bytes =
            javaClass.getResourceAsStream("/derive/result-v2-valid.json")!!.use {
                it.readAllBytes()
            }
        listOf(
                "dialogues/guide.json",
                "texts/en.json",
                "grounds/dialogue.json",
                "context/host-catalog.json",
            )
            .forEach { missing ->
                val root = mapper.readTree(bytes)
                val files =
                    root.path("dialogue").path("proof").path("files")
                        as com.fasterxml.jackson.databind.node.ArrayNode
                val index = files.indexOfFirst { it.path("path").asText() == missing }
                files.remove(index)
                assertThrows<Exception> {
                    gg.grounds.derive.CanonicalJson.readResult(mapper.writeValueAsBytes(root))
                }
            }
        listOf("hostDigest", "nonStory", "pathAlias").forEach { mutation ->
            val root = mapper.readTree(bytes)
            val files =
                root.path("dialogue").path("proof").path("files")
                    as com.fasterxml.jackson.databind.node.ArrayNode
            when (mutation) {
                "hostDigest" ->
                    (files.last() as com.fasterxml.jackson.databind.node.ObjectNode).put(
                        "sha256",
                        "f".repeat(64),
                    )
                "nonStory" ->
                    (files.first() as com.fasterxml.jackson.databind.node.ObjectNode).put(
                        "path",
                        "other/file.json",
                    )
                else ->
                    (files.first() as com.fasterxml.jackson.databind.node.ObjectNode).put(
                        "path",
                        "dialogues/Foo:bar.json",
                    )
            }
            assertThrows<Exception> {
                gg.grounds.derive.CanonicalJson.readResult(mapper.writeValueAsBytes(root))
            }
        }
    }

    @Test
    fun rejectsValidWithoutStoryPins() {
        assertThrows<IllegalArgumentException> { DialogueProjection(DialogueStatus.VALID) }
        assertThrows<IllegalArgumentException> {
            DialogueProjection(
                DialogueStatus.VALID,
                DialogueProof("a".repeat(64), "test@sha256:${"b".repeat(64)}", "c".repeat(64)),
            )
        }
    }

    @Test
    fun noneCannotContainStoryFacts() {
        val proof =
            DialogueProof(
                sourceSha256 = "a".repeat(64),
                workerImage = "test@sha256:${"b".repeat(64)}",
                executionSha256 = "c".repeat(64),
                dialogueIds = listOf("invented"),
            )
        assertThrows<IllegalArgumentException> { DialogueProjection(DialogueStatus.NONE, proof) }
    }

    @Test
    fun absentProofIsNotAbsenceEvidence() {
        assertThrows<IllegalArgumentException> { DialogueProjection(DialogueStatus.NONE) }
        assertEquals(DialogueStatus.UNKNOWN, DialogueProjection().status)
    }
}
