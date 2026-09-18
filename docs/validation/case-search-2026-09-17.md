# Database case search and pagination — 17 September 2026

Implemented and deployed locally for the saved-case queue, Evidence Q&A case picker, wrong-case JSON matching and Evidence library. The public case-list API now returns bounded pages; SQL applies authorized scope, filters, stable sorting, counts and limits before page records are deserialized. A rebuildable `fcr_case_search` projection is refreshed in the existing case transaction and backfilled at startup in batches of 100 cases. Dashboard counts also use SQL aggregates.

## Verification

- Full Java package: **421 tests passed**, no failures/errors/skips.
- Full frontend suite: **438 tests passed** in 56 suites.
- Main/native TypeScript checks and production bundles passed.
- **Four Chromium acceptance scenarios passed**, using isolated React/Java/H2 services and an explicitly labeled provider test double. The added scenario creates twelve original synthetic cases and checks ten/two pages, search, sort, literal no-match input, selected Q&A outside the current page, retained question drafts and canceled navigation. Desktop and 390px screenshots were reviewed; the narrow viewport has no page overflow.
- Backend regressions cover scope/tenant isolation before counts, exact bank/branch/reference filters, literal SQL wildcard escaping, bounded hydration, empty and clamped pages, nanosecond/offset ordering, 205-case startup rebuild, transactional rollback, owner/workflow/evidence/lifecycle refresh, and corrupt authorized evidence metadata.
- A two-connection test inserts between count and page reads. Serializable read-only snapshots preserve a consistent response; serialization conflicts retry at most three total attempts and then return a retryable 503. Validation failures do not retry.
- The standalone discovery acceptance script was adjusted to use bounded exact-ID searches instead of assuming the default list contains every case; its Python syntax check passed. The complete standalone script was not rerun in this pass.

The initial sandbox Maven invocation could compile source but could not resolve the project classes during test compilation. The scoped invocation outside that sandbox completed the full suite/package. A first library test assertion counted JdbcTemplate's delegated overload calls as separate SQL statements; it was corrected to inspect distinct bounded statements. The final suite includes that correction.

## Local activation

The previous API jar, frontend entry/assets, process receipt and closed H2 database were backed up. Only the verified project API process restarted. The existing web and worker processes were retained. Served HTML and both referenced assets match the tested native build.

Read-only post-update checks verified four two-record pages across the eight current saved cases, case-number ordering, case ID/number search, exact identity lookup, empty results, page clamping and Evidence library totals/search. The native discovery configuration remains `BANK_API`.

Before/after preservation checks retained all **eight cases, five evidence versions, eight investigations**, their original case/management/evidence/answer values, and hashes of **seven protected configuration files**. Private bank/branch authorization and connection settings were not changed. No bank inquiry, model inference or live case mutation was used for this validation.

Tested/running API SHA-256: `9b153681f43ff086444c353373115598c955c2870e373d3661bbd1f9a01faff4`.

The ignored local receipt directory `runtime/case-search-2026-09-17/` contains the build log, synthetic screenshots, private preservation snapshots, backup and live verification receipt. These changes have not been committed or pushed; no new GitHub CI result is claimed.

## Limits

This is bounded case-list loading, not a bank-scale latency benchmark. Substring filters, exact counts, large offsets and startup rebuilding still need workload measurements. Native H2 and synthetic H2 tests were exercised; a deployed PostgreSQL test was not performed. Individual case histories still use their existing APIs so reviewer/report selections remain intact; migrating those histories is separate remaining work. See [the search contract and flow](../CASE_SEARCH.md).
