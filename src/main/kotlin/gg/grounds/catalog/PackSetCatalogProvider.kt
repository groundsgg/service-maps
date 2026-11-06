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
import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.microprofile.config.inject.ConfigProperty

/** Validated Stable/Edge snapshots plus explicitly pinned operator-owned catalogs. */
@ApplicationScoped
class PackSetCatalogProvider
internal constructor(
    private val stableClient: PackSetClient,
    private val edgeClient: PackSetClient,
    private val pinned: PinnedCatalogs = PinnedCatalogs.empty(),
) : AutoCloseable {
    @Inject
    constructor(
        @ConfigProperty(name = "grounds.maps.catalogs.base-uri") baseUri: String,
        @ConfigProperty(name = "grounds.maps.catalogs.pack-set") packSet: String,
        @ConfigProperty(name = "grounds.maps.catalogs.cache-root") cacheRoot: String,
        @ConfigProperty(name = "grounds.maps.catalogs.pinned-json", defaultValue = "[]")
        pinnedJson: String,
    ) : this(
        client(URI(baseUri), packSet, PackSetChannel.STABLE, Path.of(cacheRoot, "stable")),
        client(URI(baseUri), packSet, PackSetChannel.EDGE, Path.of(cacheRoot, "edge")),
        PinnedCatalogs.parse(pinnedJson, URI(baseUri)),
    )

    fun onStart(@Observes event: StartupEvent) = start()

    fun onStop(@Observes event: ShutdownEvent) = close()

    fun start() {
        stableClient.start()
        edgeClient.start()
    }

    private fun channelCandidates(): List<AssetCatalogCandidate> =
        listOfNotNull(
            candidate("stable", stableClient.state()),
            candidate("edge", edgeClient.state()),
        )

    fun candidates(): List<AssetCatalogCandidate> = pinned.merge(channelCandidates()).candidates

    fun ready(): Boolean = channelCandidates().isNotEmpty()

    fun pinState(): CatalogPinState =
        CatalogPinState(pinned.entries.size, pinned.merge(channelCandidates()).conflicts)

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

        private fun client(
            baseUri: URI,
            packSet: String,
            channel: PackSetChannel,
            cache: Path,
        ): PackSetClient {
            // The channel cache deliberately creates only its own root. On a fresh pod the
            // configured shared parent may be several levels below the writable /tmp mount.
            Files.createDirectories(cache.parent)
            return PackSetClient(
                PackSetClientConfig(PackSetSource(baseUri, packSet, channel), cache)
            )
        }
    }
}

data class CatalogChannelState(val available: Boolean, val status: String, val lastError: String?)

data class CatalogPinState(val configured: Int, val conflicts: List<String>)
