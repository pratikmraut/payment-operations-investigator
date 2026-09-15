"""Synthetic-only benchmark contract tests; all HTTP and subprocess work is mocked."""
import json
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace
from unittest.mock import patch

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import benchmark_native_ollama as bench
from investigator.uat_answer import UatAnswerRequest, SYSTEM_PROMPT


@pytest.fixture
def private(tmp_path, monkeypatch):
    monkeypatch.setattr(bench, "PRIVATE", tmp_path)
    return tmp_path


def request(kind="unknown"):
    bundle = bench.synthetic_bundle(kind)
    return UatAnswerRequest.model_validate({"question": "What is recorded?", "snapshotId": bundle["snapshotId"],
                                            "evidenceHash": bundle["evidenceHash"], "documents": bundle["documents"]})


def response(content=None):
    return {"model": bench.MODEL, "done": True, "done_reason": "stop",
            "message": {"role": "assistant", "content": json.dumps(content or {
                "claims": [{"text": "The supplied control records two observations.", "evidenceIds": ["CONTROL-ROW"]}],
                "unknowns": ["Credit confirmation was not supplied."], "nextChecks": []})},
            "prompt_eval_count": 100, "eval_count": 20, "total_duration": 3_000_000_000,
            "load_duration": 0, "prompt_eval_duration": 1_000_000_000, "eval_duration": 2_000_000_000}


@pytest.mark.parametrize("url", ["http://127.0.0.1:11435", "http://127.0.0.1:11435/", "http://[::1]:11435"])
def test_literal_experimental_loopback_only(url):
    assert bench.base_url(url).endswith(":11435")


@pytest.mark.parametrize("url", ["http://localhost:11435", "http://127.0.0.1:11434", "http://127.0.0.1:11438",
    "http://192.168.0.1:11435", "https://127.0.0.1:11435", "http://127.0.0.1:11435/api/chat",
    "http://secret@127.0.0.1:11435", "http://127.0.0.1:11435?x=y", "http://127.0.0.1:11435#part"])
def test_rejects_baseline_remote_credentials_and_url_extras(url):
    with pytest.raises(bench.BenchError):
        bench.base_url(url)


def test_freeze_is_offline_and_uses_exact_original_worker_prompt():
    with patch.object(httpx.Client, "send", side_effect=AssertionError("network forbidden")):
        payload = bench.freeze_payload(request())
    assert payload["messages"][0]["role"] == "system"
    assert payload["messages"][0]["content"] == SYSTEM_PROMPT
    assert payload["messages"][0].get("images", []) == []
    assert list(json.loads(payload["messages"][1]["content"]))[-1] == "question"
    assert payload["options"] == {"num_ctx": 32768, "num_thread": 4, "num_predict": 1400, "temperature": 0.0}
    assert payload["keep_alive"] == "1800s"
    assert payload["think"] is False
    assert "answer" not in payload["format"]["properties"]


def test_synthetic_data_change_changes_payload_without_canned_answer():
    first, second = bench.freeze_payload(request()), bench.freeze_payload(request("changed"))
    assert first["messages"][0] == second["messages"][0]
    assert first["messages"][1]["content"] != second["messages"][1]["content"]
    assert bench.sha(bench.canonical(first)) != bench.sha(bench.canonical(second))


def test_prepare_is_private_immutable_and_tamper_checked(private):
    target = private / "control"
    with patch.object(httpx.Client, "send", side_effect=AssertionError("network forbidden")):
        prepared = bench.prepare(target, smoke="unknown")
        loaded, _, _ = bench.load_prepared(target)
    assert loaded == prepared
    with pytest.raises(bench.BenchError, match="EXISTS"):
        bench.prepare(target, smoke="changed")
    raw = json.loads((target / "ollama-request.json").read_text())
    raw["options"]["num_ctx"] = 8192
    (target / "ollama-request.json").write_text(json.dumps(raw))
    with pytest.raises(bench.BenchError, match="CHANGED"):
        bench.load_prepared(target)


def test_rejects_public_private_artifact_location(private):
    with pytest.raises(bench.BenchError, match="PRIVATE_PATH"):
        bench.prepare(private.parent / "public", smoke="unknown")


def test_real_output_exact_claim_composition_and_citation_validation():
    output = bench.validate_response(response(), request(), 3127)
    assert output["answer"] == "\n\n".join(claim["text"] for claim in output["claims"])
    assert output["answerComposition"] == "joined-model-claims"
    assert output["model"]["durationMs"] == 3127
    bad = response({"claims": [{"text": "Unsupported citation.", "evidenceIds": ["INVENTED"]}], "unknowns": [], "nextChecks": []})
    with pytest.raises(bench.BenchError, match="CITATIONS"):
        bench.validate_response(bad, request(), 123)


@pytest.mark.parametrize("change", [{"done": False}, {"done_reason": "length"}, {"model": "other"}])
def test_incomplete_truncated_wrong_identity_fail(change):
    value = response()
    value.update(change)
    with pytest.raises(bench.BenchError):
        bench.validate_response(value, request(), 123)


def test_network_endpoint_allowlist_and_redirect_rejection():
    def handler(req):
        return httpx.Response(302, headers={"location": "https://example.com"})
    with httpx.Client(transport=httpx.MockTransport(handler), follow_redirects=False, trust_env=False) as client:
        with pytest.raises(bench.BenchError, match="ENDPOINT_NOT_ALLOWED"):
            bench.request_json(client, bench.BASE_URL, "POST", "/api/pull", {})
        with pytest.raises(bench.BenchError, match="HTTP_ERROR"):
            bench.request_json(client, bench.BASE_URL, "GET", "/api/ps")


