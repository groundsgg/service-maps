package gg.grounds.lit

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * The lighting worker's contract: what it is asked to light, and what it reports back. The worker
 * (`lit-worker/`) keeps its own copy of these field names; they change together.
 */
const val LIT_SCHEMA_VERSION = 1

/**
 * @property format the light format to produce; the worker refuses one it does not light to
 * @property sourceUrl the published bundle, readable
 * @property litUrl where the lit bundle goes, writable once
 * @property resultUrl where the result goes, written last
 */
data class LitRequest(
    val schemaVersion: Int = LIT_SCHEMA_VERSION,
    val format: Int,
    val bundleSha256: String,
    val sourceUrl: String,
    val litUrl: String,
    val resultUrl: String,
)

/** A lit copy the worker made, or [error] when it could not. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class LitResult(
    val schemaVersion: Int = LIT_SCHEMA_VERSION,
    val format: Int,
    val bundleSha256: String,
    val litSha256: String? = null,
    val litSize: Long? = null,
    val presentChunks: Int? = null,
    val keptChunks: Int? = null,
    val estLoadedMib: Int? = null,
    val error: String? = null,
)

internal object LitJson {
    private val mapper: ObjectMapper =
        jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)

    fun write(request: LitRequest): String = mapper.writeValueAsString(request)

    fun readResult(bytes: ByteArray): LitResult =
        mapper.readValue<LitResult>(bytes).also {
            require(it.schemaVersion == LIT_SCHEMA_VERSION) {
                "unsupported lit result schema ${it.schemaVersion}"
            }
        }
}
