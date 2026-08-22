package gg.grounds.catalog

import java.net.URI
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PackSetCatalogProviderTest {
    @Test
    fun `configured clients start unavailable with separate channel caches`() {
        val root = Files.createTempDirectory("packset-catalogs")
        PackSetCatalogProvider.configured(URI("https://cdn.grounds.gg"), "grounds-global", root)
            .use { provider ->
                assertFalse(provider.ready())
                assertTrue(provider.candidates().isEmpty())
                assertTrue(provider.channelStates().values.none { state -> state.available })
            }
    }
}
