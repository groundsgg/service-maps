package gg.grounds.derive

import gg.grounds.blob.BlobMetadata
import gg.grounds.blob.BlobStore
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveIdentity
import gg.grounds.domain.DeriveProblem
import gg.grounds.domain.DeriveResultIntegrityException
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
import org.junit.jupiter.api.Assertions.assertThrows
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
                    .apply { complete(result) }
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
        val artifacts = FakeArtifacts(CanonicalJson.write(result)).apply { complete(result) }
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
        val artifacts = FakeArtifacts(CanonicalJson.write(result)).apply { complete(result) }
        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                artifacts,
            )
            .reconcile()
        assertTrue(versions.failures.isEmpty())
    }

    @Test
    fun `promotes the assigned bundle before accepting a success result`() {
        val version = record(VersionState.DERIVING)
        val result = success(version)
        val events = mutableListOf<String>()
        val artifacts =
            FakeArtifacts(CanonicalJson.write(result), events).apply { complete(result) }
        val versions = FakeVersions(listOf(version), events = events)

        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                artifacts,
            )
            .reconcile()

        assertEquals(
            listOf(
                "get:${BlobStore.deriveResultKey(version.mapId, version.version, requireNotNull(version.deriveAttempt))}:1048576",
                "head:${BlobStore.deriveBundleKey(version.mapId, version.version, requireNotNull(version.deriveAttempt))}",
                "head:${BlobStore.deriveManifestKey(version.mapId, version.version, requireNotNull(version.deriveAttempt))}",
                "get:${BlobStore.deriveManifestKey(version.mapId, version.version, requireNotNull(version.deriveAttempt))}:4194304",
                "promote:${BlobStore.deriveBundleKey(version.mapId, version.version, requireNotNull(version.deriveAttempt))}:${BlobStore.bundleKey(result.bundleSha256)}:${result.bundleSize}",
                "accept",
            ),
            events,
        )
    }

    @Test
    fun `promotion failure leaves successful result unaccepted and records retryable system failure`() {
        val version = record(VersionState.DERIVING)
        val result = success(version)
        val artifacts =
            FakeArtifacts(CanonicalJson.write(result)).apply {
                complete(result)
                promotionFailure = IllegalStateException("copy failed")
            }
        val versions = FakeVersions(listOf(version))
        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                artifacts,
            )
            .reconcile()
        assertTrue(versions.successes.isEmpty())
        assertEquals("RESULT_UNAVAILABLE", versions.failures.single().second.problems.single().code)
    }

    @Test
    fun `integrity conflicts are not swallowed as stale completions`() {
        val version = record(VersionState.DERIVING)
        val result = success(version)
        val artifacts = FakeArtifacts(CanonicalJson.write(result)).apply { complete(result) }
        val versions = FakeVersions(listOf(version), integrityFailure = true)
        assertThrows(DeriveResultIntegrityException::class.java) {
            reconciler(
                    versions,
                    FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                    artifacts,
                )
                .reconcile()
        }
    }

    @Test
    fun `failure marker with another attempt is rejected as a system result failure`() {
        val version = record(VersionState.DERIVING)
        val result =
            DeriveFailure(
                mapId = version.mapId,
                version = version.version,
                attempt = UUID.randomUUID(),
                sourceSha256 = requireNotNull(version.sourceSha256),
                scope = DeriveFailureScope.CONTENT,
                retryable = false,
                problems =
                    listOf(DeriveProblem(DeriveFailureScope.CONTENT, null, "INVALID", null, "bad")),
            )
        val versions = FakeVersions(listOf(version))

        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                FakeArtifacts(CanonicalJson.write(result)),
            )
            .reconcile()

        assertEquals("RESULT_UNAVAILABLE", versions.failures.single().second.problems.single().code)
    }

    @Test
    fun `a stale rejection while recording a malformed result is a no-op`() {
        val version = record(VersionState.DERIVING)
        val versions = FakeVersions(listOf(version), rejectFailure = true)

        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                FakeArtifacts("not-json".encodeToByteArray()),
            )
            .reconcile()

        assertTrue(versions.failures.isEmpty())
    }

    @Test
    fun `a manifest digest mismatch prevents promotion and acceptance`() {
        val version = record(VersionState.DERIVING)
        val result = success(version).copy(manifestSha256 = "d".repeat(64))
        val events = mutableListOf<String>()
        val artifacts =
            FakeArtifacts(CanonicalJson.write(result), events).apply { complete(success(version)) }
        val versions = FakeVersions(listOf(version), events = events)

        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                artifacts,
            )
            .reconcile()

        assertTrue(events.none { it.startsWith("promote:") || it == "accept" })
        assertEquals("RESULT_UNAVAILABLE", versions.failures.single().second.problems.single().code)
    }

    @Test
    fun `a wrong bundle metadata size prevents promotion and acceptance`() {
        val version = record(VersionState.DERIVING)
        val result = success(version)
        val events = mutableListOf<String>()
        val artifacts =
            FakeArtifacts(CanonicalJson.write(result), events).apply {
                complete(result)
                sizes["bundle"] = BlobMetadata(result.bundleSize + 1)
            }
        val versions = FakeVersions(listOf(version), events = events)

        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                artifacts,
            )
            .reconcile()

        assertTrue(events.none { it.startsWith("promote:") || it == "accept" })
        assertEquals("RESULT_UNAVAILABLE", versions.failures.single().second.problems.single().code)
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
            .let { result ->
                val manifest =
                    CanonicalJson.write(
                        DerivedManifest(
                            sourceSha256 = result.sourceSha256,
                            bundleDigestInputs = emptyList(),
                            scene = result.scene,
                        )
                    )
                result.copy(
                    manifestSha256 = sha256(manifest),
                    manifestSize = manifest.size.toLong(),
                )
            }

    private fun sha256(bytes: ByteArray) =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    private class FakeArtifacts(
        private val marker: ByteArray? = null,
        private val events: MutableList<String>? = null,
    ) : DeriveArtifactStore {
        val sizes = mutableMapOf<String, BlobMetadata>()
        var manifest: ByteArray? = null
        var promotionFailure: Exception? = null

        override fun getPrivate(key: String, maxBytes: Long) =
            when {
                key.endsWith("result.json") -> {
                    events?.add("get:$key:$maxBytes")
                    marker ?: error("missing result")
                }
                key.endsWith("derived-manifest.json") -> {
                    events?.add("get:$key:$maxBytes")
                    manifest ?: error("missing manifest")
                }
                else -> error("unexpected key $key")
            }

        override fun headPrivate(key: String): BlobMetadata? {
            events?.add("head:$key")
            return if (key.endsWith("bundle.tar.zst")) sizes["bundle"] else sizes["manifest"]
        }

        fun complete(result: DeriveSuccess) {
            val body =
                CanonicalJson.write(
                    DerivedManifest(
                        sourceSha256 = result.sourceSha256,
                        bundleDigestInputs = emptyList(),
                        scene = result.scene,
                    )
                )
            manifest = body
            sizes["bundle"] = BlobMetadata(result.bundleSize)
            sizes["manifest"] = BlobMetadata(body.size.toLong())
        }

        override fun promotePrivateBundle(
            sourceKey: String,
            destinationKey: String,
            expectedSizeBytes: Long,
        ) {
            promotionFailure?.let { throw it }
            events?.add("promote:$sourceKey:$destinationKey:$expectedSizeBytes")
        }
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
        private val rejectFailure: Boolean = false,
        private val integrityFailure: Boolean = false,
        private val events: MutableList<String>? = null,
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
            if (integrityFailure) throw DeriveResultIntegrityException("conflicting duplicate")
            if (rejectSuccess) throw gg.grounds.domain.DeriveResultRejectedException("stale")
            events?.add("accept")
            successes += identity to facts
            return records.first()
        }

        override fun acceptFailure(
            identity: DeriveIdentity,
            failure: DerivedFailure,
        ): MapVersionRecord {
            if (rejectFailure) throw gg.grounds.domain.DeriveResultRejectedException("stale")
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
