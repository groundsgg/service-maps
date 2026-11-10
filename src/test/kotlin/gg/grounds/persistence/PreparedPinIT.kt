package gg.grounds.persistence

import gg.grounds.PostgresResource
import gg.grounds.domain.*
import gg.grounds.lit.MapLightPolicy
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.util.UUID
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(value = PostgresResource::class, restrictToAnnotatedClass = true)
class PreparedPinIT {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var maps: MapRepository
    @Inject lateinit var versions: MapVersionRepository
    @Inject lateinit var lits: MapLitRepository
    @Inject lateinit var pins: MapPinRepository

    @Test
    fun `pin persistence requires accepted current-format light before changing pins or history`() {
        val map =
            maps.create(
                MapAddress("prepared", "map-${UUID.randomUUID()}"),
                "prepared",
                MapKind.ARENA,
                false,
                MapTrust.FIRST_PARTY,
                "builder",
            )
        versions.commit(map.id, null, null, null, null, "builder")
        val sha = "%064x".format(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE)
        versions.publish(map.id, 1, BundleFacts(sha, null, 100, null, null), "builder")
        val format = 1_000_000 + (Math.random() * 1_000_000).toInt()
        val guarded = PostgresMapPinRepository(dataSource, MapLightPolicy(true, true, format))
        fun refused() {
            assertThrows(NotPreparedException::class.java) {
                guarded.move("prepared", map.id, 1, "staff")
            }
            assertNull(pins.find("prepared", map.id))
            assertEquals(0, history(map.id))
        }
        refused()
        val old = UUID.randomUUID()
        lits.start(sha, format - 1, old)
        lits.succeed(sha, format - 1, old, LitFacts("b".repeat(64), 50, 1, 1, 1))
        refused()
        val current = UUID.randomUUID()
        lits.start(sha, format, current)
        refused()
        lits.succeed(sha, format, current, LitFacts("c".repeat(64), 50, 1, 1, 1))

        assertEquals(1, guarded.move("prepared", map.id, 1, "staff").version)
        assertEquals(1, history(map.id))
    }

    private fun history(map: UUID): Int =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT COUNT(*) FROM map_pin_history WHERE map = ?").use { ps ->
                ps.setObject(1, map)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
}
