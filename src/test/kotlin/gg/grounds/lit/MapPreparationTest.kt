package gg.grounds.lit

import gg.grounds.blob.*
import gg.grounds.domain.*
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MapPreparationTest {
    private val sha = "a".repeat(64)
    private val litSha = "b".repeat(64)
    private val lits = Lits()
    private val objects = Objects()
    private val bundles = PublishedBundles(objects)
    private val policy = MapLightPolicy(true, true, 2)
    private val preparation = MapPreparation(lits, bundles, policy)

    @Test
    fun `raw publication does not mean ready and all preparation stages remain observable`() {
        assertEquals("QUEUED", preparation.status(version(VersionState.DRAFT)).stage)
        assertEquals("DERIVING", preparation.status(version(VersionState.DERIVING)).stage)
        assertEquals("AWAITING_REVIEW", preparation.status(version(VersionState.IN_REVIEW)).stage)
        assertEquals("WAITING_FOR_LIGHT", preparation.status(version()).stage)
        lits.row = record(LitState.LIGHTING)
        assertEquals("LIGHTING", preparation.status(version()).stage)
        lits.row = record(LitState.LIT)
        assertEquals("WAITING_FOR_LIGHT", preparation.status(version()).stage)
        objects.values[sha to MapTrust.FIRST_PARTY] = BlobMetadata(100)
        objects.values[litSha to MapTrust.FIRST_PARTY] = BlobMetadata(1000)
        assertEquals("READY", preparation.status(version()).stage)
    }

    @Test
    fun `retryable failures remain queued while exhausted failures have safe details`() {
        lits.row =
            record(LitState.FAILED).copy(attempts = 2, failure = "https://secret/?token=private")
        assertEquals("WAITING_FOR_LIGHT", preparation.status(version()).stage)
        lits.row = lits.row!!.copy(attempts = 3)

        val status = preparation.status(version())

        assertEquals("FAILED", status.stage)
        assertEquals("LIGHTING_FAILED", status.failureCode)
        assertFalse(status.toString().contains("secret"))
        assertFalse(status.toString().contains("private"))
    }

    @Test
    fun `disabled preparation and old formats cannot claim readiness`() {
        val disabled = MapPreparation(lits, bundles, MapLightPolicy(true, false, 2))
        assertEquals("UNAVAILABLE", disabled.status(version()).stage)
        assertEquals("UNAVAILABLE", disabled.status(version(VersionState.DRAFT)).stage)
        lits.row = record(LitState.LIT).copy(lightFormat = 1)
        assertEquals("WAITING_FOR_LIGHT", preparation.status(version()).stage)
        assertEquals(2, preparation.status(version()).lightFormat)
    }

    @Test
    fun `a cached copy in another trust bucket is insufficient for readiness`() {
        lits.row = record(LitState.LIT)
        objects.values[sha to MapTrust.FIRST_PARTY] = BlobMetadata(100)
        objects.values[litSha to MapTrust.FIRST_PARTY] = BlobMetadata(1000)
        assertEquals(
            "WAITING_FOR_LIGHT",
            preparation.status(version(trust = MapTrust.UNTRUSTED)).stage,
        )

        bundles.ensure(sha, MapTrust.UNTRUSTED, 100)
        bundles.ensure(litSha, MapTrust.UNTRUSTED, 1000)

        assertEquals("READY", preparation.status(version(trust = MapTrust.UNTRUSTED)).stage)
    }

    @Test
    fun `derivation refusal takes precedence over disabled lighting`() {
        val status =
            MapPreparation(lits, bundles, MapLightPolicy(false, false, 2))
                .status(version(VersionState.DERIVE_FAILED))
        assertEquals("FAILED", status.stage)
        assertEquals("DERIVATION_FAILED", status.failureCode)
    }

    @Test
    fun `forks retain raw and cached light delivery when lighting is disabled`() {
        val disabled = MapPreparation(lits, bundles, MapLightPolicy(true, false, 2))
        objects.values[sha to MapTrust.FIRST_PARTY] = BlobMetadata(100)
        objects.values[litSha to MapTrust.FIRST_PARTY] = BlobMetadata(1000)
        lits.row = record(LitState.LIT)

        disabled.ensureDelivery(version(), MapTrust.UNTRUSTED)

        assertEquals(BlobMetadata(100), objects.values[sha to MapTrust.UNTRUSTED])
        assertEquals(BlobMetadata(1000), objects.values[litSha to MapTrust.UNTRUSTED])
        assertEquals("UNAVAILABLE", disabled.status(version(trust = MapTrust.UNTRUSTED)).stage)
    }

    private fun record(state: LitState) =
        LitRecord(sha, 2, state, UUID.randomUUID(), 1, litSha, 1000, Instant.EPOCH)

    private fun version(
        state: VersionState = VersionState.PUBLISHED,
        trust: MapTrust = MapTrust.FIRST_PARTY,
    ) =
        MapVersionRecord(
            UUID.randomUUID(),
            7,
            state,
            sha,
            sha,
            null,
            null,
            null,
            100,
            null,
            null,
            null,
            null,
            false,
            SceneProjection(SceneStatus.NONE, null, null, null, null, emptyList(), emptyList()),
            "builder",
            null,
            Instant.EPOCH,
            true,
            trust,
        )

    private class Objects : PublicBundleObjects {
        val values = mutableMapOf<Pair<String, MapTrust>, BlobMetadata>()

        override fun head(sha256: String, trust: MapTrust) = values[sha256 to trust]

        override fun copy(sha256: String, source: MapTrust, target: MapTrust, sizeBytes: Long) {
            values[sha256 to target] = values.getValue(sha256 to source)
        }
    }

    private class Lits : MapLitRepository {
        var row: LitRecord? = null

        override fun find(bundleSha256: String, format: Int) =
            row?.takeIf { it.bundleSha256 == bundleSha256 && it.lightFormat == format }

        override fun lighting(format: Int) =
            row?.takeIf { it.lightFormat == format && it.state == LitState.LIGHTING }

        override fun nextCandidate(format: Int, maxAttempts: Int): LitCandidate? = null

        override fun publishedTrusts(bundleSha256: String) = listOf(MapTrust.FIRST_PARTY)

        override fun publishedCopies(format: Int) = emptyList<PublishedLitCopy>()

        override fun start(bundleSha256: String, format: Int, attempt: UUID) = error("unused")

        override fun succeed(bundleSha256: String, format: Int, attempt: UUID, facts: LitFacts) =
            error("unused")

        override fun fail(bundleSha256: String, format: Int, attempt: UUID, reason: String) =
            error("unused")

        override fun environmentsPinning(bundleSha256: String) = emptyList<String>()

        override fun litCopies(format: Int) = emptyMap<String, String>()

        override fun trustOf(bundleSha256: String) = MapTrust.FIRST_PARTY
    }
}
