# Payment lookup, cases and inquiry evidence

Design proposal prepared 14 September 2026 after the user's Case Queue review. This document specifies the next OBPM integration increment. It is not a report of implemented endpoints or a bank connection. The current synthetic importer and separate private UAT Q&A continue unchanged.

## Product decision

Use one payment workspace with an active investigation case for its operational issue. Questions are children of the case, not new cases. An operator first locates a payment, chooses an investigation reason and opens or resumes its case. A payment that merely appears in a recent-payment query is not automatically an exception.

For the first increment, allow one active case per authorized payment identity and collect multiple related questions in it. A later unrelated incident can become a linked case with its own reason and history. The first case identity must not be derived from an arbitrary question, a host subsequence or the first matching UTR.

The payment identity must include authorized tenant, environment/deployment, source product, reference namespace and verified native key, with bank/branch scope where required. FCR reference and OBPM reference remain separate typed references; link them only through verified correlation evidence. UTR is a lookup attribute whose uniqueness must be established in the selected scope. Do not treat the proposed application key as proof of the Oracle table's key. Duplicate or contradictory payment records require explicit ambiguity handling.

## Proposed operator flow

```mermaid
sequenceDiagram
    actor Operator
    participant UI as React workspace
    participant Java as Application backend
    participant Inquiry as Bank inquiry API
    participant Store as Private case and evidence storage
    participant Model as Existing local question worker
    Operator->>UI: Find payment by native reference or scoped UTR
    UI->>Java: Authorized lookup
    Java->>Inquiry: Read payment candidates
    Inquiry-->>UI: Candidate identities via Java
    Operator->>UI: Select payment and case reason
    UI->>Java: Open or resume case (idempotent)
    Java->>Store: Save case and acquisition state
    Java->>Inquiry: Read selected payment evidence
    Inquiry-->>Java: Separate result groups and collection metadata
    Java->>Store: Save validated immutable snapshot v1
    loop Several questions about the same issue
        Operator->>UI: Ask question
        UI->>Java: Case, question and selected snapshot version
        Java->>Model: Authorized evidence and applicable guidance
        Model-->>Java: Cited answer, unknowns and next checks
        Java->>Store: Save question and answer bound to v1
        Java-->>UI: Answer with source links and observation time
    end
    Operator->>UI: Refresh bank evidence
    Java->>Inquiry: Read the same authorized payment
    Inquiry-->>Java: New observation
    Java->>Store: Save receipt; preserve changes as v2
    Java-->>UI: Differences and earlier-answer labels
```

## Payment lookup and case creation

Add a **Find payment / Open case** action to the Case Queue. Native FCR reference lookup is the first supported selector; UTR lookup returns candidates until uniqueness/correlation is verified. Date-range lookup is a paginated payment finder, not a case generator or a bank-wide analytics feed.

An operator selects a candidate and a reason such as "beneficiary credit confirmation requested", "reported processing delay" or "unexpected status". These are operator reports, not proven outcomes. Record the initial question, owner, source of report, priority with reason, creation time and data classification. Show an existing active case instead of duplicating it. Enforce deduplication transactionally and support an idempotency key for retried creation.

A case may exist while evidence acquisition is pending or fails. Keep acquisition state distinct from case workflow: `PENDING`, `AVAILABLE`, `PARTIAL`, `FAILED`. A failed fetch must leave a visible case and retryable acquisition error, not a fabricated empty successful snapshot or a silent disappearance of the issue.

## Evidence supplied by the first bank API

The API may wrap the four functions already exercised by the user. Keep their results separate:

| Result group | Purpose | Interpretation limit |
| --- | --- | --- |
| PAYMENT | Source identifiers, amount/currency, dates, raw payment/accounting/message states, N10-related observations | A source field or identifier does not independently prove bank-network outcome |
| HOST | Relevant originating-system host rows, subsequences, state domains, errors and references | Preserve all relevant rows; largest subsequence alone is not a verified current-state rule |
| HISTORY | Observed field changes and source dates | Tied dates and missing history do not form a guaranteed total event order |
| STATUS_MAPPING | Matching composite status definition | This is lookup guidance, not a payment event or downstream confirmation |

