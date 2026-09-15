# Payment Operations business API

Java 17 and Spring Boot implement the payment-case persistence and authorization boundary. The API supports configurable read-only payment/evidence inquiry services, Excel and JSON ingestion, version-bound local-model questions, case management, lifecycle controls and PDF reports. It never initiates a payment or updates bank transaction records.

The repository includes original synthetic fixtures. Private bank addresses, credentials, transaction exports, case databases and model answers are not shipped with source.

## Local build and run

From `services/api`, with Java 17 and Maven 3.9+:

```sh
mvn -B -ntp verify
java -jar target/payment-operations-api-0.1.0.jar --server.address=127.0.0.1
```

Health is available at [the API health endpoint](http://127.0.0.1:8088/api/health). The default worker URL is loopback port 8091. Run from this directory so default fixture paths resolve, or configure them explicitly. Match `POI_SERVICE_KEY` between API and worker before requesting an investigation. Health response alone does not verify bank connectivity or model availability.

The default H2 database is stored beneath this service's ignored `runtime/` directory. For PostgreSQL set `SPRING_PROFILES_ACTIVE=postgres`, `POI_DB_URL`, `POI_DB_USER` and `POI_DB_PASSWORD`. Sessions require login after an API restart. Fixture import inserts missing legacy synthetic IDs; it does not reset existing case decisions.

For a clean checkout with all services, use the explicit base-Compose instructions in the [project README](../../README.md). Native GPU launchers require separately prepared private artifacts and are not installers.

## Local identities and session contract

The local password comes from `POI_DEMO_PASSWORD`, default `demo-pass-local`, and must contain at least twelve characters.

| Username | Role | Synthetic tenant |
| --- | --- | --- |
| `analyst` | ANALYST | northstar |
| `reviewer` | REVIEWER | northstar |
| `viewer` | VIEWER | northstar |
| `admin` | ADMIN | northstar |
| `other` | ANALYST | silverline |

`POST /api/auth/login` accepts JSON containing `username` and `password`; its response contains `user` and `csrfToken`. Retain the HttpOnly session cookie and send `X-CSRF-Token` on authenticated mutations. Browser clients use the same-origin web proxy. Identity, role and tenant come from the session, not request-supplied headers.

Analysts and reviewers may create cases, attach evidence and submit questions. Viewers may inspect authorized records. Administrators have separate archive/restore/removal powers; they do not acquire normal analyst/reviewer privileges. Reviewer conclusions additionally enforce independent review of their selected investigations. The backend rechecks case, tenant, bank/branch scope and lifecycle state.

This is development authentication. `prod` and `production` profiles refuse to start with this identity configuration; production identity and entitlement management require separate implementation.

## Payment-case flow

1. Discovery returns reference/date inquiry records or validated Excel rows within the authorized scope. Opening a case preserves the selected observation and operator's reason.
2. Evidence acquisition validates the selected payment identity and four source groups, then stores a new version. API errors do not become fabricated successful results.
3. A question selects an exact evidence ID and fingerprint. Java saves a queued job and supplied source bundle, invokes the local worker, and retains the answer or recorded failure. Prior jobs keep their original evidence and source documents.
4. Management records ownership, priority, notes, evidence requests and reviewer conclusions through separate versioned state and audit events. A reviewer conclusion does not automatically resolve the payment case.
5. PDF preview saves a fixed report selection. Download checks that report's fingerprint, so later case changes do not alter an existing report silently.

Durable display case numbers use a date prefix and daily five-digit sequence; canonical internal identifiers remain stable. Archive makes a case read-only while preserving its contents. Administrator removal requires an archived case, reason and exact case-number confirmation, and retains the minimal lifecycle/number reservation records specified in the contract. Active investigation jobs block incompatible lifecycle changes.

Mutations use session CSRF, optimistic versions and idempotency keys where required by their endpoint contracts. An identical retry reuses its prior result; a changed payload using the same key is rejected. Closing a browser or timing out does not prove cancellation of work already accepted by the service.

## Configuration

| Setting | Purpose |
| --- | --- |
| `PORT`, `SERVER_ADDRESS` | HTTP port and bind address; set loopback for native development |
| `POI_DB_URL`, `POI_DB_USER`, `POI_DB_PASSWORD` | Local database connection |
| `POI_WORKER_URL`, `POI_SERVICE_KEY` | Worker location and shared API/worker authentication |
| `POI_DEMO_PASSWORD`, `POI_COOKIE_SECURE` | Development password and HTTPS cookie behavior |
| `POI_PAYMENT_DISCOVERY_MODE` | Synthetic or explicitly configured bank discovery mode |
| `POI_PAYMENT_DISCOVERY_BANK_ENABLED`, `POI_PAYMENT_DISCOVERY_BANK_URL` | Opt-in read-only discovery service |
| `POI_PAYMENT_DISCOVERY_SCOPES` | Authorized tenant/bank/branch scope definitions |
| `POI_PAYMENT_DISCOVERY_WIRE_FORMAT`, `POI_PAYMENT_DISCOVERY_API_TOKEN` | Discovery adapter format and optional private authentication |
| `POI_CASE_EVIDENCE_API_ENABLED`, `POI_CASE_EVIDENCE_API_URL` | Opt-in evidence inquiry service |
| `POI_CASE_EVIDENCE_WIRE_FORMAT`, `POI_CASE_EVIDENCE_API_TOKEN` | Evidence adapter format and optional private authentication |
| `POI_CASE_INVESTIGATION_GUIDANCE_FILE` | Reviewed private case-guidance source |
| `POI_CASE_INVESTIGATION_KNOWLEDGE_INDEX_FILE` | Optional versioned private knowledge embedding index |
| `POI_IMPORT_FIXTURES`, `POI_FIXTURES` | Optional legacy synthetic fixture loading |

Keep operational values in ignored local configuration; examples must not contain real bank addresses or secrets. Inquiry URLs are configured server-side. HTTPS uses certificate validation. Detailed adapter fields, posting-date policies, response statuses and empty-result handling are documented in [Inquiry integration](../../docs/FLEXCUBE_INTEGRATION.md).

Knowledge retrieval reads only applicable reviewed sources. When the unified embedding index is enabled, missing or stale coverage blocks new indexed questions rather than silently using mismatched vectors. A valid citation identifier or content fingerprint does not prove the model's claim is correct. See [Case knowledge](../../docs/CASE_KNOWLEDGE.md).

## Endpoint contracts

| Guide | Service responsibility |
| --- | --- |
| [Discovery](../../docs/PAYMENT_DISCOVERY.md) | Payment lookup and saved case creation |
| [Evidence](../../docs/CASE_EVIDENCE.md) | Versioned API/file/manual evidence |
| [Investigation](../../docs/CASE_INVESTIGATION.md) | Queued questions, saved sources and answer validation |
| [Management](../../docs/CASE_MANAGEMENT.md) | Owner, priority, notes, evidence requests and conclusions |
| [Lifecycle](../../docs/CASE_LIFECYCLE.md) | Archive, restore and administrator removal |
| [Reports](../../docs/PAYMENT_CASE_REPORTS.md) | Compact/detailed PDF preview and download |
| [Case numbers](../../docs/CASE_NUMBERS.md) | Display numbers and canonical ID aliases |

The earlier synthetic investigation/review endpoints remain for compatibility and isolated regression tests. Their deterministic reconciliation and replay behavior are separate from the current payment-case model-answer path.

## Verification and limits

Maven tests cover adapters, scopes, permissions, exact identifiers/amounts, evidence integrity, job association, idempotency, concurrency, reports and lifecycle transitions. Controlled HTTP transports in tests are not live bank validation. Reports are generated under `target/surefire-reports`; historical receipts describe particular runs, not a guarantee for another deployment.

Audit immutability is enforced by application behavior; it does not prevent a database owner from modifying storage. Independent identity management, deployment migrations, backup/retention policy and external audit protection remain operational work. The service records investigation evidence and human conclusions; it cannot infer a final payment outcome merely from a status label or successful inquiry response.
