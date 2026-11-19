package gg.grounds.compatibility

import gg.grounds.domain.DialogueStatus
import gg.grounds.domain.MapVersionRecord
import jakarta.enterprise.context.ApplicationScoped

class DialogueRuntimeUnavailableException(detail: String) : RuntimeException(detail)

/** Static content validation supplies no evidence of a deployed session host. E2 adds that contract. */
@ApplicationScoped
class DialogueRuntimeCompatibility(private val publication: DialoguePublicationPolicy) {
    fun problem(environment: String, address: String, version: MapVersionRecord): String? {
        if (version.dialogue.status == DialogueStatus.VALID)
            return "DIALOGUE_RUNTIME_UNAVAILABLE: no dialogue runtime contract for $address in $environment."
        return try { publication.requireChecked(version); null }
        catch (e: DialogueProofRequiredException) { e.message }
    }
}
