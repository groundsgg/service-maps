package gg.grounds.lit

import gg.grounds.api.PreparationDto
import gg.grounds.blob.PublishedBundles
import gg.grounds.domain.*
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Read model: a raw published version is not necessarily prepared for play. */
@ApplicationScoped
class MapPreparation
@Inject
constructor(
    private val lits: MapLitRepository,
    private val bundles: PublishedBundles,
    private val policy: MapLightPolicy,
) {
    val required
        get() = policy.enabled

    fun status(version: MapVersionRecord): PreparationDto {
        if (
            !required &&
                version.state !in
                    setOf(
                        VersionState.DERIVE_FAILED,
                        VersionState.REJECTED,
                        VersionState.TAKEN_DOWN,
                    )
        )
            return state(
                "UNAVAILABLE",
                "LIGHTING_DISABLED",
                "Server-side lighting preparation is disabled.",
            )
        return when (version.state) {
            VersionState.DRAFT -> state("QUEUED")
            VersionState.DERIVING -> state("DERIVING")
            VersionState.IN_REVIEW -> state("AWAITING_REVIEW")
            VersionState.DERIVE_FAILED ->
                state("FAILED", "DERIVATION_FAILED", "Map/scene preparation failed.")
            VersionState.REJECTED,
            VersionState.TAKEN_DOWN ->
                state("FAILED", "VERSION_UNAVAILABLE", "This version is unavailable.")
            VersionState.PUBLISHED -> published(version)
        }
    }

    /**
     * Forks share content, but a changed trust must not leave their public URLs pointing at 404s.
     */
    fun ensureDelivery(version: MapVersionRecord, trust: MapTrust) {
        if (!required) return
        val sha = requireNotNull(version.bundleSha256)
        bundles.ensure(sha, trust, version.sizeBytes)
        val light = lits.find(sha, policy.format)
        if (light?.state == LitState.LIT && light.litSha256 != null && light.litSize != null) {
            bundles.ensure(light.litSha256, trust, light.litSize)
        }
    }

    private fun published(version: MapVersionRecord): PreparationDto {
        if (!required)
            return state(
                "UNAVAILABLE",
                "LIGHTING_DISABLED",
                "Server-side lighting preparation is disabled.",
            )
        val sha =
            version.bundleSha256
                ?: return state("FAILED", "BUNDLE_MISSING", "The published bundle is missing.")
        val light = lits.find(sha, policy.format) ?: return state("WAITING_FOR_LIGHT")
        return when (light.state) {
            LitState.LIGHTING -> state("LIGHTING")
            LitState.FAILED ->
                if (light.attempts < LitReconciler.MAX_ATTEMPTS) state("WAITING_FOR_LIGHT")
                else
                    state(
                        "FAILED",
                        "LIGHTING_FAILED",
                        "Light preparation failed after its retry limit.",
                    )
            LitState.LIT -> {
                val litSha = light.litSha256
                val size = light.litSize
                if (litSha == null || size == null || size <= 0)
                    state(
                        "FAILED",
                        "LIGHTING_DATA_INVALID",
                        "The prepared light data is incomplete.",
                    )
                else if (
                    bundles.available(sha, version.trust, version.sizeBytes) &&
                        bundles.available(litSha, version.trust, size)
                )
                    state("READY")
                else state("WAITING_FOR_LIGHT", detail = "Publishing the prepared bundle.")
            }
        }
    }

    private fun state(stage: String, failureCode: String? = null, detail: String? = null) =
        PreparationDto(stage, policy.format, failureCode, detail)
}
