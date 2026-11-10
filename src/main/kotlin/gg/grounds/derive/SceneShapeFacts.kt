package gg.grounds.derive

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import gg.grounds.domain.SceneShapeRequirements
import gg.grounds.scene.format.*
import java.security.MessageDigest

/** Fingerprints geometry/type fields; text, bindings and world positions remain editable. */
object SceneShapeFacts {
    fun extract(
        document: SceneDocument,
        requirements: SceneShapeRequirements?,
    ): Map<String, String>? {
        if (requirements == null) return null
        val bytes = (SceneJson.encode(document) as SceneEncodeResult.Success).bytes
        val nodes =
            ObjectMapper().readTree(bytes).path("elements").associateBy {
                it.path("id").textValue()
            }
        return buildMap {
            requirements.elements.sorted().forEach { id ->
                nodes[id]?.let { element ->
                    val shape = ObjectMapper().createObjectNode()
                    listOf("type", "body", "asset", "look", "collision", "interactionBounds")
                        .forEach { key ->
                            if (element.has(key))
                                shape.set<com.fasterxml.jackson.databind.JsonNode>(
                                    key,
                                    element.get(key),
                                )
                        }
                    val transform =
                        (element
                            .path("transform")
                            .deepCopy<com.fasterxml.jackson.databind.JsonNode>() as ObjectNode)
                    transform.remove("position")
                    shape.set<com.fasterxml.jackson.databind.JsonNode>("transform", transform)
                    val digest =
                        MessageDigest.getInstance("SHA-256")
                            .digest(CanonicalJson.write(shape))
                            .joinToString("") { "%02x".format(it) }
                    put("element:$id", digest)
                }
            }
            requirements.prefixes.sorted().forEach { prefix ->
                put(
                    "count:$prefix",
                    document.elements.count { it.id.value.startsWith(prefix) }.toString(),
                )
            }
        }
    }
}
