# Payment case lifecycle validation — 16 September 2026

## Scope

Adds separate ACTIVE / ARCHIVED / DELETED lifecycle state for private saved payment cases, case-management Archive/Restore controls, queue visibility filtering and dedicated administrator removal. Archived contents remain readable/exportable and require restoration before evidence, questions or management writes. Permanent removal purges dependent contents, preserves a minimal scoped tombstone/number reservation/audit, rejects deleted aliases and stale create receipts, and permits a fresh numbered case for the payment.

Lifecycle commands use expected versions, idempotency keys and the same case row lock as write commits. New workbench and report activity includes lifecycle events; frozen prior answers, evidence and reports are not rewritten.

## Validation record

The final backend package run passed **392 tests**, with zero failures, errors or skips. This includes six lifecycle service tests, five controlled race/report-preservation tests and two real-session HTTP/security tests. Destructive checks use isolated H2 fixtures and mocked transports. They must not be interpreted as live bank requests, real model inference or a production identity/retention rollout.

The full regression run found and corrected an existing-receipt compatibility regression: creation retries now recheck that the case remains readable but retain the original receipt contents, adding only the stable number and current lifecycle projection. The library tests now exercise its explicit ALL-nondeleted scope. Exact serialized JSON assertions check stored report and deletion-retry preservation across Jackson numeric-node deserialization.

Backend artifact SHA-256: `cfaf1650385ab141901d6ae2e6398adee00c4c48085feb1e5904dc14c9830f95`. Private log: `runtime/case-lifecycle-2026-09-16/maven-final.log`.

The final frontend suite passed **388 tests in 24 files**, including lifecycle actions, uncertain retries, filters, archived intake/Q&A guards and existing flows. Main and native TypeScript checks passed. The final suite used two workers to avoid machine contention. Two existing asynchronous assertions now wait for the specific intended evidence/status content; the original export implementation is unchanged. Private log: `runtime/case-lifecycle-2026-09-16/frontend-tests.log`.

Native assets: `index-C31v7oBa.js` (SHA-256 `426ca730317f24d71e9d9eab042171378e1002d27f5e85634e5173a588be0cfb`) and `index-B2P8U0jh.css` (SHA-256 `a6974f74b84748ae4fd6b4d5cddd5fdff563dbc02c4e037b2ca2da5eadc2176c`).

The read-only predeployment baseline is privately recorded under `runtime/case-lifecycle-2026-09-16/before.json`: **7 cases, 4 evidence versions, 7 investigations, 1 original export snapshot and 3 original export answers**. The implementation must preserve these records, their case numbers, management and the current native GPU/model/knowledge/bank configuration.

## Local deployment

Deployed at `2026-09-15T19:28:45Z` (16 September in Asia/Kolkata) to the native site at `http://127.0.0.1:5178/`. The process-verified lifecycle scripts stopped/restarted only this project. The closed database and prior API/frontend were backed up before replacing the application. The API artifact and served HTML/JS/CSS match the tested files.

The final read-only checks passed for all seven canonical and short case URLs, lifecycle views, analyst/viewer/administrator available-action flags, cross-tenant access rejection, case-number library lookup, Active/Archived/All lists and served bundle fingerprints. Every live case remains ACTIVE at lifecycle version zero with no lifecycle events. Saved case/evidence/job/management resources compare exactly after excluding only the added lifecycle presentation fields. Original exports/answers and protected model, index, knowledge, TLS resolver and bank configuration hashes are unchanged. No bank/model requests or live case lifecycle writes were used in these checks.

Private receipts: `runtime/case-lifecycle-2026-09-16/deployment.json` and `after.json`; backup database SHA-256 `4091d62b0fb6d7c34804289c242289b7c831c811479b072323c465c4b19e73e8`. UI behavior is covered by component tests and served-bundle checks. A desktop browser input attempt reported unavailable coordinate geometry, so no completed postdeployment interactive browser walkthrough is claimed.

See [CASE_LIFECYCLE](../CASE_LIFECYCLE.md) for the operator and API contract, including application-level deletion and backup boundaries.
