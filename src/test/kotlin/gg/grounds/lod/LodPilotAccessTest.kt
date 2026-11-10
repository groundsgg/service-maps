package gg.grounds.lod

import gg.grounds.domain.*
import gg.grounds.lod.format.*
import jakarta.ws.rs.WebApplicationException
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class LodPilotAccessTest {
    private val now = Instant.parse("2026-11-08T14:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val token = "test-pilot-capability-" + "x".repeat(40)
    private val player = UUID.randomUUID()
    private val claim =
        LodClaim(
            UUID.randomUUID(),
            UUID.randomUUID(),
            MapTrust.FIRST_PARTY,
            "a".repeat(64),
            "b".repeat(64),
            "c".repeat(64),
            "d".repeat(64),
            "ghcr.io/test@sha256:${"e".repeat(64)}",
            now.minusSeconds(10),
            LodFacts("1".repeat(64), 4096, "2".repeat(64), 256, 1),
        )
    private val run =
        LodServingRun(
            UUID.randomUUID(),
            claim.attempt,
            digest(token.toByteArray()),
            setOf(player),
            now,
            now.plusSeconds(1800),
            "operator",
        )

    private class Repo(var row: LodServingSnapshot) : LodServingRepository {
        var debits = 0

        override fun register(run: LodServingRun) {}

        override fun find(runId: UUID) = row.takeIf { it.run.id == runId }

        override fun payload(
            runId: UUID,
            requestId: UUID,
            mapSha: String,
            player: UUID,
            bytes: Int,
            now: (() -> Instant)?,
        ): LodPayloadCredit {
            debits++
            return LodPayloadCredit(bytes, 900)
        }

        override fun remote(
            runId: UUID,
            requestId: UUID,
            mapSha: String,
            player: UUID,
            now: (() -> Instant)?,
        ): LodRemoteCredit {
            debits++
            return LodRemoteCredit(row, 120000)
        }

        override fun consumeRemote(runId: UUID, requestId: UUID, now: (() -> Instant)?) = row
    }

    private val repo = Repo(LodServingSnapshot(run, claim, 0, 0, 0))

    private val origin = LodPilotOrigin { ByteArrayInputStream(ByteArray(4096)) }

    @Test
    fun `unknown disabled wrong and expired capabilities cannot authorize a serving run`() {
        val auth = LodPilotAuthorizer(repo, true, clock)
        for ((id, secret) in
            listOf(
                run.id.toString() to null,
                run.id.toString() to "z".repeat(60),
                UUID.randomUUID().toString() to token,
            )) {
            assertEquals(
                403,
                assertFailsWith<WebApplicationException> { auth.authorize(id, secret) }
                    .response
                    .status,
            )
        }
        assertEquals(
            403,
            assertFailsWith<WebApplicationException> {
                    LodPilotAuthorizer(repo, false, clock).authorize(run.id.toString(), token)
                }
                .response
                .status,
        )
        repo.row = repo.row.copy(run = run.copy(enabled = false))
        assertEquals(
            410,
            assertFailsWith<WebApplicationException> { auth.authorize(run.id.toString(), token) }
                .response
                .status,
        )
        assertEquals(0, repo.debits)
    }

    @Test
    fun `source capability stays private exact source bound and short lived`() {
        val auth = LodPilotAuthorizer(repo, true, clock)
        val resource = LodPilotResource(repo, origin, clock)
        val request =
            LodSourceRequest(
                requestId = UUID.randomUUID().toString(),
                mapBundleSha256 = claim.mapBundleSha256,
                playerId = player.toString(),
            )
        val response = resource.sourceGrant(auth.authorize(run.id.toString(), token), request)
        assertEquals(200, response.status)
        val source = response.entity as LodSourceGrant
        source.validate()
        assertEquals(request.requestId, source.requestId)
        assertEquals(run.id.toString(), source.runId)
        assertEquals(claim.mapBundleSha256, source.mapBundleSha256)
        assertEquals(claim.sourceBundleSha256, source.sourceBundleSha256)
        assertEquals(claim.contentTimestampMillis, source.contentTimestampMillis)
        assertEquals("/v1/maps/lod-pilots/${run.id}/source/${request.requestId}", source.bundleUrl)
        assertTrue(response.getHeaderString("Cache-Control").contains("no-store"))
        assertFalse(source.bundleUrl.contains(token))
        assertEquals(1, repo.debits)
    }

    @Test
    fun `malformed credit requests cannot debit a budget`() {
        val resource = LodPilotResource(repo, origin, clock)
        val valid =
            LodPayloadRequest(
                requestId = UUID.randomUUID().toString(),
                mapBundleSha256 = claim.mapBundleSha256,
                playerId = player.toString(),
                bytes = 1024,
            )
        for (bad in
            listOf(
                valid.copy(bytes = 0),
                valid.copy(bytes = 262145),
                valid.copy(requestId = "replay"),
                valid.copy(mapBundleSha256 = "../source"),
            )) {
            assertEquals(400, resource.payloadGrant(repo.row, bad).status)
        }
        assertEquals(0, repo.debits)
        val response = resource.payloadGrant(repo.row, valid)
        assertEquals(200, response.status)
        (response.entity as LodPayloadGrant).validate()
        assertEquals(1, repo.debits)
    }
}
