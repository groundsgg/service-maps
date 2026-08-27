package gg.grounds.derive

import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

/** The rollout gates are one invariant: requiring derivation without enabling it is nonsense. */
@Startup
@ApplicationScoped
class DeriveCompatibility(
    @param:ConfigProperty(name = "grounds.maps.derive.enabled", defaultValue = "false")
    val enabled: Boolean,
    @param:ConfigProperty(name = "grounds.maps.derive.required", defaultValue = "false")
    val required: Boolean,
) {
    init {
        require(!required || enabled) {
            "grounds.maps.derive.required requires derive.enabled=true"
        }
    }
}
