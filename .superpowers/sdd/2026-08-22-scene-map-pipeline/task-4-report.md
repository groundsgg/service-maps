# Task 4 report

DONE

- Added the fixed `gg.grounds:resourcepacks-client:0.5.1` GitHub Packages dependency; resolution succeeded with the configured Gradle credentials.
- Added Stable/Edge PackSet clients with separate channel cache paths, deterministic candidate ordering, current-or-degraded snapshot selection, and readiness/channel-state reporting.
- Added bounded HTTPS catalog download, manifest size/digest checks, hostile-JAR entry validation, exact generated owner lookup, isolated closeable classloading with `scene-format` type identity, and exact catalog id/version validation.
- Added the immutable `grounds:actions` v1 empty action catalog and namespace boundary resolver.
- Focused catalog tests plus `SceneDeriverTest` passed. `spotlessApply testClasses` passed.
- Full `./gradlew test` was attempted; its catalog/unit coverage passed, but the suite cannot start `AuthorizationIT` because this environment has no valid Docker/Testcontainers daemon.

## Fix round 1 — subtask 4A

- Activated the Stable/Edge client owner as an application-scoped CDI bean. It starts at Quarkus startup, closes at shutdown, and uses distinct channel caches under the configured root.
- Added readiness reporting: disabled derivation is UP; otherwise readiness requires one current or validated degraded snapshot and exposes both sanitized channel states.
- Made `NamespaceCatalogResolver` an interface with an application-scoped default implementation and reused the derive request's shared catalog candidate model.
- Verified with `spotlessApply`, `testClasses`, `PackSetCatalogProviderTest`, and `NamespaceCatalogResolverTest`.
