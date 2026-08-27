package gg.grounds.catalog

import gg.grounds.domain.CatalogReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NamespaceCatalogResolverTest {
    @Test
    fun `grounds namespace has immutable empty actions catalog`() {
        val resolver: NamespaceCatalogResolver = DefaultNamespaceCatalogResolver()
        val resolution = resolver.resolve("grounds", CatalogReference("grounds:assets", "1"))

        assertEquals("grounds:actions", resolution.catalog.id.value)
        assertEquals("1", resolution.catalog.version)
        assertEquals(emptyMap<Any, Any>(), resolution.catalog.actions)
    }

    @Test
    fun `unsupported namespace is rejected through resolver`() {
        assertThrows(IllegalArgumentException::class.java) {
            val resolver: NamespaceCatalogResolver = DefaultNamespaceCatalogResolver()
            resolver.resolve("other", CatalogReference("other:assets", "1"))
        }
    }
}
