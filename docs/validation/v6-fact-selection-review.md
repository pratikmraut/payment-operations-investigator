# V6 development fact-selection review

Codex performed this review of original synthetic development fixtures; it is not an independent human review or a held-out quality estimate.

All six requests returned an accepted response: five actual planner + fact-selection paths (ten calls) and one planner-only insufficient-evidence path (one call). There were eleven completed provider calls, ten selected fact IDs and zero model-authored prose findings. Five displayed findings matched selected catalog text and complete links exactly. No material factual error was identified in those five service-authored findings; three selections had important coverage limitations.

| Case | Selected facts | Review |
| --- | --- | --- |
| CASE-1001 | FACT-PROVIDER-STATUS, FACT-SUCCESS-EVENT | Generic corroboration with material coverage limitation: omits available timeout observation at 06:00:08Z and capture/settlement facts. It does not by itself explain the timeout exception or demonstrate ledger reconciliation. |
| CASE-1011 | FACT-PROVIDER-STATUS, FACT-SUCCESS-EVENT | Generic corroboration with material coverage limitation: omits the available repeated-delivery fact and ledger evidence. The catalog correctly records two delivery records, one APPLIED and one IGNORED_DUPLICATE, but the model did not select it. |
| CASE-1021 | FACT-PROVIDER-STATUS, FACT-SUCCESS-EVENT | Generic corroboration with material coverage limitation: omits the available webhook timing fact. Its unselected catalog sentence correctly separates authorization occurrence 06:20:02Z/receipt 06:20:45Z from capture occurrence 06:20:05Z/receipt 06:20:10Z. The APPLIED/IGNORED_STALE records remain visible in the tool trace. |
| CASE-1031 | FACT-SETTLEMENT, FACT-REFUND-OBSERVATIONS | Specific and useful: the selected settlement and refund facts explain the posting discrepancy directly. Absence is explicitly scoped to supplied ledger membership; no claim is made about records outside that snapshot. |
| CASE-1041 | No selection; planner only | The rule-owned requests are actionable and conservative: establish timestamped provider status, confirm empty ledger coverage through a cutoff, and inspect/confirm delivery history. UNKNOWN provider, empty ledger and empty webhooks support these requests without asserting missing records exist. |
| CASE-1051 | FACT-PROVIDER-STATUS, FACT-TERMINAL-EVENT | Specific and useful for the observed failure. The selected pair omits zero supplied capture entries, which remain in the rule-owned summary and tool trace; it is not a complete restatement of every policy prerequisite. |

The timeout, duplicate and ordering cases selected generic provider-status plus successful-event corroboration. Their two clocks are explicitly distinguished and correctly linked, but the selected facts omit the specific exception and ledger evidence. Those facts remain available in the catalog/tool trace, while the rule-owned summaries communicate the diagnoses. The model's selection usefulness remains experimental; valid IDs are not sufficient evidence of a useful explanation.

The refund selection precisely names INR 24,024.87 ledger net, INR 17,911.68 provider payout and INR 6,113.19 difference, then distinguishes confirmed refund from zero entries in the supplied ledger. Full available ledger membership, confirmation and provider links plus sourceTools are preserved. This does not establish what exists outside that snapshot. Failure selection correctly distinguishes the provider status observation from the terminal event timestamp, but leaves the no-capture prerequisite to the rule summary/tool trace.

CASE-1041 is excluded from the five-finding denominator. It ran one real planner call and returned no finding; three rule-owned requests ask for current provider status, ledger-cutoff coverage and delivery history. No policy was supplied to a skipped selector. This direct-worker batch executed no Java reviewer decision or case transition.

The running worker matched the 198-test V6 receipt and image `90077f26ab568780a0dda8043b6b089442da99fbae1ba84622f926f5d88c0200`. Every accepted result recorded qwen3:4b-instruct, lexical retrieval, four threads, 4096 context, 180-second provider timeout and 384-token stage limits. These cases used the explicitly labelled standalone money baseline; the Java path is tested separately.

Exact unmodified parsed selections, fixed sentences, full catalogs, operational outputs, actual supplied policies, metrics and review notes are preserved in [v6-development-fact-selection-review.json](v6-development-fact-selection-review.json). Raw provider bytes were not separately retained. No answer repair, fallback or additional inference occurred during review. Historical model-prose errors remain preserved in prior receipts.
