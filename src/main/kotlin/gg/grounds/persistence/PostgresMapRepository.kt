package gg.grounds.persistence

import gg.grounds.domain.ForkOrigin
import gg.grounds.domain.MapAddress
import gg.grounds.domain.MapAlreadyExistsException
import gg.grounds.domain.MapKind
import gg.grounds.domain.MapRecord
import gg.grounds.domain.MapRepository
import gg.grounds.domain.MapTrust
import gg.grounds.domain.MapVersionRecord
import gg.grounds.domain.SceneProjection
import gg.grounds.domain.SceneStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

@ApplicationScoped
class PostgresMapRepository @Inject constructor(private val dataSource: DataSource) :
    MapRepository {

    override fun create(
        address: MapAddress,
        displayName: String,
        kind: MapKind,
        stateful: Boolean,
        trust: MapTrust,
        ownerSub: String,
        forkedFrom: ForkOrigin?,
        firstVersion: MapVersionRecord?,
    ): MapRecord {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                // ON CONFLICT DO NOTHING rather than a SELECT-then-INSERT: two callers
                // racing on the same address both see zero rows in the check, and only
                // the unique constraint decides. An empty result here IS the conflict.
                c.prepareStatement(
                        """
                    INSERT INTO map (id, namespace, name, display_name, kind, stateful, trust,
                                     owner_sub, forked_from_map, forked_from_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT ON CONSTRAINT map_address_unique DO NOTHING
                    """
                    )
                    .use { ps ->
                        ps.setObject(1, id)
                        ps.setString(2, address.namespace)
                        ps.setString(3, address.name)
                        ps.setString(4, displayName)
                        ps.setString(5, kind.name)
                        ps.setBoolean(6, stateful)
                        ps.setString(7, trust.name)
                        ps.setString(8, ownerSub)
                        ps.setObject(9, forkedFrom?.mapId)
                        ps.setObject(10, forkedFrom?.version)
                        if (ps.executeUpdate() == 0) throw MapAlreadyExistsException(address)
                    }

                // Same transaction as the map row, so a fork cannot half-exist.
                if (firstVersion != null) {
                    c.prepareStatement(
                            """
                        INSERT INTO map_version (
                            map, version, state, bundle_sha256, source_sha256, manifest_sha256,
                            size_bytes, present_chunks, est_loaded_mib, derive_attempt,
                            derive_failure_scope, derive_retryable, scene_present,
                            scene_schema_version, scene_sha256, asset_catalog_id,
                            asset_catalog_version, action_catalog_id, action_catalog_version,
                            published_by_sub, note
                        )
                        VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """
                        )
                        .use { ps ->
                            ps.setObject(1, id)
                            // The bytes are already assembled and already published, so the fork's
                            // first version is usable — and pinnable — the moment it exists.
                            ps.setString(2, firstVersion.state.name)
                            ps.setString(3, firstVersion.bundleSha256)
                            ps.setString(4, firstVersion.sourceSha256)
                            ps.setString(5, firstVersion.manifestSha256)
                            ps.setObject(6, firstVersion.sizeBytes)
                            ps.setObject(7, firstVersion.presentChunks)
                            ps.setObject(8, firstVersion.estLoadedMib)
                            ps.setObject(9, firstVersion.deriveAttempt)
                            ps.setString(10, firstVersion.deriveFailureScope?.name)
                            ps.setBoolean(11, firstVersion.deriveRetryable)
                            ps.setObject(
                                12,
                                when (firstVersion.scene.status) {
                                    SceneStatus.VALID -> true
                                    SceneStatus.NONE -> false
                                    else -> null
                                },
                            )
                            ps.setObject(
                                13,
                                firstVersion.scene.schemaVersion.persistedSchemaVersion(),
                            )
                            ps.setString(14, firstVersion.scene.sha256)
                            ps.setString(15, firstVersion.scene.assetCatalog?.id)
                            ps.setString(16, firstVersion.scene.assetCatalog?.version)
                            ps.setString(17, firstVersion.scene.actionCatalog?.id)
                            ps.setString(18, firstVersion.scene.actionCatalog?.version)
                            ps.setString(19, ownerSub)
                            ps.setString(
                                20,
                                "forked from ${firstVersion.mapId}@${firstVersion.version}",
                            )
                            ps.executeUpdate()
                        }
                    replaceSceneProjection(c, id, 1, firstVersion.scene)
                }
                c.commit()
                return readOn(c, address) ?: error("map $address vanished between insert and read")
            } catch (e: Exception) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = true
            }
        }
    }

    override fun find(address: MapAddress): MapRecord? =
        dataSource.connection.use { c -> readOn(c, address) }

    /**
     * Reads on a caller-supplied connection. Borrowing a second one while still holding the first
     * is how a pool deadlocks itself at small sizes, and how a post-commit read turns an applied
     * write into a 500.
     */
    private fun readOn(c: java.sql.Connection, address: MapAddress): MapRecord? =
        c.prepareStatement("$SELECT_COLUMNS WHERE namespace = ? AND name = ?").use { ps ->
            ps.setString(1, address.namespace)
            ps.setString(2, address.name)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toMapRecord() else null }
        }

    override fun list(namespace: String?): List<MapRecord> =
        dataSource.connection.use { c ->
            val sql =
                if (namespace == null) "$SELECT_COLUMNS ORDER BY namespace, name"
                else "$SELECT_COLUMNS WHERE namespace = ? ORDER BY name"
            c.prepareStatement(sql).use { ps ->
                if (namespace != null) ps.setString(1, namespace)
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.toMapRecord()) }
                }
            }
        }

    private fun ResultSet.toMapRecord(): MapRecord {
        val forkMap = getObject("forked_from_map", UUID::class.java)
        val forkVersion = getInt("forked_from_version")
        return MapRecord(
            id = getObject("id", UUID::class.java),
            address = MapAddress(getString("namespace"), getString("name")),
            displayName = getString("display_name"),
            kind = MapKind.valueOf(getString("kind")),
            stateful = getBoolean("stateful"),
            trust = MapTrust.valueOf(getString("trust")),
            ownerSub = getString("owner_sub"),
            forkedFrom = forkMap?.let { ForkOrigin(it, forkVersion) },
            createdAt = getTimestamp("created_at").toInstant(),
        )
    }

    /** Replaces the normalized projection as part of the caller's version transaction. */
    private fun replaceSceneProjection(
        c: java.sql.Connection,
        mapId: UUID,
        version: Int,
        scene: SceneProjection,
    ) {
        c.prepareStatement("DELETE FROM map_version_required_action WHERE map = ? AND version = ?")
            .use { ps ->
                ps.setObject(1, mapId)
                ps.setInt(2, version)
                ps.executeUpdate()
            }
        c.prepareStatement("DELETE FROM map_version_derive_problem WHERE map = ? AND version = ?")
            .use { ps ->
                ps.setObject(1, mapId)
                ps.setInt(2, version)
                ps.executeUpdate()
            }
        c.prepareStatement(
                """
                INSERT INTO map_version_required_action (map, version, action_id)
                VALUES (?, ?, ?)
                """
            )
            .use { ps ->
                scene.requiredActions.distinct().sorted().forEach { actionId ->
                    ps.setObject(1, mapId)
                    ps.setInt(2, version)
                    ps.setString(3, actionId)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        c.prepareStatement(
                """
                INSERT INTO map_version_derive_problem (
                    map, version, ordinal, scope, path, code, qualified_identity, message
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """
            )
            .use { ps ->
                scene.problems.forEachIndexed { ordinal, problem ->
                    ps.setObject(1, mapId)
                    ps.setInt(2, version)
                    ps.setInt(3, ordinal)
                    ps.setString(4, problem.scope.name)
                    ps.setString(5, problem.path)
                    ps.setString(6, problem.code)
                    ps.setString(7, problem.qualifiedIdentity)
                    ps.setString(8, problem.message)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
    }

    /**
     * Flyway persists the Scene v1 schema number as an integer even though the API contract is
     * text.
     */
    private fun String?.persistedSchemaVersion(): Int? =
        when (this) {
            null -> null
            else ->
                requireNotNull(toIntOrNull()) { "scene schema version must be a positive integer" }
                    .also { require(it > 0) { "scene schema version must be a positive integer" } }
        }

    private companion object {
        const val SELECT_COLUMNS =
            """
            SELECT id, namespace, name, display_name, kind, stateful, trust, owner_sub,
                   forked_from_map, forked_from_version, created_at
            FROM map
            """
    }
}
