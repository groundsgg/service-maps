package gg.grounds.lod.format

import java.util.UUID

const val LOD_PILOT_HEADER = "X-Grounds-Lod-Pilot"

data class LodPayloadRequest(
    val schemaVersion: Int = 1,
    val requestId: String = "",
    val mapBundleSha256: String = "",
    val playerId: String = "",
    val bytes: Int = 0,
) {
    fun validate() {
        pilotIds(schemaVersion, requestId, mapBundleSha256, playerId)
        require(bytes in 1..256 * 1024)
    }
}

data class LodSourceRequest(
    val schemaVersion: Int = 1,
    val requestId: String = "",
    val mapBundleSha256: String = "",
    val playerId: String = "",
) {
    fun validate() {
        pilotIds(schemaVersion, requestId, mapBundleSha256, playerId)
    }
}

/**
 * Consumers MUST compute expiry from the monotonic time immediately BEFORE sending the request,
 * never from response arrival. Discard delayed responses and unused/expired credits; no refund.
 */
data class LodPayloadGrant(
    val schemaVersion: Int = 1,
    val runId: String = "",
    val requestId: String = "",
    val bytes: Int = 0,
    val validForMillis: Long = 0,
) {
    fun validate() {
        require(schemaVersion == 1)
        canonicalUuid(runId)
        canonicalUuid(requestId)
        require(bytes in 1..256 * 1024 && validForMillis in 1..1000)
    }
}

/**
 * Narrow private source, for a server only. Never sent to a Minecraft client or stored in a pin
 * file. The relative URL requires the pilot header and permits one GET, no HEAD/Range. Compute
 * expiry from request-start monotonic time; discard responses arriving after expiry.
 */
data class LodSourceGrant(
    val schemaVersion: Int = 1,
    val runId: String = "",
    val requestId: String = "",
    val format: String = "lss20",
    val mapBundleSha256: String = "",
    val sourceBundleSha256: String = "",
    val registrySha256: String = "",
    val generatorSha256: String = "",
    val manifestSha256: String = "",
    val bundleSha256: String = "",
    val bundleBytes: Long = 0,
    val contentTimestampMillis: Long = 0,
    val bundleUrl: String = "",
    val validForMillis: Long = 0,
) {
    fun validate() {
        require(schemaVersion == 1 && format == "lss20")
        canonicalUuid(runId)
        canonicalUuid(requestId)
        require(
            listOf(
                    mapBundleSha256,
                    sourceBundleSha256,
                    registrySha256,
                    generatorSha256,
                    manifestSha256,
                    bundleSha256,
                )
                .all(::isDigest)
        )
        require(
            bundleBytes in 1..LodBounds.MAX_BUNDLE_BYTES &&
                contentTimestampMillis > 0 &&
                bundleUrl == "/v1/maps/lod-pilots/$runId/source/$requestId" &&
                validForMillis in 1..120000
        )
    }
}

private fun pilotIds(schema: Int, request: String, map: String, player: String) {
    require(schema == 1 && isDigest(map))
    canonicalUuid(request)
    canonicalUuid(player)
}

private fun canonicalUuid(value: String) {
    require(UUID.fromString(value).toString() == value) { "invalid canonical UUID" }
}
