# Local operations and bounded reliability evidence

The final local runtime uses V6b with Qwen3 4B, lexical retrieval and five running project services: [runtime-final](validation/runtime-final.json). Java35, worker198 and UI34 tests, [replay17](validation/acceptance-compose-replay-v6b.json), [adversarial16](validation/adversarial-replay-v6b.json), [replay30](validation/evaluation-replay-test-v6b.json), [live Java17](validation/acceptance-compose-ollama-v6b.json), [selection review](validation/v6b-fact-selection-review.md), [planner/hybrid review](validation/v6b-live-planner-hybrid-review.json), [browser14](validation/frontend-browser-v6b.json), [cached startup](validation/start-with-ai-v6b.json) and [fresh-volume rehearsal](validation/rehearsal-compose-v6b.json) are recorded. These are bounded synthetic-data results; the model selects facts and the service renders wording/links. The main editable delivery is the project folder and its VS Code workspace. Earlier ZIP verification is preserved in `docs/validation/package-history/latest-release.json`; the user requested removal of redundant packages. See [source-folder delivery](validation/source-folder-delivery.json).

Reviewed and exercised on 12 September 2026, India time. All case records are synthetic. This runbook covers this project's local services; it does not describe a bank operating procedure or a production deployment.

The current application uses the dedicated Compose project and PostgreSQL. Native project servers are stopped; their PID files are historical and do not establish current process ownership. The native read measurements below remain evidence of that earlier run, not current container latency. Consult [delivery status](STATUS.md) before any restart because a model investigation may be in flight.

## Final runtime and startup checks

[Runtime-final](validation/runtime-final.json) records five running services, the default Qwen3 4B/lexical configuration and ten worker module hashes matching the frozen source. [Final build provenance](validation/final-build-provenance.json) matches 45 recorded local source hashes, ten running worker modules, the served JavaScript bundle and the running Java JAR against its retained tested-build JAR. The previous API manifest ID is no longer locally resolvable; no whole-image filesystem/configuration equivalence or cross-environment reproducible build is claimed. The temporary [hybrid runtime](validation/runtime-v6b-hybrid.json) belongs to its separate successful probe.

[Cached startup](validation/start-with-ai-v6b.json) exercised `tools/start.ps1 -WithAI` in 21.181 seconds using the existing model volume. Model/download dependencies were cached; health readiness made no new inference. [Fresh-volume rehearsal](validation/rehearsal-compose-v6b.json) took 27.11 seconds, passed [17 acceptance checks](validation/acceptance-clean-compose-v6b.json), and removed all generated containers/volumes for its isolated project. This is built-source startup with fresh data, not a first internet download, Git-clone installation or model inference.

[Browser14](validation/frontend-browser-v6b.json) exercised real selection/planner labels, source and policy links, review-note validation, independent escalation, audit and historical replay handling. The refund investigation and operational snapshot remained unchanged after review. Earlier UI32 outage/recovery and native read receipts below keep their own versions and measured scope.

## What was actually tested

The stdlib-only [reliability harness](../tools/reliability.py) ran against the native Java API at `http://127.0.0.1:8088`. Its [machine-readable report](validation/reliability-native.json) records every request's method, path, status, elapsed time and server request ID. It stores no password, CSRF token, session cookie or response body.

| Observation | Result from this run |
| --- | --- |
| Runtime | Windows 11, Python 3.12.14, native API |
| Start | 2026-09-12 00:48:00 IST / 2026-09-11 19:18:00 UTC |
| Checks | 16 passed |
| Authenticated read workload | 40 requests, up to four concurrent clients |
| Read paths | Case list, dashboard, case detail, saved investigation history, audit history |
| Read HTTP status | 40/40 returned 200 with expected response shape and scope |
| Read latency | Minimum 5.555 ms; median 17.481 ms; p95 185.708 ms; maximum 212.847 ms |
| Read phase elapsed | 0.437 seconds |
| Total requests including controls and logout | 73 |
| Request IDs | 73 unique server-generated UUIDs; error bodies matched their response header |
| Session cleanup | All four authenticated harness sessions logged out; subsequent `/auth/me` returned 401 |

