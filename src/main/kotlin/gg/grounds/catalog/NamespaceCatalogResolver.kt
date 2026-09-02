package gg.grounds.catalog

import gg.grounds.domain.CatalogReference
import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.scene.format.ActionCatalog
import gg.grounds.scene.format.CatalogId
import gg.grounds.scene.format.CatalogReference as SceneCatalogReference
import jakarta.enterprise.context.ApplicationScoped

data class ActionCatalogResolution(val catalog: ActionCatalog)

/** Runtime boundary for namespaces whose action catalog is not supplied by a PackSet artifact. */
interface NamespaceCatalogResolver {
    fun resolve(
        namespace: String,
        assetCatalog: CatalogReference,
        actionCatalog: CatalogReference,
    ): ActionCatalogResolution
}

@ApplicationScoped
class DefaultNamespaceCatalogResolver : NamespaceCatalogResolver {
    override fun resolve(
        namespace: String,
        assetCatalog: CatalogReference,
        actionCatalog: CatalogReference,
    ): ActionCatalogResolution =
        when (namespace) {
            "grounds" ->
                try {
                    ActionCatalogResolution(
                        requireNotNull(
                            LobbySceneCatalogs.resolve(
                                SceneCatalogReference(
                                    CatalogId(actionCatalog.id),
                                    actionCatalog.version,
                                )
                            )
                        ) {
                            "action catalog is not available"
                        }
                    )
                } catch (failure: IllegalArgumentException) {
                    throw CatalogContentException("action catalog is invalid", failure)
                }
            else -> throw IllegalArgumentException("Unsupported action namespace: $namespace")
        }
}