The current function names are AP_BA_NEFT_PAYMENT_INQ, AP_BA_NEFT_HOST_INQ, AP_BA_NEFT_HISTORY_INQ and AP_BA_NEFT_STATUS_INQ. Their common scope includes PIO_REF_TXN_NO, PIO_ORG_BRN and PIO_ORG_BANK. The API must validate these against caller permissions and fully fetch/close each cursor. Successful function return alone does not prove every cursor row was fetched successfully.

Return metadata outside the row arrays so it also exists for an empty result: request/correlation ID, API contract version, deployment and source release, source timezone, query observation times, section scope, result code, fetch completion, rows returned, source count when actually known, truncation/pagination, consistency mode and sanitized error. Preserve identifiers and exact decimal amounts as strings; currency code mapping and precision validation belong in Java.

Zero records after a completed query means only "no matching records in this source and scope at this observation". A failed query, partial fetch, missing export or unverified completion is different. Future native OBPM, core, message and accounting sections must say `NOT_REQUESTED` or `UNAVAILABLE` when absent rather than masquerading as completed empty results.

Separate SELECT statements normally observe separate committed points in time. A bank-owned transaction strategy can provide a common read snapshot where feasible; otherwise label each section's observation and the consistency limitation. Do not inject transaction-control changes into a shared FLEXCUBE transaction without understanding the caller's lifecycle. Oracle documents the distinction in [read consistency](https://docs.oracle.com/cd/E18283_01/server.112/e16508/consist.htm).

## One case, multiple questions

Each question record needs a question ID, case ID, actor, submitted text, selected snapshot ID/hash/version, status and timestamps. Each answer needs exact source citations, model/provider identity, prompt/guidance versions, reported latency/token usage and any uncertainty. A provider error is a failed question with a retry option, not a case resolution or a prepared answer. Add request idempotency before automatic retries: the same logical request must resume or return its saved result rather than launch duplicate inference. Durable job status would let the UI recover after reload; it is a proposed extension, not a capability of the current synchronous UAT endpoint.

Examples within one case:

- "What changed in the host history?" — source rows and calculated field changes.
- "Did the payment reach OBPM?" — needs a verified FCR-to-OBPM reference bridge and corresponding acceptance/response evidence; a generic FCR message state is insufficient.
- "Was the sender debited?" — requires correlated posted accounting evidence, relevant authorization and reversal checks.
- "Was the beneficiary credited?" — requires appropriate downstream confirmation or beneficiary-side posting evidence, with verified semantics; an outgoing debit is insufficient.
- "What procedure applies next?" — retrieve a versioned procedure applicable to the installed release and actual observed situation; source code showing a possible path does not prove that path executed.

The existing UAT questions are independent requests. For this increment, keep each question self-contained. A future follow-up resolver can make "what happened after that?" explicit using selected prior turns, but prior model prose must never become transaction evidence. Suggested question buttons contain prompts, not hardcoded answers. Reuse the existing local GPU generation path and preserve the CPU demonstration.

## Refresh and review semantics

Record every collection attempt. Separate a collection receipt/time from the semantic evidence content fingerprint: an unchanged refetch can record freshness without reopening the case merely because request ID or collection time changed. Changed records, source coverage, interpretation version or meaningful consistency metadata must produce a versioned evidence update. Define canonicalization and source-row ordering explicitly; do not hide duplicates by sorting/deduplicating blindly.

Old questions retain their original snapshot references. If v2 arrives while a v1 answer is running, preserve the result as a v1 answer and label it earlier evidence. Show a computed v1/v2 difference and offer reassessment. Never overwrite an earlier cited answer to appear current. A newly complete empty source can be a meaningful coverage change even when the visible row set remains empty.

Separate case workflow, evidence acquisition, native payment state and AI-answer/review state. Asking a question does not automatically send the case to review. An analyst submits a reviewable summary with selected findings, evidence links and outstanding issues. A separate reviewer checks it against the current case/evidence version; evidence changes invalidate pending approval eligibility. Closure needs a recorded case reason and supporting evidence; it is not proof of beneficiary credit or permission for a bank action. The existing synthetic OBPM resolution prohibition remains until a separately verified real-case review design is implemented.

## Queue and case page additions

| Surface | Proposed fields/actions |
| --- | --- |
| Case Queue | Find payment/open case, case reason, native reference and UTR, source/environment, bank/branch, direction/rail, owner, priority, case state, evidence availability, last successful observation, unanswered questions, case age |
| Payment header | Exact amount/currency, verified source identity and reference bridge, native raw states with scoped mappings, creation/value dates |
| Evidence tab | Separate source groups, row-level citations, coverage, missing sources, refresh receipt and v1/v2 differences |
| Questions tab | Several questions under the same case, pending/answered/failed state, answers with citations and selected snapshot version |
| Review tab | Analyst summary, selected findings, requested checks and independent review tied to current versions |
| Audit tab | Who created the case, acquired/refreshed evidence, asked each question and reviewed it |

Case age starts at case creation. Payment age starts at the source payment timestamp. Queue/hold age requires a verified queue entry time/current-record rule. Do not invent an SLA breach threshold or equate these ages. Real customer/deployment labels must come from configured authorized identities, not treating the demo Northstar/Silverline names as bank schema owners.

## Implementation sequence and acceptance

1. **Unify one private export with a case.** Add a separate UAT/private case domain and attachment to existing snapshots. Reuse the current authorized export without relabelling it synthetic. Preserve the generic demo and its model code.
2. **Add case questions.** Reuse existing GPU Q&A with case-level ownership, snapshot binding and question history. Demonstrate three different questions in one case, with sources and explicit unknowns.
3. **Add evidence refresh and review linkage.** Compare two observations, retain earlier answers, handle failed/partial/unchanged fetches and reject stale review attempts. Use original synthetic fixtures for automated tests; keep UAT source records private.
4. **Connect the bank inquiry adapter.** Accept the agreed response contract, scope/authentication, timeout and complete-fetch semantics. The application Java service calls the bank API; React and the model receive no DB credentials or arbitrary SQL capability. Do not weaken the existing local-mock allowlist to enable this path.
5. **Extend native evidence sources.** Add verified FCR/OBPM correlation, native queue/attempts, message confirmation and accounting/reversal evidence per question need. Validate conclusions against the authorized UAT inquiry screens.
6. **Add controlled automation later.** Only create automatic exception cases from verified status rules, configured age thresholds and complete population coverage. Idempotency, clear trigger reasons and separate case-vs-payment metrics are required.

Acceptance includes two users opening the same payment concurrently without duplicate active cases; cross-tenant reference denial; same question on changed evidence using the correct snapshot; unknown status mappings; empty-complete vs failed sections; repeat HTTP requests; refresh during inference; preserved old answers; stale-review rejection; and questions that cannot establish downstream credit. Existing model factual-validation failures remain relevant until new semantic evaluation demonstrates improvement.

## Supplied recent-payment SQL

The user's query is useful for discovering candidate references. It is not full evidence and does not identify a stuck payment. `ROWNUM <= 20` in the same query block as `ORDER BY` limits rows before the final ordering, so use an ordered inner query and an outer row limit. Oracle documents this [top-N pattern](https://docs.oracle.com/en/database/oracle/oracle-database/21/sqlrf/ROWNUM-Pseudocolumn.html).

An n-to-h join can repeat a payment for several host rows and count each repeat against the 20-row limit. For a payment picker, use a scoped `EXISTS` to avoid host fan-out, then fetch all relevant host rows for the selected payment. The package already uses a scoped EXISTS for its payment result. Do not use MAX(REF_SUBSEQ_NO) or DISTINCT as a substitute for verifying record identity and current-state semantics.

The local read-only SQL worksheet at `runtime/obpm-uat/sql/12_payment_case_discovery.sql` contains both a corrected joined diagnostic and a preferred payment-candidate query plus a selected-payment host lookup. It has not been executed against UAT. Actual uniqueness, timezone, permissions, execution plan and keyset pagination remain deployment checks. `TRUNC(SYSDATE)-7` starts at midnight seven days ago; choose explicit half-open source-local start/end bounds for the API. A recent initiation-date sample can miss older unresolved payments; later monitoring needs verified status/change feeds and complete pagination.

## What exists today

Implemented: synthetic versioned case import/review; private staged UAT evidence; dynamic saved local-model answers; GPU runtime. Still proposed here: real/UAT case domain, case-linked freeform questions, live bank inquiry adapter, asynchronous durable question jobs and native downstream outcome sources. The saved UAT answer list currently retains up to 50 results per snapshot and is not a full case conversation service. This design does not claim the bank API has been deployed or that a model's citation membership proves its reasoning correct.
