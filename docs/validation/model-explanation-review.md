# Live explanation review — first development sample

Reviewer: Codex (AI), **not a human reviewer**. Reviewed on 2026-09-12 IST against the saved operational tool outputs and the exact top policy supplied to synthesis. This review covers original synthetic development fixtures; it is neither an independent real-world accuracy benchmark nor a guarantee of semantic correctness.

## Evidence and denominator

The [first six-family report](evaluation-ollama-development.json) records six sequential attempts: five accepted explanations and one provider failure. Ten model calls are reported by accepted results; failed-request usage is unknown. All calls used Qwen `qwen3:4b-instruct`, four threads, 4096 context, lexical retrieval, reasoning disabled, nonstreaming responses and the **old 120-second provider timeout**. The report's 5/6 outcome/action/reference results reflect successful delivery and deterministic constraints; they do not score prose entailment.

The reviewer also examined two earlier accepted Java results in [live-acceptance-results.json](live-acceptance-results.json). These two results are separate from the six-family sample and must not be added to its denominator. Exact generated responses are retained unchanged. The six-family checkpoint evidence is retained in [model-development-review-evidence.json](model-development-review-evidence.json), including candidate/result, actual top policy, operational tool outputs, stage timings and the failed node error. No new inference was performed for this review.

The synthesis input contained a compact authoritative assessment and operational IDs, plus **only the top retrieved policy**. Other policies returned in the result's three-citation list were not supplied to the model. The reviewer inspected complete checkpointed tool outputs; that larger review context must not be described as model input. The direct worker sample uses explicitly identified worker baseline arithmetic; Java authorization, money calculations and review actions are tested separately.

## Six-family findings

| Case | Delivery | Prose review | Main evidence or limitation |
|---|---|---|---|
| CASE-1001 | Failed; no candidate | No explanation to score | Tool selection completed; synthesis was cancelled at the old 120-second provider limit. |
| CASE-1011 | Accepted | Payment facts and recommendation supported | Two deliveries share one provider event/reference; one applied, one ignored; one capture. |
| CASE-1021 | Accepted | Payment facts supported; **workflow overclaim** | Proposal reason says the case was resolved before any review/action. |
| CASE-1031 | Accepted | Refund amount and posting exception supported | INR 6,113.19 matches confirmed refund and missing ledger difference; human review requested. |
| CASE-1041 | Accepted | Conservative uncertainty supported; weak specificity/retrieval | Unknown provider and absent ledger facts justify abstention; missing facts are generic and the supplied top policy is rejection guidance. |
| CASE-1051 | Accepted | Failure evidence and escalation recommendation supported | Matching terminal rejection/provider failure, no capture; no retry or customer contact authorized. |

Among five accepted explanations, one contains a material completed-workflow assertion, and four have no material unsupported claim identified in this review. This is an AI-assisted review of five explanations, not a calibrated quality rate. Repeated citation IDs appear in all four accepted non-insufficient findings; they are redundant links rather than additional evidence.

### CASE-1001: timeout, not output rejection

Investigation `EVAL-bb751a04-3519-4ce6-9c3c-57a46edbe43e` has no saved candidate or result. Selection completed in 41.504 seconds with 511 prompt and 108 output tokens. The failed checkpoint records `ProviderUnavailable('Ollama structured synthesis failed; no replay fallback was performed.')`. Ollama logs show selection returning 200 at 20:14:35Z and synthesis returning 500 after 2m0s at 20:16:35Z, with cancellation. This supports a provider-timeout diagnosis under the old configuration; the checkpoint does not preserve the underlying Python transport exception. No prose existed to reject or silently repair. The attempt remains a failure in the six-case denominator.

### CASE-1011: supported duplicate explanation

Generated summary: “One delivery applied, one capture recorded; duplicate webhook detected with matching provider event ID and payment reference.” `WH-1011-1` and `WH-1011-2` share the provider event and payment reference; their statuses are APPLIED and IGNORED_DUPLICATE. Ledger CAPTURE 571033 and FEE -9993 give net 561040, equal to provider payout. The supplied `RB-WEBHOOK-DEDUP:v1` requires checking these identities and one financial effect. The reason “Record duplicate notification; preserve existing payment state; no financial action required” recommends case handling and correctly limits the no-action statement to finances. It does not claim review was executed.

### CASE-1021: supported payment state, unsupported case completion

Generated summary: “Webhook receipt order differs from provider occurrence order. Final state is consistent per ledger and provider records.” Capture occurred at 06:20:05Z and arrived at 06:20:10Z; the earlier authorization occurred at 06:20:02Z but arrived at 06:20:45Z and was IGNORED_STALE. Provider success and capture/fee ledger net 2085564 agree with payout. These observations support the payment-state claim and supplied `RB-EVENT-ORDER:v1`.

