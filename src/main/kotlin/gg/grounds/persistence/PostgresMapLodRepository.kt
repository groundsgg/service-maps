package gg.grounds.persistence

import gg.grounds.domain.*
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import javax.sql.DataSource

@ApplicationScoped
class PostgresMapLodRepository @Inject constructor(private val dataSource: DataSource) :
    MapLodRepository {
    override fun createRun(run: LodRun) = transaction { c -> createRun(c, run) }

    private fun createRun(c: Connection, run: LodRun) {
        c.prepareStatement("INSERT INTO map_lod_budget(id) VALUES (?) ON CONFLICT DO NOTHING")
            .use { ps ->
                ps.setString(1, run.budgetId)
                ps.executeUpdate()
            }
        c.prepareStatement(
                """INSERT INTO map_lod_run (id,budget_id,environment,namespace,name,version,map_bundle_sha256,
            source_bundle_sha256,registry_sha256,generator_sha256,worker_image,created_at,expires_at,created_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
            )
            .use { ps ->
                ps.setObject(1, run.id)
                ps.setString(2, run.budgetId)
                ps.setString(3, run.environment)
                ps.setString(4, run.address.namespace)
                ps.setString(5, run.address.name)
                ps.setInt(6, run.version)
                ps.setString(7, run.mapBundleSha256)
                ps.setString(8, run.sourceBundleSha256)
                ps.setString(9, run.registrySha256)
                ps.setString(10, run.generatorSha256)
                ps.setString(11, run.workerImage)
                ps.setTimestamp(12, Timestamp.from(run.createdAt))
                ps.setTimestamp(13, Timestamp.from(run.expiresAt))
                ps.setString(14, run.createdBy)
                ps.executeUpdate()
            }
    }

    override fun automaticRun(selection: AutoLodSelection, now: Instant): UUID? = transaction { c ->
        // The same global lock as claim serializes selection across replicas and restarts.
        c.prepareStatement("SELECT pg_advisory_xact_lock(734428501)").use { it.execute() }
        val input =
            c.prepareStatement(
                    """SELECT p.version,v.bundle_sha256,l.lit_sha256 FROM map m
            JOIN map_pin p ON p.map=m.id AND p.environment=?
            JOIN map_version v ON v.map=m.id AND v.version=p.version AND v.state='PUBLISHED'
            JOIN map_lit l ON l.bundle_sha256=v.bundle_sha256 AND l.light_format=2 AND l.state='LIT'
            WHERE m.namespace=? AND m.name=? AND m.trust='FIRST_PARTY' FOR SHARE OF p"""
                )
                .use { ps ->
                    ps.setString(1, selection.environment)
                    ps.setString(2, selection.address.namespace)
                    ps.setString(3, selection.address.name)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) Triple(rs.getInt(1), rs.getString(2), rs.getString(3))
                        else null
                    }
                } ?: return@transaction null
        val identity =
            identity(
                MapTrust.FIRST_PARTY,
                input.second,
                input.third,
                selection.registrySha256,
                selection.generatorSha256,
            )
        val remembered =
            c.prepareStatement("SELECT run_id FROM map_lod_auto_selection WHERE identity_sha256=?")
                .use { ps ->
                    ps.setString(1, identity)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getObject(1, UUID::class.java) else null
                    }
                }
        if (remembered != null) return@transaction remembered
        // Reuse even FAILED historical identities: automatic reconciliation never retries one.
        val existing =
            c.prepareStatement(
                    "SELECT run_id FROM map_lod WHERE identity_sha256=? ORDER BY CASE state WHEN 'READY' THEN 0 WHEN 'GENERATING' THEN 1 ELSE 2 END,started_at LIMIT 1"
                )
                .use { ps ->
                    ps.setString(1, identity)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getObject(1, UUID::class.java) else null
                    }
                }
        val runId =
            existing
                ?: UUID.randomUUID().also { id ->
                    createRun(
                        c,
                        LodRun(
                            id,
                            selection.budgetId,
                            selection.environment,
                            selection.address,
                            input.first,
                            input.second,
                            input.third,
                            selection.registrySha256,
                            selection.generatorSha256,
                            selection.workerImage,
                            now,
                            now.plusSeconds(1800),
                            "automatic-stage-lobby",
                        ),
                    )
                }
        c.prepareStatement("INSERT INTO map_lod_budget(id) VALUES (?) ON CONFLICT DO NOTHING")
            .use { ps ->
                ps.setString(1, selection.budgetId)
                ps.executeUpdate()
            }
        c.prepareStatement(
                "INSERT INTO map_lod_auto_selection(identity_sha256,run_id,budget_id) VALUES (?,?,?)"
            )
            .use { ps ->
                ps.setString(1, identity)
                ps.setObject(2, runId)
                ps.setString(3, selection.budgetId)
                ps.executeUpdate()
            }
        runId
    }

    override fun claim(runId: UUID, now: Instant): LodClaim? = transaction { c ->
        // Shared across every format, run and replica. Kept only for this short DB transaction.
        c.prepareStatement("SELECT pg_advisory_xact_lock(734428501)").use { it.execute() }
        if (
            c.createStatement().use {
                it.executeQuery("SELECT 1 FROM map_lod WHERE state='GENERATING'")
                    .use(ResultSet::next)
            }
        )
            return@transaction null
        // Automatic selections survive outages before their first claim. Only the manual
        // admission clock is bypassed; the current pin, format/trust, no-attempt and shared
        // quota checks below remain in force, and started_at still sets a 15-minute worker
        // deadline.
        val selection =
            c.prepareStatement(
                    """SELECT r.*, m.trust FROM map_lod_run r
            JOIN map m ON m.namespace=r.namespace AND m.name=r.name
            JOIN map_pin p ON p.map=m.id AND p.environment=r.environment
                AND (p.version=r.version OR EXISTS (SELECT 1 FROM map_lod_auto_selection a WHERE a.run_id=r.id))
            JOIN map_version v ON v.map=m.id AND v.version=p.version AND v.state='PUBLISHED'
                AND v.bundle_sha256=r.map_bundle_sha256
            JOIN map_lit l ON l.bundle_sha256=v.bundle_sha256 AND l.state='LIT'
                AND l.lit_sha256=r.source_bundle_sha256
                AND (l.light_format=2 OR NOT EXISTS (SELECT 1 FROM map_lod_auto_selection a WHERE a.run_id=r.id))
            WHERE r.id=? AND r.created_at<=?
              AND (r.expires_at>? OR EXISTS (SELECT 1 FROM map_lod_auto_selection a WHERE a.run_id=r.id))
              AND (NOT EXISTS (SELECT 1 FROM map_lod_auto_selection a WHERE a.run_id=r.id)
                OR (m.trust='FIRST_PARTY' AND r.environment='stage' AND r.namespace='lobby' AND r.name='mainlobby'))
              AND NOT EXISTS (SELECT 1 FROM map_lod a WHERE a.run_id=r.id)"""
                )
                .use { ps ->
                    ps.setObject(1, runId)
                    ps.setTimestamp(2, Timestamp.from(now))
                    ps.setTimestamp(3, Timestamp.from(now))
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) null
                        else
                            Selection(
                                rs.getString("budget_id"),
                                MapTrust.valueOf(rs.getString("trust")),
                                rs.getString("map_bundle_sha256"),
                                rs.getString("source_bundle_sha256"),
                                rs.getString("registry_sha256"),
                                rs.getString("generator_sha256"),
                                rs.getString("worker_image"),
                            )
                    }
                } ?: return@transaction null
        val identity =
            identity(
                selection.trust,
                selection.map,
                selection.source,
                selection.registry,
                selection.generator,
            )
        if (
            c.prepareStatement(
                    """SELECT 1 FROM map_lod WHERE identity_sha256=? AND (state IN ('READY','GENERATING')
                    OR EXISTS (SELECT 1 FROM map_lod_auto_selection a WHERE a.run_id=?))"""
                )
                .use { ps ->
                    ps.setString(1, identity)
                    ps.setObject(2, runId)
                    ps.executeQuery().use(ResultSet::next)
                }
        )
            return@transaction null
        val reserved =
            c.prepareStatement(
                    """UPDATE map_lod_budget SET jobs_reserved=jobs_reserved+1, bytes_reserved=bytes_reserved+?
            WHERE id=? AND jobs_reserved<2 AND bytes_reserved+?<=1073741824"""
                )
                .use { ps ->
                    ps.setLong(1, RESERVATION_BYTES)
                    ps.setString(2, selection.budget)
                    ps.setLong(3, RESERVATION_BYTES)
                    ps.executeUpdate() == 1
                }
        if (!reserved) return@transaction null
        val claim =
            LodClaim(
                UUID.randomUUID(),
                runId,
                selection.trust,
                selection.map,
                selection.source,
                selection.registry,
                selection.generator,
                selection.image,
                Instant.ofEpochMilli(now.toEpochMilli()),
            )
        c.prepareStatement(
                """INSERT INTO map_lod (attempt,run_id,identity_sha256,trust,map_bundle_sha256,source_bundle_sha256,
            registry_sha256,generator_sha256,worker_image,state,started_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,'GENERATING',?,?)"""
            )
            .use { ps ->
                ps.setObject(1, claim.attempt)
                ps.setObject(2, runId)
                ps.setString(3, identity)
                ps.setString(4, claim.trust.name)
                ps.setString(5, claim.mapBundleSha256)
                ps.setString(6, claim.sourceBundleSha256)
                ps.setString(7, claim.registrySha256)
                ps.setString(8, claim.generatorSha256)
                ps.setString(9, claim.workerImage)
                ps.setTimestamp(10, Timestamp.from(claim.startedAt))
                ps.setTimestamp(11, Timestamp.from(claim.startedAt))
                ps.executeUpdate()
            }
        claim
    }

    override fun progress(
        trust: MapTrust,
        map: String,
        source: String,
        registry: String,
        generator: String,
    ): LodProgress? =
        dataSource.connection.use { c ->
            c.prepareStatement(
                    """SELECT state,columns,bundle_bytes FROM map_lod
                WHERE trust=? AND map_bundle_sha256=? AND source_bundle_sha256=?
                    AND registry_sha256=? AND generator_sha256=?
                ORDER BY CASE state WHEN 'READY' THEN 0 WHEN 'GENERATING' THEN 1 ELSE 2 END, updated_at DESC
                LIMIT 1"""
                )
                .use { ps ->
                    listOf(trust.name, map, source, registry, generator).forEachIndexed {
                        index,
                        value ->
                        ps.setString(index + 1, value)
                    }
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) null
                        else {
                            val state = rs.getString("state")
                            LodProgress(
                                state,
                                if (state == "READY") rs.getInt("columns") else null,
                                if (state == "READY") rs.getLong("bundle_bytes") else null,
                            )
                        }
                    }
                }
        }

    override fun active(): LodClaim? = rows("WHERE state='GENERATING'").singleOrNull()

    override fun budget(id: String): LodReservation? =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT jobs_reserved,bytes_reserved FROM map_lod_budget WHERE id=?")
                .use { ps ->
                    ps.setString(1, id)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) LodReservation(rs.getInt(1), rs.getLong(2)) else null
                    }
                }
        }

    override fun activateIfCurrent(
        attempt: UUID,
        now: () -> Instant,
        activate: () -> Unit,
    ): Boolean = transaction { c ->
        val current = lockActive(c, attempt) ?: return@transaction false
        if (!now().isBefore(current.startedAt.plusSeconds(900))) return@transaction false
        activate()
        true
    }

    override fun failWhenStopped(attempt: UUID, reason: String, stopped: () -> Boolean): Boolean =
        transaction { c ->
            lockActive(c, attempt) ?: return@transaction false
            if (!stopped()) return@transaction false
            c.prepareStatement(
                    "UPDATE map_lod SET state='FAILED',failure=?,updated_at=now() WHERE attempt=? AND state='GENERATING'"
                )
                .use { ps ->
                    ps.setString(1, reason.take(500))
                    ps.setObject(2, attempt)
                    ps.executeUpdate() == 1
                }
        }

    override fun succeed(attempt: UUID, facts: LodFacts, now: () -> Instant): Boolean =
        transaction { c ->
            val current = lockActive(c, attempt) ?: return@transaction false
            if (!now().isBefore(current.startedAt.plusSeconds(900))) return@transaction false
            c.prepareStatement(
                    """UPDATE map_lod SET state='READY',bundle_sha256=?,bundle_bytes=?,manifest_sha256=?,manifest_bytes=?,columns=?,updated_at=now()
            WHERE attempt=? AND state='GENERATING'"""
                )
                .use { ps ->
                    ps.setString(1, facts.bundleSha256)
                    ps.setLong(2, facts.bundleBytes)
                    ps.setString(3, facts.manifestSha256)
                    ps.setLong(4, facts.manifestBytes)
                    ps.setInt(5, facts.columns)
                    ps.setObject(6, attempt)
                    ps.executeUpdate() == 1
                }
        }

    private fun lockActive(c: Connection, attempt: UUID): LodClaim? =
        c.prepareStatement(
                "SELECT * FROM map_lod WHERE attempt=? AND state='GENERATING' FOR UPDATE"
            )
            .use { ps ->
                ps.setObject(1, attempt)
                ps.executeQuery().use { rs -> if (rs.next()) readLodClaim(rs) else null }
            }

    override fun ready(): List<LodClaim> =
        rows(
            "WHERE state='READY' AND NOT published AND NOT EXISTS (SELECT 1 FROM map_lod_auto_selection a WHERE a.run_id=map_lod.run_id)"
        )

    override fun published(): List<LodClaim> = rows("WHERE state='READY' AND published")

    override fun markPublished(attempt: UUID) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                    "UPDATE map_lod SET published=true,projection_pending=true,updated_at=now() WHERE attempt=? AND state='READY'"
                )
                .use { ps ->
                    ps.setObject(1, attempt)
                    check(ps.executeUpdate() == 1) { "LOD artifact is not ready" }
                }
        }
    }

    override fun projectionPending(): List<LodClaim> =
        rows(
            "WHERE state='READY' AND published AND projection_pending AND NOT EXISTS (SELECT 1 FROM map_lod_auto_selection a WHERE a.run_id=map_lod.run_id)"
        )

    override fun projectionComplete(attempt: UUID) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                    "UPDATE map_lod SET projection_pending=false WHERE attempt=? AND published"
                )
                .use { ps ->
                    ps.setObject(1, attempt)
                    ps.executeUpdate()
                }
        }
    }

    private fun rows(where: String): List<LodClaim> =
        dataSource.connection.use { c ->
            c.createStatement().use {
                it.executeQuery("SELECT * FROM map_lod $where ORDER BY started_at").use { rs ->
                    buildList { while (rs.next()) add(readLodClaim(rs)) }
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

    private data class Selection(
        val budget: String,
        val trust: MapTrust,
        val map: String,
        val source: String,
        val registry: String,
        val generator: String,
        val image: String,
    )

    private fun identity(
        trust: MapTrust,
        map: String,
        source: String,
        registry: String,
        generator: String,
    ): String =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(
                        "$trust\n$map\n$source\n$registry\n$generator\nlss20\n32\nminecraft:overworld\n"
                            .toByteArray(Charsets.UTF_8)
                    )
            )

    companion object {
        // Scratch, permanent private and public bundle+manifest plus the marker; no refunds after
        // failure.
        const val RESERVATION_BYTES = LodGenerationBounds.RESERVATION_BYTES
    }
}

internal fun readLodClaim(rs: ResultSet) =
    LodClaim(
        rs.getObject("attempt", UUID::class.java),
        rs.getObject("run_id", UUID::class.java),
        MapTrust.valueOf(rs.getString("trust")),
        rs.getString("map_bundle_sha256"),
        rs.getString("source_bundle_sha256"),
        rs.getString("registry_sha256"),
        rs.getString("generator_sha256"),
        rs.getString("worker_image"),
        rs.getTimestamp("started_at").toInstant(),
        if (rs.getString("state") == "READY")
            LodFacts(
                rs.getString("bundle_sha256"),
                rs.getLong("bundle_bytes"),
                rs.getString("manifest_sha256"),
                rs.getLong("manifest_bytes"),
                rs.getInt("columns"),
            )
        else null,
        rs.getBoolean("published"),
    )
