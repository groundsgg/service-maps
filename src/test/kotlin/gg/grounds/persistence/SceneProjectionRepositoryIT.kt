package gg.grounds.persistence

import gg.grounds.PostgresResource
import gg.grounds.domain.DeriveProblem
import gg.grounds.domain.MapAddress
import gg.grounds.domain.MapKind
import gg.grounds.domain.MapRepository
import gg.grounds.domain.MapTrust
import gg.grounds.domain.MapVersionRepository
import gg.grounds.domain.SceneStatus
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
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
}
