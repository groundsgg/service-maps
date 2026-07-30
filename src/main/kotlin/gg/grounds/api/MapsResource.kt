package gg.grounds.api

import gg.grounds.blob.BlobStore
import gg.grounds.domain.BundleFacts
import gg.grounds.domain.Digest
import gg.grounds.domain.EnvironmentName
import gg.grounds.domain.ForkOrigin
import gg.grounds.domain.MapAddress
import gg.grounds.domain.MapAlreadyExistsException
import gg.grounds.domain.MapKind
import gg.grounds.domain.MapPinRepository
import gg.grounds.domain.MapRecord
import gg.grounds.domain.MapRepository
import gg.grounds.domain.MapTrust
import gg.grounds.domain.MapVersionRecord
import gg.grounds.domain.MapVersionRepository
import gg.grounds.domain.NotPublishedException
import gg.grounds.domain.VersionNotFoundException
import gg.grounds.domain.VersionNotPublishableException
import gg.grounds.domain.VersionState
import gg.grounds.pins.PinFilePublisher
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
import java.util.UUID

@Path("/v1/maps")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
class MapsResource
@Inject
constructor(
    private val maps: MapRepository,
    private val versions: MapVersionRepository,
    private val pins: MapPinRepository,
    private val blobs: BlobStore,
    private val pinFiles: PinFilePublisher,
    private val identity: SecurityIdentity,
) {

    @POST
    fun create(request: CreateMapRequest?): Response {
        if (request == null) return problem(Response.Status.BAD_REQUEST, "a body is required")
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

    // ---------------------------------------------------------------- uploads

    /**
     * Hands back a URL the client PUTs the world zip to directly. The bytes never enter this
     * process, which is also what keeps a large publish clear of the edge's body limit.
     */
    @POST
    @Path("/{address:.+}/uploads")
    fun createUpload(@PathParam("address") address: String): Response =
        withMap(address) { _ ->
            val uploadId = UUID.randomUUID().toString()
            val key = BlobStore.uploadKey(uploadId)
            Response.ok(UploadDto(uploadId = uploadId, key = key, url = blobs.presignPut(key)))
                .build()
        }

    // --------------------------------------------------------------- versions

    @POST
    @Path("/{address:.+}/versions")
    fun commitVersion(
        @PathParam("address") address: String,
        request: CommitVersionRequest?,
    ): Response =
        withMap(address) { map ->
            if (request == null)
                return@withMap problem(Response.Status.BAD_REQUEST, "a body is required")
            // The upload id becomes part of an object key, so it is validated as the UUID it
            // is supposed to be rather than interpolated. `../../bundle/sha256/ab/cd` would
            // otherwise escape the uploads prefix and be handed to the derive Job as a
            // source to read.
            val sourceKey =
                when (val uploadId = request.uploadId) {
                    null -> null
                    else ->
                        runCatching { UUID.fromString(uploadId) }
                            .getOrNull()
                            ?.let { BlobStore.uploadKey(it.toString()) }
                            ?: return@withMap problem(
                                Response.Status.BAD_REQUEST,
                                "uploadId is not an upload id: $uploadId",
                            )
                }
            // The schema now refuses provenance that names no version; catch it here so it
            // is a 400 about the field rather than a constraint violation surfacing as a 500.
            if (
                request.parentVersion != null &&
                    versions.find(map.id, request.parentVersion) == null
            ) {
                return@withMap problem(
                    Response.Status.BAD_REQUEST,
                    "no version ${request.parentVersion} to descend from",
                )
            }
            val committed =
                versions.commit(
                    mapId = map.id,
                    sourceSha256 = request.sourceSha256,
                    sourceKey = sourceKey,
                    parentVersion = request.parentVersion,
                    note = request.note,
                    bySub = identity.principal.name,
                )
            Response.status(Response.Status.CREATED).entity(committed.toDto()).build()
        }

    @GET
    @Path("/{address:.+}/versions")
    fun listVersions(@PathParam("address") address: String): Response =
        withMap(address) { map -> Response.ok(versions.list(map.id).map { it.toDto() }).build() }

    /**
     * Records what the bundle turned out to be and makes the version usable.
     *
     * The derive Job takes this call over once it exists; until then a staff caller supplies the
     * facts for an already-assembled tree. Nothing else about the state machine changes when that
     * happens, which is the point of having the step at all.
     */
    @POST
    @Path("/{address:.+}/versions/{version}/publish")
    fun publishVersion(
        @PathParam("address") address: String,
        @PathParam("version") version: Int,
        request: PublishVersionRequest?,
    ): Response =
        withMap(address) { map ->
            if (request == null)
                return@withMap problem(Response.Status.BAD_REQUEST, "a body is required")
            // The digest becomes a path in the pin file that game servers append to the CDN
            // base, and a published version is immutable — so an unchecked or empty value
            // here is permanent and points every server somewhere nobody chose.
            if (!Digest.isValid(request.bundleSha256)) {
                return@withMap problem(
                    Response.Status.BAD_REQUEST,
                    "bundleSha256 must be 64 lowercase hex characters",
                )
            }
            try {
                val published =
                    versions.publish(
                        map.id,
                        version,
                        BundleFacts(
                            bundleSha256 = request.bundleSha256,
                            manifestSha256 = request.manifestSha256,
                            sizeBytes = request.sizeBytes,
                            presentChunks = request.presentChunks,
                            estLoadedMib = request.estLoadedMib,
                        ),
                        identity.principal.name,
                    )
                Response.ok(published.toDto()).build()
            } catch (e: VersionNotFoundException) {
                problem(Response.Status.NOT_FOUND, e.message ?: "no such version")
            } catch (e: VersionNotPublishableException) {
                problem(Response.Status.CONFLICT, e.message ?: "not publishable")
            }
        }

    // ------------------------------------------------------------------ forks

    /**
     * A new map from an existing version. Copies no bytes: the fork's first version carries the
     * same bundle digest, so it is usable — and pinnable — immediately.
     */
    @POST
    @Path("/{address:.+}/forks")
    fun fork(@PathParam("address") address: String, request: ForkRequest?): Response =
        withMap(address) { source ->
            if (request == null)
                return@withMap problem(Response.Status.BAD_REQUEST, "a body is required")
            val target =
                MapAddress.parse(request.target)
                    ?: return@withMap problem(
                        Response.Status.BAD_REQUEST,
                        "not a valid map address: ${request.target}",
                    )
            // A named version that does not exist is a 404, not a quiet fall back to the
            // latest: forking "version 7" and silently getting version 9 is help nobody asked
            // for.
            val from =
                when (val wanted = request.fromVersion) {
                    null ->
                        versions.latestPublished(source.id)
                            ?: return@withMap problem(
                                Response.Status.CONFLICT,
                                "$address has no published version to fork",
                            )
                    else ->
                        versions.find(source.id, wanted)
                            ?: return@withMap problem(
                                Response.Status.NOT_FOUND,
                                "no version $wanted of $address",
                            )
                }
            if (from.state != VersionState.PUBLISHED || from.bundleSha256 == null) {
                return@withMap problem(
                    Response.Status.CONFLICT,
                    "version ${from.version} is ${from.state} and has no usable bundle",
                )
            }
            try {
                val created =
                    maps.create(
                        address = target,
                        displayName = request.displayName ?: target.name,
                        kind = source.kind,
                        stateful = source.stateful,
                        // Trust is inherited from the SOURCE and can only get stricter.
                        // Deriving it from the target namespace would let anyone launder an
                        // untrusted creator world into a first-party namespace by forking it
                        // there — the exact boundary this design exists to hold.
                        trust =
                            if (
                                source.trust == MapTrust.UNTRUSTED ||
                                    target.namespace.startsWith("u/")
                            )
                                MapTrust.UNTRUSTED
                            else MapTrust.FIRST_PARTY,
                        ownerSub = identity.principal.name,
                        forkedFrom = ForkOrigin(source.id, from.version),
                        // One fact, one transaction: a map created without its first version
                        // would burn the address on something that can never be forked again.
                        firstVersion = from,
                    )
                Response.created(URI.create("/v1/maps/${created.address}"))
                    .entity(created.toDto())
                    .build()
            } catch (e: MapAlreadyExistsException) {
                problem(Response.Status.CONFLICT, e.message ?: "map already exists")
            }
        }

    // ------------------------------------------------------------------- pins

    /**
     * Points an environment at a version — going live, rolling back and promoting are all this one
     * operation. The pin file is republished afterwards; if that write fails the move still stands,
     * because the file is a projection and the move is the fact.
     */
    @POST
    @Path("/{address:.+}/pins/{environment}")
    fun movePin(
        @PathParam("address") address: String,
        @PathParam("environment") environment: String,
        request: MovePinRequest?,
    ): Response =
        withMap(address) { map ->
            if (request == null)
                return@withMap problem(Response.Status.BAD_REQUEST, "a body is required")
            // The environment names an object in the public bucket; it is an allowlist, not
            // a free string a caller can steer with separators.
            if (!EnvironmentName.isValid(environment)) {
                return@withMap problem(
                    Response.Status.BAD_REQUEST,
                    "not an environment name: $environment",
                )
            }
            try {
                val moved = pins.move(environment, map.id, request.version, identity.principal.name)
                val published = pinFiles.publish(environment)
                Response.ok(
                        PinDto(
                            environment = moved.environment,
                            address = map.address.toString(),
                            version = moved.version,
                            movedBySub = moved.movedBySub,
                            movedAt = moved.movedAt,
                            pinFilePublished = published,
                        )
                    )
                    .build()
            } catch (e: NotPublishedException) {
                problem(Response.Status.CONFLICT, e.message ?: "version is not published")
            }
        }

    // ----------------------------------------------------------------- shared

    /** Resolves the address once and turns both failure modes into the right status. */
    private fun withMap(address: String, block: (MapRecord) -> Response): Response {
        val parsed =
            MapAddress.parse(address)
                ?: return problem(Response.Status.BAD_REQUEST, "not a valid map address: $address")
        val found =
            maps.find(parsed) ?: return problem(Response.Status.NOT_FOUND, "no such map: $parsed")
        return block(found)
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

data class UploadDto(val uploadId: String, val key: String, val url: String)

data class CommitVersionRequest(
    val uploadId: String? = null,
    val sourceSha256: String? = null,
    /** What the editor started from, for provenance. */
    val parentVersion: Int? = null,
    val note: String? = null,
)

data class PublishVersionRequest(
    val bundleSha256: String = "",
    val manifestSha256: String? = null,
    val sizeBytes: Long = 0,
    val presentChunks: Int? = null,
    val estLoadedMib: Int? = null,
)

data class ForkRequest(
    /** The new address, e.g. `bedwars/4x4-baumhaus-winter`. */
    val target: String = "",
    /** Defaults to the latest published version. */
    val fromVersion: Int? = null,
    val displayName: String? = null,
)

data class MovePinRequest(val version: Int = 0)

data class VersionDto(
    val version: Int,
    val state: String,
    val bundleSha256: String?,
    val sourceSha256: String?,
    val parentVersion: Int?,
    val sizeBytes: Long?,
    val presentChunks: Int?,
    val estLoadedMib: Int?,
    val publishedBySub: String,
    val note: String?,
    val createdAt: Instant,
)

data class PinDto(
    val environment: String,
    val address: String,
    val version: Int,
    val movedBySub: String,
    val movedAt: Instant,
    /**
     * False when the move is committed but its projection on the CDN is not yet written. The caller
     * can surface "saved, not visible to servers yet" instead of pretending either.
     */
    val pinFilePublished: Boolean,
)

private fun MapVersionRecord.toDto(): VersionDto =
    VersionDto(
        version = version,
        state = state.name,
        bundleSha256 = bundleSha256,
        sourceSha256 = sourceSha256,
        parentVersion = parentVersion,
        sizeBytes = sizeBytes,
        presentChunks = presentChunks,
        estLoadedMib = estLoadedMib,
        publishedBySub = publishedBySub,
        note = note,
        createdAt = createdAt,
    )

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
