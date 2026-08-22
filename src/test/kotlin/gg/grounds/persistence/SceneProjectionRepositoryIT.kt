package gg.grounds.persistence

import gg.grounds.PostgresResource
import gg.grounds.domain.CatalogReference
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveProblem
import gg.grounds.domain.MapAddress
import gg.grounds.domain.MapKind
import gg.grounds.domain.MapRepository
import gg.grounds.domain.MapTrust
import gg.grounds.domain.MapVersionRepository
import gg.grounds.domain.SceneProjection
import gg.grounds.domain.SceneStatus
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(PostgresResource::class)
class SceneProjectionRepositoryIT {

    @Inject lateinit var maps: MapRepository

    @Inject lateinit var versions: MapVersionRepository

    @Test
    fun `a newly committed version has a pending empty scene projection`() {
        val map =
            maps.create(
                address = MapAddress("bedwars", "scene-default"),
                displayName = "Scene default",
                kind = MapKind.ARENA,
                stateful = false,
                trust = MapTrust.FIRST_PARTY,
                ownerSub = "builder-sub",
            )

        val committed =
            versions.commit(
                mapId = map.id,
                sourceSha256 = null,
                sourceKey = null,
                parentVersion = null,
                note = null,
                bySub = "builder-sub",
            )

        assertEquals(SceneStatus.PENDING, committed.scene.status)
        assertEquals(emptyList<String>(), committed.scene.requiredActions)
        assertEquals(emptyList<DeriveProblem>(), committed.scene.problems)
    }

    @Test
    fun `replacing a projection sorts unique actions and preserves problem ordinal`() {
        val map = createMap("projection-replacement")
        versions.commit(map.id, null, null, null, null, "builder-sub")
        versions.replaceSceneProjection(
            mapId = map.id,
            version = 1,
            deriveAttempt = UUID.randomUUID(),
            deriveFailureScope = DeriveFailureScope.CONTENT,
            deriveRetryable = false,
            scene =
                validScene(
                    requiredActions = listOf("obsolete.action"),
                    problems =
                        listOf(
                            DeriveProblem(
                                DeriveFailureScope.CONTENT,
                                null,
                                "OBSOLETE",
                                null,
                                "obsolete",
                            )
                        ),
                ),
        )
        val problems =
            listOf(
                DeriveProblem(DeriveFailureScope.CONTENT, "scene.json", "BAD_SCENE", null, "bad"),
                DeriveProblem(DeriveFailureScope.SYSTEM, null, "IO", "grounds:io", "retry"),
            )

        versions.replaceSceneProjection(
            mapId = map.id,
            version = 1,
            deriveAttempt = UUID.randomUUID(),
            deriveFailureScope = null,
            deriveRetryable = false,
            scene =
                validScene(
                    requiredActions = listOf("z.action", "a.action", "z.action"),
                    problems = problems,
                ),
        )

        val stored = requireNotNull(versions.find(map.id, 1))
        assertEquals(listOf("a.action", "z.action"), stored.scene.requiredActions)
        assertEquals(problems, stored.scene.problems)
    }

    @Test
    fun `compatibility publishing records a legacy no-scene projection`() {
        val map = createMap("legacy-published")
        versions.commit(map.id, null, null, null, null, "builder-sub")

        versions.publish(
            map.id,
            1,
            gg.grounds.domain.BundleFacts("%064x".format(41), null, 41, null, null),
            "publisher-sub",
        )

        val stored = requireNotNull(versions.find(map.id, 1))
        assertEquals(SceneStatus.NONE, stored.scene.status)
        assertEquals(null, stored.scene.schemaVersion)
        assertEquals(emptyList<String>(), stored.scene.requiredActions)
        assertEquals(emptyList<DeriveProblem>(), stored.scene.problems)
    }

    @Test
    fun `a valid fork carries every projection fact`() {
        val source = createMap("valid-fork-source")
        val problems =
            listOf(DeriveProblem(DeriveFailureScope.CONTENT, null, "WARN", null, "warning"))
        val sourceVersion =
            gg.grounds.domain.MapVersionRecord(
                mapId = source.id,
                version = 1,
                state = gg.grounds.domain.VersionState.PUBLISHED,
                bundleSha256 = "%064x".format(42),
                sourceSha256 = null,
                sourceKey = null,
                manifestSha256 = null,
                parentVersion = null,
                sizeBytes = 42,
                presentChunks = 2,
                estLoadedMib = 1,
                deriveAttempt = UUID.randomUUID(),
                deriveFailureScope = null,
                deriveRetryable = false,
                scene = validScene(listOf("z.action", "a.action", "z.action"), problems),
                publishedBySub = "builder-sub",
                note = null,
                createdAt = Instant.EPOCH,
            )

        val fork =
            maps.create(
                address = MapAddress("bedwars", "valid-fork-target"),
                displayName = "Valid fork target",
                kind = MapKind.ARENA,
                stateful = false,
                trust = MapTrust.FIRST_PARTY,
                ownerSub = "builder-sub",
                forkedFrom = gg.grounds.domain.ForkOrigin(source.id, 1),
                firstVersion = sourceVersion,
            )

        val copied = requireNotNull(versions.find(fork.id, 1))
        assertEquals(SceneStatus.VALID, copied.scene.status)
        assertEquals(sourceVersion.scene.schemaVersion, copied.scene.schemaVersion)
        assertEquals(sourceVersion.scene.sha256, copied.scene.sha256)
        assertEquals(sourceVersion.scene.assetCatalog, copied.scene.assetCatalog)
        assertEquals(sourceVersion.scene.actionCatalog, copied.scene.actionCatalog)
        assertEquals(listOf("a.action", "z.action"), copied.scene.requiredActions)
        assertEquals(problems, copied.scene.problems)
    }

    private fun createMap(name: String) =
        maps.create(
            address = MapAddress("bedwars", name),
            displayName = name,
            kind = MapKind.ARENA,
            stateful = false,
            trust = MapTrust.FIRST_PARTY,
            ownerSub = "builder-sub",
        )

    private fun validScene(requiredActions: List<String>, problems: List<DeriveProblem>) =
        SceneProjection(
            status = SceneStatus.VALID,
            schemaVersion = "1",
            sha256 = "%064x".format(43),
            assetCatalog = CatalogReference("assets", "2026.08"),
            actionCatalog = CatalogReference("actions", "1"),
            requiredActions = requiredActions,
            problems = problems,
        )
}
