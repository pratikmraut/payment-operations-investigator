# Durable payment-case investigations

Managed payment-case questions use a persistent API queue and an idempotent local worker receipt. The request is saved before evidence retrieval or generation. This applies to new payment-case investigations; legacy synchronous export/demo contracts are not used by this queue.

## Request flow

```mermaid
sequenceDiagram
  actor Analyst
  participant API
  participant DB as Case database
  participant Worker as Local investigation worker
  participant Model as Ollama
  Analyst->>API: POST investigations/readiness (question, evidenceId, evidenceHash)
  API-->>Analyst: Local evidence and knowledge readiness; no model call
  Analyst->>API: POST investigations + Idempotency-Key
  API->>DB: Save QUEUED job and admission binding
  API-->>Analyst: 202 saved job summary
  API->>DB: Claim fair turn with fenced lease
  API->>API: Verify scope, evidence and pinned knowledge; select documents
  API->>DB: Freeze input and canonical hash
  API->>Worker: Exact context preflight
  API->>DB: Persist submission uncertainty before HTTP
  API->>Worker: Status or idempotent submit of frozen job
  Worker->>Model: Generate once for this worker receipt
  Analyst->>API: Poll saved job
  API->>Worker: Poll receipt
  Worker-->>API: Completed answer and matching identity
  API->>DB: Validate citations and binding; save terminal answer under lease
  API-->>Analyst: Saved result, sources and elapsed time
```

## HTTP contract

All paths below are under `/api/payment-cases/{caseId}`. A scoped canonical case ID or its display case number is accepted. Responses have `Cache-Control: no-store`. POST operations require CSRF protection.

- `POST /investigations/readiness`: exact JSON `{question,evidenceId,evidenceHash}`. A scoped reader can inspect readiness. This only reads local saved source metadata and approved knowledge metadata; it does not call embeddings or generation. The response includes evidence identity, `ready`, `selection`, `knowledgeSelection`, `knowledgeVersion`, knowledge index status, and explicit unchecked model/context indicators. Exact worker context preflight happens after the selected request has been frozen.
- `POST /investigations`: same JSON and an `Idempotency-Key` containing 8–200 safe characters. Requires an analyst or reviewer and an active case. Returns `202` with the saved job summary. The same actor/case/key and original command return the same saved job; a different command with that key returns `409 CASE_QUESTION_KEY_CONFLICT`.
- `GET /investigations/{id}`: scoped saved job, preserved documents and any original validated answer. Pending jobs that have not reached preparation return an empty `documents` array.
- `GET /investigations/{id}/summary`: scoped metadata only, useful when a reviewer conclusion refers to a job outside the current history page. No source bodies or answer are returned.
- `POST /investigations/{id}/cancel`: exact empty JSON `{}`. The author may cancel their own question, and a reviewer may cancel any question within their case scope. Administrator and viewer roles do not gain investigation-write permissions. Returns the job summary directly. Repeating the command is safe and does not require a new retry key.

Question text is 1–2,000 characters; the saved evidence fingerprint and exact evidence version must match, with at least one source row. Admission rejects stale or incomplete approved knowledge embeddings rather than silently substituting guidance.

## State and cancellation

Public `status` remains `QUEUED` or `RUNNING` while active. Terminal states are `COMPLETED`, `FAILED`, and `CANCELLED`. New records include `queueVersion: case-job-v1` and a more specific `phase`:

`QUEUED → PREPARING → PREFLIGHT → SUBMITTING → GENERATING → COMPLETED`

Temporary worker unavailability uses `WAITING`. Cancellation after submission uses `CANCELLING`; neither releases the active-case guard. A pending job that has never crossed the worker submission boundary can be cancelled locally. A submitted job becomes terminal only after a matching worker cancellation/terminal receipt. A worker 404 is not a cancellation acknowledgement. The current worker persists a cancellation tombstone even if it has not yet received the delayed submit request, preventing that late request from starting generation.

Cancellation and completion are serialized under the same fenced job lock. If cancellation wins, a late completed answer is discarded. This does not promise that an already executing model request can be stopped instantly; the case remains active until the worker acknowledges completion or cancellation.

## Persistence, limits and fairness

