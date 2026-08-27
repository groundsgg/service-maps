package gg.grounds.catalog

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Readiness

@Readiness
@ApplicationScoped
class PackSetCatalogReadiness(
    private val provider: PackSetCatalogProvider,
    @ConfigProperty(name = "grounds.maps.derive.enabled", defaultValue = "true")
    private val enabled: Boolean,
) : HealthCheck {
    override fun call(): HealthCheckResponse {
        val response = HealthCheckResponse.named("packset-catalogs")
        if (!enabled) return response.up().withData("status", "disabled").build()
        provider.channelStates().forEach { (channel, state) ->
            response.withData("$channel.status", state.status)
            state.lastError?.let { response.withData("$channel.lastError", it) }
        }
        return if (provider.ready()) response.up().build() else response.down().build()
    }
}
