# Investigation worker

This service implements the shared contract in `../../docs/API_CONTRACT.md` with FastAPI, actual LangChain tools and an actual LangGraph state graph. It reads authorized snapshots supplied by Java and versioned original runbooks. It never reads the case fixture file or evaluation labels at runtime, changes money, or calls arbitrary URLs/SQL selected by a model.

## Run locally

Python 3.12+ is required. Create a virtual environment and install `requirements.txt`; the file pins the complete dependency resolution tested on this project. The top-level direct dependencies are also pinned in `pyproject.toml`.

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
$env:POI_SERVICE_KEY = 'poi-local-service-key'
$env:OLLAMA_BASE_URL = 'http://127.0.0.1:11438'
$env:OLLAMA_MODEL = 'qwen3:4b-instruct'
$env:OLLAMA_EMBED_MODEL = 'nomic-embed-text:v1.5'
$env:POI_RETRIEVAL_MODE = 'lexical'
.\.venv\Scripts\python.exe -m uvicorn investigator.main:app --host 127.0.0.1 --port 8091 --workers 1
```

The default key above is a local synthetic-demo value, not a production secret. With no `POI_SERVICE_KEY`, protected routes fail closed. `GET /health` remains available and explicitly does not assert model availability. Java authenticates users and derives their tenant before invoking the worker with its service key. Do not expose this service directly to end users.

Configuration:

| Environment variable | Meaning/default |
|---|---|
| `POI_SERVICE_KEY` | Required shared service key for protected routes |
| `POI_KNOWLEDGE_PATH` | Defaults to project `data/knowledge/runbooks.json` |
| `POI_CHECKPOINT_PATH` | Defaults to service `runtime/checkpoints.sqlite` |
| `POI_RETRIEVAL_MODE` | `lexical` default, or `hybrid` |
| `POI_VECTOR_DB_URL` | Optional psycopg PostgreSQL URI; enables persistent pgvector for hybrid retrieval |
| `POI_VECTOR_NAMESPACE` | Isolated vector corpus namespace; defaults to `payment-runbooks` |
| `OLLAMA_BASE_URL` | Defaults to `http://127.0.0.1:11434`; project container uses host port 11438 |
| `OLLAMA_MODEL` | Configurable tool-capable local model; example above is project selection |
| `OLLAMA_EMBED_MODEL` | Configurable actual embedding model |
| `POI_MODEL_TIMEOUT_SECONDS` | 180-second provider HTTP timeout; inference requests explicitly disable streaming |
| `POI_MODEL_CONTEXT_TOKENS` | 4096-token model context |
| `POI_MODEL_THREADS` | Explicit model CPU threads, default 4; integer 1–32 |
| `POI_TOOL_OUTPUT_TOKENS` | Tool-selection generation cap: 384 tokens |
| `POI_SYNTHESIS_OUTPUT_TOKENS` | Fact-ID selection generation cap: 384 tokens; unused for insufficient evidence |

Docker builds from this directory. Mount the runbook JSON separately and set `POI_KNOWLEDGE_PATH`; mount `/app/runtime` for durability. Use one worker process for this SQLite local deployment.

## Original synthetic OBPM NEFT slice

`domain=OBPM_NEFT` uses the typed `obpm-evidence-v1` snapshot defined in `../../docs/OBPM_IMPLEMENTATION_CONTRACT.md`. It is an original synthetic outbound NEFT/14.7 ECA path, with no Oracle connection. Four actual LangChain tools read the authorized payment identity, queue records, ECA request attempts and source coverage. The same five-node LangGraph and SQLite checkpoint flow dispatch to separate OBPM evidence rules and a separate service fact catalog. Legacy snapshots retain their serialized checkpoint shape.

One uniquely current EC/T record, a matching ECA attempt, consistent times and complete declared queue coverage establish `OBPM_ECA_TIMEOUT` only. Unknown codes, multiple current records, missing correlation, incomplete coverage and conflicting timestamps yield `INSUFFICIENT_EVIDENCE`. A newer current EC/P does not inherit a historical EC/T conclusion. Both currently propose `REQUEST_EVIDENCE`; an explicit validation gate prohibits all OBPM `RESOLVE_CASE` proposals. This slice cannot establish funds availability, block/posting outcome, beneficiary credit or settlement. Nonempty messages/accounting groups are rejected as unsupported rather than interpreted as legacy webhooks or ledger entries.

