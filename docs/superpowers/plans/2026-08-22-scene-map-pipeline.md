# Scene Map Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Derive every uploaded map asynchronously in `service-maps`, validate an optional root `scene.json` against exact resource-pack and action catalogs, publish only validated immutable bundles, and make the build server wait for the resulting version state.

**Architecture:** `service-maps` owns the state machine and creates a least-privilege Kubernetes Job for each derive attempt. The Job runs a standalone worker entry point from the same image, processes only presigned private R2 objects, writes a result object last, and exits. A watch accelerates reconciliation while a startup/30-second poll is authoritative. The service verifies the result envelope and object metadata, persists scene facts/problems transactionally, then promotes the content-addressed bundle before marking the version published.

**Tech Stack:** Kotlin/JVM 25, Quarkus 3.30, PostgreSQL/Flyway, Fabric8 Kubernetes Client, AWS SDK v2/R2 S3 API, `resourcepacks-client`, `scene-format`, Apache Commons Compress, zstd-jni, JUnit 5/Testcontainers/MinIO, Java build-server plugin, Pulumi TypeScript/Kubernetes.

**Spec:** [`docs/superpowers/specs/2026-08-22-scene-map-pipeline-design.md`](../specs/2026-08-22-scene-map-pipeline-design.md)

## Global Constraints

- Use conventional branch names without a `codex/` prefix, for example `feat/scene-map-pipeline`.
- Use test-driven development: add a failing focused test, observe the expected failure, implement the smallest behavior, then rerun the focused and relevant suites.
- Keep planning, cross-repository contracts, security decisions, and final reviews on SOL. Delegate bounded implementation to `gpt-5.6-terra` with medium reasoning.
- Use the existing PR templates. Do not create release tags; Release Please owns versions and tags.
- Never hardcode a PackSet publication/version. A fixed library dependency version is expected; runtime catalog selection comes only from validated Stable and Edge channel snapshots.
- Do not equate R2 ETags with SHA-256 and do not request a checksum algorithm on `CopyObject`. Compare signed worker facts with `HeadObject` size/existence and the service-computed contract fields.
- Keep `derive.enabled=false` until the service, chart, infrastructure, and build-server compatibility path are deployed.

---

## Task 1: Add the scene projection schema and domain contract

**Files:**

- Create: `src/main/resources/db/migration/V2__scene_derivation.sql`
- Create: `src/main/kotlin/gg/grounds/domain/SceneDerivation.kt`
- Modify: `src/main/kotlin/gg/grounds/domain/Versions.kt`
- Modify: `src/main/kotlin/gg/grounds/persistence/PostgresMapVersionRepository.kt`
- Modify: `src/main/kotlin/gg/grounds/persistence/PostgresMapRepository.kt`
- Create: `src/test/kotlin/gg/grounds/persistence/SceneProjectionRepositoryIT.kt`
- Modify: `src/test/kotlin/gg/grounds/api/VersionLifecycleIT.kt`

**Interfaces:**

```kotlin
enum class DeriveFailureScope { CONTENT, SYSTEM }
enum class SceneStatus { PENDING, INVALID, NONE, VALID }

data class CatalogReference(val id: String, val version: String)
data class DeriveProblem(
    val scope: DeriveFailureScope,
    val path: String?,
    val code: String,
    val qualifiedIdentity: String?,
    val message: String,
)

data class SceneProjection(
    val status: SceneStatus,
    val schemaVersion: String?,
    val sha256: String?,
    val assetCatalog: CatalogReference?,
    val actionCatalog: CatalogReference?,
    val requiredActions: List<String>,
    val problems: List<DeriveProblem>,
)
```

- [ ] Add a repository integration test that migrates a fresh database, commits a version, and expects `scene.status=PENDING`, empty required actions, and empty problems.
- [ ] Run `./gradlew test --tests '*SceneProjectionRepositoryIT'` and confirm it fails because the migration/domain methods do not exist.
- [ ] Add nullable scene/catalog/attempt/failure columns plus `map_version_required_action` and ordered `map_version_derive_problem` tables. Add constraints for non-negative ordinals and failure-scope values.
- [ ] Extend `MapVersionRecord` with derive attempt/failure fields and `SceneProjection`. Keep old rows readable: nullable `scene_present` maps to `PENDING` unless a terminal legacy state proves `NONE`.
- [ ] Add repository reads/writes that replace required actions and problems in the same transaction as a derive transition. Sort action IDs and preserve problem ordinal.
- [ ] Extend fork insertion to copy bundle facts, all scene projection fields, required actions, and problems without copying bytes or rerunning derivation.
- [ ] Rerun the focused test and `./gradlew test --tests '*VersionLifecycleIT'`; expect both green.
- [ ] Commit: `feat: persist scene derivation state`

