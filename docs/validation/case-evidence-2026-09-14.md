# Case evidence intake validation

14 September 2026. This milestone adds case-scoped evidence collection through the bank inquiry adapter, four Excel workbooks, and manual entry / JSON upload. It does not connect these new versions to the model or execute payments. Workflow and integration contract: [CASE_EVIDENCE.md](../CASE_EVIDENCE.md).

## Automated checks

- Full Java offline Maven `package`: **175 passed**, zero failures/errors/skips. Includes 11 evidence transport, 4 HTTP/controller, 13 service and 13 workbook tests, plus the existing application suites.
- Full React suite: **135 passed** across 10 test files, including 18 new evidence component tests. Existing jsdom `window.scrollTo` notices are nonfatal.
- After scoping the in-page save indicator to its case, reran discovery and evidence suites: **38 passed**.
- Main and native TypeScript checks passed. The final native production build passed.

Coverage includes exact long reference/decimal text preservation, strict four-group/column validation, payment scope mismatches, status tuple linkage, header-only results, malformed/unsafe XLSX archives, payload/file bounds, JSON autofill and provenance, explicit saves, immutable history, concurrent version numbering, idempotent retries/conflicts, authentication, CSRF, writer roles and tenant isolation. Simulated bank transport tests cover successful responses, partial/invalid metadata, errors, size bounds and timeouts without a fallback.

## Local deployment

The native services were stopped through the project's verified process lifecycle helper. The closed private H2 database, previous JAR and private application settings were backed up under ignored `runtime/case-evidence-validation/`. No cases were removed or created for this deployment. The new schema creates separate evidence/version-command tables; prior saved cases retain their identities and reasons.

Deployed Java SHA-256: `e919569105babcf0312aacfee5bc2506c07996f0fd8c25f0f9cb584aebd104bc` (matches the tested artifact). Final web assets: `index-CycllMAF.js` and `index-Dsik76Wj.css`.

The user-supplied `NEFTEvidenceInquiryService` URL is set in private native application properties, with a 15-second timeout and normal TLS checks. Discovery remains on its existing MOCK configuration. No bank or model request was used for these checks; the deployed bank wrapper and authentication remain unverified.

## Browser checks

Verified login at port 5178, the existing seven-case queue, and the matching saved payment detail. All three evidence tabs render. Inquiry API shows an enabled fetch/save action with the saved scope. Four Excel files exposes all four upload controls and standard header download links. Manual + JSON exposes template download, JSON autofill, group/row selectors and the native fields with case identity prefilled. Unsaved form checks do not create evidence.

## Original export smoke check

The user's existing four workbooks were uploaded unchanged to their matching saved case using the authenticated local Excel endpoint. **22 local HTTP checks passed**: 16 successful responses, two expected cross-tenant 404s, two expected authorization/CSRF 403s and two invalid-payload 422s. Saved rows are PAYMENT **1**, HOST **1**, HISTORY **2**, STATUS **0**. Every native cell was compared against the original workbook text, including the long reference and exact decimal amount. A retry reused the same version; a separate detail read returned the same persisted payload. All seven saved case identities and reasons and all four source files were preserved.

Reloaded the browser case and confirmed version 1, source Excel, all four coverage counts and original PAYMENT values. Switched to HISTORY row 2 and verified it displays separately; STATUS shows an explicit zero-row message. The page was left on Four Excel files with the saved PAYMENT group selected.

Private receipts and source comparisons remain under ignored `runtime/case-evidence-validation/runs/20260913T215307Z-efb2f31d/`. The replayable private script uses only loopback application routes and never invokes a bank or model endpoint.

## Practical limits

- Uploads/manual entry record unverified source completion, including zero-row groups. API COMPLETE coverage means the supplied completion metadata passed validation, not independent verification of the source system.
- The adapter currently expects the documented JSON contract. A bank-specific wrapper, authentication scheme or mTLS needs an actual deployment example before compatibility can be claimed.
- Sequential retries reuse the saved response. Two simultaneous API calls with the same retry key can both reach the read-only upstream before persistence locking; they still save one version.
- The existing staged Evidence Q&A and GPU/CPU model implementations are unchanged. Saving a case evidence version alone does not produce an AI answer.
