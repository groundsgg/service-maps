package gg.grounds.catalog

import gg.grounds.domain.CatalogReference
import gg.grounds.scene.format.ActionKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NamespaceCatalogResolverTest {
    @Test
    fun `grounds namespace resolves the requested legacy action catalog`() {
        val resolver: NamespaceCatalogResolver = DefaultNamespaceCatalogResolver()
        val resolution =
            resolver.resolve(
                "grounds",
                CatalogReference("grounds:assets", "1"),
                CatalogReference("grounds:actions", "1"),
            )

        assertEquals("grounds:actions", resolution.catalog.id.value)
        assertEquals("1", resolution.catalog.version)
        assertEquals(emptyMap<Any, Any>(), resolution.catalog.actions)
    }

    @Test
    fun `grounds namespace resolves navigator from requested current action catalog`() {
        val result =
            DefaultNamespaceCatalogResolver()
                .resolve(
                    "grounds",
                    CatalogReference("grounds:assets", "1"),
                    CatalogReference("grounds:actions", "2"),
                )

        assertEquals(setOf(ActionKey("grounds:lobby/open_navigator")), result.catalog.actions.keys)
    }

    @Test
    fun `unsupported namespace is rejected through resolver`() {
        assertThrows(IllegalArgumentException::class.java) {
            val resolver: NamespaceCatalogResolver = DefaultNamespaceCatalogResolver()
            resolver.resolve(
                "other",
                CatalogReference("other:assets", "1"),
                CatalogReference("other:actions", "1"),
            )
        }
    }

    @Test
    fun `unknown grounds action id and revision are content failures`() {
        val resolver = DefaultNamespaceCatalogResolver()
        listOf(CatalogReference("grounds:unknown", "1"), CatalogReference("grounds:actions", "999"))
            .forEach { requested ->
                assertThrows(CatalogContentException::class.java) {
                    resolver.resolve("grounds", CatalogReference("grounds:assets", "1"), requested)
                }
            }
    }
}
