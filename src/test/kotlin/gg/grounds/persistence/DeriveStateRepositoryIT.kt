package gg.grounds.persistence

import gg.grounds.PostgresResource
import gg.grounds.domain.CatalogReference
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveIdentity
import gg.grounds.domain.DeriveProblem
import gg.grounds.domain.DeriveResultIntegrityException
import gg.grounds.domain.DeriveResultRejectedException
import gg.grounds.domain.DerivedFacts
import gg.grounds.domain.DerivedFailure
import gg.grounds.domain.MapAddress
import gg.grounds.domain.MapKind
import gg.grounds.domain.MapRepository
import gg.grounds.domain.MapTrust
import gg.grounds.domain.MapVersionRepository
import gg.grounds.domain.SceneProjection
import gg.grounds.domain.SceneStatus
import gg.grounds.domain.VersionState
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertThrows

@QuarkusTest
@QuarkusTestResource(value = PostgresResource::class, restrictToAnnotatedClass = true)
@TestMethodOrder(OrderAnnotation::class)
class DeriveStateRepositoryIT {

    @Inject lateinit var maps: MapRepository
    @Inject lateinit var versions: MapVersionRepository

    @Inject lateinit var dataSource: javax.sql.DataSource

    @Test
    @Order(2)
    fun `concurrent claims assign exactly one attempt to a draft`() {
        val map = committed("concurrent-claim")
        val attempts = listOf(UUID.randomUUID(), UUID.randomUUID())
        val ready = CountDownLatch(attempts.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(attempts.size)
        try {
            val claimed: List<Future<gg.grounds.domain.MapVersionRecord?>> =
                attempts.map { attempt ->
                    executor.submit<gg.grounds.domain.MapVersionRecord?> {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        versions.claimForDerive(map.id, 1, attempt)
                    }
                }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            val winners = claimed.map { it.get(10, TimeUnit.SECONDS) }.filterNotNull()
            assertEquals(1, winners.size)
            assertEquals(VersionState.DERIVING, winners.single().state)
            assertEquals(winners.single().deriveAttempt, versions.find(map.id, 1)?.deriveAttempt)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    @Order(3)
    fun `a stale attempt cannot finish after a system retry`() {
        val map = committed("stale-attempt")
        val first = UUID.randomUUID()
        assertNotNull(versions.claimForDerive(map.id, 1, first))
        val identity = identity(map.id, first)
        versions.acceptFailure(identity, systemFailure("FIRST"))
        val retried = versions.retrySystemFailure(map.id, 1)

        assertEquals(VersionState.DERIVING, retried.state)
        assertTrue(retried.deriveAttempt != first)
        assertThrows<DeriveResultRejectedException> {
            versions.acceptSuccess(identity, facts(), "derive-worker")
        }
        assertEquals(VersionState.DERIVING, versions.find(map.id, 1)?.state)
    }

    @Test
    fun `matching duplicate success preserves every code-point-ordered action`() {
        val map = committed("idempotent-success")
        val attempt = UUID.randomUUID()
        assertNotNull(versions.claimForDerive(map.id, 1, attempt))
        val identity = identity(map.id, attempt)
        val accepted = versions.acceptSuccess(identity, facts(), "derive-worker")
        val repeated = versions.acceptSuccess(identity, facts(), "derive-worker")

        assertEquals(accepted, repeated)
        assertEquals(VersionState.PUBLISHED, repeated.state)
        assertEquals(
            listOf("a.action", "z.action", "\uE000.action", "\uD800\uDC00.action"),
            accepted.scene.requiredActions,
        )
        assertEquals(accepted.scene.requiredActions, repeated.scene.requiredActions)
        assertEquals(emptyList<DeriveProblem>(), repeated.scene.problems)
        assertThrows<DeriveResultIntegrityException> {
            versions.acceptSuccess(identity, facts(sizeBytes = 124), "derive-worker")
        }
    }

    @Test
    @Order(1)
    fun `reconciliation returns ordered claimable and retryable candidates within its configured batch`() {
        val (lowerMap, contentFailure, nonRetryableSystem, retryableMap, beyondLimitMap) =
            List(5) { committed("reconcile-tie-$it") }.sortedBy { it.id.toString() }
        val lowerDraft = requireNotNull(versions.find(lowerMap.id, 1))
        val lowerDeriving =
            versions.commit(
                lowerMap.id,
                digest(1),
                "tmp/uploads/lower-v2.tar.zst",
                null,
                null,
                "builder",
            )
        val lowerAttempt = UUID.randomUUID()
        versions.claimForDerive(lowerMap.id, lowerDeriving.version, lowerAttempt)
        val higherRetryableAttempt = UUID.randomUUID()
        versions.claimForDerive(retryableMap.id, 1, higherRetryableAttempt)
        versions.acceptFailure(
            DeriveIdentity(
                retryableMap.id,
                1,
                higherRetryableAttempt,
                digest(1),
                CatalogReference("assets", "2026.08"),
            ),
            systemFailure("TEMPORARY"),
        )
        val beyondLimit =
            versions.commit(
                beyondLimitMap.id,
                digest(1),
                "tmp/uploads/higher-v2.tar.zst",
                null,
                null,
                "builder",
            )
        val missingSourceSha =
            maps.create(
                MapAddress("derive", "reconcile-no-source-sha-${UUID.randomUUID()}"),
                "reconcile-no-source-sha",
                MapKind.ARENA,
                false,
                MapTrust.FIRST_PARTY,
                "builder",
            )
        versions.commit(
            missingSourceSha.id,
            null,
            "tmp/uploads/source.tar.zst",
            null,
            null,
            "builder",
        )
        val missingSourceKey =
            maps.create(
                MapAddress("derive", "reconcile-no-source-key-${UUID.randomUUID()}"),
                "reconcile-no-source-key",
                MapKind.ARENA,
                false,
                MapTrust.FIRST_PARTY,
                "builder",
            )
        versions.commit(missingSourceKey.id, digest(1), null, null, null, "builder")
        val published = committed("reconcile-published")
        val missingAttempt = committed("reconcile-missing-attempt")
        val publishedAttempt = UUID.randomUUID()
        versions.claimForDerive(published.id, 1, publishedAttempt)
        versions.acceptSuccess(identity(published.id, publishedAttempt), facts(), "derive")
        val contentAttempt = UUID.randomUUID()
        versions.claimForDerive(contentFailure.id, 1, contentAttempt)
        versions.acceptFailure(
            identity(contentFailure.id, contentAttempt),
            DerivedFailure(
                DeriveFailureScope.CONTENT,
                false,
                listOf(problem(DeriveFailureScope.CONTENT, "BAD_ARCHIVE")),
            ),
        )
        val missingAttemptId = UUID.randomUUID()
        versions.claimForDerive(missingAttempt.id, 1, missingAttemptId)
        clearDeriveAttempt(missingAttempt.id, 1)
        val nonRetryableAttempt = UUID.randomUUID()
        versions.claimForDerive(nonRetryableSystem.id, 1, nonRetryableAttempt)
        versions.acceptFailure(
            identity(nonRetryableSystem.id, nonRetryableAttempt),
            systemFailure("TEMPORARY"),
        )
        setRetryable(nonRetryableSystem.id, 1, false)

        setCreatedAt(missingSourceSha.id, 1, 0)
        setCreatedAt(missingSourceKey.id, 1, 0)
        setCreatedAt(missingAttempt.id, 1, 1)
        setCreatedAt(lowerMap.id, lowerDraft.version, 10)
        setCreatedAt(lowerMap.id, lowerDeriving.version, 10)
        setCreatedAt(contentFailure.id, 1, 10)
        setCreatedAt(nonRetryableSystem.id, 1, 10)
        setCreatedAt(retryableMap.id, 1, 10)
        setCreatedAt(beyondLimitMap.id, beyondLimit.version, 10)
        setCreatedAt(published.id, 1, 0)

        val candidates = versions.listReconcileCandidates()
        assertEquals(
            listOf(
                lowerMap.id to lowerDraft.version,
                lowerMap.id to lowerDeriving.version,
                retryableMap.id to 1,
            ),
            candidates.map { it.mapId to it.version },
        )
        assertEquals(
            listOf(VersionState.DRAFT, VersionState.DERIVING, VersionState.DERIVE_FAILED),
            candidates.map { it.state },
        )
        assertFalse(
            candidates.any {
                it.mapId in
                    setOf(
                        missingSourceSha.id,
                        missingSourceKey.id,
                        missingAttempt.id,
                        contentFailure.id,
                        nonRetryableSystem.id,
                        published.id,
                    ) || (it.mapId == beyondLimitMap.id && it.version == beyondLimit.version)
            }
        )
        assertNull(versions.claimForDerive(missingSourceSha.id, 1, UUID.randomUUID()))
        assertNull(versions.claimForDerive(missingSourceKey.id, 1, UUID.randomUUID()))
    }

    @Test
    fun `acceptance rejects wrong source catalog map or version`() {
        val map = committed("identity-rejection")
        val attempt = UUID.randomUUID()
        versions.claimForDerive(map.id, 1, attempt)
        val expected = identity(map.id, attempt)
        val cases =
            listOf(
                expected.copy(sourceSha256 = digest(2)),
                expected.copy(assetCatalog = CatalogReference("assets", "unexpected")),
                expected.copy(mapId = UUID.randomUUID()),
                expected.copy(version = 2),
            )

        cases.forEach { wrong ->
            assertThrows<DeriveResultRejectedException> {
                versions.acceptSuccess(wrong, facts(), "derive")
            }
        }
        assertEquals(VersionState.DERIVING, versions.find(map.id, 1)?.state)
    }

    @Test
    fun `content and system failures preserve retry policy and invalidate every scene`() {
        val contentMap = committed("content-failure")
        val contentAttempt = UUID.randomUUID()
        versions.claimForDerive(contentMap.id, 1, contentAttempt)
        val content =
            versions.acceptFailure(
                identity(contentMap.id, contentAttempt),
                DerivedFailure(
                    DeriveFailureScope.CONTENT,
                    false,
                    listOf(problem(DeriveFailureScope.CONTENT, "BAD_ARCHIVE")),
                ),
            )
        assertEquals(VersionState.DERIVE_FAILED, content.state)
        assertFalse(content.deriveRetryable)
        assertEquals(SceneStatus.INVALID, content.scene.status)
        assertNull(content.scene.schemaVersion)
        assertEquals(
            content,
            versions.acceptFailure(
                identity(contentMap.id, contentAttempt),
                DerivedFailure(
                    DeriveFailureScope.CONTENT,
                    false,
                    listOf(problem(DeriveFailureScope.CONTENT, "BAD_ARCHIVE")),
                ),
            ),
        )
        assertThrows<DeriveResultIntegrityException> {
            versions.acceptFailure(
                identity(contentMap.id, contentAttempt),
                DerivedFailure(
                    DeriveFailureScope.CONTENT,
                    false,
                    listOf(problem(DeriveFailureScope.CONTENT, "OTHER")),
                ),
            )
        }
        assertThrows<DeriveResultRejectedException> {
            versions.retrySystemFailure(contentMap.id, 1)
        }

        val systemMap = committed("system-failure")
        val systemAttempt = UUID.randomUUID()
        versions.claimForDerive(systemMap.id, 1, systemAttempt)
        val system =
            versions.acceptFailure(
                identity(systemMap.id, systemAttempt),
                systemFailure("TEMPORARY"),
            )
        assertEquals(VersionState.DERIVE_FAILED, system.state)
        assertTrue(system.deriveRetryable)
        assertEquals(SceneStatus.INVALID, system.scene.status)
        assertEquals(listOf("TEMPORARY"), system.scene.problems.map { it.code })
    }

    @Test
    fun `retry replaces old system problems with the next terminal result`() {
        val map = committed("replacement")
        val first = UUID.randomUUID()
        versions.claimForDerive(map.id, 1, first)
        versions.acceptFailure(identity(map.id, first), systemFailure("OLD"))

        val retried = versions.retrySystemFailure(map.id, 1)
        assertEquals(emptyList<DeriveProblem>(), retried.scene.problems)
        val failed =
            versions.acceptFailure(
                identity(map.id, requireNotNull(retried.deriveAttempt)),
                systemFailure("NEW"),
            )
        assertEquals(listOf("NEW"), failed.scene.problems.map { it.code })
    }

    @Test
    fun `valid success requires the claimed non-null asset catalog`() {
        val map = committed("catalog-identity")
        val attempt = UUID.randomUUID()
        versions.claimForDerive(map.id, 1, attempt)

        assertThrows<DeriveResultRejectedException> {
            versions.acceptSuccess(
                identity(map.id, attempt).copy(assetCatalog = null),
                facts(),
                "derive",
            )
        }
    }

    private fun committed(name: String) =
        maps
            .create(
                MapAddress("derive", "$name-${UUID.randomUUID()}"),
                name,
                MapKind.ARENA,
                false,
                MapTrust.FIRST_PARTY,
                "builder",
            )
            .also { map ->
                versions.commit(
                    map.id,
                    digest(1),
                    "tmp/uploads/source.tar.zst",
                    null,
                    null,
                    "builder",
                )
            }

    private fun setCreatedAt(mapId: UUID, version: Int, offsetSeconds: Long) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "UPDATE map_version SET created_at = ? WHERE map = ? AND version = ?"
                )
                .use { statement ->
                    statement.setTimestamp(
                        1,
                        Timestamp.from(
                            Instant.parse("2020-01-01T00:00:00Z").plusSeconds(offsetSeconds)
                        ),
                    )
                    statement.setObject(2, mapId)
                    statement.setInt(3, version)
                    statement.executeUpdate()
                }
        }
    }

