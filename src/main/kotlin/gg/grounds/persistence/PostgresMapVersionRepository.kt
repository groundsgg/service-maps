package gg.grounds.persistence

import gg.grounds.domain.BlobSizeMismatchException
import gg.grounds.domain.BundleFacts
import gg.grounds.domain.CatalogReference
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveIdentity
import gg.grounds.domain.DeriveProblem
import gg.grounds.domain.DeriveResultIntegrityException
import gg.grounds.domain.DeriveResultRejectedException
import gg.grounds.domain.DerivedFacts
import gg.grounds.domain.DerivedFailure
import gg.grounds.domain.MapTrust
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
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Narrow transaction-boundary coordination used only by repository integration tests. Production
 * defaults are no-ops, so callers cannot observe or control a transition through this hook.
 */
internal object DeriveStateRepositoryTestHooks {
    @Volatile var beforePublicBlobWrite: () -> Unit = {}
    @Volatile var afterDeriveResultCommit: () -> Unit = {}
}

@ApplicationScoped
class PostgresMapVersionRepository
@Inject
constructor(
    private val dataSource: DataSource,
    @param:ConfigProperty(name = "grounds.maps.derive.reconcile-batch-size", defaultValue = "100")
    private val reconcileBatchSize: Int,
) : MapVersionRepository {

    init {
        require(reconcileBatchSize > 0) { "reconciliation batch size must be positive" }
    }

    override fun commit(
        mapId: UUID,
        sourceSha256: String?,
        sourceKey: String?,
        parentVersion: Int?,
        note: String?,
        bySub: String,
    ): MapVersionRecord =
        commitWithDeriveRequest(
            mapId,
            sourceSha256,
            sourceKey,
            deriveRequested = false,
            parentVersion,
            note,
            bySub,
        )

    override fun commitWithDeriveRequest(
        mapId: UUID,
        sourceSha256: String?,
        sourceKey: String?,
        deriveRequested: Boolean,
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
                    deriveRequested = deriveRequested,
                    parentVersion = parentVersion,
                    note = note,
                    bySub = bySub,
                )
                val result = requireNotNull(read(c, mapId, next))
                c.commit()
                result
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
                recordPublicBlob(c, facts.bundleSha256, facts.sizeBytes)
                val result = requireNotNull(read(c, mapId, version))
                c.commit()
                result
            } catch (e: Exception) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = true
            }
        }

    override fun claimForDerive(mapId: UUID, version: Int, attempt: UUID): MapVersionRecord? =
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val current =
                    read(c, mapId, version, forUpdate = true)
                        ?: throw VersionNotFoundException(mapId, version)
                if (
                    current.state != VersionState.DRAFT ||
                        !current.deriveRequested ||
                        current.sourceSha256 == null ||
                        current.sourceKey == null
                ) {
                    c.commit()
                    return null
                }
                writeDeriveState(
                    c,
                    mapId,
                    version,
                    VersionState.DERIVING,
                    attempt,
                    null,
                    false,
                    pendingScene(),
                )
                val result = requireNotNull(read(c, mapId, version))
                c.commit()
                result
            } catch (e: Exception) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = true
            }
        }

    override fun acceptSuccess(
        identity: DeriveIdentity,
        facts: DerivedFacts,
        bySub: String,
    ): MapVersionRecord = acceptSuccessOutcome(identity, facts, bySub).record

    override fun acceptSuccessOutcome(
        identity: DeriveIdentity,
        facts: DerivedFacts,
        bySub: String,
    ): gg.grounds.domain.DeriveAcceptance =
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val current = lockedIdentity(c, identity)
                verifyCatalog(identity, facts)
                if (current.state == VersionState.PUBLISHED) {
                    if (current.matchesSuccess(identity, facts, bySub)) {
                        c.commit()
                        return gg.grounds.domain.DeriveAcceptance(current, false)
                    }
                    throw DeriveResultIntegrityException("conflicting duplicate success result")
                }
                if (current.state != VersionState.DERIVING) {
                    throw DeriveResultRejectedException(
                        "derive result cannot finish ${current.state}"
                    )
                }
                c.prepareStatement(
                        """
                        UPDATE map_version
                           SET state = ?, bundle_sha256 = ?, manifest_sha256 = ?, size_bytes = ?,
                               present_chunks = ?, est_loaded_mib = ?, published_by_sub = ?,
                               derive_failure_scope = NULL, derive_retryable = FALSE,
                               scene_present = ?, scene_schema_version = ?, scene_sha256 = ?,
                               asset_catalog_id = ?, asset_catalog_version = ?,
                               action_catalog_id = ?, action_catalog_version = ?
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
                        ps.setString(7, bySub)
                        ps.setObject(8, facts.scene.presentValue())
                        ps.setObject(9, facts.scene.schemaVersion.persistedSchemaVersion())
                        ps.setString(10, facts.scene.sha256)
                        ps.setString(11, facts.scene.assetCatalog?.id)
                        ps.setString(12, facts.scene.assetCatalog?.version)
                        ps.setString(13, facts.scene.actionCatalog?.id)
                        ps.setString(14, facts.scene.actionCatalog?.version)
                        ps.setObject(15, identity.mapId)
                        ps.setInt(16, identity.version)
                        ps.executeUpdate()
                    }
                replaceSceneCollections(c, identity.mapId, identity.version, facts.scene)
                recordPublicBlob(c, facts.bundleSha256, facts.sizeBytes)
                val result = requireNotNull(read(c, identity.mapId, identity.version))
                c.commit()
                DeriveStateRepositoryTestHooks.afterDeriveResultCommit()
                gg.grounds.domain.DeriveAcceptance(result, true)
            } catch (e: Exception) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = true
            }
        }

    override fun acceptFailure(
        identity: DeriveIdentity,
        failure: DerivedFailure,
    ): MapVersionRecord = acceptFailureOutcome(identity, failure).record

    override fun acceptFailureOutcome(
        identity: DeriveIdentity,
        failure: DerivedFailure,
    ): gg.grounds.domain.DeriveAcceptance =
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val current = lockedIdentity(c, identity)
                if (current.state == VersionState.DERIVE_FAILED) {
                    if (current.matchesFailure(identity, failure)) {
                        c.commit()
                        return gg.grounds.domain.DeriveAcceptance(current, false)
                    }
                    throw DeriveResultIntegrityException("conflicting duplicate failure result")
                }
                if (current.state != VersionState.DERIVING) {
                    throw DeriveResultRejectedException(
                        "derive result cannot finish ${current.state}"
                    )
                }
                // A failure can happen before scene discovery. Never expose partially trusted scene
                // facts.
                writeDeriveState(
                    c,
                    identity.mapId,
                    identity.version,
                    VersionState.DERIVE_FAILED,
                    identity.attempt,
                    failure.scope,
                    failure.retryable,
                    invalidScene(failure.problems),
                )
                val result = requireNotNull(read(c, identity.mapId, identity.version))
                c.commit()
                DeriveStateRepositoryTestHooks.afterDeriveResultCommit()
                gg.grounds.domain.DeriveAcceptance(result, true)
            } catch (e: Exception) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = true
            }
        }

    override fun listReconcileCandidates(): List<MapVersionRecord> =
        dataSource.connection.use { c ->
            c.prepareStatement(
                    """
                    $SELECT_COLUMNS
                    WHERE (state = 'DRAFT' AND derive_requested = TRUE
                           AND source_sha256 IS NOT NULL AND source_key IS NOT NULL)
                       OR (state = 'DERIVING' AND derive_attempt IS NOT NULL)
                    ORDER BY created_at, map, version
                    LIMIT ?
                    """
                        .trimIndent()
                )
                .use { ps ->
                    ps.setInt(1, reconcileBatchSize)
                    ps.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.toRecord(c)) }
                    }
                }
        }

    override fun retrySystemFailure(mapId: UUID, version: Int): MapVersionRecord =
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val current =
                    read(c, mapId, version, forUpdate = true)
                        ?: throw VersionNotFoundException(mapId, version)
                if (
                    current.state != VersionState.DERIVE_FAILED ||
                        current.deriveFailureScope != DeriveFailureScope.SYSTEM ||
                        !current.deriveRetryable
                ) {
                    throw DeriveResultRejectedException(
                        "only retryable system failures can be retried"
                    )
                }
                writeDeriveState(
                    c,
                    mapId,
                    version,
                    VersionState.DERIVING,
                    UUID.randomUUID(),
                    null,
                    false,
                    pendingScene(),
                )
                val result = requireNotNull(read(c, mapId, version))
                c.commit()
                result
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
        deriveRequested: Boolean,
        parentVersion: Int?,
        note: String?,
        bySub: String,
    ) {
        c.prepareStatement(
                """
                INSERT INTO map_version (map, version, state, source_sha256, source_key,
                                         derive_requested, parent_version, published_by_sub, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
            )
            .use { ps ->
                ps.setObject(1, mapId)
                ps.setInt(2, version)
                ps.setString(3, state.name)
                ps.setString(4, sourceSha256)
                ps.setString(5, sourceKey)
                ps.setBoolean(6, deriveRequested)
                ps.setObject(7, parentVersion)
                ps.setString(8, bySub)
                ps.setString(9, note)
                ps.executeUpdate()
            }
    }

    private fun lockedIdentity(c: Connection, identity: DeriveIdentity): MapVersionRecord {
        val current =
            read(c, identity.mapId, identity.version, forUpdate = true)
                ?: throw DeriveResultRejectedException("derive result names no such version")
        if (current.deriveAttempt != identity.attempt) {
            throw DeriveResultRejectedException("derive result attempt is stale")
        }
        if (current.sourceSha256 != identity.sourceSha256) {
            throw DeriveResultRejectedException(
                "derive result source does not match the committed version"
            )
        }
        return current
    }

    private fun verifyCatalog(identity: DeriveIdentity, facts: DerivedFacts) {
        if (
            facts.scene.status == SceneStatus.VALID &&
                (identity.assetCatalog == null || facts.scene.assetCatalog != identity.assetCatalog)
        ) {
            throw DeriveResultRejectedException(
                "derive result asset catalog does not match the claimed catalog"
            )
        }
    }

    private fun writeDeriveState(
        c: Connection,
        mapId: UUID,
        version: Int,
        state: VersionState,
        attempt: UUID,
        failureScope: DeriveFailureScope?,
        retryable: Boolean,
        scene: SceneProjection,
    ) {
        c.prepareStatement(
                """
                UPDATE map_version
                   SET state = ?, derive_attempt = ?, derive_failure_scope = ?, derive_retryable = ?,
                       scene_present = ?, scene_schema_version = ?, scene_sha256 = ?,
                       asset_catalog_id = ?, asset_catalog_version = ?,
                       action_catalog_id = ?, action_catalog_version = ?
                 WHERE map = ? AND version = ?
                """
            )
            .use { ps ->
                ps.setString(1, state.name)
                ps.setObject(2, attempt)
                ps.setString(3, failureScope?.name)
                ps.setBoolean(4, retryable)
                ps.setObject(5, scene.presentValue())
                ps.setObject(6, scene.schemaVersion.persistedSchemaVersion())
                ps.setString(7, scene.sha256)
                ps.setString(8, scene.assetCatalog?.id)
                ps.setString(9, scene.assetCatalog?.version)
                ps.setString(10, scene.actionCatalog?.id)
                ps.setString(11, scene.actionCatalog?.version)
                ps.setObject(12, mapId)
                ps.setInt(13, version)
                ps.executeUpdate()
            }
        replaceSceneCollections(c, mapId, version, scene)
    }

    private fun recordPublicBlob(c: Connection, digest: String, size: Long) {
        DeriveStateRepositoryTestHooks.beforePublicBlobWrite()
        c.prepareStatement(
                """
                INSERT INTO map_blob (sha256, size_bytes, public)
                VALUES (?, ?, TRUE)
                ON CONFLICT (sha256) DO UPDATE SET public = TRUE
                  WHERE map_blob.size_bytes = EXCLUDED.size_bytes
                RETURNING size_bytes
                """
            )
            .use { ps ->
                ps.setString(1, digest)
                ps.setLong(2, size)
                ps.executeQuery().use { rs -> if (rs.next()) return }
            }
        c.prepareStatement("SELECT size_bytes FROM map_blob WHERE sha256 = ?").use { ps ->
            ps.setString(1, digest)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "conflicting blob insert did not leave a row" }
                throw BlobSizeMismatchException(digest, rs.getLong(1), size)
            }
        }
    }

    private fun pendingScene() =
        SceneProjection(SceneStatus.PENDING, null, null, null, null, emptyList(), emptyList())

    private fun invalidScene(problems: List<DeriveProblem>) =
        SceneProjection(SceneStatus.INVALID, null, null, null, null, emptyList(), problems)

    private fun MapVersionRecord.matchesSuccess(
        identity: DeriveIdentity,
        facts: DerivedFacts,
        bySub: String,
    ): Boolean =
        deriveAttempt == identity.attempt &&
            sourceSha256 == identity.sourceSha256 &&
            bundleSha256 == facts.bundleSha256 &&
            manifestSha256 == facts.manifestSha256 &&
            sizeBytes == facts.sizeBytes &&
            presentChunks == facts.presentChunks &&
            estLoadedMib == facts.estLoadedMib &&
            scene.matchesPersisted(facts.scene) &&
            publishedBySub == bySub &&
            deriveFailureScope == null &&
            !deriveRetryable

    private fun MapVersionRecord.matchesFailure(
        identity: DeriveIdentity,
        failure: DerivedFailure,
    ): Boolean =
        deriveAttempt == identity.attempt &&
            sourceSha256 == identity.sourceSha256 &&
            deriveFailureScope == failure.scope &&
            deriveRetryable == failure.retryable &&
            scene.status == SceneStatus.INVALID &&
            scene.schemaVersion == null &&
            scene.sha256 == null &&
            scene.assetCatalog == null &&
            scene.actionCatalog == null &&
            scene.requiredActions.isEmpty() &&
            scene.problems == failure.problems

    private fun SceneProjection.matchesPersisted(other: SceneProjection): Boolean =
        schemaVersion == other.schemaVersion &&
            sha256 == other.sha256 &&
            assetCatalog == other.assetCatalog &&
            actionCatalog == other.actionCatalog &&
            requiredActions == other.requiredActions.distinct().sortedWith(CODE_POINT_ORDER) &&
            problems == other.problems

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
            deriveRequested = getBoolean("derive_requested"),
            trust = MapTrust.valueOf(getString("map_trust")),
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
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                        .sortedWith(CODE_POINT_ORDER)
                }
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

    /** Replaces normalized collections inside the caller's transaction. */
    private fun replaceSceneCollections(
        c: Connection,
        mapId: UUID,
        version: Int,
        scene: SceneProjection,
    ) {
        clearSceneCollections(c, mapId, version)
        c.prepareStatement(
                """
                INSERT INTO map_version_required_action (map, version, action_id)
                VALUES (?, ?, ?)
                """
            )
            .use { ps ->
                scene.requiredActions.distinct().sortedWith(CODE_POINT_ORDER).forEach { actionId ->
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

    private fun SceneProjection.presentValue(): Boolean? =
        when (status) {
            SceneStatus.VALID -> true
            SceneStatus.NONE -> false
            SceneStatus.PENDING,
            SceneStatus.INVALID -> null
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
        val CODE_POINT_ORDER =
            Comparator<String> { left, right ->
                var leftIndex = 0
                var rightIndex = 0
                while (leftIndex < left.length && rightIndex < right.length) {
                    val leftCodePoint = left.codePointAt(leftIndex)
                    val rightCodePoint = right.codePointAt(rightIndex)
                    if (leftCodePoint != rightCodePoint)
                        return@Comparator leftCodePoint.compareTo(rightCodePoint)
                    leftIndex += Character.charCount(leftCodePoint)
                    rightIndex += Character.charCount(rightCodePoint)
                }
                (left.length - leftIndex).compareTo(right.length - rightIndex)
            }

        const val SELECT_COLUMNS =
            """
            SELECT map, version, state, bundle_sha256, source_sha256, source_key, manifest_sha256,
                   parent_version, size_bytes, present_chunks, est_loaded_mib,
                   derive_attempt, derive_failure_scope, derive_retryable, derive_requested,
                   scene_present, scene_schema_version, scene_sha256,
                   asset_catalog_id, asset_catalog_version, action_catalog_id, action_catalog_version,
                   published_by_sub, note, created_at,
                   (SELECT trust FROM map WHERE id = map_version.map) AS map_trust
            FROM map_version
            """
    }
}
