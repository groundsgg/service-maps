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
            assertEquals(4, upgrade.migrate().migrationsExecuted)
            assertEquals("10", upgrade.info().current().version.toString())
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
                        listOf(
                            "map_lod_budget",
                            "map_lod_run",
                            "map_lod",
                            "map_lod_serving_run",
                            "map_lod_auto_selection",
                            "map_lod_delivery_budget",
                        )) {
                        statement.executeQuery("SELECT count(*) FROM maps.$table").use { rows ->
                            assertTrue(rows.next())
                            assertEquals(0, rows.getInt(1), "migration must not admit LOD work")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `released LOD pilot survives entitled upgrade with its expiry counters and checksum`() {
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).use { db ->
            db.start()
            fun flyway(target: String? = null): Flyway {
                val config =
                    Flyway.configure()
                        .dataSource(db.jdbcUrl, db.username, db.password)
                        .schemas("maps")
                        .defaultSchema("maps")
                        .locations("classpath:db/migration")
                if (target != null) config.target(target)
                return config.load()
            }
            val released = flyway("8")
            released.migrate()
            val checksum = released.info().current().checksum
            val run = UUID.randomUUID()
            val artifact = UUID.randomUUID()
            val serving = UUID.randomUUID()
            val player = UUID.randomUUID()
            DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO maps.map_lod_budget(id,jobs_reserved,bytes_reserved) VALUES ('kept-pilot',1,1024)"
                    )
                    statement.execute(
                        """INSERT INTO maps.map_lod_run(id,budget_id,environment,namespace,name,version,map_bundle_sha256,source_bundle_sha256,registry_sha256,generator_sha256,worker_image,created_at,expires_at,created_by)
                        VALUES ('$run','kept-pilot','stage','lobby','mainlobby',13,'${"a".repeat(64)}','${"b".repeat(64)}','${"c".repeat(64)}','${"d".repeat(64)}','ghcr.io/groundsgg/service-maps@sha256:${"e".repeat(64)}','2026-11-08T00:00:00Z','2026-11-08T00:30:00Z','original-operator')"""
                    )
                    statement.execute(
                        """INSERT INTO maps.map_lod(attempt,run_id,identity_sha256,trust,map_bundle_sha256,source_bundle_sha256,registry_sha256,generator_sha256,worker_image,state,started_at,updated_at,bundle_sha256,bundle_bytes,manifest_sha256,manifest_bytes,columns)
                        VALUES ('$artifact','$run','${"f".repeat(64)}','FIRST_PARTY','${"a".repeat(64)}','${"b".repeat(64)}','${"c".repeat(64)}','${"d".repeat(64)}','ghcr.io/groundsgg/service-maps@sha256:${"e".repeat(64)}','READY',now(),now(),'${"1".repeat(64)}',4096,'${"2".repeat(64)}',256,1)"""
                    )
                    statement.execute(
                        """INSERT INTO maps.map_lod_serving_run(id,artifact_attempt,token_sha256,allowed_players,starts_at,expires_at,created_by,payload_reserved,remote_reads_reserved)
                        VALUES ('$serving','$artifact','${"3".repeat(64)}',ARRAY['$player'::uuid],'2026-11-08T00:00:00Z','2026-11-08T00:30:00Z','original-operator',262144,2)"""
                    )
                }
            }
            val upgrade = flyway()
            assertEquals(2, upgrade.migrate().migrationsExecuted)
            assertEquals("10", upgrade.info().current().version.toString())
            assertEquals(
                checksum,
                upgrade.info().applied().single { it.version?.toString() == "8" }.checksum,
            )
            assertTrue(upgrade.validateWithResult().validationSuccessful)
            DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT mode,budget_id,cardinality(allowed_players),extract(epoch FROM expires_at-starts_at),payload_reserved,remote_reads_reserved FROM maps.map_lod_serving_run WHERE id='$serving'"
                        )
                        .use { row ->
                            assertTrue(row.next())
                            assertEquals("PILOT", row.getString(1))
                            assertEquals(null, row.getString(2))
                            assertEquals(1, row.getInt(3))
                            assertEquals(1800, row.getInt(4))
                            assertEquals(262144, row.getInt(5))
                            assertEquals(2, row.getInt(6))
                        }
                    statement
                        .executeQuery(
                            "SELECT jobs_reserved,bytes_reserved FROM maps.map_lod_budget WHERE id='kept-pilot'"
                        )
                        .use { row ->
                            assertTrue(row.next())
                            assertEquals(1, row.getInt(1))
                            assertEquals(1024, row.getInt(2))
                        }
                    statement
                        .executeQuery("SELECT count(*) FROM maps.map_lod_delivery_budget")
                        .use { row ->
                            assertTrue(row.next())
                            assertEquals(0, row.getInt(1))
                        }
                }
            }
        }
    }
}
