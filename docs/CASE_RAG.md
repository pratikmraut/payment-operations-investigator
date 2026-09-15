# Local model investigations over saved payment evidence

## Citation identifiers and literal fields, 15 September 2026

The literal-field checker excludes exact token spans of known hyphenated source IDs from native-field parsing. For example, `FCR-ENUM-CODSTATUS-2` names a supplied reference; it does not assert `CODSTATUS=-2`. A live validation exposed this false positive and its unnecessary correction. The fix preserves generated prose and all existing field checks outside those exact known reference spans, including explicit negatives, table-qualified fields, unknown identifiers and incorrect values next to valid citations. Citation names alone never become field support. See [the unified knowledge validation](validation/case-knowledge-library-2026-09-15.md).

The normal `/cases` page now opens saved Payment cases directly. The Demo cases tab has been removed from normal navigation. Analysts find a payment, save its inquiry or uploaded records, select an evidence version and ask the local model a question. The case workbench and `/evidences/questions` use the same jobs and saved answers.

This increment strengthens the case answer pipeline around the existing local Ollama model. The configured Qwen3 8B weights and native Intel GPU runtime remain unchanged. It is a RAG and validation improvement, **not model training or fine-tuning**. No speed or factual-accuracy improvement follows from the code change alone; actual model runs need their own measured and reviewed evidence.

The earlier synthetic investigation implementation, Replay fixtures, direct legacy case URLs and original standalone `/evidences/exports` model demonstration remain preserved outside normal case navigation. The original worker `/uat/answer`, its prompt and saved export answers remain unchanged. New saved Payment case questions use the separate worker `/case/answer` endpoint.

## What the architecture does

The optional [unified case knowledge index and visible Knowledge library](CASE_KNOWLEDGE.md) supersede status-only retrieval when `poi.case-investigation.knowledge-index-file` is configured. One shared source inventory supplies the library and case projection. Every current source document must have a matching embedding hash; missing/stale indexes block new indexed questions without changing saved jobs. Mandatory caveats, exact observed/explicit status definitions and applicable table guidance are retained, then one search over all current knowledge adds up to three matches. The frozen receipt is `CASE-KNOWLEDGE-RETRIEVAL`. With unified indexing disabled, the earlier optional status catalog described below remains compatible.


The private schema-reference update of 15 September 2026 adds a compact, cited review of the user's supplied PAYMENT, HOST, HISTORY and STATUS definitions to new case questions. Full source copies, parsed column/index/comment metadata and all supplied status seed rows remain in ignored `runtime/table-knowledge-2026-09-15/`; only the compact reference enters each prompt. The installed deployment is unverified, seed data never becomes a case evidence row, and disputed comments/duplicate mappings stay qualified. The existing loader enforces tenant/evidence-schema scope, not a verified bank/release knowledge registry. Earlier saved jobs keep their frozen guidance. This is knowledge curation with no model-weight or provider change; private activation receipts record source, input-budget and preservation checks, not new answer accuracy.

An optional private [status knowledge catalog](STATUS_KNOWLEDGE.md) now adds field-specific definitions before the case input is frozen. Java selects exact observed PAYMENT field/code definitions without inference for GET context. An explicitly submitted question also retrieves named field/code definitions and up to three matches from a local GPU embedding search over that tenant's precomputed catalog. The union is bounded and its original documents and compact retrieval receipt are frozen with the job. This supersedes the earlier description of case retrieval as entirely lexical; the separate answer-worker ranking remains BM25 over its supplied bundle. With the catalog disabled, the previous behavior remains available.

Java authorizes the case and freezes the selected immutable evidence version and tenant guidance before creating a durable job. The Python LangGraph answer pipeline receives only that authorized bundle. Its planning step is a deterministic scope check; it does not ask an LLM to choose tools or access other payments.

1. **Check scope.** Require at least one native PAYMENT, HOST, HISTORY or STATUS source row. Browser-provided replacement documents, arbitrary bank URLs and tenant overrides are not accepted by the question API.
2. **Select and rank applicable guidance.** When the private status catalog is configured, Java unions exact observed definitions, explicit field/code mentions and up to three GPU semantic matches before freezing the sources. The answer worker then expands selected question terms and uses BM25 lexical ranking over the supplied knowledge documents. Preserve every evidence row, coverage document, context document and supplied guidance document. There is no unrestricted global vector search or cross-tenant retrieval.
3. **Generate the established answer shape.** Check the conservative context budget, then call the configured Ollama model through LangChain. The model returns only `claims: [{text,evidenceIds}]`, `unknowns` and `nextChecks`. Oversized context fails without dropping rows. Claim wording and follow-up prose come from the model response; it is not asked to generate a separate field-proof structure.
4. **Check the response and allow one correction.** Validate structure, source citations and required unknowns/next checks. Code scans recognized explicit native quotations such as `FIELD=value` or `FIELD value` against the cited native rows and rejects mismatched values. Unparsed prose remains source-cited and is not labelled field-verified. A first validation failure can send bounded feedback with the same source bundle and question. The model generates the complete corrected response; it must pass the same checks. A second invalid response fails. Truncation, provider errors and timeouts fail without retry.
5. **Recheck in Java.** Validate the returned question/evidence identity, preserved source documents, exact joined claim text and case RAG metadata. Recheck any populated literal-field supports against their cited rows, and recheck access and evidence binding before saving the terminal result.
6. **Retain the result.** Save the answer with its authorized worker input and evidence version. Later evidence or questions do not rewrite it. The UI displays claims, citations, any checked literal quotations, unknowns, next checks and saved provenance.

