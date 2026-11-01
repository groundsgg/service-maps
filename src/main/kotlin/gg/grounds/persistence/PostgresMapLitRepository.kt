package gg.grounds.persistence

import gg.grounds.domain.LitCandidate
import gg.grounds.domain.LitFacts
import gg.grounds.domain.LitRecord
import gg.grounds.domain.LitState
import gg.grounds.domain.MapLitRepository
import gg.grounds.domain.MapTrust
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

@ApplicationScoped
class PostgresMapLitRepository @Inject constructor(private val dataSource: DataSource) :
    MapLitRepository {

    override fun lighting(format: Int): LitRecord? =
        dataSource.connection.use { c ->
            c.prepareStatement(
                    """
                    SELECT * FROM map_lit
                     WHERE light_format = ? AND state = 'LIGHTING'
                     ORDER BY started_at
                     LIMIT 1
                    """
                )
                .use { ps ->
                    ps.setInt(1, format)
                    ps.executeQuery().use { rs -> if (rs.next()) record(rs) else null }
                }
        }

    override fun nextCandidate(format: Int, maxAttempts: Int): LitCandidate? =
        dataSource.connection.use { c ->
            c.prepareStatement(
                    """
                    SELECT v.bundle_sha256, m.trust, MIN(p.moved_at) AS first_pinned
                      FROM map_pin p
                      JOIN map m ON m.id = p.map
                      JOIN map_version v ON v.map = p.map AND v.version = p.version
                      LEFT JOIN map_lit l
                        ON l.bundle_sha256 = v.bundle_sha256 AND l.light_format = ?
                     WHERE v.bundle_sha256 IS NOT NULL
                       AND (l.bundle_sha256 IS NULL OR (l.state = 'FAILED' AND l.attempts < ?))
                     GROUP BY v.bundle_sha256, m.trust
                     ORDER BY first_pinned, v.bundle_sha256
                     LIMIT 1
                    """
                )
                .use { ps ->
                    ps.setInt(1, format)
                    ps.setInt(2, maxAttempts)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) null
                        else
                            LitCandidate(
                                bundleSha256 = rs.getString("bundle_sha256"),
                                trust = MapTrust.valueOf(rs.getString("trust")),
                            )
                    }
                }
        }

    override fun start(bundleSha256: String, format: Int, attempt: UUID) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                    """
                    INSERT INTO map_lit (bundle_sha256, light_format, state, attempt)
                    VALUES (?, ?, 'LIGHTING', ?)
                    ON CONFLICT (bundle_sha256, light_format) DO UPDATE
                       SET state = 'LIGHTING',
                           attempt = EXCLUDED.attempt,
                           attempts = map_lit.attempts + 1,
                           failure = NULL,
                           started_at = now(),
                           updated_at = now()
                     WHERE map_lit.state = 'FAILED'
                    """
                )
                .use { ps ->
                    ps.setString(1, bundleSha256)
                    ps.setInt(2, format)
                    ps.setObject(3, attempt)
                    ps.executeUpdate()
                }
        }
    }

    override fun succeed(bundleSha256: String, format: Int, attempt: UUID, facts: LitFacts) {
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val updated =
                    c.prepareStatement(
                            """
                            UPDATE map_lit
                               SET state = 'LIT', lit_sha256 = ?, lit_size = ?, kept_chunks = ?,
                                   updated_at = now()
                             WHERE bundle_sha256 = ? AND light_format = ? AND attempt = ?
                               AND state = 'LIGHTING'
                            """
                        )
                        .use { ps ->
                            ps.setString(1, facts.litSha256)
                            ps.setLong(2, facts.litSize)
                            ps.setInt(3, facts.keptChunks)
                            ps.setString(4, bundleSha256)
                            ps.setInt(5, format)
                            ps.setObject(6, attempt)
                            ps.executeUpdate()
                        }
                check(updated == 1) { "lighting $bundleSha256 is no longer attempt $attempt" }
                // Facts about the map, not the version: every version of this bundle gets them,
                // and a value someone set on purpose is kept.
                c.prepareStatement(
                        """
                        UPDATE map_version
                           SET present_chunks = COALESCE(present_chunks, ?),
                               est_loaded_mib = COALESCE(est_loaded_mib, ?)
                         WHERE bundle_sha256 = ?
                        """
                    )
                    .use { ps ->
                        ps.setInt(1, facts.presentChunks)
                        ps.setInt(2, facts.estLoadedMib)
                        ps.setString(3, bundleSha256)
                        ps.executeUpdate()
                    }
                c.commit()
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    override fun fail(bundleSha256: String, format: Int, attempt: UUID, reason: String) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                    """
                    UPDATE map_lit SET state = 'FAILED', failure = ?, updated_at = now()
                     WHERE bundle_sha256 = ? AND light_format = ? AND attempt = ?
                       AND state = 'LIGHTING'
                    """
                )
                .use { ps ->
                    ps.setString(1, reason.take(MAX_REASON))
                    ps.setString(2, bundleSha256)
                    ps.setInt(3, format)
                    ps.setObject(4, attempt)
                    ps.executeUpdate()
                }
        }
    }

    override fun environmentsPinning(bundleSha256: String): List<String> =
        dataSource.connection.use { c ->
            c.prepareStatement(
                    """
                    SELECT DISTINCT p.environment
                      FROM map_pin p
                      JOIN map_version v ON v.map = p.map AND v.version = p.version
                     WHERE v.bundle_sha256 = ?
                     ORDER BY p.environment
                    """
                )
                .use { ps ->
                    ps.setString(1, bundleSha256)
                    ps.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
        }

    override fun litCopies(format: Int): Map<String, String> =
        dataSource.connection.use { c ->
            c.prepareStatement(
                    "SELECT bundle_sha256, lit_sha256 FROM map_lit WHERE light_format = ? AND state = 'LIT'"
                )
                .use { ps ->
                    ps.setInt(1, format)
                    ps.executeQuery().use { rs ->
                        buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) }
                    }
                }
        }

    override fun trustOf(bundleSha256: String): MapTrust? =
        dataSource.connection.use { c ->
            c.prepareStatement(
                    """
                    SELECT m.trust FROM map_version v JOIN map m ON m.id = v.map
                     WHERE v.bundle_sha256 = ?
                     LIMIT 1
                    """
                )
                .use { ps ->
                    ps.setString(1, bundleSha256)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) MapTrust.valueOf(rs.getString(1)) else null
                    }
                }
        }

    private fun record(rs: ResultSet) =
        LitRecord(
            bundleSha256 = rs.getString("bundle_sha256"),
            lightFormat = rs.getInt("light_format"),
            state = LitState.valueOf(rs.getString("state")),
            attempt = rs.getObject("attempt", UUID::class.java),
            attempts = rs.getInt("attempts"),
            litSha256 = rs.getString("lit_sha256"),
            litSize = rs.getObject("lit_size") as Long?,
            startedAt = rs.getTimestamp("started_at").toInstant(),
        )

    private companion object {
        const val MAX_REASON = 2000
    }
}
