# Payment Operations Investigator: a six-minute demo

This walkthrough demonstrates original synthetic payment cases, evidence review and backend controls. Budget approximately six minutes, with an extra minute for questions. The interactive walkthrough uses **Replay**, which executes the actual LangGraph workflow and LangChain tools with deterministic synthesis and zero generative-model calls. The time budget is a presentation plan, not a measured runtime guarantee.

Check [current delivery and model status](STATUS.md) immediately before presenting. Model development, container integration and validation can change independently. This script does not claim a fast or fully grounded live-model experience. Use the separate model evidence only with its actual configuration, timestamps, failures and limitations.

The [current V6b live acceptance](validation/acceptance-compose-ollama-v6b.json) passed 17 full-stack checks in 59.869 seconds for two investigations/four chat calls. Its [exact results](validation/v6b-live-acceptance-results.json) separate selected fact IDs, service wording and rule-owned assessment. The [hybrid refund investigation](validation/live-hybrid-java-v6b.json) and [actual browser reviewer journey](validation/frontend-browser-v6b.json) have separate evidence. These recorded runs are not a timing guarantee for a new presentation. This walkthrough remains replay-based.

## Prepare before the meeting

1. Start the already-verified local configuration using [SETUP.md](SETUP.md), and open `http://127.0.0.1:5178`. Do not launch Compose over native services using the same ports.
2. Check API/worker availability and that no model investigation is already occupying the serial worker. Health alone does not establish model or vector-store readiness; see [OPERATIONS.md](OPERATIONS.md).
3. Confirm the selected retrieval configuration. Replay with lexical retrieval requires no model. Replay with hybrid retrieval still depends on embedding/vector services. Keep the prepared configuration stable during the demonstration.
4. Verify the three Northstar cases are present: `CASE-1031`, `CASE-1001`, `CASE-1041`. They are development examples, not the held-out evaluation set. Existing investigations and decisions may remain from prior rehearsals; do not reset the database or delete audit history.
5. Prepare the Northstar `analyst` and `reviewer` identities. The local password is `demo-pass-local` unless configured otherwise. Sign in as analyst first. Use an independent reviewer for the approval step.
6. Keep this script, [architecture](ARCHITECTURE.md) and [validation evidence](PORTFOLIO.md#evidence-behind-the-project-claims) available in separate tabs. Have a saved, clearly dated replay investigation ready if the environment becomes unavailable.

This script describes presenter actions; writing it did not execute or record a new three-case demo. Current [browser evidence](validation/frontend-browser-v6b.json) and [actual screenshots](screenshots/README.md) complement the separate [initial recorded evidence](validation/2026-09-12-foundation.md) and [production Compose integration evidence](validation/2026-09-12-integration.md). The checked-in three-case walkthrough is not a live-model acceptance report.

## 0:00–0:35 — Establish the problem

Show the case queue and say:

> “This is a payment-operations investigation workbench. An analyst traces a payment exception through events, ledger records and versioned operating guidance, then an independent reviewer decides the case action. The records are synthetic; no button here moves money.”

Point out the Unresolved cases KPI, priority filter and search. Briefly distinguish urgency from evidential confidence: case priority is operational triage; the result's “Evidence” label is a categorical assessment, not a calibrated probability.

## 0:35–2:10 — CASE-1031: a refund posting exception

Search for `CASE-1031` and open **Payout and ledger totals differ**. Use Timeline and Evidence to show the processor refund confirmation, ledger postings and snapshot cutoff.

The checked-in fixture has these facts; inspect the current case before quoting amounts:

| Fact | Fixture value / reference |
| --- | --- |
| Capture | INR 24,452.79; `LED-1031-1` |
| Fee | INR 427.92 deduction; `LED-1031-2` |
| Ledger net | INR 24,024.87 |
| Confirmed processor refund | INR 6,113.19; `EVT-1031-5`, reference `RF-1031` |
| Reported processor payout | INR 17,911.68 at its cutoff |
| Ledger minus processor payout | INR 6,113.19 |
| Missing posting evidence | No refund ledger entry; `EVT-1031-6` reports pending posting acknowledgement |

Say:

> “A refund request alone is insufficient. Here, the processor confirms the refund under the same reference, while the ledger snapshot lacks its posting. The exact difference equals that refund. Java calculates the money facts using integer minor units; the explanation does not invent or calculate a refund.”

Select **Replay**, keep the default investigation question, and click **Run investigation** or **Run another investigation**. Wait for the actual result. Expected behavior for this fixture: `MISSING_REFUND`, proposal `ESCALATE`, and the case moves to `AWAITING_REVIEW`.

Open a linked evidence record and the refund source citation, normally `RB-REFUND:v1`. Show its version and excerpt:

> “The event and ledger records establish what happened. The runbook explains the proposed operating response. A policy citation is not proof that a payment event occurred.”

The guidance is original simulated policy, not an Oracle, bank or payment-network runbook. Show the **Replay · no language model** badge. Leave the proposal for the reviewer step.

## 2:10–3:00 — CASE-1001: timeout does not establish failure

Return to the queue, search `CASE-1001`, and open **Payment request timed out**. Show the sequence:

- `EVT-1001-2`: processor success under `PRV-1001`.
- `EVT-1001-3`: the client response subsequently times out.
- One matching capture and fee; ledger net and provider payout both INR 14,933.84.

Run Replay. Expected result: `TIMEOUT_AFTER_SUCCESS` and proposed `RESOLVE_CASE`, supported by the observed capture and `RB-TIMEOUT:v1`.

The known zero discrepancy is required, not incidental. Java rejects a new resolution proposal without its own valid-money and available-payout facts. An otherwise supported timeout, duplicate-delivery or ordering pattern with missing payout or a nonzero discrepancy requests evidence instead; a sole supported confirmed missing refund still escalates.

Say:

> “The transport failed to deliver a timely response, but the processing evidence establishes a successful capture. The proposal resolves the investigation case. It does not submit a second payment, refund or debit.”

Do not approve this second case during the timed walkthrough; the first case will demonstrate independent review.

## 3:00–3:50 — CASE-1041: request evidence instead of guessing

Search `CASE-1041` and open **Payment status requires confirmation**. Show the timeout, unavailable processor lookup, empty ledger and absence of webhook confirmation.

Run Replay. Expected result: `INSUFFICIENT_EVIDENCE`, `Evidence: Insufficient`, and proposed `REQUEST_EVIDENCE`. Open the missing-evidence list and, when shown, `RB-UNCERTAINTY:v1`.

The current rule-owned checklist asks for an authoritative processor status with its observation time, confirmation that the empty ledger snapshot is complete through a stated cutoff, and a delivery-history check with occurrence/receipt timestamps. It asks whether additional records exist rather than assuming a capture, posting or webhook is missing. Older saved investigations retain their original generic checklist; run a fresh investigation to demonstrate the current behavior.

Say:

> “This case also has a timeout, but it lacks the independent confirmation seen in CASE-1001. An empty ledger and unknown provider status do not prove failure. The system asks for correlated processor status and a ledger extract with its cutoff.”

The raw simulated provider snapshot contains zero placeholders with status `UNKNOWN`; Java's reconciliation represents unavailable provider totals as unknown rather than accepting those placeholders as verified zero money. If that field is not visible in the main UI, explain it using the authorized export or backend contract after the timed demo.

## 3:50–5:05 — An independent reviewer approves CASE-1031

Sign out, sign in as **reviewer**, search `CASE-1031`, and open its latest analyst-created investigation. Confirm the creator, evidence, proposal and current `AWAITING_REVIEW` state.

Enter this review note:

> “Checked processor refund RF-1031, the confirmation event and ledger cutoff. The missing refund posting explains the INR 6,113.19 difference. Escalate for posting review.”

Click **Approve proposal**. Show the resulting `ESCALATED` case status and the Audit trail entry with reviewer identity and note. Open **Export case** if time permits; it exports authorized synthetic evidence and workflow history.

Say:

> “The reviewer approves the stored proposal. Java checks the role, tenant, creator separation and exact case version. The decision, status update and audit record commit together. Identical command retries use a stable idempotency key, so a lost response need not create another decision.”

If the proposal is stale or already reviewed, refresh and inspect its history. Use a new analyst investigation and then return as reviewer; never change roles through request headers or force a stale version. If time is short, show the earlier recorded decision explicitly as saved evidence.

## 5:05–6:15 — Explain the architecture and evidence

Open Tool trace for a saved replay result and point to actual tool records and zero model calls. Use this compact explanation:

> “React handles the workbench. Spring Boot owns sessions, CSRF, tenant authorization, exact reconciliation and durable case decisions. Python runs a five-node LangGraph with four snapshot-scoped LangChain tools. Retrieval filters policy versions by tenant and effective date before supplying context. Replay uses deterministic synthesis. In Ollama mode, the model selects diagnostic tools and relevant fact IDs; the service supplies their exact wording and source links. Evidence rules supply the assessment and proposed action.”

Show [architecture](ARCHITECTURE.md) and distinguish the two storage responsibilities: Java's H2/PostgreSQL business records and the worker's SQLite graph checkpoints. The optional hybrid retrieval path uses real Nomic embeddings with pgvector; do not label the current result semantic retrieval unless its recorded mode and evidence establish that path.

Close with measured scope:

> “I generated 60 synthetic cases across six scenario families and two tenants. Replay matched expected outcomes and actions on 30 held-out template variants. That checks this deterministic workflow; it is not a claim of real-world AI accuracy. Independent HTTP, persistence, security and browser checks cover the application boundaries.”

If asked about the live model, open [current model status](STATUS.md). Show recorded evidence with the actual mode, duration and coverage limits. Do not start a model call within this six-minute plan or present replay output as model inference. Current results separate rule assessment, AI fact selection and service wording/links. When evidence is insufficient, the model planner runs while fact selection is intentionally skipped; the UI explains that distinction. Historical generated explanations retain their original labels. Matching the rule-derived outcome does not demonstrate independent model diagnosis; bounded fact selection still depends on catalog correctness, source completeness and relevant selections.

## If the demo does not follow the happy path

| Observation | Honest presenter response |
| --- | --- |
| Investigation is pending | “The service has not returned a result.” Show the pending state; do not narrate invented intermediate reasoning. |
| Gateway/worker/model error | Show the error. Refresh saved history before deciding whether to rerun. Explain that disconnect does not prove downstream cancellation. |
| Existing result appears during a new request | Point to its “previously saved” label and timestamp. Keep it distinct from the pending run. |
| Wrong or absent citation | Open the available evidence and state the gap. Existence of a citation ID is not proof of sentence-level support; do not silently replace or describe it as fully grounded. |
| Reviewer gets 403/409 | Explain the identity, creator or version constraint; inspect the current record rather than bypassing the control. |
| Environment cannot recover within the slot | Show the dated saved result and validation record as a recorded walkthrough, clearly distinguished from a new execution. Follow [OPERATIONS.md](OPERATIONS.md) after the meeting. |

An optional seventh minute can show the viewer role, the second tenant's scoped queue, keyboard-accessible citation dialog, or the baseline comparison. Choose one; the primary demonstration is evidence → proposal → independent review → audit.
