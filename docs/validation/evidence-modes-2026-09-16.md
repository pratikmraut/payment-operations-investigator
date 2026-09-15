# Evidence tabs and mode recovery — 16 September 2026

The user reported that Administrator evidence intake selected Manual + JSON instead of Inquiry API, then requested a broader review of bugs across input methods and roles. This increment fixes confirmed frontend state and recovery defects; it does not claim that every possible project defect has been eliminated.

## Changes

- Inquiry API is the initial evidence tab for every role. Merely opening it does not call the bank. Administrator and Viewer evidence access remains read-only; Analyst and Reviewer permissions remain unchanged.
- A failed saved-version read and a failed version-list refresh have separate GET-only retry controls. Both preserve the input draft and selected version. Late list responses cannot replace results from a newer save or another case.
- A transition to archived/read-only cancels client file reading or waiting, releases busy controls and suppresses stale updates. Already-sent saves are not rolled back by browser cancellation. The backend retains its existing lifecycle check at commit; an unchanged retry retains its command key.
- Switching discovery methods clears the previous Excel file so a blank remounted chooser cannot submit an invisible old file. Typed bank and branch values remain intact. Read-only copy correctly covers both Administrator and Viewer.
- Lifecycle refresh closes the permanent-delete form if another session restored the case. Management tab switches and management refreshes preserve lifecycle drafts, pending commands and uncertain retries. Ordinary reopening reads fresh state; pending or uncertain actions retain their original request and retry key.

The component changes are in CaseEvidence, PaymentDiscovery, CaseManagement and CaseLifecycle. Backend implementation, permissions, inquiry contracts, source evidence and model configuration were not changed.

## Verification

- Full frontend suite: **399 tests passed in 24 files** with two concurrent workers.
- Main and native TypeScript checks passed.
- Native production frontend build passed.
- Targeted backend suites: **52 tests passed** in CaseEvidenceServiceTest, CaseLifecycleControllerTest, CaseLifecycleRaceTest and PaymentDiscoveryServiceTest. These use isolated fixtures, including lifecycle/write races and authorization checks. No full backend rerun was needed for this frontend-only increment.
- New regressions cover failed reads, draft preservation, late responses, read-only transitions, hidden upload selection, restoration during delete mode, and preserving lifecycle retry state across tab switches and management refresh.

No new live bank inquiry, model inference or case mutation was performed. Interactive browser visual verification was not performed for this increment; rendered-state component tests and served-artifact verification provide the UI checks.

## Local deployment and preservation

The native site on port 5178 serves the tested frontend. Old assets and a complete frontend backup were retained, and the entry HTML was replaced after the new assets were copied. API, worker and Ollama processes were not restarted.

Exact read-only comparisons before and after deployment preserved all **seven cases, four evidence versions, seven investigations, management/lifecycle records, one original export snapshot and three original export answers**. Protected model, knowledge, embedding index, bank settings and API artifact hashes remained unchanged.

| Served artifact | SHA-256 |
| --- | --- |
| `index-BEJoSYew.js` | `75cafa07201b71a9d707a9cbe6e425cbc799847d3f5c34970de9092ed4acec9a` |
| `index-B2P8U0jh.css` | `a6974f74b84748ae4fd6b4d5cddd5fdff563dbc02c4e037b2ca2da5eadc2176c` |

The backend artifact remains `cfaf1650385ab141901d6ae2e6398adee00c4c48085feb1e5904dc14c9830f95`. Private logs, before/after comparisons, deployment receipt, staged build and frontend backup are under ignored `runtime/evidence-modes-2026-09-16/`.

The separate exact-reference PO01 bank failure still requires rebuilding and deploying the previously authorized FLEXCUBE source fix, then retesting UAT. These UI fixes do not establish that bank-side recovery.
