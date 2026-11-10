package gg.grounds.lod

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.*

class LodSourceMetricsTest {
    @Test
    fun `partial and completed source reads have bounded shared counters`() {
        val registry = SimpleMeterRegistry()
        try {
            val metrics = LodSourceMetrics(registry)
            metrics.opened()
            metrics.finished(12, true)
            metrics.opened()
            metrics.finished(2, false)
            metrics.opened()
            metrics.openFailed()
            assertEquals(3.0, registry.get("grounds.lod.source.opens").counter().count())
            assertEquals(14.0, registry.get("grounds.lod.source.read.bytes").counter().count())
            assertEquals(
                1.0,
                registry
                    .get("grounds.lod.source.transfers")
                    .tag("outcome", "complete")
                    .counter()
                    .count(),
            )
            assertEquals(
                1.0,
                registry
                    .get("grounds.lod.source.transfers")
                    .tag("outcome", "partial")
                    .counter()
                    .count(),
            )
            assertTrue(
                registry.meters
                    .flatMap { it.id.tags }
                    .all { it.key == "outcome" && it.value in setOf("complete", "partial") }
            )
        } finally {
            registry.close()
        }
    }
}
