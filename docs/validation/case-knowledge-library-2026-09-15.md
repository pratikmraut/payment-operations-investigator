# Unified case knowledge and visible library, 15 September 2026

The local `/knowledge` page now reads the same authorized current source inventory used by saved Payment case RAG. The native deployment has a complete unified embedding index. Original payment records, previous answers and the standalone export/model baseline remain preserved.

## Implemented and deployed

- Shared Java source providers and authenticated `GET /api/case-knowledge`, including exact content/source versions and current/stale/missing/disabled embedding coverage.
- Read-only Knowledge library with source inspection, word search, category/coverage filters and ten documents per page. Normal navigation describes case guidance rather than synthetic demo runbooks.
- Canonical-document GPU embeddings for all 90 Northstar documents: three generic safeguards, six curated guidance documents, a workbook scope document and 80 field-specific definitions. Silverline has its three generic documents, with no private Northstar guidance.
- Strict current-index prerequisite for new questions, exact observed-code definitions and one semantic search over all eligible reference vectors. Unchanged raw evidence and frozen source/selection receipts remain part of the normal case workflow.
- Incremental immutable index builder, a bounded query-vector cache and case-only 30-minute model keep-alive. No answer caching, model-weight fine-tuning, automatic bank requests or CPU fallback was added.

## Verification

The full Java package passed **353 tests**. The combined focused worker/index-builder regression passed **242 tests**; a subsequent citation-parser fix added twelve regressions, with all **165 affected answer-pipeline tests** passing on the final code (254 distinct worker/builder checks across these runs). The frontend passed **68 focused tests**, TypeScript and the native Vite build. Java projection and worker prompt checks exercised four preserved evidence versions and three questions each: all twelve initial prompts fit, with at most 26 source documents, 14,159 source characters and 29,879 conservative context units out of 32,768. The 2,000-byte correction-response sizing scenario used at most 32,438 units; the oversized correction probe was correctly rejected before model access. These are bounded sizing checks, not a guarantee that every possible correction fits.

Actual local indexing used `qwen3-embedding:0.6b`, digest `ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d`, with 1,024 dimensions. Ninety unique documents produced 93 tenant-scoped entries in twelve GPU batches in **10.438 seconds**. The 1,195,710-byte index has SHA-256 `bdb0ea6558ce2dffb55ecbc8f200ce9dd460058cd94f758f4493a47e6abeac05`. Provider allocation and runtime logs confirmed GPU use. A compatible unchanged rebuild reused all 93 entries with **zero embedding calls**, completing in 0.060 seconds.

Authenticated live checks returned CURRENT for all 90 Northstar and three Silverline documents. The viewer sees the same authorized Northstar inventory; the second tenant receives only its three generic guides. Source bodies and version hashes match the exported inventory. Nine small author-written retrieval questions returned the expected status/time/outcome/table guide first. Expected labels remained outside the index; this is a retrieval regression sample, not general answer accuracy.

A repeated live search took **0.093 seconds**, following a 4.203-second cold search. Runtime HTTP logs recorded one embedding call for the first search and zero for the repeat; both returned the same freshly ranked scoped matches. Remaining uncached retrieval checks took 2.719–3.094 seconds. These numbers concern reference retrieval only.

Edge checks confirmed the clean route and login restoration, 90/90 current coverage, ten-row pagination, search reset, exact workbook cell/source inspection, empty missing-embedding results and desktop layout. The browser connection became unavailable during the final category-filter check; component tests cover that interaction. No mobile-browser result is claimed. UI bundle: `index-Bi_UExuu.js`; CSS: `index-DuM7niwn.css`.

## Speed and answer-quality limits

A paired comparison using the original frozen case input and unchanged working prompt took 82.312 seconds cold and 37.531 seconds for the same-input warm answer; each used one actual Qwen3 8B call. Provider metadata shows the warm run avoided almost all model loading and prompt prefill. An experimental shorter prompt took 91.031/39.843 seconds and was **not activated**. This demonstrates a conditional warm-cache benefit, not a general latency guarantee.

The unified selection's live answer check initially returned HTTP 422 after its permitted correction. A captured diagnostic exposed a validator false positive: the checker parsed `-2` inside the known source ID `FCR-ENUM-CODSTATUS-2` as a native field value. The final fix skips only exact known hyphenated reference-ID spans. Incorrect adjacent assertions, explicit negatives, table-qualified fields and unknown identifiers retain their existing checks. The exact captured prose passes the repaired validator without rewriting or another model call. Original failed attempts and diagnostic responses remain private; no case answer was saved during verification.

Retrieval success does not establish answer correctness. The native runtime retains one loaded model, so an uncached query embedding can evict Qwen and require another model load. Generated token count, hardware load and a validation correction can dominate response time. The model still needs human factual review, including whether its next checks address genuinely missing evidence.

After deploying the parser fix, the actual case worker accepted a new answer from the real Java unified selection in **42.985 seconds**, using one Qwen3 8B call, 6,177 input tokens, 399 output tokens and 23 original source documents. This was a warm retained GPU runner, not a cold-start benchmark or a saved job. The three inspected field definitions were correct and it declined to infer beneficiary credit from those codes. Its broader comments and next checks still need factual review; validation does not prove those conclusions. No model prose was rewritten to make the test pass.

## Preservation and artifacts

The deployment used a closed-database backup and the tested API artifact. Before/after checks matched **54 saved resources**: ten cases, four evidence versions, six saved investigations, one original export snapshot and three export answers, including management state. Workbook, SQL package, active source guidance/status catalog, original export model code and GPU startup settings were preserved. The added private setting is `poi.case-investigation.knowledge-index-file`.

Private source inventories, index/build receipts, benchmarks, actual Java projection inputs, browser observations and preservation evidence are under ignored `runtime/knowledge-speed-2026-09-15/`. The original files in the user's private bank checkout were not modified. See [the operator workflow](../CASE_KNOWLEDGE.md) for subsequent knowledge updates and index rebuilding.
