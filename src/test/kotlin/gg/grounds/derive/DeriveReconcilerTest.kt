package gg.grounds.derive

import gg.grounds.blob.BlobMetadata
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveIdentity
import gg.grounds.domain.DerivedFacts
import gg.grounds.domain.DerivedFailure
import gg.grounds.domain.MapVersionRecord
import gg.grounds.domain.MapVersionRepository
import gg.grounds.domain.SceneProjection
import gg.grounds.domain.SceneStatus
import gg.grounds.domain.VersionState
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeriveReconcilerTest {
    @Test
    fun `missing Job is recreated while a running Job is left alone`() {
        val missing = record(VersionState.DERIVING)
        val running = record(VersionState.DERIVING)
        val versions = FakeVersions(listOf(missing, running))
        val jobs =
            FakeJobs(
                mapOf(
                    missing.identity() to DeriveJobStatus.MISSING,
                    running.identity() to DeriveJobStatus.RUNNING,
                )
            )
        reconciler(versions, jobs, FakeArtifacts()).reconcile()
        assertEquals(listOf(missing.identity()), jobs.created.map { it.identity })
        assertEquals(2, jobs.findCalls)
        assertTrue(versions.failures.isEmpty())
    }

    @Test
    fun `failed Job is SYSTEM and only retryable SYSTEM failures retry`() {
        val deriving = record(VersionState.DERIVING)
        val system =
            record(VersionState.DERIVE_FAILED)
                .copy(deriveFailureScope = DeriveFailureScope.SYSTEM, deriveRetryable = true)
        val content =
            record(VersionState.DERIVE_FAILED).copy(deriveFailureScope = DeriveFailureScope.CONTENT)
        val versions = FakeVersions(listOf(deriving, system, content))
        reconciler(
                versions,
                FakeJobs(mapOf(deriving.identity() to DeriveJobStatus.FAILED)),
                FakeArtifacts(),
            )
            .reconcile()
        assertEquals("JOB_FAILED", versions.failures.single().second.problems.single().code)
        assertEquals(listOf(system.mapId to system.version), versions.retried)
    }

    @Test
    fun `succeeded Job accepts only complete identity-matching result markers`() {
        val cases = listOf<ByteArray?>(null, "bad json".encodeToByteArray(), null)
        cases.forEachIndexed { index, marker ->
            val version = record(VersionState.DERIVING)
            val result =
                success(version).let { if (index == 2) it.copy(mapId = UUID.randomUUID()) else it }
            val artifacts =
                FakeArtifacts(marker ?: if (index == 2) CanonicalJson.write(result) else null)
                    .apply {
                        sizes["bundle"] = BlobMetadata(42)
                        sizes["manifest"] = BlobMetadata(9)
                    }
            val versions = FakeVersions(listOf(version))
            reconciler(
                    versions,
                    FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                    artifacts,
                )
                .reconcile()
            assertEquals(
                "RESULT_UNAVAILABLE",
                versions.failures.single().second.problems.single().code,
            )
        }
        val version = record(VersionState.DERIVING)
        val result = success(version)
        val artifacts =
            FakeArtifacts(CanonicalJson.write(result)).apply {
                sizes["bundle"] = BlobMetadata(42)
                sizes["manifest"] = BlobMetadata(9)
            }
        val versions = FakeVersions(listOf(version))
        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                artifacts,
            )
            .reconcile()
        assertEquals(1, versions.successes.size)
    }

    @Test
    fun `stale duplicate acceptance is harmless`() {
        val version = record(VersionState.DERIVING)
        val result = success(version)
        val versions = FakeVersions(listOf(version), rejectSuccess = true)
        val artifacts =
            FakeArtifacts(CanonicalJson.write(result)).apply {
                sizes["bundle"] = BlobMetadata(42)
                sizes["manifest"] = BlobMetadata(9)
            }
        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                artifacts,
            )
            .reconcile()
        assertTrue(versions.failures.isEmpty())
    }

    @Test
    fun `concurrent ticks cannot duplicate a draft claim or Job`() {
        val versions = FakeVersions(listOf(record(VersionState.DRAFT).copy(deriveAttempt = null)))
        val jobs = FakeJobs()
        val reconciler = reconciler(versions, jobs, FakeArtifacts())
        Executors.newFixedThreadPool(4).use { pool ->
            pool.invokeAll(List(12) { Callable { reconciler.reconcile() } }).forEach {
                it.get(2, TimeUnit.SECONDS)
            }
        }
        assertEquals(1, versions.claims)
        assertEquals(1, jobs.created.size)
    }

    @Test
    fun `retry delay is bounded exponential`() {
        assertEquals(java.time.Duration.ofSeconds(5), DeriveReconciler.retryDelay(0))
        assertEquals(java.time.Duration.ofSeconds(30), DeriveReconciler.retryDelay(1))
        assertEquals(java.time.Duration.ofMinutes(2), DeriveReconciler.retryDelay(99))
    }

    private fun reconciler(v: FakeVersions, j: FakeJobs, a: FakeArtifacts) =
        DeriveReconciler(v, DeriveCoordinator(v, j, { emptyList() }, true), j, a)

    private fun record(state: VersionState) =
        MapVersionRecord(
            UUID.randomUUID(),
            1,
            state,
            null,
            "a".repeat(64),
            "tmp/uploads/s.tar.zst",
            null,
            null,
            null,
            null,
            null,
            UUID.randomUUID(),
            null,
            false,
            SceneProjection(SceneStatus.PENDING, null, null, null, null, emptyList(), emptyList()),
            "test",
            null,
            Instant.EPOCH,
        )

    private fun MapVersionRecord.identity() =
        DeriveIdentity(mapId, version, requireNotNull(deriveAttempt), requireNotNull(sourceSha256))

    private fun success(v: MapVersionRecord) =
        DeriveSuccess(
            mapId = v.mapId,
            version = v.version,
            attempt = requireNotNull(v.deriveAttempt),
            sourceSha256 = requireNotNull(v.sourceSha256),
            bundleSha256 = "b".repeat(64),
            bundleSize = 42,
            manifestSha256 = "c".repeat(64),
            manifestSize = 9,
            scene = DerivedScene(false, null, null, null, null, emptyList()),
        )

    private class FakeArtifacts(private val marker: ByteArray? = null) : DeriveArtifactStore {
        val sizes = mutableMapOf<String, BlobMetadata>()

        override fun getPrivate(key: String) = marker ?: error("missing result")

        override fun headPrivate(key: String) =
            if (key.endsWith("bundle.tar.zst")) sizes["bundle"] else sizes["manifest"]
    }

    private class FakeJobs(private val states: Map<DeriveIdentity, DeriveJobStatus> = emptyMap()) :
        DeriveJobGateway {
        val created = java.util.concurrent.CopyOnWriteArrayList<DeriveJobRequest>()
        var findCalls = 0

        override fun create(request: DeriveJobRequest) {
            if (created.none { it.identity == request.identity }) created += request
        }

        override fun find(identity: DeriveIdentity): DeriveJobStatus? {
            findCalls++
            return states[identity]
        }
    }

    private class FakeVersions(
        records: List<MapVersionRecord>,
        private val rejectSuccess: Boolean = false,
    ) : MapVersionRepository by unused() {
        private val records = records.toMutableList()
        var claims = 0
        val failures = mutableListOf<Pair<DeriveIdentity, DerivedFailure>>()
        val successes = mutableListOf<Pair<DeriveIdentity, DerivedFacts>>()
        val retried = mutableListOf<Pair<UUID, Int>>()

        override fun listReconcileCandidates() = synchronized(records) { records.toList() }

        override fun claimForDerive(mapId: UUID, version: Int, attempt: UUID) =
            synchronized(records) {
                records
                    .indexOfFirst {
                        it.mapId == mapId && it.version == version && it.state == VersionState.DRAFT
                    }
                    .takeIf { it >= 0 }
                    ?.let { i ->
                        records[i]
                            .copy(state = VersionState.DERIVING, deriveAttempt = attempt)
                            .also {
                                records[i] = it
                                claims++
                            }
                    }
            }

        override fun acceptSuccess(
            identity: DeriveIdentity,
            facts: DerivedFacts,
            bySub: String,
        ): MapVersionRecord {
            if (rejectSuccess) throw gg.grounds.domain.DeriveResultRejectedException("stale")
            successes += identity to facts
            return records.first()
        }

        override fun acceptFailure(
            identity: DeriveIdentity,
            failure: DerivedFailure,
        ): MapVersionRecord {
            failures += identity to failure
            return records.first()
        }

        override fun retrySystemFailure(mapId: UUID, version: Int): MapVersionRecord {
            retried += mapId to version
            return records.first()
        }
    }
}

private fun unused(): MapVersionRepository =
    object : MapVersionRepository {
        override fun commit(
            mapId: UUID,
            sourceSha256: String?,
            sourceKey: String?,
            parentVersion: Int?,
            note: String?,
            bySub: String,
        ) = error("unused")

        override fun publish(
            mapId: UUID,
            version: Int,
            facts: gg.grounds.domain.BundleFacts,
            bySub: String,
        ) = error("unused")

        override fun claimForDerive(mapId: UUID, version: Int, attempt: UUID) = error("unused")

        override fun acceptSuccess(identity: DeriveIdentity, facts: DerivedFacts, bySub: String) =
            error("unused")

        override fun acceptFailure(identity: DeriveIdentity, failure: DerivedFailure) =
            error("unused")

        override fun listReconcileCandidates() = error("unused")

        override fun retrySystemFailure(mapId: UUID, version: Int) = error("unused")

        override fun find(mapId: UUID, version: Int) = error("unused")

        override fun list(mapId: UUID) = error("unused")

        override fun latestPublished(mapId: UUID) = error("unused")
    }
