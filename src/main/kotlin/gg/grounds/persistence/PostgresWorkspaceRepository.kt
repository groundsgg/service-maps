package gg.grounds.persistence

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.domain.OpenedSnapshot
import gg.grounds.domain.WorkspaceBlob
import gg.grounds.domain.WorkspaceFile
import gg.grounds.domain.WorkspaceHeadMovedException
import gg.grounds.domain.WorkspaceManifest
import gg.grounds.domain.WorkspaceRepository
import gg.grounds.domain.WorkspaceShrinkRefusedException
import gg.grounds.domain.WorkspaceSnapshot
import gg.grounds.domain.WorkspaceSnapshotNotFoundException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

@ApplicationScoped
class PostgresWorkspaceRepository
@Inject
constructor(private val dataSource: DataSource, private val json: ObjectMapper) :
    WorkspaceRepository {

    override fun open(
        server: String,
        base: Long?,
        files: List<WorkspaceFile>,
        shrink: Boolean,
        bySub: String,
    ): OpenedSnapshot =
        transaction(server) { c ->
            val head = readHead(c, server)
            if (head?.snapshot != base) throw WorkspaceHeadMovedException(head?.snapshot, base)
            if (head != null && !shrink) {
                WorkspaceManifest.shrinkProblem(head.files, files)?.let {
                    throw WorkspaceShrinkRefusedException(it)
                }
            }

            val known = knownBlobs(c, server)
            val missing =
                files
                    .filter { it.sha256 !in known }
                    .distinctBy { it.sha256 }
                    .map { WorkspaceBlob(it.sha256, it.size) }

            val snapshot =
                c.prepareStatement(
                        "SELECT COALESCE(MAX(snapshot), 0) + 1 FROM workspace_snapshot WHERE server = ?"
                    )
                    .use { ps ->
                        ps.setString(1, server)
                        ps.executeQuery().use { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                    }

            c.prepareStatement(
                    """
                INSERT INTO workspace_snapshot
                    (server, snapshot, state, base, manifest, pending, file_count, total_bytes, created_by)
                VALUES (?, ?, 'OPEN', ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                """
                )
                .use { ps ->
                    ps.setString(1, server)
                    ps.setLong(2, snapshot)
                    ps.setObject(3, base)
                    ps.setString(4, json.writeValueAsString(files.sortedBy { it.path }))
                    ps.setString(5, json.writeValueAsString(missing))
                    ps.setInt(6, files.size)
                    ps.setLong(7, files.sumOf { it.size })
                    ps.setString(8, bySub)
                    ps.executeUpdate()
                }
            OpenedSnapshot(snapshot, missing)
        }

    override fun findOpen(server: String, snapshot: Long): WorkspaceSnapshot? =
        dataSource.connection.use { c ->
            c.prepareStatement(
                    "$SELECT_SNAPSHOT WHERE server = ? AND snapshot = ? AND state = 'OPEN'"
                )
                .use { ps ->
                    ps.setString(1, server)
                    ps.setLong(2, snapshot)
                    ps.executeQuery().use { rs -> if (rs.next()) snapshot(rs) else null }
                }
        }

    override fun commit(server: String, snapshot: Long): Unit =
        transaction(server) { c ->
            val open =
                c.prepareStatement(
                        "$SELECT_SNAPSHOT WHERE server = ? AND snapshot = ? AND state = 'OPEN'"
                    )
                    .use { ps ->
                        ps.setString(1, server)
                        ps.setLong(2, snapshot)
                        ps.executeQuery().use { rs -> if (rs.next()) snapshot(rs) else null }
                    } ?: throw WorkspaceSnapshotNotFoundException(server, snapshot)
            val head = readHeadNumber(c, server)
            if (head != open.base) throw WorkspaceHeadMovedException(head, open.base)

            c.prepareStatement(
                    """
                INSERT INTO workspace_blob (server, sha256, size_bytes) VALUES (?, ?, ?)
                ON CONFLICT (server, sha256) DO NOTHING
                """
                )
                .use { ps ->
                    for (blob in open.pending) {
                        ps.setString(1, server)
                        ps.setString(2, blob.sha256)
                        ps.setLong(3, blob.size)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            c.prepareStatement(
                    """
                UPDATE workspace_snapshot SET state = 'COMMITTED', committed_at = NOW()
                 WHERE server = ? AND snapshot = ?
                """
                )
                .use { ps ->
                    ps.setString(1, server)
                    ps.setLong(2, snapshot)
                    ps.executeUpdate()
                }
            c.prepareStatement(
                    """
                INSERT INTO workspace_head (server, snapshot) VALUES (?, ?)
                ON CONFLICT (server) DO UPDATE SET snapshot = EXCLUDED.snapshot
                """
                )
                .use { ps ->
                    ps.setString(1, server)
                    ps.setLong(2, snapshot)
                    ps.executeUpdate()
                }
        }

    override fun head(server: String): WorkspaceSnapshot? =
        dataSource.connection.use { c -> readHead(c, server) }

    override fun servers(): List<String> =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT DISTINCT server FROM workspace_snapshot ORDER BY server")
                .use { ps ->
                    ps.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
        }

    override fun prune(server: String, now: Instant, dryRun: Boolean): Int =
        transaction(server) { c ->
            // Kept: the head, the newest committed snapshots, the newest one of each recent day,
            // and
            // open snapshots young enough that a client may still be uploading for them.
            val expired =
                """
            SELECT s.snapshot FROM workspace_snapshot s
              LEFT JOIN (
                SELECT snapshot,
                       ROW_NUMBER() OVER (ORDER BY snapshot DESC) AS recent,
                       ROW_NUMBER() OVER (PARTITION BY date_trunc('day', committed_at)
                                          ORDER BY snapshot DESC) AS daily,
                       committed_at
                  FROM workspace_snapshot WHERE server = ? AND state = 'COMMITTED'
              ) k ON k.snapshot = s.snapshot
             WHERE s.server = ?
               AND s.snapshot <> COALESCE((SELECT snapshot FROM workspace_head WHERE server = ?), -1)
               AND CASE s.state
                     WHEN 'OPEN' THEN s.created_at < ?::timestamptz - INTERVAL '1 hour'
                     ELSE k.recent > $KEEP_RECENT
                          AND NOT (k.daily = 1 AND k.committed_at >= ?::timestamptz - INTERVAL '$KEEP_DAYS days')
                   END
            """
            val sql =
                if (dryRun) "SELECT COUNT(*) FROM ($expired) e"
                else "DELETE FROM workspace_snapshot WHERE server = ? AND snapshot IN ($expired)"
            c.prepareStatement(sql).use { ps ->
                var i = 1
                if (!dryRun) ps.setString(i++, server)
                ps.setString(i++, server)
                ps.setString(i++, server)
                ps.setString(i++, server)
                ps.setObject(i++, Timestamp.from(now))
                ps.setObject(i, Timestamp.from(now))
                if (dryRun) ps.executeQuery().use { rs -> rs.next().let { rs.getInt(1) } }
                else ps.executeUpdate()
            }
        }

    override fun forgetUnreferencedBlobs(server: String, dryRun: Boolean): Set<String> =
        transaction(server) { c ->
            val referenced =
                c.prepareStatement(
                        """
                        SELECT DISTINCT f->>'sha256' FROM workspace_snapshot s,
                               jsonb_array_elements(s.manifest) f
                         WHERE s.server = ?
                        """
                    )
                    .use { ps ->
                        ps.setString(1, server)
                        ps.executeQuery().use { rs ->
                            buildSet { while (rs.next()) add(rs.getString(1)) }
                        }
                    }
            if (!dryRun) {
                c.prepareStatement(
                        "DELETE FROM workspace_blob WHERE server = ? AND NOT (sha256 = ANY (?))"
                    )
                    .use { ps ->
                        ps.setString(1, server)
                        ps.setArray(2, c.createArrayOf("text", referenced.toTypedArray()))
                        ps.executeUpdate()
                    }
            }
            referenced
        }

    /**
     * One writer per server at a time. An advisory lock rather than a row lock because the first
     * snapshot of a server has no row to lock yet, and Postgres has no gap locks.
     */
    private fun <T> transaction(server: String, block: (Connection) -> T): T =
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext('workspace:' || ?))")
                    .use { ps ->
                        ps.setString(1, server)
                        ps.executeQuery().close()
                    }
                block(c).also { c.commit() }
            } catch (e: Exception) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = true
            }
        }

    private fun readHeadNumber(c: Connection, server: String): Long? =
        c.prepareStatement("SELECT snapshot FROM workspace_head WHERE server = ?").use { ps ->
            ps.setString(1, server)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    private fun readHead(c: Connection, server: String): WorkspaceSnapshot? =
        c.prepareStatement(
                "$SELECT_SNAPSHOT WHERE server = ? AND snapshot = " +
                    "(SELECT snapshot FROM workspace_head WHERE server = ?)"
            )
            .use { ps ->
                ps.setString(1, server)
                ps.setString(2, server)
                ps.executeQuery().use { rs -> if (rs.next()) snapshot(rs) else null }
            }

    private fun knownBlobs(c: Connection, server: String): Set<String> =
        c.prepareStatement("SELECT sha256 FROM workspace_blob WHERE server = ?").use { ps ->
            ps.setString(1, server)
            ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }

    private fun snapshot(rs: ResultSet) =
        WorkspaceSnapshot(
            server = rs.getString("server"),
            snapshot = rs.getLong("snapshot"),
            committed = rs.getString("state") == "COMMITTED",
            base = rs.getLong("base").takeUnless { rs.wasNull() },
            files = json.readValue(rs.getString("manifest"), FILES),
            pending = json.readValue(rs.getString("pending"), BLOBS),
            totalBytes = rs.getLong("total_bytes"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )

    private companion object {
        const val KEEP_RECENT = 30
        const val KEEP_DAYS = 14
        const val SELECT_SNAPSHOT =
            "SELECT server, snapshot, state, base, manifest, pending, total_bytes, created_at " +
                "FROM workspace_snapshot"
        val FILES = object : TypeReference<List<WorkspaceFile>>() {}
        val BLOBS = object : TypeReference<List<WorkspaceBlob>>() {}
    }
}
