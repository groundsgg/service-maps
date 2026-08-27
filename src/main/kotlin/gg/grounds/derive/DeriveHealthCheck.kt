package gg.grounds.derive

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.Optional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Readiness

/** Pure readiness check: it never creates, lists, or mutates Jobs. */
@Readiness
@ApplicationScoped
class DeriveHealthCheck(
    private val enabled: Boolean,
    private val image: String,
    private val jobs: DeriveJobGateway,
) : HealthCheck {
    @Inject
    constructor(
        @ConfigProperty(name = "grounds.maps.derive.enabled", defaultValue = "false")
        enabled: Boolean,
        @ConfigProperty(name = "grounds.maps.derive.image") image: Optional<String>,
        jobs: DeriveJobGateway,
    ) : this(enabled, image.orElse(""), jobs)

    override fun call(): HealthCheckResponse =
        HealthCheckResponse.named("derive-jobs").let {
            if (!enabled) it.up().withData("status", "disabled").build()
            else if (image.isBlank())
                it.down().withData("reason", "image is not configured").build()
            else if (!jobs.ready()) it.down().withData("reason", "Job API is unavailable").build()
            else it.up().withData("image", image.substringBefore('?')).build()
        }
}
