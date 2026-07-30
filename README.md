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

## Status

Scaffold. Implemented: the schema, and create/list/read for maps. Not yet implemented:
versions, uploads, forks, pins and the pin-file publish, the derive Job and its watch.

> **The derive-Job watch must ship with a poll reconciler in the same commit.**
> `grounds-forge`'s build informer drops terminal Job events today, and a developer
> notices and retries by hand. A builder cannot notice and cannot retry — they see an
> unexplained hang. A watch is an optimisation; the reconciler is the correctness
> mechanism.
