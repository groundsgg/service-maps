package gg.grounds.catalog

import gg.grounds.resourcepacks.client.PackSetClient
import gg.grounds.resourcepacks.client.PackSetClientConfig
import gg.grounds.resourcepacks.client.PackSetClientState
import gg.grounds.resourcepacks.client.PackSetSnapshot
import gg.grounds.resourcepacks.client.PackSetSource
import gg.grounds.resourcepacks.contract.PackSetChannel
import java.net.URI
import java.nio.file.Path

/** The only source of runtime catalogs: validated PackSet snapshots for Stable then Edge. */
class PackSetCatalogProvider(private val stable: PackSetClient, private val edge: PackSetClient) :
    AutoCloseable {
    fun start() {
        stable.start()
        edge.start()
    }

    fun candidates(): List<AssetCatalogCandidate> =
        listOfNotNull(candidate("stable", stable.state()), candidate("edge", edge.state()))

    fun ready(): Boolean = candidates().isNotEmpty()

    fun channelStates(): Map<String, CatalogChannelState> =
        mapOf("stable" to stateOf(stable.state()), "edge" to stateOf(edge.state()))

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
            state.lastError,
        )

    override fun close() {
        stable.close()
        edge.close()
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
