# Task 3 report: safe deterministic archive derivation

## Delivered

- Added bounded streaming `.tar.zst` ingestion with portable-path validation, raw-header checks,
  duplicate detection, type/link rejection, configurable size/count limits, and trailing-data
  rejection.
- Added deterministic archive repacking with lexicographic entries and fixed tar/zstd metadata.
- Added Scene v1 decode, injected catalog validation, canonical scene replacement, manifest
  embedding, and non-retryable content outcomes.
- Kept catalog loading out of this task through `SceneCatalogResolver`, which takes the released
  `scene-format` catalog types directly.

## Validation

Red phase was observed before implementation: the two focused test classes did not compile because
the Task 3 types and dependencies did not exist. The final command completed successfully:

```text
./gradlew spotlessCheck testClasses test --tests '*DeriveContractsTest' --tests '*SafeTarZstdReaderTest' --tests '*SceneDeriverTest'
```

The JDK emitted the expected zstd-jni native-access warning; it did not affect task success.

## Review notes

- The released `scene-format:0.1.0` API was sufficient for the injected catalog boundary.
- Its Jackson 3.1 runtime requires `jackson-annotations:2.21`; the Quarkus BOM otherwise forced
  2.20, producing `NoClassDefFoundError: JsonSerializeAs`. The build now pins the compatible
  annotations artifact explicitly.
