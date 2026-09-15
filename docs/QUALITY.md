# Quality and evaluation strategy

## Required correctness
- All case/knowledge/export/investigation paths are tenant-scoped.
- Unauthorized mutations, missing CSRF and insufficient roles are rejected.
- Money uses exact integers; fixture arithmetic is consistent.
- Two-person review uses the stored proposal and current case version.
- Replaying an identical command creates one business effect; changing its body with the same key fails.
- Worker unavailability and local-model errors remain explicit.
- Findings cite evidence that exists in the authorized snapshot and runbook version.
- Missing or contradictory evidence can produce INSUFFICIENT_EVIDENCE.
- Model context never includes truth labels.

## AI evaluation
Compare fixed evidence fetching plus rules/search, fixed evidence fetching plus RAG, and adaptive tools plus RAG. Every baseline has access to the same authorized sources. Separate replay and live-model results; replay is not an AI accuracy benchmark. Use frozen development/test seeds, outcome labels and evidence references.

The executed controlled comparison covers rules only versus the same fixed tools/rules with lexical retrieval. See [evaluation results](EVALUATION.md). Separate live-model integration and selected development cases do not form a controlled three-arm experiment. In the current version, deterministic rules own outcome and action; the model selects tools and authorized fact IDs. Agreement on rule-owned fields cannot measure independent model diagnostic ability. Review fact selection for relevance, coverage and support separately from service-rendered wording.

Measure outcome correctness, citation validity/support, retrieval recall, abstention, invalid action attempts, tool calls, elapsed time and token usage where provided. Report counts and sample size. Never infer human time savings without a measured human comparison.

## Verification layers
1. Generator invariants and reproducibility.
2. Java unit/integration tests for security, persistence, exact values and concurrency.
3. Python tests for evidence rules, retrieval scope, graph replay and provider failures.
4. React tests for critical forms/state/errors and real browser journeys.
5. Full local integration: login -> case -> investigation -> different reviewer -> decision -> audit/export.
6. Container rehearsal and runtime restart/recovery.
7. Saved validation record and truthful portfolio claim matrix.

Required evidence belongs in docs/validation. No release criterion is marked passed based only on code inspection.

## Explanation review rubric

Review saved generated text against the exact operational snapshot and the policy excerpt supplied to synthesis. Record whether payment-state claims, amounts, event ordering and proposed follow-up are supported; whether uncertainty is preserved; and whether the model falsely describes a proposal as an executed review or financial action. Valid IDs are necessary but do not prove support for the linked sentence. Identify the reviewer: a Codex-assisted review is not an independent human annotation study.

Preserve failed requests, rejected candidates and accepted but weak explanations. A timeout belongs in the attempted sample denominator. If a failure returns no usage, mark its model-call/token count unknown rather than zero. Prompt changes based on development failures require a separately named rerun; never replace the original report or describe it as untouched held-out performance.
