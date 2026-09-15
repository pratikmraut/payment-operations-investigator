# Synthetic NEFT inquiry service

This independent FastAPI service exercises an actual read-only HTTP connection between our Java application and a proposed inquiry API. It returns four original synthetic records. It is not an Oracle product endpoint, a database emulator, or a verified FCR/FCUBS/OBPM physical-table export. It has no database connection, payment commands, model calls or scenario-changing endpoint.

`GET /health` returns service availability and `mode: SYNTHETIC` without authentication. Payment reads require one `X-Service-Key` header matching the configured `POI_INQUIRY_SERVICE_KEY` (at least 16 characters). Docker listens on port 8092; the root Compose configuration determines whether that port is published. No CORS access is enabled. The browser uses Java, not this service directly.

```text
GET /inquiry/v1/neft/payments/MOCK-NEFT-1001
    ?referenceType=PAYMENT_REFERENCE
    &deploymentId=SYNTHETIC-OBPM
    &hostCode=DEMO-HOST
    &branchCode=DEMO-BRANCH
X-Service-Key: <configured local mock key>
```

All four query parameters are mandatory and fixed to the values shown. Unknown, duplicate and mismatched scope parameters are rejected with 400. Missing/wrong/duplicate service keys return 401. References use `[A-Za-z0-9][A-Za-z0-9._:-]{0,99}`; lookup is case-sensitive. A valid but unknown reference returns 404; the service never substitutes a different sample. POST/PUT/PATCH/DELETE are unsupported. Successful responses and errors have `Cache-Control: no-store`. Startup rejects a fixture larger than the Java consumer's 131,072-byte response limit.

The flat `neft-inquiry-v1` response uses the existing evidence-contract field names but omits `payment.amountMinor`. Java must derive exact paise from `sourceAmountDecimal`, validate the complete response, and create the immutable application evidence snapshot. The service's limited startup checks prevent accidental fixture scope/identity/classification changes; they do not replace Java's authoritative evidence validation. Messages and accounting arrays remain empty with explicit unavailable/not-requested coverage. No raw payload, account number or source-code body appears in the fixtures.

| Reference | Source facts |
| --- | --- |
| `MOCK-NEFT-1001` | One current EC/T queue record and matching recorded timeout |
| `MOCK-NEFT-1002` | Historical timeout exited; separate current P record and later request |
| `MOCK-NEFT-1003` | Partial queue coverage, unmapped response code, unavailable queue-entry time |
| `MOCK-NEFT-1004` | Two current queue records associated with different attempts; preserve ambiguity |

These are fixed snapshots observed at `2026-09-12T07:40:00Z`. A subsequent HTTP read does not advance their business timestamps. Scenario descriptions are documentation only; no expected investigation outcome or evaluation label is served.

## Development

Set `POI_INQUIRY_SERVICE_KEY` and optionally `POI_INQUIRY_DATA_DIR`. The latter defaults to the repository's `data/obpm/inquiry` directory for local source execution; Compose sets `/app/data/inquiry` and mounts the original fixtures read-only.

```powershell
# Run from services/mock-inquiry using an environment with the pinned dependencies.
python -m pip install -r requirements-test.txt
$env:POI_INQUIRY_SERVICE_KEY = 'poi-local-inquiry-key'
python -m uvicorn mock_inquiry.main:app --host 127.0.0.1 --port 8092
python -m pytest
```

From the repository root, `python tools/generate_obpm_inquiry_samples.py --check` compares the committed fixture bytes with deterministic generation; omit `--check` to regenerate. No random seed, database, source export, clock read or evaluation dataset is needed.

The tests exercise HTTP authentication, scope, duplicate arguments, read-only methods, missing references, preservation of uncertain source facts, startup restrictions and exact fixture regeneration. They use FastAPI's test client; actual network/Java integration checks are recorded separately by the root acceptance workflow. A bank service will require its own approved authentication, installed-source mappings and coverage verification before a separate real-data integration can be enabled.