The proposal reason says: **“Final state consistent; no regressions or conflicts; case resolved per policy.”** The final clause is unsupported: it is an unreviewed proposal, and this worker evaluation executed no reviewer decision or case transition. Policy describes when resolution may be proposed; it is not evidence that resolution occurred. Valid IDs and the correct proposed action did not catch this semantic workflow error.

### CASE-1031: exact refund money, correct confirmation

Generated summary: “Refund INR 6,113.19 confirmed but not in ledger; investigate posting before any action.” The finding says: “Confirmed refund INR 6,113.19 missing from ledger; discrepancy due to absent posting, per policy RB-REFUND:v1.” The money is correct: 611319 integer minor units is INR 6,113.19. `EVT-1031-5` is REFUND_CONFIRMED/SUCCEEDED, distinct from the earlier requested refund. Provider refund is 611319; ledger refund is zero. CAPTURE 2445279 plus FEE -42792 gives ledger net 2402487; provider payout 1791168 differs by exactly 611319. Thus the absent posting explains the accounting difference in this snapshot; the explanation does not establish the underlying operational cause of the posting delay.

The supplied `RB-REFUND:v1` specifically describes this difference and human reconciliation review. The proposal reason explicitly requires human review. The finding links confirmation and provider IDs, but does not expose the ledger cutoff or ledger entry IDs in its own link array; the complete immutable settlement snapshot is needed to audit the absence claim. No claim that a refund was newly issued or a ledger was repaired was found.

### CASE-1041: correct abstention, limited guidance

The response uses INSUFFICIENT confidence, no findings, and REQUEST_EVIDENCE. Its summary says the evidence does not support a reliable conclusion; missingEvidence is “Available records do not establish one of the supported exception patterns.” This is conservative: the timeline has a client timeout and an UNAVAILABLE status lookup; the provider is UNKNOWN, and no capture ledger or webhook records exist. Zero-valued placeholder fields do not establish that payment failed or never processed, and the response makes neither claim.

Specificity is limited. It does not identify the unavailable provider confirmation and ledger reconciliation as the concrete facts to obtain. The actually supplied top policy was `RB-FAILURE:v1`, which concerns confirmed terminal rejection; its main predicate does not fit this unknown-status case. The model did not cite it in a finding or fabricate rejection, so this is a retrieval/context relevance limitation rather than an unsupported failure conclusion. A relevant policy appearing elsewhere in the returned citation list is not proof that it grounded the generated answer.

### CASE-1051: supported failure and proposed escalation

The finding states: “Confirmed processor rejection via timestamped failure event and provider state; no ledger capture recorded.” `EVT-1051-2` is PROCESSOR_REJECTED/FAILED with reason code SIMULATED_PROCESSING_DECLINE; provider PRV-1051 is FAILED, and the capture ledger is empty. This matches supplied `RB-FAILURE:v1`. The reason “Escalate provider failure with evidence; no retry or customer contact is authorized per policy” is a recommendation, not a claim that escalation already occurred. It respects the supplied policy's limits.

## Earlier Java acceptance prose

CASE-1002 (`INV-4609e5c9-1d3a-4649-bdcc-a8f7b83042b5`) correctly describes transport timeout, successful provider confirmation and one capture with zero reconciliation discrepancy. Its summary ends **“Case resolved without further action.”** This was generated while AWAITING_REVIEW and is an unsupported completed-workflow assertion. Its unqualified “no further action required” proposal reason also hides the pending independent review.

CASE-1003 (`INV-f07edabc-8104-4a80-8ac3-4484bc108d2f`) has supported payment facts and zero discrepancy. Its “No further action needed” summary and “no further action required per policy RB-TIMEOUT:v1” reason omit the required reviewer decision and overstate the supplied policy. Later harness approval cannot retroactively justify wording generated before approval. Exact responses, operational facts and top policy are preserved in the separate JSON review.

## Bounded correction and remaining limits

The authorized correction adds service-built context stating that this is a proposal requiring independent review, no review has executed for this proposal and this investigation has executed no case action. It does not assert a current Java case state: a new investigation may start from a previously resolved or escalated case. Generation instructions separate observed payment facts from a recommendation requiring independent review. A narrow validator rejects observed completed-case/proposal assertions and unqualified claims that no action is needed, across generated prose, without rewriting the answer. Controls retain legitimate payment-completion facts, negated or modal case statements, “recommend resolving the case” and “no further financial action required.” The provider grammar also bounds policy-link count to distinct supplied policy IDs (currently one); existing outputs are not rewritten or deduplicated.

The next worker configuration also increases the provider HTTP timeout to 180 seconds because accepted synthesis took 110.700 and 118.032 seconds and another attempt reached 120 seconds. This is a configuration change requiring a distinct fresh run; it does not change the recorded first-run failure, guarantee latency, or provide strict whole-graph cancellation. Neither prompt changes nor targeted phrase rejection prove general entailment or injection immunity. Source tests and a new image cannot substitute for a new live explanation review.
