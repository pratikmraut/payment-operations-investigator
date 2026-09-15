from copy import deepcopy
from dataclasses import replace
import json

import pytest

from investigator.config import Settings
from investigator.models import InvestigationRequest


@pytest.fixture
def settings(tmp_path):
    documents = [
        ("RB-TIMEOUT", "Transport timeout", "A transport timeout is not proof of failure. Check succeeded provider status, one matching capture and idempotency before retrying."),
        ("RB-DUPLICATE", "Duplicate webhook", "Repeated delivery of the same providerEventId should be applied exactly once. Preserve idempotency and reconcile capture records."),
        ("RB-ORDERING", "Out of order webhook", "A late stale webhook can arrive out of order. Compare event occurrence and receipt timestamps against terminal provider state."),
        ("RB-REFUND", "Missing refund", "Reconcile a confirmed or requested refund amount with provider refund and ledger posting. Escalate missing or conflicting refund evidence."),
        ("RB-FAILURE", "Provider failed", "A failed or declined provider status without capture requires escalation. Do not automatically retry payments."),
        ("RB-EVIDENCE", "Insufficient evidence", "Missing or conflicting provider and ledger reconciliation evidence requires additional facts before a reliable conclusion."),
    ]
    path = tmp_path / "runbooks.json"
    path.write_text(json.dumps([{"id": identifier, "version": 1, "title": title, "content": content, "keywords": [], "tenantId": "*", "effectiveFrom": "2026-01-01", "effectiveTo": None, "source": "Original simulated operating policy"} for identifier, title, content in documents]), encoding="utf-8")
    return Settings(service_key="test-service-key", knowledge_path=path, checkpoint_path=tmp_path / "checkpoints.sqlite", ollama_base_url="http://127.0.0.1:1", model_timeout_seconds=0.2)


@pytest.fixture
def base_case():
    return {
        "id": "CASE-TEST", "tenantId": "northstar", "paymentId": "PAY-TEST", "amountMinor": 100000, "currency": "INR", "policyDate": "2026-09-11",
        "title": "This text must not classify the case", "description": "Untrusted descriptive text",
        "events": [
            {"id": "EVT-REQUEST", "type": "REQUEST_ACCEPTED", "occurredAt": "2026-09-11T09:00:00Z", "status": "SUCCESS", "attributes": {"providerPaymentId": "PRV-TEST", "idempotencyKey": "IDEM-TEST"}},
            {"id": "EVT-TIMEOUT", "type": "REQUEST_TIMEOUT", "occurredAt": "2026-09-11T09:00:05Z", "status": "TIMEOUT", "attributes": {}},
        ],
        "ledgerEntries": [{"id": "LED-CAPTURE", "type": "CAPTURE", "amountMinor": 100000, "currency": "INR", "occurredAt": "2026-09-11T09:00:02Z", "reference": "PRV-TEST"}, {"id": "LED-FEE", "type": "FEE", "amountMinor": -3000, "currency": "INR", "occurredAt": "2026-09-11T09:00:03Z", "reference": "PRV-TEST"}],
        "webhooks": [],
        "provider": {"status": "SUCCEEDED", "paymentId": "PRV-TEST", "amountMinor": 100000, "feeMinor": 3000, "refundMinor": 0, "payoutMinor": 97000, "asOf": "2026-09-11T09:03:00Z"},
    }


@pytest.fixture
def request_for():
    def make(case, identifier="INV-TEST", mode="replay"):
        return InvestigationRequest(investigationId=identifier, case=deepcopy(case), question="Investigate this payment", mode=mode, actorId="analyst")
    return make
