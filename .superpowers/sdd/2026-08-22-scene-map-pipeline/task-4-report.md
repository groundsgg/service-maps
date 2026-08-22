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

## 4A review fix

- Reworked provider construction so CDI and test construction both own exactly the two Stable/Edge clients they receive/create; no shadow clients or orphaned executors remain.
- `testClasses` passed after the ownership correction.

## 4A behavioral test completion

- Added four loopback-HTTP behavioral lifecycle tests covering initial/down readiness, exact Stable/Edge validated activation and separate caches, degraded fallback on server/corrupt failures, served-document mutation, disabled health, and terminal close behavior.
- `./gradlew test --rerun-tasks --tests gg.grounds.catalog.PackSetCatalogProviderTest --tests gg.grounds.catalog.NamespaceCatalogResolverTest` passed: **6 tests completed, 0 failed** (`BUILD SUCCESSFUL`, 13 tasks).
- `./gradlew spotlessCheck testClasses` was executed; it is blocked by an existing formatting violation in `src/main/kotlin/gg/grounds/catalog/PackSetCatalogProvider.kt` (the accepted production owner declaration). The new test file was formatted with `spotlessApply`; no production source formatting change is included in this test-only commit.

## 4A formatter cleanup

- Ran `./gradlew spotlessApply`; the only production change is Spotless’s line break between `PackSetCatalogProvider` and its `internal constructor`, with no behavior change.
- `./gradlew spotlessCheck testClasses` passed (`BUILD SUCCESSFUL`, 17 actionable tasks).
- `./gradlew test --rerun-tasks --tests gg.grounds.catalog.PackSetCatalogProviderTest --tests gg.grounds.catalog.NamespaceCatalogResolverTest` passed: **6 tests completed, 0 failed** (`BUILD SUCCESSFUL`, 13 actionable tasks).
