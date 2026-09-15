"""Actual local API/worker acceptance for original synthetic NEFT snapshots.

Each run uses distinct synthetic references so existing user/history records survive.
Replay is default; --mode ollama explicitly performs local model inference.
"""
import argparse
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import time
import uuid

from acceptance import Client

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:5178")
    parser.add_argument("--mode", choices=("replay", "ollama"), default="replay")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    started = time.perf_counter()
    suffix = "-" + uuid.uuid4().hex[:8]
    checks, results, receipts = [], [], []

    def passed(name):
        checks.append(name)
        print("PASS " + name, flush=True)

    def fresh(payload):
        # Replace only synthetic identity tokens, preserving semantic evidence and chronology.
        serialized = json.dumps(payload)
        for old in ("DEMO-NEFT-0001", "DEMO-NEFT-0002", "DEMO-ECA-QUEUE-0001", "DEMO-ECA-ATTEMPT-0001", "DEMO-ECA-QUEUE-0002", "DEMO-ECA-ATTEMPT-0002"):
            serialized = serialized.replace(old, old + suffix)
        return json.loads(serialized)

    analyst, reviewer, viewer, other = [Client(args.base_url) for _ in range(4)]
    for client, name in ((analyst, "analyst"), (reviewer, "reviewer"), (viewer, "viewer"), (other, "other")):
        client.login(name)
    assert analyst.request("GET", "/api/health")["status"] == "UP"
    catalog = analyst.request("GET", "/api/obpm/samples")["items"]
    samples = {item["id"]: fresh(item["payload"]) for item in catalog}
    assert {"eca-timeout", "eca-timeout-updated", "evidence-gaps"} <= samples.keys()
    passed("authenticated catalog exposes three original validated snapshots")
    first = samples["eca-timeout"]
    viewer.request("POST", "/api/obpm/imports", first, expected=403)
    analyst.request("POST", "/api/obpm/imports", first, csrf=False, expected=403)
    passed("viewer import and missing-CSRF import denied")
    invalid = deepcopy(first)
    invalid["payment"]["amountMinor"] += 1
    analyst.request("POST", "/api/obpm/imports", invalid, expected=422)
    passed("inexact source decimal conversion rejected")
    receipt = analyst.request("POST", "/api/obpm/imports", first)
    receipts.append(receipt)
    assert receipt["status"] == "CREATED" and receipt["evidenceVersion"] == 1
    case_id = receipt["caseId"]
    duplicate = analyst.request("POST", "/api/obpm/imports", first)
    assert duplicate["status"] == "UNCHANGED" and duplicate["caseVersion"] == receipt["caseVersion"]
    passed("atomic import and duplicate identity/version behavior")
    record = analyst.request("GET", f"/api/cases/{case_id}")
    assert record["obpm"] == first and record["domain"] == "OBPM_NEFT"
    assert record["reconciliation"]["ledgerNetMinor"] is None and not record["reconciliation"]["providerAvailable"]
    assert record["obpm"]["payment"]["nativeTransactionStatus"] is None
    passed("bank snapshot preserved with unknown accounting and native payment status")
    other.request("GET", f"/api/cases/{case_id}", expected=404)
    other.request("GET", f"/api/cases/{case_id}/evidence-versions", expected=404)
    assert all(r["caseId"] != case_id for r in other.request("GET", "/api/obpm/imports")["items"])
    passed("cross-tenant case/history/import receipts inaccessible")

    def investigate(identifier, expected_outcome):
        result = analyst.request("POST", f"/api/cases/{identifier}/investigations", {
            "mode": args.mode,
            "question": "Explain the current recorded ECA queue state, cite its evidence, and identify missing external outcome and accounting evidence."
        })
        assert result["outcome"] == expected_outcome, result
        assert result["proposal"]["action"] in {"REQUEST_EVIDENCE", "ESCALATE"}
        assert result["missingEvidence"] and result["citations"]
        assert all(c["documentId"].startswith("RB-OBPM-") for c in result["citations"])
        assert result["metrics"]["modelCalls"] == 0 if args.mode == "replay" else result["metrics"]["modelCalls"] > 0
        assert result["caseSnapshot"]["evidenceHash"]
        results.append(result)
        return result

    initial = investigate(case_id, "OBPM_ECA_TIMEOUT")
    passed("real graph/tool/retrieval path diagnoses recorded timeout with OBPM-only citations")
    update = analyst.request("POST", "/api/obpm/imports", samples["eca-timeout-updated"])
    receipts.append(update)
    assert update["status"] == "UPDATED" and update["evidenceVersion"] == 2
    current = analyst.request("GET", f"/api/cases/{case_id}")
    assert current["status"] == "OPEN" and current["version"] > initial["caseVersion"]
    historical = analyst.request("GET", "/api/investigations/" + initial["id"])
    assert historical["caseSnapshot"]["obpm"] == first
    passed("refresh resets case and retains immutable earlier investigation evidence")
    reviewer.request("POST", f"/api/cases/{case_id}/decisions", {
        "investigationId": initial["id"], "decision": "APPROVE", "note": "Stale-review rejection acceptance check",
        "expectedVersion": current["version"]
    }, {"Idempotency-Key": "obpm-stale-" + uuid.uuid4().hex}, expected=409)
    analyst.request("POST", "/api/obpm/imports", first, expected=409)
    passed("server rejects stale proposal even with current version and rejects older source")
    fresh_result = investigate(case_id, "INSUFFICIENT_EVIDENCE")
    passed("current pending request cannot inherit historical timeout outcome")
    body = {"investigationId": fresh_result["id"], "decision": "APPROVE", "note": "Reviewed synthetic ECA evidence; request missing evidence only.", "expectedVersion": fresh_result["caseVersion"]}
    key = {"Idempotency-Key": "obpm-review-" + uuid.uuid4().hex}
    analyst.request("POST", f"/api/cases/{case_id}/decisions", body, key, expected=403)
    decision = reviewer.request("POST", f"/api/cases/{case_id}/decisions", body, key)
    assert decision["caseStatus"] in {"NEEDS_EVIDENCE", "ESCALATED"}
    replay = reviewer.request("POST", f"/api/cases/{case_id}/decisions", body, key)
    assert replay["replayed"] and replay["id"] == decision["id"]
    passed("independent reviewer decision is durable and idempotent; no payment completion")
    bundle = analyst.request("GET", f"/api/cases/{case_id}/export")
    assert len(bundle["evidenceVersions"]) == 2
    for version in bundle["evidenceVersions"]:
        calculated = hashlib.sha256(json.dumps(version["evidence"], ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        assert calculated == version["evidenceHash"]
    assert bundle["decisions"] and bundle["audit"] and len(bundle["investigations"]) == 2
    passed("export contains verifiable snapshot hashes, decisions and complete case audit")
    gaps = analyst.request("POST", "/api/obpm/imports", samples["evidence-gaps"])
    receipts.append(gaps)
    investigate(gaps["caseId"], "INSUFFICIENT_EVIDENCE")
    passed("partial/unknown evidence produces specific evidence requests")
    generic = analyst.request("POST", "/api/cases/CASE-1002/investigations", {"mode": "replay", "question": "Inspect the provider and captured payment evidence."})
    assert generic["outcome"] == "TIMEOUT_AFTER_SUCCESS" and generic["metrics"]["modelCalls"] == 0
    assert all(not c["documentId"].startswith("RB-OBPM-") for c in generic["citations"])
    passed("legacy generic investigation still works and excludes OBPM-only policies")
    report = {"status": "PASS", "executedAt": datetime.now(timezone.utc).isoformat(), "mode": args.mode,
              "scope": "Original synthetic outbound NEFT ECA; actual local Java/PostgreSQL/worker HTTP path, no Oracle connection",
              "checks": checks, "checkCount": len(checks), "durationSeconds": round(time.perf_counter() - started, 3),
              "modelCalls": sum(r["metrics"]["modelCalls"] for r in results), "imports": receipts, "investigations": results}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"PASS {len(checks)} checks; {report['modelCalls']} chat calls; report {args.report}", flush=True)


if __name__ == "__main__":
    main()