---

## Task 2: Define strict worker inputs, results, and derived manifests

**Files:**

- Create: `src/main/kotlin/gg/grounds/derive/DeriveContracts.kt`
- Create: `src/main/kotlin/gg/grounds/derive/CanonicalJson.kt`
- Create: `src/test/kotlin/gg/grounds/derive/DeriveContractsTest.kt`
- Create: `src/test/resources/derive/result-valid.json`
- Create: `src/test/resources/derive/result-unknown-field.json`

**Wire contract:**

```kotlin
data class DeriveRequest(
    val schemaVersion: Int = 1,
    val mapId: UUID,
    val version: Int,
    val attempt: UUID,
    val sourceSha256: String,
    val sourceUrl: URI,
    val bundleUrl: URI,
    val manifestUrl: URI,
    val resultUrl: URI,
    val catalogCandidates: List<AssetCatalogCandidate>,
)

sealed interface DeriveResult {
    val schemaVersion: Int
    val mapId: UUID
    val version: Int
    val attempt: UUID
    val sourceSha256: String
}
```

The success variant carries bundle/manifest sizes and SHA-256 values plus scene facts. The failure variant carries `CONTENT` or `SYSTEM`, retryability, and ordered structured problems. `grounds/derived-manifest.json` contains only content facts: source digest, bundle digest inputs, scene/catalog/action facts, and required actions; it contains no map ID, version, attempt, or timestamp.

- [ ] Write tests for deterministic JSON bytes, unknown-field rejection, digest validation, sorted/deduplicated required actions, and rejection of a success result with missing catalog data for a present scene.
- [ ] Run `./gradlew test --tests '*DeriveContractsTest'`; confirm compile/test failure.
- [ ] Implement Jackson models with an explicitly configured strict mapper usable outside Quarkus. Validate schema version, UUIDs, lowercase 64-hex digests, non-negative sizes, URI schemes, and result invariants at the boundary.
- [ ] Canonicalize object keys lexicographically and emit UTF-8 with a trailing newline. Do not serialize null optional fields.
- [ ] Rerun the focused test; expect green.
- [ ] Commit: `feat: define derive worker contracts`

---

## Task 3: Implement safe deterministic archive derivation

**Files:**

- Modify: `build.gradle.kts`
- Create: `src/main/kotlin/gg/grounds/derive/ArchiveLimits.kt`
- Create: `src/main/kotlin/gg/grounds/derive/SafeTarZstdReader.kt`
- Create: `src/main/kotlin/gg/grounds/derive/DerivedBundleWriter.kt`
- Create: `src/main/kotlin/gg/grounds/derive/SceneDeriver.kt`
- Create: `src/test/kotlin/gg/grounds/derive/SafeTarZstdReaderTest.kt`
- Create: `src/test/kotlin/gg/grounds/derive/SceneDeriverTest.kt`

**Dependencies:**

```kotlin
implementation("org.apache.commons:commons-compress:1.28.0")
implementation("com.github.luben:zstd-jni:1.5.7-6")
implementation("gg.grounds:scene-format:0.1.0")
```

