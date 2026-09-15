"""Controlled outages of this project's Compose worker/database, with recovery."""

import argparse
import hashlib
import json
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

from acceptance import Client

ROOT = Path(__file__).resolve().parents[1]
PROJECT = "payment-operations-investigator"


def docker(*args):
    result = subprocess.run(
        ["docker", "compose", "-f", str(ROOT / "compose.yaml"), "-p", PROJECT, *args],
        cwd=ROOT,
        text=True,
        capture_output=True,
        timeout=90,
    )
    if result.returncode:
        raise RuntimeError(
            f"Project Docker operation failed: {' '.join(args)}; {result.stderr[-1500:]}"
        )
    return result.stdout.strip()


def eventually(check, seconds=60):
    """Bound each retry by the remaining budget; socket timeouts are idle bounds."""
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            result = check(min(10.0, deadline - time.monotonic()))
            if time.monotonic() <= deadline:
                return result
            last = "A successful probe completed after the readiness deadline."
        except Exception as error:
            last = error
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(2, remaining))
    raise AssertionError(f"Recovery was not observed within {seconds} seconds: {last}")


def now():
    return datetime.now(timezone.utc).isoformat()


def fingerprint(value):
    canonical = json.dumps(value, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--report", type=Path, default=ROOT / "docs/validation/faults-compose.json"
    )
    args = parser.parse_args()
    started = time.perf_counter()
    report = {
        "timestamp": now(),
        "project": PROJECT,
        "status": "failed",
        "checks": [],
        "timings": [],
        "outages": {},
        "recovery": [],
        "evidence": {},
        "limitations": [
            "Only synthetic local Compose worker and database outages; no bank/provider interruption.",
            "No in-flight model cancellation, partition, overload or multi-node failover test.",
            "API process health is liveness, not database readiness.",
            "Readiness probes cap socket timeouts to the remaining budget; socket idle timeouts are not hard wall-clock deadlines.",
            "Run while other clients are idle; concurrent edits to the selected case deliberately fail exact persistence comparisons.",
        ],
    }
    touched = set()
    pending = set()
    outage_clocks = {}
    tests_completed = False
    client = Client("http://127.0.0.1:8088", timeout=45)
    case_id = "CASE-1004"
    path = f"/api/cases/{case_id}"

    def passed(name):
        report["checks"].append(name)
        print("PASS " + name, flush=True)

    def timed(name, operation):
        timer = time.perf_counter()
        item = {"operation": name, "startedAt": now(), "status": "failed"}
        try:
            result = operation()
            item["status"] = "passed"
            return result
        except Exception as error:
            item["error"] = str(error)
            raise
        finally:
            item["elapsedSeconds"] = round(time.perf_counter() - timer, 3)
            report["timings"].append(item)

    def bounded_get(endpoint, timeout):
        previous = client.timeout
        try:
            client.timeout = min(previous, max(0.001, timeout))
            return client.request("GET", endpoint)
        finally:
            client.timeout = previous

    def worker_ready(timeout):
        value = bounded_get("/api/system", timeout)
        assert value["workerStatus"] == "UP" and value["knowledgeAvailable"]
        return value

    def database_ready(timeout):
        return bounded_get(path, timeout)

    def stop_service(service):
        # Responsibility starts before Docker: a timeout can follow a successful stop.
        touched.add(service)
        pending.add(service)
        outage_clocks[service] = time.perf_counter()
        report["outages"][service] = {"stopAttemptedAt": now()}
        timed(f"stop_{service}", lambda: docker("stop", service))
        report["outages"][service]["stopCompletedAt"] = now()

    def confirmed_recovery(service):
        pending.discard(service)
        outage = report["outages"][service]
        if "recoveredAt" not in outage:
            outage["recoveredAt"] = now()
            outage["elapsedToReadinessSeconds"] = round(
                time.perf_counter() - outage_clocks[service], 3
            )

    try:
        report["containers"] = {}
        for service in ("api", "investigator", "postgres"):
            container = docker("ps", "--status", "running", "-q", service)
            if not container:
                raise RuntimeError(
                    f"Required {PROJECT}/{service} container is not running; no outage was started."
                )
            report["containers"][service] = container
        actor = client.login("analyst")
        before = client.request("GET", path)
        history_before = client.request("GET", path + "/investigations")
        audit_before = {
            item["id"]: item for item in client.request("GET", path + "/audit")["items"]
        }
        stop_service("investigator")
        response = timed(
            "worker_outage_response",
            lambda: client.request(
                "POST",
                path + "/investigations",
                {"question": "Review authorized payment evidence.", "mode": "replay"},
                expected=503,
            ),
        )
        assert response["code"] == "WORKER_UNAVAILABLE" and response["requestId"]
        report["evidence"]["workerError"] = response
        passed("Worker outage returns explicit 503 without fallback")
        after = client.request("GET", path)
        assert before == after
        assert client.request("GET", path + "/investigations") == history_before
        passed(
            "Failed investigation creates no case transition or stored investigation"
        )
        audit = {
            item["id"]: item for item in client.request("GET", path + "/audit")["items"]
        }
        assert all(audit.get(key) == item for key, item in audit_before.items())
        new_ids = set(audit) - set(audit_before)
        assert len(new_ids) == 1, f"Expected one new failure audit, got {len(new_ids)}"
        failure_audit = audit[new_ids.pop()]
        assert failure_audit["action"] == "INVESTIGATION_FAILED"
        assert failure_audit["actor"] == actor["id"]
        assert failure_audit["detail"] == "WORKER_UNAVAILABLE; requested mode replay; no fallback."
        report["evidence"]["failureAudit"] = failure_audit
        passed("This worker failure creates exactly one new audit event without changing old events")
        timed("start_investigator", lambda: docker("start", "investigator"))
        timed("investigator_readiness", lambda: eventually(worker_ready))
        confirmed_recovery("investigator")
        recovered = timed(
            "recovered_replay_investigation",
            lambda: client.request(
                "POST",
                path + "/investigations",
                {
                    "question": "Review authorized payment evidence after worker recovery.",
                    "mode": "replay",
                },
            ),
        )
        assert recovered["mode"] == "replay" and recovered["metrics"]["modelCalls"] == 0
        passed("Worker recovery permits a real replay investigation")
        saved = client.request("GET", path)
        assert saved["status"] == "AWAITING_REVIEW"
        assert saved["version"] == before["version"] + 1 == recovered["caseVersion"]
        report["evidence"].update(
            investigationId=recovered["id"],
            investigationHashBefore=fingerprint(recovered),
            caseHashBefore=fingerprint(saved),
        )
        stop_service("postgres")
        assert client.request("GET", "/api/health")["status"] == "UP"
        passed("API liveness remains UP during database outage, as documented")
        error = timed(
            "database_outage_response",
            lambda: client.request("GET", "/api/cases", expected=500),
        )
        assert error["code"] == "INTERNAL_ERROR" and error["requestId"]
        report["evidence"]["databaseError"] = error
        passed("Database-backed read fails explicitly while database is stopped")
        timed("start_postgres", lambda: docker("start", "postgres"))
        restored = timed("postgres_readiness", lambda: eventually(database_ready))
        confirmed_recovery("postgres")
        assert restored == saved
        stored = client.request("GET", f"/api/investigations/{recovered['id']}")
        assert stored == recovered
        persisted_audit = {
            item["id"]: item for item in client.request("GET", path + "/audit")["items"]
        }
        assert persisted_audit[failure_audit["id"]] == failure_audit
        report["evidence"].update(
            investigationHashAfter=fingerprint(stored),
            caseHashAfter=fingerprint(restored),
            exactFailureAuditPreserved=True,
        )
        passed("Database restart preserves exact case, immutable investigation and failure audit bodies")
        tests_completed = True
    except Exception as error:
        report["error"] = f"{type(error).__name__}: {error}"
    finally:
        # Verify every touched dependency again, even after normal-path recovery.
        for service, check in (("postgres", database_ready), ("investigator", worker_ready)):
            if service not in touched:
                continue
            recovery = {"service": service, "verifiedReady": False, "errors": []}
            try:
                timed(f"finally_start_{service}", lambda: docker("start", service))
            except Exception as error:
                recovery["errors"].append(str(error))
            try:
                timed(f"finally_ready_{service}", lambda: eventually(check))
                confirmed_recovery(service)
                recovery["verifiedReady"] = True
            except Exception as error:
                pending.add(service)
                recovery["errors"].append(str(error))
            report["recovery"].append(recovery)
        report["testChecksCompleted"] = tests_completed
        report["pendingRecovery"] = sorted(pending)
        recovered_cleanly = all(
            item["verifiedReady"] and not item["errors"] for item in report["recovery"]
        )
        report["status"] = "passed" if tests_completed and not pending and recovered_cleanly else "failed"
        report["completedAt"] = now()
        report["elapsedSeconds"] = round(time.perf_counter() - started, 3)
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(
            json.dumps(
                {
                    "status": report["status"],
                    "checks": len(report["checks"]),
                    "report": str(args.report),
                }
            ),
            flush=True,
        )
    if report["status"] != "passed":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
