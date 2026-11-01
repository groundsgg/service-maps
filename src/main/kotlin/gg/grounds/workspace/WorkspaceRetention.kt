package gg.grounds.workspace

import gg.grounds.blob.BlobStore
import gg.grounds.domain.WorkspaceRepository
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
import java.time.Instant
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Keeps working copies bounded: old snapshots are dropped, then blobs no remaining snapshot names.
 *
 * Dry-runs by default, like the registry's orphan collection: it logs what it would delete until an
 * operator has seen that the numbers make sense.
 *
 * Objects are listed *before* the database decides what is unreferenced, and only objects older
 * than [MIN_BLOB_AGE] are deleted. A blob uploaded for a snapshot that opened after the decision is
 * younger than that, and even if one is deleted anyway the commit's own check finds it missing and
 * the client uploads it again on its next tick.
 */
@ApplicationScoped
class WorkspaceRetention
@Inject
constructor(
    private val workspaces: WorkspaceRepository,
    private val blobs: BlobStore,
    @ConfigProperty(name = "grounds.maps.workspace.retention.dry-run") private val dryRun: Boolean,
) {
    private val log = Logger.getLogger(WorkspaceRetention::class.java)

    @Scheduled(
        every = "{grounds.maps.workspace.retention.interval:24h}",
        delayed = "10m",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun sweep() {
        val now = Instant.now()
        for (server in workspaces.servers()) {
            val objects = blobs.listPrivate(BlobStore.workspaceBlobPrefix(server))
            val pruned = workspaces.prune(server, now, dryRun)
            val referenced = workspaces.forgetUnreferencedBlobs(server, dryRun)
            val orphans =
                objects.filter { (key, modified) ->
                    key.substringAfterLast('/') !in referenced &&
                        modified.isBefore(now.minus(MIN_BLOB_AGE))
                }
            if (!dryRun) orphans.forEach { blobs.deletePrivate(it.first) }
            log.infof(
                "workspace %s: %s %d snapshot(s) and %d of %d blob(s)",
                server,
                if (dryRun) "would delete" else "deleted",
                pruned,
                orphans.size,
                objects.size,
            )
        }
    }

    private companion object {
        val MIN_BLOB_AGE: Duration = Duration.ofDays(1)
    }
}
