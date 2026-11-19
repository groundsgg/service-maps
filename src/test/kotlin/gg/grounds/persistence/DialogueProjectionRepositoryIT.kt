package gg.grounds.persistence

import gg.grounds.PostgresResource
import gg.grounds.domain.*
import io.quarkus.test.common.WithTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.util.UUID
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.*

@QuarkusTest
@WithTestResource(PostgresResource::class)
class DialogueProjectionRepositoryIT {
    @Inject lateinit var maps: MapRepository
    @Inject lateinit var versions: MapVersionRepository
    private val sha = "a".repeat(64)
    private val image = "worker@sha256:${"b".repeat(64)}"
    private fun candidate(): DeriveIdentity {
        val map = maps.create(MapAddress("dialogue", "test-${UUID.randomUUID()}"),"test",MapKind.ARENA,false,MapTrust.FIRST_PARTY,"builder")
        versions.commitWithDeriveRequest(map.id,sha,"tmp/source",true,null,null,"builder")
        val attempt = UUID.randomUUID()
        versions.claimForDerive(map.id,1,attempt)
        return DeriveIdentity(map.id,1,attempt,sha)
    }
    private fun facts(proof: DialogueProjection) = DerivedFacts("c".repeat(64),"d".repeat(64),2,null,null,
        SceneProjection(SceneStatus.NONE,null,null,null,null,emptyList(),emptyList()),proof)
    private fun noneProof() = DialogueProjection(DialogueStatus.NONE,DialogueProof(sha,image,DeriveExecutionContract(2,image,emptyList()).fingerprint()))

    @Test fun forkCopiesCompleteCheckedProof() {
        val id=candidate()
        val contract=DeriveExecutionContract(2,image,emptyList())
        versions.bindExecution(id,contract)
        versions.acceptSuccess(id,facts(noneProof()),"worker")
        val source=versions.find(id.mapId,id.version)!!
        val fork=maps.create(MapAddress("dialogue","fork-${UUID.randomUUID()}"),"fork",MapKind.ARENA,false,MapTrust.FIRST_PARTY,"builder",ForkOrigin(id.mapId,id.version),source)
        val copied=versions.find(fork.id,1)!!
        assertEquals(source.dialogue,copied.dialogue)
        assertEquals(source.executionContract,copied.executionContract)
        assertEquals(source.sourceSha256,copied.sourceSha256)
        assertEquals(source.manifestSha256,copied.manifestSha256)
        assertEquals(source.deriveContractLegacy,copied.deriveContractLegacy)
    }
    @Test fun unknownForkCannotBecomeChecked() {
        val id=candidate()
        versions.acceptSuccess(id,facts(DialogueProjection()),"legacy")
        val source=versions.find(id.mapId,id.version)!!
        val fork=maps.create(MapAddress("dialogue","old-${UUID.randomUUID()}"),"old",MapKind.ARENA,false,MapTrust.FIRST_PARTY,"builder",ForkOrigin(id.mapId,id.version),source)
        assertEquals(DialogueStatus.UNKNOWN,versions.find(fork.id,1)!!.dialogue.status)
    }

    @Test fun sameAttemptCannotChangeContract() {
        val id = candidate()
        val profile = DialogueProfilePin("test:host","1","9".repeat(64),10,java.net.URI("https://cdn.test/profile.json"))
        val contract = DeriveExecutionContract(2,image,listOf(profile))
        assertEquals(contract,versions.bindExecution(id,contract))
        assertEquals(contract,versions.find(id.mapId,id.version)!!.executionContract)
        assertEquals(contract,versions.bindExecution(id,contract))
        assertThrows<DeriveResultIntegrityException> {
            versions.bindExecution(id,DeriveExecutionContract(2,"other@sha256:${"e".repeat(64)}",emptyList()))
        }
    }
    @Test fun newManualRetryGetsOwnContract() {
        val id = candidate()
        versions.bindExecution(id,DeriveExecutionContract(2,image,emptyList()))
        versions.acceptFailure(id,DerivedFailure(DeriveFailureScope.SYSTEM,true,
            listOf(DeriveProblem(DeriveFailureScope.SYSTEM,null,"IO",null,"retry"))))
        val retry = versions.retrySystemFailure(id.mapId,id.version)
        assertNotEquals(id.attempt,retry.deriveAttempt)
        assertNull(retry.executionContract)
        assertFalse(retry.deriveContractLegacy)
        val next = DeriveExecutionContract(2,"next@sha256:${"e".repeat(64)}",emptyList())
        assertEquals(next,versions.bindExecution(id.copy(attempt=retry.deriveAttempt!!),next))
    }
    @Test fun sameProofDuplicateIsIdempotent() {
        val id = candidate()
        versions.bindExecution(id,DeriveExecutionContract(2,image,emptyList()))
        val facts = facts(noneProof())
        assertTrue(versions.acceptSuccessOutcome(id,facts,"worker").transitioned)
        assertFalse(versions.acceptSuccessOutcome(id,facts,"worker").transitioned)
        assertEquals(facts.dialogue,versions.find(id.mapId,id.version)!!.dialogue)
    }
    @Test fun changedProofDuplicateIsRejected() {
        val id = candidate()
        versions.bindExecution(id,DeriveExecutionContract(2,image,emptyList()))
        versions.acceptSuccess(id,facts(noneProof()),"worker")
        assertThrows<DeriveResultIntegrityException> {
            versions.acceptSuccess(id,facts(DialogueProjection()),"worker")
        }
    }

    @Test fun checkedProofRequiresBoundExecution() {
        val id = candidate()
        assertThrows<DeriveResultRejectedException> { versions.acceptSuccess(id,facts(noneProof()),"worker") }
    }
    @Test fun checkedProofCannotNameAnotherWorker() {
        val id = candidate()
        versions.bindExecution(id,DeriveExecutionContract(2,image,emptyList()))
        val wrong = DialogueProjection(DialogueStatus.NONE,DialogueProof(sha,"other@sha256:${"e".repeat(64)}",DeriveExecutionContract(2,image,emptyList()).fingerprint()))
        assertThrows<DeriveResultRejectedException> { versions.acceptSuccess(id,facts(wrong),"worker") }
    }
    @Test fun concurrentBindChoosesExactlyOneImmutableContract() {
        val id = candidate()
        val contracts = listOf(DeriveExecutionContract(2,image,emptyList()),DeriveExecutionContract(2,"other@sha256:${"e".repeat(64)}",emptyList()))
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            val start = java.util.concurrent.CountDownLatch(1)
            val futures = contracts.map { contract -> pool.submit(java.util.concurrent.Callable {
                start.await()
                runCatching { versions.bindExecution(id,contract) }
            }) }
            start.countDown()
            val results = futures.map { it.get(10,java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1,results.count { it.isSuccess })
            assertTrue(results.single { it.isFailure }.exceptionOrNull() is DeriveResultIntegrityException)
            assertEquals(results.single { it.isSuccess }.getOrThrow(),versions.find(id.mapId,id.version)!!.executionContract)
        }
    }

    @Test fun absenceProofCannotHideAnotherDetectionContext() {
        val id = candidate()
        val configured = DialogueProfilePin("test:host","1","9".repeat(64),10,java.net.URI("https://cdn.test/profile.json"))
        versions.bindExecution(id,DeriveExecutionContract(2,image,listOf(configured)))
        assertThrows<DeriveResultRejectedException> { versions.acceptSuccess(id,facts(noneProof()),"worker") }
    }
}
