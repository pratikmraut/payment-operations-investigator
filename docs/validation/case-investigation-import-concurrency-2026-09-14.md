# Case investigation contract checks and import concurrency — 14 September 2026

The new case workflow has 21 passing independent Java tests: 17 in `CaseInvestigationServiceTest` and four in `CaseInvestigationControllerTest`. These tests use original synthetic evidence, H2 and a mocked worker. They do not establish live inference, real bank integration or answer accuracy.

The tests cover durable queued jobs, one selected immutable evidence version per question, pending/completed idempotency, concurrent duplicate commands, five-job admission, writer/CSRF/tenant checks, strict request bounds, startup/shutdown admission, interrupted-job recovery, and authorization before dispatch and answer persistence. They also exercise provider failures, malformed citations/provenance, additional uncited summary text, changed stored evidence and tampered saved answers. Prior evidence and case metadata must remain unchanged by an investigation.

## Initial full-suite result and narrow correction

The initial full Java run reported **207 tests, zero assertion failures and one error**. All 21 new investigation tests passed. The error was in the existing `ObpmImportTest.simultaneousFirstImportsNeverCreateDuplicateCasesOrSnapshots` test.

Its two first-time imports can interleave as follows:

1. Import B reads that its payment case does not yet exist.
2. Import A commits that case and its immutable source snapshot.
3. B's later snapshot-ID lookup sees A's committed snapshot and raises the existing HTTP 409 `SNAPSHOT_ID_CONFLICT` before reaching the insert's database uniqueness constraint.

The original test permitted a concurrent conflict but caught only `DataIntegrityViolationException`. This was timing-dependent coverage of an existing race, not changing fixture timestamps or a change to import behavior. The narrow correction also accepts **only** `ApiException` with status 409 and code `SNAPSHOT_ID_CONFLICT` in this concurrent first-import path. Every other API exception still fails the test. The subsequent `UNCHANGED` retry and exactly-one-case/exactly-one-snapshot assertions remain in place.

No import service or model implementation changed for this correction. In particular, this does not claim that the existing conflict message precisely describes identical concurrently submitted content; it records and tests the current retry behavior.

## Focused verification

`ObpmImportTest` passed after the test correction: **9 tests, zero failures, zero errors, zero skipped**. Maven finished successfully in 21.062 seconds at `2026-09-13T22:23:42Z` (14 September in the laptop timezone).

The run used the previously cached `poi-reference-lookup-build:20260914` image, Docker `--network none`, and Maven `-B -ntp -o -Dtest=ObpmImportTest test`. No bank or model calls were made. The local log is `runtime/case-investigation-validation/obpm-import-focused.log`.

The subsequent full package run passed **207 tests with zero failures, errors or skips**. The deployed case/browser/model check also completed separately; see the [delivery validation record](case-investigation-2026-09-14.md) for its actual one-call result and semantic limitations. The initial full run remains an error result; structural tests do not establish answer correctness.
