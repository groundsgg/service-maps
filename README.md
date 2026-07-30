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

## Not yet true, and load-bearing

<!-- Both of these are why there is no chart and no Argo application in this repository. -->

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