Place `obpm-runbooks.json` beside the configured `runbooks.json` (mount the knowledge directory for both). OBPM retrieval requires domain `OBPM_NEFT`, rail `NEFT`, direction `OUTBOUND`, release family `14.7`, authorized tenant and effective policy date before lexical or embedding ranking. Generic retrieval excludes these scoped policies. Persistent vector searches receive only the already-scoped document identities, whose hashes also include applicability metadata. `/retrieve` accepts the four scope fields together; incomplete or unsupported combinations fail validation.

Replay uses zero chat calls. In the OBPM branch, Ollama returns a typed permutation of all four mandatory evidence tool names; the service attaches the authorized case ID and invokes the actual LangChain tools in that model-selected order. Invalid, duplicate, incomplete or unknown names fail visibly without adding omitted tools, retrying or switching modes. The model then selects the authorized timeout fact ID when the evidence gate passes; insufficient evidence skips that second call. Wording and record/policy links remain service-owned. Metrics `toolPlanningScope=mandatory-evidence-order` and `orderedToolNames` distinguish mandatory collection with model-planned order from the generic optional tool-selection path. This is not autonomous diagnosis or banking execution.

The earlier live optional planner omitted payment identity while selecting the other three groups; it stopped normally within its token budget, so rules returned insufficient evidence. Mandatory typed ordering addresses that specific omission opportunity. `tests/test_obpm.py` preserves this captured missing-name regression, validates an alternate order without sorting/repair, and covers the three original samples, structural rejection, correlation/cutoff/current-record controls, policy filtering, authorization and the real LangChain adapter with a fake HTTP transport. That adapter test is not live-model validation; actual Ollama/Java probes are recorded separately.

```mermaid
flowchart LR
  A[Java-authorized snapshot] --> B{Domain}
  B -->|Legacy| C[Existing payment tools and rules]
  B -->|OBPM_NEFT| D[Typed payment, queue, ECA and coverage tools]
  D --> E[Current EC/T correlation and completeness gates]
  E --> F[NEFT outbound 14.7 tenant/date policy retrieval]
  F --> G[Replay facts or live authorized fact-ID selection]
  G --> H[Evidence request for independent case review]
```

## Modes and graph

```mermaid
flowchart LR
  A[Authorized immutable snapshot] --> B{Requested mode}
  B -->|replay| C[Select four diagnostic tools in code]
  B -->|ollama| D[ChatOllama selects 1-4 typed tools]
  C --> E[Execute snapshot-scoped LangChain tools]
  D --> E
  E --> F[Deterministic evidence assessment]
  F --> G[Retrieve tenant/date-eligible policies]
  G --> H{Requested mode}
  H -->|replay| I[Deterministic explanation]
  H -->|ollama| J[Build service fact catalog]
  J --> N[Model selects 1-2 fact IDs or skips for insufficient evidence]
  N --> O[Render selected catalog text and links verbatim]
  I --> K[Validate evidence, citations, conclusion and provenance]
  O --> K
  K --> L[Immutable proposal for Java reviewer workflow]
  P[(SQLite checkpoint per graph step)] -.-> E
  P -.-> G
  P -.-> K
```

The compiled graph still has five nodes: select tools, collect evidence, retrieve policy, synthesize/select facts, validate evidence. Replay is explicit and deterministic, with zero LLM calls. Titles, descriptions, scenario IDs and hidden answer labels do not classify cases. Ollama mode makes one actual LangChain `ChatOllama` call for bounded typed-tool selection, then a second call that returns only `FactSelection { factIds: [...] }` when evidence supports an established assessment. It selects one or two distinct IDs from a service-owned catalog; it cannot return prose, operational links, policy links or decision fields. Unknown/duplicate IDs and extra fields are rejected without repair, retries or fallback.

The service renders only the selected catalog sentences, verbatim, and supplies their complete available evidence links. It adds no unselected sentence. Summary, outcome, confidence, missing evidence and proposal action/reason remain rule-owned. Categorical confidence expresses the rules' established/insufficient state, not a model probability. This contract is **AI-selected evidence, service-rendered facts**, rather than a claim that the model wrote a grounded explanation. Catalog correctness, source completeness, selected-fact relevance and omission of useful facts still need review.

