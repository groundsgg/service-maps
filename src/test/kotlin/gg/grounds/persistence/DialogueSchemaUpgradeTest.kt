package gg.grounds.persistence

import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class DialogueSchemaUpgradeTest {
    @Test fun legacyPublishedRowsRemainUnknown() {
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).use { db ->
            db.start()
            fun migrations(target: String? = null): Flyway {
                val config = Flyway.configure().dataSource(db.jdbcUrl,db.username,db.password)
                    .schemas("maps").defaultSchema("maps").locations("classpath:db/migration")
                if (target != null) config.target(target)
                return config.load()
            }
            migrations("10").migrate()
            val id = UUID.randomUUID()
            DriverManager.getConnection(db.jdbcUrl,db.username,db.password).use { c ->
                c.prepareStatement("INSERT INTO maps.map(id,namespace,name,display_name,kind,owner_sub) VALUES (?,'dialogue-upgrade','kept','Existing map','LOBBY','staff')").use { p -> p.setObject(1,id); p.executeUpdate() }
                c.prepareStatement("INSERT INTO maps.map_version(map,version,state,bundle_sha256,published_by_sub,scene_present) VALUES (?,1,'PUBLISHED',?,'staff',FALSE)").use { p -> p.setObject(1,id);p.setString(2,"a".repeat(64));p.executeUpdate() }
            }
            assertEquals(1,migrations().migrate().migrationsExecuted)
            DriverManager.getConnection(db.jdbcUrl,db.username,db.password).use { c ->
                c.createStatement().use { q -> q.executeQuery("SELECT dialogue_projection, derive_execution_contract, derive_contract_legacy, bundle_sha256 FROM maps.map_version").use { r ->
                    assertTrue(r.next());assertNull(r.getString(1));assertNull(r.getString(2));assertTrue(r.getBoolean(3));assertEquals("a".repeat(64),r.getString(4))
                } }
            }
        }
    }
}
