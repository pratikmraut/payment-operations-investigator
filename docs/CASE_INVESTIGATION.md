# Questions over saved case evidence

The case-investigation workbench stores questions and model answers against exact saved evidence versions. New questions now use the separate [case RAG pipeline](CASE_RAG.md), with scoped guidance ranking, generated cited claims, checks of recognized literal field quotations and Java revalidation. The normal `/cases` page opens saved Payment cases directly, without a Demo cases tab. Legacy code, data and direct routes remain preserved.

The earlier workbench milestone was deployed locally and recorded one Qwen3 8B answer in 64.413 seconds, with persisted-answer reload. That historical run had citation and completeness limitations; it is not an accuracy pass or a benchmark of the new case pipeline. Its 207 Java / 153 frontend test counts and later 18 focused checks belong to that earlier [delivery record](validation/case-investigation-2026-09-14.md). See [STATUS](STATUS.md) for final validation and deployment results for subsequent changes.

A payment case can hold multiple immutable evidence versions and multiple questions. Each investigation records the question, selected evidence ID and hash, exact authorized worker input, and either a validated model response or a visible failure. Adding evidence later must not rewrite an earlier answer or silently change the evidence used by a running question.

The later **Evidence Q&A** entry at `/evidences/questions` now reuses this same workbench and backend for saved payment cases. Select a case there, or follow **Ask about this version** from the Evidence library. `/evidences/questions/{caseId}/{evidenceId}` preserves an explicit case/version selection in the URL. The original case route remains `/payment-cases/{caseId}`; both views read the same jobs, answers and sources rather than maintaining separate histories. See [the Evidence Q&A operator flow](EVIDENCE_QA.md). The historical counts and timing above describe the earlier case-workbench milestone, not a new inference benchmark for this entry page.

## Analyst workflow

1. Open the saved payment case and attach the four PAYMENT, HOST, HISTORY and STATUS result groups through the existing evidence controls.
2. Inspect the saved rows, coverage and warnings. Manual, JSON and Excel inputs have unverified query completion; an empty result does not prove that a query completed or that a payment event did not occur.
3. Select the evidence version for the question, either in the case workbench or **Evidence Q&A**. Inspect its context to see the exact documents that will be available to the model. A deep link to an unavailable version shows an error and does not substitute another version.
4. Choose one of the six **Suggested questions**, or write your own self-contained question. Suggestions cover the payment overview, OBPM acceptance, beneficiary credit, status codes, history and missing evidence/next checks. Selecting a suggestion fills the editable question; it does not submit it. Click **Run investigation** when ready. Java creates a durable job before returning control to the page.
5. The workbench reloads pending and completed jobs from the server. Returning to the page should show the same job, rather than starting another model call.
6. Read the generated claims beside their citations, any checked literal field quotations, unknowns and next checks. Ordinary prose without recognized quotations remains source-cited and is not labelled field-verified. Check the claim's interpretation yourself before treating it as a payment finding; even a matching raw field value does not prove the surrounding prose.

Questions do not execute payments, call recovery procedures, change a case's payment outcome, or automatically fetch fresh bank evidence. A new inquiry remains a separate explicit evidence-acquisition action. Earlier answers are history, not implicit conversation context for subsequent questions.

The Evidence Q&A page initially opens the shared **Investigations** view and provides case selection, search and a link back to evidence collection. The question panel, suggested questions, source inspection, result rendering, permission checks and persistence remain shared with the case workbench. Selecting a case/version or reading a saved answer does not submit a new model job. The separate **Export demo** route `/evidences/exports` retains its original standalone snapshots and answers.

## Request contract

The case routes use the existing authenticated Java API:

| Route | Purpose |
| --- | --- |
| `GET /api/payment-cases/{caseId}/workbench` | Authorized case workspace containing evidence summaries, latest evidence ID, audit information and investigation history |
| `GET /api/payment-cases/{caseId}/evidence/{evidenceId}/context` | Authorized projection of one immutable evidence snapshot into model documents |
| `POST /api/payment-cases/{caseId}/investigations` | Create or replay an investigation command using `{question,evidenceId,evidenceHash}` and `Idempotency-Key` |
| `GET /api/payment-cases/{caseId}/investigations/{investigationId}` | Retrieve the authorized job, its frozen documents and its validated answer when completed |

Mutations require an authenticated writer and the existing `X-CSRF-Token` header. Bank and branch authorization come from the saved case and server configuration. Clients cannot supply a replacement document bundle, tenant, bank endpoint, model provider or arbitrary source location.

The question is bounded to 2,000 characters. The selected evidence must belong to the case and match the supplied hash. The server freezes the projected documents for the job. The same idempotency key and same command replay the recorded job; a different command with that key must be rejected. Retrying a failed question requires an explicit new attempt rather than an automatic model retry.

