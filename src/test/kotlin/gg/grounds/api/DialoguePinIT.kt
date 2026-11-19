package gg.grounds.api

import gg.grounds.DialogueFactsFixture as F
import gg.grounds.PostgresResource
import gg.grounds.derive.CanonicalJson
import gg.grounds.domain.*
import io.quarkus.test.common.WithTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.util.UUID
import java.util.concurrent.*
import javax.sql.DataSource
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@QuarkusTest
@WithTestResource(PostgresResource::class)
@TestSecurity(user = "operator", roles = ["grounds-staff"])
class DialoguePinIT {
    @Inject lateinit var maps: MapRepository
    @Inject lateinit var versions: MapVersionRepository
    @Inject lateinit var pins: MapPinRepository
    @Inject lateinit var dataSource: DataSource

    private fun published(story: Boolean): MapRecord {
        val map =
            maps.create(
                MapAddress("dialogue", "pin-${UUID.randomUUID()}"),
                "test",
                MapKind.ARENA,
                false,
                MapTrust.FIRST_PARTY,
                "builder",
            )
        versions.commitWithDeriveRequest(
            map.id,
            F.source,
            "tmp/source",
            true,
            null,
            null,
            "builder",
        )
        val attempt = UUID.randomUUID()
        versions.claimForDerive(map.id, 1, attempt)
        val id = DeriveIdentity(map.id, 1, attempt, F.source)
        versions.bindExecution(id, F.contract)
        versions.acceptSuccess(
            id.copy(assetCatalog = F.scene.assetCatalog),
            F.facts(story),
            "worker",
        )
        return map
    }

    @Test
    fun storyBlockedWhenSceneGuardDisabled() {
        val map = published(true)
        given()
            .contentType(ContentType.JSON)
            .body("""{"version":1}""")
            .post("/v1/maps/${map.address}/pins/stage")
            .then()
            .statusCode(409)
            .body("detail", containsString("DIALOGUE_RUNTIME_UNAVAILABLE"))
        assertNull(pins.find("stage", map.id))
    }

    @Test
    fun rejectionDoesNotChangePinOrPublishPinFile() {
        val map = published(true)
        assertThrows(RuntimeException::class.java) { pins.move("prod", map.id, 1, "operator") }
        assertTrue(pins.findAll(map.id).isEmpty())
    }

    @Test
    fun proofCheckAndPinSelectionCannotRace() {
        val map = published(false)
        val pool = Executors.newSingleThreadExecutor()
        try {
            dataSource.connection.use { c ->
                c.autoCommit = false
                c.prepareStatement("SELECT 1 FROM map_version WHERE map=? AND version=1 FOR UPDATE")
                    .use { ps ->
                        ps.setObject(1, map.id)
                        ps.executeQuery().close()
                    }
                val move = pool.submit<PinRecord> { pins.move("stage", map.id, 1, "operator") }
                assertThrows(TimeoutException::class.java) { move.get(200, TimeUnit.MILLISECONDS) }
                c.prepareStatement(
                        "UPDATE map_version SET dialogue_projection=CAST(? AS jsonb) WHERE map=? AND version=1"
                    )
                    .use { ps ->
                        ps.setString(1, CanonicalJson.write(F.story).decodeToString())
                        ps.setObject(2, map.id)
                        ps.executeUpdate()
                    }
                c.commit()
                assertThrows(ExecutionException::class.java) { move.get(5, TimeUnit.SECONDS) }
            }
            assertNull(pins.find("stage", map.id))
        } finally {
            pool.shutdownNow()
        }
    }
}
