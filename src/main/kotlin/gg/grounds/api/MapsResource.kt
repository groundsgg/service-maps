package gg.grounds.api

import gg.grounds.domain.MapAddress
import gg.grounds.domain.MapAlreadyExistsException
import gg.grounds.domain.MapKind
import gg.grounds.domain.MapRecord
import gg.grounds.domain.MapRepository
import gg.grounds.domain.MapTrust
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.net.URI
import java.time.Instant

@Path("/v1/maps")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
class MapsResource
@Inject
constructor(private val maps: MapRepository, private val identity: SecurityIdentity) {

    @POST
    fun create(request: CreateMapRequest): Response {
        val address =
            MapAddress.parse(request.address)
                ?: return problem(
                    Response.Status.BAD_REQUEST,
                    "not a valid map address: ${request.address}",
                )
        val kind =
            parseKind(request.kind)
                ?: return problem(Response.Status.BAD_REQUEST, "unknown kind: ${request.kind}")

        return try {
            val created =
                maps.create(
                    address = address,
                    displayName = request.displayName ?: address.name,
                    kind = kind,
                    stateful = request.stateful,
                    // Trust is never taken from the request: a creator asking to be
                    // first-party is exactly the request to refuse. It is derived from
                    // the namespace, and `u/<creator>` is untrusted by construction.
                    trust =
                        if (address.namespace.startsWith("u/")) MapTrust.UNTRUSTED
                        else MapTrust.FIRST_PARTY,
                    // Likewise the owner: the authenticated subject, never a field.
                    ownerSub = identity.principal.name,
                )
            Response.created(URI.create("/v1/maps/${created.address}"))
                .entity(created.toDto())
                .build()
        } catch (e: MapAlreadyExistsException) {
            problem(Response.Status.CONFLICT, e.message ?: "map already exists")
        }
    }

    @GET
    fun list(@QueryParam("namespace") namespace: String?): List<MapDto> =
        maps.list(namespace).map(MapRecord::toDto)

    /**
     * The address is one catch-all parameter rather than two segments, because a creator namespace
     * is itself `u/<creator>` and would otherwise not route.
     */
    @GET
    @Path("/{address:.+}")
    fun get(@PathParam("address") address: String): Response {
        val parsed =
            MapAddress.parse(address)
                ?: return problem(Response.Status.BAD_REQUEST, "not a valid map address: $address")
        val found =
            maps.find(parsed) ?: return problem(Response.Status.NOT_FOUND, "no such map: $parsed")
        return Response.ok(found.toDto()).build()
    }

    private fun problem(status: Response.Status, detail: String): Response =
        Response.status(status).entity(ProblemDto(status.statusCode, detail)).build()

    private fun parseKind(raw: String?): MapKind? =
        raw?.let { MapKind.entries.firstOrNull { kind -> kind.name.equals(it, ignoreCase = true) } }
}

data class CreateMapRequest(
    /** `bedwars/4x4-baumhaus`. */
    val address: String = "",
    val displayName: String? = null,
    val kind: String? = null,
    val stateful: Boolean = false,
)

data class MapDto(
    val address: String,
    val displayName: String,
    val kind: String,
    val stateful: Boolean,
    val trust: String,
    val ownerSub: String,
    val forkedFrom: String?,
    val createdAt: Instant,
)

data class ProblemDto(val status: Int, val detail: String)

private fun MapRecord.toDto(): MapDto =
    MapDto(
        address = address.toString(),
        displayName = displayName,
        kind = kind.name,
        stateful = stateful,
        trust = trust.name,
        forkedFrom = forkedFrom?.let { "${it.mapId}@${it.version}" },
        ownerSub = ownerSub,
        createdAt = createdAt,
    )
