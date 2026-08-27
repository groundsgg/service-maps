# Scene Map Pipeline Design

**Status:** Approved on 2026-08-22

**Parent:** [Scene, NPC & Resource Pack Platform — Concept and Master Plan](https://grounds.atlassian.net/wiki/spaces/GARCHITECT/pages/257622018)

**Primary repository:** `groundsgg/service-maps`

**Related repositories:** `groundsgg/buildsystem`, `groundsgg/grounds-pulumi`

## Goal

Make an optional root-level `scene.json` an immutable part of a map version. Every uploaded map is
derived by `service-maps`; a present scene is decoded, canonicalized, and validated against the
exact resource-pack and application-action catalogs before any bundle becomes public. Invalid
scene content fails the map version and is never silently omitted.

The first delivery exposes scene state and validation details through the maps API. Visible portal
UI remains Phase 8 of the master plan.

## Scope

This phase delivers:

- asynchronous source-to-derived map processing owned by `service-maps`;
- safe deterministic extraction and repacking of `.tar.zst` map archives;
- zero-or-one root-level `scene.json` discovery;
- strict Scene v1 decoding, canonicalization, and catalog validation through
  `gg.grounds:scene-format:0.1.0`;
- exact resource-pack catalog resolution without a hardcoded PackSet version;
- a replaceable namespace-to-action-catalog boundary with an empty v1 bootstrap catalog;
- scene metadata, required application action IDs, and structured derive problems in Postgres and
  the HTTP API;
- Kubernetes Job orchestration with a watch for latency and a poll reconciler for correctness;
- build-server polling and useful in-game success/failure output;
- unchanged atomic pin, rollback, and fork behavior; and
- infrastructure, release, migration, and compatibility work required to deploy the pipeline.

This phase does not deliver:

- Paper scene authoring or preview;
- a production action implementation or non-empty application ActionCatalog;
- Minestom scene rendering;
- portal scene UI;
- creator moderation, quotas, leases, or stateful snapshots;
- dynamic per-map resource-pack stacks; or
- a second scene version or scene pin.

## Ownership and boundaries

### `service-maps`

`service-maps` remains the only map publishing and versioning boundary. It owns version state,
catalog selection, Job creation, reconciliation, result acceptance, public promotion, scene
metadata, and structured failures.

The repository gains a focused derive-worker entry point in the same release artifact. The worker
owns archive processing and calls `library-scene`; it does not own map state or public promotion.
Running the worker from the same repository and version prevents an independent worker/service
compatibility matrix.

### Build server

`buildsystem` remains the source authoring and upload client. It saves the world, builds the current
reproducible source archive, uploads it, commits a version, and waits for the registry-owned derive.
It no longer claims final bundle hashes, sizes, or manifest facts.

### Resource-pack product

The existing `resourcepacks-client` resolves and validates Stable and Edge channel documents and
their immutable PackSet manifests. `service-maps` reuses that contract and cache behavior. It does
not implement another channel parser or pin a catalog version in code or deployment configuration.

### Grounds infrastructure

`grounds-pulumi` grants the registry namespace-local Job permissions and supplies bounded worker
configuration. A derive Job receives short-lived presigned URLs for exact private-bucket keys; it
does not receive reusable R2 credentials or permission to write a public bucket.

## Source and derived bundle contract

The uploaded source is a reproducible `.tar.zst` archive. New uploads use the key
`tmp/uploads/<upload-id>/source.tar.zst`. Existing rows keep their recorded key, including the
historical `source.zip` suffix; consumers use the stored key rather than inferring it.

The authored world root contains normal world files and may contain exactly one `scene.json`:

```text
world root/
├── level.dat
├── region/
├── grounds/
│   ├── pois.json
│   └── setup.json
└── scene.json       # optional
```

Only the exact root path `scene.json` is a scene candidate. A nested file with that name is ordinary
world content. A duplicate archive entry for any path is invalid. A source without `scene.json` is
a normal no-scene map and follows the same derive path.

The worker produces:

```text
tmp/derive/<map-id>/<version>/<attempt-id>/
├── bundle.tar.zst
├── derived-manifest.json
└── result.json
```

It uploads `bundle.tar.zst` first, `derived-manifest.json` second, and `result.json` last. The last
object is the completion marker. Partial attempt objects are private scratch and are removed by a
lifecycle rule after seven days.

`result.json` has schema version 1 and exactly these sections: map UUID, version, attempt UUID,
source key/digest/size, derived bundle key/digest/size, derived manifest key/digest/size, and either
successful scene facts or an ordered non-empty problem list. Successful scene facts contain
presence, nullable schema version and Scene digest, nullable asset/action catalog references, and
the sorted required application action IDs. A failure result additionally declares whether it is
retryable. Unknown fields, missing fields, contradictory success/failure sections, non-canonical
digests, and keys outside the assigned attempt prefix invalidate the result as a SYSTEM failure.

The derived bundle contains the complete world, a canonical root-level `scene.json` when present,
and `grounds/derived-manifest.json`. The embedded manifest is byte-identical to the separate
`derived-manifest.json` result object. It contains content facts only: format version, source
digest, scene facts, and catalog references. It must not contain map ID, map version, attempt ID,
actor, timestamp, or environment, because those values would make an otherwise identical bundle
different and would break zero-copy forks.

Archive entries are sorted by UTF-8 path bytes. File mode, owner, group, and mtime are normalized in
the same way as the existing build-server archive. Canonical scene bytes replace authored scene
bytes before the final archive digest is computed. Two derives with identical source bytes and
catalog inputs must produce byte-identical bundles and manifests.

## Archive safety limits

All source archives are untrusted input even when the ordinary client is the build server. The
worker rejects the archive before public promotion when any of these conditions is true:

- compressed source size exceeds 1 GiB;
- total expanded regular-file bytes exceed 8 GiB;
- a single expanded file exceeds 2 GiB;
- the archive contains more than 250,000 entries;
- a normalized UTF-8 path exceeds 1,024 bytes;
- a path is absolute, empty, contains `.` or `..` segments, contains NUL, or escapes the worker
  directory after normalization;
- an entry is a symlink, hard link, device, FIFO, socket, or unsupported tar type;
- two entries normalize to the same path; or
- the archive contains trailing unread payload after a completed zstd/tar stream.

The limits are deployment configuration with these values as required defaults. Lower values may
be used per environment. Raising a value is an explicit infrastructure change. `scene-format`
retains its own stricter 16 MiB JSON and structural limits.

The worker writes into a fresh empty directory on ephemeral storage and creates every output file
with `CREATE_NEW`. It never follows links. No source path is interpolated into an R2 key or a
Kubernetes object name.

## Scene validation

When `scene.json` is absent, the worker records `scenePresent=false` and does not construct catalogs.

When it is present, the worker:

1. hashes the authored scene bytes for diagnostics;
2. calls `SceneJson.decode` and rejects every decode problem;
3. resolves the exact asset and action catalogs declared by `SceneDocument.catalogs`;
4. calls `SceneValidation.validateCatalogs` and rejects every problem;
5. calls `SceneJson.encode` and treats the returned canonical bytes as the only derived scene;
6. hashes the canonical bytes and records that digest as the authoritative `sceneSha256`;
7. extracts the distinct `ApplicationAction.key` values, sorts them by code point, and records them;
8. writes the canonical scene and content-only derived manifest; and
9. deterministically packs and hashes the derived bundle.

Intrinsic, asset, animation, action, argument, and catalog failures are all hard failures. The
worker never removes a present scene to rescue a publish.

## Resource-pack catalog resolution

`service-maps` maintains validated cached snapshots for the configured Stable and Edge PackSet
sources through `resourcepacks-client`. Because the service does not open the source archive, it
passes the worker a bounded list containing at most those two validated immutable catalog bindings.
The worker selects exactly one binding only when all of these match the scene reference:

- catalog ID;
- catalog version;
- artifact coordinate;
- artifact file under the immutable manifest directory;
- artifact byte size; and
- artifact SHA-256.

Each catalog artifact URL is resolved relative to its already validated immutable manifest URL.
The worker downloads the matching artifact with a strict size bound, verifies size and SHA-256
before opening the JAR, and loads the published `GroundsAssetCatalog.catalog` entry point with
`scene-format` types delegated to the parent classloader. Only catalog artifacts authenticated by a
PackSet manifest are accepted. Zero or multiple matches are failures; channel precedence never
chooses between different bytes.

There is no hardcoded PackSet or resource-pack catalog version. A scene referencing neither the
currently validated Stable nor Edge catalog fails with `UNSUPPORTED_ASSET_CATALOG`. A temporary
channel or manifest outage does not turn content invalid: the existing validated cache is used; if
no valid cache exists, the attempt is classified as a retryable system failure.

## Action catalog boundary

Each map namespace resolves through this service-owned interface:

```kotlin
interface NamespaceCatalogResolver {
    fun resolve(namespace: String, reference: CatalogReference): ActionCatalogResolution
}
```

Phase 5 supplies a configured default mapping to an immutable empty catalog with ID
`grounds:actions` and version `1`. It allows safe built-in Scene actions and rejects every
`ApplicationAction` as `UNKNOWN_ACTION`. The mapping is explicit configuration, not inferred from
builder input.

Phase 7 will place application-generated, versioned ActionCatalog providers behind this interface.
No JSON catalog transport, dynamic application classloading, or provisional cross-service protocol
is introduced in Phase 5.

## Persistence model

Flyway adds nullable scene facts to `map_version`:

- `scene_present BOOLEAN` — `NULL` while an uninspected new version is DRAFT/DERIVING;
- `scene_schema_version INTEGER`;
- `scene_sha256 TEXT`;
- `asset_catalog_id TEXT`;
- `asset_catalog_version TEXT`;
- `action_catalog_id TEXT`;
- `action_catalog_version TEXT`;
- `derive_attempt UUID`;
- `derive_failure_scope TEXT`; and
- `derive_retryable BOOLEAN NOT NULL DEFAULT FALSE`.

Checks enforce that published no-scene rows have no scene/catalog facts, published scene rows have
all scene/catalog facts, and all stored SHA-256 values are 64 lowercase hexadecimal characters.
The migration backfills existing published versions with `scene_present=false`; unfinished legacy
rows remain `NULL` until inspected or superseded.

Required application actions are normalized:

```text
map_version_required_action(map, version, action_id)
```

The primary key is `(map, version, action_id)`. Action IDs are inserted in canonical code-point
order when returned through the API.

Derive problems are normalized and ordered:

```text
map_version_derive_problem(
  map, version, ordinal, scope, path, code, qualified_identity, message
)
```

The primary key is `(map, version, ordinal)`. `scope` is one of `ARCHIVE`, `SCENE`, `CATALOG`, or
`SYSTEM`. Problems are replaced as one set in the same transaction that completes or fails an
attempt. Successful versions retain no derive problems.

## Version lifecycle and transactions

The normal state flow is:

```text
DRAFT -> DERIVING -> PUBLISHED
                    DERIVE_FAILED
```

`commit` allocates the immutable version and source key in the existing transaction. A reconciler
claims a DRAFT row with a database lock, assigns a fresh attempt UUID, changes it to DERIVING, and
then creates the Job. If the process stops between the database transaction and Job creation, the
poll reconciler notices that the attempt has no matching Job and creates it idempotently.

The Job name is derived from map UUID, version, and attempt UUID and carries those values as labels.
Creating an already-existing matching Job succeeds logically. A mismatched Job with the same name
is a system failure.

On success, `service-maps` validates the bounded result document, attempt identity, source digest,
expected private keys, catalog binding, digests, sizes, and object existence. The derive worker is a
trusted component of the same release; the service does not execute the archive pipeline twice.
The worker computes every digest while producing bytes, and a successful R2 upload plus subsequent
object size check is required before its result is accepted.

The service promotes the private derived bundle to the existing content-addressed public key before
the database commit. Promotion is create-or-compare: an existing object is accepted only when its
recorded size matches, an absent object is copied, and a conflicting size is a hard integrity
failure. A failed copy leaves the version DERIVING and retryable. A successful copy followed by a
failed database transaction may leave an unreferenced immutable public object, which is harmless
and eligible for later orphan collection. The reverse order is forbidden because it could create a
PUBLISHED version pointing at a missing object.

The final database transaction:

- verifies that map, version, state, source digest, and attempt still match;
- records bundle and manifest facts;
- records scene facts and required actions;
- replaces derive problems with the empty set;
- records the committing subject as `publishedBySub`; and
- changes the state to PUBLISHED.

A content failure performs the corresponding atomic problem write and changes the state to
DERIVE_FAILED. Published rows are immutable.

## Failure and retry semantics

Content failures use `derive_retryable=false`:

- malformed or unsafe archive;
- source digest mismatch;
- malformed or intrinsically invalid scene;
- unknown asset, animation, or application action;
- catalog ID/version mismatch; and
- deterministic output or manifest contract violation.

Correcting one requires a new uploaded map version.

System failures use `derive_retryable=true`:

- Kubernetes scheduling or process failure;
- temporary R2 or CDN transport failure;
- no validated PackSet snapshot or cache being available;
- result object unavailable after a successful Job; and
- internal timeout or resource exhaustion.

The Job uses `backoffLimit: 2`. The service additionally retries transient reconciliation with
bounded exponential delays of 5 seconds, 30 seconds, and 2 minutes. After those attempts it records
a structured SYSTEM failure. `POST .../derive/retry` is allowed only for a retryable failure,
requires existing publish authorization, assigns a new attempt UUID, clears old SYSTEM problems,
and returns the version to DERIVING. Content failures return HTTP 409 from that endpoint.

Watch events reduce latency but never establish correctness. A poll runs every 30 seconds and once
at service startup. It scans DRAFT and DERIVING rows, compares them with labeled Jobs, and advances
or repairs them. Reprocessing an already accepted attempt is a no-op.

## HTTP API

`VersionDto` gains a non-null `scene` object:

```json
{
  "status": "VALID",
  "schemaVersion": 1,
  "sha256": "<64 lowercase hex>",
  "assetCatalog": { "id": "grounds:assets", "version": "0.5.1" },
  "actionCatalog": { "id": "grounds:actions", "version": "1" },
  "requiredActionIds": [],
  "problems": []
}
```

`status` is derived in state-first order:

- `PENDING` while the version is DRAFT or DERIVING;
- `INVALID` when the version is DERIVE_FAILED, including archive failures that prevented reliable
  scene discovery;
- `NONE` when the terminal successful version has `scene_present=false`; and
- `VALID` when the terminal successful version has `scene_present=true`.

Nullable facts are `null` for NONE, PENDING, or undecodable invalid scenes. Collections are always
present and sorted. Each problem contains `scope`, `path`, `code`, optional `qualifiedIdentity`, and
`message`.

The API adds:

- `GET /v1/maps/{address}/versions/{version}` for efficient polling; and
- `POST /v1/maps/{address}/versions/{version}/derive/retry` for retryable SYSTEM failures.

`POST /v1/maps/{address}/versions` accepts `derive: true` during the compatibility rollout. After
the rollout, deployment configuration requires derive for every source-backed commit. The existing
client-supplied publish endpoint rejects source-backed versions when derive is required. Forks do
not call that endpoint.

Forking a published version creates a published version 1 that references the same bundle digest and
copies all scene and catalog facts plus required action IDs in the same transaction. It copies no
bytes. Pin files require no schema change because they already point at the whole immutable bundle.
Moving or rolling back a pin therefore moves world, configuration, and scene together.

## Build-server behavior

`RegistryClient.push` changes from upload/commit/client-publish to upload/commit/poll:

1. upload the reproducible source archive;
2. commit with `derive=true`, source SHA-256, parent version, and note;
3. poll the exact-version endpoint off the Paper main thread;
4. start at one-second intervals and cap the interval at five seconds;
5. stop after ten minutes without cancelling the server-side derive; and
6. report PUBLISHED, structured DERIVE_FAILED details, or a still-processing message.

A timeout tells the builder that processing continues and points to `/map versions`; repeating
`/map push` is not suggested because that would allocate a second immutable version. On success the
world link records the published version exactly as today. Network errors do not re-upload or
re-commit after the version number has been returned.

## Kubernetes and R2 execution model

The `service-maps` ServiceAccount changes from `automountServiceAccountToken=false` to a projected,
short-lived token and receives a namespace Role limited to create/get/list/watch Jobs. Job cleanup
uses `ttlSecondsAfterFinished`; the service receives no delete permission.

Each derive Job:

- runs the exact `service-maps` image tag with the derive-worker command;
- uses a separate ServiceAccount with `automountServiceAccountToken=false`;
- receives no database, Keycloak, public-bucket, or reusable R2 credentials;
- receives presigned GET/PUT URLs for the exact source and attempt result keys;
- has `activeDeadlineSeconds: 1200`, `backoffLimit: 2`, and
  `ttlSecondsAfterFinished: 3600`;
- requests 500 millicores, 1 GiB memory, and 10 GiB ephemeral storage; and
- is limited to 2 cores, 4 GiB memory, and 20 GiB ephemeral storage.

Presigned URLs expire after 30 minutes, longer than the Job deadline. Keys and expiry are fixed by
the registry. R2's strongly consistent object metadata makes a successfully uploaded completion
marker immediately readable by the reconciler. The design does not rely on ETag as a SHA-256
digest, and it does not rely on CopyObject preserving or generating SHA-256 checksum metadata.

## Observability

Metrics:

- derive attempts by terminal outcome and failure scope;
- active DRAFT/DERIVING versions;
- queue-to-start, derive, and total publish duration;
- automatic retry and manual retry counts;
- reconciler repair count;
- source, expanded, and derived byte sizes; and
- catalog cache state by channel without including tokens or presigned URLs.

Logs include map address, version, attempt UUID, Job name, state transition, failure scope/code, and
duration. They never include bearer tokens, R2 credentials, presigned query strings, raw scene
contents, or arbitrary archive file contents.

Readiness requires Postgres, R2 metadata operations, Kubernetes Job API access when derive is
enabled, and at least one validated PackSet snapshot or cache. An in-flight CDN refresh failure is
degraded health when a valid cache remains, not unready.

## Testing

### Worker unit and integration tests

- missing scene yields deterministic NONE metadata;
- valid scene is canonicalized and catalog-validated;
- malformed JSON and every representative catalog failure retain structured sorted problems;
- duplicate paths, traversal, absolute paths, link types, archive limits, and truncated streams are
  rejected;
- two identical source/catalog inputs produce identical bundle, manifest, and Scene SHA-256 bytes;
- source digest mismatch is rejected before output publication; and
- the result marker is uploaded only after the other two result objects.

### Registry integration tests

Postgres and MinIO cover commit, claim, success, failure, system retry, restart reconciliation,
idempotent duplicate events, public promotion ordering, exact-version polling, legacy no-scene row
migration, fork metadata copying, pin, and rollback. A fake Job gateway deterministically simulates
watch loss and poll repair; Kubernetes itself is not required for ordinary unit tests.

PackSet tests use signed-by-fixture channel/manifest/catalog bytes and prove Stable, Edge, cache
reuse, hash mismatch, unsupported exact catalog, and temporary transport failure behavior.

### Build-server contract tests

Tests prove that push never calls client-supplied publish, polling stays off the main thread,
PUBLISHED records the link, DERIVE_FAILED renders bounded useful errors, timeout does not create a
second version, and a network retry after commit only polls the known version.

### Infrastructure tests

Pulumi tests assert the minimal Role verbs, both ServiceAccounts' token behavior, exact worker image
tag, resource/deadline limits, derive configuration, secret absence from Job values, and lifecycle
coverage for `tmp/derive/`.

## Rollout

1. Release `service-maps` with migrations, API additions, worker entry point, and derive disabled by
   default. Existing publish behavior remains temporarily available.
2. Apply `grounds-pulumi` RBAC, worker configuration, lifecycle, and new service image/chart.
3. Release and deploy `buildsystem` with `derive=true` and polling.
4. Publish and pin a no-scene fixture and a valid safe-action Scene fixture on Stage; verify a
   deliberately invalid scene fails without changing the current pin.
5. Enable `deriveRequired` for source-backed commits on Stage and confirm the old publish path is
   rejected.
6. Repeat the acceptance set for Production before enabling `deriveRequired` there.
7. Remove the temporary legacy source-backed publish compatibility in the next normal
   `service-maps` release after all build servers use the derive client.

No tag is created manually. Release Please owns release PRs and tags in every repository.

The R2 implementation must be checked against Cloudflare's current
[S3 compatibility matrix](https://developers.cloudflare.com/r2/api/s3/api/) during implementation.
In particular, the design treats R2 as strongly consistent but does not treat ETag as SHA-256 and
does not assume CopyObject checksum support.

## Acceptance criteria

- A map with no scene derives, publishes, pins, and rolls back exactly as before.
- A valid present scene is canonical in the derived bundle and its exact metadata is exposed by the
  API.
- An invalid present scene produces DERIVE_FAILED with structured problems and no public pin change.
- Unknown assets and application actions fail closed.
- Stable and Edge catalogs resolve through validated immutable PackSet data without a hardcoded
  resource-pack version.
- Identical inputs produce byte-identical outputs.
- A lost terminal Job event is repaired by polling.
- A service restart resumes every unfinished version without duplicate publication.
- A fork shares bytes and copies scene metadata atomically.
- Pin and rollback move the map world, map configuration, and optional scene as one version.
- The worker cannot write public R2 objects and receives no reusable cloud credential.
- The old source-backed client-publish path is disabled after the compatible rollout.

## Documentation updates after delivery

After Stage acceptance, update the master Confluence page to mark Phase 5 delivered, link the
repository specification and implementation plan, record release/PR evidence, and leave portal UI,
Paper editing, action distribution, and Minestom runtime in their existing later phases.
