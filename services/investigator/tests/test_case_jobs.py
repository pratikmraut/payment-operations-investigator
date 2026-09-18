from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from threading import Event
from types import SimpleNamespace

from fastapi import HTTPException
from fastapi.testclient import TestClient
import pytest

from investigator.case_jobs import CaseJobStore, CaseJobSubmission, CaseJobIdentity, CaseJobCancelled, CaseJobScope
from investigator.errors import UatModelBusy, UatModelTimeout
from investigator.main import create_app
from test_case_answer import case_request
from test_case_rag import adapter, plain


class Engine:
    def __init__(self, run=None):
        self.calls = 0
        self.run = run

    def preflight(self, request):
        return {"ready": True}

    def run_cancellable(self, request, cancelled):
        self.calls += 1
        if self.run:
            self.run(cancelled)
        return SimpleNamespace(model_dump_json=lambda **kwargs: '{"answer":"Synthetic receipt"}')


@pytest.fixture
def submission(case_request):
    return CaseJobSubmission(tenantId="tenant-a", caseId="case-a", jobId="job-a",
                             inputHash="a" * 64, input=case_request)


def identity(value, **changes):
    return CaseJobIdentity(**(value.model_dump(exclude={"input"}) | changes))


def test_receipt_survives_reopen_and_submit_retries_do_not_generate_twice(tmp_path, submission):
    engine = Engine()
    path = tmp_path / "jobs.sqlite"
    store = CaseJobStore(path, engine, start=False)
    assert store.submit(submission)["status"] == "QUEUED"
    assert store.submit(submission)["status"] == "QUEUED"
    assert store.run_once()
    restored = CaseJobStore(path, engine, start=False)
    assert restored.status(identity(submission))["answer"]["answer"] == "Synthetic receipt"
    assert restored.submit(submission)["status"] == "COMPLETED"
    assert not restored.run_once()
    assert engine.calls == 1


def test_identity_and_payload_conflicts_fail_without_overwriting_original(tmp_path, submission):
    store = CaseJobStore(tmp_path / "jobs.sqlite", Engine(), start=False)
    store.submit(submission)
    for changed in (submission.model_copy(update={"inputHash": "b" * 64}),
                    submission.model_copy(update={"input": submission.input.model_copy(update={"question": "Different question"})})):
        with pytest.raises(HTTPException) as error:
            store.submit(changed)
        assert error.value.status_code == 409
    for changed in (identity(submission, tenantId="tenant-b"), identity(submission, caseId="case-b")):
        with pytest.raises(HTTPException) as error:
            store.status(changed)
        assert error.value.status_code == 404
    assert store.status(identity(submission))["status"] == "QUEUED"


def test_queued_cancel_is_terminal_and_starts_no_model_call(tmp_path, submission):
    engine = Engine()
    store = CaseJobStore(tmp_path / "jobs.sqlite", engine, start=False)
    store.submit(submission)
    assert store.cancel(identity(submission))["status"] == "CANCELLED"
    assert store.cancel(identity(submission))["status"] == "CANCELLED"
    assert not store.run_once()
    assert engine.calls == 0


def test_cancel_before_a_delayed_submission_prevents_generation(tmp_path, submission):
    engine = Engine()
    path = tmp_path / "jobs.sqlite"
    store = CaseJobStore(path, engine, start=False)
    assert store.cancel(identity(submission))["status"] == "CANCELLED"
    reopened = CaseJobStore(path, engine, start=False)
    assert reopened.submit(submission)["status"] == "CANCELLED"
    assert not reopened.run_once() and engine.calls == 0
    with pytest.raises(HTTPException) as conflict:
        reopened.submit(submission.model_copy(update={"inputHash": "b" * 64}))
    assert conflict.value.status_code == 409