The percentile uses the nearest-rank method across all 40 read observations, with no warm-up exclusion. Timing spans the HTTP request through reading its response. Control requests and authentication are excluded from read latency. The services and host were already running; these numbers do not establish cold-start behavior, steady-state capacity, concurrency limits, a latency SLO or production readiness. The four clients are independent cookie sessions, one per demo identity; they do not send concurrent requests through a shared cookie jar.

Additional exercised controls:

- Anonymous protected reads remain unauthorized even with supplied tenant/role headers.
- Public login rejects malformed JSON, blank or oversized usernames, unknown fields and trailing JSON values with 400 `INVALID_REQUEST`.
- Invalid credentials return 401; a cross-site browser hint returns 403. These failed attempts leave the guest unauthenticated.
- Successful logins issue HttpOnly, SameSite=Strict session cookies and CSRF tokens. The harness checks the local HTTP attributes; it does not claim TLS or a Secure cookie.
- Both tenants receive only their case lists; cross-tenant details return 404 in both directions. Supplied headers cannot change an authenticated viewer's role or tenant.
- Missing and incorrect CSRF tokens cannot log out the analyst. Correctly authenticated logout ends the session.
- Success and error responses include `Cache-Control: no-store` and a server-generated `X-Request-Id`; a caller-supplied request ID is not trusted.

No worker endpoint, model call, investigation creation, review command, outage, restart or data reset occurred during this run. Database restart, worker interruption, session inactivity expiration, network partitions, concurrent decisions and sustained overload are outside this harness. Other project evidence must be consulted separately for any such claim.

## Separate Compose outage and fresh-volume evidence

The [controlled outage report](validation/faults-compose.json) records seven passing checks in 58.735 seconds against the dedicated Compose project. Worker unavailability returned explicit 503 without a stored investigation or case transition and created exactly one new failure audit. After worker recovery, a replay investigation completed. PostgreSQL interruption caused an explicit database-backed read error while API liveness remained UP. Recovery preserved the exact case, investigation and failure-audit bodies; final checks verified both dependencies ready with no recovery errors.

The observed stop-attempt-to-readiness intervals were 16.297 seconds for the worker and 39.171 seconds for PostgreSQL. Those intervals include intentional outage assertions and requests; they are not restart-only latency or recovery objectives. This was replay against the recorded images, not an interruption of an in-flight model call.

The [fresh-volume rehearsal manifest](validation/rehearsal-compose.json) and [acceptance report](validation/acceptance-clean-compose.json) record 17/17 workflow checks, 40.122 seconds total, and verified absence of remaining rehearsal containers/volumes after cleanup. It used a generated isolated project name and separate ports; the main project's records were preserved. This proves startup from built source with fresh data volumes, not a remote clone, model inference or a repeat on later provider-schema changes.

The later [final rehearsal manifest](validation/rehearsal-compose-final.json) pairs with [17 passing final acceptance checks](validation/acceptance-clean-compose-final.json) by SHA-256. It completed in 31.631 seconds, including 21.374 seconds for startup and 3.794 seconds for cleanup/verification; no generated rehearsal containers or volumes remained. This historical rerun covers its recorded V4 source startup/review path with fresh volumes, without model inference. The `-AcceptanceReport` option in [rehearse.ps1](../tools/rehearse.ps1) was exercised to preserve the older report. Use a distinct project-local report name for another run and retain its separate manifest.

To repeat these deliberately disruptive checks, first finish active investigations and follow the exact project scope in [faults.py](../tools/faults.py) or [rehearse.ps1](../tools/rehearse.ps1). The outage script interrupts this project's worker/database; the rehearsal creates and removes only its generated disposable project. Use the current run's status, timestamps and recovery section. A previous passing report does not prove an interrupted or failed rerun passed.

## Model evidence and operator interpretation

Current V6 separates actual tool planning, model-selected fact IDs and service-rendered text/links. The service validates one or two offered distinct IDs against the recorded catalog, and the rules own assessment/proposal. The first six-case sample completed, but three selected pairs omitted available primary exception evidence. Historical model prose can contain timing/refund/workflow errors; preserve its original provenance. Consult [MODEL_RUNTIME](MODEL_RUNTIME.md) and [EVALUATION](EVALUATION.md) rather than treating an accepted response as a useful or correct investigation.

