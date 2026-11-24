package gg.grounds.lod

import gg.grounds.domain.LodServingIdentity
import gg.grounds.domain.LodServingRepository
import gg.grounds.lod.format.*
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.Priority
import jakarta.inject.Inject
import jakarta.ws.rs.*
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import java.security.MessageDigest
import org.eclipse.microprofile.openapi.annotations.Operation

@NameBinding
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class LodServingAccess

/** Hash authentication happens before JSON decoding and before any artifact lookup. */
@Provider
@LodServingAccess
@Priority(Priorities.AUTHENTICATION)
class LodServingDiscoveryFilter
@Inject
constructor(private val quotas: LodServingRepository, private val config: LodServingConfig) :
    ContainerRequestFilter {
    override fun filter(context: ContainerRequestContext) {
        val capability = context.getHeaderString(LOD_PILOT_HEADER)
        if (!config.configured || capability == null || capability.length !in 32..256) {
            context.abortWith(reply(403))
            return
        }
        val expected = quotas.deliveryBudgetTokenSha256(requireNotNull(config.budgetId))
        if (
            expected == null ||
                !MessageDigest.isEqual(
                    digest(capability.toByteArray()).toByteArray(Charsets.US_ASCII),
                    expected.toByteArray(Charsets.US_ASCII),
                )
        )
            context.abortWith(reply(403))
    }
}

/**
 * Read-only availability for exact server-loaded identities. Never creates selections or reads R2.
 */
@Path("/v1/maps/lod-serving/discover")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@LodServingAccess
@Blocking
class LodServingDiscoveryResource
@Inject
constructor(private val quotas: LodServingRepository, private val config: LodServingConfig) {
    @POST
    @Operation(hidden = true)
    fun discover(request: LodServingDiscoveryRequest?): Response {
        val identity =
            try {
                requireNotNull(request).validate()
                LodServingIdentity(
                    request.mapBundleSha256,
                    request.sourceBundleSha256,
                    request.registrySha256,
                    request.generatorSha256,
                )
            } catch (_: IllegalArgumentException) {
                return reply(400)
            }
        if (!config.configured) return reply(403)
        val snapshot =
            quotas.discoverEntitled(
                requireNotNull(config.budgetId),
                requireNotNull(config.generationBudgetId),
                identity,
            ) ?: return reply(404)
        val artifact = snapshot.artifact
        return Response.ok(
                LodServingSelection(
                    runId = snapshot.run.id.toString(),
                    mapBundleSha256 = artifact.mapBundleSha256,
                    sourceBundleSha256 = artifact.sourceBundleSha256,
                    registrySha256 = artifact.registrySha256,
                    generatorSha256 = artifact.generatorSha256,
                )
            )
            .header("Cache-Control", "no-store")
            .build()
    }
}

private fun reply(status: Int): Response =
    Response.status(status).header("Cache-Control", "no-store").build()
