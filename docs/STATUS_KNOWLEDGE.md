# Private status knowledge for case investigations

An optional private status catalog supplies field-specific reference definitions to saved Payment case investigations. It supplements the selected payment evidence and general tenant guidance. The existing local Qwen3 8B answer model, GPU generation settings, original `/uat/answer` baseline and historical answers remain unchanged. Building an embedding index and retrieving definitions does **not** train or fine-tune model weights.

When `poi.case-investigation.knowledge-index-file` is configured, the [unified case knowledge pipeline](CASE_KNOWLEDGE.md) supersedes the status-only semantic selection described below. It uses this catalog as the source of exact definitions and the overview, embeds all eligible knowledge alongside general guidance, and performs only one unified semantic search. The Knowledge library displays these same current source definitions and their index freshness. The status-only path remains available when unified indexing is disabled.

The catalog is an operator-configured local file. Source workbooks, extracted definitions, vectors and index-generation receipts stay in ignored private runtime storage. They are not public fixtures, payment evidence, a shared global knowledge store or data automatically learned from new transactions. The Knowledge library UI does not currently edit or approve this catalog.

## Scope and selection

Java first authorizes the saved case and evidence version using the existing tenant and bank/branch rules. Catalog eligibility then requires its configured tenant, evidence schema `fcr-case-evidence-v1` and table `PM_NEFT_TXN_LOG`. Entries define only `CODSTATUS`, `ACCTSTATUS` or `MSGSTATUS`. They do not automatically define similarly valued HOST/HISTORY fields, `N10_STATUS`, status lookup tuples or a payment's final outcome.

Catalog scope is tenant/schema/table/field based. It is not a bank/product/release approval registry. The applicability of the reference to a deployed bank release still needs source review and must be stated in the catalog overview.

| Action | Reference selection | Model work |
| --- | --- | --- |
| Read investigation context | Overview and exact field/code definitions observed in PAYMENT rows from `PM_NEFT_TXN_LOG` | None |
| Submit a new question | Exact observed definitions, explicitly named field/code definitions and up to three semantic matches, deduplicated by document ID | One local query embedding, followed later by the existing answer job |
| Read a saved answer or replay an existing idempotent submission | Previously frozen documents and answer | None |

Exact lookup preserves source text. Null, blank, unknown and differently spelled codes do not acquire another code's definition; a known value is not inferred from a nearby numeric value. A question may retrieve a definition that is absent from the payment's rows. That definition remains knowledge, not an assertion that the payment had that status. A similarity score is a retrieval score, not factual confidence.

```mermaid
flowchart TD
    A[Private source reference and source fingerprint] --> B[Reviewed field-specific definitions]
    B --> C[Local GPU embedding index]
    C --> D[Configured private catalog]
    E[Authorized case and immutable evidence] --> F[Exact PAYMENT field and code lookup]
    D --> F
    F --> G[GET context with exact definitions and no inference]
    F --> H[Explicit question submission]
    H --> I[Reserve queue capacity and validate existing source bounds]
    I --> J[Scoped IDs and vectors plus question sent to worker]
    J --> K[Verify embedding model digest and GPU allocation]
    K --> L[Cosine top three plus explicit field mentions]
    L --> M[Union with exact definitions and validate final source bounds]
    M --> N[Freeze original selected documents and retrieval receipt]
    N --> O[Existing case answer job and BM25 ranking]
    K -->|Unavailable or invalid| P[Visible request failure with no fallback]
    M -->|Too large| P
```

## Configuration and index lifecycle

`poi.case-investigation.status-catalog-file` is optional and defaults to blank, which disables status-catalog retrieval while preserving the existing case workflow. Spring deployments may set `POI_CASE_INVESTIGATION_STATUS_CATALOG_FILE` to the absolute private catalog path. The existing `poi.case-investigation.guidance-file` independently supplies general tenant guidance.

The catalog has this structure, with actual definitions and vectors held privately:

```text
schemaVersion: fcr-status-knowledge-v1
tenantId: authorized tenant
evidenceSchema: fcr-case-evidence-v1
table: PM_NEFT_TXN_LOG
sourceSha256: source file SHA-256
embedding: {model, digest, dimensions}
overview: standard knowledge document
entries: [{field, code, label, document, vector}]
```

