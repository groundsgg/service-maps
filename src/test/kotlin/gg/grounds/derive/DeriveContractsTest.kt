package gg.grounds.derive

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import gg.grounds.domain.DialogueProof
import gg.grounds.domain.DialogueFilePin
import gg.grounds.domain.DialogueRequirement
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import gg.grounds.domain.CatalogReference
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveProblem
import java.net.URI
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeriveContractsTest {
    @Test
    fun `normalization target round trips without changing old request compatibility`() {
        val original =
            DeriveRequest(
                mapId = UUID.randomUUID(),
                version = 1,
                attempt = UUID.randomUUID(),
                sourceSha256 = digest(1),
                sourceUrl = URI("https://example.test/source"),
                bundleUrl = URI("https://example.test/bundle"),
                manifestUrl = URI("https://example.test/manifest"),
                resultUrl = URI("https://example.test/result"),
                catalogCandidates = emptyList(),
            )
        assertEquals(
            null,
            CanonicalJson.readRequest(CanonicalJson.write(original)).normalizationCatalog,
        )
        val target = CatalogReference("grounds:assets", "new-runtime")
        assertEquals(
            target,
            CanonicalJson.readRequest(
                    CanonicalJson.write(original.copy(normalizationCatalog = target))
                )
                .normalizationCatalog,
        )
        assertThrows(JsonMappingException::class.java) {
            CanonicalJson.readRequest(
                CanonicalJson.write(original)
                    .decodeToString()
                    .replace(
                        "\"version\":1",
                        "\"normalizationCatalog\":{\"id\":3,\"version\":true},\"version\":1",
                    )
                    .toByteArray()
            )
        }
    }

    @Test
    fun `canonical JSON is stable sorted and newline terminated`() {
        val manifest =
            DerivedManifest(
                sourceSha256 = digest(1),
                bundleDigestInputs = listOf("world/level.dat", "scene.json"),
                scene = presentScene(requiredActions = listOf("z.action", "a.action", "z.action")),
            )

        val first = CanonicalJson.write(manifest)
        val second = CanonicalJson.write(manifest)

        assertTrue(first.contentEquals(second))
        assertEquals(
            "{\"bundleDigestInputs\":[\"scene.json\",\"world/level.dat\"],\"scene\":{\"actionCatalog\":{\"id\":\"grounds\",\"version\":\"1\"},\"assetCatalog\":{\"id\":\"assets\",\"version\":\"2026.08\"},\"present\":true,\"requiredActions\":[\"a.action\",\"z.action\"],\"schemaVersion\":\"1\",\"sha256\":\"${digest(2)}\"},\"schemaVersion\":1,\"sourceSha256\":\"${digest(1)}\"}\n",
            first.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `strict result parser rejects unknown fields`() {
        val bytes = javaClass.getResourceAsStream("/derive/result-unknown-field.json")!!.readBytes()

        assertThrows(UnrecognizedPropertyException::class.java) { CanonicalJson.readResult(bytes) }
    }

    @Test
    fun `strict result parser accepts valid result`() {
        val bytes = javaClass.getResourceAsStream("/derive/result-valid.json")!!.readBytes()

        val result = CanonicalJson.readResult(bytes)

        assertEquals(DeriveResultKind.SUCCESS, (result as DeriveSuccess).kind)
        assertEquals(listOf("a.action", "z.action"), result.scene.requiredActions)
    }

    @Test
    fun `strict parser rejects omitted and null primitive wire fields`() {
        val success = resource("/derive/result-valid.json")
        val failure =
            resource("/derive/result-unknown-field.json").replace(",\n  \"unexpected\": true\n", "")

        listOf(
                success.replace("  \"schemaVersion\": 1,\n", ""),
                success.replace("\"bundleSize\": 42", "\"bundleSize\": null"),
                success.replace("\"manifestSize\": 9", "\"manifestSize\": null"),
                success.replace("\"present\": true", "\"present\": null"),
                failure.replace("  \"retryable\": true,\n", ""),
                failure.replace("\"retryable\": true", "\"retryable\": null"),
            )
            .forEach { payload ->
                assertThrows(JsonMappingException::class.java) {
                    CanonicalJson.readResult(payload.toByteArray())
                }
            }
    }

    @Test
    fun `strict parser rejects trailing JSON input`() {
        assertThrows(JsonMappingException::class.java) {
            CanonicalJson.readResult((resource("/derive/result-valid.json") + "{}\n").toByteArray())
        }
    }

    @Test
    fun `strict parser rejects scalar coercions`() {
        val success = resource("/derive/result-valid.json")
        val failure =
            resource("/derive/result-unknown-field.json").replace(",\n  \"unexpected\": true\n", "")

        listOf(
                success.replace("\"schemaVersion\": 1", "\"schemaVersion\": \"1\""),
                success.replace("\"bundleSize\": 42", "\"bundleSize\": \"42\""),
                failure.replace("\"retryable\": true", "\"retryable\": \"true\""),
            )
            .forEach { payload ->
                assertThrows(JsonMappingException::class.java) {
                    CanonicalJson.readResult(payload.toByteArray())
                }
            }
    }

    @Test
    fun `canonical JSON omits null optional scene facts`() {
        val bytes =
            CanonicalJson.write(
                DerivedManifest(
                    sourceSha256 = digest(1),
                    bundleDigestInputs = emptyList(),
                    scene = DerivedScene(false, null, null, null, null, emptyList()),
                )
            )

        val json = bytes.toString(Charsets.UTF_8)

        assertTrue(!json.contains("assetCatalog"))
        assertTrue(!json.contains("actionCatalog"))
        assertTrue(!json.contains("\"sha256\":null"))
    }

    @Test
    fun `contract constructors reject noncanonical digests and invalid urls`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                DeriveRequest(
                    mapId = UUID.randomUUID(),
                    version = 1,
                    attempt = UUID.randomUUID(),
                    sourceSha256 = ("a" + "0".repeat(63)).uppercase(),
                    sourceUrl = URI("ftp://worker.test/source"),
                    bundleUrl = URI("https://worker.test/bundle"),
                    manifestUrl = URI("https://worker.test/manifest"),
                    resultUrl = URI("https://worker.test/result"),
                    catalogCandidates = emptyList(),
                )
            }

        assertTrue(error.message!!.contains("sourceSha256"))
    }

    @Test
    fun `request rejects unsupported URL schemes independently of digest validation`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                DeriveRequest(
                    mapId = UUID.randomUUID(),
                    version = 1,
                    attempt = UUID.randomUUID(),
                    sourceSha256 = digest(1),
                    sourceUrl = URI("ftp://worker.test/source"),
                    bundleUrl = URI("https://worker.test/bundle"),
                    manifestUrl = URI("https://worker.test/manifest"),
                    resultUrl = URI("https://worker.test/result"),
                    catalogCandidates = emptyList(),
                )
            }

        assertTrue(error.message!!.contains("sourceUrl"))
    }

    @Test
    fun `present scene requires both catalog references`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                DeriveSuccess(
                    mapId = UUID.randomUUID(),
                    version = 1,
                    attempt = UUID.randomUUID(),
                    sourceSha256 = digest(1),
                    bundleSha256 = digest(2),
                    bundleSize = 10,
                    manifestSha256 = digest(3),
                    manifestSize = 5,
                    scene =
                        DerivedScene(
                            present = true,
                            schemaVersion = "1",
                            sha256 = digest(4),
                            assetCatalog = CatalogReference("assets", "2026.08"),
                            actionCatalog = null,
                            requiredActions = emptyList(),
                        ),
                )
            }

        assertTrue(error.message!!.contains("actionCatalog"))
    }

    @Test
    fun `present scene rejects blank catalog references`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                DerivedScene(
                    present = true,
                    schemaVersion = "1",
                    sha256 = digest(4),
                    assetCatalog = CatalogReference("", ""),
                    actionCatalog = CatalogReference("grounds", "1"),
                    requiredActions = emptyList(),
                )
            }

        assertTrue(error.message!!.contains("assetCatalog"))
    }

    @Test
    fun `failure independently rejects incoherent and blank problem fields`() {
        listOf(
                DeriveProblem(DeriveFailureScope.SYSTEM, null, "CODE", null, "message"),
                DeriveProblem(DeriveFailureScope.CONTENT, null, "", null, "message"),
                DeriveProblem(DeriveFailureScope.CONTENT, null, "CODE", null, ""),
                DeriveProblem(DeriveFailureScope.CONTENT, "", "CODE", null, "message"),
                DeriveProblem(DeriveFailureScope.CONTENT, null, "CODE", "", "message"),
            )
            .forEach { problem ->
                assertThrows(IllegalArgumentException::class.java) { contentFailure(problem) }
            }
    }

    private fun presentScene(requiredActions: List<String>) =
        DerivedScene(
            present = true,
            schemaVersion = "1",
            sha256 = digest(2),
            assetCatalog = CatalogReference("assets", "2026.08"),
            actionCatalog = CatalogReference("grounds", "1"),
            requiredActions = requiredActions,
        )

    private fun digest(value: Int) = "%064x".format(value)

    private fun contentFailure(problem: DeriveProblem) =
        DeriveFailure(
            mapId = UUID.randomUUID(),
            version = 1,
            attempt = UUID.randomUUID(),
            sourceSha256 = digest(1),
            scope = DeriveFailureScope.CONTENT,
            retryable = false,
            problems = listOf(problem),
        )

    private fun resource(path: String): String =
        javaClass.getResourceAsStream(path)!!.readBytes().toString(Charsets.UTF_8)

    private fun v2SuccessNode(): ObjectNode = ObjectMapper().readTree(resource("/derive/result-v2-none.json")) as ObjectNode

    @Test fun v2SuccessRequiresNoneOrValidDialogue() {
        val mapper = ObjectMapper()
        val node = v2SuccessNode()
        val decoded = CanonicalJson.readResult(mapper.writeValueAsBytes(node))
        val encoded = mapper.readTree(CanonicalJson.write(decoded))
        assertEquals("NONE", encoded.path("dialogue").path("status").asText())
        assertEquals("STATIC_VALIDATION", encoded.path("dialogue").path("proof").path("proof").asText())
        listOf("UNKNOWN", "PENDING", "INVALID").forEach { status ->
            (node.path("dialogue") as ObjectNode).put("status", status)
            assertThrows(JsonMappingException::class.java) { CanonicalJson.readResult(mapper.writeValueAsBytes(node)) }
        }
    }

    @Test fun v2RejectsMissingDialogueAndUnknownFields() {
        val mapper = ObjectMapper()
        val missing = v2SuccessNode().apply { remove("dialogue") }
        assertThrows(JsonMappingException::class.java) { CanonicalJson.readResult(mapper.writeValueAsBytes(missing)) }
        val extra = v2SuccessNode().apply { (path("dialogue") as ObjectNode).put("trusted", true) }
        assertThrows(JsonMappingException::class.java) { CanonicalJson.readResult(mapper.writeValueAsBytes(extra)) }
        val coercion = v2SuccessNode().apply { ((path("dialogue").path("proof")) as ObjectNode).put("schemaVersion", "1") }
        assertThrows(JsonMappingException::class.java) { CanonicalJson.readResult(mapper.writeValueAsBytes(coercion)) }
    }

    @Test fun v1StillReadsWithoutInventingNone() {
        val old = CanonicalJson.readResult(resource("/derive/result-valid.json").toByteArray())
        val encoded = ObjectMapper().readTree(CanonicalJson.write(old))
        assertTrue(!encoded.has("dialogue"))
        val downgrade = v2SuccessNode().apply { put("schemaVersion", 1) }
        assertThrows(JsonMappingException::class.java) { CanonicalJson.readResult(ObjectMapper().writeValueAsBytes(downgrade)) }
    }

    @Test fun v1WriterDoesNotLeakNewFields() {
        val old = DeriveFailure(mapId=UUID.randomUUID(),version=1,attempt=UUID.randomUUID(),sourceSha256=digest(1),
            scope=DeriveFailureScope.CONTENT,retryable=false,
            problems=listOf(DeriveProblem(DeriveFailureScope.CONTENT,"dialogues/a.json","BAD",null,"bad", "dialogue","ERROR","dialogues/a.json","/nodes/a","a")))
        val json = CanonicalJson.write(old).decodeToString()
        assertTrue(!json.contains("severity") && !json.contains("pointer") && !json.contains("component"))
        assertTrue(CanonicalJson.readResult(json.toByteArray()) is DeriveFailure)
    }

    @Test fun allProofFieldsParticipateInEquality() {
        fun proof(files: List<DialogueFilePin> = emptyList(), requirements: List<DialogueRequirement> = emptyList(), bindings: Int? = null) =
            DialogueProof(digest(1),"test@sha256:${digest(8)}",digest(9),files=files,requirements=requirements,bindingsSchemaVersion=bindings)
        assertTrue(proof() != proof(files=listOf(DialogueFilePin("dialogues/a.json",digest(3),2))))
        assertTrue(proof() != proof(requirements=listOf(DialogueRequirement("speaker","mechanic"))))
        assertTrue(proof() != proof(bindings=1))
    }

    @Test fun v2RequestAndManifestRoundTrip() {
        val request = CanonicalJson.readRequest(resource("/derive/request-v2.json").toByteArray())
        assertEquals(2, request.schemaVersion)
        assertEquals(request, CanonicalJson.readRequest(CanonicalJson.write(request)))
        val manifest = CanonicalJson.readManifest(resource("/derive/manifest-v2-none.json").toByteArray())
        val roundTrip = CanonicalJson.readManifest(CanonicalJson.write(manifest))
        assertEquals(2, roundTrip.schemaVersion)
        assertEquals(CanonicalJson.write(manifest).decodeToString(), CanonicalJson.write(roundTrip).decodeToString())
    }

    @Test fun rejectsOversizedControlObjectsBeforeUpload() {
        val failure = DeriveFailure(schemaVersion=2,mapId=UUID.randomUUID(),version=1,attempt=UUID.randomUUID(),sourceSha256=digest(1),
            scope=DeriveFailureScope.SYSTEM,retryable=true,
            problems=listOf(DeriveProblem(DeriveFailureScope.SYSTEM,null,"LARGE",null,"x".repeat(1048576))))
        assertEquals("PROOF_REPORT_LIMIT", assertThrows(IllegalStateException::class.java) { CanonicalJson.write(failure) }.message)
    }

    @Test fun completeStoryProofRoundTripsWithoutLosingPins() {
        val decoded = CanonicalJson.readResult(resource("/derive/result-v2-valid.json").toByteArray())
        assertEquals(decoded, CanonicalJson.readResult(CanonicalJson.write(decoded)))
        val json = ObjectMapper().readTree(CanonicalJson.write(decoded))
        assertEquals("VALID", json.path("dialogue").path("status").asText())
        assertEquals(digest(7), json.path("dialogue").path("proof").path("contentPin").asText())
    }

    @Test fun manifestBudgetAlsoAppliesBeforeUpload() {
        val base = CanonicalJson.readManifest(resource("/derive/manifest-v2-none.json").toByteArray())
        val oversized = DerivedManifest(schemaVersion=2,sourceSha256=base.sourceSha256,
            bundleDigestInputs=listOf("x".repeat(4194304)),scene=base.scene,dialogue=base.dialogue)
        assertEquals("PROOF_REPORT_LIMIT", assertThrows(IllegalStateException::class.java) { CanonicalJson.write(oversized) }.message)
    }
}
