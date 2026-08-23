package gg.grounds.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class DerivedFactsTest {

    @Test
    fun `no-scene facts reject every scene detail`() {
        assertThrows<IllegalArgumentException> {
            facts(
                SceneProjection(
                    SceneStatus.NONE,
                    null,
                    digest(1),
                    null,
                    null,
                    emptyList(),
                    emptyList(),
                )
            )
        }
        assertThrows<IllegalArgumentException> {
            facts(
                SceneProjection(
                    SceneStatus.NONE,
                    null,
                    null,
                    CatalogReference("assets", "1"),
                    null,
                    emptyList(),
                    emptyList(),
                )
            )
        }
    }

    @Test
    fun `valid facts require complete scene and catalog identity`() {
        assertThrows<IllegalArgumentException> {
            facts(
                SceneProjection(SceneStatus.VALID, "1", null, null, null, emptyList(), emptyList())
            )
        }
        assertThrows<IllegalArgumentException> {
            facts(
                SceneProjection(
                    SceneStatus.VALID,
                    "1",
                    digest(1),
                    CatalogReference("assets", "1"),
                    null,
                    emptyList(),
                    emptyList(),
                )
            )
        }
        assertDoesNotThrow {
            facts(
                SceneProjection(
                    SceneStatus.VALID,
                    "1",
                    digest(1),
                    CatalogReference("assets", "1"),
                    CatalogReference("actions", "1"),
                    emptyList(),
                    emptyList(),
                )
            )
        }
    }

    private fun facts(scene: SceneProjection) =
        DerivedFacts(digest(2), digest(3), 1, null, null, scene)

    private fun digest(value: Int) = "%064x".format(value)
}