The earlier [Java planner-only probe](validation/live-planner-only-java-4b.json) used one actual call and correctly requested evidence, but its saved missing-evidence text was generic. Current source supplies more specific status, ledger-cutoff and delivery-history requests. [A fresh V6b Java-path demonstration](validation/live-planner-only-java-v6b.json) passed with one model call, no findings and those three specific requests.

## Reproduce the bounded check

Run from the project directory with the API already available:

```powershell
python tools/reliability.py --base-url http://127.0.0.1:8088 --clients 4 --requests 40 --report docs/validation/reliability-native.json
```

If the demo password was changed, supply the existing `POI_DEMO_PASSWORD` environment variable. The harness does not print or save it. A smaller sample is possible with `--clients 2 --requests 20`; the maximum remains four concurrent clients and 40 workload reads. Authentication/control/cleanup requests are additional. The default socket timeout is five seconds, configurable only from one to ten seconds. Responses are limited to two million bytes. Only HTTP loopback targets are accepted, redirects are refused and environment proxy settings are bypassed for this local check.

The client method gate permits GET requests and POST to login/logout only. There are no automatic retries. On the first failed workload check, other clients stop scheduling new reads after their current request finishes. Cleanup attempts to log out sessions, records any cleanup failure, writes a failed report and exits nonzero. Each outstanding socket operation is bounded by its timeout; this is not a guarantee of a strict overall wall-clock deadline or cancellation of server work. If an operation is interrupted before the process can write its report, do not interpret the previous report as evidence for that interrupted run.

This command overwrites the named report. Use another project-local `docs/validation/reliability-*.json` name when retaining a separate run. The report's `status`, timestamp, configuration and request samples are the evidence for that run; a source change alone does not update its measured results.

## Health checks and identifiers

The following behavior is **source-reviewed**, except for the API process health and request-ID checks above.

| Endpoint or signal | What it establishes | What it does not establish |
| --- | --- | --- |
| `GET /api/health` on 8088 | Java request handling is alive; returns the synthetic mode | Database connectivity, worker availability or model readiness |
| `GET /health` on 8091 | Worker HTTP process is alive and reports configured modes/storage | Ollama reachability, downloaded model availability or vector database connectivity |
| Authenticated `GET /api/system` | Java asks worker health and, when up, its knowledge listing; reports tenant case count | Successful generation or hybrid retrieval; the worker health response is not a model probe |
| `knowledgeAvailable: false` | System knowledge count was not available | A verified empty runbook library; a displayed zero count is not evidence of absence |
| `X-Request-Id` / error `requestId` | Identifies one public Java response; body/header match for errors | End-to-end distributed tracing: the Java worker request does not propagate that ID, and the filter does not add an MDC log context |

Use the case ID, approximate timestamp and request ID together when recording a failure. Worker errors have their own independently generated IDs for handled exceptions; Java normally translates the worker failure to its public error code. Do not promise that a public request ID can be searched across every service log.

## Failure behavior and operator action

The table describes source-reviewed behavior. The current 112-test observed-facts worker, 31-test web image and 30-test Java API are deployed. [V4 live acceptance](validation/acceptance-compose-ollama-v4.json) passed 17 checks in 100.801 seconds for two investigations; historical v3 replay passed while its first live finding was rejected. Worker/database outage observations have the separate evidence above; the native read harness did not exercise those failures. Historical first-model runs used 120-second provider, 270-second Java and 300-second proxy settings. The deployed configuration uses 180/390/420 seconds; replay checks passed, but the full-synthesis live v2 attempt failed on its second investigation. See [model runtime](MODEL_RUNTIME.md).

