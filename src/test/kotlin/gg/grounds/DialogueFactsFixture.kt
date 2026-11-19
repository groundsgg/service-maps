package gg.grounds

import gg.grounds.domain.*
import java.net.URI

object DialogueFactsFixture {
    val source = "a".repeat(64)
    val image = "worker@sha256:${"b".repeat(64)}"
    val profile =
        DialogueProfilePin(
            "grounds:dialogue",
            "1",
            "c".repeat(64),
            12,
            URI("https://cdn.test/dialogue/profiles/1/profile.json"),
        )
    val contract = DeriveExecutionContract(2, image, listOf(profile))
    val scene =
        SceneProjection(
            SceneStatus.VALID,
            "1",
            "d".repeat(64),
            CatalogReference("grounds:assets", "1"),
            CatalogReference("grounds:actions", "3"),
            emptyList(),
            emptyList(),
        )
    val story =
        DialogueProjection(
            DialogueStatus.VALID,
            DialogueProof(
                source,
                image,
                contract.fingerprint(),
                sceneSha256 = scene.sha256,
                assetCatalog = scene.assetCatalog,
                actionCatalog = scene.actionCatalog,
                profile = profile,
                hostCatalogSha256 = "e".repeat(64),
                contentPin = "f".repeat(64),
                files = listOf(DialogueFilePin("dialogues/guide.json", "0".repeat(64), 1)),
                dialogueIds = listOf("guide"),
                bindingsSchemaVersion = 1,
            ),
        )
    val none =
        DialogueProjection(
            DialogueStatus.NONE,
            DialogueProof(source, image, contract.fingerprint()),
        )

    fun facts(story: Boolean) =
        DerivedFacts(
            "9".repeat(64),
            "8".repeat(64),
            2,
            null,
            null,
            scene,
            if (story) this.story else none,
        )
}
