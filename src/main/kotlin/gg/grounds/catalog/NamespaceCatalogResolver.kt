package gg.grounds.catalog

import gg.grounds.domain.CatalogReference
import gg.grounds.scene.format.ActionCatalog
import jakarta.enterprise.context.ApplicationScoped

data class ActionCatalogResolution(val catalog: ActionCatalog)

/** Runtime boundary for namespaces whose action catalog is not supplied by a PackSet artifact. */
interface NamespaceCatalogResolver {
    fun resolve(namespace: String, assetCatalog: CatalogReference): ActionCatalogResolution
}

@ApplicationScoped
class DefaultNamespaceCatalogResolver : NamespaceCatalogResolver {
    override fun resolve(
        namespace: String,
        assetCatalog: CatalogReference,
    ): ActionCatalogResolution =
        when (namespace) {
            "grounds" -> ActionCatalogResolution(EmptyGroundsActionCatalog.catalog)
            else -> throw IllegalArgumentException("Unsupported action namespace: $namespace")
        }
}
