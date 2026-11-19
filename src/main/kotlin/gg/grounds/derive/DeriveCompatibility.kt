package gg.grounds.derive

import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

/** The rollout gates are one invariant: requiring derivation without enabling it is nonsense. */
@Startup
@ApplicationScoped
class DeriveCompatibility
@jakarta.inject.Inject
constructor(
    @param:ConfigProperty(name = "grounds.maps.derive.enabled", defaultValue = "false")
    val enabled: Boolean,
    @param:ConfigProperty(name = "grounds.maps.derive.required", defaultValue = "false")
    val required: Boolean,
    @param:ConfigProperty(
        name = "grounds.maps.dialogue.validation-required",
        defaultValue = "false",
    )
    val dialogueRequired: Boolean = false,
    @param:ConfigProperty(name = "grounds.maps.derive.request-schema-version", defaultValue = "1")
    val requestSchemaVersion: Int = 1,
) {
    init {
        require(!dialogueRequired || (enabled && required && requestSchemaVersion == 2)) {
            "dialogue.validation-required requires derive.enabled=true, derive.required=true and request-schema-version=2"
        }
        require(!required || enabled) {
            "grounds.maps.derive.required requires derive.enabled=true"
        }
    }
}