The supported embedding model is `qwen3-embedding:0.6b`, with 1,024 dimensions and the exact installed model digest. A digest may be lowercase 64-character hex with an optional `sha256:` prefix. The catalog is bounded to 2 MiB and 100 entries. Entry field/code pairs and document IDs must be unique; vectors must have the expected dimensions, finite components within `[-1,1]` and nonzero length. Standard knowledge documents retain their source file, sheet/range or locator. Invalid or unreadable configured catalogs fail visibly.

The private index lifecycle is explicit:

1. Preserve the source and fingerprint it. Review field names, labels, source ranges, applicability and any conflicts with older guidance.
2. Create compact field-specific knowledge documents and an overview that states source provenance and applicability limits. Reference definitions remain separate from payment observations.
3. Generate document vectors locally using the pinned embedding model and record the source/model fingerprints and build receipt. Keep the vectors and source copies private.
4. Validate the candidate catalog, selection behavior and complete case prompt budget before pointing the API at it. Preserve a prior catalog copy for rollback.
5. Rebuild when the reference source, chunking or embedding model changes. Never silently combine vectors from different embedding models. New questions use the current selection; saved investigations retain their original sources.

The request path does not rebuild the index, pull models, learn from saved answers or call the bank. It uses precomputed document vectors and creates only a question embedding on a cache miss. A bounded, model-digest-keyed cache can reuse a previously GPU-verified exact query vector; each request reranks its supplied index. A configured loopback HTTP Ollama address is required for this search; redirects and proxy inheritance are disabled. The worker checks the installed digest on every request. On a cache miss it requests GPU execution, verifies a positive GPU allocation and unloads the embedding model after the attempt; it caches only a successfully verified vector. Cosine ranking is recomputed for the current supplied index on hits and misses. This verification is an execution receipt, not an accuracy guarantee or a claim that every operation occurs exclusively on GPU.

## Submission timing and failure behavior

Semantic selection runs synchronously during the explicit investigation POST, before Java creates the durable job and returns its response, normally HTTP 202. The browser gives this submission 165 seconds; Java bounds the internal search transport to 150 seconds. Worker search has a default 60-second deadline, configurable with `POI_CASE_KNOWLEDGE_TIMEOUT_SECONDS` from 1 to 120 seconds, plus a bounded five-second unload attempt and up to one second waiting for the shared inference lock. These are separate deadlines, not a promised completion time.

Other page reads retain their normal behavior. Selecting a case, evidence version or suggested question does not automatically embed a question or start an answer. If the shared model is busy, the search fails visibly; it does not launch an extra generation or fall back to CPU/lexical search. A timeout, unavailable model, mismatched digest, unverified GPU allocation or malformed search result returns an error without substituted definitions.

Java reserves local queue capacity before expensive selection and releases it on failure. It validates the existing evidence/general-guidance bounds before search and the complete selected bundle afterward. The existing limit of 100 source documents and 50,000 source characters still applies. The worker separately checks the conservative complete prompt budget. No evidence rows are dropped to make space; retrieval may succeed and later generation still fail if the complete prompt is too large.

## Frozen provenance and preservation

Java unions the selected original knowledge documents with the unchanged case evidence. New question inputs include a compact knowledge document `FCR-ENUM-RETRIEVAL`, containing the source fingerprint, embedding model/digest, GPU receipt, exact-observation document IDs, explicit-question document IDs and semantic IDs/scores. The catalog overview supplies the shared interpretation limits.

Those selected documents and the retrieval receipt enter the existing `guidanceHash` and frozen `inputHash`. Returned citations must preserve the supplied documents exactly. The receipt records the selected reference material; it does not freeze the entire vector index or prove a status interpretation. Old jobs and answers are never rebuilt from a newer catalog.

The subsequent `/case/answer` worker still retains all supplied documents and uses BM25 to order supplied guidance. Its `lexical-ranked-all-supplied` answer receipt describes that later stage; the separate frozen status-retrieval document identifies the preceding semantic selection. `model.actualCalls` continues to count answer-generation attempts, including the existing single possible correction. It does not include the earlier embedding request or its duration.

See [CASE_RAG](CASE_RAG.md) for answer validation and its limits, and [API_CONTRACT](API_CONTRACT.md) for the authenticated internal search interface. Current validation/deployment receipts are recorded separately in [STATUS](STATUS.md).
