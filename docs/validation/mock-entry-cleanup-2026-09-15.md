# Private MOCK entry cleanup, 15 September 2026

The user requested removal of MOCK entries after switching to bank API and Excel workflows. This is local data maintenance; no application rebuild, bank call or model run is required.

Before maintenance, the website API listed eleven saved payment cases: four MOCK, six EXCEL and one BANK_API. No investigation was queued or running. The verified local services were stopped, and the closed database was copied and hash-checked before opening it for maintenance.

The offline helper selected cases and discovery records by their actual `sourceKind`, scoped to Northstar. An old `LOADED` cache was included only when every item was a selected MOCK candidate, its transport was MOCK and it had no upstream bank receipt. EXCEL batches were preserved even if their historical configuration `mode` was MOCK.

## Committed removal

| Record | Removed |
| --- | ---: |
| MOCK payment cases | 4 |
| Discovery candidates | 18 |
| Discovery batches | 49 |
| Case creation command receipts | 8 |
| Evidence versions / evidence command receipts | 1 / 1 |
| Saved investigations | 2 |
| Saved reports | 3 |
| Management state / commands / events | 1 / 1 / 1 |

Every selected row was archived before the transaction deleted it. Full-table comparisons verified that every unselected row and all table schemas were unchanged before commit. Seven payment cases, four evidence versions, seven investigations and two reports remain. Other tenants and the separately preserved legacy/export baseline were not selected.

The helper first rejected a JSON integer-node comparison mismatch and then an older `LOADED` batch reference; both previews performed no writes. The corrected preview was reviewed before applying the transaction. No preservation check was removed.

Private backup, archived rows, plan, helper, API baseline and commit receipt are in ignored `runtime/mock-entry-cleanup-2026-09-15/`. The closed database backup is `poi-gpu-before.mv.db`; `archive.json` contains the individual removed rows. Restoration must account for subsequent work and identity/idempotency conflicts; replacing the full database would roll back newer changes.

The current discovery configuration remains BANK_API. Private payment cases have no startup seed; historical test fixtures and the separately preserved demonstration baseline remain available for explicit offline use.

## Verification after restart

The native stack restarted successfully. Authenticated reads through the website returned exactly the seven retained cases, four evidence versions and seven saved investigations. All retained case resources, workbenches, evidence bodies, answers and management responses equal the pre-cleanup baseline; the four removed case URLs return 404. The Evidence library reports the same seven cases and four versions, with no removed case IDs.

The other tenant remains empty. The original export snapshot and its three saved answers, protected source/configuration files, API artifact, knowledge/index, model baseline and local HTTPS resolver hashes remain unchanged. Discovery configuration reports BANK_API. `/cases`, `/evidences`, API health and worker health all return HTTP 200. These are authenticated API and HTTP checks, not a new visual browser test. No bank request or model generation was triggered during maintenance or verification.
