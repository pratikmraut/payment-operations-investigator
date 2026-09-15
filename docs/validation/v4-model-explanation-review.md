# v4 model explanation review

Reviewer: **Codex AI, not an independent human reviewer**. Evidence is from original synthetic development fixtures and the frozen 112-test worker image `f73e182f0dd7`. No model call, source edit or output rewrite was performed during this review.

The [v4 full-stack acceptance](acceptance-compose-ollama-v4.json) passed 17 checks in 100.801 seconds. Its two timeout investigations each made two successful model calls. [Exact results and semantic review](v4-live-acceptance-results.json) preserve both model-written findings, the distinct rule-owned fields, authenticated API reads and reconstructed observed-facts inputs. Both findings have supported payment facts and no workflow overclaim. CASE-1003 calls the exact net/payout amount INR 18,495.26 “total”; “net settlement” would identify the quantity more clearly, since gross capture was INR 18,824.69. The original wording is unchanged.

The separate [six-family development sample](evaluation-ollama-development-v4.json) returned six accepted responses with 11 recorded successful model calls. Five responses contain one model-written finding; CASE-1041 used one planner call and intentionally skipped finding generation. Those delivery and deterministic outcome/reference results **do not establish prose accuracy**. Review found material semantic errors in two of the five generated findings. [Exact six-row review evidence](v4-development-explanation-review.json) retains every parsed model finding, rule-owned response field, tool output, applicable policy, reconstructed finding input and recorded stage timing.

| Case | Model contribution | Review |
|---|---|---|
| CASE-1001 | One finding, two calls | Supported timeout/provider/capture facts; INR 15,199.83 correctly names the captured amount. |
| CASE-1011 | One finding, two calls | Duplicate facts supported; capture/fee claims need the settlement snapshot beyond the finding's two webhook links. |
| CASE-1021 | One finding, two calls | **Material error:** confuses notification receipt order with actual event occurrence order. |
| CASE-1031 | One finding, two calls | **Material error:** equates confirmed refund with a posting that is absent from the ledger. |
| CASE-1041 | Planner only, one call | No model finding to score. Rule-derived uncertainty remains conservative but generic. |
| CASE-1051 | One finding, two calls | Processor rejection plus no capture supports the stated failure. |

## Occurrence and receipt are different facts

CASE-1021's exact finding is:

> A payment was captured (INR 21,227.11) before authorization, with stale event ignored and ledger consistent.

The amount, ignored-stale status and final reconciliation are supported. The unqualified temporal clause is not. Authorization occurred at **06:20:02Z**, before capture at **06:20:05Z**. The captured notification arrived at **06:20:10Z**, before the stale authorization notification arrived at **06:20:45Z**. Thus receipt order was reversed; payment occurrence order was not.

Both timestamp pairs were present in the model's observed-facts input. The actually supplied `RB-EVENT-ORDER:v1` explicitly distinguishes occurrence time from receipt time and describes earlier authorization arriving after capture. Correct identifiers and a rule-derived OUT_OF_ORDER_WEBHOOK/RESOLVE_CASE result did not prevent the finding from making the wrong temporal claim. The correct rule-owned summary does not repair the separate model-written sentence.

## Confirmation is not ledger posting

CASE-1031's exact finding is:

> A refund of INR 6,113.19 was confirmed and posted, matching the discrepancy in settlement.

The refund amount and confirmation are supported: `EVT-1031-5` is REFUND_CONFIRMED/SUCCEEDED and provider refund is 611319 minor units. **Ledger refund is zero**. The ledger contains CAPTURE 2445279 and FEE -42792, with no REFUND entry. Ledger net 2402487 minus provider payout 1791168 equals the confirmed refund 611319.

The unqualified “posted” clause is unsupported as a ledger-posting assertion; the model does not identify a separate provider-side posting event. The supplied `RB-REFUND:v1` describes exactly the opposite distinction: a confirmed processor refund absent from the ledger produces this difference. The rule-owned summary correctly reports that the refund has not reconciled. This remains a material semantic error despite correct money and valid evidence/citation IDs.

## Remaining findings and limits

CASE-1011 correctly reports two deliveries of the same provider event, with one applied and one ignored. Its capture/fee statement is supported by the supplied settlement facts, but its finding links only the two webhook records. Complete claim-to-record linkage is therefore broader than the current identity checks. The provider-event attribute named in the text is present on those linked webhook records.

CASE-1041 contains no generated finding and must not be counted as a successful model explanation. The UNKNOWN provider state, unavailable lookup and absent capture support the deterministic uncertainty result. Its missing-evidence message is still generic. The top retrieved policy is rejection guidance; because synthesis was skipped, that policy was not supplied to an explanation model and cannot be credited as model grounding.

CASE-1051 includes both processor rejection and absent capture; together they support processing failure under the supplied policy. Absence of capture alone would not establish failure. No unsupported recommendation or completed-case assertion was identified in the three supported development findings.

A bounded follow-up can make occurrence order and receipt order explicit in separate, timestamp-derived sequences, and distinguish provider-confirmed refund from observed ledger posting. Such a change would address plausible context ambiguity; it would not prove why the model erred or guarantee correct future prose. Any further change needs a distinct development rerun and honest retention of these failures. This review uses no held-out examples, and makes no real-world accuracy or production-readiness claim.
