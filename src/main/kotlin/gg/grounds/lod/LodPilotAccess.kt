package gg.grounds.lod

import gg.grounds.domain.LodServingMode
import gg.grounds.domain.LodServingRepository
import gg.grounds.domain.LodServingSnapshot
import gg.grounds.lod.format.LOD_PILOT_HEADER
import gg.grounds.lod.format.digest
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.NameBinding
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import org.eclipse.microprofile.config.inject.ConfigProperty

@NameBinding
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class LodPilotAccess

const val LOD_PILOT_AUTHORIZATION = "grounds.lod.pilot.authorization"

/** Database mode selects independent pilot/entitled gates; client input cannot choose the mode. */
@ApplicationScoped
class LodPilotAuthorizer(
    private val quotas: LodServingRepository,
    private val enabled: Boolean,
    private val clock: Clock,
    private val serving: LodServingConfig = LodServingConfig(false, null as String?, null),
) {
    @Inject
    constructor(
        quotas: LodServingRepository,
        @ConfigProperty(name = "grounds.maps.lod.pilot.enabled", defaultValue = "false")
        enabled: Boolean,
        serving: LodServingConfig,
    ) : this(quotas, enabled, Clock.systemUTC(), serving)

    fun authorize(
        runId: String?,
        capability: String?,
        boundedDownload: Boolean = false,
    ): LodServingSnapshot {
        if (capability == null || capability.length !in 32..256) refuse(403)
        val id =
            try {
                UUID.fromString(runId)
            } catch (_: Exception) {
                refuse(403)
            }
        val snapshot = quotas.find(id) ?: refuse(403)
        when (snapshot.run.mode) {
            LodServingMode.PILOT -> if (!enabled) refuse(403)
            LodServingMode.ENTITLED ->
                if (
                    !serving.configured ||
                        snapshot.run.budgetId != serving.budgetId ||
                        snapshot.run.generationBudgetId != serving.generationBudgetId
                )
                    refuse(403)
        }
        val supplied = digest(capability.toByteArray()).toByteArray(Charsets.US_ASCII)
        if (
            !MessageDigest.isEqual(
                supplied,
                snapshot.run.tokenSha256.toByteArray(Charsets.US_ASCII),
            )
        )
            refuse(403)
        val now = clock.instant()
        if (
            !snapshot.run.enabled ||
                now < snapshot.run.startsAt ||
                snapshot.run.expiresAt?.let { !now.isBefore(it) } == true
        )
            refuse(410)
        if (snapshot.run.mode == LodServingMode.ENTITLED) {
            // A preexisting source lease may drain once within its original 120s bound.
            if (boundedDownload) {
                if (
                    quotas.deliveryBudgetTokenSha256(requireNotNull(serving.budgetId)) !=
                        snapshot.run.tokenSha256
                )
                    refuse(410)
            } else if (
                !quotas.isCurrentEntitled(
                    id,
                    requireNotNull(serving.budgetId),
                    requireNotNull(serving.generationBudgetId),
                )
            )
                refuse(410)
        }
        return snapshot
    }

    private fun refuse(status: Int): Nothing =
        throw WebApplicationException(
            Response.status(status).header("Cache-Control", "no-store").build()
        )
}

/** Runs before entity deserialization. Existing Maps OIDC permissions are untouched. */
@Provider
@LodPilotAccess
@Priority(Priorities.AUTHENTICATION)
class LodPilotAccessFilter @Inject constructor(private val authorizer: LodPilotAuthorizer) :
    ContainerRequestFilter {
    override fun filter(context: ContainerRequestContext) {
        try {
            val snapshot =
                authorizer.authorize(
                    context.uriInfo.pathParameters.getFirst("runId"),
                    context.getHeaderString(LOD_PILOT_HEADER),
                    boundedDownload =
                        context.method == "GET" &&
                            context.uriInfo.pathParameters.getFirst("requestId") != null,
                )
            context.setProperty(LOD_PILOT_AUTHORIZATION, snapshot)
        } catch (e: WebApplicationException) {
            context.abortWith(e.response)
        }
    }
}
