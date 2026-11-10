package gg.grounds.lod

import gg.grounds.domain.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class LodReconcilerTest {
    private val now = Instant.parse("2026-11-08T12:00:00Z")
    private val claim =
        LodClaim(
            UUID.randomUUID(),
            UUID.randomUUID(),
            MapTrust.FIRST_PARTY,
            "a".repeat(64),
            "b".repeat(64),
            "c".repeat(64),
            "d".repeat(64),
            "ghcr.io/test/image@sha256:${"e".repeat(64)}",
            now,
        )
    private val facts = LodFacts("1".repeat(64), 100, "2".repeat(64), 50, 1)

    private class Repository(private val candidate: LodClaim) : MapLodRepository {
        var row: LodClaim? = null
        var failed = false
        var pending = false
        var claimed = 0

        override fun createRun(run: LodRun) {}

        override fun claim(runId: UUID, now: Instant): LodClaim? {
            claimed++
            if (row != null || failed) return null
            row = candidate
            return row
        }

        override fun active() = row?.takeIf { it.facts == null && !failed }

        override fun budget(id: String): LodReservation? = null

        override fun failWhenStopped(
            attempt: UUID,
            reason: String,
            stopped: () -> Boolean,
        ): Boolean {
            if (!stopped()) return false
            failed = true
            return true
        }

        override fun activateIfCurrent(
            attempt: UUID,
            now: () -> Instant,
            activate: () -> Unit,
        ): Boolean {
            if (active() == null || !now().isBefore(candidate.startedAt.plusSeconds(900)))
                return false
            activate()
            return true
        }

        override fun succeed(attempt: UUID, facts: LodFacts, now: () -> Instant): Boolean {
            if (!now().isBefore(candidate.startedAt.plusSeconds(900))) return false
            row = row!!.copy(facts = facts)
            return true
        }

        override fun ready() = listOfNotNull(row?.takeIf { it.facts != null && !it.published })

        override fun markPublished(attempt: UUID) {
            row = row!!.copy(published = true)
            pending = true
        }

        override fun published() = listOfNotNull(row?.takeIf { it.published })

        override fun projectionPending() = if (pending) published() else emptyList()

        override fun projectionComplete(attempt: UUID) {
            pending = false
        }
    }

    private class Jobs : LodJobGateway {
        var activations = 0

        override fun activate(claim: LodClaim) {
            activations++
        }

        var status: LodJobStatus? = LodJobStatus.RUNNING
        var stopped = false
        var gone = false
        val created = mutableListOf<String>()

        override fun create(claim: LodClaim, requestJson: String) {
            created += claim.jobName
        }

        override fun status(claim: LodClaim) = status

        override fun stopAndConfirm(claim: LodClaim): Boolean {
            stopped = true
            return gone
        }
    }

    private class Artifacts(private val facts: LodFacts) : LodArtifacts {
        var error: RuntimeException? = null
        var publicWrites = 0
        var afterAccept: () -> Unit = {}

        override fun requestJson(c: LodClaim) = "{}"

        override fun accept(c: LodClaim): LodFacts {
            error?.let { throw it }
            afterAccept()
            return facts
        }

        override fun publish(c: LodClaim) {
            publicWrites++
        }
    }

    private val repo = Repository(claim)
    private val jobs = Jobs()
    private val artifacts = Artifacts(facts)
    private var projectionWorks = true
    private var projections = 0
    private val clock =
        object : java.time.Clock() {
            var time = now

            override fun getZone() = java.time.ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId) = this

            override fun instant() = time
        }

    private fun controller(enabled: Boolean = true, public: Boolean = false) =
        LodReconciler(
            repo,
            jobs,
            artifacts,
            {
                projections++
                projectionWorks
            },
            enabled,
            public,
            claim.runId,
            clock,
        )

    @Test
    fun `disabled admission cannot create jobs and verified output stays private by default`() {
        controller(false).step(now)
        assertEquals(0, repo.claimed)
        controller().step(now)
        assertEquals(listOf(claim.jobName), jobs.created)
        jobs.status = LodJobStatus.SUCCEEDED
        controller().step(now.plusSeconds(10))
        assertEquals(facts, repo.row!!.facts)
        assertEquals(0, artifacts.publicWrites)
        assertEquals(0, projections)
    }

    @Test
    fun `timeout and failed job retain the global slot until actual pods have stopped`() {
        controller().step(now)
        controller().step(now.plusSeconds(901))
        assertTrue(jobs.stopped)
        assertFalse(repo.failed)
        jobs.gone = true
        controller().step(now.plusSeconds(902))
        assertTrue(repo.failed)
        assertEquals(1, jobs.created.size)
    }

    @Test
    fun `lost create acknowledgement recovers the same claim within grace and never retries a missing old job`() {
        controller().step(now)
        jobs.status = null
        controller().step(now.plusSeconds(10))
        assertEquals(listOf(claim.jobName, claim.jobName), jobs.created)
        jobs.gone = true
        controller().step(now.plusSeconds(121))
        assertTrue(repo.failed)
        assertEquals(2, jobs.created.size)
    }

    @Test
    fun `unavailable storage defers acceptance while invalid results fail without publishing`() {
        controller().step(now)
        jobs.status = LodJobStatus.SUCCEEDED
        artifacts.error = LodArtifactUnavailable(IllegalStateException("offline"))
        controller().step(now.plusSeconds(20))
        assertFalse(repo.failed)
        assertNull(repo.row!!.facts)
        artifacts.error = LodInvalidResult("wrong source")
        jobs.gone = true
        controller().step(now.plusSeconds(30))
        assertTrue(repo.failed)
        assertEquals(0, artifacts.publicWrites)
    }

    @Test
    fun `projection failure is retried durably without regenerating or republishing artifacts`() {
        val controller = controller(public = true)
        controller.step(now)
        jobs.status = LodJobStatus.SUCCEEDED
        projectionWorks = false
        controller.step(now.plusSeconds(10))
        assertTrue(repo.row!!.published)
        assertTrue(repo.pending)
        assertEquals(1, artifacts.publicWrites)
        projectionWorks = true
        controller.step(now.plusSeconds(20))
        assertFalse(repo.pending)
        assertEquals(2, projections)
        assertEquals(1, artifacts.publicWrites)
        assertEquals(1, jobs.created.size)
    }

    @Test
    fun `time spent verifying cannot accept an expired result`() {
        val controller = controller()
        controller.step(now)
        jobs.status = LodJobStatus.SUCCEEDED
        jobs.gone = true
        artifacts.afterAccept = { clock.time = now.plusSeconds(901) }
        controller.step(now.plusSeconds(10))
        assertTrue(repo.failed)
        assertNull(repo.row!!.facts)
        assertEquals(0, artifacts.publicWrites)
    }

    @Test
    fun `suspended jobs resume only while the claim remains current`() {
        val controller = controller()
        controller.step(now)
        jobs.status = LodJobStatus.SUSPENDED
        val before = jobs.activations
        controller.step(now.plusSeconds(1))
        assertEquals(before + 1, jobs.activations)
        repo.failed = true
        controller(false).step(now.plusSeconds(2))
        assertEquals(before + 1, jobs.activations)
    }
}