```mermaid
flowchart TD
    A[Find payment and open saved case] --> B[Explicitly collect API or Excel or JSON evidence]
    B --> C[Save immutable evidence version]
    C --> D[Select version and submit question]
    D --> R[Java authorizes case and checks source bounds and queue capacity]
    R --> S{Private status catalog enabled?}
    S -->|Yes| T[Exact definitions plus named fields and scoped GPU semantic top three]
    S -->|No| E[Freeze authorized input in durable job]
    T --> U[Validate full selected bundle and freeze retrieval receipt]
    U --> E
    T -->|Search failure| V[Visible submission error with no fallback]
    U -->|Too large| V
    E --> F[Case worker scope check]
    F --> G[Expand retrieval terms and BM25 rank supplied guidance]
    G --> H[Retain all documents and check context budget]
    H --> I[Structured Ollama generation]
    I --> J[Worker checks citations and recognized literal field quotations]
    J --> K[Java rechecks answer and evidence binding]
    K --> L[Save completed answer and provenance]
    L --> M[Analyst inspects claims beside source records]
    H -->|Too large| N[Visible failed job with no substitute answer]
    I -->|Provider or output failure| N
    J -->|First validation failure| O[Validation feedback with same question and sources]
    O -->|One correction allowed| I
    J -->|Second validation failure| N
    K -->|Invalid response or changed access| N
```

The worker shares the existing inference lock, including optional query embedding search. Java retains its bounded local job executor and idempotency handling. A completed case answer records `model.actualCalls` as 1 or 2, including the single possible model correction. Reported token counts and generation durations include both generation attempts; an unknown token count remains unknown. These answer metrics do not include the preceding query embedding. The frozen `CASE-KNOWLEDGE-RETRIEVAL` document records unified knowledge selection, inventory version, model/digest and matches; the compatible status-only path retains `FCR-ENUM-RETRIEVAL`. The code does not rewrite prose or insert a canned answer. There is no application answer cache or automatic bank inquiry during a question. Reading a saved answer or retrying the same idempotent command reads the original job; it is not another model call or the legacy Replay mode. The provider may maintain its own prompt cache without changing this application behavior. Case-only `POI_CASE_MODEL_KEEP_ALIVE_SECONDS` defaults to 1800 (0–3600); the original export/strict-experiment lifetime stays unchanged. The worker may reuse an exact question's previously GPU-verified vector from its bounded cache while reranking the current supplied index. An uncached embedding can evict Qwen on the one-model native runtime, so model reuse does not promise a faster answer for every new question. See [CASE_KNOWLEDGE](CASE_KNOWLEDGE.md#faster-repeated-questions).

Optional semantic selection occurs synchronously before the investigation POST returns a durable job. The UI submission timeout is 165 seconds and Java's search transport cap is 150 seconds; worker search defaults to 60 seconds plus bounded cleanup, with no automatic question submission on page reads. Search failures remain visible without a substitute answer or implicit lexical fallback. See [STATUS_KNOWLEDGE](STATUS_KNOWLEDGE.md) for the catalog lifecycle, scope, exact limits and timeout configuration.

Each case-model attempt uses half the configured provider timeout: with the current 360-second setting, each attempt has a 180-second provider timeout. Provider errors and timeouts are not retried. Queueing, validation and transport overhead are separate, so this configuration is not a strict total job-duration guarantee. A failed job requires an explicit new submission; the bounded correction happens only within the existing job after its first response fails validation.

## Inspectable support contract

The browser question request remains `{question,evidenceId,evidenceHash}`. Java supplies `{question,snapshotId,evidenceHash,documents}` to authenticated `POST /case/answer`. Each document contains `id`, `kind`, `title`, original `content` and `source` metadata. Returned citations preserve these exact document values, including whether optional source keys were omitted or explicitly null.

The response retains the existing claim shape, `claims[].text` and `claims[].evidenceIds`, with an additional `rag` receipt:

```json
{
  "pipeline": "case-evidence-rag-v1",
  "promptHash": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "checks": [
    "source-membership",
    "literal-field-quotations",
    "required-unknowns-and-next-checks"
  ],
  "claimSupports": [
    {
      "claimIndex": 0,
      "claimType": "observation",
      "fields": [
        {
          "documentId": "PAYMENT-ROW-1",
          "field": "NUMAMOUNT_4038",
          "value": "123.007"
        }
      ]
    }
  ]
}
```

This example illustrates the shape using an original synthetic amount and example hash. Actual responses contain the worker's SHA-256 hash of its system prompt text. It is not a signature, a weight-training receipt or proof that the answer is correct.

