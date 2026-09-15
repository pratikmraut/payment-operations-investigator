# Status knowledge validation — 15 September 2026

The optional private catalog is active in the native local deployment. Private sources, extracted rows, vectors, training candidates and detailed receipts are in ignored `runtime/status-enumeration-2026-09-15/`. No workbook contents were added to public fixtures or sent to a remote inference provider. The supplied workbook and FLEXCUBE/SQL source were not modified or executed.

## Source and retrieval

- Verified 80 field/code/label pairs through both spreadsheet extraction and direct OOXML comparison; exact source spellings and cell ranges are retained.
- Locally generated 80 vectors with 1,024 dimensions using `qwen3-embedding:0.6b`, digest `ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d`.
- Eight index batches took 10.469 seconds after model download. Ollama reported equal total/GPU allocation of 1,228,847,185 bytes and logs showed 29/29 embedding layers offloaded to GPU.
- Six small, author-written retrieval questions found the expected definition in the first position. These checks are separate from active knowledge and do not measure general investigation accuracy.
- Compact private guidance reconciles the preferred PAYMENT definitions with older, separately qualified HOST/HISTORY source facts. All entry documents/vectors remained identical during the final shared-overview compaction; audit copies and fingerprints are retained.

## Code and prompt checks

The Java package passed 340 tests, including catalog validation, table/field isolation, unknown/null handling, forged semantic results, fixed internal transport, immutable source selection, idempotent replay, authorization and queue-capacity handling. An initial sandbox build could not resolve its compiled classes; the clean build with normal local permissions passed using the existing project JDK socket directory.

The focused worker suite passed 193 tests; these use mocked providers. The focused case-workbench suite passed 45 tests, including submission beyond the old 30-second deadline, bounded abort/same-key retry and unchanged read timing. TypeScript and the native frontend build passed. No page layout was changed.

Offline sizing reconstructed the actual case-worker messages and output schema for four saved evidence contexts, 11 questions and initial/correction attempts: 88/88 checks passed, maximum 32,623 against the existing 32,768 limit. The sizing allowance includes a 2,000-character ASCII question, 2,000-byte invalid response and 500-byte feedback. Unicode/escape-heavy content, larger feedback, more distinct explicit codes or additional source rows can exceed it; the runtime retains its visible failure guard and does not truncate evidence.

## Deployed checks and limits

Authenticated GETs verified all four evidence contexts contain exact applicable workbook documents and new guidance hashes while retaining the original evidence documents/fingerprints. GET creates no query embedding or retrieval receipt. The deployed internal semantic-search endpoint returned the expected model/digest with GPU allocation verified in 3.469 seconds.

A direct deployed `/case/answer` call used a current GET context plus the deployed semantic result. It generated one answer with three correctly cited field definitions and an explicit statement that those codes alone do not confirm beneficiary credit. The generation receipt reports one call, 6,415 prompt tokens, 283 completion tokens and 68.687 seconds. The wall-clock worker request took 68.719 seconds. Required structure, citations and literal-field checks passed. The definitions and non-credit conclusion were inspected against the workbook.

This model check did not create a durable case job; Java job freezing and replay were covered by tests. It is not a general factual-accuracy evaluation, live bank integration test or measurement of every possible question. Generation retains the existing experimental-answer limitations and baseline implementation.

After restart, all 54 captured API resources matched their before-state: 10 cases, four evidence versions, six saved investigations, one export snapshot, three export answers and the case-management records. Source workbook, package and protected model code remained unchanged. Existing private application properties are byte-for-byte preserved with one appended catalog property. No bank calls or case writes occurred.

Deployed API SHA-256: `a9c17d3ddd7f7ad295da8997098f3cc3093715b28ae940f9fb845382bb642d6a`. Frontend assets: `index-gwPJz3y2.js`, `index-CMNliQ1j.css`.

No training run or weight change occurred. Eighty mechanically prepared definition Q/A candidates remain `PENDING`, outside the active RAG catalog. A reviewed investigation corpus and separately verified training runtime are prerequisites for a meaningful future fine-tuning experiment. See the private `TRAINING.md`; the existing model remains the working baseline.
