package gg.grounds.lod

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.Optional
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class LodServingConfig(
    val enabled: Boolean,
    val budgetId: String?,
    val generationBudgetId: String?,
) {
    @Inject
    constructor(
        @ConfigProperty(name = "grounds.maps.lod.serving.enabled", defaultValue = "false")
        enabled: Boolean,
        @ConfigProperty(name = "grounds.maps.lod.serving.budget-id") budgetId: Optional<String>,
        @ConfigProperty(name = "grounds.maps.lod.serving.generation-budget-id")
        generationBudgetId: Optional<String>,
    ) : this(enabled, budgetId.orElse(null), generationBudgetId.orElse(null))

    val configured: Boolean
        get() = enabled && budgetId?.matches(ID) == true && generationBudgetId?.matches(ID) == true

    companion object {
        private val ID = Regex("[a-z0-9-]{1,64}")
    }
}