- [ ] Add adversarial archive tests covering absolute/traversal/backslash paths, symlink/hardlink/device/FIFO entries, duplicate normalized paths, a non-file `scene.json`, entry/path/file/expanded-size limits, and trailing data. Each must return a content-scoped problem and never write a public result.
- [ ] Add deterministic bundle tests: identical source and catalogs produce identical bytes; absent scene yields `NONE`; valid scene yields canonical root `scene.json` and `grounds/derived-manifest.json`; malformed/invalid scene yields `INVALID` without silently omitting it.
- [ ] Run the two focused test classes and observe expected failures.
- [ ] Stream the zstd/tar input through counting wrappers. Enforce 1 GiB compressed, 8 GiB expanded, 2 GiB per file, 250,000 entries, and 1,024 UTF-8 path bytes before allocating or writing an entry.
- [ ] Normalize only portable forward-slash relative paths; reject empty segments, `.`/`..`, NUL, drive prefixes, duplicates, links, sparse/special entries, and data following the single zstd frame/tar terminator.
- [ ] Spool validated files under a fresh worker temp directory and repack in lexicographic path order with fixed uid/gid/mode/mtime and deterministic zstd settings.
- [ ] Decode Scene v1 through `scene-format`, validate it with injected asset/action catalogs, extract sorted required action IDs, and classify all scene/archive defects as non-retryable content failures.
- [ ] Rerun focused tests and `./gradlew spotlessCheck`; expect green.
- [ ] Commit: `feat: derive deterministic scene bundles`

---

## Task 4: Resolve exact Stable and Edge asset catalogs

**Files:**

- Modify: `build.gradle.kts`
- Modify: `src/main/resources/application.properties`
- Create: `src/main/kotlin/gg/grounds/catalog/PackSetCatalogProvider.kt`
- Create: `src/main/kotlin/gg/grounds/catalog/CatalogJarLoader.kt`
- Create: `src/main/kotlin/gg/grounds/catalog/NamespaceCatalogResolver.kt`
- Create: `src/main/kotlin/gg/grounds/catalog/EmptyGroundsActionCatalog.kt`
- Create: `src/test/kotlin/gg/grounds/catalog/PackSetCatalogProviderTest.kt`
- Create: `src/test/kotlin/gg/grounds/catalog/CatalogJarLoaderTest.kt`
- Create: `src/test/kotlin/gg/grounds/catalog/NamespaceCatalogResolverTest.kt`

**Runtime boundary:**

```kotlin
interface NamespaceCatalogResolver {
    fun resolve(namespace: String, assetCatalog: CatalogReference): ActionCatalogResolution
}

data class AssetCatalogCandidate(
    val channel: String,
    val id: String,
    val version: String,
    val coordinate: String,
    val file: String,
    val uri: URI,
    val sha256: String,
    val size: Long,
)
```

- [ ] Add `resourcepacks-client` at its released library version and tests backed by a local HTTP server serving signed/validated Stable and Edge channel documents, manifests, and catalog JARs.
- [ ] Verify a failed refresh keeps the last validated cached snapshot, an initial double failure makes readiness unavailable, and a channel update changes the exact catalog candidate without changing application configuration.
- [ ] Test catalog JAR size/digest, zip-slip/duplicate-entry defenses, and exactly one `GroundsAssetCatalog.catalog`; zero or multiple entries are content failures.
- [ ] Test `grounds:*` resolves to the immutable empty ActionCatalog v1 and unsupported namespaces fail through the interface rather than a conditional in the worker.
- [ ] Run focused tests and confirm they fail first.
- [ ] Create two `PackSetClient` instances from configured base URI/PackSet and Stable/Edge sources, each with a separate `/tmp/service-maps/packsets/<channel>` cache. Expose at most two current-or-degraded validated candidates.
- [ ] Resolve each catalog URL relative to the validated manifest URI, then verify candidate ID/version/coordinate/file/size/digest before loading.
- [ ] Add health/readiness data: ready when at least one valid snapshot exists; report both channel states and last errors without logging URLs containing signatures.
- [ ] Rerun focused tests and the full service test suite; expect green.
- [ ] Commit: `feat: resolve exact scene catalogs`

---

## Task 5: Add private derive object operations and safe promotion

**Files:**

- Modify: `src/main/kotlin/gg/grounds/blob/BlobStore.kt`
- Create: `src/main/kotlin/gg/grounds/blob/BlobMetadata.kt`
- Create: `src/test/kotlin/gg/grounds/blob/BlobStoreIT.kt`
- Modify: `src/test/resources/application.properties`

**Object keys:**

```kotlin
fun uploadKey(id: String) = "tmp/uploads/$id/source.tar.zst"
fun deriveBundleKey(map: UUID, version: Int, attempt: UUID) =
    "tmp/derive/$map/$version/$attempt/bundle.tar.zst"
fun deriveManifestKey(map: UUID, version: Int, attempt: UUID) =
    "tmp/derive/$map/$version/$attempt/derived-manifest.json"
fun deriveResultKey(map: UUID, version: Int, attempt: UUID) =
    "tmp/derive/$map/$version/$attempt/result.json"
```

