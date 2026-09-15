# API validation evidence

Checked locally on 2026-09-12 (Asia/Kolkata) using original synthetic project fixtures.

| Check | Result | Evidence |
| --- | --- | --- |
| Latest Java unit/integration suite, Linux image build | 35 passed, zero failures/errors/skips: 25 API integration + 10 money tests | [Container test record](../../docs/validation/java-container-tests.json); raw XML in `runtime/container-tests-ledger-alignment` |
| H2 actual process restart | 6 checks passed, PID 41912 → 37372 | `runtime/persistence-result.json` |
| PostgreSQL + real worker HTTP acceptance | 17 checks passed | `runtime/postgres-validation/acceptance-replay.json` |
| PostgreSQL actual API restart | 6 checks passed, PID 21240 → 36844 | `runtime/postgres-validation/persistence-result.json` |
| Restricted vector database role | 9 check groups passed | `runtime/postgres-validation/vector-role-result.json` |
| Public API document | 14 paths / 15 operations covered from controllers; 43 schemas | `docs/openapi.json`; `services/api/validate-openapi.py` |

## Latest Java hardening and Linux build evidence

`docker compose --progress plain build api` executed `mvn -B -ntp package` with tests enabled and completed successfully at 2026-09-11 22:55:48 UTC (2026-09-12 04:25:48 IST). Maven/JUnit reported **35 tests, zero failures, zero errors and zero skips**. The runtime was Eclipse Adoptium Java 17.0.15+6 on Linux/amd64 in `maven:3.9.9-eclipse-temurin-17`.

This revision aligns Java and worker ledger interpretation. Normalized `CAPTURE`/`PAYMENT_CAPTURED`/`SALE` amounts must be positive; `REFUND`/`REFUND_POSTED` amounts negative; `FEE` non-positive, with zero allowed. Other signed entries, including adjustments, retain signed-net treatment. Unknown/unavailable provider states are case-insensitive and cannot make a supplied zero payout available. Five new tests cover alias totals, the concrete posted-refund discrepancy, invalid signs/zero fees, status variants and authenticated public reconciliation plus rejected import. The resulting API image is `sha256:f2b77ca3b3fd9b062d26ad120b8cd5ac713bdb22424c7eea7fa45240261f88fe`.

The concrete counterexample contains CAPTURE +100000, FEE -3000 and REFUND_POSTED -10000 with provider payout 87000. The earlier code recognized only exact REFUND and reported refundMinor=0. The new regression confirms refundMinor=10000, ledgerNetMinor=87000 and discrepancyMinor=0. Invalid signs fail with 422 INVALID_AMOUNT before a case or audit row is imported. All four checked-in data files remain byte-identical to the pre-V6 candidate snapshot; aliases were added only to tests.

Six previously added regression tests cover both signs of payout mismatch, unknown/missing provider payout, balanced timeout/deduplication/ordering resolution proposals, wrong/missing worker case and investigation IDs, nested known evaluation-label keys, and preservation of ordinary domain fields. Resolution now requires valid authoritative Java reconciliation, an available provider payout and a known zero discrepancy. Worker response identifiers must match the submitted request before server-owned provenance is applied. Fixture import rejects the existing three label keys recursively; the checked-in operational data was clean, so this is a hardened import boundary rather than evidence of an existing label disclosure.

The new timeout regression captures the outgoing `HttpRequest` through a mocked transport. It proves configured 390 seconds remains 390, an excessive 10000-second setting clamps to 390, and zero clamps to one second. It performs no network request and does not wait for a real timeout. The existing HTTP-stub integration checks continue to validate actual request/error behavior. The Java default and upper bound are 390 seconds; nginx/Vite allow 420 seconds around the worker's coordinated 180-second provider transport setting. These settings do not guarantee downstream cancellation.

The raw JUnit XML and text reports were subsequently copied from the cached `build` stage into `runtime/container-tests-ledger-alignment`. The extraction command was:

```sh
docker build --progress plain --target build --tag payment-operations-investigator-api-test-evidence:ledger-alignment services/api
```

Docker reported the Maven test/package layer **CACHED**. This extracted evidence from the successful 35-test run; it did not execute the tests again. A dedicated container named `poi-api-ledger-evidence-20260912-f2b7` was created but never started, used only to copy `/build/target/surefire-reports`, `/build/src` and `/build/pom.xml`, and then removed. All 18 copied files, including source, resources and the Maven configuration, matched the working files byte-for-byte. Only `MoneyFacts.java`, `MoneyFactsTest.java` and `ApiIntegrationTest.java` changed from the preceding tested source revision. [java-container-tests.json](../../docs/validation/java-container-tests.json) records XML totals/hashes, source hashes, runtime, commands, image identities and cleanup. The preceding 30-test [receipt](../../docs/validation/java-container-tests-30-pre-ledger-alignment.json) and raw `runtime/container-tests-knowledge-copy` reports are preserved. The earlier timeout-alignment 30-test [receipt](../../docs/validation/java-container-tests-30-pre-knowledge-copy.json), raw `runtime/container-tests-timeout-alignment`, 29-test [receipt](../../docs/validation/java-container-tests-29-pre-timeout.json) and raw `runtime/container-tests` directory also remain unchanged.