`POST` returns HTTP 202 with the persisted job summary. Its status is `QUEUED`, `RUNNING`, `COMPLETED` or `FAILED`; scheduling failure can already be recorded in the returned summary. Each summary includes the case ID, evidence ID/version/hash, question, creator, creation time, input and guidance hashes, and projection warnings. New jobs also record `answerContract="case-evidence-rag-v1"`. Lifecycle timestamps and a sanitized error appear when applicable. The workbench returns summaries, so polling does not repeatedly download source documents or model answers. The job-detail route returns the frozen `documents` and completed `answer`; the stored worker-input envelope is not exposed as an alternate writable contract.

The context route returns the evidence projection with its documents, source-observation timeline, warnings, guidance hash, selected evidence ID/version/hash and nonempty row count. This is the inspectable input context, not a generated investigation or verified payment chronology. Evidence containing no rows is rejected for investigation.

## Execution and persistence

```mermaid
sequenceDiagram
    actor Analyst
    participant UI as Case workbench or Evidence Q&A
    participant Java as Case investigation API
    participant DB as Case and job database
    participant Executor as Bounded background executor
    participant Worker as Case RAG LangGraph worker
    participant Model as Local Ollama
    Analyst->>UI: Select evidence and submit question
    UI->>Java: Question, evidence ID/hash, idempotency key, CSRF
    Java->>DB: Authorize case and load immutable evidence
    Java->>Java: Validate scope, hash and projected documents
    Java->>DB: Persist QUEUED job and exact input
    Java-->>UI: Recorded job
    Java->>Executor: Dispatch after durable creation
    Executor->>DB: Recheck job, case and evidence binding
    Executor->>Worker: POST /case/answer with question, snapshot ID/hash, frozen documents
    Worker->>Worker: Check native row scope and rank guidance with expanded-query BM25
    Worker->>Worker: Retain all supplied documents and enforce context budget
    Worker->>Model: Generate cited claims, unknowns and next checks
    Model-->>Worker: Newly generated structured response
    Worker->>Worker: Validate shape, citations, recognized literal quotations and required unknowns/checks
    opt First response fails validation
        Worker->>Model: Same question and sources with bounded validation feedback
        Model-->>Worker: Complete corrected model-generated response
        Worker->>Worker: Apply the same checks; fail if still invalid
    end
    Worker-->>Executor: Model response and provenance, or error
    Executor->>Executor: Revalidate identity, citations, fields, metadata and exact claim composition
    Executor->>DB: Save terminal result without overwriting prior evidence or answers
    UI->>Java: Reload workbench
    Java->>DB: Authorize and read persisted job status/history
    Java-->>UI: Pending status, completed answer or visible failure
```

The `fcr_case_investigation` table owns the job lifecycle. The case Python graph has no checkpoint store and does not provide durable queueing. Browser reload recovery therefore means reading the same persisted job. If the Java process restarts with unfinished jobs, startup reconciliation marks them failed; it does not pretend that inference completed or silently submit them again. Managed startup and shutdown reject new submissions with HTTP 503 until reconciliation finishes or the service is available again.

A single background thread processes case questions. Admission is limited to five unfinished jobs, including any running job; a full queue returns HTTP 429 before another job is saved. The worker also shares a lock with the existing investigation/UAT paths. It waits briefly for that lock and returns `UAT_MODEL_BUSY` if another request is active. A Java timeout stops waiting for the HTTP response, but does not prove the underlying Ollama computation stopped. Failures remain visible and do not trigger a canned answer or an automatic duplicate call.

Before inference and before accepting its response, Java rechecks case access and the selected evidence's ID, version and content hash. Reading a job detail verifies its stored input hash, evidence binding and any completed answer. A later separate evidence version remains selectable without invalidating the earlier immutable version. These checks do not change earlier records to make a newer answer appear correct.

## Case pipeline with the current local model

The service calls `UatWorkerClient.answerCase`, which sends the following shape to service-authenticated worker `POST /case/answer`:

```json
{
  "question": "A self-contained question about the supplied evidence",
  "snapshotId": "The selected immutable evidence ID",
  "evidenceHash": "The selected evidence hash",
  "documents": []
}
```

The empty document array above is only a shape illustration; an actual case request must contain at least one native PAYMENT, HOST, HISTORY or STATUS row document. Each document has `id`, `kind`, `title`, `content` and `source`. Source metadata must identify the supplied evidence or approved note; it must not invent an executed event or a verified external confirmation.

The shared contract permits at most 100 documents and 50,000 characters across their IDs, titles, contents and source metadata. Use the tighter Java limits when creating documents: title up to 500 characters, source file/locator up to 1,000, source sheet/range up to 200. The worker additionally applies a conservative UTF-8 context budget including its prompt, output schema and output reserve. A valid large evidence upload can exceed the model's usable context. The investigation must reject or fail visibly rather than silently discard source rows or caveats.

