package gg.grounds.pins

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Game servers parse every pin entry; ones released before lit copies existed would choke on a
 * `"lit": null`, so an entry without a lit copy must leave the key out.
 */
class PinnedEntryJsonTest {
    private val json = jacksonObjectMapper()

    @Test
    fun `an entry without a lit copy has no lit key`() {
        val tree =
            json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
                PinnedEntry(2, "a".repeat(64), "https://maps/bundle.tar.zst", 10, null)
            )

        assertFalse(tree.has("lit"))
        assertFalse(tree.has("lod"))
        // The existing fields keep their nulls, as before.
        assertEquals(true, tree.has("estLoadedMib"))
    }

    @Test
    fun `an entry with a lit copy names its format, digest and url`() {
        val tree =
            json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
                PinnedEntry(
                    2,
                    "a".repeat(64),
                    "https://maps/a.tar.zst",
                    10,
                    3,
                    LitEntry(1, "b".repeat(64), "https://maps/b.tar.zst"),
                )
            )

        assertEquals(1, tree["lit"]["format"].asInt())
        assertEquals("b".repeat(64), tree["lit"]["bundleSha256"].asText())
        assertEquals("https://maps/b.tar.zst", tree["lit"]["bundleUrl"].asText())
    }

    @Test
    fun `LOD reference requires the exact canonical source prelit source and trust domain`() {
        val sha = "a".repeat(64)
        val lit = "b".repeat(64)
        val claim =
            gg.grounds.domain.LodClaim(
                java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(),
                gg.grounds.domain.MapTrust.FIRST_PARTY,
                sha,
                lit,
                "c".repeat(64),
                "d".repeat(64),
                "ghcr.io/image@sha256:${"e".repeat(64)}",
                java.time.Instant.parse("2026-11-08T12:00:00Z"),
                gg.grounds.domain.LodFacts("1".repeat(64), 100, "2".repeat(64), 50, 1),
                true,
            )
        val entry =
            LodPinEntries.forMap(
                sha,
                gg.grounds.domain.MapTrust.FIRST_PARTY,
                lit,
                listOf(claim),
                "https://maps.grounds.gg",
            )!!
        assertEquals("lss20", entry.artifacts.single().format)
        assertEquals(claim.contentTimestampMillis, entry.artifacts.single().contentTimestampMillis)
        assertEquals(
            null,
            LodPinEntries.forMap("f".repeat(64), claim.trust, lit, listOf(claim), "https://maps"),
        )
        assertEquals(
            null,
            LodPinEntries.forMap(
                sha,
                gg.grounds.domain.MapTrust.UNTRUSTED,
                lit,
                listOf(claim),
                "https://ugc",
            ),
        )
        assertEquals(
            null,
            LodPinEntries.forMap(sha, claim.trust, "f".repeat(64), listOf(claim), "https://maps"),
        )
        assertEquals(
            null,
            LodPinEntries.forMap(
                sha,
                claim.trust,
                lit,
                listOf(claim.copy(published = false)),
                "https://maps",
            ),
        )
        val tree =
            json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
                PinnedEntry(13, sha, "https://maps/source", 100, 1, lod = entry)
            )
        assertEquals(
            "https://maps.grounds.gg/lod/lss20/${"2".repeat(64)}/manifest.json",
            tree["lod"]["artifacts"][0]["manifestUrl"].asText(),
        )
    }
}
