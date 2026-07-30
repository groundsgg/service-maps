package gg.grounds

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * A real Postgres per test class. The schema and its constraints are the thing under test — a
 * unique address, a fork ancestry that cannot be half-recorded — and none of that exists in a fake.
 */
class PostgresResource : QuarkusTestResourceLifecycleManager {

    private lateinit var container: PostgreSQLContainer<*>

    override fun start(): Map<String, String> {
        container = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
        container.start()
        return mapOf(
            // Flyway creates and migrates the `maps` schema; without currentSchema
            // the queries would look in `public` and find nothing.
            "quarkus.datasource.jdbc.url" to
                container.jdbcUrl +
                    (if ("?" in container.jdbcUrl) "&" else "?") +
                    "currentSchema=maps",
            "quarkus.datasource.username" to container.username,
            "quarkus.datasource.password" to container.password,
        )
    }

    override fun stop() {
        if (this::container.isInitialized) container.stop()
    }
}
