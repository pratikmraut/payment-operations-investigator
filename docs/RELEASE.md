# Local portfolio release criteria

All application gates below have executed on the recorded local synthetic-data project. [Final runtime](validation/runtime-final.json) identifies five running services with Qwen3 4B and lexical retrieval. Model selection, deterministic rules, catalog rendering and integration are separate evidence types; this is not a production-readiness or AI-accuracy certification.

## Executed release gates

| Gate | Completed evidence | Scope |
| --- | --- | --- |
| Original data and comparable baseline | [Dataset](DATASET.md), [baseline](validation/baselines-test.json) | 60 original cases, two tenants; 30 held-out template variants. Retrieval adds policy context, not demonstrated classification uplift. |
| Component boundaries | [Java35](validation/java-container-tests.json), [worker198](validation/worker-tests.json), [UI34](validation/frontend-tests.json) | Synthetic tests plus real restricted PostgreSQL; source/configuration are recorded. |
| Deterministic workflow and regressions | [HTTP17](validation/acceptance-compose-replay-v6b.json), [adversarial16](validation/adversarial-replay-v6b.json), [replay30](validation/evaluation-replay-test-v6b.json) | All passed; zero model calls. Three observed delivery-state defects were corrected. |
| Actual model selection | [Six requests/11 calls](validation/evaluation-ollama-development-v6b.json), [paired exact review](validation/v6b-fact-selection-review.md) | Five selections/nine fact IDs all include distinguishing exceptions, versus two prior V6 selections. Reused development cases, zero model-written findings. |
| Model-enabled Java and independent review | [HTTP17](validation/acceptance-compose-ollama-v6b.json), [exact review](validation/v6b-live-acceptance-results.json) | 59.869 seconds, two investigations/four actual calls. First-case metrics are not whole-run usage. |
| Planner-only uncertainty | [Java probe](validation/live-planner-only-java-v6b.json), [exact review](validation/v6b-live-planner-hybrid-review.json) | 10.896 seconds, one actual call, no findings, three specific rule-owned evidence requests. |
| Combined live model/hybrid retrieval | [Java probe](validation/live-hybrid-java-v6b.json), [review](validation/v6b-live-planner-hybrid-review.json), [provider requests](validation/hybrid-provider-requests-v6b.json) | 37.044 seconds/two generative calls. Serial-window logs corroborate two chat plus one embedding request without a distributed request ID; embedding is excluded from modelCalls. |
| Real browser journey and provenance | [14 browser checks](validation/frontend-browser-v6b.json), [actual screenshots](screenshots/README.md) | Planner/selection labels, source/policy links, required note, independent escalation, audit and unchanged exported evidence. Bounded viewport inspection. |
| Durability and dependency recovery | [H2](validation/persistence-h2-final.json), [PostgreSQL](validation/persistence-postgres.json), [seven outage checks](validation/faults-compose.json) | Actual retained-store restarts and scoped recovery on their recorded versions, not HA or backup/restore. |
| Cached startup and fresh-data reproducibility | [WithAI startup](validation/start-with-ai-v6b.json), [rehearsal](validation/rehearsal-compose-v6b.json), [paired HTTP17](validation/acceptance-clean-compose-v6b.json) | 21.181-second cached-model start; 27.11-second fresh-volume rehearsal with all generated resources removed. No first-download or inference claim. |
| Final runtime | [Runtime-final](validation/runtime-final.json) | Five services, lexical 4B and ten matching worker module hashes. [Final provenance](validation/final-build-provenance.json) verifies source/modules/JS and tested Java JAR bytes. Whole-image equivalence is unavailable and not claimed. |

- [x] Component, replay, live Java, selection, planner-only and hybrid results have exact receipts and limitations.
- [x] Browser, cached startup, isolated fresh-volume cleanup and final runtime are recorded.
- [x] Historical failures remain unchanged and final claims are separated from earlier revisions.

## Main editable delivery

Open the existing project folder through `Payment Operations Investigator.code-workspace`; [VS Code instructions](VSCODE.md) describe the source layout and tasks. This uncompressed folder is the main project.

The prior final ZIP passed 25 independent integrity checks covering 238 source files and its manifest. All 238 working files matched that manifest before the editor/documentation additions. At the user's request, the three redundant top-level ZIP copies and generated packaging probe were removed. [Original package records](validation/package-history/README.md) remain unchanged as historical evidence, including their old filenames/hashes; those paths do not imply the deleted archives still exist.

[Source-folder delivery](validation/source-folder-delivery.json) and [cleanup evidence](validation/package-cleanup.json) identify the retained source and new editor files. Historical archive evidence does not validate additions made afterward. Application source, live databases, downloaded models and existing service configuration were preserved.

The [packaging tool](../tools/package_release.py) now requires reviewed, clean Git-tracked source, applies Git ignores and private-file exclusions, and scans bounded source/workbook contents before creating a ZIP. Run `python tools/package_release.py --check` for a read-only audit. It blocks changed tracked source and untracked eligible additions rather than silently omitting feature files. See [Release privacy](RELEASE_PRIVACY.md) for the current policy, independent archive verification and detection limits. The dated archive receipts above retain their original historical scope.

## Limits and preserved history

The model selects tools and one or two eligible fact IDs. Service templates own wording/links; rules own monetary assessment, confidence, missing-evidence requests and proposals. Exact rendering does not prove source completeness, catalog correctness or complete policy coverage. UNKNOWN still ranks the failure policy above uncertainty in lexical retrieval; no selector runs for that insufficient-evidence case. Confidence is not a calibrated model probability.

The six-family comparison uses repeatedly inspected synthetic development cases and Codex AI review, not independent human adjudication or held-out AI accuracy. The baseline's held-out cases share templates. CPU inference is serialized and transport deadlines do not guarantee downstream cancellation. No payment/ledger mutation, production SSO, public hosting, HA, disaster recovery or measured business benefit is claimed.

[MODEL_RUNTIME](MODEL_RUNTIME.md) preserves failed free-prose V2–V5 outputs and 8B diagnostics; [EVALUATION](EVALUATION.md) preserves exact denominators and comparable baselines. Before-fix [13/14 tied receipt](validation/adversarial-tied-receipt-before-v6.json) and [13/16 delivery-state](validation/adversarial-delivery-states-before-v6.json) failures remain alongside post-fix passes. Original V6 evidence uses its [preserved worker198 receipt](validation/worker-tests-198-pre-selection-priority.json); the mutable current receipt identifies V6b.
