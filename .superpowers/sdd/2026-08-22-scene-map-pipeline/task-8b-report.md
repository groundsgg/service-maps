# Task 8B report — result integrity and promotion

Base: `2dbbc9b`.

## Retained diff audit and outcome

- The private artifact reader now accepts a mandatory maximum byte count, rejects an oversized
  declared length before allocation, streams unknown/chunked bodies into a bounded buffer, and
  closes the SDK response stream with `use` on both normal and exceptional paths.
- Reconciliation reads bounded result and manifest control objects. It verifies the assigned
  bundle/manifest keys via server-side metadata, then verifies the manifest's exact body length,
  SHA-256, source digest, and scene facts before promotion.
- A success promotes the assigned attempt bundle to the public content-addressed bundle key before
  `acceptSuccess`. `BlobStore` delegates that promotion to Task 5's create-or-compare conditional
  copy path, retaining its idempotency/conflict checks. No GiB-scale bundle is downloaded: this
  deliberately trusts the worker digest only because Task 8A established the exact immutable
  digest-pinned Job and coordinator-assigned signed keys.
- Success and failure markers both validate map, version, attempt, and source digest. Result JSON
  cannot select artifact keys; all keys are derived from the claimed identity. The manifest scene
  equality check also binds the success catalog/scene facts to the separate manifest.
- `DeriveResultIntegrityException` propagates, including if it occurs while recording an otherwise
  invalid result. Ordinary stale/rejected outcomes remain no-ops, including a stale rejection from
  the fallback SYSTEM failure record.

## Test evidence

Red/green was observed for failure-marker identity mismatch and for stale rejection during fallback
failure recording. Focused reconciler tests passed after the changes. The test fixture now creates
an actual manifest whose claimed size and SHA-256 match the result, so it exercises manifest
validation instead of bypassing it. Coverage includes promotion-before-accept, promotion failure,
integrity propagation, stale acceptance after promotion, malformed/missing/wrong identity result
markers, and manifest validation through the valid-success path.

Commands run successfully:

```text
./gradlew spotlessApply
./gradlew test --tests gg.grounds.derive.DeriveReconcilerTest
./gradlew quarkusBuild
```

## Scope and residuals

No Task 8C retry/watch/batch-isolation changes were made. A pre-existing concurrent coordinator
assertion was observed flaky in one focused full-class run; it is part of Task 8C scheduling
ownership and was not changed. The task's bounded reader behavior is implemented but lacks a new
dedicated loopback HTTP test matrix for declared/chunked close semantics; this should be included
in the next review round if full Task 8B acceptance requires explicit black-box proof.