| Failure or condition | Current implementation | Local operator action |
| --- | --- | --- |
| API unreachable | React reports a connection error; nginx can return a gateway error. The client recognizes an HTML 504 as an unconfirmed timeout. | Check the tracked API process/container and its logs. After recovery, sign in if needed and refresh the case/history. |
| Worker down, unreachable or non-200 | Java returns 503 `WORKER_UNAVAILABLE`; connection timeout is three seconds. It does not fabricate an investigation or switch modes. Investigation failure handling attempts to append `INVESTIGATION_FAILED` audit. | Check worker process, URL and service-key agreement. Read case/history/audit before retrying. A database failure can also prevent the failure audit from being stored. |
| Worker investigation is slow | Java now defaults/caps at 390 seconds; nginx and Vite allow 420-second proxy reads. Earlier model runs used 270/300 seconds. The live v2 attempt failed on output validation, so the larger values do not establish overall acceptance or a latency guarantee. | Check the deployed configuration, not just source defaults. Avoid repeated submissions while a request may still be executing. Refresh saved history and inspect worker activity before another explicit run. |
| Ollama unavailable, missing model or provider call failure | Worker raises handled 503 `PROVIDER_UNAVAILABLE`; Java exposes 503 `WORKER_UNAVAILABLE`. No automatic replay substitution. | Check the project Ollama container, selected tag and provider logs. Choose replay explicitly only if that is the intended demonstration mode. |
| Invalid model tool selection, schema or evidence references | Recognized model validation errors return worker 422 `INVALID_MODEL_RESULT`. Other unexpected parsing failures can return worker 500. Java treats non-200 worker responses as unavailable; its own response validation returns 503 `INVALID_WORKER_RESPONSE`. | Preserve the failure details and inspect the selected model/output validation. Do not treat an error or partial checkpoint as an approved result. |
| Explanation says the case is resolved or no review is needed | Historical accepted timeout/ordering prose overclaimed completion before review of the new proposal. The historical full-synthesis worker supplied authoritative proposal-state context and rejected observed completed-workflow/unqualified no-action phrases. Focused tests pass; v2 accepted supported financial-only wording once but conservatively rejected a second ambiguous unqualified no-action statement. | Treat current case state and durable decisions as authoritative. This narrow guard does not cover every paraphrase. Review original prose against snapshot/policy and preserve historical defects instead of claiming schema acceptance proves correctness. |
| Resolution proposed with unavailable payout or nonzero/unknown discrepancy | Java rejects a new `RESOLVE_CASE` without its own valid-money, available-payout and zero-discrepancy facts. The worker normally requests evidence for these conflicts in timeout/delivery/order cases; a sole supported confirmed missing refund remains an escalation. | Inspect authoritative reconciliation and the evidence request. A case resolution never repairs ledger values; see [domain rules](DOMAIN.md). |
| Hybrid embedding/vector failure | Worker reports `PROVIDER_UNAVAILABLE`; hybrid does not silently fall back to lexical. Replay synthesis also needs embedding/vector services when retrieval is configured as hybrid. | Check embedding tag, vector role/schema, database URL and credentials. Changing to lexical is an explicit configuration change requiring a worker recreation/restart. |
| Missing or mismatched worker service key | Worker protected routes reject requests: missing configured key gives 503; absent/wrong supplied key gives 401. Its health endpoint remains public. Java reports worker failure. | Restore the matching configured key without printing it in logs or reports; recreate/restart only the affected project service. |
| Database unavailable | API health can remain UP. Database-backed reads/writes can fail; generic unhandled data-access failures become 500 `INTERNAL_ERROR`. | Inspect the configured database and project persistence. Do not infer recovery from `/api/health` alone. |
| Session absent, expired or API restarted | Protected API routes return 401. Session state is process-memory-backed; configured inactivity timeout is 30 minutes. React returns to login on a protected 401. | Sign in again. This harness tested explicit logout, not waiting 30 minutes for inactivity expiry. |
| Wrong role/CSRF or self-review | API rejects the command with 403. | Use the correct session/token and an independent reviewer; client tenant/role headers cannot grant authority. |
| Case changed during investigation | Java checks the original case version before atomically inserting the result and advancing case state. A changed version returns 409 `VERSION_CONFLICT`; that stale result is not committed as a current investigation. | Refresh and investigate the current snapshot. |
| Review response lost | An identical command by the same actor with the same idempotency key returns the durable original decision. Changed payload with the reused key conflicts. | Preserve the same command/key for an exact retry; inspect current state and audit. Do not invent a new key to force a repeated action. |