- The global queue accepts at most 32 `QUEUED`/`RUNNING` questions; each tenant/actor pair accepts at most 4. A `429 CASE_INVESTIGATION_BUSY` response means no additional job was admitted.
- A shared database singleton grants one model lane. Admission and dispatch use a consistent singleton → case → job lock order. Case mutation locks also serialize against history-index repair.
- Fair turns rotate among tenant/actor pairs, using the oldest requested job within each pair and a stable ID tie break. An active or uncertain submitted job retains the lane until its worker state is known, preventing a retry from causing parallel duplicate generation.
- The lease lasts 30 seconds and is renewed every 5 seconds while a dispatcher is working. All state writes are fenced by owner, job ID, monotonically increasing fence, and lease expiry. Another API process cannot accept a stale owner's result.
- A small scheduler checks persisted work every second. Executor rejection or API shutdown does not erase saved jobs. Worker transport failures retain the frozen request and retry after 5 seconds, with a fixed public explanation rather than provider/private error details.
- Admission stores the canonical original command hash. Preparation rechecks the original command, actor/case/evidence identity, selected evidence fingerprint and pinned knowledge version. After preparation, the complete worker input and hash remain frozen across retries and restarts.

The API queue is not a general distributed task platform: it intentionally serves one local model lane. Adding model replicas requires an explicit capacity and worker-routing design, not merely starting more API processes.

## Recovery and limits

The API saves `workerSubmissionStarted` before a request can reach the worker. After an uncertain response or API restart, it checks the same worker identity and resubmits only that same frozen request if no receipt exists. The worker validates its own canonical input hash and treats duplicate submissions idempotently.

The worker stores private input and results in its configured SQLite receipt store. If it restarts while an inference was running, that receipt becomes `FAILED / CASE_WORKER_INTERRUPTED`; the original inference is not silently generated a second time. The user can intentionally submit a new question with a new key after reviewing the failure.

API startup preserves new durable jobs and repairs missing queue sidecar rows. Older unfinished synchronous jobs have no worker receipt with which to prove safe resumption, so they receive `CASE_INVESTIGATION_INTERRUPTED` once. Previously completed legacy jobs and their original answers remain readable without rewriting their stored source bodies.

No system can promise exactly-once inference if the operator deletes/restores only one side of the persisted API/worker state. Back up and restore the case database, evidence/private guidance and worker receipt store as one operational set. Preserve the worker receipt database during ordinary upgrades and restarts.

## Timing

The existing `timing` contract is retained: `preparationMs` is request admission time, `queueMs` covers the wait until dispatch, `processingMs` covers active processing, and `totalMs` covers request receipt to the terminal saved state. Older rows lacking `requestedAt` keep the `job-created` basis.

New durable jobs additionally expose `phaseTiming.admissionMs`, `phaseTiming.preparationMs` (actual document retrieval/preparation), and `phaseTiming.workerWaitAndGenerationMs` (preflight/worker wait/generation through terminal persistence). Unfinished or unavailable intervals are null. This is separate from the answer's model-reported inference duration. Terminal timing stays fixed on rereads and retry-key replay.

## Permanent deletion

Existing active-job guards still prevent archiving/deleting a case with active investigations. Permanent deletion writes a scoped worker-cleanup outbox row in the same database transaction as the case tombstone/source purge. Cleanup calls happen after that transaction and outside case locks. The outbox is removed only after a matching `{tenantId,caseId,forgotten:true}` receipt; unavailable, malformed or active-worker responses remain pending and retry after 30 seconds.

The worker refuses to purge active generation and permanently tombstones a forgotten case, preventing delayed old submissions from recreating private receipts. The queue sidecar rows cascade when their original investigations are deleted; the derived history index is also purged. Cleanup completion is eventual while the worker is unavailable. The operational backup includes the cleanup outbox and receipt tombstones.

## Regression coverage

The isolated Java service tests use a persisted synthetic case and an idempotent receipt double through the production dispatcher. They exercise admission-before-retrieval, local readiness, immutable command/input hashes, exact preflight limits, uncertain submission and restart, fair actor turns, queue limits, lease fencing/heartbeat, cancellation acknowledgement and races, source changes, citation validation, legacy reading/recovery, verified cleanup receipts, and scope/idempotency checks. No bank API or live model is used.
