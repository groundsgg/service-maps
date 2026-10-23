# Buildserver Working Copy Design

Status: draft, 2026-10-23

## Goal

Builders' worlds on the Stage buildserver survive a pod restart, a rollout and the loss of the
node, with at most about one minute of work lost — without a PersistentVolumeClaim on the
satellite and without moving the buildserver to core.

## Why this shape

- **No PVC.** `deny-persistent-storage-in-environments` (grounds-pulumi
  `satellite/src/platform/residency.ts`) refuses every PVC in an environment namespace, and the
  satellite has no StorageClass. Both are deliberate: a satellite node can be wiped at any time.
- **No game server on core.** The buildserver stays where it runs today.
- **So the state has to leave the pod continuously.** Data at rest moves to the EU-jurisdiction
  private R2 bucket and core's Postgres — the same place map versions already live.

Today the pod's disk is ephemeral and the only protection is a manual 30-second preboot window
(`args: sleep 30` in deploy `environments/stage/components/buildserver/values.yaml`, #249). This
design replaces that window.

## What exists, and what is new

| Exists | Where |
|---|---|
| Presigned PUTs into the private bucket; the bytes never pass through the service | `BlobStore.presignPut` |
| Keycloak client-credentials identity for the buildserver, in the `builder` group | `GROUNDS_MAPS_CLIENT_SECRET`, already mounted on Stage |
| A registry client in the plugin with token handling and presigned uploads | `RegistryClient`, `TokenSource` |
| The definition of what a world is, and what is not part of it | `WorldArchive.EXCLUDED`, the nested-`dimensions` guard |

New:

- **Per-file, content-addressed upload.** The registry today accepts one whole `tar.zst` per
  version. A working copy uploads individual files by SHA-256 and only those the bucket does not
  already have. That is new API surface in `service-maps` and a new prefix in the bucket.
- **A snapshot manifest** that becomes current atomically.
- **A sync loop and a boot-time restore** in `GroundsMaps`.

## Non-goals

- **Not a map version.** A snapshot is a backup of a *server*, not a publish. It never becomes
  pinnable, never goes through derivation, and does not appear in `/map versions`. `/map push`
  is unchanged.
- **No new world format.** Files are stored as Paper writes them (Anvil). GWF (`grounds-world`)
  is a loading format for game servers, not a backup format, and converting would lose whatever
  FAWE, Axiom or Paper keep beside the block data.
- **No complete fix for torn region files** (see *Consistency*). Unload, pack and reload stays
  the only complete fix, as in BuildSystem `GROUNDS.md` *Known gap*.
- **Not multi-writer.** One buildserver per server id. Two pods with the same id are a
  misconfiguration that the commit precondition below catches instead of resolving.

## Data model

A snapshot is keyed by **server**, not by map: most build worlds are never linked to a map, and
plugin state belongs to the server.

```
server id      GROUNDS_PERMISSION_SERVER_ID      e.g. "buildserver"
  snapshot     monotonically increasing number
    file       path relative to /app  →  sha256, size
```

The paths cover:

- every world folder BuildSystem knows about (`world/` and
  `world/dimensions/minecraft/<name>/`): `region/`, `entities/`, `poi/`, `data/`, `level.dat`,
  `grounds/`, minus `WorldArchive.EXCLUDED` (`playerdata`, `stats`, `advancements`,
  `session.lock`, `uid.dat`). The walk of `world/` skips its `dimensions/` child: since Paper
  26.1 the main world's folder physically contains every other world, and each of those is
  walked as its own root, as `WorldArchive` does;
- `plugins/BuildSystem/*.yml` (worlds, folders, categories, statuses, players, …);
- `plugins/GroundsMaps/links.yml`;
- the data folders of the builder tools that `buildserver/scripts/start.sh` already lists as
  durable (FAWE, VoxelSniper, Axiom, goPaint, CreativeUtilities, EasyArmorStands), restricted to
  configuration and schematics, not caches.

Plugin JARs and server configuration come from the image and are never in a snapshot.

### Postgres (`service-maps`)

```sql
CREATE TABLE workspace_snapshot (
    server       TEXT        NOT NULL,
    snapshot     BIGINT      NOT NULL,
    state        TEXT        NOT NULL,        -- 'open' | 'committed'
    manifest     JSONB       NOT NULL,        -- [{path, sha256, size}], sorted by path
    file_count   INTEGER     NOT NULL,
    total_bytes  BIGINT      NOT NULL,
    created_by   TEXT        NOT NULL,        -- token sub
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    committed_at TIMESTAMPTZ,
    PRIMARY KEY (server, snapshot)
);

CREATE TABLE workspace_head (
    server   TEXT   PRIMARY KEY,
    snapshot BIGINT NOT NULL,
    FOREIGN KEY (server, snapshot) REFERENCES workspace_snapshot (server, snapshot)
);
```

The manifest is a JSONB column rather than a file table: it is always read and written whole,
and a few thousand entries are a few hundred KB.

### R2

```
grounds-hetzner-maps (private, EU)
  workspace/<server>/blob/<sha256>
```

A separate prefix from the registry's `blob/`, for two reasons. The registry's orphan collector
diffs `blob/` against map manifests and would delete working-copy blobs it does not know
about. And working-copy retention (below) is time-based, which the registry rules out for
`blob/` on purpose.

Content addressing per server means an unchanged region is uploaded once and then never again.
A builder usually touches a handful of regions per minute.

## Sync protocol

```
POST /v1/workspaces/{server}/snapshots
  { "base": 41, "files": [{ "path": "...", "sha256": "...", "size": 123 }, ...] }
→ 201 { "snapshot": 42, "missing": [{ "sha256": "...", "url": "<presigned PUT>" }, ...] }

(client PUTs every missing blob)

POST /v1/workspaces/{server}/snapshots/42/commit
→ 200 { "head": 42 }
→ 409 if head is no longer 41, or a blob is still missing

GET  /v1/workspaces/{server}/head
→ 200 { "snapshot": 42, "files": [{ "path", "sha256", "size", "url": "<presigned GET>" }] }
→ 404 if the server has never committed
```

Exact contract, shared by the registry, the plugin and the restore step:

- `{server}` matches `[a-z0-9][a-z0-9-]{0,62}`. It comes from `GROUNDS_WORKSPACE_SERVER` on the
  buildserver (Stage: `stage-buildserver`). **That variable is the on-switch**: unset, the
  restore step does nothing and the plugin does not sync.
- `base` is the snapshot number the client last restored or committed, or `null` when the
  server has none (head answered 404).
- `path` is relative to `/app`, uses `/`, has no empty, `.` or `..` segment, and matches
  `[A-Za-z0-9._/-]{1,512}`. Paths are unique within a manifest. `sha256` is 64 lowercase hex
  characters, `size` ≥ 0. At most 20 000 files.
- `shrink` (boolean, default `false`) travels in the `POST …/snapshots` body.
- A request whose `base` differs from the head is refused at create time with `409` as well,
  so a client finds out before uploading anything.
- Presigned URLs live 30 minutes, the registry's existing default. A PUT to a `missing` URL must send exactly the bytes whose
  SHA-256 was announced; commit verifies presence and size with `HEAD`.
- Errors are `application/problem+json` like the rest of the registry.

Buildserver environment:

| Variable | Used by | Default |
|---|---|---|
| `GROUNDS_WORKSPACE_SERVER` | restore, plugin | unset = off |
| `GROUNDS_MAPS_CLIENT_SECRET` | restore, plugin | required when on |
| `GROUNDS_MAPS_REGISTRY_URL` | restore | `https://api.grounds.gg` |
| `GROUNDS_MAPS_TOKEN_URL` | restore | `https://account.grounds.gg/realms/grounds/protocol/openid-connect/token` |
| `GROUNDS_MAPS_CLIENT_ID` | restore | `buildserver-maps` |

The plugin takes registry and token URLs from its `config.yml`, which carries the same values.

- **`missing`** is decided by `HEAD` on `workspace/<server>/blob/<sha256>`, or by a cached set of
  hashes known to be present. The first sync uploads everything; later syncs upload the
  difference.
- **Commit is the atomic step.** It checks that every blob in the manifest exists with the
  recorded size and moves `workspace_head` in the same transaction, conditional on `head = base`.
  Until then the previous snapshot is what a restore gets. A crash in the middle of an upload
  leaves an `open` row and some blobs. The open row is discarded and the blobs are reused by the
  next attempt.
- **Authorization:** the existing `builder` group (`grounds.maps.authz.author-groups`). The
  buildserver already has it through its service account, so the loop runs unattended and needs
  no new secret.

### Guard against overwriting good state

The worst failure this design can cause: a pod boots with empty worlds because the restore
failed or was skipped, the loop starts, and an empty snapshot replaces a good one. Two guards:

1. **The plugin** does not start the loop until a restore has succeeded, or the registry has
   answered 404 on `head`. The restore runs before Paper (see *On boot*), so the two are
   connected by a marker file: `start.sh` writes `/app/.workspace-head` containing the restored
   snapshot number, or `none` after a 404, and only after every hash verified and every file is
   in place. At enable the plugin reads it and uses it as `base`. If the marker is missing, or
   the registry's head disagrees with it, the plugin logs an error and does not sync. That makes
   a bypassed wrapper fail safe: the Deployment's `args` already override the image command
   today (`sh -c "sleep 30; exec sh /app/start.sh"`), and a future edit there must not lead to
   empty worlds being pushed.
2. **The registry** refuses to open a snapshot whose `total_bytes` is below 50 % of the head's, or that
   drops a world (a `level.dat`) the head has, unless the request carries `"shrink": true`. The
   plugin sets it only when BuildSystem itself deleted a world in that interval.

## Buildserver behavior (`GroundsMaps`)

### Every minute

1. `World.save()` for every loaded world on the main thread. BuildSystem's YAML stores write
   each file atomically (`YamlStore.atomicSave`) but off the main thread, so a world created a
   moment ago can be in the region files one tick before it is in `worlds.yml`. Harmless: the
   next tick carries it.
2. Off the main thread: walk the paths above and hash every file whose size or mtime changed
   since the last pass (a size/mtime cache keeps this cheap).
3. Skip files modified in the last 5 seconds. They will be picked up next time.
4. `POST` the manifest, upload `missing`, and **hash every uploaded file again**. If a hash
   changed during the upload, abandon this snapshot and try again in the next tick.
5. Commit.

A tick that is still running when the next one is due is skipped, never queued. The interval is
`workspace.interval-seconds` in the plugin config, defaulting to 60.

### On shutdown

`onDisable` runs one final pass after Paper has saved its worlds, bounded to 20 seconds.
The chart sets no `terminationGracePeriodSeconds`, so the Kubernetes default of 30 seconds
applies and the final pass is best effort: if it does not finish, the last committed snapshot is
at most a minute old.

### On boot

The restore must finish **before Paper opens a world**, so it cannot live in the plugin. It
moves to the image's start wrapper (`containers/buildserver/scripts/start.sh`) as a small
step. The Paper base image removes `curl` and `jq` after its build, so the buildserver image
either adds them back or runs the restore as a single-file Java program (`java Restore.java`)
with the JVM it already has:

1. Get a token (client credentials) and `GET …/head`.
2. 404: a fresh server. Start Paper.
3. Otherwise download every file into `/app.restore/`, verify every SHA-256, then move each
   top-level path into place.
4. **Any failure aborts the boot.** The pod crash-loops visibly instead of starting with empty
   worlds that the loop would then push.

This replaces the `sleep 30` preboot window.

**New boot dependency.** The buildserver no longer starts while `api.grounds.gg` or Keycloak is
unreachable; the crash loop is the retry. Besides `GROUNDS_MAPS_CLIENT_SECRET`, the wrapper needs
the token URL, the client id and the registry base URL as environment variables. The plugin reads
the same values from its config today.

## Consistency

`World.save()` hands chunks to Paper's asynchronous chunk system and does not wait for every
region file to be written. FAWE and Axiom can write regions outside Paper's save path. So a
region file can be hashed while it is being written, and restored torn.

The mitigations above (mtime quiet period, re-hash after upload) catch nearly every case. What
remains:

- A region that is mid-write at *both* hashes. That needs two identical torn reads, which is
  unlikely.
- A snapshot that is consistent per file but not across files: an entity file one tick ahead of
  its region. Minecraft tolerates this, as it does after a crash.

## Retention

The registry keeps the head plus the last 30 committed snapshots per server, and additionally
one per day for 14 days. A daily job deletes older rows and then deletes
`workspace/<server>/blob/*` objects that no remaining manifest names. That job dry-runs by
default, like the existing orphan collection.

At an expected 1–5 GB per server, storage and request costs are negligible for one server.

## Alternative considered: `rclone sync` in the start wrapper

A sidecar or wrapper running `rclone sync /app/world r2:…` every minute is less code. It was
rejected because a sync is not a snapshot: a crash halfway leaves the bucket holding half the
old state and half the new, and nothing records which. The manifest commit above is exactly
what makes a restore either the last good state or nothing.

## Rollout

1. `service-maps`: migration, three endpoints, retention job. Contract tests for commit
   atomicity, the shrink guard, and `409` on a moved head.
2. `GroundsMaps`: sync loop, on only when `GROUNDS_WORKSPACE_SERVER` is set. Shutdown pass.
3. `containers/buildserver`: restore step in `start.sh`, with a test image that boots against a
   registry fixture.
4. `deploy` Stage: set `GROUNDS_WORKSPACE_SERVER`, drop `sleep 30`, and
   update `tests/test_stage_buildserver.py`, which asserts the component's shape.
5. **Before the first restore matters:** take a manual `/map push` or BuildSystem backup of
   every world. Then delete the pod and confirm every world, the navigator entries and the map
   links come back.

## Acceptance criteria

- Deleting the buildserver pod loses at most the last minute of edits. Worlds, BuildSystem
  navigator entries, `/map link`s and POIs all survive.
- A pod that cannot restore does not start Paper.
- A buildserver with empty worlds cannot replace a non-empty head without `shrink`.
- An idle buildserver uploads no blobs (only the manifest call).
- Nothing persists on the satellite node.

## Open questions

- Is `GROUNDS_PERMISSION_SERVER_ID` stable enough to be the snapshot key, or should the server
  id be its own setting?
- Should `/map restore <snapshot>` exist in-game for rolling back a single world, or is that what
  BuildSystem's own backups are for?
- Does `grounds-world` / GWF have a future at all? It is not used here.
