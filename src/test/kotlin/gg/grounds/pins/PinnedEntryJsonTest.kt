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
}
