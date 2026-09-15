"""Actual diagnostic boundary regression: receipt ties do not establish order."""
from datetime import datetime, timedelta, timezone

import pytest

from investigator.evidence import SnapshotTools, diagnose, instant
from investigator.models import CaseSnapshot


def run(case):
    tools = SnapshotTools(CaseSnapshot.model_validate(case))
    for tool in tools.tools.values():
        tool.invoke({"caseId": case["id"]})
    return tools.outputs["inspect_webhooks"], diagnose(tools.outputs)


@pytest.mark.parametrize("second_receipt", ["2026-09-11T09:00:10Z", "2026-09-11T14:30:10+05:30"])
def test_same_instant_receipts_cannot_fabricate_out_of_order_resolution(base_case, second_receipt):
    base_case["events"] = base_case["events"][:1]
    base_case["webhooks"] = [
        {"id": "WH-CAP", "providerEventId": "PE-CAP", "occurredAt": "2026-09-11T09:00:05Z", "receivedAt": "2026-09-11T09:00:10Z"},
        {"id": "WH-AUTH", "providerEventId": "PE-AUTH", "occurredAt": "2026-09-11T09:00:02Z", "receivedAt": second_receipt},
    ]
    output, assessment = run(base_case)
    assert output["orderingInversions"] == []
    assert assessment["outcome"] == "INSUFFICIENT_EVIDENCE"
    assert assessment["action"] == "REQUEST_EVIDENCE"


@pytest.mark.parametrize("reverse_tied_storage_order", [False, True])
def test_earlier_receipt_witness_survives_a_later_tied_group(base_case, reverse_tied_storage_order):
    base_case["events"] = base_case["events"][:1]
    earlier = {"id": "WH-EARLIER-RECEIPT", "providerEventId": "PE-1", "occurredAt": "2026-09-11T09:00:05Z", "receivedAt": "2026-09-11T09:00:10Z", "processingStatus": "APPLIED"}
    group = [
        {"id": "WH-NEWER-EVENT", "providerEventId": "PE-2", "occurredAt": "2026-09-11T09:00:08Z", "receivedAt": "2026-09-11T09:00:20Z", "processingStatus": "APPLIED"},
        {"id": "WH-OLDER-EVENT", "providerEventId": "PE-3", "occurredAt": "2026-09-11T09:00:02Z", "receivedAt": "2026-09-11T09:00:20Z", "processingStatus": "IGNORED_STALE"},
    ]
    base_case["webhooks"] = [earlier] + (list(reversed(group)) if reverse_tied_storage_order else group)
    output, assessment = run(base_case)
    assert output["orderingInversions"] == [["WH-EARLIER-RECEIPT", "WH-OLDER-EVENT"]]
    assert assessment["outcome"] == "OUT_OF_ORDER_WEBHOOK"


def test_many_reversed_events_emit_only_one_valid_witness_per_record(base_case):
    start = datetime(2026, 9, 11, 9, tzinfo=timezone.utc)
    base_case["webhooks"] = [{"id": f"WH-{i}", "providerEventId": f"PE-{i}", "occurredAt": (start + timedelta(seconds=100-i)).isoformat(), "receivedAt": (start + timedelta(seconds=101+i)).isoformat()} for i in range(100)]
    output = SnapshotTools(CaseSnapshot.model_validate(base_case)).webhook_analysis()
    assert len(output["orderingInversions"]) == 99
    records = {r["id"]: r for r in output["records"]}
    for before_id, after_id in output["orderingInversions"]:
        before, after = records[before_id], records[after_id]
        assert instant(before["receivedAt"]) < instant(after["receivedAt"])
        assert instant(before["occurredAt"]) > instant(after["occurredAt"])
