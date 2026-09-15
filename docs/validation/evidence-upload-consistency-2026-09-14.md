# Evidence upload consistency validation — 14 September 2026

## Confirmed problem

The reported JSON file had the expected evidence schema, standard columns and string values. Its payment identity was internally consistent. Diagnostic reproduction loaded it successfully into its matching saved case; uploading it into a different case reproduced the earlier generic identity/schema error. The reproduced failure was a selected-case mismatch. The original screenshot did not show the selected payment, so it does not independently establish that identity. The file needs no rewritten identifiers or converted values.

The original rejection protected the saved case, but its combined message did not explain the mismatch or give the operator a useful recovery path. This record contains no private transaction references, source rows or uploaded-file paths. Private diagnostic receipts remain under ignored `runtime/evidence-upload-validation/`.

## Implemented corrections

| Area | Resulting behavior |
| --- | --- |
| Wrong-case JSON upload | Shows the file and open-case identity triples, preserves the form, and offers only authorized saved cases matching reference, bank and branch exactly. Multiple matches remain operator choices. No automatic navigation, identifier substitution or save occurs. |
| JSON diagnostics | Identifies wrong schema, missing or unexpected fields, group/row/column type errors and snapshot wrappers. Invalid UTF-8 has an explicit correction message. |
| Duplicate keys and precision | Rejects duplicate decoded JSON keys, including escaped equivalents, before import. Native fields remain quoted strings; identifiers and decimal text are never coerced through floating-point numbers. Parsing has a nesting bound. |
| Excel group and row limits | Error messages identify the selected result group. Valid display-ordinal-only rows no longer consume the 500-source-row allowance; a 501st actual source row still fails. Existing ZIP/XML, cell-count, type and size protections remain. |
| Successful evidence save | Notifies the case immediately after the confirmed save, before refreshing version history. The case header reloads its authoritative evidence indicator and timestamp while the intake form and investigation workbench stay mounted. |
| Slow or failed reads | Evidence configuration, history, snapshot and matching-case reads have a 30-second bound. History failure does not undo the confirmed save. Header-refresh failure is visible and can be retried without another save. |
| Find payment navigation | The Evidence library link selects Payment cases at `/cases` after a prior visit to Demo cases. Browser history retains the appropriate queue choice. |

Invalid imports leave the previous form intact; a corrected file can be selected again. A valid import only fills the form and still requires explicit saving. JSON/manual evidence remains unverified for source-query completion, and empty result groups remain valid. No model call or payment action is introduced by these changes.

The [operator guide and recovery flow](../CASE_EVIDENCE.md#when-a-json-file-does-not-load) explain the steps and the unchanged shared payload.

## Automated verification

| Check | Confirmed result |
| --- | --- |
| Full frontend test suite | 216 tests passed |
| Full Java test suite | 229 tests passed |
| Main and native TypeScript checks, native production build | Passed |
| Final importer tests after row-label correction | 27 tests passed |
| Deployment and post-change live browser verification | Passed; details below |

The passing frontend suite includes strict JSON parsing with duplicate escaped keys, exact text and empty groups; wrong-case recovery links; field-specific errors and corrected-file retry; preservation of a prior draft; no implicit POST on upload; source provenance; same-content idempotent retry; stale case/version cancellation; save notification before a hanging history read; case-header refresh without losing intake; retry after header-refresh failure; and payment/demo queue navigation with browser history.

The passing Java suite includes exact native-text round trips; immutable versions; tenant, role and bank/branch authorization; wrong-case and native-row scope rejection; precise schema/type/header diagnostics; safe group-specific API errors; empty sections; source-count validation; duplicate JSON rejection; idempotency and concurrent saves; and downloaded configuration templates. Excel regression tests distinguish 500 actual rows plus an ordinal-only row from 501 actual rows and retain archive and unsupported-cell protections.

Tests use original synthetic fixtures. The reported private file was inspected for diagnosis; it was not copied into public test fixtures. These results establish the exercised application behavior, not bank connectivity, source-query completion or model-answer accuracy.

## Deployment and live verification

The tested Java JAR was deployed after stopping the verified project services and backing up the closed database, private configuration and previous JAR. Existing launch configuration and model implementation were retained. Build identity:

- API SHA-256: `3a263fd9bdd305dd7694ecb06f843726b842030e53596ca936f6da506113a9ef`
- Frontend: `index-BPTXhoOE.js` and `index-DxBNSWF7.css`

An isolated headless Edge session exercised the deployed website on port 5178 with the user's actual file:

1. A wrong-case upload displayed both identities and the exact authorized matching-case link. A previously entered amount retained its decimal text after rejection.
2. Following the link and selecting the same file filled PAYMENT 1, HOST 1, HISTORY 2 and STATUS 0. Exact reference and amount text, the second history row and the empty status group were inspected. Saving was available but was not triggered against the user's existing evidence.
3. Desktop and 390-pixel mobile recovery views were inspected; no horizontal page overflow occurred.
4. After visiting Demo cases, Evidence library → Find payment opened the payment queue at clean `/cases`. Back/Forward restored the expected payment/demo views.
5. No browser page errors or non-login writes occurred. No new model or bank request was made.

Before/after API snapshots matched all **8 saved cases, 2 evidence versions, 2 case investigations, 1 export snapshot and 3 export answers**. Protected model/source files and private runtime configuration hashes matched. Library filtering, ten-row pagination metadata, tenant separation, viewer reads and direct clean-page requests also passed. Private receipts and screenshots remain in `runtime/evidence-upload-validation/`.

Component tests exercise confirmed save notification, authoritative header refresh, retained intake/workbench state and failures/retries. Full Java tests exercise persistence and exact payload reads. No duplicate version was added to the user's database for testing.

## Isolated live save and reload

The same tested JAR ran separately on loopback port 8096 with an in-memory H2 database and original synthetic inputs. Eighteen HTTP requests verified discovery, two synthetic cases, one JSON evidence save, exact payload retrieval, idempotent replay, case metadata and the Evidence library. The case's new timestamp exactly matched the evidence creation timestamp, its indicator was `EVIDENCE_ATTACHED`, and other case fields were unchanged. The library returned the matching version and `PARTIAL` row coverage.

Wrong-case, numeric-source and duplicate-key requests each returned a specific 422 error without creating another version or modifying the cases. No bank or model call occurred. The owned process and listening port were checked, and only that temporary process was stopped. The receipt is `runtime/evidence-upload-validation/isolated-api-receipt.json`.

An initial isolated startup encountered the Windows JDK Unix-domain socket path limit. The successful retry used the same short socket-directory JVM setting as the existing native launcher, scoped to this test only. No product-code or live-runtime configuration change was needed.
