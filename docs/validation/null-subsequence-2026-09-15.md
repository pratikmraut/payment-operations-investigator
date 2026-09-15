# Nullable host subsequence compatibility — 15 September 2026

The native discovery package returns a nullable `REF_SUBSEQ_NO`. Its PO01 DTO field, `referenceSubsequenceNumber`, previously failed the consumer's text-only validation; accepting it at the adapter alone would still fail numeric validation/sorting downstream.

The application now preserves unknown subsequences as JSON `null`, distinct from the exact string `"0"`. Empty strings and blank Excel cells also project to unknown. Grouping retains all raw observations and one unknown marker per payment, after numerically sorted known strings. The UI renders the marker as **Unknown** in discovery and saved-case details. Existing saved cases retain their original observation and receipt when resumed. Missing required columns, malformed populated values, scope mismatches and conflicting metadata still fail.

Validation:

- Java Maven package: **330 tests passed**, no failures/errors/skips. Transport fixtures and isolated H2 databases cover PO01 date/FCR/UTR paths, null-only and mixed rows, provenance, durable case save/resume and strict rejection behavior.
- Frontend: **48 targeted tests passed** across discovery, saved cases and detail refresh. Mixed zero/null and null-only rows render explicitly in discovery and saved detail.
- TypeScript and native Vite production build passed; frontend asset is `index-C3JDT3N0.js`.
- Earlier local build attempts encountered sandbox build-output access and this laptop's known Java UNIX-socket temporary-path issue. The successful suite ran with normal local permissions and `-DargLine=-Djdk.net.unixdomain.tmpdir=../../runtime/jt`; no installed Java or global temporary-directory settings changed.

Private build logs, before/after saved-resource comparisons, the original API artifact and deployment receipts are retained in ignored `runtime/null-subsequence-validation/`. No bank or model call was made. These checks validate consumer compatibility using controlled responses; they do not establish live FLEXCUBE connectivity.

The tested build is deployed at `http://127.0.0.1:5178`. The running stack receipt and deployed JAR match build SHA-256 `6f5bfcd0e3fb9aee5afff8e25976c74ba9ffc92513f686a0ed78c59f5939edd8`, and `/cases` serves the expected frontend asset. After restart, exact comparisons preserved **10 cases, 4 evidence versions, 6 case investigations, 1 original export snapshot, 3 original export answers**, all case-management records, the SQL package, active guidance and protected model/runtime files. The first safe stop attempt reported a process-exit timeout; a second ownership-verified lifecycle attempt completed and all three services returned ready.
