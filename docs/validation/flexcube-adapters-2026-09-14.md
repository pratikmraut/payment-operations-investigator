# FLEXCUBE adapter validation — 14 September 2026

This validation uses application code, synthetic unit tests and the user's privately supplied examples. It does not contact or deploy FLEXCUBE.

## Results

- Java: 319 tests passed, no failures/errors/skips; packaged JAR SHA-256 `fc13472d939e8d6fa38620e58897e24b89386d9f5193a2977930be16718efb42`.
- Frontend: 312 tests passed across 20 files; TypeScript and native Vite build passed.
- Private sample probe: all three requests matched their examples except newly generated correlations. Response copies echoed only those new correlation values.
- PO01 date: 15 native rows, 105 source-cell comparisons. PO01 exact: one row, seven comparisons. Currency remained absent.
- PO01 exact lookup through the actual discovery service created a case in isolated H2; PO02 then saved one immutable version with 1 PAYMENT, 1 HOST, 2 HISTORY and 1 STATUS row. Its 102 mapped cells matched the example, including 17 nulls.
- Source-null values survived storage and cited-document projection. Query observation timestamps were preserved, source timezone remained UNKNOWN, and all groups remained UNVERIFIED. Idempotent replay did not call the injected transport again.
- Regression coverage includes invalid service status, mismatched correlation/date, integer bounds, scope/reference mismatch, null/missing arrays, precision-safe identifiers/amounts, grouped UTR results, provenance corruption, legacy snapshots, repeated identical inquiries and the PDF null/blank distinction.

The first native Java run encountered the project's known Windows Unix-domain socket temporary-path issue; rerunning with the existing `runtime/jt` socket setting resolved it. A new PDF assertion was normalized for Windows line endings. The final full suite passed without exclusions.

## Preservation and deployment

Private receipts are in ignored `runtime/flexcube-adapter-validation/`: `backend-contract-probe.json`, `before.json`, `preservation.json`, the source manifest, build logs and the connection guide. Raw bank examples/source are not public test fixtures or model guidance. The source manifest verifies 22 inspected FLEXCUBE source files unchanged, and the probe verifies all three original payload-file hashes.

The local runtime uses the tested package and prepared FLEXCUBE endpoints with bank calls disabled until explicit activation. No payment execution, bank inquiry, model generation or saved-case mutation is part of deployment validation. Existing model code and settings are preserved. The local preservation receipt records the final saved-data comparison.

Post-restart checks confirmed the deployed JAR hash matches the probe, the new frontend is served, and the prepared/disabled API modes are active. All 9 cases, 3 evidence versions, 5 case investigations, the original export snapshot and its 3 answers, and every case-management record matched the before snapshot. Protected model files and unrelated runtime properties matched as well. Ten configuration-helper assertions passed using an isolated synthetic properties file.

## Limits

These checks prove local contract compatibility for supplied and synthetic cases, not deployed bank behavior. Confirm service identity/channel, business posting date, TLS/gateway configuration, correlation constraints, serializer null inclusion and status rules after deployment. The discovery numeric contract accepts bounded nonnegative plain decimals; the source's broader TM9 representation requires the documented deployment check. Native PO02 does not provide per-group fetch-completion metadata, so an empty array is never promoted to proof of absent activity or a final payment outcome.

See [FLEXCUBE integration](../FLEXCUBE_INTEGRATION.md) for configuration and the unchanged user flow.
