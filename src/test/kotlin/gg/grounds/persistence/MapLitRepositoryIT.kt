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
import io.quarkus.test.common.TestResourceScope
import io.quarkus.test.common.WithTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@QuarkusTest
@WithTestResource(value = PostgresResource::class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class MapLitRepositoryIT {

    @Inject lateinit var maps: MapRepository
    @Inject lateinit var versions: MapVersionRepository
    @Inject lateinit var pins: MapPinRepository
    @Inject lateinit var lits: MapLitRepository
    @Inject lateinit var dataSource: DataSource

    /** A light format of this test's own, so tests never see each other's lit rows. */
    private val format = 1000 + (Math.random() * 1_000_000).toInt()

    private fun publishedBundle(
        environment: String? = "stage",
        createdAt: Instant? = null,
    ): String {
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
        if (environment != null) pins.move(environment, map.id, 1, "staff")
        if (createdAt != null)
            dataSource.connection.use { c ->
                c.prepareStatement("UPDATE map_version SET created_at = ? WHERE map = ?").use { ps
                    ->
                    ps.setTimestamp(1, Timestamp.from(createdAt))
                    ps.setObject(2, map.id)
                    ps.executeUpdate()
                }
            }
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
    fun `a published upload is eligible before its first live pin`() {
        val sha = publishedBundle(environment = null)

        assertTrue(sha in drainCandidates())
        assertEquals(emptyList<String>(), lits.environmentsPinning(sha))
    }

    @Test
    fun `new uploads get priority but every fourth new bundle serves the oldest work`() {
        val oldest = publishedBundle(null, Instant.parse("1900-01-01T00:00:00Z"))
        val recent = (1..4).map { publishedBundle(null, Instant.parse("2100-01-0${it}T00:00:00Z")) }
        val selected =
            (1..4).map {
                val candidate = requireNotNull(lits.nextCandidate(format, 3))
                lits.start(candidate.bundleSha256, format, UUID.randomUUID())
                candidate.bundleSha256
            }

        assertEquals(recent.reversed().take(3) + oldest, selected)
    }

    @Test
    fun `unpublished content is not eligible even if it used to be pinned`() {
        val sha = publishedBundle()
        dataSource.connection.use { c ->
            c.prepareStatement("UPDATE map_version SET state = 'IN_REVIEW' WHERE bundle_sha256 = ?")
                .use { ps ->
                    ps.setString(1, sha)
                    ps.executeUpdate()
                }
        }

        assertFalse(sha in drainCandidates())
    }

    @Test
    fun `a pinned bundle without a lit copy is a candidate, and one with a lit copy is not`() {
        val lit = publishedBundle()
        val unlit = publishedBundle()
        val attempt = UUID.randomUUID()
        lits.start(lit, format, attempt)
        lits.succeed(lit, format, attempt, LitFacts("%064x".format(7), 70, 500, 120, 5))

        val candidates = drainCandidates()

        assertTrue(unlit in candidates)
        assertFalse(lit in candidates)
        assertEquals("%064x".format(7), lits.litCopies(format)[lit])
        assertEquals(listOf("stage"), lits.environmentsPinning(lit))
        assertEquals(MapTrust.FIRST_PARTY, lits.trustOf(lit))
        assertEquals(gg.grounds.domain.LitState.LIT, lits.find(lit, format)?.state)
        assertEquals(70, lits.find(lit, format)?.litSize)
        assertNull(lits.find(lit, format + 1))
        assertEquals(listOf(MapTrust.FIRST_PARTY), lits.publishedTrusts(lit))
        assertTrue(lits.publishedCopies(format).any { it.bundleSha256 == lit && it.litSize == 70L })
    }

    @Test
    fun `a failed bundle is tried again until its attempts run out`() {
        val retried = publishedBundle()
        val exhausted = publishedBundle()
        listOf(retried, exhausted, exhausted).forEach { sha ->
            val attempt = UUID.randomUUID()
            lits.start(sha, format, attempt)
            lits.fail(sha, format, attempt, "boom")
        }

        val candidates = drainCandidates(maxAttempts = 2)

        assertTrue(retried in candidates)
        assertFalse(exhausted in candidates)
        assertNull(lits.litCopies(format)[exhausted])
        assertEquals(2, lits.find(exhausted, format)?.attempts)
        assertEquals("boom", lits.find(exhausted, format)?.failure)
    }

    @Test
    fun `only one attempt at a time lights a bundle, and a stale one cannot finish it`() {
        val sha = publishedBundle()
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
        val sha = publishedBundle(environment = "lit-facts")
        val attempt = UUID.randomUUID()
        lits.start(sha, format, attempt)

        lits.succeed(sha, format, attempt, LitFacts("%064x".format(11), 11, 900, 300, 12))

        assertEquals(12, pins.pinned("lit-facts").single { it.bundleSha256 == sha }.estLoadedMib)
    }
}
