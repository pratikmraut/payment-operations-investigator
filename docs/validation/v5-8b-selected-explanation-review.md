# V5 8B selected duplicate review

Codex AI reviewed one accepted synthetic development finding, selected after the 4B duplicate-delivery timing ambiguity. The 8B run recorded two successful calls on the unchanged 131-test v5 worker. This one-case diagnostic is not independent accuracy evidence or sufficient reason to change the default model.

Exact unchanged finding:

> Payment PRV-1011 succeeded with INR 5,710.33 captured, INR 99.93 fee, and INR 5,610.40 provider payout. Webhook WH-1011-1 applied, WH-1011-2 ignored as duplicate.

All stated facts match the supplied snapshot. The positive fee magnitude corresponds to the signed INR -99.93 fee entry. The prior “deliveries ... occurred at same time” claim is absent, so this finding does not conflate occurrence with receipt. No material factual error was identified. Both ledger IDs are linked; the direct provider evidence ID is omitted despite the payout claim. That is a remaining claim-to-record traceability limitation, not an incorrect amount. The one actually supplied deduplication policy is linked once.

Saved requests are exactly equal to the prior 4B request after excluding the investigation UUID. Tool outputs, deterministic assessment and reconstructed finding context are exactly equal. Both recorded provider stages identify `qwen3:8b`; other settings remain lexical retrieval, four threads, 4096 context, 180-second provider timeout and 384-token output limits. The graph took 184.897 s, with 54.666 s planning and 130.199 s synthesis; provider load/prefill/decoding durations are separately retained in [the exact JSON review](v5-8b-selected-explanation-review.json).

Only finding text and links are model-written. Summary, outcome, confidence, missing evidence and proposal are rule-owned. No model output was rewritten, no Java reviewer decision or case transition occurred in this direct-worker test, and no reviewer model calls or source changes were made. Context equality is reconstructed from saved state, not a raw HTTP prompt capture. Broader quality and full-stack suitability remain separate questions.
