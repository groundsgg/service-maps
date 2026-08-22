package gg.grounds.derive

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import gg.grounds.domain.CatalogReference
import java.net.URI
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeriveContractsTest {
    @Test
    fun `canonical JSON is stable sorted and newline terminated`() {
        val manifest =
            DerivedManifest(
                sourceSha256 = digest(1),
                bundleDigestInputs = listOf("world/level.dat", "scene.json"),
                scene = presentScene(requiredActions = listOf("z.action", "a.action", "z.action")),
            )

        val first = CanonicalJson.write(manifest)
        val second = CanonicalJson.write(manifest)

        assertTrue(first.contentEquals(second))
        assertEquals(
            "{\"bundleDigestInputs\":[\"scene.json\",\"world/level.dat\"],\"scene\":{\"actionCatalog\":{\"id\":\"grounds\",\"version\":\"1\"},\"assetCatalog\":{\"id\":\"assets\",\"version\":\"2026.08\"},\"present\":true,\"requiredActions\":[\"a.action\",\"z.action\"],\"schemaVersion\":\"1\",\"sha256\":\"${digest(2)}\"},\"schemaVersion\":1,\"sourceSha256\":\"${digest(1)}\"}\n",
            first.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `strict result parser rejects unknown fields`() {
        val bytes = javaClass.getResourceAsStream("/derive/result-unknown-field.json")!!.readBytes()

        assertThrows(UnrecognizedPropertyException::class.java) { CanonicalJson.readResult(bytes) }
    }

    @Test
    fun `strict result parser accepts valid result`() {
        val bytes = javaClass.getResourceAsStream("/derive/result-valid.json")!!.readBytes()

        val result = CanonicalJson.readResult(bytes)

        assertEquals(DeriveResultKind.SUCCESS, (result as DeriveSuccess).kind)
        assertEquals(listOf("a.action", "z.action"), result.scene.requiredActions)
    }

    @Test
    fun `contract constructors reject noncanonical digests and invalid urls`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                DeriveRequest(
                    mapId = UUID.randomUUID(),
                    version = 1,
                    attempt = UUID.randomUUID(),
                    sourceSha256 = ("a" + "0".repeat(63)).uppercase(),
                    sourceUrl = URI("ftp://worker.test/source"),
                    bundleUrl = URI("https://worker.test/bundle"),
                    manifestUrl = URI("https://worker.test/manifest"),
                    resultUrl = URI("https://worker.test/result"),
                    catalogCandidates = emptyList(),
                )
            }

        assertTrue(error.message!!.contains("sourceSha256"))
    }

    @Test
    fun `present scene requires both catalog references`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                DeriveSuccess(
                    mapId = UUID.randomUUID(),
                    version = 1,
                    attempt = UUID.randomUUID(),
                    sourceSha256 = digest(1),
                    bundleSha256 = digest(2),
                    bundleSize = 10,
                    manifestSha256 = digest(3),
                    manifestSize = 5,
                    scene =
                        DerivedScene(
                            present = true,
                            schemaVersion = "1",
                            sha256 = digest(4),
                            assetCatalog = CatalogReference("assets", "2026.08"),
                            actionCatalog = null,
                            requiredActions = emptyList(),
                        ),
                )
            }

        assertTrue(error.message!!.contains("actionCatalog"))
    }

    private fun presentScene(requiredActions: List<String>) =
        DerivedScene(
            present = true,
            schemaVersion = "1",
            sha256 = digest(2),
            assetCatalog = CatalogReference("assets", "2026.08"),
            actionCatalog = CatalogReference("grounds", "1"),
            requiredActions = requiredActions,
        )

    private fun digest(value: Int) = "%064x".format(value)
}
