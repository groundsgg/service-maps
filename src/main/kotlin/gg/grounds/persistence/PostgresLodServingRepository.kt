package gg.grounds.persistence

import gg.grounds.domain.*
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@ApplicationScoped
class PostgresLodServingRepository @Inject constructor(private val dataSource: DataSource) :
    LodServingRepository {
    override fun register(run: LodServingRun) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                    """INSERT INTO map_lod_serving_run(id,artifact_attempt,token_sha256,allowed_players,starts_at,expires_at,created_by,enabled)
            SELECT ?,a.attempt,?,?,?,?,?,? FROM map_lod a WHERE a.attempt=? AND a.state='READY'"""
                )
                .use { ps ->
                    ps.setObject(1, run.id)
                    ps.setString(2, run.tokenSha256)
                    val array = c.createArrayOf("uuid", run.players.toTypedArray())
                    try {
                        ps.setArray(3, array)
                        ps.setTimestamp(4, Timestamp.from(run.startsAt))
                        ps.setTimestamp(5, Timestamp.from(run.expiresAt))
                        ps.setString(6, run.createdBy)
                        ps.setBoolean(7, run.enabled)
                        ps.setObject(8, run.artifactAttempt)
                        if (ps.executeUpdate() != 1) denied(LodServingRefusal.SOURCE)
                    } finally {
                        array.free()
                    }
                }
        }
    }

    override fun find(runId: UUID): LodServingSnapshot? =
        dataSource.connection.use { snapshot(it, runId, false) }

    override fun payload(
        runId: UUID,
        requestId: UUID,
        mapSha: String,
        player: UUID,
        bytes: Int,
        now: (() -> Instant)?,
    ): LodPayloadCredit {
        require(bytes in 1..MAX_GRANT_BYTES)
        return transaction { c ->
            val (current, at) = current(c, runId, mapSha, player, now)
            val second = Math.floorDiv(at.toEpochMilli(), 1000)
            val end = minOf(Instant.ofEpochSecond(second + 1), current.run.expiresAt)
            val ttl = java.time.Duration.between(at, end).toMillis()
            if (ttl <= 0) denied(LodServingRefusal.RATE)
            debit(c, runId, requestId, player, "PAYLOAD", bytes, at)
            val allocated =
                c.prepareStatement(
                        """INSERT INTO map_lod_serving_window(run_id,player,window_second,bytes_reserved) VALUES (?,?,?,?)
                ON CONFLICT (run_id,player,window_second) DO UPDATE SET bytes_reserved=map_lod_serving_window.bytes_reserved+EXCLUDED.bytes_reserved
                WHERE map_lod_serving_window.bytes_reserved+EXCLUDED.bytes_reserved<=524288"""
                    )
                    .use { ps ->
                        ps.setObject(1, runId)
                        ps.setObject(2, player)
                        ps.setLong(3, second)
                        ps.setInt(4, bytes)
                        ps.executeUpdate() == 1
                    }
            if (!allocated) denied(LodServingRefusal.RATE)
            val reserved =
                c.prepareStatement(
                        "UPDATE map_lod_serving_run SET payload_reserved=payload_reserved+?,payload_grants_reserved=payload_grants_reserved+1 WHERE id=? AND payload_reserved+?<=1073741824 AND payload_grants_reserved<8192"
                    )
                    .use { ps ->
                        ps.setInt(1, bytes)
                        ps.setObject(2, runId)
                        ps.setInt(3, bytes)
                        ps.executeUpdate() == 1
                    }
            if (!reserved) denied(LodServingRefusal.BUDGET)
            LodPayloadCredit(bytes, ttl)
        }
    }

    override fun remote(
        runId: UUID,
        requestId: UUID,
        mapSha: String,
        player: UUID,
        now: (() -> Instant)?,
    ): LodRemoteCredit = transaction { c ->
        val (current, at) = current(c, runId, mapSha, player, now)
        val bytes = requireNotNull(current.artifact.facts).bundleBytes
        // SDK retries are disabled. JDK HttpURLConnection can still retry one failed header read.
        val envelopeBytes = Math.multiplyExact(bytes, 2)
        debit(c, runId, requestId, player, "REMOTE", bytes.toInt(), at)
        val reserved =
            c.prepareStatement(
                    """UPDATE map_lod_serving_run SET remote_reads_reserved=remote_reads_reserved+2,remote_bytes_reserved=remote_bytes_reserved+?
            WHERE id=? AND remote_reads_reserved+2<=10000 AND remote_bytes_reserved+?<=1073741824"""
                )
                .use { ps ->
                    ps.setLong(1, envelopeBytes)
                    ps.setObject(2, runId)
                    ps.setLong(3, envelopeBytes)
                    ps.executeUpdate() == 1
                }
        if (!reserved) denied(LodServingRefusal.BUDGET)
        LodRemoteCredit(
            current.copy(
                remoteReadsReserved = current.remoteReadsReserved + 2,
                remoteBytesReserved = current.remoteBytesReserved + envelopeBytes,
            ),
            minOf(120000L, java.time.Duration.between(at, current.run.expiresAt).toMillis()),
        )
    }

    override fun consumeRemote(
        runId: UUID,
        requestId: UUID,
        now: (() -> Instant)?,
    ): LodServingSnapshot = transaction { c ->
        val snapshot = snapshot(c, runId, true) ?: denied(LodServingRefusal.CLOSED)
        val (player, createdAt, consumed) =
            c.prepareStatement(
                    "SELECT player,created_at,consumed_at FROM map_lod_serving_debit WHERE run_id=? AND request_id=? AND kind='REMOTE'"
                )
                .use { ps ->
                    ps.setObject(1, runId)
                    ps.setObject(2, requestId)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) denied(LodServingRefusal.SOURCE)
                        Triple(
                            rs.getObject(1, UUID::class.java),
                            rs.getTimestamp(2).toInstant(),
                            rs.getTimestamp(3) != null,
                        )
                    }
                }
        val (current, at) = current(c, runId, snapshot.artifact.mapBundleSha256, player, now)
        if (consumed || !at.isBefore(createdAt.plusSeconds(120))) denied(LodServingRefusal.CLOSED)
        c.prepareStatement(
                "UPDATE map_lod_serving_debit SET consumed_at=? WHERE run_id=? AND request_id=? AND consumed_at IS NULL"
            )
            .use { ps ->
                ps.setTimestamp(1, Timestamp.from(at))
                ps.setObject(2, runId)
                ps.setObject(3, requestId)
                check(ps.executeUpdate() == 1)
            }
        current
    }

    private fun current(
        c: Connection,
        runId: UUID,
        mapSha: String,
        player: UUID,
        now: (() -> Instant)?,
    ): Pair<LodServingSnapshot, Instant> {
        val current = snapshot(c, runId, true) ?: denied(LodServingRefusal.CLOSED)
        // One authoritative clock across controller replicas; explicit clocks are a test seam.
        val at =
            now?.invoke()
                ?: c.createStatement().use {
                    it.executeQuery("SELECT clock_timestamp()").use { rs ->
                        check(rs.next())
                        rs.getTimestamp(1).toInstant()
                    }
                }
        if (
            !current.run.enabled || at < current.run.startsAt || !at.isBefore(current.run.expiresAt)
        )
            denied(LodServingRefusal.CLOSED)
        if (current.artifact.mapBundleSha256 != mapSha) denied(LodServingRefusal.SOURCE)
        if (player !in current.run.players) denied(LodServingRefusal.PLAYER)
        return current to at
    }

    private fun debit(
        c: Connection,
        runId: UUID,
        requestId: UUID,
        player: UUID,
        kind: String,
        amount: Int,
        now: Instant,
    ) {
        val inserted =
            c.prepareStatement(
                    """INSERT INTO map_lod_serving_debit(run_id,request_id,player,kind,amount,created_at)
            VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING"""
                )
                .use { ps ->
                    ps.setObject(1, runId)
                    ps.setObject(2, requestId)
                    ps.setObject(3, player)
                    ps.setString(4, kind)
                    ps.setInt(5, amount)
                    ps.setTimestamp(6, Timestamp.from(now))
                    ps.executeUpdate() == 1
                }
        if (!inserted) denied(LodServingRefusal.DUPLICATE)
    }

    private fun snapshot(c: Connection, runId: UUID, lock: Boolean): LodServingSnapshot? =
        c.prepareStatement(SELECT + if (lock) " FOR UPDATE OF s" else "").use { ps ->
            ps.setObject(1, runId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null
                else {
                    val array = rs.getArray("allowed_players")
                    val players =
                        try {
                            (array.array as Array<*>).map { UUID.fromString(it.toString()) }.toSet()
                        } finally {
                            array.free()
                        }
                    val run =
                        LodServingRun(
                            rs.getObject("serving_id", UUID::class.java),
                            rs.getObject("artifact_attempt", UUID::class.java),
                            rs.getString("token_sha256"),
                            players,
                            rs.getTimestamp("starts_at").toInstant(),
                            rs.getTimestamp("expires_at").toInstant(),
                            rs.getString("serving_created_by"),
                            rs.getBoolean("enabled"),
                        )
                    LodServingSnapshot(
                        run,
                        readLodClaim(rs),
                        rs.getLong("payload_reserved"),
                        rs.getInt("remote_reads_reserved"),
                        rs.getLong("remote_bytes_reserved"),
                    )
                }
            }
        }

    private fun <T> transaction(block: (Connection) -> T): T =
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val result = block(c)
                c.commit()
                result
            } catch (failure: Throwable) {
                c.rollback()
                throw failure
            }
        }

    private fun denied(reason: LodServingRefusal): Nothing = throw LodServingDenied(reason)

    companion object {
        const val MAX_GRANT_BYTES = 256 * 1024
        private const val SELECT =
            """SELECT s.id AS serving_id,s.artifact_attempt,s.token_sha256,s.allowed_players,s.starts_at,s.expires_at,
            s.created_by AS serving_created_by,s.enabled,s.payload_reserved,s.remote_reads_reserved,s.remote_bytes_reserved,a.*
            FROM map_lod_serving_run s JOIN map_lod a ON a.attempt=s.artifact_attempt AND a.state='READY' WHERE s.id=?"""
    }
}
