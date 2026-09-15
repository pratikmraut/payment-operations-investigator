# V5 selected development review

Codex AI reviewed two model-written findings from original synthetic development cases selected after earlier errors. Both were accepted, with two recorded successful calls each. No material factual error was identified in these two findings, with the wording limits below. This is not an independent human review or held-out accuracy result. Exact results, rule-owned fields, operational evidence, reconstructed contexts, provider timing and source checks are in [the JSON review](v5-selected-explanation-review.json).

| Case | Exact model-only finding | Review |
| --- | --- | --- |
| CASE-1021 | Payment PRV-1021 captured at 2026-09-11T06:20:05Z, authorized earlier, received late, ledger reflects INR 21,227.11 capture and INR 371.47 fee. | Correct capture time and earlier authorization; “received late” leaves the authorization webhook implicit. Capture and fee magnitude match. |
| CASE-1031 | A INR 6,113.19 refund was confirmed and absent from ledger, creating a discrepancy of INR 6,113.19. | Confirmation, zero refund entries in the supplied ledger, and exact discrepancy support the claim. “A INR” is a grammatical defect. |

For CASE-1021, authorization occurred at 06:20:02Z before capture at 06:20:05Z; the authorization notification arrived at 06:20:45Z after the capture notification at 06:20:10Z. The new finding does not repeat the prior capture-before-authorization contradiction. Its compressed receipt clause is understood against the nearest authorization antecedent and linked evidence; explicit naming would be clearer. The supplied ordering policy distinguishes those two axes. A positive fee magnitude does not claim a positive ledger posting: the actual fee entry is INR -371.47.

For CASE-1031, confirmation is INR 6,113.19. The supplied ledger has only capture and fee entries, net INR 24,024.87; provider payout is INR 17,911.68, leaving difference INR 6,113.19. The new finding describes absence from that snapshot instead of the previous unsupported posting claim, matching the supplied refund policy. It does not establish the contents of another system or a later snapshot.

Both findings use one applicable policy version once, authorized operational links and no completed case-action claim. Summary, outcome, confidence, missing-evidence list and proposed action/reason remain deterministic; no model text was repaired or rewritten. Direct-worker evaluation executes no Java review or case transition.

The running image was `4ed10f3b87761dd356543fb58c2ab83cf04beda7feb56402733517e7e02dfb25`, the frozen 131-test v5 revision. Both stages declared `qwen3:4b-instruct`; results record lexical retrieval, four threads, 4096 context, 180-second provider timeout and 384-token stage limits. Graph durations were 73.187 s and 60.433 s. Saved requests match their v4 counterparts except UUID; operational tool outputs and assessments match exactly. The context intentionally changed to separate timing axes and expose supplied-ledger refund entries. Reconstructed context is not a raw HTTP capture.

Two selected findings cannot establish general model accuracy, prompt-injection immunity or production readiness. These results justify continuing the coordinated six-family and full-stack validation; they do not replace it. Historical failures remain preserved separately.
