package gg.grounds.lod

import gg.grounds.api.LodPreparationDto
import gg.grounds.domain.*
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Read-only build feedback. This does not require LODs before activating a prepared map. */
@ApplicationScoped
class LodPreparation
@Inject
constructor(
    private val auto: AutoLodAdmission,
    private val lods: MapLodRepository,
    private val lits: MapLitRepository,
    private val pins: MapPinRepository,
) {
    fun status(map: MapRecord, version: MapVersionRecord): LodPreparationDto {
        val selected = auto.selection
        if (
            selected == null ||
                map.address != selected.address ||
                map.trust != MapTrust.FIRST_PARTY ||
                version.trust != MapTrust.FIRST_PARTY
        )
            return LodPreparationDto("NOT_CONFIGURED")
        require(version.mapId == map.id)
        if (version.state != VersionState.PUBLISHED || !Digest.isValid(version.bundleSha256))
            return LodPreparationDto("WAITING_FOR_MAP")
        val sha = requireNotNull(version.bundleSha256)
        val light = lits.find(sha, 2)
        if (
            light?.state != LitState.LIT ||
                !Digest.isValid(light.litSha256) ||
                (light.litSize ?: 0) <= 0
        )
            return LodPreparationDto("WAITING_FOR_LIGHT")
        val progress =
            lods.progress(
                map.trust,
                sha,
                requireNotNull(light.litSha256),
                selected.registrySha256,
                selected.generatorSha256,
            )
        when (progress?.state) {
            "READY" ->
                return LodPreparationDto(
                    "READY",
                    columns = progress.columns,
                    bundleBytes = progress.bundleBytes,
                )
            "GENERATING" -> return LodPreparationDto("GENERATING")
            "FAILED" -> return LodPreparationDto("FAILED", failureCode = "LOD_GENERATION_FAILED")
        }
        val budget = lods.budget(selected.budgetId)
        if (
            budget != null &&
                (budget.jobsReserved >= LodGenerationBounds.MAX_JOBS ||
                    budget.bytesReserved >
                        LodGenerationBounds.MAX_RESERVED_BYTES -
                            LodGenerationBounds.RESERVATION_BYTES)
        )
            return LodPreparationDto("LIMIT_REACHED", failureCode = "LOD_MEASUREMENT_LIMIT")
        if (pins.find(selected.environment, map.id)?.version != version.version)
            return LodPreparationDto("WAITING_FOR_ACTIVATION")
        return LodPreparationDto("QUEUED")
    }
}
