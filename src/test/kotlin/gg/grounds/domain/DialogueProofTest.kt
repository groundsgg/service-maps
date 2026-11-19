package gg.grounds.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class DialogueProofTest {
    @Test fun rejectsValidWithoutStoryPins() {
        assertThrows<IllegalArgumentException> { DialogueProjection(DialogueStatus.VALID) }
        assertThrows<IllegalArgumentException> {
            DialogueProjection(DialogueStatus.VALID, DialogueProof("a".repeat(64), "test@sha256:${"b".repeat(64)}", "c".repeat(64)))
        }
    }

    @Test fun noneCannotContainStoryFacts() {
        val proof = DialogueProof(
            sourceSha256 = "a".repeat(64), workerImage = "test@sha256:${"b".repeat(64)}",
            executionSha256 = "c".repeat(64), dialogueIds = listOf("invented"),
        )
        assertThrows<IllegalArgumentException> { DialogueProjection(DialogueStatus.NONE, proof) }
    }

    @Test fun absentProofIsNotAbsenceEvidence() {
        assertThrows<IllegalArgumentException> { DialogueProjection(DialogueStatus.NONE) }
        assertEquals(DialogueStatus.UNKNOWN, DialogueProjection().status)
    }
}
