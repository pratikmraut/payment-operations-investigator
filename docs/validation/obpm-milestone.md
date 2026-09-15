# Synthetic OBPM NEFT milestone — 12 September 2026

The local app now accepts original synthetic outbound NEFT/ECA evidence, persists immutable snapshots, runs scoped investigation/retrieval and presents the evidence in React. It does not connect to Oracle, execute a payment, or establish real NEFT operational accuracy.

## Implemented input and output

- Input: a bounded `obpm-evidence-v1` JSON snapshot containing source identity, exact INR amount, payment reference, queue records, correlated ECA attempts and per-source coverage. Original sample files are available in the case queue.
- Java: session/CSRF/role and tenant controls; exact decimal-to-paise validation; strict supported fields; bounded payloads/collections; source identity; canonical hashes; atomic imports; immutable evidence versions; duplicate and stale-source handling.
- Worker: four real LangChain snapshot tools in a LangGraph OBPM branch. Current EC/T plus correlated and complete evidence supports a recorded timeout. Unknown, incomplete, ambiguous or newly pending evidence yields specific evidence requests. No OBPM case can propose resolution.
- RAG: original OBPM sidecar runbooks filtered by tenant, effective date, domain, rail, direction and release family. Generic investigations exclude OBPM-only policies.
- Dashboard: import receipts, separate native payment/case status, raw queue and attempt references, snapshot-as-of age, coverage, evidence history, findings/citations/tool trace, independent case review, audit and export. Totals cover imported cases, not bank-wide payment volume or success rates.

See the [walkthrough](../OBPM_STEP_BY_STEP.md), [normalized contract](../OBPM_IMPLEMENTATION_CONTRACT.md), [API documentation](../API_CONTRACT.md) and [flows](../ARCHITECTURE.md).

## Executed validation

The final component run passed **52 Java, 280 worker and 44 React tests** (376 total). [The current component receipt](obpm-components.json) records these totals and selected source/tested-artifact/running-artifact correspondence. Java tests include simultaneous initial imports, immutable history, in-flight worker/source races and stale-review rejection; worker tests include actual restricted pgvector operations. Model adapter unit tests use controlled HTTP responses and are distinct from the real-model run below.

[Actual HTTP Replay acceptance](obpm-http-replay.json) passed 14 checks through React's nginx API route, Spring Boot, PostgreSQL and the real worker. It verified exact money rejection; CSRF/role/tenant controls; original sample import; duplicate identity; canonical snapshot hashes; current timeout diagnosis; scoped citations; source refresh; immutable prior investigation; stale-source/stale-review rejection; newer pending-attempt abstention; independent idempotent review; export; and preserved generic investigation behavior. It made zero model calls. This is a bounded synthetic acceptance suite, not a representative accuracy benchmark.

The [initial real-model attempt](obpm-ollama-initial-failure.json) is retained as a failure to diagnose the supplied complete timeout. The actual model selected queue, ECA requests and coverage but omitted payment identity. Evidence rules safely returned insufficient evidence. This failed attempt must not be counted as a successful timeout diagnosis. Model follow-up results are recorded separately.

The corrected OBPM planner uses a typed permutation of all four mandatory tools. The model chooses their order; the service fixes the authorized case identity. An incomplete, duplicated or unknown tool plan is rejected without automatic additions or a Replay fallback. Fact selection remains a separate actual model stage when evidence supports a conclusion. Metrics expose `toolPlanningScope: mandatory-evidence-order`; this is controlled evidence collection and fact selection, not autonomous banking diagnosis.

[Actual HTTP Ollama acceptance](obpm-http-ollama.json) passed all 14 checks in 46.845 seconds with `qwen3:4b-instruct`. Three synthetic NEFT investigations used **four actual chat calls**: two for the current timeout (tool ordering and selection of `FACT-OBPM-ECA-TIMEOUT`), and one each for the newer pending attempt and unknown/incomplete evidence (ordering followed by deterministic abstention). The first finding cites the matching queue/attempt records and the scoped timeout runbook. The [selection review](obpm-model-selection-review.json) records this bounded check. This does not measure independent banking accuracy, operator productivity or OBPM hybrid-embedding behavior.

The [legacy HTTP regression](obpm-legacy-regression.json) passed all 17 existing workflow checks with OBPM cases also present. Its fixture assertion now verifies the original generic case identities independently of the growing imported-case collection.

[Browser observations](obpm-browser.json) preserve 16 functional checks covering original-sample import, bank evidence, source coverage, version history, Replay findings, citation navigation, evidence highlighting, tool trace and generic cases. Three post-copy checks verify the final deployed bundle, corrected source timestamp label and unchanged original case. An actual screenshot was emitted inline and its digest recorded; no local screenshot files are claimed. The updated initial-helper wording is covered by component tests and the packaged bundle. The original `DEMO-NEFT-0001` case remains awaiting review for the user's walkthrough.

## Local runtime and build limitation

Open <http://127.0.0.1:5178>. The existing PostgreSQL and model volumes were preserved. Java, worker and web containers were replaced with the updated local images. Existing generic cases and investigations remain present.

Docker Hub base-image metadata requests timed out on this laptop's current network. Java was tested and packaged offline in an existing cached Maven image. Updated application images were built from existing local runtime images with the tested Java jar, current worker source and the native-built React bundle. This workaround introduces no dependency changes and does not prove clean-room or whole-image reproducibility. The normal checked-in Dockerfiles remain available for an environment with registry connectivity.

To start these already-built local images without fetching/building base images:

```powershell
docker compose --profile ai up -d --no-build
```

Do not remove database/model volumes to repeat a demonstration. The HTTP acceptance harness creates distinct original synthetic references on each run and preserves prior histories.

```powershell
python tools/generate_obpm_samples.py --check
python tools/obpm_acceptance.py --mode replay --report docs/validation/obpm-http-replay.json
python tools/obpm_acceptance.py --mode ollama --report docs/validation/obpm-http-ollama.json
python tools/record_obpm_validation.py
```

Ollama execution needs the local running model and may take several minutes. No error falls back silently to Replay.

## Oracle mapping boundary and next steps

The user authorized inspecting internal Grok and Confluence links. Bounded read-only navigation found FLEXCUBE 11.x source-tree names. The specific Confluence page was titled “OBPM Data Model / FC-OBPM Mapping,” last updated 7 August 2022, with data-model and column-mapping attachment links. No release number was visible. No attachment contents, product source code or transaction records were downloaded or copied into this project. These observations identify useful mapping resources but do not verify OBPM 14.7 compatibility or any table name.

For an actual Oracle test integration, establish the exact installed maintenance release and matching documentation first. The [field-mapping checklist](../OBPM_FIELD_MAPPING.md) specifies the required logical fields and verification steps. Then verify the approved inquiry response/reporting-view fields, payment/queue/attempt correlation, status semantics, extraction scope, pagination and timestamps. Implement an original read-only extractor inside an authorized non-production environment; reconcile sampled normalized snapshots to the source screens before expanding evidence types. Use approved local procedures for RAG in that environment.

Credentials should be configured locally in that environment. This personal demonstration requires no customer data, proprietary source corpus or live Oracle credentials. Message/accounting ingestion, continuous synchronization, enterprise identity, bank-wide analytics and production deployment remain future work.
