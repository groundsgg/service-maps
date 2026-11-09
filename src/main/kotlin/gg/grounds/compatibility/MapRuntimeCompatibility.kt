package gg.grounds.compatibility

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.domain.CatalogReference
import gg.grounds.domain.EnvironmentName
import gg.grounds.domain.MapAddress
import gg.grounds.domain.SceneProjection
import gg.grounds.domain.SceneStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

/** Operator-owned, image-bound metadata. A map author cannot declare runtime capabilities. */
@ApplicationScoped
class MapRuntimeCompatibility
@Inject
constructor(
    @ConfigProperty(name = "grounds.maps.runtime.environments", defaultValue = "disabled")
    enabledEnvironments: String,
    @ConfigProperty(name = "grounds.maps.runtime.targets-json", defaultValue = "[]")
    targetsJson: String,
) {
    private val enabled =
        enabledEnvironments
            .split(',')
            .map(String::trim)
            .filter { it.isNotEmpty() && it != "disabled" }
            .toSet()
    private val targets = parse(targetsJson)

    init {
        require(enabled.all(EnvironmentName::isValid)) { "Invalid runtime-guard environment" }
        require(targets.map { it.environment to it.address }.distinct().size == targets.size) {
            "Duplicate runtime target"
        }
        require(
            targets.filter { it.normalizeUploads }.map { it.assetCatalog }.distinct().size <= 1
        ) {
            "Ambiguous upload normalization target"
        }
    }

    fun problem(environment: String, address: String, scene: SceneProjection): String? {
        if (environment !in enabled) return null
        val target = targets.singleOrNull { it.environment == environment && it.address == address }
        if (scene.status == SceneStatus.NONE && target?.requireScene != true) return null
        if (target == null)
            return "Live activation blocked: no deployed runtime contract for $address in $environment."
        if (scene.status != SceneStatus.VALID)
            return "Live activation blocked: this runtime requires a valid scene."
        if (scene.schemaVersion !in target.schemaVersions)
            return "Live activation blocked: scene schema ${scene.schemaVersion} is unsupported by the deployed runtime."
        if (scene.assetCatalog != target.assetCatalog)
            return "Live activation blocked: map needs asset catalog ${scene.assetCatalog?.id}@${scene.assetCatalog?.version}; deployed runtime supports ${target.assetCatalog.id}@${target.assetCatalog.version}."
        if (scene.actionCatalog !in target.actionCatalogs)
            return "Live activation blocked: action catalog ${scene.actionCatalog?.id}@${scene.actionCatalog?.version} is unsupported by the deployed runtime."
        val missing = scene.requiredActions.filterNot { it in target.supportedActions }.sorted()
        if (missing.isNotEmpty())
            return "Live activation blocked: deployed runtime does not support actions ${missing.joinToString()}."
        if (scene.sha256 !in target.legacySceneHashes) {
            target.requiredElements.forEach { (id, sha) ->
                if (scene.runtimeFacts?.get("element:$id") != sha)
                    return "Live activation blocked: required scene element $id is missing or incompatible with the deployed runtime."
            }
            target.elementCounts.forEach { (prefix, count) ->
                if (scene.runtimeFacts?.get("count:$prefix") != count.toString())
                    return "Live activation blocked: deployed runtime requires $count scene elements with prefix $prefix."
            }
        }
        return null
    }

    fun shapeRequirements(address: String): gg.grounds.domain.SceneShapeRequirements? {
        val selected = targets.filter { it.address == address }
        val elements = selected.flatMap { it.requiredElements.keys }.toSet()
        val prefixes = selected.flatMap { it.elementCounts.keys }.toSet()
        return if (elements.isEmpty() && prefixes.isEmpty()) null
        else gg.grounds.domain.SceneShapeRequirements(elements, prefixes)
    }

    fun normalizationCatalog(address: String): CatalogReference? =
        targets
            .filter { (it.address == address) && it.normalizeUploads }
            .map { it.assetCatalog }
            .distinct()
            .singleOrNull()

    private data class Target(
        val environment: String,
        val address: String,
        val assetCatalog: CatalogReference,
        val schemaVersions: Set<String>,
        val actionCatalogs: Set<CatalogReference>,
        val supportedActions: Set<String>,
        val requireScene: Boolean,
        val normalizeUploads: Boolean,
        val requiredElements: Map<String, String>,
        val elementCounts: Map<String, Int>,
        val legacySceneHashes: Set<String>,
    )

    private fun parse(json: String): List<Target> {
        require(json.toByteArray().size <= 64 * 1024) { "Runtime contracts exceed 64 KiB" }
        try {
            val mapper =
                ObjectMapper(
                        JsonFactory.builder()
                            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .build()
                    )
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            val root = mapper.readTree(json)
            require(root.isArray && root.size() <= 32) { "Runtime targets must be a bounded array" }
            return root.map { node ->
                require(
                    node.isObject && node.fieldNames().asSequence().all { it in TARGET_FIELDS }
                ) {
                    "Unknown runtime target field"
                }
                val environment = text(node, "environment")
                val address = text(node, "address")
                require(EnvironmentName.isValid(environment) && MapAddress.parse(address) != null) {
                    "Invalid runtime target identity"
                }
                require(text(node, "image").matches(Regex("[^\\s]+@sha256:[0-9a-f]{64}"))) {
                    "Runtime image must be immutable"
                }
                val schemas = strings(node, "schemaVersions")
                val actions = node.path("actionCatalogs")
                require(schemas.isNotEmpty() && actions.isArray && actions.size() in 1..16) {
                    "Runtime schemas and action catalogs are required"
                }
                Target(
                    environment,
                    address,
                    catalog(node.path("assetCatalog")),
                    schemas,
                    actions.map(::catalog).toSet(),
                    strings(node, "supportedActions"),
                    flag(node, "requireScene"),
                    flag(node, "normalizeUploads"),
                    hashes(node, "requiredElements"),
                    counts(node),
                    if (node.has("legacySceneHashes"))
                        strings(node, "legacySceneHashes").also {
                            require(it.all { sha -> sha.matches(Regex("[0-9a-f]{64}")) })
                        }
                    else emptySet(),
                )
            }
        } catch (failure: Exception) {
            throw IllegalArgumentException(
                "Invalid runtime target contracts: ${failure.message}",
                failure,
            )
        }
    }

    private fun hashes(node: JsonNode, field: String): Map<String, String> {
        if (!node.has(field)) return emptyMap()
        val entries = node.path(field)
        require(entries.isObject && entries.size() <= 128)
        return entries.fields().asSequence().associate { (key, value) ->
            require(
                key.matches(Regex("[a-zA-Z0-9_-]{1,128}")) &&
                    value.isTextual &&
                    value.textValue().matches(Regex("[0-9a-f]{64}"))
            )
            key to value.textValue()
        }
    }

    private fun counts(node: JsonNode): Map<String, Int> {
        if (!node.has("elementCounts")) return emptyMap()
        val entries = node.path("elementCounts")
        require(entries.isObject && entries.size() <= 32)
        return entries.fields().asSequence().associate { (key, value) ->
            require(
                key.matches(Regex("[a-zA-Z0-9_-]{1,128}")) &&
                    value.isIntegralNumber &&
                    value.canConvertToInt() &&
                    value.intValue() in 0..100000
            )
            key to value.intValue()
        }
    }

    private fun text(node: JsonNode, field: String): String {
        val value = node.path(field)
        require(
            value.isTextual && value.textValue().isNotBlank() && value.textValue().length <= 512
        ) {
            "Invalid runtime $field"
        }
        return value.textValue()
    }

    private fun strings(node: JsonNode, field: String): Set<String> {
        val values = node.path(field)
        require(values.isArray && values.size() <= 256) { "Invalid runtime $field" }
        return values
            .map {
                require(it.isTextual && it.textValue().isNotBlank() && it.textValue().length <= 512)
                it.textValue()
            }
            .toSet()
    }

    private fun flag(node: JsonNode, field: String): Boolean {
        if (!node.has(field)) return false
        require(node.path(field).isBoolean) { "Invalid runtime $field" }
        return node.path(field).booleanValue()
    }

    private fun catalog(node: JsonNode): CatalogReference {
        require(node.isObject && node.fieldNames().asSequence().toSet() == setOf("id", "version")) {
            "Invalid runtime catalog"
        }
        return CatalogReference(text(node, "id"), text(node, "version"))
    }

    private companion object {
        val TARGET_FIELDS =
            setOf(
                "environment",
                "address",
                "image",
                "schemaVersions",
                "assetCatalog",
                "actionCatalogs",
                "supportedActions",
                "requireScene",
                "normalizeUploads",
                "requiredElements",
                "elementCounts",
                "legacySceneHashes",
            )
    }
}
