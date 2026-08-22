package gg.grounds.catalog

import gg.grounds.derive.AssetCatalogCandidate
import java.net.URI
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CatalogJarLoaderTest {
    @Test
    fun `rejects an untrusted catalog scheme before downloading`() {
        CatalogJarLoader(Files.createTempDirectory("catalog-loader")).use { loader ->
            assertThrows(IllegalArgumentException::class.java) {
                loader.load(
                    AssetCatalogCandidate(
                        "stable",
                        "grounds:assets",
                        "1",
                        "gg.grounds:resourcepacks-catalog:1",
                        "catalog.jar",
                        URI("http://localhost/catalog.jar"),
                        "a".repeat(64),
                        1,
                    )
                )
            }
        }
    }

    @Test
    fun `rejects a catalog larger than the absolute cap`() {
        CatalogJarLoader(Files.createTempDirectory("catalog-loader"), maxCatalogBytes = 1).use {
            loader ->
            assertThrows(IllegalArgumentException::class.java) {
                loader.load(
                    AssetCatalogCandidate(
                        "stable",
                        "grounds:assets",
                        "1",
                        "gg.grounds:resourcepacks-catalog:1",
                        "catalog.jar",
                        URI("https://catalogs.example/catalog.jar"),
                        "a".repeat(64),
                        2,
                    )
                )
            }
        }
    }
}
