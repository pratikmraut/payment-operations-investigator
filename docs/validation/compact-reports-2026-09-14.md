# Compact payment reports and visible export actions — 14 September 2026

## Requested behavior

The user could not find report export and found the existing 26-page PDF too long. Source inspection confirmed that export was only exposed in the payment-case header; the queue and shared Q&A workbench had no export entry. No claim is made about the user's browser cache or active session.

**Export PDF** now appears at all three entry points. `/payment-cases/{caseId}?report=1` opens the case-specific report choices; selection and preview remain explicit. The default **Case summary** chooses the latest completed question for the selected evidence version. It permits up to two questions and renders at most three pages. **Detailed report** retains complete cited source documents, up to twenty questions and optional raw evidence.

Summary PDF contents are payment identity and saved amount, bank/branch, owner/priority/status, reason, selected evidence metadata and key cautions, original answer excerpts, unknowns/next checks, short citation references, matching reviewer conclusion, and concise current follow-up information when present. Excerpts and omitted-item counts are labeled. No answer prose is generated or rewritten during export.

## Automated and live verification

- Full Java package: **298 tests**, no failures/errors/skips; **12** cover PDF rendering.
- Full frontend: **303 tests**; TypeScript/native production build passed.
- Six live read-only Edge checks covered all three export entries, summary defaults and selection limits, detailed/raw choices, sign-in restoration, reload, Back and 390px layouts. No page errors or model/bank calls occurred.
- After backend deployment, the live browser successfully generated a default one-question SUMMARY preview and downloaded its PDF using the exact returned report ID/fingerprint. Desktop and 390px mobile layouts passed. Only the preview/download artifact endpoints were submitted; no management, evidence or investigation writes occurred.
- Same-case HTTP comparison selected the exact same two saved questions as the earlier detailed report. It verified unchanged complete saved answers and citations, exact reviewer binding, frozen same-key retries, changed-mode conflicts, summary/raw rejection and successful legacy report download.
- The actual SUMMARY PDF has **2 pages / 19,026 bytes**; the legacy DETAILED PDF has **26 pages / 61,147 bytes**. Both saved answer texts fit fully in the summary. This is a measured example, not a promise that all case summaries have two pages.
- Both actual summary pages passed visual inspection. All **52** text/document checks passed, including both complete saved answers, payment values, prioritized source cautions, source labels, next-check text across a page break, reviewer status and the exact report fingerprint. Body extraction excludes page headers/footers; those are verified separately. No PDF or answer change was needed for the extraction corrections.
- Before and after deployment/export, all **9 cases, 3 evidence versions, 5 case investigations, 1 original export snapshot and 3 export answers** matched exactly, as did every existing management representation and protected worker/model/runtime file. Export validation adds only frozen report artifacts; no management, evidence, case or investigation was changed.

The tested Java artifact SHA-256 is `3986101903a457d0dccb1f8b626b2b515af37bbfc5107268a27e34f552b4661a`. The same hash is deployed locally. Current web assets are `index-DzVwiUaN.js` and `index-CMNliQ1j.css`.

## Compatibility and limits

The UI explicitly requests `reportMode: SUMMARY`. The field is optional for older clients: absent mode remains DETAILED and preserves the old request hash, idempotency and frozen-report semantics. SUMMARY and DETAILED both freeze full selected answer/citation objects; their PDF presentation differs. Mode does not relax reviewer binding to the evidence fingerprint and completed-question set.

The summary's length limits are explicit; unusually large fields that cannot fit return an actionable error instead of exceeding the cap. Short citations do not contain the source body; use Detailed report or the saved investigation to inspect it. Local rendering and source-fidelity checks do not establish model factual accuracy or production readiness.

## Private validation artifacts

Private records and PDFs remain in ignored runtime directories, outside public fixtures and this document:

- `runtime/compact-report-validation/before.json`, `preservation.json`, `deployment.json` and a closed-database rollback backup.
- `runtime/compact-report-validation/java-package.log` and `pdf-renderer/summary-visual-validation.json`.
- `runtime/compact-report-validation/summary-preview.json`, `payment-case-summary.pdf`, `legacy-detailed-report.pdf` and `http-summary-receipt.json`.
- `runtime/compact-report-validation/summary-text-validation.json`, `summary-visual-validation.json` and `summary-page-1.png` / `summary-page-2.png`.
- `runtime/compact-report-validation/ui-summary-download.json`, `ui-summary-report.pdf`, `ui-summary-preview.json` and desktop/mobile screenshots.
- `runtime/case-rag-validation/export-summary-frontend-tests.log` and `export-summary-browser.json`.

The original 26-page PDF and its earlier validation receipts were retained unchanged.
