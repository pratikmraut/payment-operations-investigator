# Payment discovery validation — 14 September 2026

Follow-up presentation correction: the Authorized branch dropdown now shows `Branch 1352`, without repeating the bank code. The existing bank-switching test passed (one selected test), the frontend production build passed, and the running browser showed the corrected label. The updated served JS is `index-Cf7BEZnm.js`; the earlier artifact hashes below describe the preceding four-parameter milestone. No backend change was required.

The four-parameter implementation is deployed at `http://127.0.0.1:5178/`. The date in this report is local Asia/Kolkata; UTC receipts can show 13 September. Inquiry mode is **MOCK**, with ESAF example bank code **760**. The user-supplied bank URL was not contacted.

## Delivered behavior

- Root **Payment cases** offers FCR/UTR lookup, XLSX upload and inquiry listing.
- All paths select a configured bank and branch. The list API receives `orgBank`, `orgBranch`, `inquiryDate`, `recordCount`; reference lookup adds app-only reference filters. Bank/branch authorization is checked server-side.
- Exact references/amounts are preserved. Duplicate host subsequences are grouped under a payment. Ambiguous UTR matches remain separate choices.
- Selecting a candidate and entering a reason creates or resumes a persistent discovery-only case. Retrying or concurrently creating the same scoped case does not create duplicates.
- Existing demo cases and private UAT Q&A remain separate. New payment cases explicitly identify the four detailed inquiry outputs as not yet acquired.

See [operator instructions, API contract and flow](../PAYMENT_DISCOVERY.md).

## Executed checks

| Check | Result |
| --- | --- |
| Java Docker build/package | 128 tests, zero failures/errors/skips; includes 35 discovery/client/controller/workbook tests |
| Final affected React tests | 35 passed across PaymentDiscovery, routing and OBPM compatibility |
| TypeScript | Main and native GPU entry checks passed |
| Frontend production build | Passed; final JS asset `index-ixVXjBQG.js` |
| Actual local HTTP | Seven scenario groups passed in 3.179 seconds; zero model/bank calls |
| Downloadable template | Served bytes equal the verified XLSX; actual upload accepted four native rows as three payments |
| Tenant/bank/branch controls | Unauthorized roles, CSRF, wrong branch, missing bank, unauthorized bank and cross-tenant case reads rejected |
| Multi-bank identity | Java test verifies same reference/branch at distinct configured banks stays separate; React tests use a non-760 bank and cancel stale responses on scope changes |
| Runtime correspondence | Ready native-service receipt matches deployed JAR; 42 tested Java source/resource files match the current tree |
| Preserved implementation | Seven selected original CPU/GPU/UAT source/configuration files retain their prior SHA-256 values |

[HTTP receipt](payment-discovery-http-2026-09-14.json) and [component/source receipt](payment-discovery-components-2026-09-14.json) contain the supporting records. The HTTP harness uses only original mock rows and the original fictional workbook. Uploaded examples are handled as PRIVATE_UAT by the application; that classification does not claim the example values came from a bank.

The earlier full React run passed 101 of 102 tests; a legacy routing assertion checked the URL before React's normalization effect completed. It was corrected to await that effect and the eight routing tests passed. The final affected 35-test run includes routing and the subsequent bank-selector changes. A final whole-suite rerun was not performed.

Native Windows Java production compilation passed, but test compilation encountered the existing space-path/classpath failure. Docker supplied the successfully tested JAR. During the bank-parameter change, a new multipart test exposed a request-wrapper difference; the controller now obtains the native multipart request through Spring's wrapper-aware utility. The successful 128-test build and actual multipart HTTP upload include that fix.

## Browser observations

The existing in-app browser was used, with original synthetic inputs only:

1. Root sign-in opened Payment cases and retained `/` as the homepage.
2. The final form showed bank 760, branch 1352, date and record count. Inquiry returned six candidates and required explicit selection before case creation.
3. The downloadable workbook was uploaded through the actual file chooser. Three selectable payments appeared, preserving text references and grouped subsequences `0, 1`.
4. Selecting an Excel candidate and entering a reason opened a case showing bank 760, branch 1352, amount 1500.75 INR and the supplied source date. Reload retained the case.
5. Existing cases created before the final restart remained visible after restart. The HTTP harness also verified saved-case reuse and concurrent creation.
6. Demo cases remained available with 48 records. No existing investigation or decision was changed by these checks.
7. Before the bank-parameter addition, the same flow also verified UTR ambiguity: two candidates were shown and neither was selected automatically. The final HTTP checks and affected React tests cover lookup with the required bank field.
8. The browser was left at `/` with Inquiry API selected, bank 760, branch 1352 and record count 20. A temporary viewport override was reset; a larger viewport screenshot failed, so no desktop-wide visual validation is claimed. The normal narrow view was inspected.

## Deployment and limits

Build image: `poi-discovery-build:20260914-bank`. Deployed JAR SHA-256: `f0b034fb75db758153821f032a1a8829d1e39eafe3a35bc1b54a61decceb1cbe`. The original pre-change JAR and frontend files are retained under ignored `runtime/payment-discovery-validation/before-deploy/`. Native data and saved answers were retained. No CPU application containers or model-generation requests were started by this work.

The API adapter is prepared against the proposed JSON contract, with a fixed configured HTTPS endpoint and no fallback from bank failures to mock data. Actual FLEXCUBE authentication/envelope/TLS provisioning and UAT execution remain unverified. The SQL worksheet is read-only and was not executed or compiled against Oracle. One configured endpoint/deployment is supported; separate bank endpoints/credentials require isolated deployments or a later server-owned routing extension.

The selected date is a calendar day, defaulting to today in Asia/Kolkata. Source Oracle DATE timezone and the bank business date are not inferred. A bounded search cannot establish bank-wide absence. These cases contain discovery metadata; attaching detailed inquiry snapshots and version-bound model questions is the next integration increment. Existing experimental model accuracy limitations remain.
