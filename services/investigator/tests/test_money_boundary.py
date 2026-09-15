from copy import deepcopy

import pytest

from investigator.evidence import SnapshotTools, diagnose
from investigator.facts import build_fact_catalog
from investigator.models import CaseSnapshot


def inspect(case):
    tools = SnapshotTools(CaseSnapshot.model_validate(case))
    for tool in tools.tools.values():
        tool.invoke({"caseId": case["id"]})
    return tools.outputs, diagnose(tools.outputs)


def attach_java(case):
    output = SnapshotTools(CaseSnapshot.model_validate(case)).settlement()
    case["reconciliation"] = {key: output[key] for key in (
        "currency", "validMoney", "captureCount", "captureMinor", "refundMinor", "ledgerNetMinor",
        "providerPayoutMinor", "providerAvailable", "discrepancyMinor")}
    case["reconciliation"]["calculatedBy"] = "java-api"


def refund_case(case):
    case["events"] = case["events"][:1] + [{"id": "EVT-REFUND", "type": "refund.confirmed", "status": "succeeded",
        "attributes": {"amountMinor": 10000, "providerRefundId": "RF-TEST"}}]
    case["ledgerEntries"][0]["type"] = "payment-captured"
    case["ledgerEntries"].append({"id": "LED-REFUND", "type": "refund.posted", "amountMinor": -10000, "currency": "INR"})
    case["provider"].update(refundMinor=10000, payoutMinor=87000)


def test_refund_alias_has_matching_count_total_and_no_false_missing_refund(base_case):
    refund_case(base_case)
    attach_java(base_case)
    outputs, result = inspect(base_case)
    settlement = outputs["compare_settlement"]
    assert settlement["validMoney"] and settlement["captureCount"] == 1
    assert settlement["captureMinor"] == 100000 and settlement["refundMinor"] == 10000
    assert settlement["ledgerNetMinor"] == 87000
    assert result["outcome"] != "MISSING_REFUND"
    catalog = build_fact_catalog(outputs, [{"id": "RB-REFUND@1"}])
    fact = next(row for row in catalog if row["id"] == "FACT-REFUND-OBSERVATIONS")
    assert "1 refund entry totaling INR 100.00" in fact["text"]
    assert "LED-REFUND" in fact["evidenceIds"]


def test_legacy_java_refund_alias_mismatch_abstains_and_cannot_render_zero_refund(base_case):
    refund_case(base_case)
    attach_java(base_case)
    base_case["reconciliation"]["refundMinor"] = 0
    outputs, result = inspect(base_case)
    assert not outputs["compare_settlement"]["validMoney"]
    assert outputs["check_refund"]["ledgerRefundMinor"] is None
    assert result["outcome"] == "INSUFFICIENT_EVIDENCE" and result["action"] == "REQUEST_EVIDENCE"
    assert any("refundMinor" in item for item in result["missingEvidence"])
    catalog = build_fact_catalog(outputs, [{"id": "RB-REFUND@1"}])
    assert not {"FACT-REFUND-OBSERVATIONS", "FACT-CAPTURE-LEDGER", "FACT-SETTLEMENT"}.intersection(row["id"] for row in catalog)
    # Keep the contradictory claim available for audit, not a silently repaired total.
    assert outputs["compare_settlement"]["reconciliation"]["refundMinor"] == 0


@pytest.mark.parametrize("field,value", [
    ("captureCount", 2), ("captureMinor", 99999), ("refundMinor", 1), ("ledgerNetMinor", 96999),
    ("providerPayoutMinor", 96999), ("discrepancyMinor", 1), ("providerAvailable", False),
    ("currency", "USD"), ("validMoney", False), ("captureCount", True), ("providerAvailable", 1),
])
def test_contradictory_java_aggregate_never_authorizes_resolution(base_case, field, value):
    attach_java(base_case)
    base_case["reconciliation"][field] = value
    outputs, result = inspect(base_case)
    assert outputs["compare_settlement"]["validMoney"] is False
    assert result["outcome"] == "INSUFFICIENT_EVIDENCE" and result["action"] == "REQUEST_EVIDENCE"
    assert result["missingEvidence"]


@pytest.mark.parametrize("entry_type,amount", [("SALE", 0), ("payment.captured", -1), ("REFUND_POSTED", 1), ("refund", 0), ("fee", 1)])
@pytest.mark.parametrize("authoritative", [False, True])
def test_invalid_ledger_signs_cannot_be_masked_by_java_valid_money(base_case, entry_type, amount, authoritative):
    if authoritative:
        attach_java(base_case)
    base_case["ledgerEntries"].append({"id": "LED-BAD", "type": entry_type, "amountMinor": amount, "currency": "INR"})
    outputs, result = inspect(base_case)
    assert not outputs["compare_settlement"]["validMoney"]
    assert result["action"] == "REQUEST_EVIDENCE"
    assert any("positive captures, negative refunds and non-positive fees" in reason for reason in result["missingEvidence"])


@pytest.mark.parametrize("capture_type", ["CAPTURE", "payment.captured", "payment-captured", "sale"])
def test_normalized_capture_aliases_zero_fee_and_signed_adjustments_remain_valid(base_case, capture_type):
    base_case["ledgerEntries"][0]["type"] = capture_type
    base_case["ledgerEntries"][1]["amountMinor"] = 0
    for identifier, amount in (("LED-PLUS", 1000), ("LED-MINUS", -500)):
        base_case["ledgerEntries"].append({"id": identifier, "type": "ADJUSTMENT", "amountMinor": amount, "currency": "INR"})
    base_case["provider"].update(feeMinor=0, payoutMinor=100500)
    attach_java(base_case)
    outputs, result = inspect(base_case)
    assert outputs["compare_settlement"]["validMoney"]
    assert outputs["compare_settlement"]["ledgerNetMinor"] == 100500
    assert result["action"] == "RESOLVE_CASE"


@pytest.mark.parametrize("status", ["", " \t", "unknown", "unavailable"])
def test_unavailable_status_never_exposes_numeric_payout_as_authoritative(base_case, status):
    base_case["provider"]["status"] = status
    outputs, result = inspect(base_case)
    settlement = outputs["compare_settlement"]
    assert settlement["providerAvailable"] is False
    assert settlement["providerPayoutMinor"] is None and settlement["discrepancyMinor"] is None
    assert result["action"] == "REQUEST_EVIDENCE"
    attach_java(base_case)
    java_outputs, java_result = inspect(base_case)
    assert java_outputs["compare_settlement"]["validMoney"]
    assert java_result["action"] == "REQUEST_EVIDENCE"
    base_case["reconciliation"].update(providerAvailable=True, providerPayoutMinor=97000, discrepancyMinor=0)
    assert inspect(base_case)[0]["compare_settlement"]["validMoney"] is False
