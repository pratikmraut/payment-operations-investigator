# Case-based Evidence Q&A — 14 September 2026

## Delivered behavior

The Evidence workspace now has **Case evidence**, **Evidence Q&A** and **Export demo** tabs. `/evidences/questions` selects an authorized saved payment case and reuses its existing investigation workbench. The original standalone export component and answers remain at `/evidences/exports`.

Search covers reference, UTR, case ID and investigation reason without automatically selecting another payment. Payment details are expandable. The workbench initially displays saved investigations, with Timeline, Evidence and Audit trail still available. Explicit questions use the existing case investigation API and storage; no second answer store or model pipeline was added.

Library links and `/evidences/questions/{caseId}/{evidenceId}` carry the exact immutable version, including older versions. Unknown case/version links never fall back to another source. Loaded context must match the selected ID and hash before submission. The initial version and draft remain selected during refresh when newer evidence arrives; controlled dropdown changes update the route, so Back restores the preceding selection. Case changes clear the old workbench and abort browser reads, while already saved server jobs continue.

## Automated verification

- **249 frontend tests across 17 files passed** on the final implementation.
- Main and native TypeScript checks and native production build passed.
- Deployed assets: `index-D6K0rLTe.js` and `index-CU-L4kkP.css`.
- Checks include exact-version POST identity, unavailable version rejection, retained draft/version during refresh, controlled Back behavior, writer/viewer access, empty cases, search, encoded paths, login restoration and inspector links.
- Tests use original synthetic fixtures. Submission tests check the existing endpoint and returned saved-answer rendering with synthetic responses; they are not live-model accuracy tests.

An existing timeline test initially asserted a row before its asynchronous context read completed. It now waits for that source row. The final full suite passed. Browser testing found mobile overflow from the long case breadcrumb; the existing truncation rule now covers this route too.

## Deployed browser and preservation checks

An isolated headless Edge session verified explicit case selection, exact latest/earlier versions, Back/Forward, deep-link reload, editable suggestions, preserved draft text, existing answers and citations, source inspection, library inspector links, no-evidence guidance, unavailable case/version rejection and viewer reads. Desktop and 390-pixel layouts passed without horizontal overflow. The original Export demo remained available. There were no page errors or non-login writes.

Before/after API captures preserved **8 cases, 3 evidence versions, 3 case investigations, 1 standalone export snapshot and 3 export answers**. Model/export components, Java model clients, launcher and private configuration hashes matched. The shared frontend workbench was intentionally extended and is excluded from the unchanged-model hash assertion. The backend was not modified or restarted.

Private receipts and screenshots are in ignored `runtime/evidence-qa-validation/`. Validation made **zero new model calls and zero bank calls**. Existing real saved answers were inspected; new submission binding was exercised with synthetic test responses. Earlier live-model functionality and factual-accuracy limitations still apply, with no new speed or accuracy claim.

See [the operator flow](../EVIDENCE_QA.md) and [existing investigation contract](../CASE_INVESTIGATION.md).
