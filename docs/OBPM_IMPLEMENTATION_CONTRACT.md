# OBPM synthetic milestone contract

Implemented synthetic milestone, 12 September 2026. This contract describes the first outbound NEFT ECA evidence path. See [executed validation](validation/obpm-milestone.md). No Oracle connection or payment execution is included.

## Input v1

A subsequent [mock inquiry transport](OBPM_MOCK_INQUIRY.md) now fetches the original `neft-inquiry-v1` contract over HTTP. It uses the same logical evidence fields, omits `payment.amountMinor`, and fixes `mappingVersion` to `original-synthetic-inquiry-v1`. Java computes exact paise, changes the schema identifier to `obpm-evidence-v1`, validates every field and reuses the importer below. No other evidence groups or payment conclusions were added. The separate [mock OpenAPI](obpm-inquiry.openapi.json) and [Java API](openapi.json) describe the two boundaries.

Single JSON snapshot, `schemaVersion: "obpm-evidence-v1"`, `dataClassification: "SYNTHETIC"`, `snapshotId`, `mappingVersion`, `extractedAt` UTC timestamp.

`source`: `deploymentId: "SYNTHETIC-OBPM"`, `releaseFamily: "14.7"`, `exactMaintenanceRelease` nullable, `hostCode: "DEMO-HOST"`, `branchCode: "DEMO-BRANCH"`. Tenant is assigned from the authenticated server identity, never input.

`payment`: `sourcePaymentId`, `rail: "NEFT"`, `direction: "OUTBOUND"`, `sourceAmountDecimal` decimal string, `amountMinor` exact INR paise, `currency: "INR"`, `activationDate`, `createdAt`, `nativeTransactionStatus` nullable, optional `statusUnavailableReason`.

`queueRecords`: bounded array of `evidenceId`, `sourcePaymentId`, `queueReference`, `requestAttemptId`, `nativeQueueCode`, `nativeResponseStatus`, `enteredAt` nullable, `exitedAt` nullable, `isCurrentQueueRecord`, `observedAt`.

`externalRequestAttempts`: bounded array of `evidenceId`, `requestAttemptId`, `sourcePaymentId`, `requestType: "ECA"`, `requestedAt`, optional nullable `timeoutRecordedAt`, `externalSystemFinalOutcome` nullable. Unknown native response codes are retained and require evidence; no automatic failure inference.

`messages` and `accountingEntries` are empty in this first supported contract. Nonempty collections are rejected as unsupported rather than silently ignored. These evidence groups require a later typed contract and domain rules.

`sourceCoverage` requires `queueRecords`, `messages`, `externalCoreResponses`, `accountingEntries`. Each has `status` COMPLETE/PARTIAL/UNAVAILABLE/NOT_REQUESTED; COMPLETE requires `scope`, `asOf`, `paginationComplete:true`; other states require `reason`. In this slice messages/accountingEntries must be UNAVAILABLE or NOT_REQUESTED. Completeness is per declared query scope, not proof of entire-bank completeness.

## App endpoints and saved case

- `POST /api/obpm/imports`: authenticated writer + CSRF; normalized JSON body above, max bounded payload. Returns `{importId,caseId,status:"CREATED"|"UPDATED"|"UNCHANGED",evidenceVersion,evidenceHash,caseVersion}`.
- `GET /api/obpm/imports`: tenant-scoped recent receipt summaries in `{items:[...]}`.
- `GET /api/obpm/samples`: authenticated, original synthetic sample catalog `{items:[{id,title,description,payload}]}`. Payloads read from configured original local fixture directory; no URL fetching or Oracle credentials.
- `GET /api/cases/{id}/evidence-versions`: tenant-scoped immutable snapshot summaries `{items:[{evidenceVersion,evidenceHash,sourceSnapshotId,extractedAt,importedAt}]}`.

Existing cases endpoints and investigation/review flows are reused. New cases carry `rail:"NEFT"`, `domain:"OBPM_NEFT"`, `obpm:<validated input>`, `evidenceVersion`, `evidenceHash`, and standard id/tenantId/paymentId/title/description/priority/status/amountMinor/currency/createdAt/updatedAt/version/policyDate fields. Legacy `events`, `ledgerEntries`, `webhooks` are empty and `provider` null. Do not show their zero totals as bank evidence. OBPM money validation stays in Java and no card reconciliation can establish OBPM completion.

Stable case identity includes authenticated tenant + source deployment/host/branch + rail/direction/native payment identity. Exact duplicate input is UNCHANGED; changed evidence with a later extractedAt creates a new immutable evidence version, increments case workflow version and resets case to OPEN. Older or changed same-cutoff input is rejected. Historical investigations retain their snapshot. Source refresh invalidates pending review through case-version checks.

## Worker behavior

Add an explicit OBPM branch within the real LangGraph workflow and snapshot-scoped LangChain tools. No database/SQL tool. Retain generic paths unchanged.

First supported outcome `OBPM_ECA_TIMEOUT`: a uniquely current EC/T record, matching ECA request attempt, valid time/correlation and complete queue coverage establish a recorded ECA timeout only. `REQUEST_EVIDENCE` or `ESCALATE` are permitted; `RESOLVE_CASE` is prohibited for all OBPM cases in this milestone. Never infer funds availability, block/posting outcome, beneficiary credit or settlement. Ambiguous, unknown or incomplete evidence yields `INSUFFICIENT_EVIDENCE` with specific requests.

For OBPM live mode, the model returns a typed order containing every mandatory tool exactly once; the service fixes the authorized case ID. Incomplete, duplicate or unknown plans fail visibly without automatic completion or Replay substitution. The model then selects authorized fact IDs when evidence supports a finding; wording and assessment are service/rule owned. Metrics explicitly record `mandatory-evidence-order`. Generic live tool selection remains unchanged. Replay is deterministic with zero chat calls. Facts and evidence IDs include OBPM record identities, not fabricated legacy provider records.

Runbooks are original sidecar `data/knowledge/obpm-runbooks.json`, loaded with the configured knowledge directory, tagged domain OBPM_NEFT, rail NEFT, direction OUTBOUND, releaseFamily14.7 and effective dates. Generic retrieval must exclude OBPM-only policies; OBPM retrieval must enforce domain, rail, direction and release family as well as tenant/date. Old fixture/runbook manifest files remain unchanged.

## UI scope

Existing queue shows newly imported NEFT cases. Add an import-original-sample workflow, import receipts and banking evidence panel with raw codes, references, attempts, coverage, as-of queue age, source identity and evidence version/history. Existing investigate, citations, independent review, audit and export remain usable. Payment native status is separate from case status. Avoid provider/webhook/reconciliation views for OBPM cases. Overview remains explicitly imported-case analytics, not all-OBPM throughput or success rates.

## Ownership during implementation

Root owns Java/schema/API integration and operational validation/docs. Worker agent owns investigator Python and worker tests. UI agent owns apps/web. Data/document agent owns original OBPM sample files, sidecar runbooks and a step-by-step completion guide. Communicate contract changes before edits across ownership boundaries.
