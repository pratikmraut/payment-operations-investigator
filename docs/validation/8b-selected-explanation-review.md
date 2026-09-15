# Controlled 8B explanation review

Codex AI reviewed two selected synthetic development findings, not an independent human or held-out evaluation. Both requests were accepted and recorded two successful provider calls each. The full exact findings, rule-owned fields, operational evidence, policy, stage metadata and comparison hashes are in [the JSON receipt](8b-selected-explanation-review.json).

The unchanged 112-test worker image was `f73e182f0dd7c47c1db7ef1e8e70814a901788e70ff087552842166872e20391`. Both stages declare `qwen3:8b`. Settings remain lexical retrieval, four threads, 4096 context, 180-second provider timeout, temperature zero and thinking disabled. Source still defaults to 4B. Saved request bodies are identical to their prior 4B counterparts except investigation UUID; operational tool outputs and reconstructed finding contexts compare exactly equal. Reconstruction is not a raw HTTP prompt capture.

| Case | Review of model-only finding | Observed graph duration |
| --- | --- | --- |
| CASE-1021 | Material timing ambiguity remains; not an unqualified semantic pass | 124.658 s |
| CASE-1031 | Supported; earlier unsupported posting claim removed | 106.467 s |

CASE-1021 says: “Payment PRV-1021 was captured with INR 21,227.11, netting INR 20,855.64 after fees. Webhook WH-1021-1 applied capture event after stale authorization WH-1021-2.” The money matches. Authorization occurred at 06:20:02Z before capture at 06:20:05Z, but the capture webhook arrived at 06:20:10Z before the stale authorization arrived at 06:20:45Z. The sentence puts webhook application as the subject of “after”; it does not distinguish the two time axes. Its ordering is supported only as provider occurrence order, not a demonstrated webhook application order. No separate processing timestamp exists in the supplied snapshot. This materially ambiguous explanation does not cleanly satisfy the supplied policy's instruction to distinguish occurrence from receipt.

CASE-1031 says: “Refund confirmed for PRV-1031 with amount INR 6,113.19, discrepancy INR 6,113.19, and ledger net INR 24,024.87.” Confirmation and all three named amounts match the snapshot. The 4B finding had asserted “confirmed and posted” despite no refund ledger entry; this finding makes no posting claim. It does not itself explain the missing posting, but its stated facts are supported. The missing-posting summary and escalation proposal remain rule-owned.

Both findings use a single applicable policy link and contain no completed case-action claim. One of two model findings had no material error identified; the other retains material temporal ambiguity. Two deliberately selected cases cannot establish general accuracy or justify changing the default model. Rule-owned outcome/action agreement is separate from this semantic review. No text was repaired or rewritten, and this direct-worker sample executed no Java case transition or reviewer decision.
