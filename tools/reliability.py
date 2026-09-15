"""Bounded local HTTP read checks; stdlib only, with no investigation or decision calls."""

from __future__ import annotations

import argparse
import concurrent.futures
from datetime import datetime, timezone
import http.cookiejar
import ipaddress
import json
import math
import os
from pathlib import Path
import platform
import statistics
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
MAX_RESPONSE_BYTES = 2_000_000
MAX_CLIENTS = 4
MAX_READS = 40


class CheckFailed(Exception):
    pass


def require(condition, message):
    if not condition:
        raise CheckFailed(message)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


class Recorder:
    def __init__(self):
        self.lock = threading.Lock()
        self.samples = []
        self.request_ids = set()

    def add(self, sample):
        with self.lock:
            request_id = sample.get("requestId")
            if request_id:
                require(request_id not in self.request_ids, "Server reused a request ID")
                self.request_ids.add(request_id)
            self.samples.append(sample)


class Client:
    def __init__(self, base, name, recorder, timeout):
        self.base, self.name, self.recorder, self.timeout = base, name, recorder, timeout
        self.csrf = None
        self.authenticated = False
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            NoRedirect(),
            urllib.request.HTTPCookieProcessor(self.cookies),
        )

    def request(self, method, path, body=None, *, headers=None, expected=200,
                code=None, csrf=True, phase="controls", raw=None):
        require(method == "GET" or (method == "POST" and path in
                {"/api/auth/login", "/api/auth/logout"}),
                "Harness permits only GET plus login/logout")
        require(path.startswith("/api/") and not path.startswith("//"), "Invalid API path")
        outgoing = {"Accept": "application/json", **(headers or {})}
        data = raw if raw is not None else json.dumps(body).encode() if body is not None else None
        if data is not None:
            outgoing["Content-Type"] = "application/json"
        if csrf and self.csrf and method == "POST":
            outgoing.setdefault("X-CSRF-Token", self.csrf)
        request = urllib.request.Request(self.base + path, data=data, headers=outgoing, method=method)
        started = time.perf_counter()
        sample = {"client": self.name, "phase": phase, "method": method, "path": path}
        try:
            try:
                response = self.opener.open(request, timeout=self.timeout)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                status = response.status
                response_headers = response.headers
                payload = response.read(MAX_RESPONSE_BYTES + 1)
            sample.update(status=status, durationMs=round((time.perf_counter() - started) * 1000, 3),
                          requestId=response_headers.get("X-Request-Id"))
            self.recorder.add(sample)
            require(len(payload) <= MAX_RESPONSE_BYTES, f"{path}: response exceeds 2 MB bound")
            require(status == expected, f"{method} {path}: expected HTTP {expected}, received {status}")
            require(response_headers.get_content_type() == "application/json", f"{path}: response is not JSON")
            try:
                uuid.UUID(sample["requestId"] or "")
            except (ValueError, TypeError, AttributeError) as error:
                raise CheckFailed(f"{path}: missing or invalid X-Request-Id") from error
            require("no-store" in response_headers.get("Cache-Control", ""), f"{path}: no-store missing")
            try:
                result = json.loads(payload)
            except (ValueError, UnicodeDecodeError) as error:
                raise CheckFailed(f"{path}: unreadable JSON") from error
            require(isinstance(result, dict), f"{path}: expected a JSON object")
            if status >= 400:
                require(result.get("requestId") == sample["requestId"], f"{path}: error request ID mismatch")
                require(isinstance(result.get("message"), str) and bool(result["message"]), f"{path}: error message missing")
                require(result.get("code") == code, f"{path}: expected error code {code}, received {result.get('code')}")
            return result, response_headers
        except (OSError, TimeoutError, urllib.error.URLError) as error:
            if "durationMs" not in sample:
                sample.update(durationMs=round((time.perf_counter() - started) * 1000, 3),
                              transportError=type(error).__name__)
                self.recorder.add(sample)
            raise CheckFailed(f"{method} {path}: transport failure ({type(error).__name__})") from error

    def login(self, username):
        result, headers = self.request("POST", "/api/auth/login", {
            "username": username,
            "password": os.getenv("POI_DEMO_PASSWORD", "demo-pass-local"),
        })
        self.csrf = result.get("csrfToken")
        self.authenticated = True
        require(isinstance(self.csrf, str) and len(self.csrf) >= 16, "Login did not return a usable CSRF token")
        cookie_headers = " ".join(headers.get_all("Set-Cookie", [])).lower()
        require("poi_session=" in cookie_headers and "httponly" in cookie_headers
                and "samesite=strict" in cookie_headers, "Session cookie attributes are missing")
        return result["user"]

    def logout(self):
        result, _ = self.request("POST", "/api/auth/logout", phase="cleanup")
        require(result.get("status") == "SIGNED_OUT", "Logout was not acknowledged")
        self.authenticated = False
        self.csrf = None


