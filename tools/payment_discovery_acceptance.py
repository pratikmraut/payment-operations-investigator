"""Exercise local payment discovery and case creation using original demo data.

No bank endpoint, database query or inference call. Creates/resumes demo cases.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

from acceptance import Client

ROOT = Path(__file__).resolve().parents[1]


def upload(client, file: Path, branch: str, bank: str, expected=200):
    boundary = "poi-discovery-" + uuid.uuid4().hex
    fields = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"orgBank\"\r\n\r\n{bank}\r\n"
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"orgBranch\"\r\n\r\n{branch}\r\n"
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"payments.xlsx\"\r\n"
        "Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n"
    ).encode()
    body = fields + file.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(client.base + "/api/payment-discovery/uploads", data=body,
        headers={"Content-Type": "multipart/form-data; boundary=" + boundary,
                 "X-CSRF-Token": client.csrf or ""}, method="POST")
    try:
        response = client.opener.open(request, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        status = response.code
        result = json.loads(response.read())
    assert status == expected, f"Upload expected {expected}, got {status}; code={result.get('code')}"
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:5178")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if not args.base_url.startswith(("http://127.0.0.1:", "http://localhost:")):
        raise SystemExit("This harness is restricted to the local demo.")
    checks = []
    started = time.perf_counter()
    def passed(label):
        checks.append(label)
        print("PASS " + label, flush=True)

    clients = {role: Client(args.base_url, timeout=30) for role in ("analyst", "reviewer", "viewer", "other")}
    for role, client in clients.items(): client.login(role)
    analyst, reviewer, viewer, other = (clients[r] for r in ("analyst", "reviewer", "viewer", "other"))
    config = analyst.request("GET", "/api/payment-discovery/config")
    assert config["mode"] == "MOCK", "Acceptance must run against explicit MOCK mode."
    branch = config["scopes"][0]["orgBranch"]
    bank = config["scopes"][0]["orgBank"]
    query = {"orgBank": bank, "orgBranch": branch, "inquiryDate": config["today"], "recordCount": 20}
    guest = Client(args.base_url)
    guest.request("GET", "/api/payment-cases", expected=401)
    viewer.request("POST", "/api/payment-discovery/search", query, expected=403)
    analyst.request("POST", "/api/payment-discovery/search", query, expected=403, csrf=False)
    other.request("POST", "/api/payment-discovery/search", query, expected=403)
    missing_bank = {key:value for key,value in query.items() if key != "orgBank"}
    analyst.request("POST", "/api/payment-discovery/search", missing_bank, expected=422)
    permitted_banks = {scope["orgBank"] for scope in config["scopes"]}
    denied_bank = next(str(code) for code in range(999990,999999) if str(code) not in permitted_banks)
    analyst.request("POST", "/api/payment-discovery/search", {**query,"orgBank":denied_bank}, expected=403)
    passed("session, role, CSRF and explicit bank/branch restrictions")

    batch = analyst.request("POST", "/api/payment-discovery/search", query)
    assert batch["sourceKind"] == "MOCK" and batch["items"]
    item = batch["items"][-1]
    assert isinstance(item["reference"], str) and isinstance(item["amount"], str)
    assert item["dataClassification"] == "SYNTHETIC"
    passed("mock list returns exact identifiers, amounts and explicit provenance")

    lookup = {"orgBank":bank,"orgBranch":branch,"referenceType":"FCR","reference":item["reference"]}
    limited = analyst.request("POST", "/api/payment-discovery/search", {**query,"recordCount":1})
    assert len(limited["items"]) == 1 and all(c["reference"] != item["reference"] for c in limited["items"])
    direct = analyst.request("POST", "/api/payment-discovery/lookup", lookup)
    assert direct["matchStatus"] == "EXACT_MATCH" and len(direct["items"]) == 1
    assert direct["items"][0]["reference"] == item["reference"]
    assert direct["items"][0]["hostSubsequences"] == item["hostSubsequences"]
    shared = batch["items"][0]["utr"]
    utr = analyst.request("POST", "/api/payment-discovery/lookup", {
        **lookup,"referenceType":"UTR","reference":shared})
    assert utr["matchStatus"] == "AMBIGUOUS" and len(utr["items"]) == 2
    assert all(c["utr"] == shared for c in utr["items"])
    empty = analyst.request("POST", "/api/payment-discovery/lookup", {
        **lookup,"reference":"DEMO-NO-SUCH-REFERENCE"})
    assert empty["items"] == [] and empty["matchStatus"] == "NOT_FOUND" and empty["coverage"]
    analyst.request("POST", "/api/payment-discovery/lookup", {**lookup,"recordCount":1}, expected=422)
    analyst.request("POST", "/api/payment-discovery/lookup", {**lookup,"inquiryDate":config["today"]}, expected=422)
    analyst.request("POST", "/api/payment-discovery/search", {**query,"reference":item["reference"]}, expected=422)
    viewer.request("POST", "/api/payment-discovery/lookup", lookup, expected=403)
    analyst.request("POST", "/api/payment-discovery/lookup", lookup, expected=403, csrf=False)
    other.request("POST", "/api/payment-discovery/lookup", lookup, expected=403)
    analyst.request("POST", "/api/payment-discovery/lookup", {**lookup,"orgBank":denied_bank}, expected=403)
    passed("exact lookup bypasses list limits, preserves groups and exposes UTR ambiguity")

    historic_list = analyst.request("POST", "/api/payment-discovery/search", {**query,"inquiryDate":"2026-01-07"})
    historic = historic_list["items"][-1]
    historic_match = analyst.request("POST", "/api/payment-discovery/lookup", {**lookup,"reference":historic["reference"]})
    assert historic_match["matchStatus"] == "EXACT_MATCH" and historic_match["items"][0]["initiatedAt"].startswith("2026-01-07")
    passed("historical exact reference lookup requires no date parameter")

    body = {"candidateId": item["candidateId"], "reason": "Original demo: validate inquiry selection and case persistence."}
    headers = {"Idempotency-Key": "discovery-check-" + uuid.uuid4().hex}
    opened = analyst.request("POST", "/api/payment-cases", body, headers=headers)
    retried = analyst.request("POST", "/api/payment-cases", body, headers=headers)
    assert opened["caseId"] == retried["caseId"]
    same = reviewer.request("POST", "/api/payment-cases", body, headers={"Idempotency-Key": uuid.uuid4().hex})
    assert opened["caseId"] == same["caseId"]
    case = analyst.request("GET", "/api/payment-cases/" + opened["caseId"])
    assert case["evidenceStatus"] == "DISCOVERY_ONLY" and case["status"] == "OPEN"
    assert case["amount"] == item["amount"] and case["reference"] == item["reference"]
    other.request("GET", "/api/payment-cases/" + opened["caseId"], expected=404)
    viewer.request("POST", "/api/payment-cases", body, headers={"Idempotency-Key":uuid.uuid4().hex}, expected=403)
    passed("open/resume, idempotent retry and private case authorization")

    template = ROOT / "apps/web/public/payment-discovery-template.xlsx"
    with urllib.request.urlopen(args.base_url + "/payment-discovery-template.xlsx", timeout=10) as download:
        assert download.status == 200 and download.read() == template.read_bytes(), "Served template must match the verified workbook."
    imported = upload(analyst, template, branch, bank)
    assert imported["sourceKind"] == "EXCEL" and imported["items"]
    assert all(c["dataClassification"] == "PRIVATE_UAT" for c in imported["items"])
    assert any(len(c["hostSubsequences"]) > 1 for c in imported["items"])
    assert any(len(c["reference"]) > 20 for c in imported["items"])
    other_scope = other.request("GET", "/api/payment-discovery/config")["scopes"][0]
    upload(other, template, other_scope["orgBranch"], other_scope["orgBank"], expected=403)
    passed("Excel template, long text IDs, grouped host rows and scope rejection")

    selected = imported["items"][0]
    local = analyst.request("POST", "/api/payment-discovery/lookup", {
        **lookup,"reference":selected["reference"]})
    assert local["items"] == [] and local["matchStatus"] == "NOT_FOUND"
    chosen = {"candidateId":selected["candidateId"],"reason":"Original workbook example: validate selected payment case."}
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(client.request, "POST", "/api/payment-cases", chosen,
                   {"Idempotency-Key":uuid.uuid4().hex}) for client in (analyst,reviewer)]
        opened_parallel = [f.result() for f in futures]
    assert len({result["caseId"] for result in opened_parallel}) == 1
    passed("API lookup does not substitute loaded Excel; concurrent selected-row creation reuses one case")

    listed = analyst.request("GET", "/api/payment-cases")
    assert listed["pageSize"] == 10 and len(listed["items"]) <= 10
    for case_id in (opened["caseId"], opened_parallel[0]["caseId"]):
        found = analyst.request("GET", "/api/payment-cases?search=" + case_id)
        assert found["total"] == 1 and found["items"][0]["id"] == case_id
    original_cases = analyst.request("GET", "/api/cases")["items"]
    assert opened["caseId"] not in {c["id"] for c in original_cases}
    passed("new payment cases persist separately from original demo cases")
    report = {"status":"PASS","timestamp":datetime.now(timezone.utc).isoformat(),
        "durationSeconds":round(time.perf_counter()-started,3),"checks":checks,
        "checkCount":len(checks),"bankCalls":0,"modelCalls":0,
        "data":"original mock and original downloadable workbook only",
        "limitations":["No bank endpoint or UAT database was contacted.",
                        "Discovery records are not full inquiry evidence.",
                        "Local demo cases were created or resumed; no payment action was executed."]}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"status":"PASS","checks":len(checks),"seconds":report["durationSeconds"]}))


if __name__ == "__main__": main()
