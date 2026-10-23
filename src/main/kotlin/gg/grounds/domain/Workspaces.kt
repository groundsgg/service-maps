package gg.grounds.domain

import java.time.Instant

/** One file of a buildserver's working copy, relative to the server root (`/app`). */
data class WorkspaceFile(val path: String, val sha256: String, val size: Long)

/** A blob the client was told to upload for an open snapshot. */
data class WorkspaceBlob(val sha256: String, val size: Long)

data class WorkspaceSnapshot(
    val server: String,
    val snapshot: Long,
    val committed: Boolean,
    val base: Long?,
    val files: List<WorkspaceFile>,
    val pending: List<WorkspaceBlob>,
    val totalBytes: Long,
    val createdAt: Instant,
)

/** What opening a snapshot hands back: its number and the blobs the bucket does not have yet. */
data class OpenedSnapshot(val snapshot: Long, val missing: List<WorkspaceBlob>)

/** The head moved since the client last restored or committed: another writer, or a stale one. */
class WorkspaceHeadMovedException(val head: Long?, val base: Long?) :
    RuntimeException("head is ${head ?: "none"}, not ${base ?: "none"}")

/** A snapshot that would shrink the working copy sharply, sent without `shrink`. */
class WorkspaceShrinkRefusedException(message: String) : RuntimeException(message)

class WorkspaceSnapshotNotFoundException(server: String, snapshot: Long) :
    RuntimeException("no open snapshot $snapshot for $server")

/**
 * The rules a manifest has to meet, checked once at the API boundary. Paths become file paths on
 * restore, so anything that could escape the server root is refused here rather than trusted there.
 */
object WorkspaceManifest {

    const val MAX_FILES = 20_000

    private val SERVER = Regex("[a-z0-9][a-z0-9-]{0,62}")
    private val PATH = Regex("[A-Za-z0-9._/-]{1,512}")
    private val SHA256 = Regex("[0-9a-f]{64}")

    fun isValidServer(server: String): Boolean = SERVER.matches(server)

    /** The first problem with [files], or null when the manifest is acceptable. */
    fun problem(files: List<WorkspaceFile>): String? {
        if (files.size > MAX_FILES) return "at most $MAX_FILES files, got ${files.size}"
        val seen = HashSet<String>()
        for (file in files) {
            pathProblem(file.path)?.let {
                return it
            }
            if (!seen.add(file.path)) return "duplicate path: ${file.path}"
            if (!SHA256.matches(file.sha256)) return "not a sha256: ${file.sha256}"
            if (file.size < 0) return "negative size for ${file.path}"
        }
        return null
    }

    private fun pathProblem(path: String): String? {
        if (!PATH.matches(path)) return "not an allowed path: $path"
        if (path.startsWith("/")) return "path must be relative: $path"
        if (path.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            return "path has an empty, . or .. segment: $path"
        }
        return null
    }

    /**
     * Why [next] would replace [head] with much less, or null when it would not. A booting server
     * that restored nothing looks exactly like a builder who deleted everything; only the second
     * one says so.
     */
    fun shrinkProblem(head: List<WorkspaceFile>, next: List<WorkspaceFile>): String? {
        val headBytes = head.sumOf { it.size }
        val nextBytes = next.sumOf { it.size }
        if (nextBytes * 2 < headBytes) {
            return "snapshot is $nextBytes bytes, less than half of the head's $headBytes"
        }
        val nextPaths = next.mapTo(HashSet()) { it.path }
        val lostWorld = head.firstOrNull { it.path.endsWith("/level.dat") && it.path !in nextPaths }
        return lostWorld?.let { "snapshot drops the world at ${it.path.removeSuffix("level.dat")}" }
    }
}

interface WorkspaceRepository {

    /**
     * Opens a snapshot on top of [base]. Throws [WorkspaceHeadMovedException] when [base] is not
     * the head and [WorkspaceShrinkRefusedException] when the snapshot would shrink the working
     * copy without [shrink].
     */
    fun open(
        server: String,
        base: Long?,
        files: List<WorkspaceFile>,
        shrink: Boolean,
        bySub: String,
    ): OpenedSnapshot

    fun findOpen(server: String, snapshot: Long): WorkspaceSnapshot?

    /**
     * Makes an open snapshot the head. The caller has verified its pending blobs exist; this
     * records them and moves the head, conditional on the snapshot's base still being the head.
     */
    fun commit(server: String, snapshot: Long)

    fun head(server: String): WorkspaceSnapshot?

    fun servers(): List<String>

    /**
     * Drops snapshots outside the retention policy and returns how many it dropped (or would drop,
     * with [dryRun]).
     */
    fun prune(server: String, now: Instant, dryRun: Boolean): Int

    /**
     * Forgets recorded blobs no remaining snapshot names, and returns every digest that a snapshot
     * still names. Both in one transaction, so the reference set and the forgetting agree.
     */
    fun forgetUnreferencedBlobs(server: String, dryRun: Boolean): Set<String>
}