The worker serializes graph execution with a process-local lock around its SQLite-backed graph. Up to two model calls request four CPU threads, 384 output tokens per stage and a 4096-token context with reasoning and streaming disabled. The deployed provider HTTP timeout is 180 seconds; the earlier model records used 120. [Historical 112-test evidence](validation/worker-tests-112-pre-timing-context.json) covers observed-only finding context, assembly, planner-only insufficient handling, adapter serialization with synthetic responses and workflow-language checks. The suite itself is not live inference; the separate v4 HTTP report is. The [96-test full-synthesis receipt](validation/worker-tests-96-pre-finding-only.json) remains historical. The historical V5 provider schema accepted one model-written Finding. Current V6 accepts only eligible fact IDs and renders their exact catalog text and evidence/policy links. Rules assemble the public summary, outcome, confidence, missing facts and proposal; the model does not write them. These formatting, reference and phrase constraints do not establish sentence-level semantic support or independent model diagnostic accuracy. Nonstreaming applies the read timeout while awaiting a complete provider response; transport limits are not strict total-graph deadlines or server-compute cancellation guarantees. Queue wait, optional embedding requests and persistence are additional work. Neither historical nor current transport limits establish that queued or already-running worker/Ollama computation has been cancelled.

```mermaid
sequenceDiagram
  participant UI as React case view
  participant API as Java API
  participant W as Worker / Ollama
  participant DB as Case database
  UI->>API: Authenticated investigation for case A
  API->>W: Authorized snapshot and requested mode
  alt Valid result before API deadline
    W-->>API: Evidence-linked result
    API->>DB: Lock, check original version, store result/state/audit
    API-->>UI: Saved investigation
  else Error or Java wait expires
    API->>DB: Attempt failure audit
    API-->>UI: Error; no fabricated result
    Note over W: Already-started work may continue
  end
  Note over UI: Leaving case A stops observing its request
  UI->>API: On return, read saved case history
```

Leaving a case aborts the browser fetch and suppresses late callbacks. The new case cannot display the old pending response. Existing findings are labeled as previously saved while another request is pending. This browser behavior is covered by frontend component tests, not by the read harness. A disconnected request may still finish and persist at Java, or Java may have already timed out and discarded the worker response; read the saved case state to determine what is durable.

## Project-only recovery

Begin with read-only inspection from this project directory:

```powershell
$poiCompose = @('compose', '-f', 'compose.yaml', '-p', 'payment-operations-investigator')
& docker @poiCompose ps
& docker @poiCompose logs --tail 80 api investigator web
& docker @poiCompose --profile ai logs --tail 80 ollama
Invoke-RestMethod http://127.0.0.1:8088/api/health
Invoke-RestMethod http://127.0.0.1:8091/health
```

Reuse the explicit `$poiCompose` arguments from this project directory. For a known failed service, `& docker @poiCompose restart api` or `& docker @poiCompose restart investigator` restarts only that service. Restarting does not apply changed environment/build configuration; use `& docker @poiCompose up -d --build <service>` when those changed, after checking active work. A web rebuild is required to deploy a changed nginx configuration. Inspect dependencies before retrying a business command. This runbook documents these actions; the native reliability run did not execute them.

For the optional project model service, read `& docker @poiCompose exec -T ollama ollama list` to check installed tags and `& docker @poiCompose exec -T ollama ollama ps` to inspect loaded models. Starting it with `& docker @poiCompose --profile ai up -d ollama` preserves its model volume. Model downloads and a successful model listing are separate from a verified inference result. Follow [SETUP.md](SETUP.md) for intentional model installation/configuration; avoid parallel generation calls on this local CPU setup.

Native development uses the same host ports as Compose. Inspect each tracked process before stopping or starting anything:

| Native service | PID file | Logs |
| --- | --- | --- |
| API | `services/api/runtime/api.pid` | `services/api/runtime/api.stdout.log`, `api.stderr.log` |
| Worker | `services/investigator/runtime/worker.pid` | Inspect the files in `services/investigator/runtime` used by its launcher |
| Vite | `apps/web/.runtime/vite.pid` | `apps/web/.runtime/vite.stdout.log`, `vite.stderr.log` |

Verify the PID's current executable/command line and listening port; PIDs can be reused. The API launcher `services/api/start-local.ps1` refuses to replace an occupied 8088 port and uses a scoped Windows JVM socket workaround. Follow each service README for its native launch command. Do not start a duplicate on an occupied port, kill all Java/Python/Node processes, or change machine-wide JVM settings. The unrelated AutoPay Guard services on 3000/8080/5432 are outside this project.

Project ports are 5178 (web), 8088 (API), 8091 (worker), 5438 (PostgreSQL), and 11438 (Ollama), bound to loopback. `tools/stop.ps1` stops this Compose project and preserves its volumes. Never use global pruning or `docker compose down -v` as a recovery step: removing the database/checkpoint/model volumes destroys the local durable demonstration state or downloaded models.