Insufficient evidence keeps the actionable rule-owned requests, returns no finding and intentionally skips fact selection. One real planner call is recorded. All five graph nodes execute. Metrics include `assessmentSource="deterministic-evidence-rules"` and `synthesisScope="fact-selection"`, `"skipped-insufficient-evidence"` or `"deterministic-replay"`. Fact-selected results also record `findingSource="service-rendered-facts"`, `factCatalogVersion`, `factCatalogHash`, `selectedFactIds` and `selectedFactProvenance` including `sourceTools` and links. The full catalog and original selection are checkpointed. Historical `finding-only` results retain their original model-prose provenance and text; they are not relabelled.

Catalog text comes from typed observed fields, not case titles, record summaries, policy instructions or an assessment's narrative. Provider status uses **as of** observation time; success/terminal events use their own occurrence time. Event occurrence and webhook receipt stay separate; a receipt timestamp is not a processing timestamp. Money uses exact integer formatting and names capture, ledger net, provider payout and discrepancy separately. Refund confirmation is distinct from entries present in the supplied ledger snapshot. Generic timeout observations do not claim transport causation; cancellation is rendered as its observed controlled status, not rejection.

Aggregate and absence facts identify the source tool and are explicitly limited to the supplied snapshot. No fictitious evidence ID is created for an absent ledger entry. Actual witnessing IDs and full available ledger membership are retained; a provider link alone does not prove ledger absence. The immutable `compare_settlement` output supplies that membership evidence. Policy links identify applicable guidance; valid tenant/date/version IDs alone do not prove relevance or factual entailment.

The planner and fact selector receive the question as untrusted data. The selector receives the bounded catalog and the actually supplied top policy, with an ID-only schema. Injected text cannot write displayed facts or grant tools, but could still influence which allowed facts are selected. This is not a prompt-injection immunity claim. The final evidence/citation, money/conclusion and narrow workflow-prose gates remain in place. The public Finding shape is unchanged (`F-1`, text, operational IDs, one policy link). Legacy V5 context/grammar helper functions remain only for historical contract regression/inspection; the V6 provider does not use them.

The four tools are `get_payment_timeline`, `compare_settlement`, `inspect_webhooks` and `check_refund`. Each accepts only the exact authorized `caseId`. Java-derived `case.reconciliation` controls arithmetic when `calculatedBy="java-api"`; isolated fixture tests clearly identify worker baseline arithmetic. Its capture/refund totals, capture count, ledger net, currency, provider availability/payout and discrepancy must also agree with the supplied immutable records. Contradictions retain the original reconciliation for audit, mark money invalid, omit affected catalog money facts and request corrected authoritative evidence; the worker does not substitute local totals. Java and worker normalize case plus periods/hyphens to underscores: positive captures include CAPTURE/PAYMENT_CAPTURED/SALE, negative refunds include REFUND/REFUND_POSTED, and FEE must be non-positive. Generic signed adjustments remain supported. Empty/whitespace, UNKNOWN and UNAVAILABLE provider status cannot supply a usable payout, even if a numeric field is present. Receipt inversion diagnostics compare only strictly earlier receipt groups. Tied receipt instants cannot imply order; a maximum-occurrence witness catches nonadjacent inversions with at most one pair per record, avoiding quadratic output. Successful duplicate resolution additionally requires exactly one APPLIED delivery and all others IGNORED_DUPLICATE; each earlier-occurring late event in an ordering inversion must be IGNORED_STALE. Pending/applied/unknown handling requests evidence. Generic timeout summaries describe a timeout observation without asserting transport causation.

UNKNOWN/UNAVAILABLE zero-candidate cases request a timestamped authoritative status, plus ledger-coverage or delivery-history checks only when the relevant supplied lists are empty. They do not assert that records exist. Earlier conflicting-evidence and terminal-status handling, insufficient retrieval query and conservative action remain unchanged.

Provider requests use `think:false`, `stream:false`, four threads, 4096 context and independently bounded output. The actual HTTP adapter is tested through a controlled transport. The configured provider timeout is 180 seconds, Java deadline 390 and proxy timeout 420; earlier evidence used 120/270/300. Provider read timeout is not strict whole-job or server-compute cancellation. A serialized graph request can also queue. Metrics retain successful-stage durations/tokens and configured limits; transport/parse failures can leave attempted usage unreported. A rejected ID selection records its completed selection stage and resumes validation without another provider call.

Java owns user/tenant authorization, reviewer decisions and durable case changes. Repeated identical investigations return the stored immutable result; changed input under the same tenant/investigation ID is rejected. Completed historical results are returned unchanged. A failed legacy checkpoint already containing model prose requires a new investigation ID under the fact-selection contract; checkpoints before synthesis can enter the new node and record its new provenance. Fresh IDs are used for measured acceptance. Full input fingerprints include tenant, case snapshot, question, actor and mode. SQLite writes remain serialized for this local deployment.

