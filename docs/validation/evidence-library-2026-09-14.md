# Evidence library validation — 14 September 2026

## Delivered scope

`/evidences` is now a case Evidence library with global authorized counts, case/payment/UTR/reason search, bank/branch/latest-source/latest-coverage filters and pages of ten. The inspector reads immutable versions and exact native fields from the existing case evidence APIs. Case links lead to the existing evidence intake and investigation workbench. The original standalone export Q&A and saved answers remain at `/evidences/exports`.

No model implementation, prompt, inference configuration, existing source snapshot or answer was replaced. The read API adds no schema or data migration and executes no bank query. See [the operator guide, API and flow](../EVIDENCE_LIBRARY.md).

## Automated verification

| Check | Actual result |
| --- | --- |
| Full offline Java Maven package | 221 tests passed; zero failures, errors or skips |
| New Java library tests included above | 11 service tests and 3 HTTP/security tests |
| Full React suite | 203 tests passed across 12 files |
| New library component tests included above | 8 tests |
| Routing / preserved standalone export tests included above | 71 tests |
| Main and native TypeScript | Passed |
| Native production build | Passed |

Java tests cover authorization before counts, exclusion of disallowed metadata, latest numeric version, empty and partially populated groups, exact decimal strings, source and all-word search, filter-before-pagination, time ordering, page clamp, unknown/duplicate/bounded input validation, viewer reads and absence of mutations. Frontend tests cover delayed search cancellation, stale case/version reads, exact native text, timeout/retry, empty states, tenant identity reset, fixed pages and filter reset. Route tests exercise direct loads, sign-in destination restoration and Back/Forward including `/evidences/exports`.

Tests use original synthetic fixtures. They are functional checks, not a new model accuracy or bank integration assessment.

## Local deployment and preservation

The stopped local stack was backed up to `runtime/evidence-library-validation/backup-20260914-051349` before replacing its JAR. Startup used the existing ownership-checked native launcher and private configuration.

The built and deployed API SHA-256 matches:

```text
8accf245c69440e8425aa5adf220c81adb57117ec86decadc476c9d3d8646cc3
```

Final native frontend assets are `index-DqWwya76.js` and `index-B90xM6AL.css`. Final styling added card padding, clearer source text and a readable mobile Find payment link; the native build and deployed browser checks were repeated after this adjustment. A final singular/plural row-label correction also passed all eight library component tests and the deployed browser checks.

Authenticated before/after reads were exactly equal for all seven saved cases, both evidence versions and their payloads, both saved case investigations, the separately staged export snapshot and its three saved answers. Hashes also matched for the standalone Q&A component, existing case investigation component, Java UAT service/client, native launcher and private application configuration. Private receipts retain those comparisons without copying their content into public fixtures.

The new live index reports seven cases, one with rows, six without rows and two evidence versions. Latest-source/coverage and combined exact-reference/scope filtering returned the expected matching case. Search with no matches retained global summary counts. Invalid, duplicate and unauthorized filters were rejected. Viewer results matched authorized analyst reads; the second tenant could not see the first tenant's case identities.

## Deployed browser checks

An isolated headless Edge session exercised the actual built frontend and local Java API:

1. Signed in from the clean `/evidences` deep link and loaded the seven-case library.
2. Searched an existing payment, selected Inspect, verified latest JSON version 2, and read the exact saved `N10_STATUS` value using field search.
3. Inspected the empty STATUS group, then selected earlier Excel version 1 and the second HISTORY row. The stored field value and earlier-version notice were displayed.
4. Opened the correct payment case, navigated Back, and filtered by latest source. Excel correctly matched no case when the current version was JSON. Bank/branch filtering worked and Branch displayed only its branch number.
5. Opened Export Q&A, loaded the earlier saved answers and refreshed. Returned to Case evidence and refreshed its clean URL successfully.
6. Checked desktop and 390-pixel layouts. The page had no horizontal overflow; the wide evidence table scrolls inside its own container. Screenshots were visually reviewed after the final spacing adjustment.

There were zero browser JavaScript errors. The only non-GET request was sign-in: no evidence save, question submission, model call or bank call occurred. Three direct page requests returned the deployed SPA HTML. Private screenshots and executable smoke checks are under `runtime/evidence-library-validation/`.

The desktop browser-extension connection timed out during initial inspection. Verification used a fresh isolated headless Edge session against the same deployed app, rather than claiming the existing interactive tab was refreshed.

## Remaining boundaries

The index batches local metadata and filters in memory; bank-scale search/pagination is not benchmarked. Four populated groups do not establish complete evidence or a payment outcome. The real inquiry wrapper/authentication and the existing model's factual limitations remain separate unresolved integration concerns. This increment adds evidence organization and navigation, not new AI inference behavior or payment execution.
