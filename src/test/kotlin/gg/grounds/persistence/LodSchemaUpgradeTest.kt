package gg.grounds.persistence

import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class LodSchemaUpgradeTest {
    @Test
    fun `current released schema upgrades without changing maps or creating LOD work`() {
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).use { db ->
            db.start()
            fun migrations(target: String? = null): Flyway {
                val config =
                    Flyway.configure()
                        .dataSource(db.jdbcUrl, db.username, db.password)
                        .schemas("maps")
                        .defaultSchema("maps")
                        .locations("classpath:db/migration")
                if (target != null) config.target(target)
                return config.load()
            }
            val released = migrations("6")
            released.migrate()
            val previousChecksum = released.info().current().checksum
            val id = UUID.randomUUID()
            DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { connection ->
                connection
                    .prepareStatement(
                        "INSERT INTO maps.map(id,namespace,name,display_name,kind,owner_sub) VALUES (?,'lod-upgrade','kept','Existing map','LOBBY','staff')"
                    )
                    .use { statement ->
                        statement.setObject(1, id)
                        statement.executeUpdate()
                    }
                connection
                    .prepareStatement(
                        "INSERT INTO maps.map_version(map,version,state,bundle_sha256,published_by_sub,scene_present,scene_runtime_facts) VALUES (?,1,'PUBLISHED',?,'staff',FALSE,'{\"sentinel\":\"preserved\"}'::jsonb)"
                    )
                    .use { statement ->
                        statement.setObject(1, id)
                        statement.setString(2, "a".repeat(64))
                        statement.executeUpdate()
                    }
            }
            val upgrade = migrations()
            assertEquals(2, upgrade.migrate().migrationsExecuted)
            assertEquals("8", upgrade.info().current().version.toString())
            assertEquals(
                previousChecksum,
                upgrade.info().applied().single { it.version?.toString() == "6" }.checksum,
            )
            assertTrue(upgrade.validateWithResult().validationSuccessful)
            DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT scene_runtime_facts->>'sentinel' FROM maps.map_version WHERE map='$id'"
                        )
                        .use { rows ->
                            assertTrue(rows.next())
                            assertEquals("preserved", rows.getString(1))
                        }
                    for (table in
                        listOf("map_lod_budget", "map_lod_run", "map_lod", "map_lod_serving_run")) {
                        statement.executeQuery("SELECT count(*) FROM maps.$table").use { rows ->
                            assertTrue(rows.next())
                            assertEquals(0, rows.getInt(1), "migration must not admit LOD work")
                        }
                    }
                }
            }
        }
    }
}
