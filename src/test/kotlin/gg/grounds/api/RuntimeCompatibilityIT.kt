package gg.grounds.api

import gg.grounds.PostgresResource
import gg.grounds.domain.*
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import javax.sql.DataSource
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RuntimeContractProfile : QuarkusTestProfile {
    override fun getConfigOverrides() =
        mapOf(
            "grounds.maps.lit.enabled" to "false",
            "grounds.maps.runtime.environments" to "stage",
            "grounds.maps.runtime.targets-json" to
                """[{"environment":"stage","address":"lobby/guard-test","image":"ghcr.io/groundsgg/lobby@sha256:${"a".repeat(64)}","schemaVersions":["1"],"assetCatalog":{"id":"grounds:assets","version":"runtime"},"actionCatalogs":[{"id":"grounds:actions","version":"3"}],"supportedActions":[]},{"environment":"stage","address":"lobby/shape-test","image":"ghcr.io/groundsgg/lobby@sha256:${"a".repeat(64)}","schemaVersions":["1"],"assetCatalog":{"id":"grounds:assets","version":"runtime"},"actionCatalogs":[{"id":"grounds:actions","version":"3"}],"supportedActions":[],"requiredElements":{"demo-hands":"${"c".repeat(64)}"},"elementCounts":{"demo-":15}}]""",
        )
}

@QuarkusTest
@TestProfile(RuntimeContractProfile::class)
@QuarkusTestResource(PostgresResource::class)
@TestSecurity(user = "operator", roles = ["grounds-staff"])
class RuntimeCompatibilityIT {
    @Inject lateinit var maps: MapRepository
    @Inject lateinit var versions: MapVersionRepository
    @Inject lateinit var pins: MapPinRepository
    @Inject lateinit var dataSource: DataSource

    @Test
    fun `missing station metadata blocks matching catalog and facts survive a map fork`() {
        val map =
            maps.create(
                MapAddress("lobby", "shape-test"),
                "Shape",
                MapKind.LOBBY,
                false,
                MapTrust.FIRST_PARTY,
                "builder",
            )
        repeat(2) { i ->
            versions.commit(map.id, null, null, null, null, "builder")
            versions.publish(
                map.id,
                i + 1,
                BundleFacts((i + 3).toString().repeat(64), null, 100, null, null),
                "builder",
            )
        }
        dataSource.connection.use { c ->
            c.prepareStatement(
                    """UPDATE map_version SET scene_present=TRUE,scene_schema_version=1,scene_sha256=?,asset_catalog_id='grounds:assets',asset_catalog_version='runtime',action_catalog_id='grounds:actions',action_catalog_version='3' WHERE map=?"""
                )
                .use { ps ->
                    ps.setString(1, "d".repeat(64))
                    ps.setObject(2, map.id)
                    assertEquals(2, ps.executeUpdate())
                }
            gg.grounds.persistence.SceneRuntimeFactProjection.write(
                c,
                map.id,
                1,
                mapOf("element:demo-hands" to "c".repeat(64), "count:demo-" to "15"),
            )
        }
        pins.move("stage", map.id, 1, "operator")
        given()
            .contentType(ContentType.JSON)
            .body("""{"version":2}""")
            .post("/v1/maps/lobby/shape-test/pins/stage")
            .then()
            .statusCode(409)
            .body("detail", containsString("demo-hands"))
        assertEquals(1, pins.find("stage", map.id)!!.version)
        val source = versions.find(map.id, 1)!!
        assertEquals("15", source.scene.runtimeFacts!!["count:demo-"])
        val fork =
            maps.create(
                MapAddress("lobby", "shape-fork"),
                "Fork",
                MapKind.LOBBY,
                false,
                MapTrust.FIRST_PARTY,
                "builder",
                firstVersion = source,
            )
        assertEquals(source.scene.runtimeFacts, versions.find(fork.id, 1)!!.scene.runtimeFacts)
    }

    @Test
    fun `incompatible live activation leaves the serving pin and audited history unchanged`() {
        val map =
            maps.create(
                MapAddress("lobby", "guard-test"),
                "Guard",
                MapKind.LOBBY,
                false,
                MapTrust.FIRST_PARTY,
                "builder",
            )
        repeat(2) { i ->
            versions.commit(map.id, null, null, null, null, "builder")
            versions.publish(
                map.id,
                i + 1,
                BundleFacts((i + 1).toString().repeat(64), null, 100, null, null),
                "builder",
            )
        }
        pins.move("stage", map.id, 1, "operator")
        dataSource.connection.use { c ->
            c.prepareStatement(
                    """UPDATE map_version SET scene_present=TRUE, scene_schema_version=1, scene_sha256=?, asset_catalog_id='grounds:assets', asset_catalog_version='editor-old', action_catalog_id='grounds:actions', action_catalog_version='3' WHERE map=? AND version=2"""
                )
                .use { ps ->
                    ps.setString(1, "c".repeat(64))
                    ps.setObject(2, map.id)
                    assertEquals(1, ps.executeUpdate())
                }
        }
        given()
            .contentType(ContentType.JSON)
            .body("""{"version":2}""")
            .post("/v1/maps/lobby/guard-test/pins/stage")
            .then()
            .statusCode(409)
            .body("detail", containsString("editor-old"))
        assertEquals(1, pins.find("stage", map.id)!!.version)
        dataSource.connection.use { c ->
            c.prepareStatement(
                    "SELECT COUNT(*) FROM map_pin_history WHERE map=? AND environment='stage'"
                )
                .use { ps ->
                    ps.setObject(1, map.id)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        assertEquals(1, rs.getInt(1))
                    }
                }
        }
    }
}
