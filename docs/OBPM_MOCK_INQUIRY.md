# Synthetic NEFT inquiry: implementation and walkthrough

This milestone develops the API boundary while the installed Oracle table mapping is pending. An original local mock service supplies four outbound NEFT examples over HTTP. Java calls that service, converts decimal amounts exactly to paise, validates the evidence, and uses the existing durable importer and investigation workflow. Our application does not connect to an Oracle database.

The mock response is **our proposed `neft-inquiry-v1` contract**, not a verified Oracle/FLEXCUBE endpoint, OBPM 14.7 data dictionary, or bank transaction export. The `SYNTHETIC_MOCK` mode, `SYNTHETIC` classification and demonstration deployment identifiers remain explicit.

```mermaid
sequenceDiagram
    actor Analyst
    participant React as React case workspace
    participant Java as Java application API
    participant Inquiry as Original synthetic inquiry service
    participant Store as Durable case and evidence store
    participant Worker as LangGraph and LangChain tools
    participant RAG as Scoped original runbooks
    Analyst->>React: Enter synthetic payment reference
    React->>Java: POST /api/obpm/inquiries + session and CSRF
    Java->>Inquiry: GET configured inquiry path and scope
    Inquiry-->>Java: neft-inquiry-v1, decimal amount, source coverage
    Java->>Java: Verify reference/scope; convert exact paise; validate evidence
    Java->>Store: Import immutable snapshot or reuse identical version
    Store-->>Java: Case ID, import status, evidence version and hash
    Java-->>React: Confirmed import receipt
    Analyst->>React: Open case and run Replay investigation
    React->>Java: Authorized investigation request
    Java->>Worker: Tenant-scoped stored evidence snapshot
    Worker->>Worker: Four read-only evidence tools and deterministic checks
    Worker->>RAG: Retrieve applicable procedure passages
    RAG-->>Worker: Versioned citations
    Worker-->>Java: Assessment, evidence gaps and proposed case action
    Java->>Store: Persist investigation and original snapshot
    Java-->>React: Findings, citations and reviewable case proposal
```

## Four original examples

| Payment reference | Source amount (INR) | Evidence supplied | Expected supported assessment |
| --- | ---: | --- | --- |
| `MOCK-NEFT-1001` | 18,450.75 | One explicitly current `EC` / `T` queue record correlated with its recorded ECA timeout | `OBPM_ECA_TIMEOUT`; request missing outcome/posting evidence |
| `MOCK-NEFT-1002` | 2,750.00 | A historical timeout and a different, explicitly current `P` record | `INSUFFICIENT_EVIDENCE`; the earlier timeout does not establish the current request outcome |
| `MOCK-NEFT-1003` | 925.25 | Partial queue coverage and an unknown/unmapped state | `INSUFFICIENT_EVIDENCE`; identify the missing or unverified evidence |
| `MOCK-NEFT-1004` | 6,040.50 | More than one record marked current | `INSUFFICIENT_EVIDENCE`; preserve and disclose the ambiguity |

Operational JSON is under [data/obpm/inquiry](../data/obpm/inquiry/). Expected outcomes belong to the acceptance harness, not the inquiry response or worker's source evidence. These four examples exercise integration behavior; they do not measure independent AI accuracy or establish production status-code semantics.

## Use the local dashboard

