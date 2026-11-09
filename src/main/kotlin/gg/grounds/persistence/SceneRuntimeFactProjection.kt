package gg.grounds.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Connection
import java.util.UUID

internal object SceneRuntimeFactProjection {
    private val json = ObjectMapper()

    fun write(c: Connection, map: UUID, version: Int, facts: Map<String, String>?) {
        c.prepareStatement(
                "UPDATE map_version SET scene_runtime_facts = ?::jsonb WHERE map=? AND version=?"
            )
            .use { ps ->
                ps.setString(1, facts?.let(json::writeValueAsString))
                ps.setObject(2, map)
                ps.setInt(3, version)
                ps.executeUpdate()
            }
    }

    fun read(value: String?): Map<String, String>? =
        value?.let { text ->
            val node = json.readTree(text)
            check(node.isObject)
            node.fields().asSequence().associate { (key, value) ->
                check(value.isTextual)
                key to value.textValue()
            }
        }
}
