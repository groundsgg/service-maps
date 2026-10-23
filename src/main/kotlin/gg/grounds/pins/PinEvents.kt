package gg.grounds.pins

import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.Options
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.util.Optional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Tells running servers that `pins/<env>.json` changed, on `maps.pins.<env>`.
 *
 * The message is the pin file itself, so a receiver acts on exactly what was published instead of
 * re-reading the CDN, which may serve the previous file for up to its `max-age`. Lobbies use it to
 * restart onto a new lobby map, game servers to light a new map before a round picks it.
 *
 * Plain core NATS, no stream: a server that misses one is not stuck. Lobbies read the pin at boot
 * and game servers still re-read it every few minutes, so the event only makes them faster.
 *
 * Without `NATS_URL` nothing is sent. A failed publish is logged and never fails the pin move, for
 * the same reason a failed pin file upload does not: the move is the fact, this is a projection.
 */
@ApplicationScoped
class PinEvents(@ConfigProperty(name = "grounds.maps.nats.url") natsUrl: Optional<String>) {

    private val log = Logger.getLogger(PinEvents::class.java)

    private val connection: Connection? =
        natsUrl
            .map { url ->
                // Reconnects in the background when NATS is down at boot, rather than taking the
                // registry down with it.
                Nats.connectReconnectOnConnect(
                    Options.Builder()
                        .server(url)
                        .connectionName("service-maps")
                        .connectionTimeout(Duration.ofSeconds(5))
                        .maxReconnects(-1)
                        .build()
                )
            }
            .orElse(null)
            .also { if (it == null) log.info("NATS_URL is unset; pin changes are not announced") }

    fun published(environment: String, pinFile: String) {
        val connection = connection ?: return
        try {
            connection.publish(subject(environment), pinFile.toByteArray())
        } catch (e: Exception) {
            log.warnf(
                e,
                "could not announce the pin file for %s; servers pick it up later",
                environment,
            )
        }
    }

    @PreDestroy
    fun close() {
        connection?.close()
    }

    companion object {
        fun subject(environment: String) = "maps.pins.$environment"
    }
}
