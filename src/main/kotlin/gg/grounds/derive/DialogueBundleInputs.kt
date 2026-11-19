package gg.grounds.derive

import gg.grounds.domain.DialogueFilePin
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveProblem
import gg.grounds.scene.format.ApplicationAction
import gg.grounds.scene.format.Npc
import gg.grounds.scene.format.SceneDocument
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

sealed interface DialogueInputScan {
    data object Absent : DialogueInputScan
    class Present(
        val bindingsBytes: ByteArray, val hostBytes: ByteArray,
        val dialogues: Map<String, ByteArray>, val texts: Map<String, ByteArray>,
        val files: List<DialogueFilePin>,
    ) : DialogueInputScan
}

/** Reads only the already-isolated archive spool, with budgets checked before allocation. */
object DialogueBundleInputs {
    private const val MIB = 1048576
    private val reserved = setOf("dialogues", "texts", "context", "grounds")

    fun read(spool: List<SpoolEntry>, knownStartActions: Set<String>, scene: SceneDocument?): DialogueInputScan {
        val definitions = spool.filter { it.path.startsWith("dialogues/") && !it.directory }
        val binding = spool.singleOrNull { it.path == "grounds/dialogue.json" }
        val host = spool.singleOrNull { it.path == "context/host-catalog.json" }
        val trigger = scene?.elements?.filterIsInstance<Npc>()?.flatMap { it.bindings }
            ?.flatMap { it.actions }?.filterIsInstance<ApplicationAction>()?.any { it.key.value in knownStartActions } == true
        // Reject aliases of reserved roots even when they would otherwise evade story discovery.
        spool.forEach { entry ->
            val first = entry.path.substringBefore('/')
            if (first.lowercase(Locale.ROOT) in reserved && first !in reserved)
                fail(entry.path, "DIALOGUE_PATH", "Reserved dialogue path has noncanonical case")
            if (entry.path == "dialogues" && !entry.directory)
                fail(entry.path, "DIALOGUE_STRUCTURE", "dialogues must be a directory")
        }
        if (definitions.isEmpty() && binding == null && host == null && !trigger) return DialogueInputScan.Absent
        if (definitions.isEmpty() || binding == null || host == null)
            fail("dialogues", "DIALOGUE_STRUCTURE", "Story requires definitions, Grounds bindings and host context")
        val locales = spool.filter { it.path.startsWith("texts/") && !it.directory }
        if (locales.isEmpty()) fail("texts", "DIALOGUE_STRUCTURE", "Story requires locale files")
        if (definitions.size > 1024 || locales.size > 64)
            fail("dialogues", "DIALOGUE_INPUT_LIMIT", "Too many dialogue or locale files")
        val relevant = (definitions + locales + binding + host).sortedBy { it.path }
        val seen = HashSet<String>()
        var libraryBytes = 0L
        relevant.forEach { entry ->
            val path = entry.path
            if (path.toByteArray(Charsets.UTF_8).size > 1024 || path.startsWith('/') || '\\' in path ||
                path.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
                !Normalizer.isNormalized(path, Normalizer.Form.NFC) || !seen.add(path.lowercase(Locale.ROOT)))
                fail(path, "DIALOGUE_PATH", "Ambiguous or nonportable dialogue path")
            if (entry.directory || entry.file == null || !Files.isRegularFile(entry.file, NOFOLLOW_LINKS))
                fail(path, "DIALOGUE_STRUCTURE", "Dialogue input must be a regular file")
            if ((path.startsWith("dialogues/") || path.startsWith("texts/")) && !path.endsWith(".json"))
                fail(path, "DIALOGUE_PATH", "Dialogue inputs must use .json files")
            val max = if (path.startsWith("texts/")) 4 * MIB else MIB
            val size = Files.size(entry.file)
            if (size > max) fail(path, "DIALOGUE_INPUT_LIMIT", "Dialogue input exceeds file budget")
            if (entry != binding) libraryBytes += size
        }
        if (libraryBytes > 16L * MIB) fail("dialogues", "DIALOGUE_INPUT_LIMIT", "Dialogue catalogue exceeds 16 MiB")
        val texts = linkedMapOf<String, ByteArray>()
        locales.forEach { entry ->
            val name = entry.path.removePrefix("texts/")
            if ('/' in name) fail(entry.path, "DIALOGUE_PATH", "Locale files must be directly under texts")
            val tag = name.removeSuffix(".json")
            val valid = try {
                val locale = Locale.Builder().setLanguageTag(tag).build()
                locale.language.isNotEmpty() && locale.toLanguageTag() == tag
            } catch (_: IllegalArgumentException) { false }
            if (!valid || texts.containsKey(tag)) fail(entry.path, "DIALOGUE_PATH", "Expected unique canonical language tag")
            texts[tag] = byteArrayOf() // Reserve the key before reading any contents.
        }
        val pins = mutableListOf<DialogueFilePin>()
        val bytes = relevant.associate { entry ->
            val max = if (entry.path.startsWith("texts/")) 4 * MIB else MIB
            val data = Files.newInputStream(entry.file!!, NOFOLLOW_LINKS).use { it.readNBytes(max + 1) }
            if (data.size > max) fail(entry.path, "DIALOGUE_INPUT_LIMIT", "Dialogue input grew beyond file budget")
            pins += DialogueFilePin(entry.path, MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }, data.size.toLong())
            entry.path to data
        }
        if (bytes.filterKeys { it != binding.path }.values.sumOf { it.size.toLong() } > 16L * MIB)
            fail("dialogues", "DIALOGUE_INPUT_LIMIT", "Dialogue catalogue exceeds 16 MiB")
        return DialogueInputScan.Present(bytes.getValue(binding.path), bytes.getValue(host.path),
            definitions.sortedBy { it.path }.associate { it.path.removePrefix("dialogues/") to bytes.getValue(it.path) },
            locales.sortedBy { it.path }.associate { it.path.removePrefix("texts/").removeSuffix(".json") to bytes.getValue(it.path) },
            java.util.List.copyOf(pins))
    }

    private fun fail(file: String, code: String, message: String): Nothing = throw DialogueContentException(
        DeriveProblem(DeriveFailureScope.CONTENT, file, code, null, message, "dialogue", "ERROR", file, "/"))
}
