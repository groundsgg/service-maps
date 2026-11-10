package gg.grounds.lod.format

import java.util.UUID

/** Server-only exact loaded-map identity. Discovery never generates or reads terrain. */
data class LodServingDiscoveryRequest(
    val schemaVersion: Int = 1,
    val mapBundleSha256: String = "",
    val sourceBundleSha256: String = "",
    val registrySha256: String = "",
    val generatorSha256: String = "",
) {
    fun validate() {
        require(
            schemaVersion == 1 &&
                listOf(mapBundleSha256, sourceBundleSha256, registrySha256, generatorSha256)
                    .all(::isDigest)
        )
    }
}

/**
 * Immutable private serving selection; the same capability is still required for credits/source.
 */
data class LodServingSelection(
    val schemaVersion: Int = 1,
    val runId: String = "",
    val mapBundleSha256: String = "",
    val sourceBundleSha256: String = "",
    val registrySha256: String = "",
    val generatorSha256: String = "",
) {
    fun validate() {
        require(UUID.fromString(runId).toString() == runId)
        LodServingDiscoveryRequest(
                schemaVersion,
                mapBundleSha256,
                sourceBundleSha256,
                registrySha256,
                generatorSha256,
            )
            .validate()
    }
}
