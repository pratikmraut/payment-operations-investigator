# Supplied inquiry responses and connection validation — 15 September 2026

The user supplied actual PO01 date-inquiry and PO02 four-group response examples, and authorized using the supplied URLs in the local application. Endpoint addresses, service identity, native business date, actual payment records and test receipts remain in ignored private runtime files. FLEXCUBE source on D: was inspected read-only; no database package or upstream application was modified.

## Changes

- Activated the supplied fixed PO01/PO02 HTTPS endpoints in the local native configuration. Service identity and explicit bank posting date come from the supplied sanity examples; posting date remains separate from the selected inquiry date. Existing scopes, credentials outside these settings, knowledge/index files and model configuration are preserved.
- Added PO02 provenance v2 for optional DTO properties omitted by the deployed serializer. Missing properties and explicit nulls have separate reversible provenance. Required scope/reference/query metadata and explicit four-array validation remain enforced. Nullable STATUS tuple joins retain the package's semantics.
- Updated both frontend evidence views to accept v2 and display **Not supplied**, source null and blank text separately. Older v1 snapshots remain readable without rewriting stored values or hashes.
- Added safe TLS error codes/messages to both inquiry clients; the frontend displays the returned message without substituting mock records. Normal hostname and certificate trust validation remains enabled.

## Validation

- **163 Java tests passed:** 96 inquiry/adapter/evidence tests and 67 saved-investigation/report tests. The API package build passed.
- **93 frontend tests passed**, including configured-bank discovery results, visible TLS failures and sparse v2 evidence in both views. TypeScript and the native frontend build passed.
- A private offline probe passed the user's original supplied JSON through the adapters and isolated in-memory services: one discovered payment; PAYMENT 1, HOST 1, HISTORY 3, STATUS 0; 22 omitted properties. All 110 mapped field positions preserve original presence/type/value in cited rows. Immutable replay and UNVERIFIED coverage were checked. This probe made no bank/model calls or live case writes.
- Deployed API SHA-256: `9667ec20685a84316b05ef88e51f285f2f1dc9fbed8b66f673d0749b3b3d0168`. Frontend bundle: `index-CICYG50P.js`. Services restarted with an active-job guard and previous-jar/config backups.
- Read-only before/after comparison preserved **10 cases, four evidence versions, eight saved investigations, management state, one original export snapshot and three original export answers**. Model code and knowledge/index hashes were unchanged.

## Live connection result

The server is reachable, but its certificate covers bank DNS names rather than the supplied IP address. A PO02 request using the native JDK's default HTTPS client failed with `SSLHandshakeException: No subject alternative names matching IP address ... found`. The request did not receive an HTTP API response.

After deployment, an authenticated PO01 search through `http://127.0.0.1:5178/api/payment-discovery/search`, the same proxy/backend endpoint used by the frontend, confirmed mode `BANK_API` and returned HTTP 503 with `DISCOVERY_TLS_ERROR`. No fallback records, payment cases, evidence versions or model jobs were created by these live checks. The user's Thunder Client screenshot records their separate successful HTTP 200 call; it does not establish Java TLS compatibility. No TLS setting was changed in Thunder Client or the application.

Live records cannot populate the frontend until the bank provides a hostname covered by the certificate that resolves to this endpoint, or corrects the endpoint certificate to cover its IP. If the replacement hostname uses a private CA, its approved chain must also be trusted by the Java runtime. Do not disable hostname verification or install a trust-all client.

Native browser visual inspection was unavailable; rendered component tests and deployed HTTP checks provide frontend validation for this increment. Private evidence is under `runtime/uat-connection-2026-09-15/` and its `adapter-probe/` subdirectory. Offline response compatibility is verified; a successful live inquiry remains unverified because of the certificate mismatch.