- [ ] Add MinIO integration tests for 30-minute presigned GET/PUT URLs, `headPrivate`, missing objects, and create-or-compare promotion.
- [ ] Cover promotion cases: destination absent copies successfully; destination present with matching size succeeds without replacement; destination present with conflicting size is a hard integrity failure.
- [ ] Confirm tests fail against the existing `BlobStore`.
- [ ] Add `presignGet`, exact-key `presignPut`, `headPrivate`, `headPublic`, and `copyPrivateToPublic`. Keep legacy `source.zip` readable for existing rows while all new uploads use `source.tar.zst`.
- [ ] Implement create-or-compare using `HeadObject` around `CopyObject`. Do not use ETag as a content digest and do not set `checksumAlgorithm` on R2 copy requests.
- [ ] Verify promotion happens before the database transition to `PUBLISHED`.
- [ ] Rerun focused integration tests; expect green.
- [ ] Commit: `feat: store derive artifacts privately`

---

## Task 6: Build the standalone derive worker entry point

**Files:**

- Create: `src/main/kotlin/gg/grounds/derive/DeriveWorkerMain.kt`
- Create: `src/main/kotlin/gg/grounds/derive/WorkerHttpTransfer.kt`
- Modify: `Dockerfile`
- Create: `src/test/kotlin/gg/grounds/derive/DeriveWorkerMainIT.kt`

**Invocation:**

```text
java -cp app/*:lib/boot/*:lib/main/* gg.grounds.derive.DeriveWorkerMain --request-env DERIVE_REQUEST_JSON
```

- [ ] Write an end-to-end worker test with local HTTP endpoints for source GET and exact artifact PUTs. Assert the bundle and manifest upload before `result.json`, and assert a failed bundle upload prevents any result upload.
- [ ] Test success, content failure, transient download failure, digest mismatch, expired URL, and bounded HTTP response bodies/timeouts.
- [ ] Run `./gradlew test --tests '*DeriveWorkerMainIT'`; confirm failure.
- [ ] Implement a dependency-free CLI boundary around the strict mapper, catalog loader, and scene deriver. Read the request from the named environment variable (and allow a local file only in the test/dev entry point), then validate it before any network call.
- [ ] Stream downloads/uploads, hash while transferring, reject redirects and non-HTTPS outside tests, use connect/read/request timeouts, and redact URL query strings from logs/errors.
- [ ] Upload bundle then manifest then the final result. For failures, upload only the signed failure result when the result URL remains usable.
- [ ] Confirm the distroless runtime image contains the application classpath and supports the documented command override; add a container smoke test to CI if required.
- [ ] Rerun worker IT, `./gradlew build`, and `docker build` where Docker is available.
- [ ] Commit: `feat: add scene derive worker`

---

## Task 7: Implement transactional derive state transitions

**Files:**

- Modify: `src/main/kotlin/gg/grounds/domain/Versions.kt`
- Modify: `src/main/kotlin/gg/grounds/persistence/PostgresMapVersionRepository.kt`
- Create: `src/main/kotlin/gg/grounds/domain/DeriveResultAcceptance.kt`
- Create: `src/test/kotlin/gg/grounds/persistence/DeriveStateRepositoryIT.kt`

**Repository operations:**

```kotlin
fun claimForDerive(mapId: UUID, version: Int, attempt: UUID): MapVersionRecord?
fun acceptSuccess(identity: DeriveIdentity, facts: DerivedFacts, bySub: String): MapVersionRecord
fun acceptFailure(identity: DeriveIdentity, failure: DerivedFailure): MapVersionRecord
fun listReconcileCandidates(): List<MapVersionRecord>
fun retrySystemFailure(mapId: UUID, version: Int): MapVersionRecord
```

