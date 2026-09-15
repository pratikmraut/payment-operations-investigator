# Oracle field-mapping checklist

This is the next implementation input after the synthetic milestone. The user will implement an inquiry API that can read FCR, FCUBS and OBPM schemas. Our Java backend will consume that API. See the [API plan and flow](OBPM_INQUIRY_API.md); our application will not connect directly to Oracle DB.

Read-only source inspection verified Retail and FCUBS candidate tables in an older FLEXCUBE source branch. Detailed identifiers, joins and source links are recorded in the ignored local `runtime/obpm-inquiry-design/VERIFIED_SOURCE_MAPPING.md`, alongside a metadata-only SQL worksheet. These are source-backed candidates, not confirmation of installed OBPM 14.7 objects. The supplied Confluence spreadsheet preview provides logical OBPM labels but does not establish physical table names or release compatibility.

| Evidence group | Fields we need from the approved source | What to verify |
| --- | --- | --- |
| Deployment scope | Exact OBPM release, host, branch and permitted tenant/deployment identity | Which identity constrains every inquiry/view query; avoid cross-host reference collisions |
| Payment | Native payment reference, NEFT/outbound discriminator, INR amount as decimal, activation date, creation timestamp, raw transaction status | Reference uniqueness within the source scope; correct currency scale; timezone and raw status meanings |
| ECA queue | Queue reference, payment reference, request-attempt reference, raw queue/response codes, entry/exit times, current-record indicator, observation timestamp | Current versus historical records; whether EC/T describes this attempt; uniqueness and history retention |
| ECA requests | Attempt/reference ID, payment reference, request time, explicit timeout time if available, external-core final outcome if actually observed | Reliable correlation across every request; distinguish missing response from a negative outcome |
| Coverage | Exact query filters, extraction cutoff, pagination/end-of-results signal, per-source unavailable/partial reason | Complete for this payment and declared interval; no truncated history labeled complete |
| Later: messages | Native message reference/type, payment/bundle links, acknowledgement type and timestamps | Separate transport/SFMS acknowledgements from beneficiary credit confirmation; v1 currently rejects populated message arrays |
| Later: accounting | Instruction/posting references, response/status, amount/currency and reversal links | Separate an instruction from confirmed posting; v1 currently rejects populated accounting arrays |

For each normalized field, record: source product/release; sanctioned endpoint or approved view; source field; join/correlation rule; raw type; allowed null meaning; enum definition; timestamp timezone; query scope; and document/version used to confirm it. Names alone are insufficient if their correlation or status semantics are unknown.

## Steps after release confirmation

The [mock inquiry milestone](OBPM_MOCK_INQUIRY.md) can already exercise HTTP retrieval and the existing ECA investigation with original dummy data. The steps below concern the eventual real bank API and richer source evidence, not prerequisites for running the local mock.

1. Confirm physical objects, keys and reference transformations in the installed versions; complete the native OBPM queue/request mapping.
2. Implement the user's bank-side inquiry API using fixed, parameterized queries across the relevant schemas. Return payment, queues and request attempts with per-section coverage; the API owns database access.
3. Implement an original Java API consumer that maps those responses to a new deployment profile and versioned normalized contract. Do not relax the personal demo's `SYNTHETIC` restriction to disguise real records as samples.
4. Reconcile a bounded test payment at each source screen against its normalized records. Check decimal conversion, missing values, attempt correlation, cutoff and pagination before investigation.
5. Add approved deployment/runbook scope and tests, then connect ingestion. Test duplicate snapshots, new attempts, stale proposals, access boundaries and source outages.
6. Add message/accounting types and domain rules as separately documented milestones. Bank-wide analytics requires its own population and denominator coverage; imported exception cases cannot provide those measures.

Codex can implement the API consumer and tests once the inquiry contract and test environment are established. The user owns bank-side API queries and Oracle access. See the [working synthetic contract](OBPM_IMPLEMENTATION_CONTRACT.md) and [user walkthrough](OBPM_STEP_BY_STEP.md).
