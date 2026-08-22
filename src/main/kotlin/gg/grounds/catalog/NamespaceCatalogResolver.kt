package gg.grounds.catalog

import gg.grounds.domain.CatalogReference
import gg.grounds.scene.format.ActionCatalog

data class ActionCatalogResolution(val catalog: ActionCatalog)

/** Resolves actions belonging to namespaces which are owned by this service. */
class NamespaceCatalogResolver {
    fun resolve(namespace: String, assetCatalog: CatalogReference): ActionCatalogResolution =
        when (namespace) {
            "grounds" -> ActionCatalogResolution(EmptyGroundsActionCatalog.catalog)
            else -> throw IllegalArgumentException("Unsupported action namespace: $namespace")
        }
}
