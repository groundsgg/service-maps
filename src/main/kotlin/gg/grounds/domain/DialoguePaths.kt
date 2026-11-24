package gg.grounds.domain

import java.text.Normalizer
import java.util.Locale

/** Portable authored Story file names, shared by discovery and attestation acceptance. */
object DialoguePaths {
    private val devices = Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]|CONIN\\$|CONOUT\\$)")

    fun isStoryFile(path: String): Boolean =
        path == "grounds/dialogue.json" ||
            path == "context/host-catalog.json" ||
            (path.startsWith("dialogues/") && path.endsWith(".json")) ||
            (path.startsWith("texts/") &&
                path.endsWith(".json") &&
                '/' !in path.removePrefix("texts/"))

    fun validInventory(paths: List<String>): Boolean {
        val seen = HashSet<String>()
        val ancestors = HashMap<String, String>()
        for (path in paths) {
            if (
                path.toByteArray(Charsets.UTF_8).size > 1024 ||
                    !Normalizer.isNormalized(path, Normalizer.Form.NFC)
            )
                return false
            val parts = path.split('/')
            if (
                parts.any { part ->
                    part.isEmpty() ||
                        part.endsWith('.') ||
                        part.endsWith(' ') ||
                        part.toByteArray(Charsets.UTF_8).size > 255 ||
                        part.any { it.code < 32 || it in "<>:\\|?*\"" } ||
                        devices.matches(part.substringBefore('.').trimEnd(' '))
                }
            )
                return false
            if (!seen.add(path.lowercase(Locale.ROOT))) return false
            for (i in 1 until parts.size) {
                val ancestor = parts.take(i).joinToString("/")
                val key = ancestor.lowercase(Locale.ROOT)
                val previous = ancestors.putIfAbsent(key, ancestor)
                if (previous != null && previous != ancestor) return false
            }
        }
        return seen.none { it in ancestors }
    }
}
