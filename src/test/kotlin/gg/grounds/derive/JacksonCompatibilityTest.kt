package gg.grounds.derive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class JacksonCompatibilityTest {
    data class CatalogRevision(val namespace: String, val revision: Int)

    @Test
    fun `Jackson can round trip Kotlin catalog metadata with the resolved annotations`() {
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val revision = CatalogRevision("grounds", 3)

        assertEquals(
            revision,
            mapper.readValue(mapper.writeValueAsString(revision), CatalogRevision::class.java),
        )
    }
}
