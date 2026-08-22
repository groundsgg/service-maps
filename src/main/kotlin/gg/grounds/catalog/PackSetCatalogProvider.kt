package gg.grounds.catalog

import gg.grounds.derive.AssetCatalogCandidate
import gg.grounds.resourcepacks.client.PackSetClient
import gg.grounds.resourcepacks.client.PackSetClientConfig
import gg.grounds.resourcepacks.client.PackSetClientState
import gg.grounds.resourcepacks.client.PackSetSnapshot
import gg.grounds.resourcepacks.client.PackSetSource
import gg.grounds.resourcepacks.contract.PackSetChannel
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import java.net.URI
import java.nio.file.Path
import org.eclipse.microprofile.config.inject.ConfigProperty

/** The only source of runtime catalogs: validated PackSet snapshots for Stable then Edge. */
@ApplicationScoped
class PackSetCatalogProvider
@Inject
constructor(
    @ConfigProperty(name = "grounds.maps.catalogs.base-uri") private val baseUri: String,
    @ConfigProperty(name = "grounds.maps.catalogs.pack-set") private val packSet: String,
    @ConfigProperty(name = "grounds.maps.catalogs.cache-root") private val cacheRoot: String,
) : AutoCloseable {
    private val stable =
        client(URI(baseUri), packSet, PackSetChannel.STABLE, Path.of(cacheRoot, "stable"))
    private val edge =
        client(URI(baseUri), packSet, PackSetChannel.EDGE, Path.of(cacheRoot, "edge"))

    internal constructor(
        stable: PackSetClient,
        edge: PackSetClient,
    ) : this("https://cdn.grounds.gg", "grounds-global", "/tmp/service-maps/packsets") {
        stableOverride = stable
        edgeOverride = edge
    }

    private var stableOverride: PackSetClient? = null
    private var edgeOverride: PackSetClient? = null
    private val stableClient
        get() = stableOverride ?: stable

    private val edgeClient
        get() = edgeOverride ?: edge

    fun onStart(@Observes event: StartupEvent) = start()

    fun onStop(@Observes event: ShutdownEvent) = close()

    fun start() {
        stableClient.start()
        edgeClient.start()
    }

    fun candidates(): List<AssetCatalogCandidate> =
        listOfNotNull(
            candidate("stable", stableClient.state()),
            candidate("edge", edgeClient.state()),
        )

    fun ready(): Boolean = candidates().isNotEmpty()

    fun channelStates(): Map<String, CatalogChannelState> =
        mapOf("stable" to stateOf(stableClient.state()), "edge" to stateOf(edgeClient.state()))

    private fun candidate(channel: String, state: PackSetClientState): AssetCatalogCandidate? {
        val snapshot = state.current ?: state.degradedFallback ?: return null
        return candidate(channel, snapshot)
    }

    private fun candidate(channel: String, snapshot: PackSetSnapshot): AssetCatalogCandidate {
        val catalog = snapshot.manifest.catalog
        val manifestUri = URI(snapshot.channel.manifest.url)
        val uri = manifestUri.resolve(catalog.file)
        return AssetCatalogCandidate(
            channel = channel,
            id = catalog.id,
            version = catalog.version,
            coordinate = catalog.coordinate,
            file = catalog.file,
            uri = uri,
            sha256 = catalog.sha256,
            size = catalog.size,
        )
    }

    private fun stateOf(state: PackSetClientState) =
        CatalogChannelState(
            state.current != null || state.degradedFallback != null,
            state.status.name,
            state.lastError?.replace(Regex("https?://[^\\s]+"), "<redacted-url>"),
        )

    override fun close() {
        stableClient.close()
        edgeClient.close()
    }

    companion object {
        fun configured(baseUri: URI, packSet: String, cacheRoot: Path): PackSetCatalogProvider =
            PackSetCatalogProvider(
                client(baseUri, packSet, PackSetChannel.STABLE, cacheRoot.resolve("stable")),
                client(baseUri, packSet, PackSetChannel.EDGE, cacheRoot.resolve("edge")),
            )

        private fun client(baseUri: URI, packSet: String, channel: PackSetChannel, cache: Path) =
            PackSetClient(PackSetClientConfig(PackSetSource(baseUri, packSet, channel), cache))
    }
}

data class CatalogChannelState(val available: Boolean, val status: String, val lastError: String?)
