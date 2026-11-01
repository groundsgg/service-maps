package gg.grounds

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

/** A real NATS, so the pin announcement is tested as what a server would receive. */
class NatsResource : QuarkusTestResourceLifecycleManager {

    private lateinit var container: GenericContainer<*>

    override fun start(): Map<String, String> {
        container =
            GenericContainer("nats:2.12.1-alpine")
                .withExposedPorts(4222)
                .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1))
        container.start()
        url = "nats://${container.host}:${container.getMappedPort(4222)}"
        return mapOf("grounds.maps.nats.url" to url!!)
    }

    override fun stop() {
        container.stop()
    }

    companion object {
        var url: String? = null
    }
}