New answers contain 1–4 claims, 1–3 unknowns and 1–3 next checks; each prose item is at most 2,000 characters. The first generated claim is prompted to address the question directly. The worker attaches the `rag` receipt in code after checking the original model response. Each claim has one entry at the corresponding zero-based index, with up to six checked fields. The receipt categories do not establish that the complete prose is factually correct:

| Claim type | Enforced support |
| --- | --- |
| `source-cited` | Valid supplied citations, which may be evidence or guidance; fields may be empty and the prose is not field-verified |
| `observation` | At least one cited evidence document and one checked native row quotation |
| `interpretation` | The same raw quotation support plus at least one supplied knowledge citation; this does not verify the interpretation |
| `limitation` | Retained contract category with at least one cited evidence document; fields may be empty |

Populated field supports must point to a cited evidence document named `PAYMENT-ROW-n`, `HOST-ROW-n`, `HISTORY-ROW-n` or `STATUS-ROW-n`. The field must be a top-level textual JSON value in that row, and the value must match exactly. Identifiers, decimal amounts, dates and codes are not coerced to numbers. The claim text must include the exact nonblank value. Duplicate document/field references in one claim fail. CASE-CONTEXT and coverage documents remain available as citations, but are not native row field supports.

The literal checker recognizes a narrow written syntax. A sentence such as "the recorded amount is ..." may have a valid source citation but no recognized native field quotation. It remains `source-cited`; the system does not manufacture a field-proof record or reject ordinary prose merely because it uses a readable alias. The absence of checked fields is not proof that the prose is correct or incorrect.

Java records `answerContract="case-evidence-rag-v1"` on new jobs. Their returned and saved answers must retain the required metadata. Older saved answers remain readable under their original validation contract; they are not retrospectively rewritten or labelled as passing the new field checks.

## What these checks do not establish

An exact field/value citation can still accompany an unsupported conclusion. A model can cite irrelevant guidance or misunderstand a valid status mapping. A knowledge citation establishes source membership, not whether its definition is applicable or logically proves the prose. The literal checker does not parse all natural language or validate every number in an answer. Nonempty unknowns and next checks improve response structure; they do not guarantee useful or complete advice.

The existing experimental-answer warning remains appropriate. Source documents and notes are treated as untrusted data, and the prompt prohibits payment operations, but prompt instructions alone are not a semantic safety proof. The case worker has no payment-execution or recovery tools.

These four FCR result groups do not by themselves establish native OBPM acceptance, posted ledger entries, settlement or beneficiary credit. A status meaning requires the exact field, product and release mapping. Unverified deployment assumptions, missing records, tied timestamps and non-atomic acquisition remain visible limitations. Case hashes bind saved content, not its real-world truth.

## Operator and manager demonstration

Use one approved payment and an evidence version whose source you can explain. Keep employer or customer information within its approved local demonstration scope.

1. Open `/cases`, find the payment within its bank/branch scope and explain the investigation reason. Show that the saved case identity matches the payment.
2. Collect evidence through the available API, four Excel files or Manual + JSON path. Identify the actual source used. A configured endpoint is not evidence that a deployed bank integration has been verified.
3. Show the saved version, exact reference, source time metadata, row counts and missing groups. Explain why row presence or absence is not a payment outcome.
4. Ask a specific question, such as what a named recorded field says or whether the supplied records establish the reported outcome. Submit it explicitly; do not present a saved answer as a fresh live run.
5. Open the generated claim's cited source. Compare its quoted field/value with the original row and review any interpretation against the correct source guidance.
6. Show unknowns and next checks. If the evidence cannot establish beneficiary credit or native OBPM acceptance, demonstrate the missing-evidence conclusion instead of inventing an outcome.
7. Open a second question or another saved evidence version. Show that each job retains its own question, evidence fingerprint, sources and answer after reload.
8. Report the model name, actual call count and measured generation duration from that run, along with any failed or factually incorrect result. Passing HTTP or schema checks is separate from factual review.

The defensible engineering demonstration is authorized evidence collection, immutable versions, local model generation, inspectable citations, bounded checks of literal field quotations and durable question history. No promotion, business-impact, production-readiness or model-accuracy guarantee is implied.

## Preserved structured-support experiment

An earlier case-only experiment asked the model to generate `claimType` and field-support objects as well as its prose. Local model trials showed unreliable support objects, including fields unused in the text and missing expected citations. That experiment and its harness are preserved for inspection, but it is not the default case engine. The default `CaseRagEngine` keeps the already working cited-claim model shape and derives only recognized literal-quotation metadata in code. Neither approach changes model weights or supplies prepared answer prose.

## Verification status

The Java package after initial contract alignment passed **243 tests**, including case response bounds, exact values, immutable job binding and unchanged export validation. The final source-citation/literal-quotation contract and bounded correction passed **33 focused validator and case-service tests**. Worker and browser checks, actual-model evaluation and deployment have separate receipts. Consult [STATUS](STATUS.md) for the final recorded results; this architecture guide does not claim a new measured latency or successful factual assessment before those results are available.