def test_execute_uses_one_chat_and_preserves_failed_model_output(private):
    target = private / "case"
    bench.prepare(target, smoke="unknown")
    trial = target / "trials" / "invalid-output"
    trial.mkdir(parents=True)
    calls = []
    invalid_response = response({"claims": [{"text": "Original invalid-citation control.", "evidenceIds": ["INVENTED"]}],
                                 "unknowns": [], "nextChecks": []})
    def fake_http(client, base, method, endpoint, payload=None):
        calls.append((method, endpoint))
        if endpoint == "/api/chat":
            assert payload["model"] == bench.MODEL
            return invalid_response
        if endpoint in {"/api/tags", "/api/ps"}:
            return {"models": [{"name": bench.MODEL, "digest": "a" * 64, "size_vram": 1024}]}
        return {"version": "0.34.0"} if endpoint == "/api/version" else {"details": {"quantization_level": "Q4_K_M"}}
    with patch.object(bench, "request_json", side_effect=fake_http):
        bench.execute(target, trial, bench.BASE_URL)
    result = bench.read_json(trial / "child-result.json")
    assert result["errorCode"] == "MODEL_STRUCTURE_OR_CITATIONS_INVALID"
    assert calls.count(("POST", "/api/chat")) == 1
    assert (trial / "ollama-response.json").exists()
    assert (trial / "after.json").exists()
    assert not (trial / "validated-answer.json").exists()


def test_model_transport_failure_is_fixed_code_with_no_retry(private):
    target = private / "case"
    bench.prepare(target, smoke="unknown")
    trial = target / "trials" / "transport-error"
    trial.mkdir(parents=True)
    def fake_http(client, base, method, endpoint, payload=None):
        if endpoint == "/api/chat":
            raise httpx.ReadTimeout("PRIVATE-BODY-MUST-NOT-APPEAR")
        if endpoint in {"/api/tags", "/api/ps"}:
            return {"models": [{"name": bench.MODEL, "digest": "a" * 64}]}
        return {}
    with patch.object(bench, "request_json", side_effect=fake_http) as http:
        bench.execute(target, trial, bench.BASE_URL)
    assert sum(call.args[3] == "/api/chat" for call in http.call_args_list) == 1
    result = bench.read_json(trial / "child-result.json")
    assert result["errorCode"] == "TRANSPORT_TIMEOUT"
    assert "PRIVATE-BODY" not in json.dumps(result)


def test_sanitized_receipt_never_copies_source_or_model_prose(private):
    target = private / "case"
    prepared = bench.prepare(target, smoke="unknown")
    trial = target / "trials" / "first"
    trial.mkdir(parents=True)
    private_marker = "PRIVATE-BANK-VALUE-DO-NOT-PUBLISH"
    value = response({"claims": [{"text": private_marker, "evidenceIds": ["CONTROL-ROW"]}], "unknowns": [], "nextChecks": []})
    value["unexpected"] = private_marker
    bench.write_json(trial / "ollama-response.json", value)
    bench.write_json(trial / "show.json", {"details": {"quantization_level": private_marker}})
    bench.write_json(trial / "version.json", {"version": private_marker})
    bench.write_json(trial / "tags.json", {"models": [{"name": bench.MODEL, "digest": "a" * 64, "private": private_marker}]})
    bench.write_json(trial / "after.json", {"models": [{"name": bench.MODEL, "digest": "a" * 64, "size_vram": 1024,
                                                         "context_length": 32768, "private": private_marker}]})
    result = bench.sanitized_receipt(prepared, trial, {"status": "completed", "errorCode": None}, 3100, 360)
    assert private_marker not in json.dumps(result)
    assert "documents" not in result and "question" not in result and "answer" not in result
    assert result["rates"] == {"promptTokensPerSecond": 100.0, "completionTokensPerSecond": 10.0}
    assert result["loadedAfter"]["gpuAllocatedBytes"] == 1024
    assert result["loadedAfter"]["backendIdentity"] == "not-established-by-api"
    assert result["semanticReview"] == "not-performed-by-harness"


def test_interrupted_partial_metadata_still_has_failure_receipt(private):
    target = private / "case"
    prepared = bench.prepare(target, smoke="unknown")
    trial = target / "trials" / "killed"
    trial.mkdir(parents=True)
    (trial / "ollama-response.json").write_text('{"unfinished":')
    bench.write_json(trial / "after.json", {"models": "invalid"})
    result = bench.sanitized_receipt(prepared, trial, {"status": "failed", "errorCode": "WALL_TIMEOUT"}, 360010, 360)
    assert result["errorCode"] == "WALL_TIMEOUT"
    assert result["rawResponseSha256"] is not None
    assert result["loadedAfter"] is None


def test_parent_deadline_kills_only_its_child_and_records_failure(private):
    target = private / "case"
    bench.prepare(target, smoke="unknown")
    child = SimpleNamespace(wait=None, kill=None)
    waits, kills = [], []
    def wait(timeout):
        waits.append(timeout)
        if len(waits) == 1:
            raise subprocess.TimeoutExpired("mock child", timeout)
    child.wait = wait
    child.kill = lambda: kills.append(True)
    with patch.object(subprocess, "Popen", return_value=child) as spawn:
        result = bench.run(target, "bounded", wall_seconds=0.01)
    assert result["errorCode"] == "WALL_TIMEOUT"
    assert waits == [0.01, 5] and kills == [True]
    assert spawn.call_args.args[0][0] == sys.executable
    assert "--base-url" in spawn.call_args.args[0]
    assert (target / "trials/bounded/sanitized-receipt.json").exists()


@pytest.mark.parametrize("cap", [0, -1, 361, float("inf"), float("nan"), True])
def test_rejects_unbounded_deadline(private, cap):
    with pytest.raises(bench.BenchError, match="WALL_CAP"):
        bench.run(private / "absent", "trial", wall_seconds=cap)