def distribution(samples):
    values = sorted(sample["durationMs"] for sample in samples)
    if not values:
        return None
    return {"samples": len(values), "minMs": values[0],
            "medianMs": round(statistics.median(values), 3),
            "p95Ms": values[math.ceil(len(values) * 0.95) - 1], "maxMs": values[-1],
            "percentileMethod": "nearest rank; elapsed HTTP response read, no warm-up exclusion"}


def validate_read(path, result, tenant, case_id):
    if path == "/api/cases":
        items = result.get("items")
        require(isinstance(items, list) and bool(items), "Case list is empty or invalid")
        require(result.get("total") == len(items), "Case list total does not match returned records")
        require(all(item.get("tenantId") == tenant for item in items), "Cross-tenant case list data")
        require(all(not any(key in item for key in ("provider", "events", "ledgerEntries", "webhooks"))
                    for item in items), "Case summary unexpectedly exposes full operational detail")
    elif path == "/api/dashboard":
        require(result.get("mode") == "synthetic" and result.get("currency") == "INR", "Dashboard provenance/currency invalid")
        for key in ("openCases", "highPriorityCases", "awaitingReview", "resolvedCases", "totalAmountMinor"):
            require(type(result.get(key)) is int and abs(result[key]) <= 9_007_199_254_740_991,
                    f"Dashboard {key} is not a JSON-safe integer")
    elif path.endswith("/investigations"):
        require(isinstance(result.get("items"), list), "Investigation history missing")
        require(all(item.get("caseId") == case_id for item in result["items"]), "Another case's investigation was returned")
    elif path.endswith("/audit"):
        require(isinstance(result.get("items"), list), "Audit history missing")
    else:
        require(result.get("id") == case_id and result.get("tenantId") == tenant, "Wrong case detail or tenant")
        require(type(result.get("version")) is int and result["version"] >= 1, "Case version invalid")
        for key in ("events", "ledgerEntries", "webhooks"):
            require(isinstance(result.get(key), list), f"Case {key} missing")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8088")
    parser.add_argument("--clients", type=int, default=4)
    parser.add_argument("--requests", type=int, default=40, help="Authenticated read requests, maximum 40; controls are additional")
    parser.add_argument("--timeout", type=float, default=5, help="Per HTTP operation timeout, 1 to 10 seconds")
    parser.add_argument("--report", type=Path, default=ROOT / "docs/validation/reliability-native.json")
    args = parser.parse_args()
    parsed = urllib.parse.urlsplit(args.base_url)
    try:
        loopback = parsed.hostname == "localhost" or ipaddress.ip_address(parsed.hostname or "").is_loopback
    except ValueError:
        loopback = False
    if not loopback or parsed.scheme != "http" or parsed.username or parsed.password or parsed.path not in ("", "/") or parsed.query or parsed.fragment:
        parser.error("Use an HTTP loopback base URL without credentials, path, query or fragment")
    if not (1 <= args.clients <= MAX_CLIENTS and args.clients <= args.requests <= MAX_READS and 1 <= args.timeout <= 10):
        parser.error("Bounds: 1-4 clients, clients <= requests <= 40, timeout 1-10 seconds")
    report_path = args.report.resolve()
    if report_path.parent != (ROOT / "docs/validation").resolve() or not report_path.name.startswith("reliability-") or report_path.suffix != ".json":
        parser.error("Report must be docs/validation/reliability-*.json inside this project")

    started = time.perf_counter()
    recorder, clients, checks, cleanup_errors = Recorder(), [], [], []
    report = {"status": "failed", "timestamp": datetime.now(timezone.utc).isoformat(),
              "baseUrl": args.base_url.rstrip("/"), "python": platform.python_version(),
              "platform": platform.platform(), "configuration": {"clients": args.clients,
              "readRequests": args.requests, "httpTimeoutSeconds": args.timeout,
              "maxResponseBytes": MAX_RESPONSE_BYTES, "automaticRetries": 0}}

    def passed(name):
        checks.append(name)
        print("PASS " + name, flush=True)

    def client(name):
        value = Client(report["baseUrl"], name, recorder, args.timeout)
        clients.append(value)
        return value

    try:
        guest = client("anonymous")
        health, _ = guest.request("GET", "/api/health", headers={"X-Request-Id": "untrusted-client-id"})
        require(health.get("status") == "UP" and health.get("mode") == "synthetic", "Unexpected API health identity")
        passed("API process health, server-generated request ID and no-store")
        guest.request("GET", "/api/cases", expected=401, code="UNAUTHENTICATED", headers={"X-Tenant-Id": "northstar", "X-Role": "REVIEWER"})
        passed("Anonymous tenant/role headers cannot authenticate a protected read")
        invalid_logins = [
            ("malformed JSON", None, b'{"username":'),
            ("blank username", {"username": "", "password": "not-a-real-password"}, None),
            ("oversized username", {"username": "x" * 101, "password": "not-a-real-password"}, None),
            ("unknown role field", {"username": "analyst", "password": "not-a-real-password", "role": "REVIEWER"}, None),
            ("trailing JSON value", None, b'{"username":"analyst","password":"not-a-real-password"} {}'),
        ]
        for label, body, raw in invalid_logins:
            guest.request("POST", "/api/auth/login", body, raw=raw, expected=400, code="INVALID_REQUEST")
            passed("Public login rejects " + label)
        guest.request("POST", "/api/auth/login", {"username": "analyst", "password": "intentionally-invalid-reliability-password"}, expected=401, code="INVALID_CREDENTIALS")
        passed("Invalid credentials are rejected")
        guest.request("POST", "/api/auth/login", {"username": "analyst", "password": "not-a-real-password"}, headers={"Sec-Fetch-Site": "cross-site"}, expected=403, code="FORBIDDEN")
        passed("Cross-site login hint is rejected before authentication")
        guest.request("GET", "/api/auth/me", expected=401, code="UNAUTHENTICATED")
        passed("Failed login controls leave the guest unauthenticated")

        identities = [("analyst", "northstar", "ANALYST"), ("viewer", "northstar", "VIEWER"),
                      ("reviewer", "northstar", "REVIEWER"), ("other", "silverline", "ANALYST")]
        workload = []
        for username, tenant, role in identities:
            current = client(username)
            actor = current.login(username)
            require(actor.get("id") == username and actor.get("tenantId") == tenant and actor.get("role") == role, "Authenticated identity differs from configured demo role")
            listed, _ = current.request("GET", "/api/cases")
            validate_read("/api/cases", listed, tenant, "")
            workload.append((current, tenant, listed["items"][0]["id"]))
        passed("All demo identities have scoped case lists and HttpOnly SameSite=Strict sessions")
        analyst, tenant, northstar_id = workload[0]
        other, _, silverline_id = workload[3]
        other.request("GET", "/api/cases/" + northstar_id, expected=404, code="NOT_FOUND")
        analyst.request("GET", "/api/cases/" + silverline_id, expected=404, code="NOT_FOUND")
        passed("Cross-tenant case reads return 404 in both directions")
        me, _ = workload[1][0].request("GET", "/api/auth/me", headers={"X-Role": "REVIEWER", "X-Tenant-Id": "silverline"})
        require(me["user"]["role"] == "VIEWER" and me["user"]["tenantId"] == "northstar", "Client headers changed the authenticated identity")
        passed("Authenticated role/tenant remain server-owned")
        for token in (None, "invalid-csrf-token"):
            analyst.request("POST", "/api/auth/logout", csrf=False,
                            headers={"X-CSRF-Token": token} if token else {}, expected=403, code="FORBIDDEN")
            me, _ = analyst.request("GET", "/api/auth/me")
            require(me["user"]["id"] == "analyst", "Rejected logout unexpectedly ended the session")
        passed("Missing/wrong CSRF token cannot log out an authenticated session")

        stop = threading.Event()
        load_started = time.perf_counter()

        def read_batch(index):
            current, tenant, case_id = workload[index]
            paths = ["/api/cases", "/api/dashboard", f"/api/cases/{case_id}",
                     f"/api/cases/{case_id}/investigations", f"/api/cases/{case_id}/audit"]
            for turn, _ in enumerate(range(index, args.requests, args.clients)):
                if stop.is_set():
                    return
                try:
                    path = paths[(turn + index) % len(paths)]
                    result, _ = current.request("GET", path, phase="load")
                    validate_read(path, result, tenant, case_id)
                except Exception:
                    stop.set()
                    raise

        with concurrent.futures.ThreadPoolExecutor(max_workers=args.clients) as executor:
            futures = [executor.submit(read_batch, index) for index in range(args.clients)]
            for future in concurrent.futures.as_completed(futures):
                future.result()
        report["loadDurationSeconds"] = round(time.perf_counter() - load_started, 3)
        load_samples = [sample for sample in recorder.samples if sample["phase"] == "load"]
        require(len(load_samples) == args.requests and all(sample.get("status") == 200 for sample in load_samples), "Read workload incomplete")
        passed(f"{args.requests} authenticated reads passed at up to {args.clients} concurrent clients")
        for current, _, _ in workload:
            current.logout()
            current.request("GET", "/api/auth/me", expected=401, code="UNAUTHENTICATED", phase="cleanup")
        passed("Valid logout invalidates all harness sessions")
        report["status"] = "passed"
    except Exception as error:
        report["failure"] = {"type": type(error).__name__, "message": str(error)}
        print(f"FAIL {type(error).__name__}: {error}", flush=True)
    finally:
        for current in clients:
            if current.authenticated:
                try:
                    current.logout()
                except Exception as error:
                    cleanup_errors.append({"client": current.name, "error": str(error)})
        if cleanup_errors:
            report["status"] = "failed"
        load_samples = [sample for sample in recorder.samples if sample["phase"] == "load"]
        report.update(checks=checks, checkCount=len(checks), durationSeconds=round(time.perf_counter() - started, 3),
                      httpRequestsObserved=len(recorder.samples), readRequestsCompleted=len(load_samples),
                      uniqueRequestIds=len(recorder.request_ids), readLatency=distribution(load_samples),
                      perReadPath={path: distribution([sample for sample in load_samples if sample["path"] == path])
                                   for path in sorted({sample["path"] for sample in load_samples})},
                      cleanupErrors=cleanup_errors, requests=recorder.samples,
                      limitations=["Small local read sample, not a capacity, scalability, percentile-SLO or production-security benchmark.",
                                   "Only login/logout mutations. No investigation, decision, worker, model, outage or restart requests.",
                                   "Control/cleanup requests are additional to the bounded 40-read workload and excluded from read latency.",
                                   "Session inactivity expiry, TLS, distributed replicas, durable recovery and sustained overload are not tested.",
                                   "HTTP operation timeouts are socket limits, not automatic cancellation of server work."])
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"status": report["status"], "checks": len(checks), "report": str(report_path), "readLatency": report["readLatency"]}), flush=True)
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
