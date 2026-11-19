package gg.grounds.derive

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DialogueContentContractsTest {
    private val sha = "a".repeat(64)
    private val profile = """{"id":"grounds:test","version":"1","sha256":"$sha"}"""

    @Test fun bindingsRejectDuplicateUnknownAndUrlFields() {
        listOf(
            """{"schemaVersion":1,"profile":$profile,"speakers":{"guide":"npc","guide":"other"}}""",
            """{"schemaVersion":1,"profile":$profile,"speakers":{},"url":"https://evil.test/"}""",
            """{"schemaVersion":1,"profile":{"id":"x","version":"1","sha256":"$sha","uri":"https://evil.test/"},"speakers":{}}""",
        ).forEach { input -> assertThrows<DialogueContentException> { DialogueContentContracts.readBindings(input.toByteArray()) } }
    }

    @Test fun validBindingsResolveExplicitElementOnly() {
        val result = DialogueContentContracts.readBindings("""{"schemaVersion":1,"profile":$profile,"speakers":{"guide":"npc-guide"}}""".toByteArray())
        assertEquals("npc-guide", result.speakers["guide"])
        assertEquals(sha, result.profile.sha256)
    }

    @Test fun profilePreservesExactHostBytes() {
        val input = """{"schemaVersion":1,"id":"grounds:test","version":"1","hostCatalogJson":"{ \"generation\": \"test\" }\n","assetCatalog":{"id":"assets","version":"1"},"actionCatalogs":[{"id":"actions","version":"1"}],"startActions":{},"npcAssets":{}}"""
        val result = DialogueContentContracts.readProfile(input.toByteArray())
        assertContentEquals("{ \"generation\": \"test\" }\n".toByteArray(), result.hostCatalogBytes)
        val returned = result.hostCatalogBytes
        returned[0] = 0
        assertEquals('{'.code.toByte(), result.hostCatalogBytes[0])
    }

    @Test fun schemaVersionMustNotWrapToOne() {
        assertThrows<DialogueContentException> {
            DialogueContentContracts.readBindings("""{"schemaVersion":4294967297,"profile":$profile,"speakers":{}}""".toByteArray())
        }
    }

    @Test fun malformedUtf8AndTrailingDocumentsAreRejected() {
        assertThrows<DialogueContentException> { DialogueContentContracts.readBindings(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows<DialogueContentException> { DialogueContentContracts.readBindings("{} {}".toByteArray()) }
    }
}
