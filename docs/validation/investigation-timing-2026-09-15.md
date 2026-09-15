# Payment investigation timing validation — 15 September 2026

## Implemented behavior

The shared payment case/Evidence Q&A workbench starts a monotonic browser stopwatch before submitting a valid question. It follows the submitted job independently of which historical investigation is selected. A terminal status freezes the displayed wait; unconfirmed submission/polling errors remain explicitly uncertain. Invalid requests stop with a rejection label. Refreshing an existing pending job uses a qualified server-timestamp estimate. Timer ticks do not issue network requests or regenerate evidence context.

Java persists request-entry time and stage timings for new jobs. Summary/detail/replay expose the same timing contract. Older jobs derive available intervals from existing timestamps without rewriting saved bodies or summaries. The existing model-reported generation duration remains separate. See [the timing contract](../INVESTIGATION_TIMING.md).

## Executed checks

- **67 Java tests passed:** investigation service (25), investigation controller (4), report service (20), report controller (5), PDF renderer (13). Deterministic clock/worker fixtures cover preparation, queue, processing, completion, rejection, timeout, access failure, interrupted recovery, replay and legacy byte preservation. They test timing and compatibility; they do not measure model speed.
- **66 frontend tests passed:** CaseInvestigation, investigationTiming, EvidenceQuestions and PaymentCaseDetail.refresh. Coverage includes a deferred submission, independent historical selection, terminal/failed/unknown status, request rejection, case reset, timer cleanup, clock changes, no per-tick requests and saved server totals separated from model duration.
- TypeScript passed. The native Vite build passed and produced `index-55gbejnK.js` and `index-BwsXWQsw.css`.
- The tested Java package SHA-256 is `9717b9f3ca8ad02623a3c9fa998abad4a793fea823a85b84a038d575b0adfbad`.

An initial sandbox Maven test compilation could not resolve the compiled application classes. Repeating the affected suites using JDK 17.0.16 and normal local permissions passed. Browser visual verification was unavailable for this increment: Computer Use stopped because it could not reliably identify the browser URL. No further browser input was attempted; component tests verify the rendered states.

No bank or model call is required for this change. Saved answers, citations and model configuration are outside its write scope. Private runtime preservation and deployment receipts are under ignored `runtime/investigation-timing-2026-09-15/`.

## Local deployment

The tested JAR and native frontend were deployed on port 5178. Root and Evidence Q&A routes served the new asset version; proxied API health, worker health and Ollama version checks returned HTTP 200. All seven existing investigations now expose timing matching their saved timestamps. Before/after comparisons preserved ten cases, four evidence versions, seven investigations, management records, the original export snapshot and three export answers, excluding only additive timing in read responses. Protected guidance, embedding index, runtime configuration and original model code hashes were unchanged. No new investigation, bank request or model generation was made during deployment verification.
