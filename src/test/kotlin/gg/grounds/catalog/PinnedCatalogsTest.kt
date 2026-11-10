package gg.grounds.catalog

import gg.grounds.derive.AssetCatalogCandidate
import java.net.URI
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PinnedCatalogsTest {
    private val base = URI("https://cdn.example.test")
    private val sha = "a".repeat(64)
    private val version = "0.0.0-edge.42.g0123456789ab"
    private val uri = "$base/resourcepacks/catalog-pins/npc-stage/$version/$sha/catalog.jar"

    private fun json(
        uri: String = this.uri,
        version: String = this.version,
        sha: String = this.sha,
        size: Long = 1234,
        coordinate: String = "gg.grounds:resourcepacks-catalog:$version",
    ) =
        """{"channel":"pinned","id":"grounds:resourcepacks","version":"$version","coordinate":"$coordinate","file":"catalog.jar","uri":"$uri","sha256":"$sha","size":$size}"""

    @Test
    fun `empty configuration preserves current catalogs and valid pins add an exact candidate`() {
        val channels = listOf(candidate("stable", "1.4.0", "b".repeat(64)))
        assertEquals(channels, PinnedCatalogs.parse("[]", base).merge(channels).candidates)
        val selection = PinnedCatalogs.parse("[${json()}]", base).merge(channels)
        assertEquals(listOf("1.4.0", version), selection.candidates.map { it.version })
        assertEquals(URI(uri), selection.candidates.last().uri)
        assertTrue(selection.conflicts.isEmpty())
    }

    @Test
    fun `retains the editor catalog alongside both published map catalogs`() {
        val references =
            (1..3).map { index ->
                val revision = "0.0.0-edge.${40 + index}.g0123456789ab"
                json(
                    version = revision,
                    uri = "$base/resourcepacks/catalog-pins/npc-stage/$revision/$sha/catalog.jar",
                )
            }
        val channels = listOf(candidate("stable", "1.6.0", "b".repeat(64)))

        val selection =
            PinnedCatalogs.parse(references.joinToString(",", "[", "]"), base).merge(channels)

        assertEquals(4, selection.candidates.size)
        assertEquals(
            listOf(
                "1.6.0",
                "0.0.0-edge.41.g0123456789ab",
                "0.0.0-edge.42.g0123456789ab",
                "0.0.0-edge.43.g0123456789ab",
            ),
            selection.candidates.map { it.version },
        )
        assertTrue(selection.conflicts.isEmpty())
    }

    @Test
    fun `catalog history remains bounded to eight metadata entries`() {
        val references =
            (1..8).map { index ->
                val revision = "0.0.0-edge.${40 + index}.g0123456789ab"
                json(
                    version = revision,
                    uri = "$base/resourcepacks/catalog-pins/npc-stage/$revision/$sha/catalog.jar",
                )
            }

        assertEquals(
            8,
            PinnedCatalogs.parse(references.joinToString(",", "[", "]"), base).entries.size,
        )
        assertThrows(IllegalArgumentException::class.java) {
            PinnedCatalogs.parse(
                (references + references.first()).joinToString(",", "[", "]"),
                base,
            )
        }
    }

    @Test
    fun `rejects foreign mutable and encoded pin locations`() {
        for (bad in
            listOf(
                uri.replace("https:", "http:"),
                uri.replace("cdn.example.test", "evil.example.test"),
                uri.replace("cdn.example.test", "user@cdn.example.test"),
                uri + "?token=redacted",
                uri + "#fragment",
                uri.replace("cdn.example.test", "cdn.example.test:443"),
                uri.replace("/catalog-pins/", "/catalog-pins-evil/"),
                uri.replace("/npc-stage/", "/npc-stage/../npc-stage/"),
                uri.replace("/npc-stage/", "/npc-stage//"),
                uri.replace("/npc-stage/", "/%6epc-stage/"),
            )) {
            assertThrows(IllegalArgumentException::class.java) {
                PinnedCatalogs.parse("[${json(uri = bad)}]", base)
            }
        }
    }

    @Test
    fun `rejects unbounded metadata invalid coordinates and conflicting configured pins`() {
        for (bad in
            listOf(
                "[${json(size = 0)}]",
                "[${json(size = 33554433)}]",
                "[${json(sha = "bad")}]",
                "[${json(coordinate = "another:module:$version")}]",
                List(9) { json() }.joinToString(",", "[", "]"),
                "[]" + " ".repeat(65537),
                "[${json()},${json(sha = "b".repeat(64), uri = uri.replace(sha, "b".repeat(64)))}]",
            )) {
            assertThrows(IllegalArgumentException::class.java) { PinnedCatalogs.parse(bad, base) }
        }
    }

    @Test
    fun `deduplicates identical candidates and withholds a pin conflicting after channel refresh`() {
        val pins = PinnedCatalogs.parse("[${json()},${json()}]", base)
        val same = candidate("edge", version, sha)
        val identical = pins.merge(listOf(same, same.copy(channel = "stable")))
        assertEquals(listOf(same), identical.candidates)
        assertTrue(identical.conflicts.isEmpty())
        val refreshed = same.copy(sha256 = "b".repeat(64))
        val conflicted = pins.merge(listOf(refreshed))
        assertEquals(listOf(refreshed), conflicted.candidates)
        assertEquals(listOf(version), conflicted.conflicts)
        assertEquals(
            2,
            pins.merge(listOf(candidate("stable", "1.4.0", "c".repeat(64)))).candidates.size,
        )
    }

    private fun candidate(channel: String, version: String, sha: String) =
        AssetCatalogCandidate(
            channel,
            "grounds:resourcepacks",
            version,
            "gg.grounds:resourcepacks-catalog:$version",
            "catalog.jar",
            URI(uri),
            sha,
            1234,
        )
}
