"""Generate original synthetic OBPM-shaped evidence; never read Oracle data.

This generator is independent of the original payment fixture/evaluation files.
Run with --check to validate content and compare the committed bytes without writes.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime
from decimal import Decimal
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SAMPLES = ROOT / "data" / "obpm" / "samples"
RUNBOOKS = ROOT / "data" / "knowledge" / "obpm-runbooks.json"
SAFE_INTEGER_MAX = 9_007_199_254_740_991


def timestamp(value: str) -> datetime:
    assert value.endswith("Z"), "Fixture timestamps must explicitly use UTC"
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def unavailable_sources() -> dict:
    return {
        "messages": {
            "status": "NOT_REQUESTED",
            "reason": "This original synthetic slice does not supply message history.",
        },
        "externalCoreResponses": {
            "status": "UNAVAILABLE",
            "reason": "No DDA/core response source is connected in this synthetic example.",
        },
        "accountingEntries": {
            "status": "UNAVAILABLE",
            "reason": "No accounting instruction or posting evidence is supplied.",
        },
    }


def make_samples() -> dict[str, dict]:
    first = {
        "schemaVersion": "obpm-evidence-v1",
        "dataClassification": "SYNTHETIC",
        "snapshotId": "DEMO-NEFT-0001-SNAPSHOT-001",
        "mappingVersion": "original-synthetic-neft-v1",
        "extractedAt": "2026-09-12T05:20:00Z",
        "source": {
            "deploymentId": "SYNTHETIC-OBPM",
            "releaseFamily": "14.7",
            "exactMaintenanceRelease": None,
            "hostCode": "DEMO-HOST",
            "branchCode": "DEMO-BRANCH",
        },
        "payment": {
            "sourcePaymentId": "DEMO-NEFT-0001",
            "rail": "NEFT",
            "direction": "OUTBOUND",
            "sourceAmountDecimal": "12500.00",
            "amountMinor": 1_250_000,
            "currency": "INR",
            "activationDate": "2026-09-12",
            "createdAt": "2026-09-12T05:00:00Z",
            "nativeTransactionStatus": None,
            "statusUnavailableReason": "The synthetic source does not supply payment status.",
        },
        "queueRecords": [
            {
                "evidenceId": "DEMO-NEFT-0001-QUEUE-001",
                "sourcePaymentId": "DEMO-NEFT-0001",
                "queueReference": "DEMO-ECA-QUEUE-0001-001",
                "requestAttemptId": "DEMO-ECA-ATTEMPT-0001-001",
                "nativeQueueCode": "EC",
                "nativeResponseStatus": "T",
                "enteredAt": "2026-09-12T05:01:00Z",
                "exitedAt": None,
                "isCurrentQueueRecord": True,
                "observedAt": "2026-09-12T05:20:00Z",
            }
        ],
        "externalRequestAttempts": [
            {
                "evidenceId": "DEMO-NEFT-0001-REQUEST-001",
                "requestAttemptId": "DEMO-ECA-ATTEMPT-0001-001",
                "sourcePaymentId": "DEMO-NEFT-0001",
                "requestType": "ECA",
                "requestedAt": "2026-09-12T05:00:30Z",
                "timeoutRecordedAt": "2026-09-12T05:01:00Z",
                "externalSystemFinalOutcome": None,
            }
        ],
        "messages": [],
        "accountingEntries": [],
        "sourceCoverage": {
            "queueRecords": {
                "status": "COMPLETE",
                "scope": "DEMO-NEFT-0001 queue records from 05:00 through 05:20 UTC only.",
                "asOf": "2026-09-12T05:20:00Z",
                "paginationComplete": True,
            },
            **unavailable_sources(),
        },
    }

    gaps = deepcopy(first)
    gaps["snapshotId"] = "DEMO-NEFT-0002-SNAPSHOT-001"
    gaps["payment"].update(
        sourcePaymentId="DEMO-NEFT-0002",
        sourceAmountDecimal="875.25",
        amountMinor=87_525,
    )
    gaps["queueRecords"][0].update(
        evidenceId="DEMO-NEFT-0002-QUEUE-001",
        sourcePaymentId="DEMO-NEFT-0002",
        queueReference="DEMO-ECA-QUEUE-0002-001",
        requestAttemptId="DEMO-ECA-ATTEMPT-0002-001",
        nativeResponseStatus="DEMO_UNMAPPED",
        enteredAt=None,
    )
    gaps["externalRequestAttempts"][0].update(
        evidenceId="DEMO-NEFT-0002-REQUEST-001",
        sourcePaymentId="DEMO-NEFT-0002",
        requestAttemptId="DEMO-ECA-ATTEMPT-0002-001",
        timeoutRecordedAt=None,
    )
    gaps["sourceCoverage"]["queueRecords"] = {
        "status": "PARTIAL",
        "scope": "DEMO-NEFT-0002 queue history through 05:20 UTC; not all pages supplied.",
        "asOf": "2026-09-12T05:20:00Z",
        "paginationComplete": False,
        "reason": "Original synthetic partial-page example; the response code has no approved mapping.",
    }

    updated = deepcopy(first)
    updated["snapshotId"] = "DEMO-NEFT-0001-SNAPSHOT-002"
    updated["extractedAt"] = "2026-09-12T05:40:00Z"
    updated["queueRecords"][0].update(
        exitedAt="2026-09-12T05:25:00Z",
        isCurrentQueueRecord=False,
        observedAt="2026-09-12T05:40:00Z",
    )
    updated["queueRecords"].append(
        {
            "evidenceId": "DEMO-NEFT-0001-QUEUE-002",
            "sourcePaymentId": "DEMO-NEFT-0001",
            "queueReference": "DEMO-ECA-QUEUE-0001-002",
            "requestAttemptId": "DEMO-ECA-ATTEMPT-0001-002",
            "nativeQueueCode": "EC",
            "nativeResponseStatus": "P",
            "enteredAt": "2026-09-12T05:25:00Z",
            "exitedAt": None,
            "isCurrentQueueRecord": True,
            "observedAt": "2026-09-12T05:40:00Z",
        }
    )
    updated["externalRequestAttempts"].append(
        {
            "evidenceId": "DEMO-NEFT-0001-REQUEST-002",
            "requestAttemptId": "DEMO-ECA-ATTEMPT-0001-002",
            "sourcePaymentId": "DEMO-NEFT-0001",
            "requestType": "ECA",
            "requestedAt": "2026-09-12T05:25:00Z",
            "timeoutRecordedAt": None,
            "externalSystemFinalOutcome": None,
        }
    )
    updated["sourceCoverage"]["queueRecords"].update(
        scope="DEMO-NEFT-0001 queue records from 05:00 through 05:40 UTC only.",
        asOf="2026-09-12T05:40:00Z",
    )
    return {
        "eca-timeout.json": first,
        "evidence-gaps.json": gaps,
        "eca-timeout-updated.json": updated,
    }


def make_runbooks() -> list[dict]:
    scope = {
        "version": 1,
        "tenantId": "*",
        "domain": "OBPM_NEFT",
        "rail": "NEFT",
        "direction": "OUTBOUND",
        "releaseFamily": "14.7",
        "effectiveFrom": "2026-01-01",
        "effectiveTo": None,
        "source": "Original synthetic NEFT operating guidance; informed by public Oracle documentation",
    }
    return [
        {
            **scope,
            "id": "RB-OBPM-ECA-TIMEOUT",
            "title": "NEFT outbound: recorded ECA timeout and next evidence checks",
            "content": (
                "For this original synthetic OBPM 14.7 outbound NEFT workflow, correlate the payment, "
                "current EC queue record and its ECA request attempt. A current T response means that "
                "OBPM recorded an ECA timeout. Confirm queue coverage is complete for the declared "
                "scope and cutoff and that attempt and observation times agree. A historical timeout "
                "does not establish the state of a newer attempt. The recorded timeout does not prove "
                "insufficient funds, absence of a DDA amount block, posting failure, beneficiary-credit "
                "failure or settlement. Request the authorized external-core outcome for that exact "
                "attempt and accounting or message evidence where relevant. Preserve unavailable "
                "groups as unknown. Oracle documents ECA Resend for timeout with a new reference "
                "and no native authorization step. Retry instead requires a rejected ECA record, "
                "cancellation not done and a current activation date; it supports save and authorize. "
                "These facts are conditional guidance, "
                "not permission to execute either operation. Confirm the exact release, latest state "
                "and approved local procedure before recommending recovery. Propose REQUEST_EVIDENCE "
                "or an appropriate human case escalation. This milestone cannot resolve an OBPM case "
                "or perform payment actions; independent case review changes only the investigation workflow."
            ),
            "keywords": ["NEFT", "OBPM", "ECA", "EC", "T", "timeout", "timed out", "request attempt", "complete queue coverage"],
            "sourceUrl": "https://docs.oracle.com/en/industries/financial-services/banking-payments/14.7.0.0.0/obpeq/external-credit-approval-queue.html",
        },
        {
            **scope,
            "id": "RB-OBPM-EVIDENCE-GAPS",
            "title": "NEFT outbound: incomplete, unknown or changed evidence",
            "content": (
                "Preserve native payment and queue codes without guessing their meaning. In this "
                "original synthetic OBPM 14.7 outbound NEFT workflow, unmapped codes, partial queue "
                "coverage, ambiguous current records, missing attempt correlation or conflicting times "
                "require evidence. Name the missing source, identity and cutoff. COMPLETE covers only "
                "the declared scope; PARTIAL, UNAVAILABLE and NOT_REQUESTED cannot establish absence "
                "of an event. Request current payment status, complete correlated queue history and "
                "the external-core response for the current ECA attempt. A pending P record must not "
                "inherit the timeout conclusion of an earlier T record. A null enteredAt means unknown "
                "queue age. A refreshed snapshot supersedes evidence for new assessments but preserves "
                "historical investigations; reassess before reviewing a proposal based on old evidence. "
                "SFMS message acknowledgments and N10 have different meanings: a correctly correlated "
                "N10 reports beneficiary credit, whereas an SFMS acknowledgment alone does not establish "
                "that credit. This first milestone supplies neither messages nor accounting records, "
                "so it cannot assess those events. Request the exact maintenance release and eligible "
                "local guidance. Propose REQUEST_EVIDENCE; never infer success, failure or permission "
                "to resend from missing records, and never resolve the OBPM case in this milestone."
            ),
            "keywords": ["NEFT", "OBPM", "ECA", "unknown", "unmapped", "partial", "unavailable", "missing evidence", "coverage", "P", "pending", "changed snapshot"],
            "sourceUrl": "https://docs.oracle.com/en/industries/financial-services/banking-payments/14.7.0.0.0/innug/neft-outbound-transaction-view.html",
        },
    ]


def validate_samples(samples: dict[str, dict]) -> None:
    for name, sample in samples.items():
        assert sample["schemaVersion"] == "obpm-evidence-v1", name
        assert sample["dataClassification"] == "SYNTHETIC", name
        assert "tenantId" not in sample and "expectedOutcome" not in sample, name
        assert sample["messages"] == sample["accountingEntries"] == [], name
        payment = sample["payment"]
        exact_paise = Decimal(payment["sourceAmountDecimal"]) * 100
        assert exact_paise == payment["amountMinor"], name
        assert 0 < payment["amountMinor"] <= SAFE_INTEGER_MAX, name
        assert payment["currency"] == "INR", name
        cutoff = timestamp(sample["extractedAt"])
        attempts = {row["requestAttemptId"]: row for row in sample["externalRequestAttempts"]}
        assert len(attempts) == len(sample["externalRequestAttempts"]), name
        evidence_ids = set()
        for row in sample["queueRecords"] + sample["externalRequestAttempts"]:
            assert row["sourcePaymentId"] == payment["sourcePaymentId"], name
            assert row["evidenceId"] not in evidence_ids, name
            evidence_ids.add(row["evidenceId"])
        for queue in sample["queueRecords"]:
            request = attempts[queue["requestAttemptId"]]
            requested = timestamp(request["requestedAt"])
            observed = timestamp(queue["observedAt"])
            assert requested <= observed <= cutoff, name
            if request["timeoutRecordedAt"] is not None:
                assert requested <= timestamp(request["timeoutRecordedAt"]) <= observed, name
            if queue["enteredAt"] is not None:
                assert requested <= timestamp(queue["enteredAt"]) <= observed, name
            if queue["exitedAt"] is not None:
                assert timestamp(queue["enteredAt"]) <= timestamp(queue["exitedAt"]) <= observed, name
            if queue["isCurrentQueueRecord"]:
                assert queue["exitedAt"] is None, name
        assert sum(row["isCurrentQueueRecord"] for row in sample["queueRecords"]) == 1, name
        for coverage in sample["sourceCoverage"].values():
            if coverage["status"] == "COMPLETE":
                assert coverage["scope"] and coverage["paginationComplete"] is True, name
                assert timestamp(coverage["asOf"]) <= cutoff, name
            else:
                assert coverage["reason"], name
        coverage = sample["sourceCoverage"]["queueRecords"]
        if coverage["status"] == "COMPLETE":
            assert all(timestamp(row["observedAt"]) <= timestamp(coverage["asOf"])
                       for row in sample["queueRecords"]), name

    original = samples["eca-timeout.json"]
    changed = samples["eca-timeout-updated.json"]
    assert original["source"] == changed["source"]
    assert original["payment"] == changed["payment"]
    assert timestamp(original["extractedAt"]) < timestamp(changed["extractedAt"])
    assert changed["queueRecords"][0]["isCurrentQueueRecord"] is False
    assert changed["queueRecords"][1]["nativeResponseStatus"] == "P"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Validate and compare without writing")
    args = parser.parse_args()
    samples = make_samples()
    validate_samples(samples)
    books = make_runbooks()
    assert {book["id"] for book in books} == {"RB-OBPM-ECA-TIMEOUT", "RB-OBPM-EVIDENCE-GAPS"}
    outputs = {SAMPLES / name: body for name, body in samples.items()}
    outputs[RUNBOOKS] = books
    for path, body in outputs.items():
        expected = (json.dumps(body, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        if args.check:
            if not path.is_file() or path.read_bytes() != expected:
                raise SystemExit(f"Generated data differs: {path.relative_to(ROOT)}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(expected)
    mode = "Verified" if args.check else "Generated"
    print(f"{mode} 3 original synthetic snapshots and 2 scoped runbooks in {len(outputs)} files.")


if __name__ == "__main__":
    main()
