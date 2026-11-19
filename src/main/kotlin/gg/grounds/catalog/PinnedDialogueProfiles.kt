package gg.grounds.catalog

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.domain.DialogueProfilePin
import java.net.URI

/** Operator-owned immutable profile locations; a map supplies an identity, never a URL. */
class PinnedDialogueProfiles private constructor(pins: List<DialogueProfilePin>) {
    private val entries: List<DialogueProfilePin> = java.util.List.copyOf(pins)

    fun pins(): List<DialogueProfilePin> = entries

    companion object {
        private val mapper =
            ObjectMapper(
                    JsonFactory.builder()
                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .build()
                )
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

        fun parse(json: String, trustedBase: URI): PinnedDialogueProfiles {
            require(json.toByteArray(Charsets.UTF_8).size <= 65536) {
                "Dialogue profile configuration exceeds 64 KiB"
            }
            val root =
                try {
                    mapper.readTree(json)
                } catch (_: Exception) {
                    throw IllegalArgumentException("Invalid dialogue profile configuration")
                }
            require(root != null && root.isArray && root.size() <= 8) {
                "At most eight dialogue profiles are allowed"
            }
            if (root.isEmpty) return PinnedDialogueProfiles(emptyList())
            require(
                trustedBase.scheme == "https" &&
                    trustedBase.host != null &&
                    trustedBase.rawUserInfo == null &&
                    trustedBase.rawQuery == null &&
                    trustedBase.rawFragment == null &&
                    trustedBase == trustedBase.normalize()
            ) {
                "Dialogue profiles need a trusted HTTPS origin"
            }
            val pins =
                root.map { node ->
                    require(
                        node.isObject &&
                            node.fieldNames().asSequence().toSet() ==
                                setOf("id", "version", "sha256", "sizeBytes", "uri")
                    )
                    fun text(name: String): String {
                        val v = node.path(name)
                        require(
                            v.isTextual &&
                                v.textValue().isNotBlank() &&
                                v.textValue().length <= 2048
                        )
                        return v.textValue()
                    }
                    val version = text("version")
                    require(version.matches(Regex("[0-9A-Za-z][0-9A-Za-z.+_-]{0,127}")))
                    val size = node.path("sizeBytes")
                    require(
                        size.isIntegralNumber &&
                            size.canConvertToLong() &&
                            size.longValue() in 1..1048576
                    )
                    val sha = text("sha256")
                    val uri = URI(text("uri"))
                    val prefix = trustedBase.path.trimEnd('/') + "/dialogue/profiles/"
                    require(
                        uri.scheme == "https" &&
                            uri.host == trustedBase.host &&
                            uri.port == trustedBase.port &&
                            uri.rawUserInfo == null &&
                            uri.rawQuery == null &&
                            uri.rawFragment == null &&
                            uri == uri.normalize() &&
                            '%' !in uri.rawPath &&
                            '\\' !in uri.rawPath &&
                            uri.path.startsWith(prefix) &&
                            "//" !in uri.path &&
                            uri.path.endsWith("/$version/$sha/profile.json")
                    ) {
                        "Dialogue profile must use immutable trusted CDN path"
                    }
                    DialogueProfilePin(text("id"), version, sha, size.longValue(), uri)
                }
            require(pins.groupBy { it.id to it.version }.values.all { it.distinct().size == 1 }) {
                "Conflicting dialogue profile identity"
            }
            return PinnedDialogueProfiles(
                pins.distinct().sortedWith(compareBy({ it.id }, { it.version }))
            )
        }
    }
}
