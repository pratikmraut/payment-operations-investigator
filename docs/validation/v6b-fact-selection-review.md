# V6b controlled selection review

All six original synthetic development requests returned accepted results with eleven completed provider calls: five planner-plus-selector paths and one planner-only insufficient-evidence path. Five selected findings contain an exception-distinguishing observation, compared with two in the preceding V6 run. This is a small diagnostic comparison after inspecting those cases, not a general accuracy estimate.

| Case | V6b selected facts | Review and remaining limit |
| --- | --- | --- |
| CASE-1001 | FACT-TIMEOUT-OBSERVATION, FACT-PROVIDER-STATUS | The selection now includes the distinguishing timeout plus status corroboration. It omits the available single capture and zero-difference settlement, so it is not the complete evidence needed for resolution; those checks remain in the rule summary/tool trace. |
| CASE-1011 | FACT-REPEATED-DELIVERY | The single selected fact now directly explains the duplicate-delivery exception. It does not add an unselected sentence or claim to demonstrate the ledger/capture prerequisite. |
| CASE-1021 | FACT-WEBHOOK-TIMING, FACT-SUCCESS-EVENT | The selected pair now explains the ordering inversion and adds success-event corroboration. It does not narrate IGNORED_STALE or ledger values; those remain verified in the assessment/tool trace. |
| CASE-1031 | FACT-REFUND-OBSERVATIONS, FACT-SETTLEMENT | Specific and useful refund observation first, followed by settlement corroboration. Same two facts as V6 in reversed order; no selected sentence was rewritten. |
| CASE-1041 | Planner only | Correct planner-only insufficient-evidence path; excluded from the five-selection denominator. |
| CASE-1051 | FACT-TERMINAL-EVENT, FACT-PROVIDER-STATUS | Relevant terminal-failure observation followed by provider corroboration. Empty capture-ledger proof remains in the rule summary/tool trace, not in the selected sentences. |

The requests match V6 after removing only investigationId. Operational tool outputs, rule assessments, full catalogs and structured selector user inputs are equal for each paired case; only the selector SystemMessage changed. Source/runtime receipts identify the new image separately from the preserved V6 receipt. Equality establishes comparison control, not semantic validity.

Codex separately compared each selected sentence with the actual operational records and applicable supplied policy. Timeout and status observation times are distinct; the duplicate record count/statuses match both deliveries; ordering explicitly gives occurrence and receipt for both events; refund facts correctly distinguish confirmation, zero supplied postings and the INR 6,113.19 difference; terminal failure occurrence is distinct from provider asOf. Exact catalog text, full available link closure and rule-owned field composition matched in all five selected findings. No material factual error was identified in those selected service sentences. This is an AI review, not independent human validation.

The model authored no prose and chose nine fact IDs in total. The service rendered those facts without adding unselected sentences or repairing output. Selection remains a highlight: ledger reconciliation and ignored-stale handling are not repeated in every finding. Rules/tool traces still supply those prerequisites; a selected snippet should not be treated as the entire basis for a case decision.

CASE-1041 had one planner call, no selection and no finding. Its three requests seek timestamped provider status, ledger cutoff/coverage and delivery history. No selector policy credit applies to a skipped stage. No Java reviewer action or case transition was performed in this direct-worker batch.

Configuration remained qwen3:4b-instruct, lexical retrieval, four threads, 4096 context, 180-second provider timeout and 384-token stage limits. The current image is `ae5b391eb19a902d8b20c65ea170783555b37f998502e15adb1cb008bc3dd505`, with 198 passing component tests including real PostgreSQL. Full Java workflow and live hybrid validation remain separate evidence.

Exact selections, catalogs, sentences, links, model metrics, policy context, paired request hashes and manual notes are in [v6b-development-fact-selection-review.json](v6b-development-fact-selection-review.json). The prior omissions remain unchanged in [v6-fact-selection-review.md](v6-fact-selection-review.md).
