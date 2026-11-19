package gg.grounds.derive

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.domain.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class DialogueContentException(val problem: DeriveProblem) : RuntimeException(problem.message)

class DialogueBindings(val profile: DialogueProfileReference, speakers: Map<String, String>) {
    val speakers: Map<String, String> = java.util.Map.copyOf(speakers)
}

data class DialogueStartAction(val dialogueArgument: String, val speakerArgument: String)

class DialogueHostProfile(
    val id: String,
    val version: String,
    hostCatalogBytes: ByteArray,
    val assetCatalog: CatalogReference,
    actionCatalogs: Set<CatalogReference>,
    startActions: Map<String, DialogueStartAction>,
    npcAssets: Map<String, Map<String, String>>,
) {
    private val host = hostCatalogBytes.clone()
    val hostCatalogBytes: ByteArray
        get() = host.clone()

    val actionCatalogs: Set<CatalogReference> = java.util.Set.copyOf(actionCatalogs)
    val startActions: Map<String, DialogueStartAction> = java.util.Map.copyOf(startActions)
    val npcAssets: Map<String, Map<String, String>> =
        java.util.Map.copyOf(npcAssets.mapValues { java.util.Map.copyOf(it.value) })
}

/** Grounds envelope only; the public Library is the sole decoder for HostCatalog and dialogues. */
object DialogueContentContracts {
    private const val LIMIT = 1048576
    private val mapper =
        ObjectMapper(
                JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(
                        StreamReadConstraints.builder()
                            .maxNestingDepth(32)
                            .maxStringLength(LIMIT)
                            .maxNumberLength(32)
                            .build()
                    )
                    .build()
            )
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun readBindings(bytes: ByteArray): DialogueBindings =
        decode(bytes, "grounds/dialogue.json") { root ->
            fields(root, setOf("schemaVersion", "profile", "speakers"))
            schema(root)
            val ref = root.path("profile")
            fields(ref, setOf("id", "version", "sha256"))
            val profile =
                DialogueProfileReference(text(ref, "id"), text(ref, "version"), text(ref, "sha256"))
            DialogueBindings(profile, stringMap(root.path("speakers")))
        }

    fun readProfile(bytes: ByteArray): DialogueHostProfile =
        decode(bytes, "host-profile") { root ->
            fields(
                root,
                setOf(
                    "schemaVersion",
                    "id",
                    "version",
                    "hostCatalogJson",
                    "assetCatalog",
                    "actionCatalogs",
                    "startActions",
                    "npcAssets",
                ),
            )
            schema(root)
            val host = text(root, "hostCatalogJson", LIMIT).toByteArray(Charsets.UTF_8)
            require(host.size <= LIMIT)
            val catalogs = root.path("actionCatalogs")
            require(catalogs.isArray && catalogs.size() in 1..16)
            val starts = root.path("startActions")
            objectSize(starts, 128)
            val actions =
                starts.properties().asSequence().associate { (key, value) ->
                    key(key)
                    fields(value, setOf("dialogueArgument", "speakerArgument"))
                    val a = text(value, "dialogueArgument")
                    val b = text(value, "speakerArgument")
                    require(a != b)
                    key to DialogueStartAction(a, b)
                }
            val assets = root.path("npcAssets")
            objectSize(assets, 4096)
            val npcs =
                assets.properties().asSequence().associate { (key, value) ->
                    key(key)
                    fields(value, setOf("gestures"))
                    key to stringMap(value.path("gestures"))
                }
            DialogueHostProfile(
                text(root, "id"),
                text(root, "version"),
                host,
                catalog(root.path("assetCatalog")),
                catalogs.map(::catalog).toSet(),
                actions,
                npcs,
            )
        }

    private fun <T> decode(bytes: ByteArray, file: String, read: (JsonNode) -> T): T {
        if (bytes.size > LIMIT)
            throw failure(file, "DIALOGUE_INPUT_LIMIT", "Dialogue context exceeds 1 MiB")
        return try {
            val json =
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            read(requireNotNull(mapper.readTree(json)))
        } catch (_: Exception) {
            throw failure(
                file,
                "DIALOGUE_CONTEXT_INVALID",
                "Invalid dialogue context structure or encoding",
            )
        }
    }

    private fun failure(file: String, code: String, message: String) =
        DialogueContentException(
            DeriveProblem(
                DeriveFailureScope.CONTENT,
                file,
                code,
                null,
                message,
                "dialogue",
                "ERROR",
                file,
                "/",
            )
        )

    private fun schema(node: JsonNode) {
        require(
            node.path("schemaVersion").isIntegralNumber &&
                node.path("schemaVersion").canConvertToInt() &&
                node.path("schemaVersion").intValue() == 1
        )
    }

    private fun fields(node: JsonNode, expected: Set<String>) {
        require(node.isObject && node.fieldNames().asSequence().toSet() == expected)
    }

    private fun text(node: JsonNode, name: String, max: Int = 128): String {
        val value = node.path(name)
        require(
            value.isTextual && value.textValue().isNotBlank() && value.textValue().length <= max
        )
        return value.textValue()
    }

    private fun key(value: String) {
        require(value.isNotBlank() && value.length <= 128)
    }

    private fun objectSize(node: JsonNode, max: Int) {
        require(node.isObject && node.size() <= max)
    }

    private fun stringMap(node: JsonNode): Map<String, String> {
        objectSize(node, 4096)
        return node.properties().asSequence().associate { (k, v) ->
            key(k)
            require(v.isTextual && v.textValue().isNotBlank() && v.textValue().length <= 128)
            k to v.textValue()
        }
    }

    private fun catalog(node: JsonNode): CatalogReference {
        fields(node, setOf("id", "version"))
        return CatalogReference(text(node, "id"), text(node, "version"))
    }
}
