package gg.grounds.derive

import gg.grounds.domain.MapVersionRecord
import gg.grounds.domain.MapVersionRepository
import gg.grounds.domain.SceneProjection
import gg.grounds.domain.VersionState
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeriveCoordinatorTest {
    @Test
    fun `enabled coordinator claims once and creates a request-only job`() {
        val version = version()
        val repository = FakeVersions(version)
        val jobs = RecordingJobs()
        val coordinator =
            DeriveCoordinator(repository, jobs, candidates = { emptyList() }, enabled = true)

        coordinator.coordinate(version.mapId, version.version)

        assertEquals(1, repository.claims)
        assertEquals(1, jobs.requests.size)
        val request = jobs.requests.single()
        assertEquals(version.deriveAttempt, request.identity.attempt)
        assertEquals(version.sourceSha256, request.identity.sourceSha256)
        assertTrue(request.requestJson.contains("sourceUrl"))
    }

    @Test
    fun `disabled coordinator does not claim drafts`() {
        val version = version()
        val repository = FakeVersions(version)
        DeriveCoordinator(
                repository,
                RecordingJobs(),
                candidates = { emptyList() },
                enabled = false,
            )
            .coordinate(version.mapId, version.version)
        assertEquals(0, repository.claims)
    }

    private fun version() =
        MapVersionRecord(
            mapId = UUID.randomUUID(),
            version = 1,
            state = VersionState.DERIVING,
            bundleSha256 = null,
            sourceSha256 = "a".repeat(64),
            sourceKey = "tmp/uploads/a/source.tar.zst",
            manifestSha256 = null,
            parentVersion = null,
            sizeBytes = null,
            presentChunks = null,
            estLoadedMib = null,
            deriveAttempt = UUID.randomUUID(),
            deriveFailureScope = null,
            deriveRetryable = false,
            scene =
                SceneProjection(
                    gg.grounds.domain.SceneStatus.PENDING,
                    null,
                    null,
                    null,
                    null,
                    emptyList(),
                    emptyList(),
                ),
            publishedBySub = "u",
            note = null,
            createdAt = java.time.Instant.EPOCH,
        )

    private class RecordingJobs : DeriveJobGateway {
        val requests = mutableListOf<DeriveJobRequest>()

        override fun create(request: DeriveJobRequest) {
            requests += request
        }

        override fun find(identity: gg.grounds.domain.DeriveIdentity): DeriveJobStatus? = null
    }

    private class FakeVersions(private val record: MapVersionRecord) :
        MapVersionRepository by unsupported() {
        var claims = 0

        override fun claimForDerive(mapId: UUID, version: Int, attempt: UUID): MapVersionRecord? {
            claims++
            return record
        }
    }
}

private fun unsupported(): MapVersionRepository =
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

        override fun acceptSuccess(
            identity: gg.grounds.domain.DeriveIdentity,
            facts: gg.grounds.domain.DerivedFacts,
            bySub: String,
        ) = error("unused")

        override fun acceptFailure(
            identity: gg.grounds.domain.DeriveIdentity,
            failure: gg.grounds.domain.DerivedFailure,
        ) = error("unused")

        override fun listReconcileCandidates() = error("unused")

        override fun retrySystemFailure(mapId: UUID, version: Int) = error("unused")

        override fun find(mapId: UUID, version: Int) = error("unused")

        override fun list(mapId: UUID) = error("unused")

        override fun latestPublished(mapId: UUID) = error("unused")
    }