This latest build produced only the API image. The earlier combined timeout-alignment build also passed its then-current 18 frontend tests, production build, Vite formatting check and isolated `nginx -t`; those are historical observations preserved in the preceding Java receipt. Current frontend validation is recorded separately in [frontend-tests.json](../../docs/validation/frontend-tests.json). No running service was recreated by this build task. Browser and live-model acceptance remain separate evidence.

Native Windows Maven compiled the updated main classes but `testCompile` could not resolve the project's main symbols, including `CaseStore` and `MoneyFacts`, even though the class files existed, `javap` read them, and the debug output included `target/classes` in the test classpath. Forked javac had the same failure. The existing socket workaround addresses a different runtime issue and did not solve this classpath failure. No dependency changes or skipped-test workaround were committed; the successful Linux package is the final Java validation for this source revision. Do not use older native `target/surefire-reports` as evidence for the new 35-test result.

The runtime acceptance and persistence observations below have their own timestamps and source scope. These build tests do not claim a new PostgreSQL acceptance, live-worker/model run, restart or full Compose rehearsal. Current combined validation is tracked in [STATUS.md](../../docs/STATUS.md).

PostgreSQL verification used the dedicated `127.0.0.1:5438/poi` database, PostgreSQL 17.11, and a separate native API bound to `127.0.0.1:8089`. The existing H2 API on 8088 and unrelated AutoPay Guard containers were preserved. All 60 operational fixtures imported; northstar sessions see only their 48 cases. Acceptance covers authentication, tenant/role denial, CSRF, actual replay worker calls with evidence, reviewer separation, stale versions, idempotency conflicts/replay, export and audit.

The restart probe records stable canonical JSON digests, preserving array order while sorting object keys. This avoids treating unspecified JSON property order as a data mutation. After an actual process restart, it verifies the immutable investigation and audit, then submits the original review command and checks the original decision identity, unchanged audit, version and case status. Sessions intentionally require a fresh login after restart.

PostgreSQL's server time-zone catalog rejected this Windows host's old `Asia/Calcutta` alias sent by JDBC. The separate PostgreSQL launcher sets `-Duser.timezone=UTC` only for that process; no host or database time-zone setting is changed.

The worker client explicitly selects HTTP/1.1, avoiding an unsupported h2c upgrade request to Uvicorn. An integration assertion checks the actual worker request has no Upgrade header. Its configured investigation request timeout defaults to 390 seconds and is bounded to 1–390 seconds. The worker's 180-second provider HTTP read timeout is an inactivity timeout rather than a strict combined CPU deadline; the two generation stages remain bounded by token caps, while Java limits its wait for the worker response. A Java timeout does not assert downstream provider cancellation. Live-model success/latency is verified separately by the worker and root harnesses; backend regression tests use a real local HTTP stub.

## Vector role isolation

`infra/postgres/020-vector-role.sh` was executed against the dedicated database and rerun successfully. Its Bash heredoc prevents shell interpolation of SQL; psql's quoted variable syntax handles the password, and PostgreSQL `format` quotes the database identifier. The file uses LF line endings. The existing public vector table and all existing API tables were preserved.

The role `poi_vectors` authenticates over TCP, owns `poi_knowledge`, and defaults to `search_path=poi_knowledge,public`. Verified by actual SQL:

- Can create, insert and select a `vector(3)` table in its own schema; the probe transaction was rolled back.
- Cannot SELECT or DELETE from `public.payment_case`, `public.investigation`, `public.review_decision` or `public.audit_event`.
- Cannot create a table in `public` or `SET ROLE poi`.
- Has no superuser, role/database creation, inherited role privileges, replication or RLS bypass attributes.

The API demo owner `poi` remains the dedicated development database administrator. This verifies worker isolation; it does not claim production database-role hardening for the API. Application audit rows remain append-only through API code, rather than tamper-proof against a database administrator.

Repeat role checks with `./verify-vector-role.ps1`. Start the separate PostgreSQL instance with `./start-postgres-validation.ps1`; use `verify-persistence.ps1` with explicit `-BaseUrl`, `-ProcessIdPath`, `-StatePath` and `-ResultPath` pointing to `runtime/postgres-validation`. Never use the H2 PID file when stopping the separate validation instance.

Primary syntax references: [psql variable quoting](https://www.postgresql.org/docs/17/app-psql.html#APP-PSQL-INTERPOLATION), [role privileges](https://www.postgresql.org/docs/17/sql-createrole.html), [schema privileges and search paths](https://www.postgresql.org/docs/17/ddl-schemas.html).

## Public API document

`docs/openapi.json` describes only Java's public routes in OpenAPI 3.1.0. It is read from the controllers, session security, case store, review service and monetary validators; it does not publish the internal worker API. Success codes reflect the actual HTTP 200 behavior. Cookie sessions, CSRF, review idempotency/maker-checker rules, exact monetary bounds, nullable unknown provider totals and optional worker metadata are explicit. Imported status/priority strings and query filters are described as known values without pretending Java enum-validates them.

Run `python services/api/validate-openapi.py` from the project root. This helper is read-only and uses only the standard library. It checks strict JSON, all local references, controller path/method coverage, security headers and selected source/contract invariants. It is not a full OpenAPI standards validator or a substitute for the HTTP acceptance suite. Schema syntax follows the [OpenAPI 3.1.0 specification](https://spec.openapis.org/oas/v3.1.0.html).
