package gg.grounds.compatibility

import gg.grounds.domain.DialogueStatus
import gg.grounds.domain.MapVersionRecord
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

class DialogueProofRequiredException : RuntimeException(
    "DIALOGUE_PROOF_REQUIRED: pull the exact version with /map pull <namespace/name> <version>, then /map push to derive a new checked version."
)

/** A positive service-owned proof, never an author declaration or an inferred absence. */
@ApplicationScoped
class DialoguePublicationPolicy(
    @param:ConfigProperty(name="grounds.maps.dialogue.validation-required", defaultValue="false")
    val required: Boolean,
) {
    fun requireChecked(record: MapVersionRecord) {
        if (!required) return
        val proof=record.dialogue.proof
        val contract=record.executionContract
        if (record.dialogue.status !in setOf(DialogueStatus.NONE,DialogueStatus.VALID) ||
            proof == null || contract?.schemaVersion != 2 || record.manifestSha256 == null ||
            proof.sourceSha256 != record.sourceSha256 || proof.workerImage != contract.workerImage ||
            proof.executionSha256 != contract.fingerprint() ||
            (record.dialogue.status == DialogueStatus.VALID && proof.profile !in contract.dialogueProfiles))
            throw DialogueProofRequiredException()
    }
}
