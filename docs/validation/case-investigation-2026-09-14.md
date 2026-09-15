# Saved case investigation delivery — 14 September 2026

## Outcome and scope

The saved-payment workflow now connects immutable attached evidence to a source-observation timeline, inspectable evidence documents, dynamic model questions, saved answers and an audit view. Java persists the command before returning HTTP 202. Each job owns one evidence ID/version/hash and frozen input documents; answers cannot silently move to a newer version. Failed inference is visible and has no canned answer or automatic retry.

The workbench is deployed on the existing local website at `http://127.0.0.1:5178/`. Its bank evidence API configuration is retained, but the deployed bank request/response wrapper and authentication remain unverified. This milestone made **zero bank calls** and did not connect directly to Oracle DB.

## Automated checks

| Check | Result and scope |
| --- | --- |
| Full Java Maven package | 207 tests; zero failures, errors or skips; successful JAR build |
| Projection checks included above | 11 tests for source projection, identity, coverage, timeline and guidance bounds |
| Investigation service checks included above | 17 tests for lifecycle, idempotency, queue/restart behavior, immutable binding and failure handling |
| Investigation controller checks included above | 4 HTTP/security tests |
| Full frontend suite | 153 tests passed across 11 files |
| Final answer-display adjustment | 18 focused tests passed; native TypeScript and production build passed |
| Main frontend TypeScript/build before final display adjustment | Passed; the final native build contains the deployed adjustment |

Contract tests use original synthetic fixtures and a mocked model. They are not answer-quality evaluations. The first full Java attempt encountered an older concurrent-import test error. A narrow test-only correction and successful focused/full reruns are preserved in the [concurrency note](case-investigation-import-concurrency-2026-09-14.md); import behavior was not changed.

The final deployed Java SHA-256 is `c7bdc2e3db5b062768f6aa42773e9c3084363f80ec5d74de8ff7aa58171f2f6f`, matching `services/api/target/payment-operations-api-0.1.0.jar`. Native frontend assets are `index-C7OCA9hs.js` and `index-BwjoZce2.css`. A closed-database/JAR/configuration backup was retained at `runtime/case-investigation-validation/backup-20260914-035649` before deployment.

## Actual local model run

The existing private case with four supplied source rows was used: PAYMENT 1, HOST 1, HISTORY 2 and STATUS 0. No new case or replacement evidence was created. The question was:

> Do these attached records confirm beneficiary credit? Explain the supporting evidence and what is still missing.

| Observation | Measured result |
| --- | --- |
| Model | Ollama `qwen3:8b` |
| Actual model calls | 1 |
| Worker-reported duration | 64.413 seconds |
| Client submission-to-completed observation | 64.906 seconds |
| Tokens | 5,950 input; 291 output |
| Result | COMPLETED, model-generated, exact joined model claims |
| Same-key retry | Returned the same job |
| Reloaded saved answer | Equal to the original completed response |
| Saved case records | All 7 preserved |
| Bank calls | 0 |

Native Ollama's local runner log records Intel Arc 140V, Vulkan0 and `offloaded 37/37 layers to GPU`, with the runner starting at 03:58:32 +05:30 during this request. GPU layer offload does not mean the CPU performs no orchestration or host-memory work. This is one observation, not a latency guarantee or a controlled comparison with the earlier CPU benchmark.

The projection supplied 17 documents: the selected case context, four group-coverage documents, four exact source rows, three original generic guides and five previously reviewed private knowledge documents. Only the five knowledge documents were copied from the earlier private guidance bundle; no earlier payment documents or prepared answers were reused. The private guidance remains tenant-scoped and states that deployment matching is unverified.

Private receipts are under `runtime/case-investigation-validation/live/`: submitted job, frozen context, completed response, case records before/after, workbench and `receipt.json`. They contain private identifiers and are excluded from repository/public artifacts. Runtime details and original exports were not copied into public test fixtures.

## Semantic review: not a complete grounding pass

The overall conclusion, that these records do not confirm beneficiary credit, is supported. The four numeric status statements match the actual PAYMENT/HOST values and the supplied field-specific definitions. However:

1. Those statements cite `CASE-CONTEXT` and `NEFT-FIELD-DEFINITIONS`, omitting `PAYMENT-ROW-1` or `HOST-ROW-1`. The cited context contains no raw status fields, so the citations do not establish the observed values for this payment.
2. “Transaction was released” overstates a source-label mapping as an established operational event. The mapping's `deploymentMatchVerified=false` qualification is not carried into the answer.
3. The question asks what is still missing, but the model returned empty `unknowns` and `nextChecks`. It did not explain the need for native OBPM outcome evidence, network/N10/return confirmation and posted accounting/reversals described in the available guidance.

The actual output remains preserved. No scripted rewrite, additional summary, replacement citation or fabricated next check was inserted. The UI shows the experimental-answer notice and explicit messages for empty unknowns/next checks. Source-membership validation protects structure and provenance; semantic validation and evidence completeness still require improvement and operator review.

## Browser verification

On the deployed Edge application, verified sign-in and the unchanged seven-case queue, then opened the case containing the live answer. The workbench showed four source observations, evidence version 1 and the completed answer with one actual call and 64.4 seconds displayed. Clicking the PAYMENT timeline citation selected Evidence and opened the exact saved row and source locator. Investigations showed the persisted question. Audit trail showed case opening, evidence attachment, question submission, processing start and answer completion from stored records. The completed answer remained available on a fresh page load without a new submission.

The answer appears before unknowns and next checks. No model run is triggered by opening the case, switching tabs, fetching evidence history or selecting an existing answer. The existing three evidence input tabs remain available.

## Preserved components and remaining limits

SHA-256 checks confirmed no changes to the existing Python answer worker/configuration, Java `UatService`/`UatWorkerClient`, standalone Evidence Q&A component or native GPU startup script. The slow CPU demonstration remains preserved. The workbench adds case orchestration around the existing model path.

One Java background thread processes at most five admitted unfinished questions. Browser reload reads durable jobs; Java restart marks interrupted jobs failed without automatically repeating inference. A timeout does not guarantee that Ollama has stopped. Large saved evidence sets can exceed the model document/context budget and must fail visibly instead of dropping rows. This increment does not implement final case approval/resolution or any payment execution.

Operator steps and the sequence flow are in [Questions over saved case evidence](../CASE_INVESTIGATION.md).
