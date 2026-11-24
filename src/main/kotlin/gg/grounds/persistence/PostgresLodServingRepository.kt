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
        require(run.mode == LodServingMode.PILOT) {
            "Entitled rows are published by the dedicated reconciler"
        }
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
                        ps.setTimestamp(5, run.expiresAt?.let(Timestamp::from))
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

    override fun deliveryBudgetTokenSha256(budgetId: String): String? =
        dataSource.connection.use { c ->
            c.prepareStatement(
                    "SELECT token_sha256 FROM map_lod_delivery_budget WHERE id=? AND enabled"
                )
                .use { ps ->
                    ps.setString(1, budgetId)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
        }

    override fun reconcileEntitled(
        budgetId: String,
        generationBudgetId: String,
        now: Instant,
    ): Int =
        dataSource.connection.use { c ->
            c.prepareStatement(
                    """INSERT INTO map_lod_serving_run(id,artifact_attempt,token_sha256,allowed_players,starts_at,expires_at,created_by,mode,budget_id,generation_budget_id)
                SELECT gen_random_uuid(),a.attempt,b.token_sha256,ARRAY[]::uuid[],?,NULL,'stage-entitled-reconciler','ENTITLED',b.id,?
                FROM map_lod a JOIN map_lod_run r ON r.id=a.run_id
                JOIN map_lod_auto_selection auto ON auto.run_id=r.id AND auto.identity_sha256=a.identity_sha256
                JOIN map_lod_delivery_budget b ON b.id=? AND b.enabled
                WHERE auto.budget_id=? AND """ +
                        CURRENT_ARTIFACT +
                        " ON CONFLICT DO NOTHING"
                )
                .use { ps ->
                    ps.setTimestamp(1, Timestamp.from(now))
                    ps.setString(2, generationBudgetId)
                    ps.setString(3, budgetId)
                    ps.setString(4, generationBudgetId)
                    ps.executeUpdate()
                }
        }

    override fun discoverEntitled(
        budgetId: String,
        generationBudgetId: String,
        identity: LodServingIdentity,
    ): LodServingSnapshot? =
        dataSource.connection.use { c ->
            val id =
                c.prepareStatement(
                        """SELECT s.id FROM map_lod_serving_run s JOIN map_lod a ON a.attempt=s.artifact_attempt
                WHERE s.mode='ENTITLED' AND s.enabled AND s.budget_id=? AND s.generation_budget_id=?
                AND a.map_bundle_sha256=? AND a.source_bundle_sha256=? AND a.registry_sha256=? AND a.generator_sha256=?
                AND """ +
                            CURRENT_SELECTION
                    )
                    .use { ps ->
                        ps.setString(1, budgetId)
                        ps.setString(2, generationBudgetId)
                        ps.setString(3, identity.mapBundleSha256)
                        ps.setString(4, identity.sourceBundleSha256)
                        ps.setString(5, identity.registrySha256)
                        ps.setString(6, identity.generatorSha256)
                        ps.executeQuery().use { rs ->
                            if (rs.next()) rs.getObject(1, UUID::class.java) else null
                        }
                    }
            id?.let { snapshot(c, it, false) }
        }

    override fun isCurrentEntitled(
        runId: UUID,
        budgetId: String,
        generationBudgetId: String,
    ): Boolean =
        dataSource.connection.use { c -> currentSelection(c, runId, budgetId, generationBudgetId) }

    private fun currentSelection(
        c: Connection,
        runId: UUID,
        budgetId: String,
        generationBudgetId: String,
    ): Boolean =
        c.prepareStatement(
                """SELECT 1 FROM map_lod_serving_run s JOIN map_lod a ON a.attempt=s.artifact_attempt
            WHERE s.id=? AND s.mode='ENTITLED' AND s.enabled AND s.budget_id=? AND s.generation_budget_id=? AND """ +
                    CURRENT_SELECTION
            )
            .use { ps ->
                ps.setObject(1, runId)
                ps.setString(2, budgetId)
                ps.setString(3, generationBudgetId)
                ps.executeQuery().use { it.next() }
            }

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
            val end = minOf(Instant.ofEpochSecond(second + 1), current.run.expiresAt ?: Instant.MAX)
            val ttl = java.time.Duration.between(at, end).toMillis()
            if (ttl <= 0) denied(LodServingRefusal.RATE)
            debit(c, runId, requestId, player, "PAYLOAD", bytes, at)
            val entitled = current.run.mode == LodServingMode.ENTITLED
            val allocated =
                c.prepareStatement(
                        if (entitled)
                            """INSERT INTO map_lod_delivery_window(budget_id,player,window_second,bytes_reserved) VALUES (?,?,?,?)
                    ON CONFLICT (budget_id,player,window_second) DO UPDATE SET bytes_reserved=map_lod_delivery_window.bytes_reserved+EXCLUDED.bytes_reserved
                    WHERE map_lod_delivery_window.bytes_reserved+EXCLUDED.bytes_reserved<=524288"""
                        else
                            """INSERT INTO map_lod_serving_window(run_id,player,window_second,bytes_reserved) VALUES (?,?,?,?)
                ON CONFLICT (run_id,player,window_second) DO UPDATE SET bytes_reserved=map_lod_serving_window.bytes_reserved+EXCLUDED.bytes_reserved
                WHERE map_lod_serving_window.bytes_reserved+EXCLUDED.bytes_reserved<=524288"""
                    )
                    .use { ps ->
                        if (entitled) ps.setString(1, current.run.budgetId)
                        else ps.setObject(1, runId)
                        ps.setObject(2, player)
                        ps.setLong(3, second)
                        ps.setInt(4, bytes)
                        ps.executeUpdate() == 1
                    }
            if (!allocated) denied(LodServingRefusal.RATE)
            if (entitled) {
                val global =
                    c.prepareStatement(
                            """INSERT INTO map_lod_delivery_global_window(budget_id,window_second,bytes_reserved) VALUES (?,?,?)
                    ON CONFLICT (budget_id,window_second) DO UPDATE SET bytes_reserved=map_lod_delivery_global_window.bytes_reserved+EXCLUDED.bytes_reserved
                    WHERE map_lod_delivery_global_window.bytes_reserved+EXCLUDED.bytes_reserved<=4194304"""
                        )
                        .use { ps ->
                            ps.setString(1, current.run.budgetId)
                            ps.setLong(2, second)
                            ps.setInt(3, bytes)
                            ps.executeUpdate() == 1
                        }
                if (!global) denied(LodServingRefusal.RATE)
                val budget =
                    c.prepareStatement(
                            "UPDATE map_lod_delivery_budget SET payload_reserved=payload_reserved+?,payload_grants_reserved=payload_grants_reserved+1 WHERE id=? AND payload_reserved+?<=1073741824 AND payload_grants_reserved<8192"
                        )
                        .use { ps ->
                            ps.setInt(1, bytes)
                            ps.setString(2, current.run.budgetId)
                            ps.setInt(3, bytes)
                            ps.executeUpdate() == 1
                        }
                if (!budget) denied(LodServingRefusal.BUDGET)
            }
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
        if (current.run.mode == LodServingMode.ENTITLED) {
            val budget =
                c.prepareStatement(
                        """UPDATE map_lod_delivery_budget SET remote_reads_reserved=remote_reads_reserved+2,remote_bytes_reserved=remote_bytes_reserved+?
                WHERE id=? AND remote_reads_reserved+2<=10000 AND remote_bytes_reserved+?<=1073741824"""
                    )
                    .use { ps ->
                        ps.setLong(1, envelopeBytes)
                        ps.setString(2, current.run.budgetId)
                        ps.setLong(3, envelopeBytes)
                        ps.executeUpdate() == 1
                    }
            if (!budget) denied(LodServingRefusal.BUDGET)
        }
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
            minOf(
                120000L,
                current.run.expiresAt?.let { java.time.Duration.between(at, it).toMillis() }
                    ?: 120000L,
            ),
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
        val (current, at) =
            current(
                c,
                runId,
                snapshot.artifact.mapBundleSha256,
                player,
                now,
                requireCurrentPin = false,
            )
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
        requireCurrentPin: Boolean = true,
    ): Pair<LodServingSnapshot, Instant> {
        val current = snapshot(c, runId, true) ?: denied(LodServingRefusal.CLOSED)
        if (current.run.mode == LodServingMode.ENTITLED) {
            // Every allocation across selections/replicas serializes on the stable budget row.
            val active =
                c.prepareStatement(
                        "SELECT enabled,token_sha256 FROM map_lod_delivery_budget WHERE id=? FOR UPDATE"
                    )
                    .use { ps ->
                        ps.setString(1, current.run.budgetId)
                        ps.executeQuery().use { rs ->
                            rs.next() &&
                                rs.getBoolean(1) &&
                                rs.getString(2) == current.run.tokenSha256
                        }
                    }
            if (
                !active ||
                    (requireCurrentPin &&
                        !currentSelection(
                            c,
                            runId,
                            requireNotNull(current.run.budgetId),
                            requireNotNull(current.run.generationBudgetId),
                        ))
            )
                denied(LodServingRefusal.CLOSED)
        }
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
            !current.run.enabled ||
                at < current.run.startsAt ||
                current.run.expiresAt?.let { !at.isBefore(it) } == true
        )
            denied(LodServingRefusal.CLOSED)
        if (current.artifact.mapBundleSha256 != mapSha) denied(LodServingRefusal.SOURCE)
        if (current.run.mode == LodServingMode.PILOT && player !in current.run.players)
            denied(LodServingRefusal.PLAYER)
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
                            rs.getTimestamp("expires_at")?.toInstant(),
                            rs.getString("serving_created_by"),
                            rs.getBoolean("enabled"),
                            LodServingMode.valueOf(rs.getString("mode")),
                            rs.getString("budget_id"),
                            rs.getString("generation_budget_id"),
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
            s.created_by AS serving_created_by,s.enabled,s.mode,s.budget_id,s.generation_budget_id,
            COALESCE(b.payload_reserved,s.payload_reserved) AS payload_reserved,
            COALESCE(b.remote_reads_reserved,s.remote_reads_reserved) AS remote_reads_reserved,
            COALESCE(b.remote_bytes_reserved,s.remote_bytes_reserved) AS remote_bytes_reserved,a.*
            FROM map_lod_serving_run s JOIN map_lod a ON a.attempt=s.artifact_attempt AND a.state='READY'
            LEFT JOIN map_lod_delivery_budget b ON b.id=s.budget_id WHERE s.id=?"""

        private const val CURRENT_ARTIFACT =
            """
            a.state='READY' AND a.trust='FIRST_PARTY' AND NOT a.published
            AND r.environment='stage' AND r.namespace='lobby' AND r.name='mainlobby'
            AND a.map_bundle_sha256=r.map_bundle_sha256 AND a.source_bundle_sha256=r.source_bundle_sha256
            AND a.registry_sha256=r.registry_sha256 AND a.generator_sha256=r.generator_sha256
            AND EXISTS (SELECT 1 FROM map m JOIN map_pin p ON p.map=m.id AND p.environment='stage'
                JOIN map_version v ON v.map=m.id AND v.version=p.version AND v.state='PUBLISHED'
                JOIN map_lit l ON l.bundle_sha256=v.bundle_sha256 AND l.state='LIT' AND l.light_format=2
                WHERE m.namespace='lobby' AND m.name='mainlobby' AND m.trust='FIRST_PARTY'
                AND v.bundle_sha256=a.map_bundle_sha256 AND l.lit_sha256=a.source_bundle_sha256)
        """
        private const val CURRENT_SELECTION =
            """
            EXISTS (SELECT 1 FROM map_lod_run r
                JOIN map_lod_auto_selection auto ON auto.run_id=r.id AND auto.identity_sha256=a.identity_sha256
                JOIN map_lod_delivery_budget b ON b.id=s.budget_id AND b.enabled AND b.token_sha256=s.token_sha256
                WHERE r.id=a.run_id AND auto.budget_id=s.generation_budget_id AND """ +
                CURRENT_ARTIFACT +
                ")"
    }
}
