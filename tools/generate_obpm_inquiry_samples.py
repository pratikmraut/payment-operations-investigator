"""Generate original logical NEFT inquiry records without database/source access.

These fixtures model an API contract, not Oracle physical tables. No evaluation
labels are included in the returned records. --check verifies committed bytes.
"""
from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime
from decimal import Decimal
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "data" / "obpm" / "inquiry"


def build_payment(reference: str, amount: str) -> dict:
    attempt_id = f"{reference}-ECA-001"
    return {
        "schemaVersion": "neft-inquiry-v1",
        "dataClassification": "SYNTHETIC",
        "snapshotId": f"{reference}-SNAPSHOT-001",
        "mappingVersion": "original-synthetic-inquiry-v1",
        "extractedAt": "2026-09-12T07:40:00Z",
        "source": {
            "deploymentId": "SYNTHETIC-OBPM",
            "releaseFamily": "14.7",
            "exactMaintenanceRelease": None,
            "hostCode": "DEMO-HOST",
            "branchCode": "DEMO-BRANCH",
        },
        "payment": {
            "sourcePaymentId": reference,
            "rail": "NEFT",
            "direction": "OUTBOUND",
            "sourceAmountDecimal": amount,
            "currency": "INR",
            "activationDate": "2026-09-12",
            "createdAt": "2026-09-12T07:00:00Z",
            "nativeTransactionStatus": None,
            "statusUnavailableReason": "The original mock inquiry does not supply payment status.",
        },
        "queueRecords": [{
            "evidenceId": f"{reference}-QUEUE-001",
            "sourcePaymentId": reference,
            "queueReference": f"{reference}-QUEUE-REF-001",
            "requestAttemptId": attempt_id,
            "nativeQueueCode": "EC",
            "nativeResponseStatus": "T",
            "enteredAt": "2026-09-12T07:01:00Z",
            "exitedAt": None,
            "isCurrentQueueRecord": True,
            "observedAt": "2026-09-12T07:40:00Z",
        }],
        "externalRequestAttempts": [{
            "evidenceId": f"{reference}-REQUEST-001",
            "requestAttemptId": attempt_id,
            "sourcePaymentId": reference,
            "requestType": "ECA",
            "requestedAt": "2026-09-12T07:00:30Z",
            "timeoutRecordedAt": "2026-09-12T07:01:00Z",
            "externalSystemFinalOutcome": None,
        }],
        "messages": [],
        "accountingEntries": [],
        "sourceCoverage": {
            "queueRecords": {
                "status": "COMPLETE",
                "scope": f"{reference} queue records from 07:00 through 07:40 UTC only.",
                "asOf": "2026-09-12T07:40:00Z",
                "paginationComplete": True,
            },
            "messages": {
                "status": "NOT_REQUESTED",
                "reason": "This original mock inquiry does not supply message history.",
            },
            "externalCoreResponses": {
                "status": "UNAVAILABLE",
                "reason": "The mock has no external core response source.",
            },
            "accountingEntries": {
                "status": "UNAVAILABLE",
                "reason": "The mock supplies no accounting instruction or posting evidence.",
            },
        },
    }


def add_current_attempt(record: dict) -> None:
    reference = record["payment"]["sourcePaymentId"]
    second_queue = deepcopy(record["queueRecords"][0])
    second_queue.update({
        "evidenceId": f"{reference}-QUEUE-002",
        "queueReference": f"{reference}-QUEUE-REF-002",
        "requestAttemptId": f"{reference}-ECA-002",
        "nativeResponseStatus": "P",
        "enteredAt": "2026-09-12T07:25:00Z",
    })
    record["queueRecords"].append(second_queue)
    second_attempt = deepcopy(record["externalRequestAttempts"][0])
    second_attempt.update({
        "evidenceId": f"{reference}-REQUEST-002",
        "requestAttemptId": f"{reference}-ECA-002",
        "requestedAt": "2026-09-12T07:25:00Z",
        "timeoutRecordedAt": None,
    })
    record["externalRequestAttempts"].append(second_attempt)


def make_samples() -> dict[str, dict]:
    timeout = build_payment("MOCK-NEFT-1001", "18450.75")
    pending = build_payment("MOCK-NEFT-1002", "2750.00")
    add_current_attempt(pending)
    pending["queueRecords"][0].update({
        "isCurrentQueueRecord": False,
        "exitedAt": "2026-09-12T07:25:00Z",
    })
    partial = build_payment("MOCK-NEFT-1003", "925.25")
    partial["queueRecords"][0].update({
        "nativeResponseStatus": "DEMO_UNMAPPED",
        "enteredAt": None,
    })
    partial["externalRequestAttempts"][0]["timeoutRecordedAt"] = None
    partial["sourceCoverage"]["queueRecords"].update({
        "status": "PARTIAL",
        "scope": "MOCK-NEFT-1003 queue history through 07:40 UTC; not all pages supplied.",
        "paginationComplete": False,
        "reason": "Original partial-page example with an unmapped response code.",
    })
    ambiguous = build_payment("MOCK-NEFT-1004", "6040.50")
    add_current_attempt(ambiguous)
    return {
        f"{record['payment']['sourcePaymentId']}.json": record
        for record in (timeout, pending, partial, ambiguous)
    }


def validate_samples(samples: dict[str, dict]) -> None:
    """Assert source facts and correlation without computing investigation labels."""
    identifiers: set[str] = set()
    for name, record in samples.items():
        assert record["schemaVersion"] == "neft-inquiry-v1"
        assert record["dataClassification"] == "SYNTHETIC"
        payment = record["payment"]
        reference = payment["sourcePaymentId"]
        assert name == f"{reference}.json"
        assert "amountMinor" not in payment
        amount = Decimal(payment["sourceAmountDecimal"])
        assert amount > 0 and amount * 100 == (amount * 100).to_integral_value()
        assert amount * 100 <= 9_007_199_254_740_991
        cutoff = datetime.fromisoformat(record["extractedAt"].replace("Z", "+00:00"))
        attempts = {a["requestAttemptId"]: a for a in record["externalRequestAttempts"]}
        assert len(attempts) == len(record["externalRequestAttempts"])
        for row in record["externalRequestAttempts"] + record["queueRecords"]:
            assert row["evidenceId"] not in identifiers
            identifiers.add(row["evidenceId"])
            assert row["sourcePaymentId"] == reference
            for field in ("requestedAt", "timeoutRecordedAt", "enteredAt", "exitedAt", "observedAt"):
                if row.get(field):
                    assert datetime.fromisoformat(row[field].replace("Z", "+00:00")) <= cutoff
        for queue in record["queueRecords"]:
            assert queue["requestAttemptId"] in attempts
            assert not (queue["isCurrentQueueRecord"] and queue["exitedAt"])
        assert record["messages"] == record["accountingEntries"] == []


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    samples = make_samples()
    validate_samples(samples)
    if not args.check:
        OUTPUT.mkdir(parents=True, exist_ok=True)
    for filename, record in samples.items():
        encoded = (json.dumps(record, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
        path = OUTPUT / filename
        if args.check:
            if not path.exists() or path.read_bytes() != encoded:
                raise SystemExit(f"Generated fixture differs: {path.name}")
        else:
            path.write_bytes(encoded)
    print(f"{'Verified' if args.check else 'Generated'} {len(samples)} original synthetic inquiry records.")


if __name__ == "__main__":
    main()
