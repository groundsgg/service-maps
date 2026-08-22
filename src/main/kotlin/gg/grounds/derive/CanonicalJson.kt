package gg.grounds.derive

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

object CanonicalJson {
    private val mapper: ObjectMapper =
        ObjectMapper()
            .findAndRegisterModules()
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun write(value: Any): ByteArray {
        val sorted = sort(mapper.valueToTree(value))
        return mapper.writeValueAsBytes(sorted).plus('\n'.code.toByte())
    }

    fun readRequest(bytes: ByteArray): DeriveRequest =
        mapper.readValue(bytes, DeriveRequest::class.java)

    fun readResult(bytes: ByteArray): DeriveResult =
        mapper.readValue(bytes, DeriveResult::class.java)

    fun readManifest(bytes: ByteArray): DerivedManifest =
        mapper.readValue(bytes, DerivedManifest::class.java)

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
}
