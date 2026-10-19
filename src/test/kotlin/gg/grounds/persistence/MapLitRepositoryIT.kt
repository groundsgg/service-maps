package gg.grounds.persistence

import gg.grounds.PostgresResource
import gg.grounds.domain.BundleFacts
import gg.grounds.domain.LitFacts
import gg.grounds.domain.MapAddress
import gg.grounds.domain.MapKind
import gg.grounds.domain.MapLitRepository
import gg.grounds.domain.MapPinRepository
import gg.grounds.domain.MapRepository
import gg.grounds.domain.MapTrust
import gg.grounds.domain.MapVersionRepository
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(value = PostgresResource::class, restrictToAnnotatedClass = true)
class MapLitRepositoryIT {

    @Inject lateinit var maps: MapRepository
    @Inject lateinit var versions: MapVersionRepository
    @Inject lateinit var pins: MapPinRepository
    @Inject lateinit var lits: MapLitRepository

    /** A light format of this test's own, so tests never see each other's lit rows. */
    private val format = 1000 + (Math.random() * 1_000_000).toInt()

    private fun pinnedBundle(environment: String = "stage"): String {
        val map =
            maps.create(
                MapAddress("lit", "map-${UUID.randomUUID()}"),
                "lit",
                MapKind.ARENA,
                false,
                MapTrust.FIRST_PARTY,
                "builder",
            )
        versions.commit(map.id, null, null, null, null, "builder")
        val sha = "%064x".format(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE)
        versions.publish(map.id, 1, BundleFacts(sha, null, 100, null, null), "publisher")
        pins.move(environment, map.id, 1, "staff")
        return sha
    }

    /** Every candidate there is, each marked as being lit so the next one comes up. */
    private fun drainCandidates(maxAttempts: Int = 3): List<String> = buildList {
        while (true) {
            val next = lits.nextCandidate(format, maxAttempts) ?: break
            add(next.bundleSha256)
            lits.start(next.bundleSha256, format, UUID.randomUUID())
        }
    }

    @Test
    fun `a pinned bundle without a lit copy is a candidate, and one with a lit copy is not`() {
        val lit = pinnedBundle()
        val unlit = pinnedBundle()
        val attempt = UUID.randomUUID()
        lits.start(lit, format, attempt)
        lits.succeed(lit, format, attempt, LitFacts("%064x".format(7), 70, 500, 120, 5))

        val candidates = drainCandidates()

        assertTrue(unlit in candidates)
        assertFalse(lit in candidates)
        assertEquals("%064x".format(7), lits.litCopies(format)[lit])
        assertEquals(listOf("stage"), lits.environmentsPinning(lit))
        assertEquals(MapTrust.FIRST_PARTY, lits.trustOf(lit))
    }

    @Test
    fun `a failed bundle is tried again until its attempts run out`() {
        val retried = pinnedBundle()
        val exhausted = pinnedBundle()
        listOf(retried, exhausted, exhausted).forEach { sha ->
            val attempt = UUID.randomUUID()
            lits.start(sha, format, attempt)
            lits.fail(sha, format, attempt, "boom")
        }

        val candidates = drainCandidates(maxAttempts = 2)

        assertTrue(retried in candidates)
        assertFalse(exhausted in candidates)
        assertNull(lits.litCopies(format)[exhausted])
    }

    @Test
    fun `only one attempt at a time lights a bundle, and a stale one cannot finish it`() {
        val sha = pinnedBundle()
        val stale = UUID.randomUUID()
        lits.start(sha, format, stale)
        lits.fail(sha, format, stale, "timed out")
        val current = UUID.randomUUID()
        lits.start(sha, format, current)

        val refused = runCatching {
            lits.succeed(sha, format, stale, LitFacts("%064x".format(9), 9, 1, 1, 1))
        }

        assertTrue(refused.isFailure)
        assertNull(lits.litCopies(format)[sha])
        assertEquals(current, lits.lighting(format)?.takeIf { it.bundleSha256 == sha }?.attempt)
    }

    @Test
    fun `a lit copy fills the memory facts of the bundle's versions`() {
        val sha = pinnedBundle(environment = "lit-facts")
        val attempt = UUID.randomUUID()
        lits.start(sha, format, attempt)

        lits.succeed(sha, format, attempt, LitFacts("%064x".format(11), 11, 900, 300, 12))

        assertEquals(12, pins.pinned("lit-facts").single { it.bundleSha256 == sha }.estLoadedMib)
    }
}