- [ ] Add concurrent repository tests proving only one attempt claims a draft, stale attempts cannot finish a version, repeated acceptance is idempotent, and results for the wrong source/catalog/map/version are rejected.
- [ ] Add tests for `CONTENT -> DERIVE_FAILED/retryable=false`, `SYSTEM -> DERIVE_FAILED/retryable=true`, retry only for system failures, and exact replacement of actions/problems.
- [ ] Run focused tests and confirm failure.
- [ ] Implement row-locked transitions and attempt identity checks. `claimForDerive` assigns the UUID and enters `DERIVING`; acceptance clears no immutable input identity.
- [ ] Keep `PUBLISHED` immutable. Treat a duplicate matching result as success and a conflicting duplicate as an integrity failure.
- [ ] Make `scene.status=INVALID` for every terminal derive failure, including archive failures where no scene could be decoded.
- [ ] Rerun focused and lifecycle tests; expect green.
- [ ] Commit: `feat: enforce derive state transitions`

---

## Task 8: Orchestrate and reconcile least-privilege Kubernetes Jobs

**Files:**

- Modify: `build.gradle.kts`
- Create: `src/main/kotlin/gg/grounds/derive/DeriveJobGateway.kt`
- Create: `src/main/kotlin/gg/grounds/derive/Fabric8DeriveJobGateway.kt`
- Create: `src/main/kotlin/gg/grounds/derive/DeriveCoordinator.kt`
- Create: `src/main/kotlin/gg/grounds/derive/DeriveReconciler.kt`
- Create: `src/main/kotlin/gg/grounds/derive/DeriveHealthCheck.kt`
- Create: `src/test/kotlin/gg/grounds/derive/DeriveCoordinatorTest.kt`
- Create: `src/test/kotlin/gg/grounds/derive/DeriveReconcilerTest.kt`
- Modify: `src/main/resources/application.properties`

**Job settings:**

```yaml
backoffLimit: 2
activeDeadlineSeconds: 1200
ttlSecondsAfterFinished: 3600
resources:
  requests: { cpu: 500m, memory: 1Gi, ephemeral-storage: 10Gi }
  limits: { cpu: "2", memory: 4Gi, ephemeral-storage: 20Gi }
```

- [ ] Test disabled mode leaves committed versions as drafts; enabled mode claims once, builds a Job with the same image, passes only a request file/URLs, and never mounts R2/DB/Keycloak secrets into the worker.
- [ ] Test startup reconciliation, 30-second polling, watch-triggered reconciliation, watch reconnect, missing Job recreation with the same attempt, retry delays 5s/30s/2m, and terminal system failure after Job backoff is exhausted.
- [ ] Run focused tests and observe failure.
- [ ] Add Quarkus scheduler and Fabric8 client dependencies. Keep Kubernetes access behind `DeriveJobGateway` so tests do not need a cluster.
- [ ] Build Jobs with a strict JSON request in the Job's `DERIVE_REQUEST_JSON` environment variable. It contains only presigned exact URLs expiring after 30 minutes and bounded catalog candidates; do not create a ConfigMap or Secret. Use the service image digest/tag supplied by configuration.
- [ ] Set worker `serviceAccountName=service-maps-derive-worker`, `automountServiceAccountToken=false`, read-only root, `/work` emptyDir, restricted security context, and the exact resource/deadline/backoff/TTL fields.
- [ ] Start one watch as a latency hint; always run startup plus scheduled reconciliation as the correctness path. Close/reopen failed watches with bounded backoff.
- [ ] Record counters/timers and structured logs keyed by map/version/attempt/job without signed URLs.
- [ ] Rerun focused and full service tests; expect green.
- [ ] Commit: `feat: orchestrate scene derive jobs`

---

## Task 9: Replace client-side publish with derive API behavior

**Files:**

- Modify: `src/main/kotlin/gg/grounds/api/MapsResource.kt`
- Modify: `src/main/kotlin/gg/grounds/openapi/OpenApiConfiguration.kt`
- Modify: `src/test/kotlin/gg/grounds/api/VersionLifecycleIT.kt`
- Create: `src/test/kotlin/gg/grounds/api/DeriveApiIT.kt`
- Modify: `README.md`

**Endpoints:**

```text
GET  /v1/maps/{address}/versions/{version}
POST /v1/maps/{address}/versions/{version}/derive/retry
```

The commit body accepts `derive:true` during compatibility rollout. When derivation is enabled, source-backed commits automatically enter the coordinator path. The old `/publish` endpoint rejects source-backed versions and remains only for legacy/fork compatibility until the build server is deployed.

