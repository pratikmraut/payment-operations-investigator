# Walk through the synthetic OBPM NEFT milestone

Updated 12 September 2026. **The synthetic milestone is implemented and deployed locally.** Its validation includes 52 Java, 280 worker and 44 React tests; 14 actual HTTP Replay checks; 14 actual HTTP Ollama checks; and 17 generic regression checks. The live NEFT run used three original synthetic investigations, four actual `qwen3:4b-instruct` chat calls and 46.845 seconds. See [milestone evidence](validation/obpm-milestone.md), [component correspondence](validation/obpm-components.json), [Replay](validation/obpm-http-replay.json), [Ollama](validation/obpm-http-ollama.json) and [generic regression](validation/obpm-legacy-regression.json). No Oracle connection was used.

The importer, NEFT evidence tools, rules, scoped runbooks, version history and interface are available. No transaction-data preparation or coding is needed for this synthetic walkthrough. The browser walkthrough passed 16 checks, followed by a separate three-check label/bundle/state recheck; [the browser record](validation/obpm-browser.json) distinguishes those revisions and limitations. Start with Replay, then use the verified Ollama path in step 5. The user will separately create the bank-side inquiry API for the future integration, following the [inquiry API design](OBPM_INQUIRY_API.md).

## 1. Open the application and choose the first sample

Open <http://127.0.0.1:5178> and sign in as `analyst`. The local demo password is `demo-pass-local` unless it was changed in the local configuration. If the application is stopped, follow [SETUP](SETUP.md) or the VS Code workspace task `POI: Start application (Docker)`.