The worker's retrieval expands selected question terms and applies BM25 lexical ranking to supplied guidance, retaining all supplied documents unchanged. It performs no external web search, embedding search or bank database read. LangChain `ChatOllama` generates claim wording inside the case LangGraph scope-check/retrieve/generate/validate sequence. Earlier prepared answers and evaluation ground truth are not projected into the input. The configured Ollama weights and native GPU runtime are unchanged; this is a RAG/code improvement, not fine-tuning.

The model returns its original cited-claim response shape, without extra model-authored support objects. Code checks recognized explicit native field/value quotations and attaches required `rag` metadata containing the pipeline ID, system-prompt hash, check names and ordered per-claim quotation records. Claims without recognized quotations remain `source-cited` and can reference evidence or guidance. `CaseAnswerValidator` reuses the unchanged `UatService.validateAnswer` checks on the original response shape, then validates metadata, any populated exact quotations and required unknowns/next checks. Populated interpretation records must cite knowledge as well as evidence; that verifies citation presence, not whether the definition actually proves its interpretation. See [the complete support contract](CASE_RAG.md#inspectable-support-contract) and the retained structured-support experiment described there.

The displayed answer is the exact join of generated claim texts. Validation does not add a second free-form summary, rewrite model prose or replace a failed generation with prepared text. One first-response validation failure may trigger a single model correction using the same question and sources plus bounded validation feedback. The corrected response must pass the same checks; further correction attempts are prohibited. Provider errors, truncation and timeouts are not retried. Completed answers report `model.actualCalls` as 1 or 2 and include both attempts' reported token counts and generation time, preserving unknown token counts as null. Each attempt uses half the configured provider timeout; queueing and other overhead remain separate. Old saved answers retain their original shape and validation; new job contract markers prevent a missing RAG receipt from silently becoming an old-format answer.

## Interpretation and current limits

The original response validation label remains `structure-and-source-membership-only`. The additional case RAG receipt records checks of recognized literal field quotations and required response sections. Neither establishes that a claim follows logically from its cited records. The literal checker does not understand every sentence or verify all numbers in ordinary prose. An unrelated but valid field or inappropriate knowledge citation can still accompany an incorrect conclusion. Earlier local runs produced attribution and status interpretation errors; preserve those [validation findings](UAT_MODEL_QA.md) alongside the [native GPU setup](NATIVE_GPU_SETUP.md). Stronger checks do not by themselves establish improved semantic accuracy.

Evidence hashes bind recorded content, not its real-world truth or database completeness. Source-code definitions describe possible behavior, not proof that a particular transaction executed that path. Status codes require the exact field/domain definition. Missing evidence must remain unknown, and none of these four source groups alone proves beneficiary credit, settlement or an approved corrective procedure.

The existing CPU demonstration, GPU runtime, original export prompt, `/uat/answer` response format and original UAT receipts remain preserved. New case questions use their own prompt and extended response contract around the same configured local model. No speed, accuracy or production-readiness claim follows from that reuse without a corresponding measurement and factual review.

## Verification

The initial case RAG contract alignment passed the full Java package with **243 tests**, including 10 case-answer validator tests, 20 case-job lifecycle tests and four case-investigation HTTP/security tests. The final source-citation/literal-quotation contract and bounded correction passed **33 focused tests**: 13 validator tests and the 20 lifecycle tests. The current worker, browser and actual-model receipts are recorded separately in [STATUS](STATUS.md); successful structural validation is not factual approval.

The following records describe the earlier workbench milestone and remain historical evidence:

- Passing contract tests: writer/CSRF enforcement, tenant isolation, evidence ownership/hash checks, stable version binding, idempotent/concurrent retries, durable pending reads, queue bounds and restart reconciliation.
- Passing contract tests: successful-response validation, provider failures, malformed citations, source/answer tampering and prevention of answer/evidence overwrites.
- Passed deployed verification: one real model call, idempotent replay of the same job, persisted answer equality, unchanged seven case records, and unchanged model baseline files. Browser checks covered the source timeline, source-row opening, saved answer/history, audit events and completed-answer reload.
- Semantic review remains incomplete: the live answer reached a supported overall conclusion but omitted raw-row citations, qualified deployment context and the requested missing-evidence checks. These are documented limitations, not repaired or replaced answer text.

`CaseInvestigationServiceTest` passed 17 synthetic lifecycle tests; `CaseInvestigationControllerTest` passed four HTTP/security tests; `CaseEvidenceProjectionTest` passed 11 projection tests. They use synthetic fixtures and never read the user's exports. The [main validation record](validation/case-investigation-2026-09-14.md) distinguishes those results from the real local model run; the [concurrency test note](validation/case-investigation-import-concurrency-2026-09-14.md) preserves the initial full-suite error and its narrow correction.