def test_two_workers_claim_once_and_running_cancel_discards_late_result(tmp_path, submission):
    entered, finish = Event(), Event()
    def delayed(cancelled):
        entered.set()
        assert finish.wait(5)
    engine = Engine(delayed)
    path = tmp_path / "jobs.sqlite"
    first = CaseJobStore(path, engine, start=False)
    second = CaseJobStore(path, engine, start=False)
    first.submit(submission)
    with ThreadPoolExecutor() as executor:
        running = executor.submit(first.run_once)
        assert entered.wait(5)
        assert not second.run_once()
        cancelled = second.cancel(identity(submission))
        assert cancelled["status"] == "RUNNING" and cancelled["cancellationRequested"]
        assert "answer" not in cancelled
        finish.set()
        assert running.result(5)
    assert second.status(identity(submission))["status"] == "CANCELLED"
    assert "answer" not in second.status(identity(submission))
    assert engine.calls == 1


def test_expired_running_lease_fails_without_automatic_regeneration(tmp_path, submission):
    now = [1000.0]
    engine = Engine()
    path = tmp_path / "jobs.sqlite"
    old = CaseJobStore(path, engine, clock=lambda: now[0], start=False)
    old.submit(submission)
    assert old._claim()
    restored = CaseJobStore(path, engine, clock=lambda: now[0], start=False)
    assert restored.status(identity(submission))["status"] == "RUNNING"
    assert not restored.run_once()
    now[0] += old.LEASE_SECONDS + 1
    receipt = restored.status(identity(submission))
    assert receipt["status"] == "FAILED"
    assert receipt["error"]["code"] == "CASE_WORKER_INTERRUPTED"
    assert restored.submit(submission)["status"] == "FAILED"
    assert not restored.run_once() and engine.calls == 0


def test_expired_worker_cannot_overwrite_failure_with_late_answer(tmp_path, submission):
    now = [1000.0]
    entered, finish = Event(), Event()
    def delayed(cancelled):
        entered.set()
        assert finish.wait(5)
    engine = Engine(delayed)
    path = tmp_path / "jobs.sqlite"
    old = CaseJobStore(path, engine, clock=lambda: now[0], start=False)
    restored = CaseJobStore(path, engine, clock=lambda: now[0], start=False)
    old.submit(submission)
    with ThreadPoolExecutor() as executor:
        running = executor.submit(old.run_once)
        assert entered.wait(5)
        now[0] += old.LEASE_SECONDS + 1
        assert restored.status(identity(submission))["status"] == "FAILED"
        finish.set()
        assert running.result(5)
    assert restored.status(identity(submission))["status"] == "FAILED"
    assert "answer" not in restored.status(identity(submission))


def test_purged_expired_receipt_handles_a_late_worker_without_resurrection(tmp_path, submission):
    now = [1000.0]
    entered, finish = Event(), Event()
    def delayed(cancelled):
        entered.set()
        assert finish.wait(5)
    engine = Engine(delayed)
    path = tmp_path / "jobs.sqlite"
    store = CaseJobStore(path, engine, clock=lambda: now[0], start=False)
    cleanup = CaseJobStore(path, engine, clock=lambda: now[0], start=False)
    store.submit(submission)
    with ThreadPoolExecutor() as executor:
        running = executor.submit(store.run_once)
        assert entered.wait(5)
        now[0] += store.LEASE_SECONDS + 1
        scope = CaseJobScope(tenantId=submission.tenantId, caseId=submission.caseId)
        assert cleanup.forget_case(scope)["forgotten"]
        finish.set()
        assert running.result(5)
    with pytest.raises(HTTPException) as missing:
        cleanup.status(identity(submission))
    assert missing.value.status_code == 404 and engine.calls == 1


def test_queued_jobs_resume_after_worker_reopen(tmp_path, submission):
    engine = Engine()
    path = tmp_path / "jobs.sqlite"
    CaseJobStore(path, engine, start=False).submit(submission)
    restarted = CaseJobStore(path, engine, start=False)
    assert restarted.run_once()
    assert restarted.status(identity(submission))["status"] == "COMPLETED"
    assert engine.calls == 1