    private fun clearDeriveAttempt(mapId: UUID, version: Int) =
        updateVersion(mapId, version, "derive_attempt = NULL")

    private fun setRetryable(mapId: UUID, version: Int, retryable: Boolean) =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "UPDATE map_version SET derive_retryable = ? WHERE map = ? AND version = ?"
                )
                .use { statement ->
                    statement.setBoolean(1, retryable)
                    statement.setObject(2, mapId)
                    statement.setInt(3, version)
                    statement.executeUpdate()
                }
        }

    private fun updateVersion(mapId: UUID, version: Int, assignment: String) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "UPDATE map_version SET $assignment WHERE map = ? AND version = ?"
                )
                .use { statement ->
                    statement.setObject(1, mapId)
                    statement.setInt(2, version)
                    statement.executeUpdate()
                }
        }
    }

    private fun identity(mapId: UUID, attempt: UUID) =
        DeriveIdentity(mapId, 1, attempt, digest(1), CatalogReference("assets", "2026.08"))

    private fun facts(sizeBytes: Long = 123) =
        DerivedFacts(
            bundleSha256 = digest(3),
            manifestSha256 = digest(4),
            sizeBytes = sizeBytes,
            presentChunks = 2,
            estLoadedMib = 1,
            scene =
                SceneProjection(
                    SceneStatus.VALID,
                    "1",
                    digest(5),
                    CatalogReference("assets", "2026.08"),
                    CatalogReference("actions", "1"),
                    listOf(
                        "z.action",
                        "a.action",
                        "z.action",
                        "\uE000.action",
                        "\uD800\uDC00.action",
                    ),
                    emptyList(),
                ),
        )

    private fun systemFailure(code: String) =
        DerivedFailure(
            DeriveFailureScope.SYSTEM,
            true,
            listOf(problem(DeriveFailureScope.SYSTEM, code)),
        )

    private fun problem(scope: DeriveFailureScope, code: String) =
        DeriveProblem(scope, null, code, null, code.lowercase())

    private fun digest(value: Int) = "%064x".format(value)
}
