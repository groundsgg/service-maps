package gg.grounds.derive

import gg.grounds.domain.MapVersionRecord
import gg.grounds.domain.MapVersionRepository
import gg.grounds.domain.SceneProjection
import gg.grounds.domain.VersionState
import java.net.URI
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeriveCoordinatorTest {
    @Test
    fun `normalization stays scoped to the configured map address`() {
        val policy =
            gg.grounds.compatibility.MapRuntimeCompatibility(
                "stage",
                """[{"environment":"stage","address":"lobby/mainlobby","image":"ghcr.io/groundsgg/lobby@sha256:${"a".repeat(64)}","schemaVersions":["1"],"assetCatalog":{"id":"grounds:assets","version":"current"},"actionCatalogs":[{"id":"grounds:actions","version":"3"}],"supportedActions":[],"normalizeUploads":true}]""",
            )
        listOf("lobby/mainlobby", "arena/other").forEach { address ->
            val row = version()
            val record =
                gg.grounds.domain.MapRecord(
                    row.mapId,
                    gg.grounds.domain.MapAddress.parse(address)!!,
                    "test",
                    gg.grounds.domain.MapKind.LOBBY,
                    false,
                    gg.grounds.domain.MapTrust.FIRST_PARTY,
                    "builder",
                    null,
                    java.time.Instant.EPOCH,
                )
            val maps =
                java.lang.reflect.Proxy.newProxyInstance(
                    javaClass.classLoader,
                    arrayOf(gg.grounds.domain.MapRepository::class.java),
                ) { _, method, _ ->
                    if (method.name == "findById") record else error("unexpected repository call")
                } as gg.grounds.domain.MapRepository
            val jobs = RecordingJobs()
            DeriveCoordinator(FakeVersions(row), jobs, { emptyList() }, true, policy, maps)
                .coordinate(row.mapId, row.version)
            val actual =
                CanonicalJson.readRequest(jobs.requests.single().requestJson.toByteArray())
                    .normalizationCatalog
            assertEquals(
                if (address == "lobby/mainlobby")
                    gg.grounds.domain.CatalogReference("grounds:assets", "current")
                else null,
                actual,
            )
        }
    }

    @Test
    fun `third immutable catalog reaches the worker request without channel clipping`() {
        val version = version()
        val repository = FakeVersions(version)
        val jobs = RecordingJobs()
        val candidates =
            (1..3).map { number ->
                AssetCatalogCandidate(
                    "pinned",
                    "grounds:resourcepacks",
                    "$number.0.0",
                    "gg.grounds:resourcepacks-catalog:$number.0.0",
                    "catalog.jar",
                    java.net.URI("https://cdn.example.test/catalog.jar"),
                    "a".repeat(64),
                    1234,
                )
            }
        DeriveCoordinator(repository, jobs, candidates = { candidates }, enabled = true)
            .coordinate(version.mapId, version.version)
        val request = CanonicalJson.readRequest(jobs.requests.single().requestJson.toByteArray())
        assertEquals(candidates, request.catalogCandidates)
    }

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

    @Test
    fun restartUsesOriginalProfileAndImage() {
        val image = "old@sha256:${"a".repeat(64)}"
        val profile =
            gg.grounds.domain.DialogueProfilePin(
                "test",
                "1",
                "c".repeat(64),
                10,
                URI("https://cdn.test/profile.json"),
            )
        val candidate =
            AssetCatalogCandidate(
                "pinned",
                "assets",
                "1",
                "test:assets:1",
                "catalog.jar",
                URI("https://cdn.test/catalog.jar"),
                "d".repeat(64),
                10,
            )
        val bound =
            gg.grounds.domain.DeriveExecutionContract(
                2,
                image,
                listOf(profile),
                listOf(candidate),
                gg.grounds.domain.CatalogReference("assets", "1"),
                gg.grounds.domain.SceneShapeRequirements(setOf("guide"), emptySet()),
            )
        val record = version().copy(executionContract = bound)
        val jobs = RecordingJobs()
        DeriveCoordinator(
                FakeVersions(record),
                jobs,
                candidates = { error("moving catalog source must not be read") },
                enabled = true,
                workerImage = "new@sha256:${"b".repeat(64)}",
                requestSchemaVersion = 2,
            )
            .ensure(record, DeriveJobStatus.MISSING)
        val request = jobs.requests.single()
        assertEquals(image, request.workerImage)
        val payload = CanonicalJson.readRequest(request.requestJson.toByteArray())
        assertEquals(listOf(profile), payload.dialogueProfiles)
        assertEquals(image, payload.workerImage)
        assertEquals(listOf(candidate), payload.catalogCandidates)
        assertEquals(
            gg.grounds.domain.CatalogReference("assets", "1"),
            payload.normalizationCatalog,
        )
        assertEquals(setOf("guide"), payload.shapeRequirements!!.elements)
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

    private class FakeVersions(private var record: MapVersionRecord) :
        MapVersionRepository by unsupported() {
        var claims = 0

        override fun bindExecution(
            identity: gg.grounds.domain.DeriveIdentity,
            proposed: gg.grounds.domain.DeriveExecutionContract,
        ): gg.grounds.domain.DeriveExecutionContract {
            val existing = record.executionContract
            require(existing == null || existing == proposed)
            record = record.copy(executionContract = proposed)
            return proposed
        }

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