- [ ] Add API tests for exact-version polling, `PENDING/INVALID/NONE/VALID` scene DTOs, structured ordered problems, authorization/404 privacy, manual retry rules, and old-publish rejection for source-backed versions.
- [ ] Test compatibility modes: derive disabled preserves the old client; derive enabled accepts `derive:true`; `deriveRequired=true` rejects old source-backed commit/publish flow with an actionable problem response.
- [ ] Run focused tests and confirm failure.
- [ ] Add exact version and retry endpoints. Extend `VersionDto` with scene facts, catalogs, required actions, and problems without removing existing fields.
- [ ] Trigger coordination after the commit transaction has completed. If Job creation fails, persist a retryable system failure or leave a reconcile candidate; never return a falsely published version.
- [ ] Generate and inspect the production OpenAPI snapshot.
- [ ] Rerun API/full tests and `./gradlew generateOpenApiSnapshot`; expect green.
- [ ] Commit: `feat: expose asynchronous map derivation`

---

## Task 10: Package secure chart defaults

**Files:**

- Modify: `chart/values.yaml`
- Modify: `chart/templates/deployment.yaml`
- Create: `chart/templates/derive-worker-serviceaccount.yaml`
- Modify: `chart/templates/_helpers.tpl`
- Create: `chart/tests/derive-security_test.yaml` if helm-unittest is already supported; otherwise add assertions to the repository's chart render verification.
- Modify: `.github/workflows/ci.yml`

- [ ] Add render assertions for derive disabled by default, service token opt-in, worker SA token disabled, exact config/env fields, `/tmp` cache/work volumes, and no R2 secret reference in any worker-owned resource.
- [ ] Render the chart and observe the new assertions fail.
- [ ] Add values for `derive.enabled`, `derive.required`, image reference, channel sources, poll interval, URL TTL, archive limits, Job resources/security, and worker service account.
- [ ] Allow the service deployment to mount a projected short-lived service-account token only when derive is enabled. Keep worker automount false.
- [ ] Ensure service readiness includes database plus at least one validated catalog snapshot only when derive is enabled.
- [ ] Run `helm lint chart`, render tests, `./gradlew build`, and `./gradlew spotlessCheck`.
- [ ] Commit: `feat: package scene derive runtime`

---

## Task 11: Update the build server to poll registry-owned derivation

**Repository:** `/home/lukas/grounds/buildsystem`

**Files:**

- Create branch: `feat/async-map-derivation`
- Modify: `buildsystem-grounds/src/main/java/gg/grounds/buildsystem/maps/RegistryClient.java`
- Modify: `buildsystem-grounds/src/main/java/gg/grounds/buildsystem/maps/MapVersion.java`
- Modify: `buildsystem-grounds/src/main/java/gg/grounds/buildsystem/commands/MapCommand.java`
- Create: `buildsystem-grounds/src/test/java/gg/grounds/buildsystem/maps/RegistryDerivePollingTest.java`
- Modify: `buildsystem-grounds/src/test/java/gg/grounds/buildsystem/maps/UploadErrorTest.java`

**Polling policy:** Start at 1 second, exponentially back off to 5 seconds, and time out after 10 minutes without canceling server-side derivation.

- [ ] Add HTTP-server tests proving `push()` uploads `source.tar.zst`, commits with `derive:true`, polls the exact version URL, returns only on `PUBLISHED`, and never calls `/publish`.
- [ ] Test `DERIVE_FAILED` renders ordered structured problems, timeout reports that processing continues, authentication/network failures remain actionable, and interruption restores the thread interrupt flag.
- [ ] Run `./gradlew :buildsystem-grounds:test --tests '*RegistryDerivePollingTest'`; confirm failure.
- [ ] Extend `MapVersion` parsing for scene status/problems while remaining tolerant of newly added response fields.
- [ ] Replace the publish request with polling using a monotonic deadline and injectable sleeper/clock for deterministic tests.
- [ ] Update command feedback: upload accepted, deriving, published with version/hash, or failed with concise problem details. Do not spam one chat line per poll.
- [ ] Run the focused test, module tests, full build, and formatting checks.
- [ ] Commit: `feat: wait for map derivation`
- [ ] Push with the repository PR template and request review; do not tag a release.

---

## Task 12: Add Kubernetes RBAC, R2 lifecycle, and rollout controls

**Repository:** `/home/lukas/grounds/grounds-pulumi`

