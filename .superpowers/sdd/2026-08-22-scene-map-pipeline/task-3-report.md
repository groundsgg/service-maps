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

## Fix round 1 — subtask A

### RED

Added source-I/O classification regressions, then ran:

```text
./gradlew test --tests gg.grounds.derive.SafeTarZstdReaderTest --tests gg.grounds.derive.SceneDeriverTest
```

The pre-fix code classified the injected source `IOException` as `ArchiveContentException`.

### GREEN

Replaced Commons Compress on the untrusted input path with an owned 512-byte physical tar parser;
it validates checksums, extension metadata, declared payload/padding, physical limits and exact
two-record termination. The compressed source is disk-spooled and memory-mapped so the one zstd
frame must consume the complete file. Scene bytes are independently limited and canonical output
uses an out-of-tree temporary path. Content defects are converted to CONTENT only; source and
ordinary disk I/O propagate.

Verified with:

```text
./gradlew test --tests gg.grounds.derive.SafeTarZstdReaderTest --tests gg.grounds.derive.SceneDeriverTest --tests gg.grounds.derive.DeriveContractsTest
./gradlew spotlessApply
./gradlew spotlessCheck testClasses
```

## Subtask A review fix

### RED

The scoped review found that the parser's catch-all `IOException` conversion incorrectly marked
local output failures as content, and that synthetic extension fixtures never wrote their declared
payload. Regression coverage was added for real PAX/GNU paths and metadata, PAX arithmetic and
one-entry state, scene temp-name collision, and injected spool-output I/O.

### GREEN

Only `ZstdIOException` is now content-classified at the decompressor boundary; source and ordinary
filesystem I/O propagate. PAX record lengths use checked `Long` arithmetic and local PAX has an
explicit pending state that must be consumed by the next file/directory. The physical fixtures now
write exact extension payload bytes and padding.

Verified with:

```text
./gradlew spotlessApply
./gradlew spotlessCheck testClasses
./gradlew test --tests gg.grounds.derive.SafeTarZstdReaderTest --tests gg.grounds.derive.SceneDeriverTest --tests gg.grounds.derive.DeriveContractsTest
```
