# Payment case management and PDF reports — 14 September 2026

The saved Payment case workflow now supports owner/priority changes, append-only notes, evidence requests, independent reviewer conclusions and an exportable PDF investigation report. The visible demo navigation, original case/evidence data and GPU/local-model configuration remain unchanged by this milestone.

## Implemented behavior

- Management sits below Selected payment and before evidence acquisition. Owner and priority appear in the queue and are searchable. Commands use scoped Java authorization, CSRF, expected versions and idempotency keys; drafts survive conflicts and uncertain network responses.
- Evidence requests have OPEN/FULFILLED/CANCELLED history. Fulfillment explicitly binds an authorized saved evidence version and fingerprint. No request automatically calls a bank service.
- Reviewer conclusions bind to an exact evidence version/hash and completed-question set. The reviewer must be different from the case and selected-question creators. Recording a conclusion neither closes the case nor approves a payment.
- Export report is a case-header action. The user selects evidence and saved investigations, previews a frozen server record, then downloads that exact record as an A4 PDF. Complete cited sources are retained; raw evidence is an optional appendix. Old-version and unfinished questions retain their own source/version/status labels.

Operator and API details: [case management](../CASE_MANAGEMENT.md), [PDF reports](../PAYMENT_CASE_REPORTS.md), [API contract](../API_CONTRACT.md).

## Executed verification

| Area | Executed result |
| --- | --- |
| Java | Final full package: **287 tests**, zero failures/errors/skips |
| Frontend | Full suite **293 tests** passed; **24 affected tests** passed after adding an explicit unfinished-job selection test and correcting a duplicate role label; TypeScript and native production build passed |
| PDF renderer | **7 tests**: exact identifiers/amounts/Unicode, source wrapping and bounds, raw appendix choices, pending/recorded review, passive PDF structure, all-page render and original-character mappings for `fi`/`ff` and fingerprints |
| Direct HTTP integration | **27 checks** passed on an isolated copy: assignment, priority, note, retry/conflict/version rules, viewer/tenant/CSRF protections, strict JSON, request lifecycle and frozen report download after later edits |
| Browser workflow | Isolated copy passed assignment, notes, fulfillment with saved evidence, reload, PDF preview/download, independent review, matching review scope and viewer restrictions |
| Main browser | Five read-only checks passed for queue, management, export defaults, deep-link reload and viewer access; zero business-data writes, model calls or bank calls |
| Mobile | Overview, requests, report and viewer layouts fit 390px without horizontal overflow; final main-page controls were visually inspected |
| Restart durability | All eight isolated management representations, including notes/requests/reviewer history, remained exactly equal after API restart; the prior frozen report replay and PDF download also passed |
| Preservation | All **8 cases, 3 evidence versions, 5 case investigations, 1 original export snapshot and 3 export answers** retained their original data. Protected worker/model/runtime hashes remained identical |
| Actual exported PDF | **141 literal-text comparisons** passed: exact fingerprint, original question/claim order, all seven complete citation occurrences and their source tails/provenance, and raw evidence values. This checks preservation, not factual correctness of the saved model answer |
| Final visual PDF review | All **26 actual report pages** and **19 synthetic fixture pages** inspected. A4/page numbering, margins, source continuations and field alignment passed; no observed clipping, overlap or missing glyphs. No forms, annotations, JavaScript or open actions in the actual export |

The live export uses existing authorized saved questions. Its preview was compared against their original answers and citations, and the same report fingerprint is used for PDF download. No new investigation, model call or bank call was made. Report preview adds only a report artifact; management testing uses a private database copy, leaving the original cases unassigned at their existing priorities unless the user changes them later.

The PDF audit initially detected default font ligatures changing copied `fi`/`ff` characters, including a hexadecimal fingerprint. Glyph substitution is now disabled before the embedded font is loaded; a regression tests the original Unicode glyph mapping directly. Long-source checking also reconstructs text across page headers instead of mistaking pagination for missing content. These corrections preserve source text and do not rewrite model answers.

## Deployment and private receipts

The tested Java artifact is `c692209c2f4ff3b1dc974a575c7548429a17f7913445a8ba352d38128e51535f`. Frontend script is `index-88hWDFYa.js` (`3fbd813c369ee177a7a63038a485bf5c4ed23e8b2c78ac0ba744326f3abdafbf`); CSS is `index-CoIRp9mD.css` (`e7c9b7fd170f1cec12e5e9627e7df19771de30b506bfbef5f3ce6f300b0452f6`).

Database and former JAR backups were taken with the owned native stack stopped. Validation uses ignored local directories because the existing case content is private:

- `runtime/case-management-validation/java-package-release.log`
- `runtime/case-management-validation/http-isolated-smoke.json`
- `runtime/case-management-validation/durability-receipt.json`
- `runtime/case-management-validation/preservation-latest.json`
- `runtime/case-management-validation/deployment-final.json`
- `runtime/case-management-validation/final-runtime.json`
- `runtime/case-management-validation/live-report-receipt.json`
- `runtime/case-management-validation/live-pdf-text-validation.json`
- `runtime/case-management-validation/live-pdf-visual-validation.json`
- `runtime/case-management-validation/pdf-renderer/visual-validation.json`
- `runtime/case-rag-validation/management-browser-qa.json`
- `runtime/case-rag-validation/management-main-readonly.json`

The isolated API/proxy were stopped after verification; their database and receipts are retained. This milestone does not change the underlying model, replace the preserved CPU baseline, connect the actual bank APIs or execute payments.

## Practical limits

The application remains local with configured demonstration identities and bank/branch entitlements. Production SSO/directory administration, durable distributed jobs, case closure policy, external audit retention and report retention/download history are future deployment work. Current reports are bounded to 20 questions, 8 MiB frozen content and 200 pages; two PDFs may render concurrently per API process. The fingerprint checks content integrity and is not a digital signature or a factual verification of model conclusions.
