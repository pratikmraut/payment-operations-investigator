# V5 broader 8B review

Five of six synthetic development requests were accepted; one was rejected by the named-evidence-link gate. Accepted results report nine model calls. The rejected checkpoint records two additional completed provider stages, for eleven known completed calls across the batch. This reconciles the client's failed-request usage limitation without claiming billing telemetry.

There are **four accepted model-written findings**, one accepted planner-only uncertainty result and one rejected model-written candidate. Codex AI identified a material factual problem in one of the four accepted findings; three had no material factual error identified, with traceability/snapshot limits. This is not an independent human review or held-out accuracy result. Exact candidates, outputs, tool evidence, actual policy, context equality and stage metadata are in [the JSON review](v5-8b-development-explanation-review.json).

| Case | Result | Review |
| --- | --- | --- |
| CASE-1001 | Accepted | Material timestamp conflation: “succeeded on 2026-09-11T06:05:50Z” uses provider `asOf`, a status observation time, as success occurrence. The actual confirmation occurred at 06:00:05Z. Current success and capture amount are supported. |
| CASE-1011 | Accepted | Same supported money/status finding as the selected 8B diagnostic. Prior simultaneous-delivery wording is absent. Direct provider-payout link remains omitted. |
| CASE-1021 | Rejected | Names WH-1021-1 in text but omits it from evidenceIds. Both calls completed; timestamps and monetary facts are supported. No accepted result exists and no text was repaired. |
| CASE-1031 | Accepted | Correct capture, refund identity/confirmation and absence from the supplied ledger; direct provider-success link omitted. |
| CASE-1041 | Accepted, planner-only | One actual call; rule-owned actionable uncertainty requests; no model prose or explanation-policy context to score. |
| CASE-1051 | Accepted | Rejection event and current failure plus no supplied capture support its limited observation. Absence is snapshot-scoped. |

CASE-1021's planner completed in 34.503 s and synthesis in 145.055 s. The checkpoint records two calls, 1,582 input and 309 output tokens before final validation rejected the missing link. It was not a timeout or silently repaired answer. All exact rejected text remains in the JSON receipt.

The frozen 131-test v5 image was `4ed10f3b87761dd356543fb58c2ab83cf04beda7feb56402733517e7e02dfb25`, with runtime `qwen3:8b`, lexical retrieval, four threads, 4096 context and 180-second provider timeout. For each case, tool outputs and reconstructed finding contexts exactly equal the preceding v5 4B sample. Context reconstruction is not a raw HTTP capture. Source/default model remained unchanged.

Only finding prose/links were model-written; other fields were deterministic. This direct-worker batch performed no Java review or case transition. Larger-model delivery and valid IDs did not eliminate unsupported prose, which motivates the separately documented fact-selection contract. These outputs are historical evidence, not text to rewrite or retroactively relabel under that new contract.