## Retrieval and citations

The initial store keeps small runbooks as LangChain `Document` objects. Documents are filtered by tenant and effective date before either text is scored or embeddings are created. `effectiveTo` is exclusive. Every returned citation carries the immutable document/version ID, title, excerpt, source and retrieval score.

Lexical mode is token overlap weighted by inverse document frequency. It is honestly identified as a lexical baseline, not BM25 or semantic search. Hybrid mode invokes actual `OllamaEmbeddings` and combines cosine ranking with lexical ranking using reciprocal-rank fusion. Without a database URL it caches vectors in memory. With `POI_VECTOR_DB_URL`, it persists real vectors and provenance in `poi_vector_documents`: model name, dimension, content hash, document metadata hash, version, tenant, validity dates and index timestamp. Permission/validity changes invalidate cached document keys. SQL filters namespace/model/dimension/tenant/date and the current authorized document keys before ordering/limiting candidates. An obsolete or removed source cannot appear just because an old embedding remains stored. Physical retention/purging is a separate future task.

Database initialization installs the vector extension separately. The worker uses the restricted `poi_vectors` role with its `poi_knowledge,public` search path and creates only its own vector table and scope B-tree index. It does not install extensions or require API-table permissions. This uses exact vector distance, suitable for the small corpus; it does not claim an approximate HNSW index. Database and embedding failures are explicit; hybrid never silently falls back to lexical. Current limitations: one document per short runbook, no reranker, local serial worker and no automatic physical cleanup of old indexed versions.

```powershell
$env:POI_RETRIEVAL_MODE = 'hybrid'
$env:POI_VECTOR_DB_URL = 'postgresql://poi_vectors:poi-vector-local-only@127.0.0.1:5438/poi'
$env:POI_TEST_VECTOR_DB_URL = $env:POI_VECTOR_DB_URL
.\.venv\Scripts\python.exe -m pytest --basetemp=runtime/pytest-vector -m integration
```

The source/version IDs and authorization of citations are validated. Live natural-language entailment is not fully machine-verified; analyst review remains necessary. Malicious document instructions are treated as data, and cannot grant tools or alter backend authority.

## Tests and current verification

```powershell
New-Item -ItemType Directory -Force runtime | Out-Null
.\.venv\Scripts\python.exe -m pytest --basetemp=runtime/pytest-local
```

The explicit workspace temp directory avoids this host's existing Windows pytest-temp ACL issue. Pytest owns and clears only that test directory. Tests cover record-derived classifications, missing/conflicting facts, exact tool argument scope, tenant/date boundaries, immutable identity, persistence across restart, prompt-like document input, forged evidence/citations, service authorization, and a real unavailable Ollama endpoint with no fallback. Test embedding doubles check routing/filter order; they are not evidence of live embedding quality.

Current prompt-only V6b validation on 2026-09-12: **198 tests passed with real restricted-role PostgreSQL enabled**, with one third-party Starlette deprecation warning. The database test uses tiny fixed vectors to verify persistent storage and tenant/date/source/model/dimension filters; it does not invoke an embedding model. Actual LangChain/Ollama serialization is exercised with synthetic HTTP responses for ID-only fact selection, original options and one-call insufficiency. Tests cover exact catalog rendering, full link/source-tool provenance, status-as-of versus event time, cancellation/timeout wording, refund presence, precise money, injection/extra-field/foreign-ID rejection, strict receipt ties/offsets/nonadjacent witnesses, complete duplicate/stale handling requirements, historical result immutability and failed-selection checkpoint resume without regeneration. Legacy observation/grammar regressions remain clearly historical. These are component/contract checks, not successful live inference or proof of selection usefulness. Exact JUnit is `runtime/pytest-verified.xml`; the current receipt belongs in `docs/validation/worker-tests.json`. The first V6 six-family sample accepted six requests with eleven calls and exact service rendering, but three generic selections omitted available exception observations. Its unchanged review remains in `docs/validation/v6-fact-selection-review.md`. V6b changes only the selector prompt to prioritize the observation discussed by the supplied policy, then optional corroboration. Its controlled six-case repeat accepted all six requests with eleven calls: five selected findings now include a distinguishing exception observation and one result is planner-only. Requests excluding UUID, tool outputs, catalogs and structured selector input matched V6. Exact service text/links matched, and Codex found no material factual error in the selected fixed facts; this is an AI review of previously inspected synthetic development cases, not a held-out quality estimate. Capture/settlement or ignored-stale details can still remain in the rule summary/tool trace rather than a selected snippet. See `docs/validation/v6b-fact-selection-review.md` and its exact JSON receipt.