1. Start the project using the existing [setup instructions](SETUP.md), then open [the local application](http://127.0.0.1:5178/). The configured synthetic inquiry service must be running with the application.
2. Sign in as the local demo analyst. The default local-only credentials are `analyst` / `demo-pass-local`, unless `POI_DEMO_PASSWORD` was changed in your local configuration.
3. In the case workspace, select **Import synthetic NEFT evidence**. Under **Fetch from mock inquiry API**, select or enter `MOCK-NEFT-1001`, then select **Fetch and import**. A successful response gives a case ID, import status and evidence version.
4. Select **Open fetched case**. Confirm the payment reference, INR amount, synthetic source, current queue record, request attempt and coverage. Missing accounting/message evidence is displayed as missing evidence, not a successful posting or delivery.
5. Run an investigation in **Replay** mode. This executes the actual graph, evidence tools and retrieval with zero language-model calls. Inspect the recorded timeout assessment, linked source evidence, retrieved procedure citations and missing-evidence requests.
6. Fetch and investigate the other three references. The historical timeout, incomplete coverage and conflicting current markers should each produce insufficient evidence. Investigations preserve the supplied records so the analyst can understand why the tool abstained.
7. Fetch the same reference again. Identical source evidence returns `UNCHANGED` and reuses the existing evidence version. A new investigation may be run against that version and is saved separately in case history.
8. If demonstrating the review workflow, sign in as the independent demo reviewer after the analyst's investigation. Any approved proposal changes only the application's case workflow. It does not retry, release, debit, credit or execute a NEFT payment.

The existing Ollama mode remains separately available for real local model tool planning and bounded evidence selection. This inquiry acceptance defaults to Replay; it must not be described as a fresh live-model validation unless a separate actual `--mode ollama` run has passed.

## HTTP boundary

`GET /api/obpm/inquiry` requires an authenticated session and describes whether inquiry is enabled, its `SYNTHETIC_MOCK` mode, supported `PAYMENT_REFERENCE` lookup and available example labels.

`POST /api/obpm/inquiries` requires an analyst/reviewer session and its `X-CSRF-Token`:

```json
{"paymentReference":"MOCK-NEFT-1001"}
```

The response uses the existing import receipt: `caseId`, `status`, `caseVersion`, `evidenceVersion`, `evidenceHash`, `importId` and `importedAt`. The frontend sends a reference, not a target URL, SQL query, schema name or database credential. Unknown references return 404 without creating a case; invalid reference shapes or unsupported request fields return 400.

The Java service calls the configured local mock route `/inquiry/v1/neft/payments/{reference}` with explicit reference type, deployment, host and branch scope. The service key and target address stay in backend configuration. They are not browser inputs. The mock returns a decimal string without `amountMinor`; Java owns its exact conversion before passing a normalized `obpm-evidence-v1` snapshot to the existing importer.

## Executable validation

From the project directory, with the application and mock deployed:

```powershell
python tools/obpm_inquiry_acceptance.py --base-url http://127.0.0.1:5178 --mode replay --report docs/validation/obpm-inquiry-http.json
```

If Python is not on `PATH`, use an installed Python executable or the bundled runtime path documented by your local environment. The script uses Python's standard library and the existing HTTP acceptance client.

The script logs into real local sessions, calls the deployed inquiry API, compares persisted evidence with the original HTTP-service fixtures, checks exact decimal-to-paise conversion, duplicate version behavior, rejected-request writes, role/CSRF and tenant boundaries, then runs four actual worker investigations. It also checks the case-list/dashboard API and preserves unrelated case records. It does not reset databases, delete evidence, perform browser actions, or infer an Oracle connection from a synthetic response.

Fixed mock payment identities make reruns reusable. Reruns may append new investigation history to those four synthetic cases. The JSON receipt is written only when the script actually executes and records either `PASS` or `FAIL`; use its execution timestamp and recorded checks as evidence. Component and browser validation are separate from this HTTP receipt.

### Executed HTTP evidence

The actual local run on **12 September 2026 passed 14 checks in 1.419 seconds**, with four saved Replay investigations and **zero model calls**. It created the four mock cases at evidence version 1, fetched each again without creating another evidence version, verified the source decimal mapping and stored source snapshots, and checked the authorization, tenant, worker and dashboard API boundaries described above. See the [actual HTTP receipt](validation/obpm-inquiry-http.json).

The [initial failed run](validation/obpm-inquiry-http-initial.json) is retained. The harness initially expected an unauthenticated POST without a CSRF token to return 401; the existing Spring CSRF filter correctly rejected it first with 403. The harness expectation was corrected and the full run passed. The authenticated read still requires a session and returns 401 to an anonymous client. No application security behavior was changed for this correction.

This receipt verifies the deployed HTTP/worker path. It does not claim browser validation, a fresh Ollama run, a real Oracle connection or broad payment diagnostic accuracy.

## Next steps toward actual inquiry data

The [component receipt](validation/obpm-inquiry-components.json) records 68 successful Java tests, the mock/React checks and tested/running artifact correspondence. The [browser receipt](validation/obpm-inquiry-browser.json) records eight observations of login, inquiry configuration, unknown-reference error, duplicate fetch, case navigation, source facts and procedure citations. The actual inquiry wire contract is [obpm-inquiry.openapi.json](obpm-inquiry.openapi.json); public application endpoints are in [openapi.json](openapi.json).

1. Validate the installed native payment, queue, attempt and correlation mapping using the [inquiry design](OBPM_INQUIRY_API.md) and locally retained schema worksheet. The mock does not establish those mappings.
2. Agree the bank-side response contract and source-coverage semantics using the synthetic examples. A real integration needs explicit release, reference namespace, tenant/branch authorization, source timezone and current-record derivation.
3. Implement a separately reviewed real-data adapter and classification. The current synthetic-only validator must not be bypassed by labelling bank records `SYNTHETIC`, and changing a URL alone does not enable a valid bank integration.
4. Add host records, status history, core blocks/errors, message acknowledgements/confirmations and verified accounting evidence as separately versioned evidence groups. Implement their display and supported rules before claiming broader NEFT diagnosis.
5. Compare an authorized non-production inquiry result against the same payment and observation cutoff in the source system. Enable each interpretation only after its correlation and state semantics are established.

This milestone supports a recorded ECA timeout or explicit insufficient evidence. It cannot establish beneficiary credit, funds availability, posted accounting, settlement success or the complete cause of every stuck NEFT payment. Dashboard totals cover imported cases; bank-wide analytics require a complete, separately scoped population feed.
