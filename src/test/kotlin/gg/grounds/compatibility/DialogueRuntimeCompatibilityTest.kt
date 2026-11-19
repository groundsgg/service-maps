package gg.grounds.compatibility

import gg.grounds.DialogueFactsFixture as F
import gg.grounds.domain.*
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DialogueRuntimeCompatibilityTest {
    private fun version(dialogue: DialogueProjection) =
        MapVersionRecord(
            UUID.randomUUID(),
            1,
            VersionState.PUBLISHED,
            "9".repeat(64),
            F.source,
            null,
            "8".repeat(64),
            null,
            2,
            null,
            null,
            UUID.randomUUID(),
            null,
            false,
            F.scene,
            "worker",
            null,
            Instant.EPOCH,
            dialogue = dialogue,
            executionContract = F.contract,
        )

    @Test
    fun storyBlockedWithoutRuntimeContract() {
        assertTrue(
            DialogueRuntimeCompatibility(DialoguePublicationPolicy(false))
                .problem("prod", "test/map", version(F.story))!!
                .startsWith("DIALOGUE_RUNTIME_UNAVAILABLE")
        )
    }

    @Test
    fun unknownRollbackBlockedInRequiredMode() {
        assertNotNull(
            DialogueRuntimeCompatibility(DialoguePublicationPolicy(true))
                .problem("prod", "test/map", version(DialogueProjection()))
        )
        assertNull(
            DialogueRuntimeCompatibility(DialoguePublicationPolicy(false))
                .problem("prod", "test/map", version(DialogueProjection()))
        )
    }

    @Test
    fun noneStillRunsExistingSceneChecks() {
        val v = version(F.none)
        assertNull(
            DialogueRuntimeCompatibility(DialoguePublicationPolicy(true))
                .problem("stage", "test/map", v)
        )
        assertNotNull(MapRuntimeCompatibility("stage", "[]").problem("stage", "test/map", v.scene))
    }
}