Six selected development fixtures (one per supported exception family) also ran through the actual graph with expected dispositions. Refund output uses exact integer-based human currency formatting and retains both request and confirmation proof; a request alone cannot establish missing refund completion. Conflicting authoritative terminal processor success/failure observations cause abstention, while normal preterminal and transport events do not create false contradictions. These checks concern synthetic examples, not real-world AI accuracy.

Actual Ollama embedding development probes used `nomic-embed-text:v1.5` (768 dimensions, model digest `0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f`) on the 12 currently eligible Northstar runbooks. Four selected queries retrieved the expected timeout, duplicate, ordering and refund policies at rank one, with both in-memory hybrid retrieval and persistent pgvector retrieval. The persistent probe connected as `poi_vectors` and stored in `poi_knowledge`; a fresh store reused the existing document vectors with zero document re-embeddings. Namespace/model/dimension/tenant/date/current-source-key filters were separately verified against real PostgreSQL. The first PostgreSQL indexing/query took 6257 ms, subsequent selected queries 268–451 ms on this development host. These are small measured probes, not a retrieval accuracy or throughput benchmark.

An actual graph investigation using real embeddings and persistent pgvector returned `MISSING_REFUND` / `ESCALATE` for selected fixture CASE-1031 in 558 ms. It used replay synthesis (zero generative model calls); it is evidence for the hybrid retrieval path, not live generative inference. Probe records are `runtime/hybrid-inmemory-verification.json`, `runtime/hybrid-pgvector-verification.json` and `runtime/hybrid-pgvector-investigation.json`; published copies belong under the project validation documentation.

The historical 131-test v5 image also completed a selected two-case development run with `qwen3:4b-instruct`, lexical retrieval, four threads, 4096 context and the 180-second provider timeout. Both responses were accepted (four successful model calls); Codex found no material factual error in these two findings. Ordering still used compressed receipt wording, and the refund finding had a minor grammatical defect. Graph durations were 73.187 and 60.433 seconds. This is a diagnostic pair chosen after earlier errors, not general accuracy evidence. The exact unchanged outputs and limits are in `docs/validation/v5-selected-explanation-review.json` and `.md`. Its subsequent six-family 4B run accepted all six requests with eleven calls, but one of five model findings had material delivery/event-time ambiguity. The broader 8B run accepted five requests and rejected one missing named link; one of four accepted model findings conflated provider snapshot time with success occurrence. Those results motivated the explicit V6 contract change, not a default move to 8B. See `docs/validation/v5-model-explanation-review.md` and `docs/validation/v5-8b-model-explanation-review.md`.

Resolution requires a current provider payout and known zero ledger-net-minus-payout discrepancy. Java reconciliation controls that decision when supplied; standalone synthetic probes explicitly use signed worker baseline arithmetic. A mismatch yields INSUFFICIENT_EVIDENCE / REQUEST_EVIDENCE with exact currency amounts and linked ledger/provider records. This gate applies to otherwise resolvable timeout, duplicate and ordering cases, preserving confirmed missing-refund escalation. A selected payout mutation exposing an INR 33.84 discrepancy now abstains; see `runtime/payout-gate-verification.json`.

The Linux worker image previously passed a disposable network-disabled container smoke test as uid 1000: health, service authentication, four actual diagnostic tools, conservative insufficient-evidence outcome and immutable checkpoint reuse. Full deployment/workflow evidence is recorded separately from unit and container smoke tests.

## Historical development evidence

These observations describe earlier revisions; their failures or then-pending checks are not current acceptance claims. Exact rejected output and timing remain in the linked project validation receipts. No candidate was repaired or relabelled as accepted.

