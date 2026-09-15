"""HTTP acceptance checks against running Java + Python; no third-party test packages."""

import argparse, concurrent.futures, http.cookiejar, json, os, time, urllib.error, urllib.request, uuid
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class Client:
    def __init__(self, base, timeout=430):
        self.base = base
        self.timeout = timeout
        self.csrf = None
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
        )

    def request(self, method, path, body=None, headers=None, expected=200, csrf=True):
        h = {"Content-Type": "application/json", **(headers or {})}
        if csrf and self.csrf:
            h["X-CSRF-Token"] = self.csrf
        request = urllib.request.Request(
            self.base + path,
            data=json.dumps(body).encode() if body is not None else None,
            method=method,
            headers=h,
        )
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                status = response.status
                payload = response.read()
        except urllib.error.HTTPError as error:
            status = error.code
            payload = error.read()
        assert (
            status == expected
        ), f"{method} {path}: expected {expected}, got {status}; {payload.decode()[:500]}"
        return json.loads(payload) if payload else {}

    def login(self, user):
        result = self.request(
            "POST",
            "/api/auth/login",
            {
                "username": user,
                "password": os.getenv("POI_DEMO_PASSWORD", "demo-pass-local"),
            },
        )
        self.csrf = result["csrfToken"]
        return result["user"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8088")
    parser.add_argument("--mode", choices=["replay", "ollama"], default="replay")
    parser.add_argument(
        "--report", type=Path, help="Optional validation JSON output path."
    )
    args = parser.parse_args()
    started = time.perf_counter()
    checks = []

    def passed(name):
        checks.append(name)
        print("PASS " + name, flush=True)

    guest = Client(args.base_url)
    assert guest.request("GET", "/api/health")["status"] == "UP"
    passed("health")
    guest.request("GET", "/api/cases", expected=401)
    passed("anonymous access denied")
    analyst = Client(args.base_url)
    reviewer = Client(args.base_url)
    viewer = Client(args.base_url)
    other = Client(args.base_url)
    for c, user in [
        (analyst, "analyst"),
        (reviewer, "reviewer"),
        (viewer, "viewer"),
        (other, "other"),
    ]:
        c.login(user)
    passed("all four demo roles authenticate")
    listed = analyst.request("GET", "/api/cases")["items"]
    original_ids = {
        c["id"] for c in json.loads((ROOT / "data/fixtures/cases.json").read_text(encoding="utf-8"))
        if c["tenantId"] == "northstar"
    }
    assert {c["id"] for c in listed if c.get("domain") != "OBPM_NEFT"} == original_ids
    assert all(c["tenantId"] == "northstar" for c in listed)
    passed("tenant-scoped list")
    other.request("GET", "/api/cases/CASE-1001", expected=404)
    analyst.request("GET", "/api/cases/CASE-1005", expected=404)
    passed("cross-tenant detail denied")
    question = {
        "question": "Investigate the observed payment state and recommend the next case action with evidence.",
        "mode": args.mode,
    }
    viewer.request(
        "POST", "/api/cases/CASE-1002/investigations", question, expected=403
    )
    passed("viewer mutation denied")
    analyst.request(
        "POST",
        "/api/cases/CASE-1002/investigations",
        question,
        csrf=False,
        expected=403,
    )
    passed("CSRF required")
    inv = analyst.request("POST", "/api/cases/CASE-1002/investigations", question)
    assert inv["mode"] == args.mode and inv["outcome"] == "TIMEOUT_AFTER_SUCCESS", inv
    assert (
        inv["proposal"]["action"] == "RESOLVE_CASE"
        and inv["findings"]
        and inv["citations"]
    )
    if args.mode == "replay":
        assert inv["metrics"]["modelCalls"] == 0
    if args.mode == "ollama":
        assert inv["metrics"]["modelCalls"] > 0
    passed("actual worker investigation with evidence and citations")
    fresh = analyst.request("GET", "/api/cases/CASE-1002")
    assert fresh["status"] == "AWAITING_REVIEW"
    passed("case version/state refreshed")
    body = dict(
        investigationId=inv["id"],
        decision="APPROVE",
        note="Acceptance harness: checked captured payment and supporting evidence.",
        expectedVersion=fresh["version"],
    )
    key = "acceptance-" + str(uuid.uuid4())
    headers = {"Idempotency-Key": key}
    analyst.request(
        "POST", "/api/cases/CASE-1002/decisions", body, headers, expected=403
    )
    passed("analyst cannot approve")
    reviewer.request(
        "POST",
        "/api/cases/CASE-1002/decisions",
        {**body, "expectedVersion": fresh["version"] - 1},
        headers,
        expected=409,
    )
    passed("stale version rejected")
    first = reviewer.request("POST", "/api/cases/CASE-1002/decisions", body, headers)
    second = reviewer.request("POST", "/api/cases/CASE-1002/decisions", body, headers)
    assert (
        first["caseStatus"] == "RESOLVED"
        and first["id"] == second["id"]
        and second["replayed"] is True
    )
    passed("review persisted and idempotent retry returned same decision")
    reviewer.request(
        "POST",
        "/api/cases/CASE-1002/decisions",
        {**body, "note": "Changed payload"},
        headers,
        expected=409,
    )
    passed("idempotency key payload conflict")
    exported = analyst.request("GET", "/api/cases/CASE-1002/export")
    text = json.dumps(exported)
    assert inv["id"] in text and first["id"] in text
    assert not any(
        s in text
        for s in (
            '"expectedOutcome"',
            '"groundTruth"',
            '"scenarioFamily"',
            '"password"',
        )
    )
    passed("authorized export contains durable evidence without labels")
    audit = analyst.request("GET", "/api/cases/CASE-1002/audit")["items"]
    assert any(e["action"] == "INVESTIGATION_CREATED" for e in audit) and any(
        e["action"] == "REVIEW_APPROVE" for e in audit
    )
    passed("audit trail")
    own = reviewer.request("POST", "/api/cases/CASE-1003/investigations", question)
    owncase = reviewer.request("GET", "/api/cases/CASE-1003")
    reviewer.request(
        "POST",
        "/api/cases/CASE-1003/decisions",
        dict(
            investigationId=own["id"],
            decision="APPROVE",
            note="Self review must fail.",
            expectedVersion=owncase["version"],
        ),
        {"Idempotency-Key": "maker-" + str(uuid.uuid4())},
        expected=403,
    )
    passed("maker-checker self review denied")
    knowledge = analyst.request("GET", "/api/knowledge")["items"]
    assert not any(b["tenantId"] not in ("*", "northstar") for b in knowledge)
    passed("knowledge library tenant scope")
    report = dict(
        status="passed",
        timestamp=datetime.now(timezone.utc).isoformat(),
        mode=args.mode,
        baseUrl=args.base_url,
        checks=checks,
        durationSeconds=round(time.perf_counter() - started, 3),
        caseId="CASE-1002",
        investigationId=inv["id"],
        decisionId=first["id"],
        modelMetrics=inv["metrics"],
        limitations=[
            "Local demo authentication; this acceptance run does not establish production security.",
            "Acceptance scenarios are development cases, not held-out benchmark results.",
        ],
    )
    path = args.report or ROOT / "docs/validation" / f"acceptance-{args.mode}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(
        json.dumps(dict(status="passed", checks=len(checks), report=str(path))),
        flush=True,
    )


if __name__ == "__main__":
    main()