The original first payment has already been imported in the local analyst tenant as [OBPM-01267fa9f71b0442424f74c232386c8f](http://127.0.0.1:5178/cases/OBPM-01267fa9f71b0442424f74c232386c8f), with evidence version 1 and a Replay investigation awaiting review at handoff. Open it to continue. Check **Evidence versions** before repeating a sequence: this is a persistent application, so earlier walkthrough activity remains saved.

To inspect the input or demonstrate import, return to the case queue and click **Import synthetic NEFT evidence**. In **Synthetic sample**, select **eca timeout**, the display title derived from `eca-timeout.json`. Inspect the **Snapshot JSON** preview. The payload uses `schemaVersion: "obpm-evidence-v1"` and `dataClassification: "SYNTHETIC"`; the older illustrative wrapper in `docs/examples` is not an import payload.

The sample describes payment `DEMO-NEFT-0001`, INR 12,500.00, with source identity `SYNTHETIC-OBPM` / `DEMO-HOST` / `DEMO-BRANCH`. It contains one current ECA queue record with raw code `EC`, response `T`, and a matching request attempt. The exact maintenance release and native payment status are unknown.

Click **Import snapshot**. A previously unseen payment returns **Created**, evidence version 1 and a server-generated case ID. The already imported first sample returns **Unchanged** while it is still the latest snapshot. Use **Open imported case**; the case ID differs from the source payment reference. Receipts appear under **Recent imports**. The authenticated server session determines the tenant.

Reimport the exact same snapshot before proceeding. It should return **Unchanged**, with the same evidence version and case identity. This is the duplicate-import demonstration, not another payment.

## 2. Inspect the evidence and investigate in Replay

Open **Bank evidence** and inspect the source, native codes, request/queue references and timestamps. Then open **Source coverage** and **Evidence versions**. Queue coverage is complete only for this payment and the declared interval through 05:20 UTC. At that cutoff, the queue age is 19 minutes; it is not an elapsed clock measured from your present time. No SLA threshold is configured by this sample.

Messages are **Not requested**. External-core responses and accounting records are **Unavailable**. An empty group with these labels is not proof that no external event happened. Banking evidence is separate from the legacy provider/capture/refund views.

Select **Replay**, then **Run investigation**. After an earlier result, the button reads **Run another investigation**. A suitable question is:

> Explain the current recorded hold, identify the supporting queue and request evidence, and request the missing information needed before any recovery recommendation.

The supported first-sample outcome is `OBPM_ECA_TIMEOUT`, with a request for further evidence. It means the snapshot records an ECA timeout. It does not establish insufficient funds, an absent amount block, posting failure, beneficiary-credit failure or settlement. Replay runs the actual scoped graph and retrieval with zero chat-model calls.

Inspect the findings, cited evidence IDs, tool trace, missing-evidence requests and original scoped runbooks. The two document IDs are `RB-OBPM-ECA-TIMEOUT` and `RB-OBPM-EVIDENCE-GAPS`. They are original demonstration guidance informed by public Oracle documentation, not an approved bank operating procedure.

Oracle's documented timeout-specific Resend condition is background guidance, not permission for this app to resend. Native ECA Retry has different prerequisites. Neither **Run investigation** nor case approval executes either operation. This milestone prohibits `RESOLVE_CASE` for all OBPM cases.

## 3. Demonstrate new evidence and stale review

Leave the first investigation awaiting review. In the import selector, choose **eca timeout updated** (`eca-timeout-updated.json`). It keeps the same source/payment identity but has a later cutoff of 05:40 UTC. Importing it after the first snapshot returns **Updated**, with a new evidence version, an increased case version and an **Open** case awaiting a fresh assessment.

The updated snapshot preserves the old timed-out request and marks its queue record exited. A distinct current request is pending with response `P`. Its as-of queue age is 15 minutes. The sample records a second request but does not identify which operation or actor caused it; do not infer that the app or an analyst executed Resend.

Check **Evidence versions**, the earlier investigation and **Audit trail**. Previous results retain their original snapshot. The old proposal is no longer reviewable against the changed evidence. The interface can prevent the action before sending it; the server independently rejects a stale submission, including one that supplies the new case version. The [actual HTTP checks](validation/obpm-http-replay.json) verified that behavior on a separate original synthetic payment identity.

Run a fresh Replay investigation. The current `P` record does not inherit the historical `T` conclusion. The implemented rule returns `INSUFFICIENT_EVIDENCE` and requests evidence. It cannot establish the final external outcome.

Sign out and sign in as `reviewer`. Review the fresh proposal, enter a note and approve or reject it using the normal controls. The reviewer must differ from the investigation creator. Approving `REQUEST_EVIDENCE` changes the case to **Needs evidence**, not the payment's native state. Inspect the recorded decision, audit and export.

After importing the later snapshot, the earlier changed snapshot is intentionally too old to import again. On an already used demo, inspect the saved versions and earlier results, then run a fresh investigation of the latest evidence. A new, independent versioning demonstration needs another generated original sample pair; Codex can prepare that pair without asking you to edit JSON or reset the database. Preserve the existing case history.

## 4. Demonstrate incomplete and unknown input

Select **evidence gaps** (`evidence-gaps.json`) and import it. This describes a separate payment, `DEMO-NEFT-0002`, for INR 875.25. It contains a deliberately unmapped test response `DEMO_UNMAPPED`, partial queue history and a null queue entry time. That invented value is not a claimed Oracle status.

Replay returns `INSUFFICIENT_EVIDENCE` with specific requests to resolve the observed gaps, including complete queue history and verified native-code meanings. Queue age is unavailable, not zero. The dashboard describes imported cases; the three catalog snapshots represent two distinct payments. Other acceptance and browser test cases can also appear in the persistent queue. These totals are not bank-wide throughput or a payment success rate.

## 5. Run the verified live-model path

Select **Ollama** and run another investigation. For OBPM, the model orders all four mandatory evidence tools; the service validates the complete permutation and supplies the authorized case ID. Omissions, duplicates and invalid orders are rejected, without filling in missing tools. When evidence is sufficient, a second actual call selects an authorized fact ID. The service supplies its exact text and links; rules own the assessment and proposal.

The [completed live HTTP run](validation/obpm-http-ollama.json) selected `FACT-OBPM-ECA-TIMEOUT` for the complete timeout snapshot, linked to its current queue and matching request evidence. The changed pending snapshot and incomplete snapshot each used one planner call, returned `INSUFFICIENT_EVIDENCE` and had no findings. That is four chat calls across three investigations; the two insufficient cases intentionally skipped fact selection.

The [initial failed planner attempt](validation/obpm-ollama-initial-failure.json) remains preserved: it omitted payment identity, so rules safely abstained on otherwise sufficient input. The corrected contract constrains tool coverage; it does not demonstrate that AI discovered a new diagnosis. The model has no Oracle credentials, arbitrary SQL or payment-action tool, and provider failures remain visible. These are small synthetic workflow checks, not independent NEFT accuracy, representative latency or measured business benefit.

## What is completed and what remains

For the newer API-driven dummy-data workflow, use [Fetch from mock inquiry API](OBPM_MOCK_INQUIRY.md). It supplements the manual snapshot demonstration above and uses the same ECA investigation rules.

- [x] Three original input snapshots and two scoped original runbooks.
- [x] Java validation of exact amounts, authenticated tenant assignment, import receipts and immutable evidence versions.
- [x] Real LangGraph execution using four OBPM LangChain tools and eligible NEFT runbooks in Replay and actual Ollama mode.
- [x] Unknown-source handling, historical-timeout separation, stale review rejection and independent case decisions.
- [x] React import/evidence/history views, plus actual HTTP audit and export checks.
- [x] Corrected mandatory-tool ordering, actual live fact selection/abstention, and recorded browser workflow plus separate label recheck.
- [x] Preserved generic investigation behavior and recorded selected source/tested/running-artifact correspondence.
- [x] Set the integration boundary: this application consumes the user's inquiry API; that bank-side API can query FCR, FCUBS and OBPM.
- [ ] Confirm the exact Oracle release, validate the bank-side mappings and implement/test the inquiry API and application client in the authorized environment.

## What is needed for a real sandbox later

The remaining deployment-specific inputs are:

1. The exact installed OBPM maintenance release and relevant enabled features.
2. A validated bank-side mapping for the FCR, FCUBS and OBPM evidence exposed by the inquiry API: correlation keys, native codes, timestamps, scope filters and external-core evidence.
3. The inquiry API request/response contract, endpoint and authentication method configured inside an authorized non-production environment, plus approved local runbooks.

Those details can be validated inside the approved organization environment. Authorized read-only source inspection and an in-browser mapping-spreadsheet preview have informed the design. They do not establish compatibility with the installed 14.7 maintenance release. Proprietary source bodies and transaction data were not imported into the public application fixtures; internal identifiers and review notes remain outside this public walkthrough.

The user will implement the inquiry API inside FLEXCUBE. Codex can implement and test this application's Java API client and evidence adapter once the contract is established. Database connections stay behind the bank-side API. Keep credentials and employer/customer records inside the approved environment. The current fixtures were written originally; they are not Oracle exports or extracted product code. The existing importer accepts only synthetic evidence. An explicit contract extension and source-to-response validation are required before the application accepts approved bank evidence; real records must not be relabeled synthetic.

## Sample validation and references

From the project root:

```powershell
python tools/generate_obpm_samples.py --check
```

This command checks byte-for-byte regeneration plus original sample money, chronology, correlation and coverage invariants. It makes no network or model calls. The [sample README](../data/obpm/README.md) provides the local Windows environment command. [Milestone validation](validation/obpm-milestone.md) separates generator, component, HTTP, model and browser evidence, including the documented local-image build limitation. Matching selected tested/running artifacts does not establish whole-image reproducibility.

- [Milestone implementation contract](OBPM_IMPLEMENTATION_CONTRACT.md)
- [Bank-side inquiry API design](OBPM_INQUIRY_API.md)
- [Input/output blueprint](OBPM_INPUT_OUTPUT_BLUEPRINT.md)
- [Original sample directory](../data/obpm/samples)
- [Original scoped runbooks](../data/knowledge/obpm-runbooks.json)
- [Oracle 14.7 External Credit Approval Queue](https://docs.oracle.com/en/industries/financial-services/banking-payments/14.7.0.0.0/obpeq/external-credit-approval-queue.html)
- [Oracle 14.7 N10 credit-confirmation semantics](https://docs.oracle.com/en/industries/financial-services/banking-payments/14.7.0.0.0/innug/credit-confirmation-ack-message-n10-processing.html)
