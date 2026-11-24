package gg.grounds.lod

import gg.grounds.domain.LodServingRepository
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
import org.jboss.logging.Logger

/** Crash recovery between READY acceptance and private serving publication is idempotent. */
@ApplicationScoped
class LodServingReconciler
@Inject
constructor(private val quotas: LodServingRepository, private val config: LodServingConfig) {
    @Scheduled(
        every = "{grounds.maps.lod.serving.poll-interval:30s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun reconcile() {
        if (!config.configured) return
        try {
            quotas.reconcileEntitled(
                requireNotNull(config.budgetId),
                requireNotNull(config.generationBudgetId),
                Instant.now(),
            )
        } catch (failure: Exception) {
            Logger.getLogger(LodServingReconciler::class.java)
                .warnf("LOD serving reconciliation deferred (%s)", failure.javaClass.simpleName)
        }
    }
}
