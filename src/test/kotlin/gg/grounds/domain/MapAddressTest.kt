package gg.grounds.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MapAddressTest {

    @Test
    fun `splits a two segment address`() {
        val address = MapAddress.parse("bedwars/4x4-baumhaus")
        assertEquals("bedwars", address?.namespace)
        assertEquals("4x4-baumhaus", address?.name)
    }

    /**
     * The case the whole catch-all path parameter exists for: a creator namespace is itself two
     * segments, so splitting from the left would file this map under namespace `u`.
     */
    @Test
    fun `splits a creator address at the last slash`() {
        val address = MapAddress.parse("u/hendrik/treehouse")
        assertEquals("u/hendrik", address?.namespace)
        assertEquals("treehouse", address?.name)
    }

    @Test
    fun `rejects addresses that are not addresses`() {
        assertNull(MapAddress.parse("lobby"), "no namespace")
        assertNull(MapAddress.parse("/lobby"), "empty namespace")
        assertNull(MapAddress.parse("bedwars/"), "empty name")
        assertNull(MapAddress.parse("a/b/c/d"), "namespace of three segments")
        assertNull(MapAddress.parse("Bedwars/Crater"), "uppercase")
        assertNull(MapAddress.parse("bedwars/-crater"), "leading dash")
        assertNull(MapAddress.parse("bedwars/cra ter"), "space")
        assertNull(MapAddress.parse("bedwars/../etc"), "path traversal")
    }

    @Test
    fun `renders back to the address it came from`() {
        assertEquals("u/hendrik/treehouse", MapAddress.parse("u/hendrik/treehouse").toString())
    }
}