**Files:**

- Create branch: `feat/service-maps-derive-jobs`
- Modify: `core/src/platform/service-maps.ts`
- Modify: `core/src/config.ts`
- Modify: `cloudflare/src/maps-storage.ts`
- Create: `tests/service-maps-core-runtime.test.ts`
- Modify: environment stack YAML only when the released chart/image versions are known.

- [ ] Write Pulumi mock/unit tests asserting a namespaced Role permits only `jobs` create/get/list/watch, the service RoleBinding names only the service SA, the worker SA has no binding/token, and derive values are disabled by default.
- [ ] Assert the private R2 bucket has a seven-day lifecycle rule scoped to `tmp/derive/` while existing upload lifecycle behavior remains.
- [ ] Run `node --import tsx --test --test-concurrency=1 tests/service-maps-core-runtime.test.ts`; confirm failure.
- [ ] Change the service SA to opt into its token only with derive enabled. Add the namespaced Role/RoleBinding with no permissions for secrets, pods, configmaps, or other namespaces.
- [ ] Pass released chart/image, Stable/Edge PackSet sources, image identity, and derive feature gates as typed configuration. Remove stale comments that claim no Kubernetes API access.
- [ ] Add the R2 lifecycle rule without duplicating objects or changing public bucket retention.
- [ ] Rerun the focused test, complete Pulumi test suite, formatting, and TypeScript checks.
- [ ] Commit: `feat: provision map derive jobs`
- [ ] Push with the repository PR template and request review; do not tag a release.

---

## Task 13: Cross-repository compatibility and security review

**Files:**

- Modify as findings require in the three feature branches.
- Update: `docs/superpowers/specs/2026-08-22-scene-map-pipeline-design.md` only for clarified, behavior-preserving details discovered during implementation.

- [ ] On SOL, compare all request/result/API/chart/Pulumi fields across repositories; reject drift in names, defaults, state mappings, URL TTL, resource limits, and feature gates.
- [ ] Review worker isolation: no reusable R2 credentials, no DB/OIDC credentials, no service token, no public write URL, no arbitrary object key, no signed URL logging, no archive path escape, and bounded resource consumption.
- [ ] Review idempotency and ordering: result last, stale attempts rejected, startup poll authoritative, public object before `PUBLISHED`, create-or-compare promotion, fork copies metadata only.
- [ ] Run service-maps full verification from a clean checkout: `./gradlew clean build spotlessCheck generateOpenApiSnapshot` plus chart lint/render tests.
- [ ] Run buildsystem full verification from a clean checkout: `./gradlew clean build` and its formatting checks.
- [ ] Run grounds-pulumi full verification: project tests, formatting, typecheck, and a preview/mocked render for the service-maps resources.
- [ ] Fix every correctness/security finding with a regression test and a conventional commit in the owning repository.

---

## Task 14: Release and stage rollout

**Repositories:** `service-maps`, `buildsystem`, `grounds-pulumi`

- [ ] Push the service-maps branch using the existing PR template. Wait for required review/CI, merge it, then let Release Please create the release PR/tag; do not create a tag manually.
- [ ] Deploy the released service/chart with `derive.enabled=false`. Verify DB migration, catalog refresh/cache state, readiness, and unchanged legacy publish behavior.
- [ ] Merge/release the buildsystem client and deploy it while compatibility mode is active.
- [ ] Merge the Pulumi infrastructure PR with RBAC/lifecycle/config. Pin only versions produced by Release Please.
- [ ] Enable `derive.enabled=true`, `derive.required=false` on stage. Upload a fixture with no scene, one valid scene, and one invalid scene; verify worker isolation, states, API facts/problems, private temp artifacts, and public promotion.
- [ ] Verify recovery by deleting a running Job, restarting the service during a derive, temporarily denying R2 access, and confirming watch loss is healed by polling.
- [ ] Enable `derive.required=true` on stage after the deployed build server is confirmed compatible.
- [ ] Update the master Confluence page and implementation-status documentation with released versions, stage evidence, and remaining Phase 6/7/8 work.
- [ ] Promote the same released versions to production with derive initially optional, observe metrics/error budgets, then require derivation after the compatibility window.
- [ ] Remove the legacy source-backed `/publish` path in a later explicit cleanup PR only after no old build-server versions remain.
