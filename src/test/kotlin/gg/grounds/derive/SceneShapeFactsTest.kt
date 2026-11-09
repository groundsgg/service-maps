package gg.grounds.derive

import gg.grounds.domain.SceneShapeRequirements
import gg.grounds.scene.format.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SceneShapeFactsTest {
    private val rules = SceneShapeRequirements(setOf("demo-hands"), setOf("demo-"))

    private fun document(
        x: Double = 0.0,
        body: String = "grounds:guide",
        present: Boolean = true,
    ): SceneDocument {
        val npc =
            Npc(
                LocalId("demo-hands"),
                null,
                Transform(Vec3(x, 0.0, 0.0), ZERO_ROTATION, Vec3(1.0, 1.0, 1.0)),
                body = AssetKey(body),
                label = null,
                labelOffset = ORIGIN,
                look = LookBehavior.Fixed,
                initialAnimation = null,
                interactionBounds = LocalBounds(ORIGIN, Vec3(1.0, 1.0, 1.0)),
                proximity = null,
                bindings = emptyList(),
            )
        return SceneDocument(
            1,
            SceneId("grounds:test"),
            SceneMetadata("test", null, emptySet()),
            SceneCatalogReferences(
                CatalogReference(CatalogId("grounds:assets"), "1"),
                CatalogReference(CatalogId("grounds:actions"), "1"),
            ),
            emptyList(),
            if (present) listOf(npc) else emptyList(),
        )
    }

    @Test
    fun `shape facts ignore editable positions but detect changed types bodies and missing stations`() {
        val initial = SceneShapeFacts.extract(document(), rules)!!
        assertEquals(initial, SceneShapeFacts.extract(document(x = 123.0), rules))
        assertNotEquals(
            initial["element:demo-hands"],
            SceneShapeFacts.extract(document(body = "grounds:other"), rules)!!["element:demo-hands"],
        )
        val absent = SceneShapeFacts.extract(document(present = false), rules)!!
        assertFalse(absent.containsKey("element:demo-hands"))
        assertEquals("0", absent["count:demo-"])
        assertEquals("1", initial["count:demo-"])
    }
}
