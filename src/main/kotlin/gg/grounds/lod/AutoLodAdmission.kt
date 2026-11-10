package gg.grounds.lod

import gg.grounds.domain.AutoLodSelection
import gg.grounds.domain.MapAddress
import gg.grounds.domain.MapLodRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.eclipse.microprofile.config.inject.ConfigProperty

/** Scheduled post-lighting selection. Never called by map push, discovery, or player requests. */
@ApplicationScoped
class AutoLodAdmission(
    private val lods: MapLodRepository,
    private val selection: AutoLodSelection?,
) {
    @Inject
    constructor(
        lods: MapLodRepository,
        @ConfigProperty(name = "grounds.maps.lod.auto.enabled", defaultValue = "false")
        enabled: Boolean,
        @ConfigProperty(name = "grounds.maps.lod.auto.environment") environment: Optional<String>,
        @ConfigProperty(name = "grounds.maps.lod.auto.namespace") namespace: Optional<String>,
        @ConfigProperty(name = "grounds.maps.lod.auto.name") name: Optional<String>,
        @ConfigProperty(name = "grounds.maps.lod.auto.budget-id") budgetId: Optional<String>,
        @ConfigProperty(name = "grounds.maps.lod.auto.registry-sha256") registry: Optional<String>,
        @ConfigProperty(name = "grounds.maps.lod.auto.generator-sha256")
        generator: Optional<String>,
        @ConfigProperty(name = "grounds.maps.lod.auto.worker-image") image: Optional<String>,
    ) : this(
        lods,
        if (enabled)
            AutoLodSelection(
                environment.orElseThrow {
                    IllegalArgumentException("automatic LOD environment is required")
                },
                MapAddress(
                    namespace.orElseThrow {
                        IllegalArgumentException("automatic LOD namespace is required")
                    },
                    name.orElseThrow { IllegalArgumentException("automatic LOD name is required") },
                ),
                budgetId.orElseThrow {
                    IllegalArgumentException("automatic LOD persistent budget is required")
                },
                registry.orElseThrow {
                    IllegalArgumentException("automatic LOD registry is required")
                },
                generator.orElseThrow {
                    IllegalArgumentException("automatic LOD generator is required")
                },
                image.orElseThrow {
                    IllegalArgumentException("automatic LOD immutable worker image is required")
                },
            )
        else null,
    )

    val enabled: Boolean
        get() = selection != null

    fun admit(now: Instant): UUID? = selection?.let { lods.automaticRun(it, now) }
}
