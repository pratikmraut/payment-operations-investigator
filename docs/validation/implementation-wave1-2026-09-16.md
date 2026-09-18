# Implementation wave 1 — 16 September 2026

The user authorized implementation in the proposed order: CI/current-payment acceptance, operational workflow, draft protection, then assigned follow-ups and report history (audit priorities 1, 2, 3 and 9).

## Delivered behavior

| Location | Capability |
| --- | --- |
| Case management → Overview | Reasoned states OPEN, INVESTIGATING, AWAITING_EVIDENCE, AWAITING_REVIEW and RESOLVED; independent reviewer resolution and explicit reopening. |
| Case management → Reviewer conclusion | Direct evidence-only review or review of selected completed questions, bound to the exact evidence version. |
| Case evidence, investigation question and management forms | Navigation/Back/reload/sign-out draft warnings, explicit draft download and restore; successful saves clear the draft baseline. |
| Saved payment cases | Work filters alongside existing search, lifecycle selection and ten-row pages. |
| Case management → Evidence requests | Scoped request assignment, reassignment and preserved request history. |
| Below Saved payment cases → Evidence follow-ups | Read-only assigned/all/overdue requests, in-app overdue reminders and bounded pages. |
| Export PDF → Saved reports | Paginated frozen report metadata and download of a prior report selection without creating another preview. |
| GitHub workflow | Supported Java 17 version range and a current payment-case Chromium acceptance job. |

Contracts: [workflow](../CASE_WORKFLOW.md), [draft protection](../DRAFT_PROTECTION.md), [follow-ups](../CASE_WORK_QUEUE.md), [report history](../REPORT_HISTORY.md), [browser acceptance](../PAYMENT_BROWSER_TESTS.md).

## Executed verification

- Full Java suite: 409 passing tests, no failures/errors/skips. After the final management lifecycle projection and queue freshness regression, all 27 affected tests passed (management controller 4, management service 18, work queue 5).
- Full frontend suite: 421 passing tests across 26 files. After adding bounded queue loading and invalid-response recovery, all 16 focused follow-up/saved-case tests passed. This adds two recovery tests; focused counts overlap the earlier full run.
- TypeScript and both application/native production builds passed. The native bundle retains a nonblocking approximately 506 kB JavaScript chunk warning; code splitting remains an optimization opportunity.
- Three actual Chromium scenarios passed against the final Java artifact and current UI: discovery/evidence/investigation/independent review/PDF/history/lifecycle, canceled navigation/Back/draft download and restore, and assigned overdue request filtering. Total final browser run: approximately 1.4 minutes. Dedicated test listeners shut down cleanly.
- Git diff whitespace checks passed. CI workflow changes are local; no pushed GitHub run is represented as passing.

Tests use public original synthetic records and a fresh H2 memory database. The Java API and frontend are real, while the model provider boundary returns an explicitly labeled contract-test answer. These runs verify orchestration, provenance persistence and UI behavior, not model accuracy, real bank connectivity or production authentication.

The backend's Windows test run needed scoped execution because sandbox path resolution blocked Java compilation. A project-local JDK Unix-domain-socket directory avoided short-name TEMP aliases. No production TLS bypass or compiler source workaround was added.

## Local rollout and preservation

Before rollout, saved case/detail/management/evidence/answer values and protected configuration hashes were captured privately, with no active investigations. The old API jar, receipt, web assets and closed H2 database were backed up in the ignored project runtime directory. Only the verified app API process was restarted. Worker/model processes were not restarted; new web assets were published with the entry point last, retaining old assets for open pages.

Final API artifact SHA-256: `96a6acd6925e72033388e762afe93aebd168564169fac8158e0420d537e4d33a`.

Post-rollout read-only checks verified:

- Exact preservation of six case identities, three evidence versions, six investigations and all preexisting returned case/management/evidence/answer fields. Additive response metadata is allowed.
- Seven protected private configuration files retain their hashes. Effective discovery remains BANK_API.
- Served `/cases` HTML and its two assets match the tested native build bytes.
- Management workflow metadata, report-history reads for all six cases, and the follow-up endpoint work through the frontend proxy. Two preexisting report metadata entries remain readable; zero open requests existed at verification time.
- API and worker health respond successfully, and the web/worker process receipts are unchanged.

Private verification artifacts are under `runtime/wave1-build/` and are excluded from Git. No bank/model requests, actual-case workflow changes, commits or pushes were made during this wave.

## Remaining scope

This wave does not implement organization SSO, formal database migrations, knowledge approval workflows, restart-durable investigation workers, full database-backed search/pagination or other deferred audit work. Follow-ups are visible in the app; no scheduled email or external notification delivery is enabled. Resolution records completed investigation work and a reviewer conclusion, not an asserted payment outcome.
