package gg.grounds.derive

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.KotlinModule

object CanonicalJson {
    private val mapper: ObjectMapper =
        ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun write(value: Any): ByteArray {
        val sorted = sort(mapper.valueToTree(value))
        return mapper.writeValueAsBytes(sorted).plus('\n'.code.toByte())
    }

    fun readRequest(bytes: ByteArray): DeriveRequest {
        val node = mapper.readTree(bytes)
        requireFields(node, "request", REQUEST_FIELDS)
        requireArray(node.path("catalogCandidates"), "catalogCandidates")
        node.path("catalogCandidates").forEach {
            requireFields(it, "catalog candidate", CATALOG_FIELDS)
        }
        return mapper.readValue(bytes, DeriveRequest::class.java)
    }

    fun readResult(bytes: ByteArray): DeriveResult {
        val node = mapper.readTree(bytes)
        requireFields(node, "result", RESULT_FIELDS)
        when (node.path("kind").asText()) {
            "SUCCESS" -> {
                requireFields(node, "success result", SUCCESS_FIELDS)
                validateScene(node.path("scene"))
            }
            "FAILURE" -> {
                requireFields(node, "failure result", FAILURE_FIELDS)
                requireArray(node.path("problems"), "failure problems")
                node.path("problems").forEach {
                    requireFields(it, "failure problem", PROBLEM_FIELDS)
                }
            }
            else -> invalid("result kind must be SUCCESS or FAILURE")
        }
        return mapper.readValue(bytes, DeriveResult::class.java)
    }

    fun readManifest(bytes: ByteArray): DerivedManifest {
        val node = mapper.readTree(bytes)
        requireFields(node, "manifest", MANIFEST_FIELDS)
        requireArray(node.path("bundleDigestInputs"), "bundleDigestInputs")
        validateScene(node.path("scene"))
        return mapper.readValue(bytes, DerivedManifest::class.java)
    }

    private fun validateScene(node: JsonNode) {
        requireFields(node, "scene", SCENE_FIELDS)
        requireArray(node.path("requiredActions"), "scene requiredActions")
        if (node.path("present").asBoolean()) {
            requireFields(node, "present scene", PRESENT_SCENE_FIELDS)
        }
    }

    private fun requireFields(node: JsonNode, type: String, fields: Set<String>) {
        if (!node.isObject) invalid("$type must be an object")
        fields.forEach { field ->
            if (!node.hasNonNull(field)) invalid("$type requires non-null $field")
        }
    }

    private fun requireArray(node: JsonNode, type: String) {
        if (!node.isArray) invalid("$type must be an array")
    }

    private fun invalid(message: String): Nothing = throw JsonMappingException(null, message)

    private fun sort(node: JsonNode): JsonNode =
        when {
            node.isObject -> {
                val result = mapper.nodeFactory.objectNode()
                (node as ObjectNode).fieldNames().asSequence().toList().sorted().forEach { name ->
                    result.set<JsonNode>(name, sort(node.get(name)))
                }
                result
            }
            node.isArray -> {
                val result: ArrayNode = mapper.nodeFactory.arrayNode()
                node.forEach { result.add(sort(it)) }
                result
            }
            else -> node
        }

    private val REQUEST_FIELDS =
        setOf(
            "schemaVersion",
            "mapId",
            "version",
            "attempt",
            "sourceSha256",
            "sourceUrl",
            "bundleUrl",
            "manifestUrl",
            "resultUrl",
            "catalogCandidates",
        )

    private val CATALOG_FIELDS =
        setOf("channel", "id", "version", "coordinate", "file", "uri", "sha256", "size")

    private val RESULT_FIELDS =
        setOf("kind", "schemaVersion", "mapId", "version", "attempt", "sourceSha256")

    private val SUCCESS_FIELDS =
        setOf("bundleSha256", "bundleSize", "manifestSha256", "manifestSize", "scene")

    private val FAILURE_FIELDS = setOf("scope", "retryable", "problems")

    private val PROBLEM_FIELDS = setOf("scope", "code", "message")

    private val MANIFEST_FIELDS =
        setOf("schemaVersion", "sourceSha256", "bundleDigestInputs", "scene")

    private val SCENE_FIELDS = setOf("present", "requiredActions")

    private val PRESENT_SCENE_FIELDS =
        setOf("schemaVersion", "sha256", "assetCatalog", "actionCatalog")
}