def test_queue_is_bounded_and_terminal_receipts_do_not_consume_capacity(tmp_path, submission):
    store = CaseJobStore(tmp_path / "jobs.sqlite", Engine(), start=False)
    store.MAX_PENDING = 2
    store.submit(submission)
    other = submission.model_copy(update={"jobId": "job-b"})
    store.submit(other)
    with pytest.raises(HTTPException) as error:
        store.submit(submission.model_copy(update={"jobId": "job-c"}))
    assert error.value.status_code == 429
    store.cancel(identity(submission))
    assert store.submit(submission.model_copy(update={"jobId": "job-c"}))["status"] == "QUEUED"


def test_busy_without_inference_waits_but_timeout_does_not_retry(tmp_path, submission):
    now = [1000.0]
    def busy(cancelled):
        raise UatModelBusy("No inference started")
    engine = Engine(busy)
    store = CaseJobStore(tmp_path / "jobs.sqlite", engine, clock=lambda: now[0], start=False)
    store.submit(submission)
    assert store.run_once()
    assert store.status(identity(submission))["status"] == "QUEUED"
    assert not store.run_once()
    now[0] += 5
    def timed_out(cancelled):
        raise UatModelTimeout("private provider content")
    engine.run = timed_out
    assert store.run_once()
    receipt = store.status(identity(submission))
    assert receipt["status"] == "FAILED" and receipt["error"]["code"] == "UAT_MODEL_TIMEOUT"
    assert "private provider" not in str(receipt)
    assert not store.run_once()


def test_cancellation_context_stops_correction_call_and_original_engine_still_works(settings, case_request):
    engine, calls = adapter(settings, plain())
    with pytest.raises(CaseJobCancelled):
        engine.run_cancellable(case_request, lambda: bool(calls))
    assert len(calls) == 1
    result = engine.run(case_request)
    assert result.model.actualCalls == 1 and len(calls) == 2


def test_cancel_before_generation_makes_no_provider_call(settings, case_request):
    engine, calls = adapter(settings, plain())
    with pytest.raises(CaseJobCancelled):
        engine.run_cancellable(case_request, lambda: True)
    assert calls == []


def test_job_routes_require_service_auth_and_preserve_scope(settings, submission):
    engine = Engine()
    with TestClient(create_app(settings, case_engine=engine)) as client:
        body = submission.model_dump(exclude_unset=True)
        for route in ("submit", "status", "cancel"):
            assert client.post("/case/jobs/" + route, json=body).status_code == 401
        headers = {"X-Service-Key": settings.service_key}
        submitted = client.post("/case/jobs/submit", json=body, headers=headers)
        assert submitted.status_code == 200
        wrong_scope = identity(submission, tenantId="another-tenant").model_dump()
        response = client.post("/case/jobs/status", json=wrong_scope, headers=headers)
        assert response.status_code == 404
        assert response.json()["detail"]["code"] == "CASE_WORKER_JOB_NOT_FOUND"
        status = client.post("/case/jobs/status", json=identity(submission).model_dump(), headers=headers)
        assert status.status_code == 200
        assert "input" not in status.json() and "payload" not in status.json()


def test_case_purge_waits_for_terminal_jobs_and_prevents_late_resurrection(tmp_path, submission):
    store = CaseJobStore(tmp_path / "jobs.sqlite", Engine(), start=False)
    store.submit(submission)
    scope = CaseJobScope(tenantId=submission.tenantId, caseId=submission.caseId)
    with pytest.raises(HTTPException) as active:
        store.forget_case(scope)
    assert active.value.status_code == 409
    store.cancel(identity(submission))
    assert store.forget_case(scope)["forgotten"]
    assert store.forget_case(scope)["forgotten"]
    with pytest.raises(HTTPException) as missing:
        store.status(identity(submission))
    assert missing.value.status_code == 404
    with pytest.raises(HTTPException) as deleted:
        store.submit(submission)
    assert deleted.value.detail["code"] == "CASE_WORKER_CASE_DELETED"
    # Other tenants retain their own scope, even when case and job ids match.
    other = submission.model_copy(update={"tenantId": "tenant-b"})
    assert store.submit(other)["status"] == "QUEUED"
