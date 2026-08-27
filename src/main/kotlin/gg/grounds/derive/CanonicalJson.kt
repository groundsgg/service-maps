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
        requireTypes(node, "request", REQUEST_TEXT_FIELDS, REQUEST_INTEGER_FIELDS)
        requireArray(node.path("catalogCandidates"), "catalogCandidates")
        node.path("catalogCandidates").forEach {
            requireFields(it, "catalog candidate", CATALOG_FIELDS)
            requireTypes(it, "catalog candidate", CATALOG_TEXT_FIELDS, CATALOG_INTEGER_FIELDS)
        }
        return mapper.readValue(bytes, DeriveRequest::class.java)
    }

    fun readResult(bytes: ByteArray): DeriveResult {
        val node = mapper.readTree(bytes)
        requireFields(node, "result", RESULT_FIELDS)
        requireTypes(node, "result", RESULT_TEXT_FIELDS, RESULT_INTEGER_FIELDS)
        when (node.path("kind").asText()) {
            "SUCCESS" -> {
                requireFields(node, "success result", SUCCESS_FIELDS)
                requireTypes(node, "success result", SUCCESS_TEXT_FIELDS, SUCCESS_INTEGER_FIELDS)
                validateScene(node.path("scene"))
            }
            "FAILURE" -> {
                requireFields(node, "failure result", FAILURE_FIELDS)
                requireTypes(
                    node,
                    "failure result",
                    FAILURE_TEXT_FIELDS,
                    booleanFields = FAILURE_BOOLEAN_FIELDS,
                )
                requireArray(node.path("problems"), "failure problems")
                node.path("problems").forEach {
                    requireFields(it, "failure problem", PROBLEM_FIELDS)
                    requireTypes(it, "failure problem", PROBLEM_TEXT_FIELDS)
                    requireOptionalText(it, "failure problem", OPTIONAL_PROBLEM_TEXT_FIELDS)
                }
            }
            else -> invalid("result kind must be SUCCESS or FAILURE")
        }
        return mapper.readValue(bytes, DeriveResult::class.java)
    }

    fun readManifest(bytes: ByteArray): DerivedManifest {
        val node = mapper.readTree(bytes)
        requireFields(node, "manifest", MANIFEST_FIELDS)
        requireTypes(node, "manifest", MANIFEST_TEXT_FIELDS, MANIFEST_INTEGER_FIELDS)
        requireArray(node.path("bundleDigestInputs"), "bundleDigestInputs")
        requireStringArray(node.path("bundleDigestInputs"), "bundleDigestInputs")
        validateScene(node.path("scene"))
        return mapper.readValue(bytes, DerivedManifest::class.java)
    }

    private fun validateScene(node: JsonNode) {
        requireFields(node, "scene", SCENE_FIELDS)
        requireTypes(node, "scene", booleanFields = SCENE_BOOLEAN_FIELDS)
        requireArray(node.path("requiredActions"), "scene requiredActions")
        requireStringArray(node.path("requiredActions"), "scene requiredActions")
        if (node.path("present").asBoolean()) {
            requireFields(node, "present scene", PRESENT_SCENE_FIELDS)
            requireTypes(node, "present scene", PRESENT_SCENE_TEXT_FIELDS)
            validateCatalog(node.path("assetCatalog"), "assetCatalog")
            validateCatalog(node.path("actionCatalog"), "actionCatalog")
        }
    }

    private fun validateCatalog(node: JsonNode, type: String) {
        requireFields(node, type, CATALOG_REFERENCE_FIELDS)
        requireTypes(node, type, CATALOG_REFERENCE_FIELDS)
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

    private fun requireStringArray(node: JsonNode, type: String) {
        node.forEach { if (!it.isTextual) invalid("$type entries must be strings") }
    }

    private fun requireTypes(
        node: JsonNode,
        type: String,
        textFields: Set<String> = emptySet(),
        integerFields: Set<String> = emptySet(),
        booleanFields: Set<String> = emptySet(),
    ) {
        textFields.forEach { field ->
            if (!node.path(field).isTextual) invalid("$type $field must be a string")
        }
        integerFields.forEach { field ->
            if (!node.path(field).isIntegralNumber) invalid("$type $field must be an integer")
        }
        booleanFields.forEach { field ->
            if (!node.path(field).isBoolean) invalid("$type $field must be a boolean")
        }
    }

    private fun requireOptionalText(node: JsonNode, type: String, fields: Set<String>) {
        fields.forEach { field ->
            if (node.has(field) && !node.path(field).isNull && !node.path(field).isTextual) {
                invalid("$type $field must be a string or null")
            }
        }
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

    private val REQUEST_TEXT_FIELDS =
        setOf(
            "mapId",
            "attempt",
            "sourceSha256",
            "sourceUrl",
            "bundleUrl",
            "manifestUrl",
            "resultUrl",
        )

    private val REQUEST_INTEGER_FIELDS = setOf("schemaVersion", "version")

    private val CATALOG_FIELDS =
        setOf("channel", "id", "version", "coordinate", "file", "uri", "sha256", "size")

    private val CATALOG_TEXT_FIELDS =
        setOf("channel", "id", "version", "coordinate", "file", "uri", "sha256")

    private val CATALOG_INTEGER_FIELDS = setOf("size")

    private val RESULT_FIELDS =
        setOf("kind", "schemaVersion", "mapId", "version", "attempt", "sourceSha256")

    private val RESULT_TEXT_FIELDS = setOf("kind", "mapId", "attempt", "sourceSha256")

    private val RESULT_INTEGER_FIELDS = setOf("schemaVersion", "version")

    private val SUCCESS_FIELDS =
        setOf("bundleSha256", "bundleSize", "manifestSha256", "manifestSize", "scene")

    private val SUCCESS_TEXT_FIELDS = setOf("bundleSha256", "manifestSha256")

    private val SUCCESS_INTEGER_FIELDS = setOf("bundleSize", "manifestSize")

    private val FAILURE_FIELDS = setOf("scope", "retryable", "problems")

    private val FAILURE_TEXT_FIELDS = setOf("scope")

    private val FAILURE_BOOLEAN_FIELDS = setOf("retryable")

    private val PROBLEM_FIELDS = setOf("scope", "code", "message")

    private val PROBLEM_TEXT_FIELDS = setOf("scope", "code", "message")

    private val OPTIONAL_PROBLEM_TEXT_FIELDS = setOf("path", "qualifiedIdentity")

    private val MANIFEST_FIELDS =
        setOf("schemaVersion", "sourceSha256", "bundleDigestInputs", "scene")

    private val MANIFEST_TEXT_FIELDS = setOf("sourceSha256")

    private val MANIFEST_INTEGER_FIELDS = setOf("schemaVersion")

    private val SCENE_FIELDS = setOf("present", "requiredActions")

    private val SCENE_BOOLEAN_FIELDS = setOf("present")

    private val PRESENT_SCENE_FIELDS =
        setOf("schemaVersion", "sha256", "assetCatalog", "actionCatalog")

    private val PRESENT_SCENE_TEXT_FIELDS = setOf("schemaVersion", "sha256")

    private val CATALOG_REFERENCE_FIELDS = setOf("id", "version")
}
