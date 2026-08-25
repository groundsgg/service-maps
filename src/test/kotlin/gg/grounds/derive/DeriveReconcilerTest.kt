package gg.grounds.derive

import gg.grounds.blob.BlobCopyPreconditionException
import gg.grounds.blob.BlobMetadata
import gg.grounds.blob.BlobStore
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveIdentity
import gg.grounds.domain.DeriveProblem
import gg.grounds.domain.DeriveResultIntegrityException
import gg.grounds.domain.DerivedFacts
import gg.grounds.domain.DerivedFailure
import gg.grounds.domain.MapVersionRecord
import gg.grounds.domain.MapVersionRepository
import gg.grounds.domain.SceneProjection
import gg.grounds.domain.SceneStatus
import gg.grounds.domain.VersionState
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class DeriveReconcilerTest {
    @Test
    fun `strict manifest parser rejects malformed bytes whose claimed facts match`() {
        val version = record(VersionState.DERIVING)
        val malformed = "not-json".encodeToByteArray()
        val result =
            success(version)
                .copy(manifestSha256 = sha256(malformed), manifestSize = malformed.size.toLong())
        val events = mutableListOf<String>()
        val artifacts =
            FakeArtifacts(CanonicalJson.write(result), events).assigned(version).apply {
                complete(result)
                manifest = malformed
                sizes["manifest"] = BlobMetadata(malformed.size.toLong())
            }
        val versions = FakeVersions(listOf(version), events = events)
        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                artifacts,
            )
            .reconcile()
        assertEquals("RESULT_UNAVAILABLE", versions.failures.single().second.problems.single().code)
        assertTrue(events.none { it.startsWith("promote:") || it == "accept" })
    }

    @Test
    fun `promotion precondition conflict records retryable system failure after manifest validation`() {
        val version = record(VersionState.DERIVING)
        val result = success(version)
        val events = mutableListOf<String>()
        val artifacts =
            FakeArtifacts(CanonicalJson.write(result), events).assigned(version).apply {
                complete(result)
                promotionFailure = BlobCopyPreconditionException("conflict")
            }
        val versions = FakeVersions(listOf(version), events = events)
        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                artifacts,
            )
            .reconcile()
        assertTrue(versions.successes.isEmpty())
        assertEquals(DeriveFailureScope.SYSTEM, versions.failures.single().second.scope)
        assertTrue(versions.failures.single().second.retryable)
        assertTrue(events.any { it.startsWith("get:") && it.contains("derived-manifest.json") })
        assertTrue(events.none { it == "accept" })
    }

    /** Each row isolates one value that must remain bound to the assigned worker request. */
    @TestFactory
    fun `success result identity mutations are rejected one at a time`() =
        Stream.of(
                "map" to { result: DeriveSuccess -> result.copy(mapId = UUID.randomUUID()) },
                "version" to { result: DeriveSuccess -> result.copy(version = result.version + 1) },
                "attempt" to { result: DeriveSuccess -> result.copy(attempt = UUID.randomUUID()) },
                "source" to { result: DeriveSuccess -> result.copy(sourceSha256 = "d".repeat(64)) },
            )
            .map { (name, mutate) ->
                DynamicTest.dynamicTest(name) {
                    val version = record(VersionState.DERIVING)
                    assertUnavailable(version, mutate(success(version)))
                }
            }

    @TestFactory
    fun `failure result identity mutations are rejected one at a time`() =
        Stream.of(
                "map" to { value: DeriveFailure -> value.copy(mapId = UUID.randomUUID()) },
                "version" to { value: DeriveFailure -> value.copy(version = value.version + 1) },
                "attempt" to { value: DeriveFailure -> value.copy(attempt = UUID.randomUUID()) },
                "source" to { value: DeriveFailure -> value.copy(sourceSha256 = "d".repeat(64)) },
            )
            .map { (name, mutate) ->
                DynamicTest.dynamicTest(name) {
                    val version = record(VersionState.DERIVING)
                    val failure = failure(version)
                    val versions = FakeVersions(listOf(version))
                    reconciler(
                            versions,
                            FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                            FakeArtifacts(CanonicalJson.write(mutate(failure))).assigned(version),
                        )
                        .reconcile()
                    assertEquals(
                        "RESULT_UNAVAILABLE",
                        versions.failures.single().second.problems.single().code,
                    )
                }
            }

    @TestFactory
    fun `result marker mutations are rejected one at a time`() =
        Stream.of(
                "missing" to { _: MapVersionRecord, _: DeriveSuccess -> null },
                "oversized" to { _: MapVersionRecord, _: DeriveSuccess -> ByteArray(1_048_577) },
                "malformed" to
                    { _: MapVersionRecord, _: DeriveSuccess ->
                        "not-json".encodeToByteArray()
                    },
            )
            .map { (name, marker) ->
                DynamicTest.dynamicTest(name) {
                    val version = record(VersionState.DERIVING)
                    assertUnavailable(version, success(version), marker(version, success(version)))
                }
            }

    @TestFactory
    fun `manifest and bundle mutations are rejected one at a time`() =
        Stream.of(
                "manifest missing" to { a: FakeArtifacts, _: DeriveSuccess -> a.manifest = null },
                "manifest oversized" to
                    { a: FakeArtifacts, _: DeriveSuccess ->
                        a.manifest = ByteArray(4_194_305)
                    },
                "manifest malformed" to
                    { a: FakeArtifacts, _: DeriveSuccess ->
                        a.manifest = "not-json".encodeToByteArray()
                    },
                "manifest metadata size" to
                    { a: FakeArtifacts, r: DeriveSuccess ->
                        a.sizes["manifest"] = BlobMetadata(r.manifestSize + 1)
                    },
                "manifest body size" to
                    { a: FakeArtifacts, _: DeriveSuccess ->
                        a.manifest = "{}".encodeToByteArray()
                    },
                "manifest sha" to
                    { a: FakeArtifacts, _: DeriveSuccess ->
                        a.manifest =
                            a.manifest!!.clone().also { bytes ->
                                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                            }
                    },
                "bundle missing" to
                    { a: FakeArtifacts, _: DeriveSuccess ->
                        a.sizes.remove("bundle")
                    },
                "bundle wrong size" to
                    { a: FakeArtifacts, r: DeriveSuccess ->
                        a.sizes["bundle"] = BlobMetadata(r.bundleSize + 1)
                    },
            )
            .map { (name, mutate) ->
                DynamicTest.dynamicTest(name) {
                    val version = record(VersionState.DERIVING)
                    val result = success(version)
                    val events = mutableListOf<String>()
                    val artifacts =
                        FakeArtifacts(CanonicalJson.write(result), events).assigned(version).apply {
                            complete(result)
                            mutate(this, result)
                        }
                    val versions = FakeVersions(listOf(version), events = events)
                    reconciler(
                            versions,
                            FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                            artifacts,
                        )
                        .reconcile()
                    assertEquals(
                        "RESULT_UNAVAILABLE",
                        versions.failures.single().second.problems.single().code,
                    )
                    assertTrue(events.none { it.startsWith("promote:") || it == "accept" })
                }
            }

    @TestFactory
    fun `validated manifest fields stay bound to the success result`() =
        Stream.of("source", "scene", "catalog").map { field ->
            DynamicTest.dynamicTest(field) {
                val version = record(VersionState.DERIVING)
                val baseline = success(version, catalogScene("catalog"))
                val changedScene =
                    when (field) {
                        "source" -> baseline.scene
                        "scene" -> catalogScene("catalog", sha = "f".repeat(64))
                        else -> catalogScene("other")
                    }
                val changedSource = if (field == "source") "d".repeat(64) else baseline.sourceSha256
                val changedManifest = manifest(baseline, changedSource, changedScene)
                val result =
                    baseline.copy(
                        manifestSha256 = sha256(changedManifest),
                        manifestSize = changedManifest.size.toLong(),
                    )
                val events = mutableListOf<String>()
                val artifacts =
                    FakeArtifacts(CanonicalJson.write(result), events).assigned(version).apply {
                        complete(result)
                        manifest = changedManifest
                        sizes["manifest"] = BlobMetadata(changedManifest.size.toLong())
                    }
                val versions = FakeVersions(listOf(version), events = events)
                reconciler(
                        versions,
                        FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                        artifacts,
                    )
                    .reconcile()
                assertEquals(
                    "RESULT_UNAVAILABLE",
                    versions.failures.single().second.problems.single().code,
                )
                assertTrue(events.none { it.startsWith("promote:") || it == "accept" })
            }
        }

    @Test
    fun `missing Job is recreated while a running Job is left alone`() {
        val missing = record(VersionState.DERIVING)
        val running = record(VersionState.DERIVING)
        val versions = FakeVersions(listOf(missing, running))
        val jobs =
            FakeJobs(
                mapOf(
                    missing.identity() to DeriveJobStatus.MISSING,
                    running.identity() to DeriveJobStatus.RUNNING,
                )
            )
        reconciler(versions, jobs, FakeArtifacts()).reconcile()
        assertEquals(listOf(missing.identity()), jobs.created.map { it.identity })
        assertEquals(2, jobs.findCalls)
        assertTrue(versions.failures.isEmpty())
    }

    @Test
    fun `failed Job is SYSTEM and completed failures are never retried automatically`() {
        val deriving = record(VersionState.DERIVING)
        val system =
            record(VersionState.DERIVE_FAILED)
                .copy(deriveFailureScope = DeriveFailureScope.SYSTEM, deriveRetryable = true)
        val content =
            record(VersionState.DERIVE_FAILED).copy(deriveFailureScope = DeriveFailureScope.CONTENT)
        val versions = FakeVersions(listOf(deriving, system, content))
        reconciler(
                versions,
                FakeJobs(mapOf(deriving.identity() to DeriveJobStatus.FAILED)),
                FakeArtifacts(),
            )
            .reconcile()
        assertEquals("JOB_FAILED", versions.failures.single().second.problems.single().code)
        assertTrue(versions.retried.isEmpty())
    }

    @Test
    fun `succeeded Job accepts only complete identity-matching result markers`() {
        val cases = listOf<ByteArray?>(null, "bad json".encodeToByteArray(), null)
        cases.forEachIndexed { index, marker ->
            val version = record(VersionState.DERIVING)
            val result =
                success(version).let { if (index == 2) it.copy(mapId = UUID.randomUUID()) else it }
            val artifacts =
                FakeArtifacts(marker ?: if (index == 2) CanonicalJson.write(result) else null)
                    .apply { complete(result) }
            val versions = FakeVersions(listOf(version))
            reconciler(
                    versions,
                    FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                    artifacts,
                )
                .reconcile()
            assertEquals(
                "RESULT_UNAVAILABLE",
                versions.failures.single().second.problems.single().code,
            )
        }
        val version = record(VersionState.DERIVING)
        val result = success(version)
        val artifacts = FakeArtifacts(CanonicalJson.write(result)).apply { complete(result) }
        val versions = FakeVersions(listOf(version))
        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                artifacts,
            )
            .reconcile()
        assertEquals(1, versions.successes.size)
    }

    @Test
    fun `stale duplicate acceptance is harmless`() {
        val version = record(VersionState.DERIVING)
        val result = success(version)
        val versions = FakeVersions(listOf(version), rejectSuccess = true)
        val artifacts = FakeArtifacts(CanonicalJson.write(result)).apply { complete(result) }
        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                artifacts,
            )
            .reconcile()
        assertTrue(versions.failures.isEmpty())
    }

    @Test
    fun `stale success is promoted exactly before acceptance rejects it`() {
        val version = record(VersionState.DERIVING)
        val result = success(version)
        val events = mutableListOf<String>()
        val artifacts =
            FakeArtifacts(CanonicalJson.write(result), events).assigned(version).apply {
                complete(result)
            }
        val versions = FakeVersions(listOf(version), rejectSuccess = true, events = events)
        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                artifacts,
            )
            .reconcile()
        assertEquals(
            listOf(
                "get:${BlobStore.deriveResultKey(version.mapId, version.version, requireNotNull(version.deriveAttempt))}:1048576",
                "head:${BlobStore.deriveBundleKey(version.mapId, version.version, requireNotNull(version.deriveAttempt))}",
                "head:${BlobStore.deriveManifestKey(version.mapId, version.version, requireNotNull(version.deriveAttempt))}",
                "get:${BlobStore.deriveManifestKey(version.mapId, version.version, requireNotNull(version.deriveAttempt))}:4194304",
                "promote:${BlobStore.deriveBundleKey(version.mapId, version.version, requireNotNull(version.deriveAttempt))}:${BlobStore.bundleKey(result.bundleSha256)}:${result.bundleSize}",
            ),
            events,
        )
        assertEquals(1, artifacts.promotions.size)
    }

    @Test
    fun `accepted success carries the result asset catalog identity`() {
        val version = record(VersionState.DERIVING)
        val result = success(version, catalogScene("assets"))
        val versions = FakeVersions(listOf(version))
        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                FakeArtifacts(CanonicalJson.write(result)).assigned(version).apply {
                    complete(result)
                },
            )
            .reconcile()
        assertEquals(
            gg.grounds.domain.CatalogReference("assets", "1"),
            versions.successes.single().first.assetCatalog,
        )
    }

    @Test
    fun `integrity exception while recording unavailable result is not treated as stale`() {
        val version = record(VersionState.DERIVING)
        val versions = FakeVersions(listOf(version), failureIntegrity = true)
        assertThrows(DeriveResultIntegrityException::class.java) {
            reconciler(
                    versions,
                    FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                    FakeArtifacts("not-json".encodeToByteArray()).assigned(version),
                )
                .reconcile()
        }
    }

    @Test
    fun `promotes the assigned bundle before accepting a success result`() {
        val version = record(VersionState.DERIVING)
        val result = success(version)
        val events = mutableListOf<String>()
        val artifacts =
            FakeArtifacts(CanonicalJson.write(result), events).apply { complete(result) }
        val versions = FakeVersions(listOf(version), events = events)

        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                artifacts,
            )
            .reconcile()

        assertEquals(
            listOf(
                "get:${BlobStore.deriveResultKey(version.mapId, version.version, requireNotNull(version.deriveAttempt))}:1048576",
                "head:${BlobStore.deriveBundleKey(version.mapId, version.version, requireNotNull(version.deriveAttempt))}",
                "head:${BlobStore.deriveManifestKey(version.mapId, version.version, requireNotNull(version.deriveAttempt))}",
                "get:${BlobStore.deriveManifestKey(version.mapId, version.version, requireNotNull(version.deriveAttempt))}:4194304",
                "promote:${BlobStore.deriveBundleKey(version.mapId, version.version, requireNotNull(version.deriveAttempt))}:${BlobStore.bundleKey(result.bundleSha256)}:${result.bundleSize}",
                "accept",
            ),
            events,
        )
    }

    @Test
    fun `promotion failure leaves successful result unaccepted and records retryable system failure`() {
        val version = record(VersionState.DERIVING)
        val result = success(version)
        val artifacts =
            FakeArtifacts(CanonicalJson.write(result)).apply {
                complete(result)
                promotionFailure = IllegalStateException("copy failed")
            }
        val versions = FakeVersions(listOf(version))
        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                artifacts,
            )
            .reconcile()
        assertTrue(versions.successes.isEmpty())
        assertEquals("RESULT_UNAVAILABLE", versions.failures.single().second.problems.single().code)
    }

    @Test
    fun `integrity conflicts are not swallowed as stale completions`() {
        val version = record(VersionState.DERIVING)
        val result = success(version)
        val artifacts = FakeArtifacts(CanonicalJson.write(result)).apply { complete(result) }
        val versions = FakeVersions(listOf(version), integrityFailure = true)
        assertThrows(DeriveResultIntegrityException::class.java) {
            reconciler(
                    versions,
                    FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                    artifacts,
                )
                .reconcile()
        }
    }

    @Test
    fun `failure marker with another attempt is rejected as a system result failure`() {
        val version = record(VersionState.DERIVING)
        val result =
            DeriveFailure(
                mapId = version.mapId,
                version = version.version,
                attempt = UUID.randomUUID(),
                sourceSha256 = requireNotNull(version.sourceSha256),
                scope = DeriveFailureScope.CONTENT,
                retryable = false,
                problems =
                    listOf(DeriveProblem(DeriveFailureScope.CONTENT, null, "INVALID", null, "bad")),
            )
        val versions = FakeVersions(listOf(version))

        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                FakeArtifacts(CanonicalJson.write(result)),
            )
            .reconcile()

        assertEquals("RESULT_UNAVAILABLE", versions.failures.single().second.problems.single().code)
    }

    @Test
    fun `a stale rejection while recording a malformed result is a no-op`() {
        val version = record(VersionState.DERIVING)
        val versions = FakeVersions(listOf(version), rejectFailure = true)

        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                FakeArtifacts("not-json".encodeToByteArray()),
            )
            .reconcile()

        assertTrue(versions.failures.isEmpty())
    }

    @Test
    fun `a manifest digest mismatch prevents promotion and acceptance`() {
        val version = record(VersionState.DERIVING)
        val result = success(version).copy(manifestSha256 = "d".repeat(64))
        val events = mutableListOf<String>()
        val artifacts =
            FakeArtifacts(CanonicalJson.write(result), events).apply { complete(success(version)) }
        val versions = FakeVersions(listOf(version), events = events)

        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                artifacts,
            )
            .reconcile()

        assertTrue(events.none { it.startsWith("promote:") || it == "accept" })
        assertEquals("RESULT_UNAVAILABLE", versions.failures.single().second.problems.single().code)
    }

    @Test
    fun `a wrong bundle metadata size prevents promotion and acceptance`() {
        val version = record(VersionState.DERIVING)
        val result = success(version)
        val events = mutableListOf<String>()
        val artifacts =
            FakeArtifacts(CanonicalJson.write(result), events).apply {
                complete(result)
                sizes["bundle"] = BlobMetadata(result.bundleSize + 1)
            }
        val versions = FakeVersions(listOf(version), events = events)

        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                artifacts,
            )
            .reconcile()

        assertTrue(events.none { it.startsWith("promote:") || it == "accept" })
        assertEquals("RESULT_UNAVAILABLE", versions.failures.single().second.problems.single().code)
    }

    @Test
    fun `overlapping triggers create at most one logical Job`() {
        val versions = FakeVersions(listOf(record(VersionState.DRAFT).copy(deriveAttempt = null)))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val jobs =
            FakeJobs(
                onCreate = {
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                }
            )
        val reconciler = reconciler(versions, jobs, FakeArtifacts())
        Executors.newFixedThreadPool(2).use { pool ->
            val first = pool.submit { reconciler.reconcile() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val overlapping = pool.submit { reconciler.reconcile() }
            release.countDown()
            first.get(2, TimeUnit.SECONDS)
            overlapping.get(2, TimeUnit.SECONDS)
        }
        assertEquals(1, versions.claims)
        assertEquals(1, jobs.created.size)
    }

    @Test
    fun `retry delay is bounded exponential`() {
        assertEquals(java.time.Duration.ofSeconds(5), DeriveReconciler.retryDelay(0))
        assertEquals(java.time.Duration.ofSeconds(30), DeriveReconciler.retryDelay(1))
        assertEquals(java.time.Duration.ofMinutes(2), DeriveReconciler.retryDelay(99))
    }

    @Test
    fun `watch reconnects progress until a derive event resets the backoff`() {
        val scheduler = FakeScheduler()
        val jobs = FakeJobs()
        val reconciler = reconciler(FakeVersions(emptyList()), jobs, FakeArtifacts(), scheduler)

        reconciler.onStart(io.quarkus.runtime.StartupEvent())
        jobs.watches[0].remoteClose()
        scheduler.runNext()
        jobs.watches[1].remoteClose()

        assertEquals(listOf(Duration.ofSeconds(5), Duration.ofSeconds(30)), scheduler.delays)

        scheduler.runNext()
        jobs.watches[2].event()
        jobs.watches[2].remoteClose()

        assertEquals(
            listOf(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(5)),
            scheduler.delays,
        )
    }

    @Test
    fun `stale watch close and queued reconnect cannot disturb a newer watch or shutdown`() {
        val scheduler = FakeScheduler()
        val jobs = FakeJobs()
        val reconciler = reconciler(FakeVersions(emptyList()), jobs, FakeArtifacts(), scheduler)

        reconciler.onStart(io.quarkus.runtime.StartupEvent())
        jobs.watches[0].remoteClose()
        scheduler.runNext()
        assertEquals(2, jobs.watches.size)

        jobs.watches[0].remoteClose()
        assertEquals(listOf(Duration.ofSeconds(5)), scheduler.delays)

        jobs.watches[1].remoteClose()
        reconciler.close()
        scheduler.runNext()
        assertEquals(2, jobs.watches.size)
    }

    @Test
    fun `transient candidate failures use the exact nonblocking retry sequence before terminal failure`() {
        val version = record(VersionState.DERIVING)
        val clock = FakeScheduler()
        val versions = FakeVersions(listOf(version))
        val jobs = FakeJobs(failFinds = 4)
        val reconciler = reconciler(versions, jobs, FakeArtifacts(), clock)

        reconciler.reconcile()
        assertEquals(listOf(Duration.ofSeconds(5)), clock.delays)
        clock.runNext()
        assertEquals(listOf(Duration.ofSeconds(5), Duration.ofSeconds(30)), clock.delays)
        clock.runNext()
        assertEquals(
            listOf(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2)),
            clock.delays,
        )
        clock.runNext()
        assertEquals(
            "RECONCILIATION_UNAVAILABLE",
            versions.failures.single().second.problems.single().code,
        )
    }

    @Test
    fun `pending retry prevents a poll from reprocessing the same identity`() {
        val version = record(VersionState.DERIVING)
        val scheduler = FakeScheduler()
        val jobs = FakeJobs(failFinds = 1)
        val reconciler = reconciler(FakeVersions(listOf(version)), jobs, FakeArtifacts(), scheduler)

        reconciler.reconcile()
        reconciler.reconcile()

        assertEquals(1, jobs.findCalls)
        assertEquals(listOf(Duration.ofSeconds(5)), scheduler.delays)
    }

    @Test
    fun `draft Job creation failure enters the retry gate using the claimed attempt`() {
        val draft = record(VersionState.DRAFT).copy(deriveAttempt = null)
        val scheduler = FakeScheduler()
        val jobs = FakeJobs(failCreates = 1)
        val versions = FakeVersions(listOf(draft))

        reconciler(versions, jobs, FakeArtifacts(), scheduler).reconcile()

        assertEquals(1, versions.claims)
        assertEquals(listOf(Duration.ofSeconds(5)), scheduler.delays)
    }

    @Test
    fun `retry scheduling failure does not prevent a later candidate from reconciling`() {
        val failing = record(VersionState.DERIVING)
        val later = record(VersionState.DERIVING)
        val jobs = FakeJobs(mapOf(later.identity() to DeriveJobStatus.MISSING), failFinds = 1)
        val versions = FakeVersions(listOf(failing, later))
        val scheduler = FakeScheduler(failSchedules = 1)
        val reconciler = reconciler(versions, jobs, FakeArtifacts(), scheduler)

        reconciler.reconcile()

        assertEquals(listOf(later.identity()), jobs.created.map { it.identity })
        reconciler.reconcile()
        assertTrue(jobs.created.any { it.identity == failing.identity() })
    }

    @Test
    fun `due callback colliding with an active batch prompts one post-batch reconciliation`() {
        val due = record(VersionState.DERIVING)
        val blocker = record(VersionState.DERIVING)
        val scheduler = FakeScheduler()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockActiveBatch = AtomicBoolean(false)
        val jobs =
            FakeJobs(
                mapOf(blocker.identity() to DeriveJobStatus.RUNNING),
                failFinds = 1,
                onFind = { identity ->
                    if (blockActiveBatch.get() && identity == blocker.identity()) {
                        entered.countDown()
                        check(release.await(2, TimeUnit.SECONDS))
                    }
                },
            )
        val reconciler =
            reconciler(FakeVersions(listOf(due, blocker)), jobs, FakeArtifacts(), scheduler)

        reconciler.reconcile()
        blockActiveBatch.set(true)
        Executors.newSingleThreadExecutor().use { executor ->
            val active = executor.submit { reconciler.reconcile() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            scheduler.runNext()
            release.countDown()
            active.get(2, TimeUnit.SECONDS)
        }
        scheduler.runNext()

        assertEquals(5, jobs.findCalls)
        assertEquals(listOf(due.identity()), jobs.created.map { it.identity })
        assertEquals(listOf(Duration.ofSeconds(5), Duration.ZERO), scheduler.delays)
    }

    @Test
    fun `throwing retry metrics do not strand the retry or later candidate`() {
        val failing = record(VersionState.DERIVING)
        val later = record(VersionState.DERIVING)
        val jobs = FakeJobs(mapOf(later.identity() to DeriveJobStatus.MISSING), failFinds = 1)
        val scheduler = FakeScheduler()
        val metrics =
            object : DeriveReconciliationObserver {
                override fun retry() = error("metrics unavailable")

                override fun activeCandidates(count: Int) = Unit

                override fun repair() = Unit
            }

        reconciler(FakeVersions(listOf(failing, later)), jobs, FakeArtifacts(), scheduler, metrics)
            .reconcile()

        assertEquals(listOf(Duration.ofSeconds(5)), scheduler.delays)
        assertEquals(listOf(later.identity()), jobs.created.map { it.identity })
    }

    @Test
    fun `reconciliation records closed-label outcomes duration and resets candidate gauge`() {
        val registry = SimpleMeterRegistry()
        val version = record(VersionState.DERIVING)
        val result = success(version)
        reconciler(
                FakeVersions(listOf(version)),
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                FakeArtifacts(CanonicalJson.write(result)).apply { complete(result) },
                FakeScheduler(),
                DeriveReconciliationMetrics(registry),
            )
            .reconcile()

        val labels = registry.meters.flatMap { it.id.tags }.map { it.key to it.value }
        assertTrue(
            labels.all { (key, value) ->
                key in setOf("outcome", "scope", "trigger") &&
                    !value.contains("token", ignoreCase = true) &&
                    !value.contains("http", ignoreCase = true)
            }
        )
        assertEquals(
            1.0,
            registry
                .counter("derive.reconciliation.attempts", "outcome", "succeeded", "scope", "none")
                .count(),
        )
        assertEquals(0.0, registry.get("derive.reconciliation.active_candidates").gauge().value())
        assertEquals(
            1L,
            registry.timer("derive.reconciliation.duration", "trigger", "reconcile").count(),
        )
    }

    @Test
    fun `reconciliation resets candidate gauge when candidate loading fails`() {
        val registry = SimpleMeterRegistry()
        val reconciler =
            reconciler(
                FakeVersions(emptyList(), failList = true),
                FakeJobs(),
                FakeArtifacts(),
                FakeScheduler(),
                DeriveReconciliationMetrics(registry),
            )

        assertThrows(IllegalStateException::class.java) { reconciler.reconcile() }

        assertEquals(0.0, registry.get("derive.reconciliation.active_candidates").gauge().value())
    }

    private fun reconciler(
        v: FakeVersions,
        j: FakeJobs,
        a: FakeArtifacts,
        scheduler: ReconciliationScheduler = FakeScheduler(),
        metrics: DeriveReconciliationObserver? = null,
    ) =
        DeriveReconciler(
            v,
            DeriveCoordinator(v, j, { emptyList() }, true),
            j,
            a.assigned(v.firstDeriving()),
            scheduler,
            metrics,
        )

    private fun record(state: VersionState) =
        MapVersionRecord(
            UUID.randomUUID(),
            1,
            state,
            null,
            "a".repeat(64),
            "tmp/uploads/s.tar.zst",
            null,
            null,
            null,
            null,
            null,
            UUID.randomUUID(),
            null,
            false,
            SceneProjection(SceneStatus.PENDING, null, null, null, null, emptyList(), emptyList()),
            "test",
            null,
            Instant.EPOCH,
        )

    private fun MapVersionRecord.identity() =
        DeriveIdentity(mapId, version, requireNotNull(deriveAttempt), requireNotNull(sourceSha256))

    private fun success(
        v: MapVersionRecord,
        scene: DerivedScene = DerivedScene(false, null, null, null, null, emptyList()),
    ) =
        DeriveSuccess(
                mapId = v.mapId,
                version = v.version,
                attempt = requireNotNull(v.deriveAttempt),
                sourceSha256 = requireNotNull(v.sourceSha256),
                bundleSha256 = "b".repeat(64),
                bundleSize = 42,
                manifestSha256 = "c".repeat(64),
                manifestSize = 9,
                scene = scene,
            )
            .let { result ->
                val manifest =
                    CanonicalJson.write(
                        DerivedManifest(
                            sourceSha256 = result.sourceSha256,
                            bundleDigestInputs = emptyList(),
                            scene = result.scene,
                        )
                    )
                result.copy(
                    manifestSha256 = sha256(manifest),
                    manifestSize = manifest.size.toLong(),
                )
            }

    private fun failure(v: MapVersionRecord) =
        DeriveFailure(
            mapId = v.mapId,
            version = v.version,
            attempt = requireNotNull(v.deriveAttempt),
            sourceSha256 = requireNotNull(v.sourceSha256),
            scope = DeriveFailureScope.CONTENT,
            retryable = false,
            problems =
                listOf(DeriveProblem(DeriveFailureScope.CONTENT, null, "INVALID", null, "bad")),
        )

    private fun manifest(
        result: DeriveSuccess,
        source: String = result.sourceSha256,
        scene: DerivedScene = result.scene,
    ) =
        CanonicalJson.write(
            DerivedManifest(sourceSha256 = source, bundleDigestInputs = emptyList(), scene = scene)
        )

    private fun catalogScene(id: String, sha: String = "e".repeat(64)) =
        DerivedScene(
            true,
            "1",
            sha,
            gg.grounds.domain.CatalogReference(id, "1"),
            gg.grounds.domain.CatalogReference("actions", "1"),
            emptyList(),
        )

    private fun assertUnavailable(
        version: MapVersionRecord,
        result: DeriveSuccess,
        marker: ByteArray? = CanonicalJson.write(result),
    ) {
        val versions = FakeVersions(listOf(version))
        reconciler(
                versions,
                FakeJobs(mapOf(version.identity() to DeriveJobStatus.SUCCEEDED)),
                FakeArtifacts(marker).assigned(version).apply { complete(result) },
            )
            .reconcile()
        assertEquals("RESULT_UNAVAILABLE", versions.failures.single().second.problems.single().code)
    }

    private fun sha256(bytes: ByteArray) =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    private class FakeArtifacts(
        private val marker: ByteArray? = null,
        private val events: MutableList<String>? = null,
    ) : DeriveArtifactStore {
        val sizes = mutableMapOf<String, BlobMetadata>()
        var manifest: ByteArray? = null
        var promotionFailure: Exception? = null
        private var identity: DeriveIdentity? = null
        val reads = mutableListOf<Pair<String, Long>>()
        val promotions = mutableListOf<Triple<String, String, Long>>()

        fun assigned(version: MapVersionRecord?) = apply {
            identity =
                version?.let {
                    DeriveIdentity(
                        it.mapId,
                        it.version,
                        requireNotNull(it.deriveAttempt),
                        requireNotNull(it.sourceSha256),
                    )
                }
        }

        private fun assignedKey(type: String): String =
            requireNotNull(identity).let { assigned ->
                when (type) {
                    "result" ->
                        BlobStore.deriveResultKey(
                            assigned.mapId,
                            assigned.version,
                            assigned.attempt,
                        )
                    "manifest" ->
                        BlobStore.deriveManifestKey(
                            assigned.mapId,
                            assigned.version,
                            assigned.attempt,
                        )
                    else ->
                        BlobStore.deriveBundleKey(
                            assigned.mapId,
                            assigned.version,
                            assigned.attempt,
                        )
                }
            }

        override fun getPrivate(key: String, maxBytes: Long) =
            when {
                key == assignedKey("result") -> {
                    require(maxBytes == 1_048_576L) { "unexpected result bound $maxBytes" }
                    events?.add("get:$key:$maxBytes")
                    reads += key to maxBytes
                    requireNotNull(marker) { "missing result" }
                        .also { require(it.size <= maxBytes) { "oversized result" } }
                }
                key == assignedKey("manifest") -> {
                    require(maxBytes == 4_194_304L) { "unexpected manifest bound $maxBytes" }
                    events?.add("get:$key:$maxBytes")
                    reads += key to maxBytes
                    requireNotNull(manifest) { "missing manifest" }
                        .also { require(it.size <= maxBytes) { "oversized manifest" } }
                }
                else -> error("unexpected key $key")
            }

        override fun headPrivate(key: String): BlobMetadata? {
            events?.add("head:$key")
            return when (key) {
                assignedKey("bundle") -> sizes["bundle"]
                assignedKey("manifest") -> sizes["manifest"]
                else -> error("unexpected key $key")
            }
        }

        fun complete(result: DeriveSuccess) {
            val body =
                CanonicalJson.write(
                    DerivedManifest(
                        sourceSha256 = result.sourceSha256,
                        bundleDigestInputs = emptyList(),
                        scene = result.scene,
                    )
                )
            manifest = body
            sizes["bundle"] = BlobMetadata(result.bundleSize)
            sizes["manifest"] = BlobMetadata(body.size.toLong())
        }

        override fun promotePrivateBundle(
            sourceKey: String,
            destinationKey: String,
            expectedSizeBytes: Long,
        ) {
            require(sourceKey == assignedKey("bundle"))
            require(destinationKey == BlobStore.bundleKey("b".repeat(64)))
            require(expectedSizeBytes == 42L)
            promotionFailure?.let { throw it }
            events?.add("promote:$sourceKey:$destinationKey:$expectedSizeBytes")
            promotions += Triple(sourceKey, destinationKey, expectedSizeBytes)
        }
    }

    private class FakeJobs(
        private val states: Map<DeriveIdentity, DeriveJobStatus> = emptyMap(),
        private var failFinds: Int = 0,
        private var failCreates: Int = 0,
        private val onFind: ((DeriveIdentity) -> Unit)? = null,
        private val onCreate: (() -> Unit)? = null,
    ) : DeriveJobGateway {
        val created = java.util.concurrent.CopyOnWriteArrayList<DeriveJobRequest>()
        val watches = mutableListOf<FakeWatch>()
        var findCalls = 0

        override fun create(request: DeriveJobRequest) {
            if (failCreates-- > 0) throw IllegalStateException("transient create")
            onCreate?.invoke()
            if (created.none { it.identity == request.identity }) created += request
        }

        override fun find(identity: DeriveIdentity): DeriveJobStatus? {
            findCalls++
            if (failFinds-- > 0) throw IllegalStateException("transient")
            onFind?.invoke(identity)
            return states[identity]
        }

        override fun watch(onEvent: () -> Unit, onClose: (Throwable?) -> Unit): AutoCloseable? {
            return FakeWatch(onEvent, onClose).also { watches += it }
        }
    }

    private class FakeWatch(
        private val onEvent: () -> Unit,
        private val onClose: (Throwable?) -> Unit,
    ) : AutoCloseable {
        fun event() = onEvent()

        fun remoteClose(cause: Throwable? = null) = onClose(cause)

        override fun close() = onClose(null)
    }

    private class FakeScheduler(private var failSchedules: Int = 0) : ReconciliationScheduler {
        val delays = mutableListOf<Duration>()
        private val tasks = ArrayDeque<() -> Unit>()

        override fun schedule(delay: Duration, task: () -> Unit) {
            if (failSchedules-- > 0) throw IllegalStateException("scheduler unavailable")
            delays += delay
            tasks += task
        }

        fun runNext() = requireNotNull(tasks.removeFirst()).invoke()

        override fun close() = Unit
    }

    private class FakeVersions(
        records: List<MapVersionRecord>,
        private val rejectSuccess: Boolean = false,
        private val rejectFailure: Boolean = false,
        private val integrityFailure: Boolean = false,
        private val failureIntegrity: Boolean = false,
        private val failList: Boolean = false,
        private val events: MutableList<String>? = null,
    ) : MapVersionRepository by unused() {
        private val records = records.toMutableList()
        var claims = 0
        val failures = mutableListOf<Pair<DeriveIdentity, DerivedFailure>>()
        val successes = mutableListOf<Pair<DeriveIdentity, DerivedFacts>>()
        val retried = mutableListOf<Pair<UUID, Int>>()

        fun firstDeriving() = records.firstOrNull { it.deriveAttempt != null }

        override fun listReconcileCandidates() =
            synchronized(records) {
                if (failList) throw IllegalStateException("repository unavailable")
                records.toList()
            }

        override fun claimForDerive(mapId: UUID, version: Int, attempt: UUID) =
            synchronized(records) {
                records
                    .indexOfFirst {
                        it.mapId == mapId && it.version == version && it.state == VersionState.DRAFT
                    }
                    .takeIf { it >= 0 }
                    ?.let { i ->
                        records[i]
                            .copy(state = VersionState.DERIVING, deriveAttempt = attempt)
                            .also {
                                records[i] = it
                                claims++
                            }
                    }
            }

        override fun acceptSuccess(
            identity: DeriveIdentity,
            facts: DerivedFacts,
            bySub: String,
        ): MapVersionRecord {
            if (integrityFailure) throw DeriveResultIntegrityException("conflicting duplicate")
            if (rejectSuccess) throw gg.grounds.domain.DeriveResultRejectedException("stale")
            events?.add("accept")
            successes += identity to facts
            return records.first()
        }

        override fun acceptFailure(
            identity: DeriveIdentity,
            failure: DerivedFailure,
        ): MapVersionRecord {
            if (failureIntegrity) throw DeriveResultIntegrityException("conflicting failure")
            if (rejectFailure) throw gg.grounds.domain.DeriveResultRejectedException("stale")
            failures += identity to failure
            return records.first()
        }

        override fun retrySystemFailure(mapId: UUID, version: Int): MapVersionRecord {
            retried += mapId to version
            return records.first()
        }
    }
}

private fun unused(): MapVersionRepository =
    object : MapVersionRepository {
        override fun commit(
            mapId: UUID,
            sourceSha256: String?,
            sourceKey: String?,
            parentVersion: Int?,
            note: String?,
            bySub: String,
        ) = error("unused")

        override fun publish(
            mapId: UUID,
            version: Int,
            facts: gg.grounds.domain.BundleFacts,
            bySub: String,
        ) = error("unused")

        override fun claimForDerive(mapId: UUID, version: Int, attempt: UUID) = error("unused")

        override fun acceptSuccess(identity: DeriveIdentity, facts: DerivedFacts, bySub: String) =
            error("unused")

        override fun acceptFailure(identity: DeriveIdentity, failure: DerivedFailure) =
            error("unused")

        override fun listReconcileCandidates() = error("unused")

        override fun retrySystemFailure(mapId: UUID, version: Int) = error("unused")

        override fun find(mapId: UUID, version: Int) = error("unused")

        override fun list(mapId: UUID) = error("unused")

        override fun latestPublished(mapId: UUID) = error("unused")
    }
