# Local model questions over UAT exports

**Current default:** open the Evidence library’s [Export Q&A tab](http://127.0.0.1:5178/evidences/exports) for Intel GPU inference; select Analyst · Northstar and use `demo-pass-local`. Sign-in appears at `/` and then restores the requested `/evidences/exports` page. Run `tools/start.ps1` to start the prepared native profile. The original CPU demonstration is preserved at port 5180. See [current GPU setup](NATIVE_GPU_SETUP.md) and [the login/default repair](validation/gpu-default-2026-09-13.md). This frontend rename leaves the `/api/uat` API and existing model configuration unchanged.

**Current validation:** dynamic inference works, but the final live factual review failed. The model attributed fields to a source record that does not contain them and mixed separate status values. Treat this page as an experimental evidence workspace. The [dated validation report](validation/uat-qa-2026-09-13.md) retains the errors and their scope; citations and exact text composition do not certify factual correctness.

The UAT evidence page adds open-ended, model-written answers to the local website. The analyst selects an export snapshot and asks a question. The answer is generated from that snapshot and its supplied source notes. The earlier synthetic investigation workflow still uses model selection of catalogued facts; this new page is a separate inference path with no fixed payment answers.

## Evidence to answer

```mermaid
sequenceDiagram
    actor Analyst
    participant React as UAT evidence page
    participant Java as Authorized Java API
    participant Files as Private snapshot and answer files
    participant Graph as LangGraph worker
    participant Model as Local Ollama model
    Analyst->>React: Select snapshot; enter any payment question
    React->>Java: Question plus selected evidence hash and CSRF token
    Java->>Files: Load and validate session-tenant snapshot
    Java->>Java: Reject stale hash or invalid source contract
    Java->>Graph: Authorized question and source documents
    Graph->>Graph: Keep all export evidence; rank supplied source notes
    Graph->>Model: Question, records, source notes, output schema (bounded 900s UAT read)
    Note over React,Model: Page shows measured elapsed time; no completion estimate
    Model-->>Graph: Newly written cited claims, unknowns, next checks
    Graph->>Graph: Check output shape, truncation and citation membership
    Graph->>Graph: Assemble display text from exact generated claim paragraphs
    Graph-->>Java: Exact claim wording, composition marker and inference provenance
    Java->>Java: Verify identity, hash, citations, exact composition and provenance
    Java->>Files: Persist private request and answer receipt
    Java-->>React: Answer and source documents
    React-->>Analyst: Generated explanation, evidence links and model usage
```

The initial collection is small. Retrieval ranks the supplied knowledge documents lexically and includes all of them, along with all exported evidence, so relevant field caveats are not dropped. There is no external web lookup or vector search in this path. A conservative context budget rejects oversized requests rather than silently removing source material.

The cited claim prose comes from LangChain `ChatOllama` inside a LangGraph retrieve/generate/validate sequence. There is one model invocation per successful question. The model writes `claims`, `unknowns` and `nextChecks`; it does not generate a separate summary paragraph. The worker joins the exact generated claim texts with two newlines into the API's `answer` field and sets `answerComposition="joined-model-claims"`. Java verifies that equality, and React displays the generated claims with their citations. No new assertion can be introduced by a second summary-generation step. The service attaches identifiers, exact source objects and reported token/timing metadata; it does not substitute a prepared answer on failure. The earlier offline `PAYMENT_QA.md` report is not provided to this model.

Earlier saved free-prose summaries remain preserved and are labeled separately. Both tested local models introduced an unsupported outcome in that redundant summary despite uncertain cited claims. Removing it limits this observed failure mode; the generated claims themselves can still be wrong and require source review. This path uses no fixed fact-sentence catalog and does not rewrite the model's claims.

## Preparing and opening a snapshot

1. Place the four text-preserving function exports in ignored `dbdata/`, named `<reference>_AP_BA_NEFT_PAYMENT_INQ.xlsx`, `<reference>_AP_BA_NEFT_HOST_INQ.xlsx`, `<reference>_AP_BA_NEFT_HISTORY_INQ.xlsx` and `<reference>_AP_BA_NEFT_STATUS_INQ.xlsx`.
2. Use the reviewed local export contract in `runtime/obpm-uat/export-contract.json`. Optional private knowledge documents should contain field definitions and source provenance, not transaction-specific question/answer pairs. The supplied local source notes remain under `runtime/obpm-uat/private/qa/`.
3. Prepare a new snapshot identifier, preserving earlier versions:

```powershell
python tools/prepare_uat_qa.py --input-dir dbdata `
  --reference <fcr-reference> --snapshot-id <new-snapshot-id> `
  --tenant-id northstar `
  --knowledge runtime/obpm-uat/private/qa/source-notes.json
```

The preparer reads the XLSX files without altering them, distinguishes the exported row-number column and empty second sheet, validates the exact function headers and reported counts, rejects numeric/formula cells, and preserves exact decimal strings and reference namespaces. It requires one originating payment and consistent reference/branch/bank scope. It computes simple consistency checks and a source-local history interval. It does not classify a payment outcome or call a model. Source count agreement does not establish full query completion or system-wide coverage.

Computed history evidence includes adjacent raw field differences, exact elapsed seconds and formatted durations, with the originating row IDs. Tied timestamps are grouped without inventing order; missing timestamps and duplicate rows remain explicit. These calculations apply to the selected records and contain no payment-specific answers. The model generates cited claims and sees losslessly parsed JSON content where possible; exact original document strings are retained for provenance.

Reviewed field definitions can be represented as structured source documents listing the applicable table columns, source constants and unmapped domains. Equal codes in two different columns do not imply the same meaning. These are reusable source definitions, not transaction-specific answers. The response contract allows up to five claims, three unknowns and three next checks. Next checks must be readable inspection steps; bare technical identifiers are rejected as a formatting error. These controls still do not constitute semantic verification.

4. Start the prepared GPU workspace using `tools/start.ps1`. To explicitly run the preserved CPU workspace on port 5180, include both the private-evidence configuration and the web-port override:

```powershell
docker compose -f compose.yaml -f infra/compose.uat-qa.yaml -f compose.override.yaml --profile ai up -d --build
```

This mounts private bundles read-only into Java and stores question receipts in `runtime/obpm-uat/private/qa/answers/`. The worker receives only the selected bundle over the authenticated internal request. Original workbooks and the offline Q&A report are not mounted into the worker. Existing model and database volumes are preserved. Starting with the base Compose file alone leaves this optional workspace disabled.

In VS Code, **Terminal > Run Task > POI: Start UAT Evidence Q&A (local model)** runs the same command. The normal synthetic application task does not enable the private UAT mount.

The optional configuration selects the already installed `qwen3:8b` model for UAT questions through `POI_UAT_MODEL`. The original synthetic case model remains configured separately by `OLLAMA_MODEL`. Without a UAT override, the worker inherits the case model setting. Actual answer provenance identifies the model used; earlier saved answers retain their original model name. The larger model was selected for comparison after the smaller model made repeated unsupported outcome claims; its evaluated results and remaining limits belong in the validation record.

5. Open [the evidence page](http://127.0.0.1:5178/evidences/exports), sign in as the local analyst, select the snapshot and submit a question. Questions can be up to 2,000 characters. Inspect cited records and source notes beside the answer. The history retains prior generated answers and distinguishes an earlier evidence hash.

Useful freeform prompts include:

- What changed between the history observations, and what does that establish?
- Does this evidence prove beneficiary credit? Explain the supporting or missing evidence.
- What does the message status mean, and what can we not infer from it?
- Which correlated fields should we obtain next from the inquiry API?

These are prompts only; their answers are not stored in application code.

## API and storage contract

| Endpoint | Behavior |
| --- | --- |
| `GET /api/uat/snapshots` | Session-tenant snapshot metadata and enabled state |
| `GET /api/uat/snapshots/{id}` | Authorized snapshot with exact document contents |
| `POST /api/uat/snapshots/{id}/questions` | Writer role, CSRF and `{question,evidenceHash}`; genuine model answer or visible error |
| `GET /api/uat/snapshots/{id}/questions` | Up to 50 stored answers for that tenant and snapshot |
| Worker `POST /uat/answer` | Service-key authenticated, bounded question and authorized document bundle |

`evidenceHash` is SHA-256 of recursively key-sorted, compact UTF-8 JSON for the full bundle excluding its `evidenceHash` property; arrays retain order. The Java service recomputes it when loading and checks again after inference. Receipts retain the supplied request, answer, actor, snapshot hash and a separate canonical request hash. They are local files, not a tamper-proof external audit service. No UAT answer changes synthetic case states or performs a bank operation.

## Interpretation and limits

This section describes the retained standalone export Q&A baseline. The visible saved payment-case flow uses [case RAG](CASE_RAG.md) and the [durable investigation queue](CASE_JOBS.md); its background jobs and cancellation behavior are separate from this synchronous export route.

- A cited source identifier is checked against the exact supplied document. This proves citation membership, **not that the generated sentence logically follows from the source**. Model answers require review and can still contain errors.
- Local source notes describe possible code paths, not evidence that a particular payment executed them. Their correspondence with deployed code and configuration must be established separately.
- Unknown or missing evidence remains usable context for a generated explanation. Empty fields and empty exports do not become success/failure facts.
- This workspace consumes four FCR function exports, not a live OBPM API, posted ledger or network confirmation feed. It does not establish a full bank integration, production identity, approved recovery procedure or verified payment outcome.
- The model is local Ollama. UAT graph tracing is disabled. Supplied exports, source notes, snapshots and full answers remain in ignored local directories and are not portfolio fixtures.
- Questions are independent requests over the selected snapshot. Previous answers are shown as history but are not silently injected as conversation context. Ask self-contained questions.
- Provider failures, context overflow, truncated/malformed model JSON, invalid citations and private-storage failures remain errors. No fixed-answer fallback is used.
- CPU inference can take several minutes on the first full export question. The optional Compose file allows a 900-second UAT provider read timeout; the dedicated Java UAT deadline is 930 seconds and the nginx/Vite UAT proxy wait is 960 seconds. The provider connection/write/pool bounds are separately 5/30/5 seconds. These are bounded transport waits, not a speed guarantee. The original case route retains its earlier deadlines. Documents precede the question to improve prefix-cache reuse; changing retrieved document order can still require new prompt processing.
- `POI_UAT_MODEL_KEEP_ALIVE_SECONDS` defaults to 1800 and accepts 0–3600. It asks Ollama to retain the loaded model after a request, reducing repeated loading between nearby questions. It does not cache an answer or guarantee prefix reuse. The model can consume substantial memory while retained. The setting uses the documented [Ollama chat `keep_alive` option](https://docs.ollama.com/api/chat).
- The page displays measured elapsed waiting time and preserves the question after an error. `UAT_MODEL_TIMEOUT` (504), `UAT_MODEL_BUSY` (503), `UAT_MODEL_UNAVAILABLE` (503) and `INVALID_UAT_ANSWER` (502) have distinct guidance. Busy requests make no additional model call. Provider exception bodies are not shown to the browser. Keep the page open during an active request; this remains a synchronous request, not a resumable background job.

Executed validation and the live model/browser results are recorded in [delivery status](STATUS.md), the [dated review](validation/uat-qa-2026-09-13.md) and [machine-readable receipt](validation/uat-qa-2026-09-13.json). Actual inference completed, but factual validation did not pass.

The subsequent [timeout repair and cold-start retry](validation/uat-qa-timeout-fix-2026-09-13.md) records the user-reported missing response separately from the earlier factual failures.
