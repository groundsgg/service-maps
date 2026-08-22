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
import gg.grounds.domain.VersionNotPublishableException
import gg.grounds.domain.VersionState
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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
        versions.transitionSceneProjection(
            mapId = map.id,
            version = 1,
            expectedState = VersionState.DRAFT,
            nextState = VersionState.DERIVING,
            deriveAttempt = UUID.randomUUID(),
            deriveFailureScope = DeriveFailureScope.CONTENT,
            deriveRetryable = true,
            scene =
                validScene(
                    schemaVersion = "1",
                    sha256 = "%064x".format(44),
                    assetCatalog = CatalogReference("assets-a", "2026.07"),
                    actionCatalog = CatalogReference("actions-a", "1"),
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

        val finalAttempt = UUID.randomUUID()
        val finalScene =
            validScene(
                schemaVersion = "2",
                sha256 = "%064x".format(45),
                assetCatalog = CatalogReference("assets-b", "2026.08"),
                actionCatalog = CatalogReference("actions-b", "2"),
                requiredActions = listOf("z.action", "a.action", "z.action"),
                problems = problems,
            )
        versions.transitionSceneProjection(
            mapId = map.id,
            version = 1,
            expectedState = VersionState.DERIVING,
            nextState = VersionState.PUBLISHED,
            deriveAttempt = finalAttempt,
            deriveFailureScope = null,
            deriveRetryable = false,
            scene = finalScene,
        )

        val stored = requireNotNull(versions.find(map.id, 1))
        assertEquals(SceneStatus.VALID, stored.scene.status)
        assertEquals(finalScene.schemaVersion, stored.scene.schemaVersion)
        assertEquals(finalScene.sha256, stored.scene.sha256)
        assertEquals(finalScene.assetCatalog, stored.scene.assetCatalog)
        assertEquals(finalScene.actionCatalog, stored.scene.actionCatalog)
        assertEquals(finalAttempt, stored.deriveAttempt)
        assertEquals(null, stored.deriveFailureScope)
        assertEquals(false, stored.deriveRetryable)
        assertEquals(listOf("a.action", "z.action"), stored.scene.requiredActions)
        assertEquals(problems, stored.scene.problems)
    }

    @Test
    fun `a terminal version cannot transition its scene projection`() {
        val map = createMap("terminal-projection")
        versions.commit(map.id, null, null, null, null, "builder-sub")
        versions.transitionSceneProjection(
            map.id,
            1,
            VersionState.DRAFT,
            VersionState.PUBLISHED,
            UUID.randomUUID(),
            null,
            false,
            validScene(emptyList(), emptyList()),
        )

        assertThrows<VersionNotPublishableException> {
            versions.transitionSceneProjection(
                map.id,
                1,
                VersionState.PUBLISHED,
                VersionState.PUBLISHED,
                UUID.randomUUID(),
                null,
                false,
                validScene(emptyList(), emptyList()),
            )
        }
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
                state = VersionState.PUBLISHED,
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

    private fun validScene(
        requiredActions: List<String>,
        problems: List<DeriveProblem>,
        schemaVersion: String = "1",
        sha256: String = "%064x".format(43),
        assetCatalog: CatalogReference = CatalogReference("assets", "2026.08"),
        actionCatalog: CatalogReference = CatalogReference("actions", "1"),
    ) =
        SceneProjection(
            status = SceneStatus.VALID,
            schemaVersion = schemaVersion,
            sha256 = sha256,
            assetCatalog = assetCatalog,
            actionCatalog = actionCatalog,
            requiredActions = requiredActions,
            problems = problems,
        )
}
