package gg.grounds.persistence

import gg.grounds.domain.BlobSizeMismatchException
import gg.grounds.domain.BundleFacts
import gg.grounds.domain.CatalogReference
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveProblem
import gg.grounds.domain.MapVersionRecord
import gg.grounds.domain.MapVersionRepository
import gg.grounds.domain.SceneProjection
import gg.grounds.domain.SceneStatus
import gg.grounds.domain.VersionNotFoundException
import gg.grounds.domain.VersionNotPublishableException
import gg.grounds.domain.VersionState
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

@ApplicationScoped
class PostgresMapVersionRepository @Inject constructor(private val dataSource: DataSource) :
    MapVersionRepository {

    override fun commit(
        mapId: UUID,
        sourceSha256: String?,
        sourceKey: String?,
        parentVersion: Int?,
        note: String?,
        bySub: String,
    ): MapVersionRecord =
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val next = lockMapAndTakeNextVersion(c, mapId)
                insert(
                    c,
                    mapId = mapId,
                    version = next,
                    state = VersionState.DRAFT,
                    sourceSha256 = sourceSha256,
                    sourceKey = sourceKey,
                    parentVersion = parentVersion,
                    note = note,
                    bySub = bySub,
                )
                c.commit()
                requireNotNull(read(c, mapId, next))
            } catch (e: Exception) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = true
            }
        }

    override fun publish(
        mapId: UUID,
        version: Int,
        facts: BundleFacts,
        bySub: String,
    ): MapVersionRecord =
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val current =
                    read(c, mapId, version, forUpdate = true)
                        ?: throw VersionNotFoundException(mapId, version)
                // A published version is immutable, and a rejected or taken-down one must
                // not come back to life through this door.
                if (
                    current.state != VersionState.DRAFT &&
                        current.state != VersionState.DERIVING &&
                        current.state != VersionState.IN_REVIEW
                ) {
                    throw VersionNotPublishableException(current.state)
                }
                c.prepareStatement(
                        """
                        UPDATE map_version
                           SET state = ?, bundle_sha256 = ?, manifest_sha256 = ?,
                               size_bytes = ?, present_chunks = ?, est_loaded_mib = ?,
                               published_by_sub = ?, scene_present = FALSE,
                               scene_schema_version = NULL, scene_sha256 = NULL,
                               asset_catalog_id = NULL, asset_catalog_version = NULL,
                               action_catalog_id = NULL, action_catalog_version = NULL
                         WHERE map = ? AND version = ?
                        """
                    )
                    .use { ps ->
                        ps.setString(1, VersionState.PUBLISHED.name)
                        ps.setString(2, facts.bundleSha256)
                        ps.setString(3, facts.manifestSha256)
                        ps.setLong(4, facts.sizeBytes)
                        ps.setObject(5, facts.presentChunks)
                        ps.setObject(6, facts.estLoadedMib)
                        // Who published it, which is not who created the draft. Reporting the
                        // committer as the publisher attributes a go-live to the wrong person.
                        ps.setString(7, bySub)
                        ps.setObject(8, mapId)
                        ps.setInt(9, version)
                        ps.executeUpdate()
                    }
                // Compatibility publishing produces a successful no-scene version. A previous
                // derive attempt must not leave action requirements or diagnostics attached to it.
                clearSceneCollections(c, mapId, version)
                // Recording the blob is not bookkeeping for its own sake: orphan collection
                // needs to know a digest was seen before it can decide nothing references it.
                // A digest names exactly one byte string, so it has exactly one size. Two
                // different sizes for one digest means somebody supplied a number that is not
                // true of the object; keep the first and refuse the contradiction rather than
                // silently overwrite it.
                c.prepareStatement("SELECT size_bytes FROM map_blob WHERE sha256 = ?").use { ps ->
                    ps.setString(1, facts.bundleSha256)
                    ps.executeQuery().use { rs ->
                        if (rs.next() && rs.getLong(1) != facts.sizeBytes) {
                            throw BlobSizeMismatchException(
                                facts.bundleSha256,
                                rs.getLong(1),
                                facts.sizeBytes,
                            )
                        }
                    }
                }
                c.prepareStatement(
                        """
                        INSERT INTO map_blob (sha256, size_bytes, public)
                        VALUES (?, ?, TRUE)
                        ON CONFLICT (sha256) DO UPDATE SET public = TRUE
                        """
                    )
                    .use { ps ->
                        ps.setString(1, facts.bundleSha256)
                        ps.setLong(2, facts.sizeBytes)
                        ps.executeUpdate()
                    }
                c.commit()
                requireNotNull(read(c, mapId, version))
            } catch (e: Exception) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = true
            }
        }

    override fun find(mapId: UUID, version: Int): MapVersionRecord? =
        dataSource.connection.use { c -> read(c, mapId, version) }

    override fun list(mapId: UUID): List<MapVersionRecord> =
        dataSource.connection.use { c ->
            c.prepareStatement("$SELECT_COLUMNS WHERE map = ? ORDER BY version DESC").use { ps ->
                ps.setObject(1, mapId)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toRecord(c)) } }
            }
        }

    override fun latestPublished(mapId: UUID): MapVersionRecord? =
        dataSource.connection.use { c ->
            c.prepareStatement(
                    "$SELECT_COLUMNS WHERE map = ? AND state = 'PUBLISHED' ORDER BY version DESC LIMIT 1"
                )
                .use { ps ->
                    ps.setObject(1, mapId)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.toRecord(c) else null }
                }
        }

    /**
     * Takes a row lock on the map before reading `MAX(version)`.
     *
     * Without it, two concurrent commits both read the same maximum, both try to insert the same
     * number, and one dies on the primary key — a publish failing for no reason a builder could
     * understand. Locking the parent row serialises allocation per map and leaves every other map
     * unblocked.
     */
    private fun lockMapAndTakeNextVersion(c: Connection, mapId: UUID): Int {
        c.prepareStatement("SELECT id FROM map WHERE id = ? FOR UPDATE").use { ps ->
            ps.setObject(1, mapId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) throw IllegalArgumentException("no such map: $mapId")
            }
        }
        c.prepareStatement("SELECT COALESCE(MAX(version), 0) + 1 FROM map_version WHERE map = ?")
            .use { ps ->
                ps.setObject(1, mapId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
    }

    private fun insert(
        c: Connection,
        mapId: UUID,
        version: Int,
        state: VersionState,
        sourceSha256: String?,
        sourceKey: String?,
        parentVersion: Int?,
        note: String?,
        bySub: String,
    ) {
        c.prepareStatement(
                """
                INSERT INTO map_version (map, version, state, source_sha256, source_key,
                                         parent_version, published_by_sub, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """
            )
            .use { ps ->
                ps.setObject(1, mapId)
                ps.setInt(2, version)
                ps.setString(3, state.name)
                ps.setString(4, sourceSha256)
                ps.setString(5, sourceKey)
                ps.setObject(6, parentVersion)
                ps.setString(7, bySub)
                ps.setString(8, note)
                ps.executeUpdate()
            }
    }

    private fun read(
        c: Connection,
        mapId: UUID,
        version: Int,
        forUpdate: Boolean = false,
    ): MapVersionRecord? =
        c.prepareStatement(
                "$SELECT_COLUMNS WHERE map = ? AND version = ?" +
                    if (forUpdate) " FOR UPDATE" else ""
            )
            .use { ps ->
                ps.setObject(1, mapId)
                ps.setInt(2, version)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toRecord(c) else null }
            }

    private fun ResultSet.toRecord(c: Connection): MapVersionRecord =
        MapVersionRecord(
            mapId = getObject("map", UUID::class.java),
            version = getInt("version"),
            state = VersionState.valueOf(getString("state")),
            bundleSha256 = getString("bundle_sha256"),
            sourceSha256 = getString("source_sha256"),
            sourceKey = getString("source_key"),
            manifestSha256 = getString("manifest_sha256"),
            parentVersion = getObject("parent_version") as Int?,
            sizeBytes = getObject("size_bytes") as Long?,
            presentChunks = getObject("present_chunks") as Int?,
            estLoadedMib = getObject("est_loaded_mib") as Int?,
            deriveAttempt = getObject("derive_attempt", UUID::class.java),
            deriveFailureScope =
                getString("derive_failure_scope")?.let(DeriveFailureScope::valueOf),
            deriveRetryable = getBoolean("derive_retryable"),
            scene =
                SceneProjection(
                    status = sceneStatus(),
                    schemaVersion = getString("scene_schema_version"),
                    sha256 = getString("scene_sha256"),
                    assetCatalog = catalog("asset_catalog_id", "asset_catalog_version"),
                    actionCatalog = catalog("action_catalog_id", "action_catalog_version"),
                    requiredActions =
                        requiredActions(c, getObject("map", UUID::class.java), getInt("version")),
                    problems =
                        deriveProblems(c, getObject("map", UUID::class.java), getInt("version")),
                ),
            publishedBySub = getString("published_by_sub"),
            note = getString("note"),
            createdAt = getTimestamp("created_at").toInstant(),
        )

    private fun ResultSet.sceneStatus(): SceneStatus =
        when (VersionState.valueOf(getString("state"))) {
            VersionState.DERIVE_FAILED -> SceneStatus.INVALID
            VersionState.PUBLISHED ->
                if (getObject("scene_present") as Boolean? == true) SceneStatus.VALID
                else SceneStatus.NONE
            else -> SceneStatus.PENDING
        }

    private fun ResultSet.catalog(idColumn: String, versionColumn: String): CatalogReference? =
        getString(idColumn)?.let { CatalogReference(it, requireNotNull(getString(versionColumn))) }

    private fun requiredActions(c: Connection, mapId: UUID, version: Int): List<String> =
        c.prepareStatement(
                """
                SELECT action_id FROM map_version_required_action
                 WHERE map = ? AND version = ?
                 ORDER BY action_id
                """
            )
            .use { ps ->
                ps.setObject(1, mapId)
                ps.setInt(2, version)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }

    private fun deriveProblems(c: Connection, mapId: UUID, version: Int): List<DeriveProblem> =
        c.prepareStatement(
                """
                SELECT scope, path, code, qualified_identity, message
                  FROM map_version_derive_problem
                 WHERE map = ? AND version = ?
                 ORDER BY ordinal
                """
            )
            .use { ps ->
                ps.setObject(1, mapId)
                ps.setInt(2, version)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                DeriveProblem(
                                    scope = DeriveFailureScope.valueOf(rs.getString("scope")),
                                    path = rs.getString("path"),
                                    code = rs.getString("code"),
                                    qualifiedIdentity = rs.getString("qualified_identity"),
                                    message = rs.getString("message"),
                                )
                            )
                        }
                    }
                }
            }

    private fun clearSceneCollections(c: Connection, mapId: UUID, version: Int) {
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
    }

    private companion object {
        const val SELECT_COLUMNS =
            """
            SELECT map, version, state, bundle_sha256, source_sha256, source_key, manifest_sha256,
                   parent_version, size_bytes, present_chunks, est_loaded_mib,
                   derive_attempt, derive_failure_scope, derive_retryable,
                   scene_present, scene_schema_version, scene_sha256,
                   asset_catalog_id, asset_catalog_version, action_catalog_id, action_catalog_version,
                   published_by_sub, note, created_at
            FROM map_version
            """
    }
}
