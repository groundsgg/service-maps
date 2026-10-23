package gg.grounds.api

import gg.grounds.authz.Authorizer
import gg.grounds.blob.BlobStore
import gg.grounds.domain.WorkspaceFile
import gg.grounds.domain.WorkspaceHeadMovedException
import gg.grounds.domain.WorkspaceManifest
import gg.grounds.domain.WorkspaceRepository
import gg.grounds.domain.WorkspaceShrinkRefusedException
import gg.grounds.domain.WorkspaceSnapshotNotFoundException
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * A buildserver's working copy: its worlds and plugin state, synced about once a minute so a pod
 * that loses its disk can be put back. A snapshot is a backup of a server, not a map version — it
 * is never pinned, derived or listed in a map's history.
 *
 * Bytes go straight to the private bucket through presigned PUTs, content-addressed per server, so
 * an unchanged region file is uploaded once and then never again. A snapshot only becomes the head
 * on commit, after every blob it introduced is confirmed present: a restore gets either the last
 * complete snapshot or nothing.
 *
 * See docs/superpowers/specs/2026-10-23-buildserver-working-copy-design.md.
 */
@Tag(name = "Workspaces")
@Path("/v1/workspaces/{server}")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
class WorkspacesResource
@Inject
constructor(
    private val workspaces: WorkspaceRepository,
    private val blobs: BlobStore,
    private val authz: Authorizer,
) {

    @Operation(
        summary = "Open a working-copy snapshot",
        description =
            "Returns the snapshot number and a presigned PUT for every blob the bucket does not " +
                "have yet. Refused with 409 when base is not the head.",
    )
    @POST
    @Path("/snapshots")
    fun open(@PathParam("server") server: String, request: OpenSnapshotRequest?): Response =
        withServer(server) {
            val files =
                request?.files
                    ?: return@withServer problem(Response.Status.BAD_REQUEST, "files are required")
            WorkspaceManifest.problem(files)?.let {
                return@withServer problem(Response.Status.BAD_REQUEST, it)
            }
            try {
                val opened =
                    workspaces.open(server, request.base, files, request.shrink, authz.subject)
                Response.status(Response.Status.CREATED)
                    .entity(
                        OpenedSnapshotDto(
                            snapshot = opened.snapshot,
                            missing =
                                opened.missing.map {
                                    MissingBlobDto(
                                        it.sha256,
                                        blobs.presignPut(
                                            BlobStore.workspaceBlobKey(server, it.sha256)
                                        ),
                                    )
                                },
                        )
                    )
                    .build()
            } catch (e: WorkspaceHeadMovedException) {
                problem(Response.Status.CONFLICT, e.message ?: "head moved")
            } catch (e: WorkspaceShrinkRefusedException) {
                problem(
                    Response.Status.CONFLICT,
                    "${e.message}; send shrink=true if this is intended",
                )
            }
        }

    @Operation(
        summary = "Commit a working-copy snapshot",
        description = "Makes the snapshot the head once every blob it introduced is in the bucket.",
    )
    @POST
    @Path("/snapshots/{snapshot}/commit")
    fun commit(
        @PathParam("server") server: String,
        @PathParam("snapshot") snapshot: Long,
    ): Response =
        withServer(server) {
            val open =
                workspaces.findOpen(server, snapshot)
                    ?: return@withServer problem(
                        Response.Status.NOT_FOUND,
                        "no open snapshot $snapshot for $server",
                    )
            // Only the blobs this snapshot introduced. Everything else was recorded by an earlier
            // commit, and checking it again would be a HEAD per file every minute.
            val absent =
                open.pending.filter {
                    blobs.headPrivate(BlobStore.workspaceBlobKey(server, it.sha256))?.sizeBytes !=
                        it.size
                }
            if (absent.isNotEmpty()) {
                return@withServer problem(
                    Response.Status.CONFLICT,
                    "${absent.size} blob(s) missing or the wrong size, e.g. ${absent.first().sha256}",
                )
            }
            try {
                workspaces.commit(server, snapshot)
                Response.ok(HeadDto(snapshot)).build()
            } catch (e: WorkspaceHeadMovedException) {
                problem(Response.Status.CONFLICT, e.message ?: "head moved")
            } catch (e: WorkspaceSnapshotNotFoundException) {
                problem(Response.Status.NOT_FOUND, e.message ?: "no such snapshot")
            }
        }

    @Operation(
        summary = "Read the working-copy head",
        description =
            "Every file of the head snapshot with a presigned GET. 404 before the first commit.",
    )
    @GET
    @Path("/head")
    fun head(@PathParam("server") server: String): Response =
        withServer(server) {
            val head =
                workspaces.head(server)
                    ?: return@withServer problem(
                        Response.Status.NOT_FOUND,
                        "$server has no working copy yet",
                    )
            val urls =
                head.files
                    .map { it.sha256 }
                    .distinct()
                    .associateWith { blobs.presignGet(BlobStore.workspaceBlobKey(server, it)) }
            Response.ok(
                    HeadSnapshotDto(
                        snapshot = head.snapshot,
                        files =
                            head.files.map {
                                HeadFileDto(it.path, it.sha256, it.size, urls.getValue(it.sha256))
                            },
                    )
                )
                .build()
        }

    private fun withServer(server: String, block: () -> Response): Response {
        if (!WorkspaceManifest.isValidServer(server)) {
            return problem(Response.Status.BAD_REQUEST, "not a valid server id: $server")
        }
        if (!authz.mayKeepWorkspace()) {
            return problem(Response.Status.FORBIDDEN, "not allowed to keep a working copy")
        }
        return block()
    }

    private fun problem(status: Response.Status, detail: String): Response =
        Response.status(status)
            .type("application/problem+json")
            .entity(ProblemDto(status.statusCode, detail))
            .build()
}

data class OpenSnapshotRequest(
    /** The snapshot last restored or committed; null when the server has none. */
    val base: Long? = null,
    val files: List<WorkspaceFile>? = null,
    /** Allows a snapshot much smaller than the head, e.g. after a world was deleted. */
    val shrink: Boolean = false,
)

data class MissingBlobDto(val sha256: String, val url: String)

data class OpenedSnapshotDto(val snapshot: Long, val missing: List<MissingBlobDto>)

data class HeadDto(val head: Long)

data class HeadFileDto(val path: String, val sha256: String, val size: Long, val url: String)

data class HeadSnapshotDto(val snapshot: Long, val files: List<HeadFileDto>)
