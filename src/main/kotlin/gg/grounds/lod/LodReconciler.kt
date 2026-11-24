package gg.grounds.lod

import gg.grounds.domain.LodClaim
import gg.grounds.domain.MapLitRepository
import gg.grounds.domain.MapLodRepository
import gg.grounds.lit.PinFiles
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

fun interface LodProjection {
    fun publish(claim: LodClaim): Boolean
}

@ApplicationScoped
class PublisherLodProjection
@Inject
constructor(private val pins: PinFiles, private val lits: MapLitRepository) : LodProjection {
    override fun publish(claim: LodClaim): Boolean =
        lits.environmentsPinning(claim.mapBundleSha256).map(pins::publish).all { it }
}

/** Manual and scheduled post-lighting admission are both off by default. */
@ApplicationScoped
class LodReconciler(
    private val lods: MapLodRepository,
    private val jobs: LodJobGateway,
    private val artifacts: LodArtifacts,
    private val projection: LodProjection,
    private val enabled: Boolean,
    private val publicEnabled: Boolean,
    private val runId: UUID?,
    private val clock: Clock = Clock.systemUTC(),
    private val automatic: AutoLodAdmission? = null,
) {
    @Inject
    constructor(
        lods: MapLodRepository,
        jobs: LodJobGateway,
        artifacts: LodArtifacts,
        projection: LodProjection,
        @ConfigProperty(name = "grounds.maps.lod.enabled", defaultValue = "false") enabled: Boolean,
        @ConfigProperty(name = "grounds.maps.lod.publication.enabled", defaultValue = "false")
        publicEnabled: Boolean,
        @ConfigProperty(name = "grounds.maps.lod.run-id") runId: Optional<String>,
        automatic: AutoLodAdmission,
    ) : this(
        lods,
        jobs,
        artifacts,
        projection,
        enabled,
        publicEnabled,
        selectedRunId(runId),
        automatic = automatic,
    )

    private val log = Logger.getLogger(LodReconciler::class.java)

    @Scheduled(
        every = "{grounds.maps.lod.poll-interval:30s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun reconcile() {
        try {
            step(clock.instant())
        } catch (e: Exception) {
            log.warnf("LOD reconciliation deferred (%s)", e.javaClass.simpleName)
        }
    }

    internal fun step(now: Instant) {
        val active = lods.active()
        if (active != null) follow(active, now)
        if (publicEnabled && automatic?.enabled != true) {
            lods.ready().firstOrNull()?.let { ready ->
                artifacts.publish(ready)
                lods.markPublished(ready.attempt)
            }
            lods.projectionPending().firstOrNull()?.let { ready ->
                if (projection.publish(ready)) lods.projectionComplete(ready.attempt)
            }
        }
        // Finishing or cancelling a previous claim is independent of the admission flag.
        // Do not start another job in the same tick that followed a former one.
        if (active != null || !enabled) return
        val selected = runId ?: automatic?.admit(now) ?: return
        val next = lods.claim(selected, now) ?: return
        jobs.create(next, artifacts.requestJson(next))
        activate(next)
    }

    private fun follow(c: LodClaim, now: Instant) {
        val age = Duration.between(c.startedAt, now)
        if (age >= DEADLINE) {
            stopAndFail(c, "LOD job or acceptance deadline exceeded")
            return
        }
        when (jobs.status(c)) {
            LodJobStatus.SUSPENDED -> if (enabled) activate(c)
            LodJobStatus.RUNNING -> Unit
            LodJobStatus.FAILED -> stopAndFail(c, "LOD worker failed")
            LodJobStatus.SUCCEEDED -> {
                try {
                    val facts = artifacts.accept(c)
                    if (
                        !lods.succeed(c.attempt, facts, clock::instant) &&
                            !clock.instant().isBefore(c.startedAt.plus(DEADLINE))
                    )
                        stopAndFail(c, "LOD result verification exceeded deadline")
                } catch (e: LodInvalidResult) {
                    stopAndFail(c, "LOD result was refused")
                } catch (e: LodArtifactUnavailable) {
                    log.debug("LOD storage unavailable; keeping the claim")
                }
            }
            null -> {
                if (age >= MISSING_GRACE) stopAndFail(c, "LOD job missing; no automatic retry")
                // The deterministic name makes an unacknowledged create idempotent.
                // Never recreate after the grace: it may have run and been cleaned up.
                else if (enabled) {
                    jobs.create(c, artifacts.requestJson(c))
                    activate(c)
                }
            }
        }
    }

    private fun activate(c: LodClaim) {
        lods.activateIfCurrent(c.attempt, clock::instant) { jobs.activate(c) }
    }

    private fun stopAndFail(c: LodClaim, reason: String) {
        lods.failWhenStopped(c.attempt, reason) { jobs.stopAndConfirm(c) }
    }

    companion object {
        val DEADLINE: Duration = Duration.ofMinutes(15)
        val MISSING_GRACE: Duration = Duration.ofMinutes(2)
    }
}

private fun selectedRunId(value: Optional<String>): UUID? =
    try {
        value.orElse(null)?.let(UUID::fromString)
    } catch (_: IllegalArgumentException) {
        null
    }
