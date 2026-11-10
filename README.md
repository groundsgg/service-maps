# service-maps

The map registry. It owns the answer to two questions:

- **which content hash is which version of which map**, and
- **which version players currently get**.

Bytes never travel through this service. They live in R2 keyed by their own sha256;
clients upload with a presigned PUT and read from `cdn.grounds.gg`. Game servers do not
call this service at all — they read `pins/<env>.json` and the bundles it points at
straight off the CDN, so a pod can boot with this service down.

Architecture: [Maps & Asset Distribution](https://grounds.atlassian.net/wiki/spaces/GARCHITECT/pages/251723780)
· [Build Server & Builder Experience](https://grounds.atlassian.net/wiki/spaces/GARCHITECT/pages/251822081)

## Why it deviates from the other services

| | The gameplay services | Here |
|---|---|---|
| Transport | gRPC on `:9000` | **HTTP + JSON** on `:9000` — the callers are the portal's BFF proxy, the build server's Paper plugin and staff tooling |
| Auth | projected ServiceAccount token, verified against the k8s JWKS | **Keycloak bearer** — the build server runs on the grounds-dev spoke while this runs on core, and a k8s token from one cluster does not verify against the other's JWKS |

Everything else follows the house shape: Quarkus/Kotlin, Flyway into its own schema,
plain JDBC over the injected `DataSource`, distroless Java 25 runtime, the shared CI
workflow.

## Who may do what

| Action | Granted by |
|---|---|
| Create a map, upload, commit a version, fork | a group in `author-groups`, **or** owning the `u/<sub>` namespace |
| Publish a version (makes it pinnable) | a group in `publish-groups`, **or** owning the namespace |
| Move a pin — change what players load | a group in `golive-groups`. **Never** by ownership |
| See another creator's maps | a group in `review-groups` |

Publishing and going live are separate because they are different risks: publishing changes
nothing anyone sees, moving a pin changes what every player loads. A creator can do the first
in their own corner and never the second.

`service-permissions` is deliberately not in this path — it answers what a *player* may do
in-game, scoped per environment. Using it to gate a staff HTTP API would be the same word for
two different questions.

## Addressing

A map is `<namespace>/<name>`, e.g. `bedwars/4x4-baumhaus`. The namespace is normally a
gamemode, `grounds` for platform-owned maps, or `u/<creator>` for creator content — so
**a namespace may itself contain a slash**, and an address is not reliably two segments.
Parsing splits at the *last* slash, and the API takes the whole address as one catch-all
path parameter. Splitting from the left would file every creator map under namespace `u`.

The namespace is not part of any R2 key. Keys are content-addressed, so renaming a map
moves no bytes.

## Build and test

```bash
./gradlew build -Pgithub.user="$GITHUB_ACTOR" -Pgithub.token="$GITHUB_TOKEN"
```

Tests start a real Postgres with Testcontainers and run the Flyway migration into it, so
the schema and its constraints are under test rather than stubbed. `@TestSecurity`
supplies the identity; there is no Keycloak in a test run.

## Asynchronous source derivation

`MAPS_DERIVE_ENABLED=false` is the compatibility default. With it enabled, every commit that
names a private uploaded source is accepted as a DRAFT, then moves to `DERIVING`; clients poll
`GET /v1/maps/{address}/versions/{version}` until it reaches a terminal state. The returned
version includes derive attempt/failure fields plus a `scene` projection (`PENDING`, `INVALID`,
`NONE`, or `VALID`) with catalog references, ordered required actions, and ordered diagnostics.

`POST /v1/maps/{address}/versions/{version}/derive/retry` is for retryable SYSTEM failures only
and requires the normal publish authority. It returns `202` and a new DERIVING attempt; a failed
Job submission remains a reconciliation candidate rather than becoming a falsely published map.

During rollout, `derive: true` is accepted on a commit. Setting `MAPS_DERIVE_REQUIRED=true`
requires `MAPS_DERIVE_ENABLED=true` and rejects source-backed commits that omit `derive: true`.
Once derivation is enabled, the legacy `/publish` endpoint rejects source-backed versions: forks
and source-less compatibility versions may still use it, but uploaded source bytes are never
promoted by that path.

## Explicit immutable catalog metadata

The normal derivation candidates remain the current Stable and Edge PackSet catalogs. To retain
an exact private or historical catalog independently of moving channels, operators may configure
at most eight additional references in `derive.catalog.pinned` (or `MAPS_CATALOGS_PINNED_JSON`).
Each entry has the worker candidate fields `channel`, `id`, `version`, `coordinate`, `file`,
`uri`, `sha256`, and `size`:

- `channel` is `pinned`; `id` is `grounds:resourcepacks`.
- `coordinate` is exactly `gg.grounds:resourcepacks-catalog:<version>`.
- `size` is positive and at most 32 MiB; `sha256` is 64 lowercase hexadecimal characters.
- `file` is a safe JAR basename. The trusted HTTPS CDN URI has the form
  `<baseUri>/resourcepacks/catalog-pins/<scope>/<version>/<sha256>/<file>`, without query,
  fragment, user information, explicit port, percent escapes, or path traversal.

The full JSON is bounded to 64 KiB. Identical candidate identities/content collapse while
preserving channel precedence. Conflicting configured pins fail startup. If a channel refresh
conflicts with a pin, the pin is withheld and the existing channel candidates remain available;
the `packset-catalogs` readiness data reports `pins.configured` and `pins.conflicts`. Readiness
continues to depend on a validated channel, not merely configured metadata.

These references are trusted executable catalog JARs, not client-supplied URLs. Only publish
neutral metadata at a new immutable path and use a distinct identity when private definitions
differ from a public catalog. Existing digest, archive, owner, scene and action validation is
unchanged. This adds metadata to the shared derivation service; map/environment pins still
control where a map is deployed. No PackSet channel or Minecraft client pack is changed.

## Lit copies

With derivation and lighting enabled, every **published** bundle gets a prepared light copy
before its first live pin. The standalone JVM worker (`lit-worker/`) uses the same `MapLight`
and Minestom build as the game servers. Its accepted result is published content-addressed beside
the source bundle. No upload moves a live pin automatically.

The exact-version response carries `preparation`: `QUEUED`, `DERIVING`, `AWAITING_REVIEW`,
`WAITING_FOR_LIGHT`, `LIGHTING`, `READY`, `FAILED` or `UNAVAILABLE`, together with the required
`lightFormat`. Raw `PUBLISHED` means the immutable bundle exists; only `READY` means it can go
live. The build-server client waits for that confirmation and reports the current step.
A live-pin request before readiness returns 409 without changing the pin or its history.
Explicitly disabled preparation retains the legacy publication path and reports `UNAVAILABLE`.

A pin file entry names the accepted prepared transport:

```json
"lit": {"format": 2, "bundleSha256": "…", "bundleUrl": "https://…/bundle/sha256/…tar.zst"}
```

Versions and source digests never change. `map_lit` deduplicates by bundle digest and light
format. One worker runs at a time; failed attempts are retried up to three times. Three new
bundle claims prefer recent uploads and the fourth serves the oldest eligible work. Existing
records preserve this position across API restarts. A shared digest's raw and prepared objects
are made available in each required published trust bucket, including cross-trust forks.
Lighting fills `present_chunks` and `est_loaded_mib`. `MAPS_LIT_FORMAT` must equal the worker's
`MapLight.FORMAT`; raising both prepares published bundles again. Existing pinned environments
are republished as their prepared copies become available.

## Offline LOD worker

`lod-worker/` encodes stored overworld chunks into bounded, deterministic LSS protocol-20
column tiles. It verifies the input bundle, rejects unsupported archive metadata and chunk
schemas, and publishes a local output directory atomically. It has no listener or cloud
credentials. The pinned upstream codec and MIT license are documented in
`lod-worker/UPSTREAM.md`; the unchanged wire code and MIT license ship in `lod-format/`.

Build with `./gradlew :lod-worker:test :lod-worker:installDist`, then run the installed
`lod-worker` command with `--bundle <map.tar.zst> --sha256 <digest> --output <new-directory>`.
Input is ordinary USTAR; GNU/PAX extension headers require explicit bounded support.
The `--identity` command reports the immutable source/dependency generator fingerprint and
registry fingerprint without generating terrain. With no CLI arguments, the same executable
accepts `LOD_REQUEST_JSON`: one scoped input GET and exactly three scoped output PUTs (bundle,
manifest, result-last); it carries no R2 credentials and performs no automatic retry.

LOD scheduling and public publication default off independently (`lod.enabled`,
`lod.publication.enabled`). Admission needs both an existing hostname in `lod.node` and an
explicit `lod.runId` registered using `scripts/register-lod-pilot.sql`. That script accepts
only the measured stage Lobby v13 canonical and prelit hashes and never resets existing
campaign counters. No boot, join, pin change or client request registers a run or backfills maps.
The immutable worker image must contain this worker; obtain its generator/registry fingerprints
using `--identity` before registration. Registration expires after 30 minutes.

PostgreSQL reserves at most two cloud jobs and one GiB of maximum retained outputs per campaign;
reservations survive failures/restarts and are never refunded automatically. Only one generator
is admitted globally. Jobs are created suspended, activated under the current database claim with
UID/revision fencing, and constrained to 2 CPUs, 2 GiB RAM, 4 GiB scratch and 900 seconds with no
Kubernetes retries. A stopped job keeps the global slot until both the Job and its pods are gone.
The scoped service Role must additionally allow Job patch/delete and pod list before admission.

A verified result can remain private. Public promotion is separate and preserves own/UGC trust
buckets; only a published artifact matching the exact canonical **and current prelit** source
appears as optional `lod.artifacts[]`. Entries without LOD retain their existing JSON shape.
Projection retries are durable. Optional LOD lookup failure still publishes the base map pin.
The existing pin publisher remains single-replica; global generator admission does not change
that deployment requirement. Public LOD HTTP qualification, runtime delivery limits and real
Voxy/DH client acceptance remain separate integration steps; keep public publication off until
those paths have been qualified. Finished artifacts are not deleted by an age-based lifecycle.

The finite Stage controller additionally sets `MAPS_LOD_PILOT_ISOLATED=true`. Its REST
filter permits only complete private pilot operation paths, even over direct Pod/Service
addresses and with a staff JWT. Use a dedicated restricted namespace, never the live Maps
namespace, because its namespaced Job permissions can mount any secret in that namespace.
Keep its database ephemeral and separate; import only the exact published pilot metadata.

`MAPS_R2_SESSION_TOKEN` enables temporary S3 session credentials. The isolated process reads
first-party input from the immutable HTTPS CDN, so credentials need only the private bucket's
`tmp/lod/` and `lod/first_party/lss20/` prefixes. Set public/UGC bucket names to unused sentinels;
never give this process the long-lived Maps storage credential. Keep publication disabled.

In isolated mode `MAPS_LOD_PILOT_OWNER_POD_NAME` and `MAPS_LOD_PILOT_OWNER_POD_UID` are required
Downward API values: even suspended worker Jobs are owned by the API Pod and collected when
it is removed. Worker image pulls are disabled (`Never`); prefetch verified digest-pinned images
on the selected existing node without copying the registry credential into the pilot namespace.
Bound the API Job to 1800 seconds with a 300-second cleanup TTL and every container's CPU,
memory and ephemeral storage. Deactivate the serving row, disable the infrastructure flag,
and remove the dedicated namespace after measurement. A later infrastructure reconciliation
can recreate an enabled expired Job, so leave the flag disabled; no startup path seeds quota
or run rows. Capture counters before deleting its temporary database.

The populated `ci/verify-native.py` contract runs the packaged Native API, actual JVM LOD worker,
TLS object storage, PostgreSQL and a Kubernetes fixture through source-pinned publication.

## Not yet true, and load-bearing

<!-- Both of these are why there is no chart and no Argo application in this repository. -->

- **A Keycloak client `service-maps` has to exist**, and its tokens must carry
  `aud: service-maps`. Without the audience check this service would accept any token the
  realm signed, including one minted for another client entirely. Adding the client touches
  the realm import, which is known to rotate other clients' secrets on re-import — so it is
  a deliberate step, not a side effect of a deploy.
- **Authorization is by Keycloak group, and the groups have to exist.** The token's `groups`
  claim decides staff actions and ownership decides creator ones; which group grants which
  action is configuration (`MAPS_AUTHOR_GROUPS`, `MAPS_PUBLISH_GROUPS`, `MAPS_GOLIVE_GROUPS`,
  `MAPS_REVIEW_GROUPS`). The defaults name `grounds-staff`, `map-authors` and `map-reviewers`,
  which do not exist in Keycloak yet — until they do, only creators acting inside their own
  `u/<sub>` namespace can do anything at all.
- **Single replica.** The pin file is rebuilt in full and published under a per-environment lock,
  which orders concurrent moves *within one process*. A second replica can still have two moves
  land out of order and leave the CDN behind the database. Two replicas need a conditional write
  or a reconciler first.

## Deploying it

The chart in `chart/` is published to `oci://ghcr.io/groundsgg/charts/service-maps` from the
release tag, so a chart version always names an image that exists. What it does **not** carry,
because a chart that mints secrets puts them in the release history:

- `existingSecret` — the R2 credentials, provisioned as an `InfisicalSecret`.
- `database.existingSecret` — the application user, generated by CNPG.
- `config.r2.endpoint` — **required**, and rendering fails without it. An EU-jurisdiction
  bucket answers on its own endpoint host, and getting it wrong is a 404 on a bucket that
  plainly exists.

`replicas: 1` and `strategy: Recreate` are correctness settings, not capacity ones — see the
note on ordering below.

The API exposes Prometheus metrics at `/q/metrics` on its HTTP port (9000 by default),
including HTTP requests and derive reconciliation counters, timers and active candidates.
With Prometheus Operator installed, set `monitoring.serviceMonitor.enabled=true` to scrape
the release's Service every 30 seconds. It defaults off so the chart also installs without
the operator CRDs. The public API gateway only routes `/v1/maps` and `/v1/workspaces`.

## Status

Phase 1. Implemented: the schema; create, list and read for maps; presigned uploads;
version commit and publish; forks; pin moves and the published `pins/<env>.json`. Not yet
implemented: the derive Job and its watch, moderation and review, leases and stateful
snapshots, quotas.

> **The derive-Job watch must ship with a poll reconciler in the same commit.**
> `grounds-forge`'s build informer drops terminal Job events today, and a developer
> notices and retries by hand. A builder cannot notice and cannot retry — they see an
> unexplained hang. A watch is an optimisation; the reconciler is the correctness
> mechanism.

### Runtime compatibility at live activation

`/map push` prepares a version and its light data. The Portal remains the manual live
activation surface. `runtimeCompatibility.environments` enables an additional API gate:
authored scenes need a matching target contract for the environment and map address.
A refusal returns HTTP 409 with its reason in `detail` before changing the pin, history,
CDN pin file or NATS announcement. Existing lobbies keep serving their current map.
Maps without scenes remain usable unless a target declares `requireScene: true`.

Each operator-owned target contains `environment`, `address`, immutable `image` digest,
`schemaVersions`, exact `assetCatalog`, accepted `actionCatalogs`, `supportedActions`,
and optional `requireScene`/`normalizeUploads` booleans. Maintain this metadata alongside
its runtime image deployment; it is a capability declaration, not a trial server or a
startup guarantee. Map uploads cannot supply or override it. Enabled environments with
missing scene contracts fail closed.

A target with `normalizeUploads: true` selects the canonical upload catalog from the
existing trusted catalog candidates. All normalization targets must agree on that catalog.
The derive worker validates the authored scene against its exact source catalog, verifies
that the target retains the referenced source definitions' kind, bounds, metadata and animations,
and validates again after projecting only the catalog reference. The original upload and
workspace remain unchanged. Incompatible catalogs retain their original reference so
preparation can finish and the Portal can explain why live activation is blocked. No
runtime code is loaded from author-supplied locations. Existing immutable versions are
not rewritten; republish the source as a new version to synchronize it.

Private runtime profiles can declare `requiredElements` (element ID → structural SHA-256)
and `elementCounts` (ID prefix → count). Derivation records only the selected structural
facts, and activation rejects missing/changed facts. Fingerprints include type, body,
look, collision, bounds, rotation and scale; world position, labels and bindings remain
editable. `legacySceneHashes` is a bounded operator allowlist for independently verified
pre-migration scenes; it is not a map-author override or a requirement for new uploads.

Rollout order: deploy the new worker image first, then enable normalization/shape metadata
in the API. Old workers intentionally reject unknown request fields rather than silently
skipping a requested normalization or scene proof. Contracts are scoped to the uploaded
map address; another map is never normalized against a lobby's private target.

## LOD measurement semantics

The optional source relay exports `grounds.lod.source.opens`,
`grounds.lod.source.open.failures`, `grounds.lod.source.read.bytes` and
`grounds.lod.source.transfers` with the bounded outcomes `complete` or `partial`.
Bytes count actual origin-stream reads; the final observation runs once on close
after any in-flight read has finished. Truncated or interrupted downloads retain
their observed bytes. These counters do not claim SDK request billing, successful
remote receipt or wire bytes. Observability errors never change authorization,
retry an origin read, or retain the concurrent-download permit. Durable remote
attempt/byte and client-payload reservations remain independent and irreversible.

The Minestom LOD library requires an access predicate. Lobby binds it to the current
`grounds.lod.receive` permission snapshot and rechecks it before source admission
and packet serialization. Missing rights do not load the optional source. It does
not delete terrain previously cached by Voxy or change normal map delivery.
