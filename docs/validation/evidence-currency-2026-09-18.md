# Currency from saved payment evidence — 18 September 2026

The saved-case UI previously used only the original discovery currency. PO01 does not supply that field, so cases could show "Currency not supplied" even after PO02 evidence provided `PAYMENT.CODCURR`.

The backend now adds optional `evidenceCurrency` display metadata when the latest saved evidence has a valid fingerprint, matches the case identity, and every PAYMENT row agrees on currency and the original numeric amount. The queue, case details, Evidence library and Evidence Q&A display the currency with its evidence version. Original discovery values and saved evidence remain unchanged. Internal report and investigation inputs retain their original observation and selected-version semantics.

## Code verification

- The full Java suite ran **428 tests**: 427 passed and one new query-count assertion exposed JdbcTemplate spy delegation. After correcting that test to count the service query boundary and verify its exact scope/latest-version arguments, all **seven currency tests passed**. Application source was unchanged between those runs.
- The full frontend suite passed **460 tests in 58 suites**; the focused currency/integration selection passed **103 tests** (overlapping the full suite).
- Main and native TypeScript checks and both production builds passed. The existing bundle-size advisory remains.
- Backend checks cover exact decimal agreement without rounding, conflicting/blank currencies, mismatched identity, fingerprint tampering, replacement/empty latest versions, original currency precedence, tenant scope, report isolation, bounded evidence reads and idempotent migration/backfill.
- Frontend checks cover shared rendering, source validation, exact amount preservation, evidence-version labels and the original missing-currency fallback.

The query projection stores only the small derived currency observation. Startup/mutation refresh inspects at most the latest evidence body; public list/detail/library reads do not fetch evidence bodies for currency display. See [the contract and flow](../CASE_SEARCH.md#currency-supplied-by-saved-evidence).

## Browser and local activation

All **four isolated Chromium scenarios passed**. The first uploads a documented synthetic workbook with missing optional currency, opens a case, saves matching PAYMENT evidence, and checks that the original API currency stays null while detail, queue search/refresh, library and Q&A display the evidence currency. Other scenarios retain draft, lifecycle/report, follow-up and pagination coverage. The provider is an explicit test double; these are not model-quality tests. Queue and detail screenshots were visually reviewed, and all three isolated service listeners stopped after testing.

The API jar, frontend assets, process receipt and closed H2 database were backed up before activation. Only the verified project API restarted; existing web and worker processes were retained. The served HTML and both referenced assets match the tested native build. Live API checks confirm the affected saved case now supplies INR from evidence version 1 consistently in case list, public detail and Evidence library responses.

Before/after checks preserved all **eight cases, five evidence versions, eight investigations**, every original case/management/evidence/answer value and hashes of **seven protected configuration files**. The original discovery amount and null currency remain unchanged. Native discovery remains BANK_API, with existing authorization and connection settings preserved. No live case mutation, bank inquiry or model inference was used for validation.

Tested/running API SHA-256: `a6d898936ab543ba560277cf44e166457683c5c2ebd1b500d41bbb7c6839d3af`.

Private runtime receipts and backups are kept under the ignored `runtime/evidence-currency-2026-09-18/` directory. No commit or push was made, and no new GitHub CI result is claimed.

## Limits

This is a source-attributed currency display, not a payment-outcome determination or a currency default. Initial PO01 discovery results still show missing currency when it was not supplied. Conflicting or incomplete latest PAYMENT evidence withholds the hint. Reports and previously saved answers are not rewritten. No new bank inquiry or model inference is needed for this change.
