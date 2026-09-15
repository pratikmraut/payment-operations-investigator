"""Exercise the local synthetic inquiry HTTP -> Java -> durable worker path.

Only fixed, original MOCK-NEFT references are fetched. Reruns preserve case and
evidence history, and append new investigations to those four synthetic cases.
Replay is the default and does not make any language-model calls.
"""

import argparse
from copy import deepcopy
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import sys
import time

from acceptance import Client


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_OUTCOMES = {
    "MOCK-NEFT-1001": "OBPM_ECA_TIMEOUT",
    "MOCK-NEFT-1002": "INSUFFICIENT_EVIDENCE",
    "MOCK-NEFT-1003": "INSUFFICIENT_EVIDENCE",
    "MOCK-NEFT-1004": "INSUFFICIENT_EVIDENCE",
}
EXPECTED_TOOLS = {
    "inspect_obpm_payment",
    "inspect_obpm_queue",
    "inspect_obpm_eca_requests",
    "inspect_obpm_coverage",
}


def check(condition, message):
    # Keep acceptance assertions active even if Python is invoked with -O.
    if not condition:
        raise AssertionError(message)


def run(args, report):
    def passed(name):
        report["checks"].append(name)
        print("PASS " + name, flush=True)

    guest = Client(args.base_url)
    analyst, viewer, other = [Client(args.base_url) for _ in range(3)]
    check(guest.request("GET", "/api/health")["status"] == "UP", "API must be healthy")
    for client, username in ((analyst, "analyst"), (viewer, "viewer"), (other, "other")):
        client.login(username)
    passed("actual Java API healthy; analyst, viewer and second-tenant sessions authenticate")

    original_cases = {item["id"]: item for item in analyst.request("GET", "/api/cases")["items"]}
    guest.request("GET", "/api/obpm/inquiry", expected=401)
    # Spring's CSRF filter rejects this unsafe request before authentication.
    guest.request("POST", "/api/obpm/inquiries", {"paymentReference": "MOCK-NEFT-1001"}, expected=403)
    config = analyst.request("GET", "/api/obpm/inquiry")
    check(config.get("enabled") is True, "Synthetic inquiry must be enabled for this suite")
    check(config.get("mode") == "SYNTHETIC_MOCK", "Acceptance must target the synthetic mock only")
    check(config.get("referenceType") == "PAYMENT_REFERENCE", "Expected explicit payment-reference lookup")
    examples = {item["reference"]: item["label"] for item in config.get("examples", [])}
    check(set(examples) == set(EXPECTED_OUTCOMES) and all(examples.values()), "Expected four labelled examples independent of catalog order")
    check(viewer.request("GET", "/api/obpm/inquiry") == config, "Authenticated viewer may read inquiry configuration")
    report["inquiryConfiguration"] = config
    passed("inquiry is authenticated and explicitly identifies synthetic mock/reference scope")

    before_receipts = analyst.request("GET", "/api/obpm/imports")
    before_cases = analyst.request("GET", "/api/cases")
    viewer.request("POST", "/api/obpm/inquiries", {"paymentReference": "MOCK-NEFT-1001"}, expected=403)
    analyst.request("POST", "/api/obpm/inquiries", {"paymentReference": "MOCK-NEFT-1001"}, csrf=False, expected=403)
    passed("viewer fetch/import and missing-CSRF fetch/import denied")
    analyst.request("POST", "/api/obpm/inquiries", {"paymentReference": "http://127.0.0.1:65535/private"}, expected=400)
    analyst.request("POST", "/api/obpm/inquiries", {"paymentReference": "MOCK-NEFT-1001", "url": "http://127.0.0.1:65535/private"}, expected=400)
    passed("URL-shaped reference and unsupported request field rejected")
    analyst.request("POST", "/api/obpm/inquiries", {"paymentReference": "MOCK-NEFT-9999"}, expected=404)
    check(analyst.request("GET", "/api/obpm/imports") == before_receipts, "Rejected inquiry requests must not append import receipts")
    check(analyst.request("GET", "/api/cases") == before_cases, "Rejected inquiry requests must not create or change cases")
    passed("unknown payment returns 404; all rejected requests leave cases and imports unchanged")

    records = {}
    for reference in EXPECTED_OUTCOMES:
        source_fixture = json.loads((ROOT / "data/obpm/inquiry" / f"{reference}.json").read_text(encoding="utf-8"))
        check(source_fixture["schemaVersion"] == "neft-inquiry-v1" and "amountMinor" not in source_fixture["payment"],
              "Mock must supply the distinct inquiry contract with a source decimal and no precomputed paise")
        receipt = analyst.request("POST", "/api/obpm/inquiries", {"paymentReference": reference})
        check(receipt["status"] in {"CREATED", "UNCHANGED"}, f"{reference}: fixed fixture must create or reuse its stored snapshot")
        case_id = receipt["caseId"]
        record = analyst.request("GET", f"/api/cases/{case_id}")
        evidence = record["obpm"]
        payment = evidence["payment"]
        check(record["domain"] == "OBPM_NEFT" and record["paymentId"] == reference, f"{reference}: wrong stored case identity/domain")
        check(payment["sourcePaymentId"] == reference, f"{reference}: source payment reference changed")
        check(evidence["schemaVersion"] == "obpm-evidence-v1", "Java must map the inquiry contract into the validated evidence contract")
        check(evidence["dataClassification"] == "SYNTHETIC", "Mock evidence must remain explicitly synthetic")
        check(evidence["mappingVersion"] == "original-synthetic-inquiry-v1", "Wrong inquiry mapping version")
        check(evidence["source"] == {
            "deploymentId": "SYNTHETIC-OBPM", "releaseFamily": "14.7",
            "exactMaintenanceRelease": None, "hostCode": "DEMO-HOST", "branchCode": "DEMO-BRANCH",
        }, f"{reference}: wrong synthetic source metadata")
        minor = Decimal(payment["sourceAmountDecimal"]) * 100
        check(minor == minor.to_integral_value(), f"{reference}: source amount must be exactly representable in paise")
        check(payment["amountMinor"] == int(minor) == record["amountMinor"], f"{reference}: source decimal/Java amount mismatch")
        check(payment["currency"] == record["currency"] == "INR", "Currency must be retained")
        expected_evidence = deepcopy(source_fixture)
        expected_evidence["schemaVersion"] = "obpm-evidence-v1"
        expected_evidence["payment"]["amountMinor"] = int(Decimal(source_fixture["payment"]["sourceAmountDecimal"]) * 100)
        check(evidence == expected_evidence, f"{reference}: deployed inquiry evidence differs from the original source fixture after mapping")
        check(record["reconciliation"]["ledgerNetMinor"] is None and record["reconciliation"]["providerAvailable"] is False,
              "Absent accounting/provider evidence must remain unknown")
        check(not evidence["messages"] and not evidence["accountingEntries"], "This milestone must not fabricate message/posting evidence")
        expected_hash = hashlib.sha256(json.dumps(evidence, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        check(record["evidenceHash"] == receipt["evidenceHash"] == expected_hash, f"{reference}: stored snapshot hash mismatch")
        records[reference] = record
        report["imports"].append({"paymentReference": reference, **receipt})

        versions = analyst.request("GET", f"/api/cases/{case_id}/evidence-versions")
        duplicate = analyst.request("POST", "/api/obpm/inquiries", {"paymentReference": reference})
        check(duplicate["status"] == "UNCHANGED", f"{reference}: duplicate should be unchanged")
        for field in ("caseId", "caseVersion", "evidenceVersion", "evidenceHash"):
            check(duplicate[field] == receipt[field], f"{reference}: duplicate changed {field}")
        check(analyst.request("GET", f"/api/cases/{case_id}/evidence-versions") == versions,
              f"{reference}: duplicate created another evidence version")
        other.request("GET", f"/api/cases/{case_id}", expected=404)
        other.request("GET", f"/api/cases/{case_id}/evidence-versions", expected=404)
        other.request("GET", f"/api/cases/{case_id}/export", expected=404)
    passed("all four references pass actual inquiry HTTP, Java exact-money mapping and durable snapshot storage")
    passed("duplicate fetches preserve all four case/evidence identities and create no evidence versions")
    passed("second tenant cannot read imported cases, evidence versions or exports")

    first_current = [q for q in records["MOCK-NEFT-1001"]["obpm"]["queueRecords"] if q["isCurrentQueueRecord"]]
    check(len(first_current) == 1 and first_current[0]["nativeQueueCode"] == "EC" and first_current[0]["nativeResponseStatus"] == "T",
          "1001 must contain one explicitly current recorded EC/T queue record")
    pending = records["MOCK-NEFT-1002"]["obpm"]["queueRecords"]
    pending_current = [q for q in pending if q["isCurrentQueueRecord"]]
    check(len(pending_current) == 1 and pending_current[0]["nativeResponseStatus"] == "P",
          "1002 must identify the current pending queue record independently of array order")
    check(any(q["nativeResponseStatus"] == "T" and not q["isCurrentQueueRecord"] for q in pending), "1002 must preserve its historical timeout")
    gap_coverage = records["MOCK-NEFT-1003"]["obpm"]["sourceCoverage"]["queueRecords"]
    check(gap_coverage["status"] != "COMPLETE", "1003 must disclose partial or unknown queue coverage")
    ambiguous_current = [q for q in records["MOCK-NEFT-1004"]["obpm"]["queueRecords"] if q["isCurrentQueueRecord"]]
    check(len(ambiguous_current) > 1, "1004 must preserve ambiguous current records")
    passed("stored evidence distinguishes current timeout, historical timeout, coverage gaps and ambiguous current state")

    for reference, expected_outcome in EXPECTED_OUTCOMES.items():
        record = records[reference]
        case_id = record["id"]
        result = analyst.request("POST", f"/api/cases/{case_id}/investigations", {
            "mode": args.mode,
            "question": "Explain the current recorded ECA queue state, cite the evidence and applicable procedure, and identify missing external outcome and accounting evidence.",
        })
        check(result["outcome"] == expected_outcome, f"{reference}: unexpected outcome {result['outcome']}")
        check(result["mode"] == args.mode, "Investigation mode must remain explicit")
        check(result["proposal"]["action"] in {"REQUEST_EVIDENCE", "ESCALATE"}, "OBPM evidence cannot resolve a payment case")
        check(bool(result["missingEvidence"]) and bool(result["citations"]), "Investigation must retain evidence gaps and cited procedures")
        check(all(c["documentId"].startswith("RB-OBPM-") for c in result["citations"]), "OBPM runbook retrieval must remain scoped")
        check({call["name"] for call in result["toolCalls"]} == EXPECTED_TOOLS, "Expected actual four-tool OBPM worker path")
        check(all(call["status"] == "COMPLETED" for call in result["toolCalls"]), "Every evidence tool must complete")
        check(result["caseSnapshot"]["obpm"] == record["obpm"] and result["caseSnapshot"]["evidenceHash"] == record["evidenceHash"],
              "Investigation must retain its immutable inquiry evidence snapshot")
        calls = result["metrics"]["modelCalls"]
        check(calls == 0 if args.mode == "replay" else calls > 0, "Model-call count must match the requested mode")
        stored = analyst.request("GET", "/api/investigations/" + result["id"])
        check(stored == result, "Returned investigation must equal its persisted representation")
        other.request("GET", "/api/investigations/" + result["id"], expected=404)
        report["investigations"].append({"paymentReference": reference, **result})
        passed(f"{reference}: real {args.mode} worker path returns {expected_outcome} with scoped citations and immutable evidence")

    listed = {item["id"]: item for item in analyst.request("GET", "/api/cases")["items"]}
    new_ids = {record["id"] for record in records.values()}
    for reference, record in records.items():
        check(listed[record["id"]]["amountMinor"] == record["amountMinor"], f"{reference}: case queue amount mismatch")
    for case_id, original in original_cases.items():
        if case_id not in new_ids:
            check(listed.get(case_id) == original, f"Unrelated case {case_id} changed during acceptance")
    dashboard = analyst.request("GET", "/api/dashboard")
    check(dashboard["mode"] == "synthetic" and dashboard["currency"] == "INR", "Dashboard must disclose synthetic scope")
    report["dashboard"] = deepcopy(dashboard)
    passed("case-list/dashboard API exposes synthetic imported cases while unrelated existing case records are preserved")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:5178")
    parser.add_argument("--mode", choices=("replay", "ollama"), default="replay")
    parser.add_argument("--report", type=Path, default=ROOT / "docs/validation/obpm-inquiry-http.json")
    args = parser.parse_args()
    started = time.perf_counter()
    report = {
        "status": "RUNNING", "executedAt": datetime.now(timezone.utc).isoformat(),
        "mode": args.mode, "baseUrl": args.base_url,
        "scope": "Original synthetic mock inquiry -> actual Java mapper/PostgreSQL/worker HTTP path; no Oracle connection or browser check",
        "checks": [], "imports": [], "investigations": [],
    }
    error = None
    try:
        run(args, report)
        report["status"] = "PASS"
    except Exception as failure:
        report["status"] = "FAIL"
        report["failure"] = {"type": type(failure).__name__, "message": str(failure)}
        error = failure
    report["checkCount"] = len(report["checks"])
    report["durationSeconds"] = round(time.perf_counter() - started, 3)
    report["modelCalls"] = sum(result["metrics"]["modelCalls"] for result in report["investigations"])
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{report['status']} {report['checkCount']} checks; {report['modelCalls']} chat calls; report {args.report}", flush=True)
    if error:
        print(f"{type(error).__name__}: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