| Revision/probe | Observed result and limitation | Project validation evidence |
| --- | --- | --- |
| Early Qwen checkpoint resume | Schema-valid result after 98.09 s tool selection and 545.62 s synthesis; 751.23 s includes failure/manual gap. Review found an undeclared ledger link now rejected by the validator. | `docs/validation/live-qwen-resume.json` |
| First compact nonstreaming run | Both provider stages completed but inconsistent confidence and empty findings caused rejection; no accepted result. | `docs/validation/live-compose-failed-probe.json` |
| Request-level thread pair | One ordered 48-token probe measured 1.4448 tokens/s with eight threads and 14.399 with four. It motivated explicit four-thread testing, not a general speedup claim; no global hardware settings changed. | `docs/validation/model-thread-probe.json` |
| First four-thread full case | Completed both calls in about 57 s, then rejected swapped operational/policy IDs. Scoped schema enums followed. | `docs/validation/live-compose-four-thread-failed.json` |
| Scoped-ID full acceptance and first six-family sample | Full acceptance passed 17 checks in 198.252 s. Five of six development responses were accepted under the old 120-second provider limit; one timed out, with failed usage unknown. Separate review found workflow overclaims. | `docs/validation/model-explanation-review.md`, `docs/validation/live-acceptance-results.json` |
| 96-test v2 | The 180-second timeout/phrase guard revision accepted one case then conservatively rejected ambiguous unqualified no-action wording on the second. No successful v2 acceptance report exists. | `docs/validation/live-acceptance-v2-results.json` |
| 108-test v3 finding-only | First call rejected “no further action required” despite completed provider stages. Model context still included assessment/question wording; its influence was plausible, not proven. No stored investigation. | `docs/validation/v3-live-acceptance-results.json` |
| 112-test v4 observed context | Full acceptance passed 17 checks in 100.801 s. Six-family delivery/rule checks passed with 11 calls, but two of five model findings had material timing/refund-posting errors; one row had only a planner call. | `docs/validation/v4-live-acceptance-results.json`, `docs/validation/v4-development-explanation-review.json`, `docs/validation/v4-model-explanation-review.md` |
| Controlled 8B on unchanged v4 context | Two selected cases accepted/four calls; refund improved, ordering retained material ambiguity. Requests except UUID and reconstructed contexts matched the prior 4B run. No default-model change was justified by this pair. | `docs/validation/8b-selected-explanation-review.json`, `docs/validation/8b-selected-explanation-review.md` |

The historical V5 revision separately projected event occurrence and webhook receipt, exposes refund ledger-entry presence within the supplied snapshot, and makes unknown-status requests actionable. V6 now replaces model-written prose with ID selection and exact service rendering as described above. Final validators, no fallback and the source default `qwen3:4b-instruct` remain. Neither valid fact selection nor deterministic rendering guarantees source accuracy or useful coverage.

Complete 112-test source, tests, dependencies, README, original JUnit XML and receipt were preserved before editing in `runtime/history-worker112-pre-v5`, with published receipt `docs/validation/worker-tests-112-pre-timing-context.json`. Complete 108-test evidence remains in `runtime/history-worker108-20260912` and `docs/validation/worker-tests-108-pre-observed-context.json`. Historical 96-test runtime source and requirements remain in `runtime/history-worker96-20260912`, but its original JUnit XML had been overwritten and was not reconstructed. Historical failures and overclaims remain visible.

Complete 131-test source/tests/dependencies/README/original JUnit and receipt were preserved before V6 in `runtime/history-worker131-pre-v6`; published receipt: `docs/validation/worker-tests-131-pre-fact-selection.json`. Original V5 prose, failures and review artifacts remain unchanged.

The intermediate 167-test V6 receipt, original XML and complete worker source/tests/dependencies were archived before the cross-language money correction in `runtime/history-worker167-pre-money-boundary`; published receipt: `docs/validation/worker-tests-167-pre-money-boundary.json`. That image was built but never deployed.

The first V6 198-test source, original XML, receipt, runtime identity and six-case review were preserved in `runtime/history-worker198-pre-selection-priority`; published receipt: `docs/validation/worker-tests-198-pre-selection-priority.json`. The V6b source change is limited to the selector SystemMessage.

V6b also completed the 17-check Java workflow in 59.869 seconds with two actual investigations/four model calls, independent approval persistence, idempotency and denied self-review. Exact stored results and separate factual review are in `docs/validation/v6b-live-acceptance-results.json`. A fresh Java UNKNOWN-status probe used one actual planner call and no finding; a separate Java hybrid refund probe used two generative calls, real hybrid retrieval and the relevant refund policy. Their exact checkpoints, Java-authoritative money and narrow review limits are in `docs/validation/v6b-live-planner-hybrid-review.json`. Embedding requests are not included in the generative-call count. These probes did not authorize any payment or ledger mutation.
