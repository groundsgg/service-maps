package gg.grounds.domain

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class DerivedFactsTest {

    @TestFactory
    fun `none scene rejects each independently populated field`() =
        listOf(
                "schema version" to noneScene().copy(schemaVersion = "1"),
                "scene digest" to noneScene().copy(sha256 = digest(1)),
                "asset catalog" to noneScene().copy(assetCatalog = CatalogReference("assets", "1")),
                "action catalog" to
                    noneScene().copy(actionCatalog = CatalogReference("actions", "1")),
                "required actions" to noneScene().copy(requiredActions = listOf("grounds:action")),
                "problems" to
                    noneScene()
                        .copy(
                            problems =
                                listOf(
                                    DeriveProblem(
                                        DeriveFailureScope.CONTENT,
                                        null,
                                        "BAD",
                                        null,
                                        "bad",
                                    )
                                )
                        ),
            )
            .map { (field, scene) ->
                DynamicTest.dynamicTest("NONE rejects $field") {
                    assertThrows<IllegalArgumentException> { facts(scene) }
                }
            }

    @TestFactory
    fun `valid scene requires each complete fact`() =
        listOf(
                "missing schema version" to validScene().copy(schemaVersion = null),
                "missing scene digest" to validScene().copy(sha256 = null),
                "malformed scene digest" to validScene().copy(sha256 = "not-a-digest"),
                "missing asset catalog identity" to validScene().copy(assetCatalog = null),
                "missing action catalog identity" to validScene().copy(actionCatalog = null),
                "problems" to
                    validScene()
                        .copy(
                            problems =
                                listOf(
                                    DeriveProblem(DeriveFailureScope.SYSTEM, null, "IO", null, "io")
                                )
                        ),
            )
            .map { (field, scene) ->
                DynamicTest.dynamicTest("VALID rejects $field") {
                    assertThrows<IllegalArgumentException> { facts(scene) }
                }
            }

    @TestFactory
    fun `only none and valid scene statuses are successful facts`() =
        listOf(SceneStatus.PENDING, SceneStatus.INVALID).map { status ->
            DynamicTest.dynamicTest("$status is not a successful scene status") {
                assertThrows<IllegalArgumentException> { facts(validScene().copy(status = status)) }
            }
        }

    @TestFactory
    fun `valid scene permits canonical action requirements`() =
        listOf(emptyList(), listOf("grounds:action")).map { actions ->
            DynamicTest.dynamicTest("VALID accepts ${actions.size} required actions") {
                assertDoesNotThrow { facts(validScene().copy(requiredActions = actions)) }
            }
        }

    private fun noneScene() =
        SceneProjection(SceneStatus.NONE, null, null, null, null, emptyList(), emptyList())

    private fun validScene() =
        SceneProjection(
            SceneStatus.VALID,
            "1",
            digest(1),
            CatalogReference("assets", "1"),
            CatalogReference("actions", "1"),
            emptyList(),
            emptyList(),
        )

    private fun facts(scene: SceneProjection) =
        DerivedFacts(digest(2), digest(3), 1, null, null, scene)

    private fun digest(value: Int) = "%064x".format(value)
}
