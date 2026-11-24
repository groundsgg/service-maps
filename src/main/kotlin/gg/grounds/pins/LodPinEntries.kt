package gg.grounds.pins

import gg.grounds.domain.LodClaim
import gg.grounds.domain.MapTrust
import gg.grounds.lod.LodArtifactStore

data class LodEntry(val artifacts: List<LodArtifactRef>)

data class LodArtifactRef(
    val format: String,
    val manifestSha256: String,
    val manifestUrl: String,
    val bundleSha256: String,
    val bundleUrl: String,
    val bundleBytes: Long,
    val sourceBundleSha256: String,
    val registrySha256: String,
    val generatorSha256: String,
    /**
     * Stable across pods, reconnects and proxies; never an extraction or file modification time.
     */
    val contentTimestampMillis: Long,
)

object LodPinEntries {
    fun forMap(
        mapBundleSha256: String,
        trust: MapTrust,
        litSha256: String?,
        published: List<LodClaim>,
        publicBase: String,
    ): LodEntry? {
        val artifact =
            published
                .filter {
                    it.published &&
                        it.facts != null &&
                        it.trust == trust &&
                        it.mapBundleSha256 == mapBundleSha256 &&
                        it.sourceBundleSha256 == litSha256
                }
                .maxByOrNull { it.startedAt } ?: return null
        val facts = requireNotNull(artifact.facts)
        return LodEntry(
            listOf(
                LodArtifactRef(
                    "lss20",
                    facts.manifestSha256,
                    "${publicBase.trimEnd('/')}/${LodArtifactStore.manifestKey(facts.manifestSha256)}",
                    facts.bundleSha256,
                    "${publicBase.trimEnd('/')}/${LodArtifactStore.bundleKey(facts.bundleSha256)}",
                    facts.bundleBytes,
                    artifact.sourceBundleSha256,
                    artifact.registrySha256,
                    artifact.generatorSha256,
                    artifact.contentTimestampMillis,
                )
            )
        )
    }
}
