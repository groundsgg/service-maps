package gg.grounds.catalog

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.derive.AssetCatalogCandidate
import java.net.URI

internal data class CatalogSelection(
    val candidates: List<AssetCatalogCandidate>,
    val conflicts: List<String>,
)

/** Additional immutable metadata trusted by the operator, never selected by a request URL. */
internal class PinnedCatalogs private constructor(val entries: List<AssetCatalogCandidate>) {
    fun merge(channels: List<AssetCatalogCandidate>): CatalogSelection {
        val conflicts =
            entries
                .filter { pin ->
                    channels.any {
                        it.id == pin.id &&
                            it.version == pin.version &&
                            (it.sha256 != pin.sha256 || it.size != pin.size)
                    }
                }
                .map { it.version }
                .distinct()
        val accepted = entries.filter { it.version !in conflicts }
        return CatalogSelection(
            (channels + accepted).distinctBy { listOf(it.id, it.version, it.sha256, it.size) },
            conflicts,
        )
    }

    companion object {
        private const val MAX_PINNED_CATALOGS = 8

        private val fields =
            setOf("channel", "id", "version", "coordinate", "file", "uri", "sha256", "size")
        private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

        fun empty() = PinnedCatalogs(emptyList())

        fun parse(json: String, base: URI): PinnedCatalogs {
            require(json.toByteArray(Charsets.UTF_8).size <= 65536) {
                "Catalog pin configuration exceeds 64 KiB"
            }
            val root =
                try {
                    mapper.readTree(json)
                } catch (_: Exception) {
                    throw IllegalArgumentException("Invalid catalog pin JSON")
                }
            require(root != null && root.isArray && root.size() <= MAX_PINNED_CATALOGS) {
                "At most $MAX_PINNED_CATALOGS catalog pins are allowed"
            }
            val pins =
                root.map { node ->
                    require(node.isObject && node.fieldNames().asSequence().toSet() == fields) {
                        "Invalid catalog pin fields"
                    }
                    fun text(name: String): String {
                        require(node.path(name).isTextual) { "Catalog pin fields must be strings" }
                        return node.path(name).textValue()
                    }
                    val version = text("version")
                    val sha = text("sha256")
                    val file = text("file")
                    val size = node.path("size")
                    require(text("channel") == "pinned" && text("id") == "grounds:resourcepacks") {
                        "Invalid catalog pin owner"
                    }
                    require(version.matches(Regex("[0-9A-Za-z][0-9A-Za-z.+_-]{0,127}"))) {
                        "Invalid pinned catalog version"
                    }
                    require(text("coordinate") == "gg.grounds:resourcepacks-catalog:$version") {
                        "Invalid catalog pin coordinate"
                    }
                    require(sha.matches(Regex("[0-9a-f]{64}"))) { "Invalid catalog pin SHA-256" }
                    require(
                        file.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,120}\\.jar")) &&
                            ".." !in file
                    ) {
                        "Invalid catalog pin filename"
                    }
                    require(
                        size.isIntegralNumber &&
                            size.canConvertToLong() &&
                            size.longValue() in 1..33554432L
                    ) {
                        "Invalid catalog pin size"
                    }
                    val uri =
                        try {
                            URI(text("uri"))
                        } catch (_: Exception) {
                            throw IllegalArgumentException("Invalid catalog pin URI")
                        }
                    require(
                        base.scheme == "https" &&
                            base.host != null &&
                            base.port == -1 &&
                            uri.scheme == "https" &&
                            uri.host == base.host &&
                            uri.port == -1 &&
                            uri.rawUserInfo == null &&
                            uri.rawQuery == null &&
                            uri.rawFragment == null &&
                            '%' !in uri.rawPath &&
                            '\\' !in uri.rawPath &&
                            uri == uri.normalize()
                    ) {
                        "Catalog pins must use the trusted HTTPS CDN origin"
                    }
                    val prefix = base.path.trimEnd('/') + "/resourcepacks/catalog-pins/"
                    require(
                        uri.path.startsWith(prefix) &&
                            !uri.path.contains("//") &&
                            uri.path.split('/').none { it == "." || it == ".." } &&
                            uri.path.endsWith("/$version/$sha/$file")
                    ) {
                        "Catalog pins must use an immutable catalog-pin path"
                    }
                    AssetCatalogCandidate(
                        "pinned",
                        "grounds:resourcepacks",
                        version,
                        text("coordinate"),
                        file,
                        uri,
                        sha,
                        size.longValue(),
                    )
                }
            require(
                pins
                    .groupBy { it.id to it.version }
                    .values
                    .all { group -> group.map { it.sha256 to it.size }.distinct().size == 1 }
            ) {
                "Configured catalog pins have conflicting identities"
            }
            return PinnedCatalogs(pins.distinctBy { listOf(it.id, it.version, it.sha256, it.size) })
        }
    }
}
