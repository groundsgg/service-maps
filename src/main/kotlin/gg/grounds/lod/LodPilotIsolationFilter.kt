package gg.grounds.lod

import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.container.PreMatching
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Defense in depth for direct Pod/Service traffic, independent of Gateway routing and user roles.
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION - 100)
class LodPilotIsolationFilter(
    @ConfigProperty(name = "grounds.maps.lod.pilot.isolated", defaultValue = "false")
    private val isolated: Boolean
) : ContainerRequestFilter {
    override fun filter(context: ContainerRequestContext) {
        if (isolated && !ALLOWED.matches(context.uriInfo.requestUri.rawPath)) {
            context.abortWith(Response.status(404).build())
        }
    }

    companion object {
        private const val UUID = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
        private val ALLOWED = Regex("/v1/maps/lod-pilots/$UUID/(credits|source(/$UUID)?)")
    }
}
