# Release privacy, bounded case histories and durable investigations

Implementation order: **13 → remaining 8 → 6 → 7**, authorized on 18 September 2026. This receipt covers the saved payment-case workflow. The retained export/synthetic baseline and original model settings are separate.

## Changes

- **13 — Source ZIP privacy:** packaging selects reviewed Git-tracked files, applies ignore/private-path/content checks, requires a clean source tree and verifies a reproducible manifest. It never includes the private runtime merely because it is present on disk. CI includes the audit and synthetic packaging tests. See [the release policy](../RELEASE_PRIVACY.md).
- **8 — Remaining case histories:** evidence versions, questions and activity use scoped server-side keyset pages (10 by default, at most 25). Older selected versions and referenced questions are fetched by exact metadata ID. Reports and reviewer selectors page completed questions for their selected evidence. Active questions remain independently discoverable and polled. See [the history contract](../CASE_HISTORY.md).
- **6 — Evidence and knowledge capacity:** selection retains whole original source rows with their original IDs, mandatory group coverage, explicit row references and interpretation safeguards. A saved receipt shows supplied/selected/omitted counts. All rows remain in the immutable evidence version. Knowledge inventories accept up to 1,000 documents per tenant / 3,000 index entries, with a 64 MiB index bound and unchanged embedding compatibility rules. Local readiness does not call an embedding or generation provider. Exact worker serialization is checked after input freezing. See [capacity and limits](../CASE_EVIDENCE_CAPACITY.md).
- **7 — Durable investigations:** Java persists admission before retrieval/preparation, fairly schedules actors on a leased model lane, freezes input and reconciles idempotent SQLite worker receipts. Phases, cancellation, timing and recovery are visible in the workbench. Permanent removal uses a verified worker-cleanup outbox. See [the job protocol](../CASE_JOBS.md).

## Verification

Tests use isolated databases and synthetic source/provider fixtures. They do not invoke the bank or the live model.

| Area | Result |
| --- | --- |
| Java | Full 455-test run: 454 passed; an old race-test spy still intercepted synchronous preparation. It was updated to intercept local readiness before admission, retaining the archive/concurrency assertions. All five lifecycle race tests then passed and the API package built. No product change was needed for that test correction. |
| Frontend | Final full suite: **507 tests passed** across 32 files. Both TypeScript entry points and both production builds passed. |
| Worker | Full suite: **514 passed, 1 skipped**. A subsequent worker cleanup-race fix and regression passed all **15** durable-worker tests. These counts overlap. |
| Privacy | **42 packaging tests passed**. Tracked and eligible untracked source inspection found no private-content findings or forbidden tracked files. |
| Browser | **Five complete Chromium scenarios passed**, covering discovery, evidence, questions, independent review, PDF/lifecycle, drafts, follow-ups, case/history pagination and cancellation after reload. A focused rerun additionally confirmed the UI reaches terminal Cancelled and removes the pending cancellation control. |

The skipped worker test requires a configured real pgvector service; this run does not claim that integration was executed. An existing Starlette/AnyIO deprecation warning and the frontend's large-chunk advisory remain. Earlier frontend runs exposed a long multi-assertion test timeout under concurrent builds and an overly broad title selector; the test was split without removing assertions and the selector corrected before the fully passing final run. One focused browser rerun timed out waiting for sign-in, before reaching the feature; its diagnostic rerun passed without product changes. Earlier failed logs are retained privately.

## Local delivery and preservation

The tested API, worker and frontend assets are **deployed locally**. The original web and Ollama processes remain running. The API and worker use the existing private bank, authorization, knowledge and model settings; no branch-master expansion was activated. The worker runs from a frozen copy of the tested current Python package, recorded in the local process receipt.

Preservation checks before deployment, on private maintenance port 18089, and after reopening 8089 retain **8 readable cases, 5 evidence versions and 8 investigations**, including original case/management/evidence/answer values and complete histories. All seven protected configuration files and four original export/answer files have unchanged hashes. Closed-database fingerprints verify all original authoritative tables, including retained deletion markers; only derived indexes and new queue sidecars are excluded. The first deployment attempt conservatively stopped at a count mismatch because the guard included deletion markers as readable cases, and restored the old API before changing the worker or stores. The corrected guard excludes those markers only from the readable-case count; it still hashes and preserves their original tables.

Read-only post-deployment checks confirm bounded history pages/cursors, BANK_API configuration, the unchanged five tenant-visible scope entries, **90/90 current knowledge embeddings**, and byte-identical served production assets. Explicit local readiness checks on all five saved evidence versions retain every row (4, 5, 5, 4 and 4 rows respectively), report current knowledge and create no jobs. These checks perform no bank, embedding or generation calls. The cancelled-job screenshot was inspected for readable status, timing and source coverage.

Operational backup contains the prior API/web files, closed H2/SQLite stores and current worker source. The old process receipt did not identify the previously imported Python source, so full rollback to that prior in-memory worker code is not claimed. Publication is not part of this change; no Git commit, push or ZIP creation was performed.

Private execution logs, builds and preservation receipts are retained under the ignored `runtime/implementation-wave2-2026-09-18/` directory. They must not be added to a source release.

## Practical limits

- This is a bounded local model queue, not a multi-replica production scheduler. It admits at most 32 active questions globally and four per actor. A running or uncertain submitted job retains its model lane.
- Cancellation is cooperative. An already executing Ollama request may finish; a cancelled question does not receive that late answer. A worker crash during inference fails that receipt without silently generating it again. API restart recovery retains the same frozen request identity.
- Local readiness checks saved evidence and knowledge metadata. It does not prove model availability or full prompt capacity; the worker's exact preflight handles the latter. It is not a factual-answer evaluation.
- Omitted rows may contain relevant or conflicting information. Coverage is shown explicitly; selection never establishes a complete lifecycle or payment outcome. The small-input path still includes every original row when it fits.
- These changes do not fine-tune model weights, establish higher factual accuracy or claim a measured speedup. No new live inference benchmark was run.
- Management notes, current requests and reviewer conclusions remain complete state collections; this change pages the historical streams and relevant selectors. Deployment-scale load testing remains separate.
- Release scanning cannot recognize every proprietary value or read text in screenshots through OCR. Human source review is still required, and uncommitted/untracked feature work intentionally prevents a release archive.
