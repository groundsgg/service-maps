package gg.grounds.lit

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import gg.grounds.blob.BlobMetadata
import gg.grounds.blob.BlobStore
import gg.grounds.domain.LitCandidate
import gg.grounds.domain.LitFacts
import gg.grounds.domain.LitRecord
import gg.grounds.domain.LitState
import gg.grounds.domain.MapLitRepository
import gg.grounds.domain.MapTrust
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LitReconcilerTest {
    private val sha = "a".repeat(64)
    private val litSha = "b".repeat(64)
    private val now = Instant.parse("2026-10-19T12:00:00Z")

    private val lits = FakeLits()
    private val jobs = FakeJobs()
    private val blobs = FakeBlobs()
    private val published = mutableListOf<String>()
    private val reconciler =
        LitReconciler(
            lits,
            jobs,
            blobs,
            {
                published += it
                true
            },
            true,
            1,
            Duration.ofMinutes(20),
        )

    @Test
    fun `a pinned bundle without a lit copy gets a lighting job`() {
        lits.candidates += LitCandidate(sha, MapTrust.FIRST_PARTY)

        reconciler.step(now)

        val record = lits.rows.getValue(sha)
        assertEquals(LitState.LIGHTING, record.state)
        val name = LitReconciler.jobName(sha, 1, record.attempt)
        assertTrue(name.length <= 63)
        val request = jacksonObjectMapper().readTree(jobs.created.getValue(name))
        assertEquals(sha, request["bundleSha256"].asText())
        assertEquals(1, request["format"].asInt())
        assertEquals("source:$sha", request["sourceUrl"].asText())
        assertEquals(
            "work:${LitReconciler.litKey(sha, 1, record.attempt)}",
            request["litUrl"].asText(),
        )
    }

    @Test
    fun `one bundle at a time`() {
        lits.candidates += LitCandidate(sha, MapTrust.FIRST_PARTY)
        reconciler.step(now)
        lits.candidates += LitCandidate("c".repeat(64), MapTrust.FIRST_PARTY)
        jobs.status = LitJobStatus.RUNNING

        reconciler.step(now.plusSeconds(30))

        assertEquals(1, jobs.created.size)
    }

    @Test
    fun `a finished job is promoted, recorded and republished`() {
        lits.candidates += LitCandidate(sha, MapTrust.UNTRUSTED)
        reconciler.step(now)
        val attempt = lits.rows.getValue(sha).attempt
        blobs.objects[LitReconciler.resultKey(sha, 1, attempt)] =
            """{"schemaVersion":1,"format":1,"bundleSha256":"$sha","litSha256":"$litSha",
               "litSize":1234,"presentChunks":900,"keptChunks":300,"estLoadedMib":10}"""
                .toByteArray()
        blobs.sizes[LitReconciler.litKey(sha, 1, attempt)] = 1234
        lits.environments[sha] = listOf("prod", "stage")
        lits.trust[sha] = MapTrust.UNTRUSTED
        jobs.status = LitJobStatus.SUCCEEDED

        reconciler.step(now.plusSeconds(60))

        assertEquals(LitState.LIT, lits.rows.getValue(sha).state)
        assertEquals(LitFacts(litSha, 1234, 900, 300, 10), lits.facts[sha])
        assertEquals(
            listOf(
                Triple(
                    LitReconciler.litKey(sha, 1, attempt),
                    BlobStore.bundleKey(litSha),
                    MapTrust.UNTRUSTED,
                )
            ),
            blobs.promoted,
        )
        assertEquals(listOf("prod", "stage"), published)
    }

    @Test
    fun `a lit bundle of the wrong size fails the attempt`() {
        lits.candidates += LitCandidate(sha, MapTrust.FIRST_PARTY)
        reconciler.step(now)
        val attempt = lits.rows.getValue(sha).attempt
        blobs.objects[LitReconciler.resultKey(sha, 1, attempt)] =
            """{"schemaVersion":1,"format":1,"bundleSha256":"$sha","litSha256":"$litSha",
               "litSize":1234,"presentChunks":900,"keptChunks":300,"estLoadedMib":10}"""
                .toByteArray()
        blobs.sizes[LitReconciler.litKey(sha, 1, attempt)] = 99
        jobs.status = LitJobStatus.SUCCEEDED

        reconciler.step(now.plusSeconds(60))

        assertEquals(LitState.FAILED, lits.rows.getValue(sha).state)
        assertTrue(blobs.promoted.isEmpty())
        assertTrue(published.isEmpty())
    }

    @Test
    fun `a worker refusal fails the attempt`() {
        lits.candidates += LitCandidate(sha, MapTrust.FIRST_PARTY)
        reconciler.step(now)
        val attempt = lits.rows.getValue(sha).attempt
        blobs.objects[LitReconciler.resultKey(sha, 1, attempt)] =
            """{"schemaVersion":1,"format":1,"bundleSha256":"$sha","error":"bundle digest mismatch"}"""
                .toByteArray()
        jobs.status = LitJobStatus.SUCCEEDED

        reconciler.step(now.plusSeconds(60))

        assertEquals(LitState.FAILED, lits.rows.getValue(sha).state)
        assertTrue(lits.reasons.getValue(sha).contains("bundle digest mismatch"))
    }

    @Test
    fun `a job that runs too long or vanished fails the attempt`() {
        lits.candidates += LitCandidate(sha, MapTrust.FIRST_PARTY)
        reconciler.step(now)

        jobs.status = LitJobStatus.RUNNING
        reconciler.step(now.plus(Duration.ofMinutes(10)))
        assertEquals(LitState.LIGHTING, lits.rows.getValue(sha).state)
        reconciler.step(now.plus(Duration.ofMinutes(21)))
        assertEquals(LitState.FAILED, lits.rows.getValue(sha).state)

        lits.candidates += LitCandidate(sha, MapTrust.FIRST_PARTY)
        lits.startedAt = now.plus(Duration.ofMinutes(22))
        reconciler.step(now.plus(Duration.ofMinutes(22)))
        jobs.status = null
        reconciler.step(now.plus(Duration.ofMinutes(23)))
        assertEquals(LitState.LIGHTING, lits.rows.getValue(sha).state)
        reconciler.step(now.plus(Duration.ofMinutes(25)))
        assertEquals(LitState.FAILED, lits.rows.getValue(sha).state)
    }

    @Test
    fun `nothing runs when lighting is off`() {
        lits.candidates += LitCandidate(sha, MapTrust.FIRST_PARTY)

        LitReconciler(lits, jobs, blobs, { true }, false, 1, Duration.ofMinutes(20)).reconcile()

        assertNull(lits.rows[sha])
        assertTrue(jobs.created.isEmpty())
    }

    private inner class FakeLits : MapLitRepository {
        val candidates = mutableListOf<LitCandidate>()
        val rows = mutableMapOf<String, LitRecord>()
        val facts = mutableMapOf<String, LitFacts>()
        val reasons = mutableMapOf<String, String>()
        val environments = mutableMapOf<String, List<String>>()
        val trust = mutableMapOf<String, MapTrust>()

        override fun lighting(format: Int) =
            rows.values.firstOrNull { it.state == LitState.LIGHTING }

        override fun nextCandidate(format: Int, maxAttempts: Int) = candidates.removeFirstOrNull()

        override fun start(bundleSha256: String, format: Int, attempt: UUID) {
            rows[bundleSha256] =
                LitRecord(
                    bundleSha256,
                    format,
                    LitState.LIGHTING,
                    attempt,
                    (rows[bundleSha256]?.attempts ?: 0) + 1,
                    null,
                    null,
                    startedAt,
                )
        }

        /** When the reconciler under test started the current attempt. */
        var startedAt: Instant = now

        override fun succeed(bundleSha256: String, format: Int, attempt: UUID, facts: LitFacts) {
            rows[bundleSha256] = rows.getValue(bundleSha256).copy(state = LitState.LIT)
            this.facts[bundleSha256] = facts
        }

        override fun fail(bundleSha256: String, format: Int, attempt: UUID, reason: String) {
            rows[bundleSha256] = rows.getValue(bundleSha256).copy(state = LitState.FAILED)
            reasons[bundleSha256] = reason
        }

        override fun environmentsPinning(bundleSha256: String) =
            environments[bundleSha256] ?: emptyList()

        override fun litCopies(format: Int) =
            rows.values.filter { it.state == LitState.LIT }.associate { it.bundleSha256 to litSha }

        override fun trustOf(bundleSha256: String) = trust[bundleSha256]
    }

    private class FakeJobs : LitJobGateway {
        val created = mutableMapOf<String, String>()
        var status: LitJobStatus? = LitJobStatus.RUNNING

        override fun create(name: String, requestJson: String) {
            created[name] = requestJson
        }

        override fun status(name: String) = if (name in created) status else null
    }

    private class FakeBlobs : LitBlobs {
        val objects = mutableMapOf<String, ByteArray>()
        val sizes = mutableMapOf<String, Long>()
        val promoted = mutableListOf<Triple<String, String, MapTrust>>()

        override fun sourceUrl(bundleSha256: String, trust: MapTrust) = "source:$bundleSha256"

        override fun workUrl(key: String) = "work:$key"

        override fun getPrivate(key: String, maxBytes: Long) =
            objects[key] ?: throw IllegalStateException("no $key")

        override fun headPrivate(key: String) = sizes[key]?.let { BlobMetadata(it) }

        override fun promote(
            sourceKey: String,
            destinationKey: String,
            sizeBytes: Long,
            trust: MapTrust,
        ) {
            promoted += Triple(sourceKey, destinationKey, trust)
        }
    }
}
