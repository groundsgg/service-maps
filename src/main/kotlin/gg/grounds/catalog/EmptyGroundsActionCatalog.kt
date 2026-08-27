package gg.grounds.catalog

import gg.grounds.scene.format.ActionCatalog
import gg.grounds.scene.format.CatalogId

object EmptyGroundsActionCatalog {
    val catalog: ActionCatalog = ActionCatalog(CatalogId("grounds:actions"), "1", emptyMap())
}
