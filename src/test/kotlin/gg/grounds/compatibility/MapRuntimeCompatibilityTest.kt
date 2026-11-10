package gg.grounds.compatibility

import gg.grounds.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MapRuntimeCompatibilityTest {
    private val profile =
        """[{"environment":"stage","address":"lobby/mainlobby","image":"ghcr.io/groundsgg/lobby@sha256:${"a".repeat(64)}","schemaVersions":["1"],"assetCatalog":{"id":"grounds:assets","version":"runtime"},"actionCatalogs":[{"id":"grounds:actions","version":"3"}],"supportedActions":["grounds:lobby/open_navigator"],"requireScene":true,"normalizeUploads":true}]"""

    private fun scene() =
        SceneProjection(
            SceneStatus.VALID,
            "1",
            "b".repeat(64),
            CatalogReference("grounds:assets", "runtime"),
            CatalogReference("grounds:actions", "3"),
            listOf("grounds:lobby/open_navigator"),
            emptyList(),
        )

    private val policy
        get() = MapRuntimeCompatibility("stage", profile)

    @Test
    fun `known demo shape requirements reject missing or changed stations before activation`() {
        val configured =
            profile.dropLast(2) +
                ",\"requiredElements\":{\"demo-hands\":\"${"c".repeat(64)}\"},\"elementCounts\":{\"demo-\":15}}]"
        val guarded = MapRuntimeCompatibility("stage", configured)
        assertNotNull(guarded.problem("stage", "lobby/mainlobby", scene()))
        val facts = mapOf("element:demo-hands" to "c".repeat(64), "count:demo-" to "15")
        assertNull(guarded.problem("stage", "lobby/mainlobby", scene().copy(runtimeFacts = facts)))
        assertNotNull(
            guarded.problem(
                "stage",
                "lobby/mainlobby",
                scene().copy(runtimeFacts = facts - "element:demo-hands"),
            )
        )
        assertNotNull(
            guarded.problem(
                "stage",
                "lobby/mainlobby",
                scene().copy(runtimeFacts = facts + ("element:demo-hands" to "d".repeat(64))),
            )
        )
        assertNotNull(
            guarded.problem(
                "stage",
                "lobby/mainlobby",
                scene().copy(runtimeFacts = facts + ("count:demo-" to "14")),
            )
        )
    }

    @Test
    fun `known scene requirements match a digest-bound runtime without a per-map hash`() {
        assertNull(policy.problem("stage", "lobby/mainlobby", scene()))
        assertEquals(
            CatalogReference("grounds:assets", "runtime"),
            policy.normalizationCatalog("lobby/mainlobby"),
        )
    }

    @Test
    fun `catalog mismatches explain the unsupported requirement to the portal`() {
        val problem =
            policy.problem(
                "stage",
                "lobby/mainlobby",
                scene().copy(assetCatalog = CatalogReference("grounds:assets", "editor-old")),
            )!!
        assertTrue(problem.contains("editor-old"))
        assertTrue(problem.contains("runtime"))
    }

    @Test
    fun `rejects unsupported schema action revision and required action`() {
        listOf(
                scene().copy(schemaVersion = "2"),
                scene().copy(actionCatalog = CatalogReference("grounds:actions", "4")),
                scene().copy(requiredActions = listOf("grounds:lobby/unknown")),
            )
            .forEach { assertNotNull(policy.problem("stage", "lobby/mainlobby", it)) }
    }

    @Test
    fun `enabled targets fail closed when the deployed runtime contract is missing`() {
        assertNotNull(
            MapRuntimeCompatibility("stage", "[]").problem("stage", "lobby/mainlobby", scene())
        )
        assertNotNull(policy.problem("stage", "lobby/other", scene()))
        assertNull(policy.problem("prod", "lobby/mainlobby", scene()))
    }

    @Test
    fun `demo targets require a valid scene while ordinary maps without scenes remain usable`() {
        val absent =
            SceneProjection(SceneStatus.NONE, null, null, null, null, emptyList(), emptyList())
        assertNotNull(policy.problem("stage", "lobby/mainlobby", absent))
        assertNull(policy.problem("stage", "arena/no-scene", absent))
        assertNotNull(
            policy.problem("stage", "lobby/mainlobby", scene().copy(status = SceneStatus.INVALID))
        )
    }

    @Test
    fun `rejects ambiguous targets unknown fields and mutable runtime images`() {
        listOf(
                profile.dropLast(1) + "," + profile.drop(1),
                profile.replace("\"requireScene\"", "\"typo\""),
                profile.replace("@sha256:${"a".repeat(64)}", ":latest"),
            )
            .forEach {
                assertThrows(IllegalArgumentException::class.java) {
                    MapRuntimeCompatibility("stage", it)
                }
            }
    }
}
