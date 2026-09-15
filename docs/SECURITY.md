# Security model

## Trust boundaries
Browser input, payment descriptions, runbook content and model output are untrusted. Session identity is server-derived. CSRF protects cookie-session mutations. Each API request checks roles and tenant; possession of an object ID is not authorization.

The worker receives only an API-authorized case snapshot and a service-key-protected request. Its typed tools cannot query arbitrary SQL, read arbitrary files, call arbitrary URLs or change money. Retrieval filters tenant and effective date before returning passages.

## Demonstrated controls
Session handling; role matrix; two-person decisions; idempotent mutation keys; optimistic case versions; append-only audit API; limits/timeouts; validated citation/evidence IDs; local service-key protection; original synthetic records.

## Demo authentication
Local accounts are deliberately available for synthetic demonstrations. The deployment must bind localhost and reject demo authentication in a production profile. Public deployment requires a separate identity and operational review; a local demo login is not enterprise SSO.

## Threat scenarios
Cross-tenant ID guessing; forged role/tenant headers; missing CSRF; duplicated review submissions; stale-case approvals; malicious instructions inside retrieved text; invented citations; case export leakage; excessive agent steps; worker outages; shared-checkpoint contamination.

## Deployment boundary
No real payment execution or production data. Paid services, public hosting and publication require explicit authorization. Do not access or alter unrelated application containers.

