package gg.grounds.persistence

import gg.grounds.domain.MapAddress
import gg.grounds.domain.MapPinRepository
import gg.grounds.domain.NotPublishedException
import gg.grounds.domain.PinRecord
import gg.grounds.domain.PinnedMap
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.UUID
import javax.sql.DataSource

@ApplicationScoped
class PostgresMapPinRepository @Inject constructor(private val dataSource: DataSource) :
    MapPinRepository {

    override fun move(environment: String, mapId: UUID, version: Int, bySub: String): PinRecord =
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                // Only a published version may be pinned. The foreign key already stops a
                // pin at a version that does not exist; this stops a pin at one whose bundle
                // has not been assembled, which the key cannot see.
                c.prepareStatement("SELECT state FROM map_version WHERE map = ? AND version = ?")
                    .use { ps ->
                        ps.setObject(1, mapId)
                        ps.setInt(2, version)
                        ps.executeQuery().use { rs ->
                            if (!rs.next()) throw NotPublishedException(version)
                            if (rs.getString(1) != "PUBLISHED") throw NotPublishedException(version)
                        }
                    }

                val previous =
                    c.prepareStatement(
                            "SELECT version FROM map_pin WHERE environment = ? AND map = ? FOR UPDATE"
                        )
                        .use { ps ->
                            ps.setString(1, environment)
                            ps.setObject(2, mapId)
                            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null }
                        }

                c.prepareStatement(
                        """
                        INSERT INTO map_pin (environment, map, version, moved_by_sub)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (environment, map)
                        DO UPDATE SET version = EXCLUDED.version,
                                      moved_by_sub = EXCLUDED.moved_by_sub,
                                      moved_at = NOW()
                        """
                    )
                    .use { ps ->
                        ps.setString(1, environment)
                        ps.setObject(2, mapId)
                        ps.setInt(3, version)
                        ps.setString(4, bySub)
                        ps.executeUpdate()
                    }

                // Append-only, in the same transaction as the move: a pin that changed
                // without a history row would be a move nobody can account for.
                c.prepareStatement(
                        """
                        INSERT INTO map_pin_history (environment, map, from_version, to_version, moved_by_sub)
                        VALUES (?, ?, ?, ?, ?)
                        """
                    )
                    .use { ps ->
                        ps.setString(1, environment)
                        ps.setObject(2, mapId)
                        ps.setObject(3, previous)
                        ps.setInt(4, version)
                        ps.setString(5, bySub)
                        ps.executeUpdate()
                    }

                c.commit()
                requireNotNull(find(environment, mapId))
            } catch (e: Exception) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = true
            }
        }

    override fun find(environment: String, mapId: UUID): PinRecord? =
        dataSource.connection.use { c ->
            c.prepareStatement(
                    """
                    SELECT environment, map, version, moved_by_sub, moved_at
                      FROM map_pin WHERE environment = ? AND map = ?
                    """
                )
                .use { ps ->
                    ps.setString(1, environment)
                    ps.setObject(2, mapId)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) return@use null
                        PinRecord(
                            environment = rs.getString("environment"),
                            mapId = rs.getObject("map", UUID::class.java),
                            version = rs.getInt("version"),
                            movedBySub = rs.getString("moved_by_sub"),
                            movedAt = rs.getTimestamp("moved_at").toInstant(),
                        )
                    }
                }
        }

    override fun pinned(environment: String): List<PinnedMap> =
        dataSource.connection.use { c ->
            c.prepareStatement(
                    """
                    SELECT m.namespace, m.name, p.version, v.bundle_sha256, v.size_bytes, v.est_loaded_mib
                      FROM map_pin p
                      JOIN map m ON m.id = p.map
                      JOIN map_version v ON v.map = p.map AND v.version = p.version
                     WHERE p.environment = ?
                       AND v.bundle_sha256 IS NOT NULL
                     ORDER BY m.namespace, m.name
                    """
                )
                .use { ps ->
                    ps.setString(1, environment)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    PinnedMap(
                                        address =
                                            MapAddress(
                                                rs.getString("namespace"),
                                                rs.getString("name"),
                                            ),
                                        version = rs.getInt("version"),
                                        bundleSha256 = rs.getString("bundle_sha256"),
                                        sizeBytes = rs.getObject("size_bytes") as Long?,
                                        estLoadedMib = rs.getObject("est_loaded_mib") as Int?,
                                    )
                                )
                            }
                        }
                    }
                }
        }
}
