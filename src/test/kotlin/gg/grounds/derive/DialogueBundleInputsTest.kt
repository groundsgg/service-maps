package gg.grounds.derive

import com.github.luben.zstd.ZstdOutputStream
import gg.grounds.scene.format.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import kotlin.test.*

class DialogueBundleInputsTest {
    @TempDir lateinit var root: Path
    private var serial = 0
    private fun file(name: String, bytes: ByteArray = "{}".toByteArray()): SpoolEntry =
        SpoolEntry(name, Files.write(root.resolve("spool-${serial++}"), bytes), false)
    private fun context() = listOf(file("grounds/dialogue.json"), file("context/host-catalog.json"), file("texts/en.json"))
    private fun read(entries: List<SpoolEntry>) = DialogueBundleInputs.read(entries, emptySet(), null)

    @Test fun pureMapIsAbsent() {
        assertIs<DialogueInputScan.Absent>(read(listOf(file("region/r.0.0.mca"))))
    }
    @Test fun textsAloneIsAbsent() {
        assertIs<DialogueInputScan.Absent>(read(listOf(file("texts/en.json"))))
    }
    @Test fun emptyDirectoryIsAbsent() {
        assertIs<DialogueInputScan.Absent>(read(listOf(SpoolEntry("dialogues", null, true))))
    }
    @Test fun orphanHostIsError() {
        assertEquals("DIALOGUE_STRUCTURE", assertThrows<DialogueContentException> {
            read(listOf(file("context/host-catalog.json")))
        }.problem.code)
    }
    @Test fun triggerWithoutDefinitionsIsError() {
        val base = SceneDocument(1, SceneId("test:map"), SceneMetadata("test", null, emptySet()),
            SceneCatalogReferences(CatalogReference(CatalogId("test:assets"), "1"), CatalogReference(CatalogId("test:actions"), "1")),
            emptyList(), emptyList())
        val npc = Npc(LocalId("guide"), null, Transform(Vec3(0.0,0.0,0.0), ZERO_ROTATION, Vec3(1.0,1.0,1.0)),
            body = AssetKey("test:guide"), label = null, labelOffset = Vec3(0.0,0.0,0.0), look = LookBehavior.Fixed,
            initialAnimation = null, interactionBounds = LocalBounds(Vec3(0.0,0.0,0.0), Vec3(1.0,1.0,1.0)), proximity = null,
            bindings = listOf(TriggerBinding(SceneTrigger.RIGHT_CLICK, emptyList(), 0, 0,
                listOf(ApplicationAction(ActionKey("test:dialogue"), emptyMap())))))
        val scene = SceneDocument(base.schemaVersion, base.id, base.metadata, base.catalogs, emptyList(), listOf(npc))
        assertThrows<DialogueContentException> { DialogueBundleInputs.read(emptyList(), setOf("test:dialogue"), scene) }
    }
    @Test fun rejectsCaseAndUnicodeAliases() {
        listOf(listOf(file("Dialogues/guide.json")),
            context() + listOf(file("dialogues/a.json"), file("dialogues/A.json")),
            context() + listOf(file("dialogues/caf\u00e9.json"), file("dialogues/cafe\u0301.json")))
            .forEach { assertThrows<DialogueContentException> { read(it) } }
    }
    @Test fun validInputsKeepRelativeDefinitionAndCanonicalLocaleKeys() {
        val inputs = assertIs<DialogueInputScan.Present>(read(context() + file("dialogues/chapter/guide.json")))
        assertEquals(setOf("chapter/guide.json"), inputs.dialogues.keys)
        assertEquals(setOf("en"), inputs.texts.keys)
        assertEquals(4, inputs.files.size)
        assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", inputs.files.first().sha256)
    }
    @Test fun checksEachBudgetBeforeReading() {
        val big = file("dialogues/big.json", ByteArray(1048577))
        assertEquals("DIALOGUE_INPUT_LIMIT", assertThrows<DialogueContentException> { read(context()+big) }.problem.code)
        val definitions = (1..1025).map { file("dialogues/$it.json") }
        assertEquals("DIALOGUE_INPUT_LIMIT", assertThrows<DialogueContentException> { read(context()+definitions) }.problem.code)
        val locales = (1..65).map { file("texts/x-$it.json") }
        assertEquals("DIALOGUE_INPUT_LIMIT", assertThrows<DialogueContentException> { read(context()+file("dialogues/a.json")+locales) }.problem.code)
    }
    @Test fun malformedStoryFilesAreNotSilentlyIgnored() {
        listOf("dialogues/readme.txt", "dialogues/a.JSON", "texts/en-us.json", "texts/nested/en.json").forEach { path ->
            assertThrows<DialogueContentException> { read(context()+file("dialogues/a.json")+file(path)) }
        }
    }
    @Test fun realPaxArchiveFeedsTheSameScanner() {
        val longName = "dialogues/" + "a".repeat(110) + ".json"
        val bytes = ByteArrayOutputStream().also { out ->
            ZstdOutputStream(out).use { zstd ->
                TarArchiveOutputStream(zstd).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    listOf(longName, "grounds/dialogue.json", "context/host-catalog.json", "texts/en.json").forEach { path ->
                        tar.putArchiveEntry(TarArchiveEntry(path).apply { size = 2 })
                        tar.write("{}".toByteArray()); tar.closeArchiveEntry()
                    }
                }
            }
        }.toByteArray()
        val spoolRoot = Files.createDirectory(root.resolve("archive"))
        val spool = SafeTarZstdReader().read(ByteArrayInputStream(bytes), spoolRoot)
        assertEquals(1, assertIs<DialogueInputScan.Present>(read(spool)).dialogues.size)
    }
}