## Persistence and retry limits

- Java stores cases, immutable investigations, decisions and audit in H2 for the native default or PostgreSQL in Compose. Fixture import inserts missing IDs; it does not reset existing case decisions. Sessions are separate in-memory state.
- Review storage, case transition and audit are one database transaction. Application append-only access is not protection against a database owner editing data. Backup/restore and tamper-evident external retention were not tested by this harness.
- Worker SQLite checkpoints are keyed by tenant, investigation ID and an immutable-input fingerprint. An identical worker request can resume an interrupted graph or return its stored result; changed input under the same identity is rejected. The public Java API creates a **new investigation ID for each new POST**, so pressing Run again is not automatic continuation of an earlier worker checkpoint.
- No public cancellation endpoint, durable background job queue or cross-process graph scheduler is implemented. Stopping a browser request does not prove that Java, Python or Ollama has stopped work. The current local lock is not a horizontally scalable worker design.
- PostgreSQL initialization scripts install pgvector and bootstrap the restricted `poi_vectors` role/schema only for a fresh volume. Changing environment values does not rotate an existing database password. Preserve the configured role/search path and consult [SETUP.md](SETUP.md) before changing credentials.

## Source-review references

- [Public security, request IDs and session configuration](../services/api/src/main/java/dev/pratik/poi/SecurityConfig.java), [login/logout validation](../services/api/src/main/java/dev/pratik/poi/AuthController.java), [application defaults](../services/api/src/main/resources/application.yml).
- [API health/system and reads](../services/api/src/main/java/dev/pratik/poi/ApiController.java), [worker transport/deadlines](../services/api/src/main/java/dev/pratik/poi/WorkerClient.java), [public error translation](../services/api/src/main/java/dev/pratik/poi/ErrorHandler.java).
- [Investigation and review persistence](../services/api/src/main/java/dev/pratik/poi/InvestigationService.java), [worker routes/errors](../services/investigator/investigator/main.py), [serial graph/checkpoints](../services/investigator/investigator/graph.py), [worker defaults](../services/investigator/investigator/config.py), [hybrid retrieval failure](../services/investigator/investigator/retrieval.py).
- [React pending/error handling](../apps/web/src/App.tsx), [API client errors](../apps/web/src/api.ts), [nginx timeout](../apps/web/nginx.conf), [Compose services/volumes](../compose.yaml), [setup guidance](SETUP.md).

## Current delivery and money boundary

[HTTP adversarial16](validation/adversarial-replay-v6.json) passed after correcting tied receipt order, pending duplicate and APPLIED late-authorization assumptions. All three now request evidence; [before-fix 13/16](validation/adversarial-delivery-states-before-v6.json) remains preserved. Equal receipt timestamps cannot prove arrival order, and unknown/pending or APPLIED states must not be presented as ignored. See [ADR-018](DECISIONS.md#adr-018-require-observed-delivery-state-before-proposing-resolution).

[Java35](validation/java-container-tests.json) and the [V6 worker198](validation/worker-tests-198-pre-selection-priority.json) test shared ledger semantics: CAPTURE/PAYMENT_CAPTURED/SALE are positive; REFUND/REFUND_POSTED negative; FEE non-positive; generic adjustments retain their sign. Codes use uppercase and dot/hyphen normalization. Invalid capture/refund/fee signs return Java422 INVALID_AMOUNT. Money remains within the JSON-safe integer range. Blank/UNKNOWN/UNAVAILABLE provider status makes payout/discrepancy unavailable, even when numeric zero is supplied.

The worker checks Java aggregates against the same visible immutable records. A contradiction preserves the original reconciliation, marks money invalid with specific issues, suppresses money catalog facts and requests evidence; it does not substitute a new authoritative total. [ADR-019](DECISIONS.md#adr-019-align-ledger-semantics-across-java-and-the-worker) records the boundary. Neither rendering nor a matching zero discrepancy proves missing delivery-processing state.

[Provider request evidence](validation/hybrid-provider-requests-v6b.json) preserves two successful POST /api/chat requests and one successful POST /api/embed request in the known serial probe window. No distributed request ID joins those log lines to the investigation; timing/window correlation corroborates the saved result, not an end-to-end tracing guarantee. Generative modelCalls remains two and excludes the embedding request.
