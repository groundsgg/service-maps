package gg.grounds.catalog

import java.net.URI
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class PinnedDialogueProfilesTest {
    private val sha = "a".repeat(64)
    private val base = URI("https://cdn.test")
    private fun pin(hash: String = sha, origin: String = "https://cdn.test") =
        """{"id":"grounds:test","version":"1","sha256":"$hash","sizeBytes":10,"uri":"$origin/dialogue/profiles/test/1/$hash/profile.json"}"""
    @Test fun rejectsConflictingIdentityAndUntrustedOrigin() {
        assertThrows<IllegalArgumentException> { PinnedDialogueProfiles.parse("[${pin()},${pin("b".repeat(64))}]", base) }
        assertThrows<IllegalArgumentException> { PinnedDialogueProfiles.parse("[${pin(origin="https://evil.test")}]", base) }
        assertThrows<IllegalArgumentException> { PinnedDialogueProfiles.parse("[${pin().replace("/profile.json", "/../profile.json")}]", base) }
    }
    @Test fun resolvesExactPinnedVersion() {
        val result = PinnedDialogueProfiles.parse("[${pin()}]", base).pins().single()
        assertEquals("grounds:test", result.id)
        assertEquals("1", result.version)
        assertEquals(sha, result.sha256)
    }
    @Test fun emptyConfigurationDoesNotInventProfile() {
        assertEquals(emptyList(), PinnedDialogueProfiles.parse("[]", base).pins())
    }
    @Test fun rejectsOversizeDuplicateUnknownAndMutableLocations() {
        listOf("[${pin().replace("\"sizeBytes\":10", "\"sizeBytes\":1048577")}]",
            "[${pin().replace("\"id\":", "\"id\":\"other\",\"id\":")} ]",
            "[${pin().replace("profile.json\"", "profile.json?token=x\"")}]",
            "[${pin().replace("\"sizeBytes\":10", "\"sizeBytes\":10,\"other\":true")}]")
            .forEach { assertThrows<IllegalArgumentException> { PinnedDialogueProfiles.parse(it, base) } }
    }
}
