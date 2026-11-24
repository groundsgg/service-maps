package gg.grounds.derive

import gg.grounds.catalog.DialogueProfileLoader
import gg.grounds.domain.DeriveExecutionContract

data class DialogueDeriveContext(
    val execution: DeriveExecutionContract,
    val profileLoader: DialogueProfileLoader,
) {
    init {
        require(execution.schemaVersion == 2)
    }
}

class DialogueProfileConfigurationException :
    RuntimeException("No host profile is configured for this Story")
