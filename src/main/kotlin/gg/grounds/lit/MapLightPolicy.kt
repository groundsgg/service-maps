package gg.grounds.lit

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

/** One preparation contract for the worker, status projection and live-pin transaction. */
@ApplicationScoped
class MapLightPolicy
@Inject
constructor(
    @ConfigProperty(name = "grounds.maps.derive.enabled", defaultValue = "false")
    deriveEnabled: Boolean,
    @ConfigProperty(name = "grounds.maps.lit.enabled", defaultValue = "true") litEnabled: Boolean,
    @ConfigProperty(name = "grounds.maps.lit.format", defaultValue = "2") val format: Int,
) {
    val enabled = deriveEnabled && litEnabled

    init {
        require(format > 0) { "light format must be positive" }
    }
}
