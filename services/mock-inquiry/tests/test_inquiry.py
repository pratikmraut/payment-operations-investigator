from __future__ import annotations

from decimal import Decimal
import importlib.util
import json
from pathlib import Path

from fastapi.testclient import TestClient
import pytest

from mock_inquiry.main import REFERENCE_IDS, SCOPE, Settings, create_app, load_catalog

ROOT = Path(__file__).resolve().parents[3]
DATA = ROOT / "data" / "obpm" / "inquiry"
KEY = "original-mock-test-service-key"
HEADERS = {"X-Service-Key": KEY}
URL = "/inquiry/v1/neft/payments/MOCK-NEFT-1001"


@pytest.fixture
def client():
    with TestClient(create_app(Settings(DATA, KEY))) as instance:
        yield instance


def test_health_identifies_synthetic_service_without_key(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "UP", "service": "synthetic-neft-inquiry", "mode": "SYNTHETIC"}
    assert response.headers["cache-control"] == "no-store"


@pytest.mark.parametrize("headers", [{}, {"X-Service-Key": "wrong"},
                                     [("X-Service-Key", KEY), ("X-Service-Key", KEY)]])
def test_missing_wrong_or_duplicate_service_keys_do_not_return_evidence(client, headers):
    response = client.get(URL, params=SCOPE, headers=headers)
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "UNAUTHORIZED"
    assert "payment" not in response.json()
    assert KEY not in response.text


@pytest.mark.parametrize("reference", REFERENCE_IDS)
def test_exact_scoped_lookup_preserves_source_record_and_decimal_amount(client, reference):
    response = client.get(f"/inquiry/v1/neft/payments/{reference}", params=SCOPE, headers=HEADERS)
    expected = json.loads((DATA / f"{reference}.json").read_text(encoding="utf-8"))
    assert response.status_code == 200
    assert response.headers["content-type"] == "application/json"
    assert response.headers["cache-control"] == "no-store"
    assert response.json() == expected
    assert "amountMinor" not in response.json()["payment"]
    assert isinstance(response.json()["payment"]["sourceAmountDecimal"], str)
    assert Decimal(response.json()["payment"]["sourceAmountDecimal"]) > 0


@pytest.mark.parametrize("params", [
    {},
    {**SCOPE, "referenceType": "UTR"},
    {**SCOPE, "deploymentId": "OTHER"},
    {**SCOPE, "hostCode": "OTHER"},
    {**SCOPE, "branchCode": "OTHER"},
    {**SCOPE, "tenantId": "OTHER"},
    list(SCOPE.items()) + [("referenceType", "PAYMENT_REFERENCE")],
])
def test_scope_cannot_be_expanded_or_ambiguously_supplied(client, params):
    response = client.get(URL, params=params, headers=HEADERS)
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_SCOPE"


@pytest.mark.parametrize("reference", ["MOCK-NEFT-9999", "lowercase", "Neft:source.001", "X" * 100])
def test_missing_reference_does_not_substitute_a_sample(client, reference):
    response = client.get(f"/inquiry/v1/neft/payments/{reference}", params=SCOPE, headers=HEADERS)
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "PAYMENT_NOT_FOUND"


@pytest.mark.parametrize("reference", ["MOCK%20NEFT", "X" * 101, "..", "-MOCK-NEFT"])
def test_unsupported_reference_cannot_select_a_file(client, reference):
    response = client.get(f"/inquiry/v1/neft/payments/{reference}", params=SCOPE, headers=HEADERS)
    # URL path normalization may resolve '..' before routing; neither path exposes data.
    assert response.status_code in (400, 404)
    assert "payment" not in response.json()


@pytest.mark.parametrize("method", ["post", "put", "patch", "delete"])
def test_inquiry_has_no_payment_mutation_operations(client, method):
    response = client.request(method.upper(), URL, params=SCOPE, headers=HEADERS, json={"action": "retry"})
    assert response.status_code == 405
    read = client.get(URL, params=SCOPE, headers=HEADERS)
    assert read.json() == json.loads((DATA / "MOCK-NEFT-1001.json").read_text(encoding="utf-8"))


def test_pending_partial_and_ambiguous_source_facts_are_preserved(client):
    def inquiry(reference):
        return client.get(f"/inquiry/v1/neft/payments/{reference}", params=SCOPE, headers=HEADERS).json()

    pending = inquiry("MOCK-NEFT-1002")
    assert [(r["nativeResponseStatus"], r["isCurrentQueueRecord"]) for r in pending["queueRecords"]] == [
        ("T", False), ("P", True),
    ]
    partial = inquiry("MOCK-NEFT-1003")
    assert partial["sourceCoverage"]["queueRecords"]["paginationComplete"] is False
    assert partial["queueRecords"][0]["nativeResponseStatus"] == "DEMO_UNMAPPED"
    assert partial["queueRecords"][0]["enteredAt"] is None
    ambiguous = inquiry("MOCK-NEFT-1004")
    assert sum(r["isCurrentQueueRecord"] for r in ambiguous["queueRecords"]) == 2
    assert len({r["requestAttemptId"] for r in ambiguous["queueRecords"]}) == 2


@pytest.mark.parametrize("change", ["classification", "schema", "scope", "minor_units", "identity", "extra_field"])
def test_startup_rejects_catalog_outside_mock_contract(tmp_path, change):
    for reference in REFERENCE_IDS:
        record = json.loads((DATA / f"{reference}.json").read_text(encoding="utf-8"))
        if reference == REFERENCE_IDS[0]:
            if change == "classification":
                record["dataClassification"] = "BANK_DATA"
            elif change == "schema":
                record["schemaVersion"] = "obpm-evidence-v1"
            elif change == "scope":
                record["source"]["branchCode"] = "OTHER"
            elif change == "minor_units":
                record["payment"]["amountMinor"] = 1845075
            elif change == "identity":
                record["payment"]["sourcePaymentId"] = "MOCK-NEFT-1002"
            elif change == "extra_field":
                record["unexpected"] = "content"
        (tmp_path / f"{reference}.json").write_text(json.dumps(record), encoding="utf-8")
    with pytest.raises(ValueError):
        load_catalog(Settings(tmp_path, KEY))


def test_service_requires_explicit_configured_key():
    with pytest.raises(ValueError, match="POI_INQUIRY_SERVICE_KEY"):
        load_catalog(Settings(DATA, ""))


def test_startup_rejects_fixture_larger_than_java_response_bound(tmp_path):
    (tmp_path / "MOCK-NEFT-1001.json").write_bytes(b" " * 131_073)
    with pytest.raises(ValueError, match="exceeds the supported size"):
        load_catalog(Settings(tmp_path, KEY))


def test_generator_reproduces_committed_records_without_reading_other_fixtures():
    specification = importlib.util.spec_from_file_location("inquiry_generator", ROOT / "tools" / "generate_obpm_inquiry_samples.py")
    generator = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(generator)
    generated = generator.make_samples()
    generator.validate_samples(generated)
    for filename, record in generated.items():
        assert (DATA / filename).read_bytes() == (json.dumps(record, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
