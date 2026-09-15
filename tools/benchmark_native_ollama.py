"""Freeze unchanged UAT prompts and benchmark one separate native Ollama request.

Run with services/investigator/.venv/Scripts/python.exe. No inference occurs in
prepare. Full inputs/results stay under ignored runtime/obpm-uat/private; only
sanitized-receipt.json is suitable for a public validation artifact. An API GPU
memory report does not identify Vulkan/Arc: inspect the native server logs too.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
from importlib.metadata import version
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import time
from types import SimpleNamespace
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT / "runtime/obpm-uat/private"
sys.path.insert(0, str(ROOT / "services/investigator"))
MODEL = "qwen3:8b"
BASE_URL = "http://127.0.0.1:11435"
MAX_JSON_BYTES = 2_000_000
SOURCE_FILES = ["services/investigator/investigator/uat_answer.py",
                "services/investigator/investigator/config.py",
                "services/investigator/pyproject.toml", "infra/compose.uat-qa.yaml"]
ENDPOINTS = {("GET", "/api/version"), ("GET", "/api/tags"),
             ("GET", "/api/ps"), ("POST", "/api/show"), ("POST", "/api/chat")}


class BenchError(Exception):
    """A fixed, non-private error code, safe to include in a public receipt."""


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def sha(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def utc():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def private_path(path: Path) -> Path:
    resolved = path.resolve()
    if not resolved.is_relative_to(PRIVATE.resolve()) or resolved == PRIVATE.resolve():
        raise BenchError("PRIVATE_PATH_REQUIRED")
    return resolved


def read_json(path: Path):
    raw = path.read_bytes()
    if len(raw) > MAX_JSON_BYTES:
        raise BenchError("INPUT_TOO_LARGE")
    def reject(value):
        raise ValueError("Nonfinite JSON")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON key")
            result[key] = value
        return result
    try:
        return json.loads(raw, parse_constant=reject, object_pairs_hook=unique)
    except (ValueError, UnicodeError, RecursionError) as exc:
        raise BenchError("INVALID_JSON") from exc


def write_json(path, value):
    # Every artifact has a new path. Preserve previous attempts and failures.
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")


def base_url(value):
    try:
        parts = urlsplit(value)
        if (parts.scheme != "http" or parts.hostname not in {"127.0.0.1", "::1"}
                or parts.port != 11435 or parts.username is not None or parts.password is not None
                or parts.path not in {"", "/"} or parts.query or parts.fragment):
            raise ValueError()
    except ValueError as exc:
        raise BenchError("EXPERIMENT_LOOPBACK_11435_REQUIRED") from exc
    return f"http://{'[::1]' if parts.hostname == '::1' else '127.0.0.1'}:11435"


def settings():
    # Explicit benchmark settings: never load provider URLs/keys from the env.
    from investigator.config import Settings
    return Settings(service_key="", knowledge_path=ROOT / "data/knowledge/runbooks.json",
                    checkpoint_path=PRIVATE / "unused-benchmark-checkpoint",
                    ollama_base_url=BASE_URL, uat_model=MODEL,
                    uat_context_tokens=32768, uat_output_tokens=1400, model_threads=4,
                    uat_model_timeout_seconds=360, uat_model_keep_alive_seconds=1800)


def freeze_payload(request):
    """Use the actual worker construction/guard and installed client serialization.

    Capture returns an empty internal placeholder, never a displayed answer. It
    makes no model call and is not passed through synthesis validation. Real
    benchmark outputs are validated separately using the unchanged worker.
    """
    from investigator.uat_answer import UatAnswerEngine
    from ollama._types import ChatRequest

    class Capture:
        def bind(self, **kwargs):
            self.kwargs = kwargs
            return self

        def invoke(self, messages):
            self.messages = messages
            return SimpleNamespace(content="{}", response_metadata={}, usage_metadata={})

    capture = Capture()
    engine = UatAnswerEngine(settings(), model_factory=lambda: capture)
    state = {"request": request, **engine._retrieve({"request": request})}
    engine._generate(state)  # Includes the real conservative context guard.
    model = engine._model()  # Constructs clients only; no invoke/send call.
    try:
        params = model._chat_params(capture.messages, **capture.kwargs)
        params["tools"] = []  # Same empty list inserted by ollama.Client.chat.
        payload = ChatRequest(**params).model_dump(mode="json", exclude_none=True)
    finally:
        model._client._client.close()
    if payload.get("model") != MODEL or payload.get("stream") is not False or payload.get("think") is not False:
        raise BenchError("UNEXPECTED_WORKER_MODEL_CONTRACT")
    if payload.get("options") != {"temperature": 0.0, "num_ctx": 32768, "num_thread": 4, "num_predict": 1400}:
        raise BenchError("UNEXPECTED_WORKER_OPTIONS")
    if payload.get("keep_alive") != "1800s" or payload.get("tools") != []:
        raise BenchError("UNEXPECTED_WORKER_OPTIONS")
    return payload


def synthetic_bundle(kind):
    count = "2" if kind == "unknown" else "7"
    bundle = {"snapshotId": "original-native-control", "classification": "SYNTHETIC",
              "documents": [
                  {"id": "CONTROL-ROW", "kind": "evidence", "title": "Original synthetic observation",
                   "content": json.dumps({"sequenceCount": count, "beneficiaryCreditEvidence": "not supplied"}),
                   "source": {"file": "original-native-control.json"}},
                  {"id": "CONTROL-SCOPE", "kind": "knowledge", "title": "Original control field scope",
                   "content": "In this original synthetic fixture, sequenceCount counts observations only. It does not establish a funds outcome. Credit confirmation is outside the supplied scope.",
                   "source": {"file": "original-native-control-scope.txt"}}]}
    bundle["evidenceHash"] = sha(canonical(bundle))
    return bundle


def prepare(run_dir, *, bundle_path=None, question_path=None, baseline_path=None, smoke=None):
    from investigator.uat_answer import UatAnswerRequest
    target = private_path(run_dir)
    if target.exists():
        raise BenchError("PREPARED_DIRECTORY_EXISTS")
    if smoke:
        bundle = synthetic_bundle(smoke)
        question = "What sequence count is recorded, and does this snapshot establish beneficiary credit?"
    else:
        if bundle_path is None or question_path is None:
            raise BenchError("BUNDLE_AND_QUESTION_REQUIRED")
        bundle = read_json(private_path(bundle_path))
        question = private_path(question_path).read_text(encoding="utf-8-sig").strip()
    if not isinstance(bundle, dict) or bundle.get("evidenceHash") != sha(canonical({k: v for k, v in bundle.items() if k != "evidenceHash"})):
        raise BenchError("EVIDENCE_HASH_MISMATCH")
    request = UatAnswerRequest.model_validate({"question": question, "snapshotId": bundle["snapshotId"],
                                             "evidenceHash": bundle["evidenceHash"], "documents": bundle["documents"]})
    request_dict = request.model_dump(exclude_unset=True)
    payload = freeze_payload(request)
    baseline_sha = None
    baseline_matches = None
    if baseline_path:
        resolved = baseline_path.resolve()
        # Public sanitized receipt or ignored private original receipt only.
        if not (resolved.is_relative_to(PRIVATE.resolve()) or resolved.is_relative_to((ROOT / "docs/validation").resolve())):
            raise BenchError("BASELINE_RECEIPT_PATH_INVALID")
        baseline = read_json(resolved)
        baseline_sha = sha(resolved.read_bytes())
        if isinstance(baseline, dict) and isinstance(baseline.get("request"), dict):
            baseline_matches = canonical(baseline["request"]) == canonical(request_dict)
    prepared = {"version": "native-ollama-benchmark-v1", "kind": "synthetic" if smoke else "private-uat",
                "createdAt": utc(), "evidenceHash": request.evidenceHash,
                "requestHash": sha(canonical(request_dict)), "payloadHash": sha(canonical(payload)),
                "questionHash": sha(question.encode("utf-8")), "baselineReceiptSha256": baseline_sha,
                "baselineRequestMatches": baseline_matches,
                "sourceHashes": {file: sha((ROOT / file).read_bytes()) for file in SOURCE_FILES},
                "packages": {name: version(name) for name in ["langchain-ollama", "ollama", "pydantic", "httpx"]},
                "settings": {"model": MODEL, "num_ctx": 32768, "num_thread": 4, "num_predict": 1400,
                             "temperature": 0, "think": False, "stream": False, "keep_alive": "1800s"}}
    target.mkdir(parents=True)
    write_json(target / "bundle.json", bundle)
    write_json(target / "request.json", request_dict)
    write_json(target / "ollama-request.json", payload)
    write_json(target / "prepared.json", prepared)
    return prepared


def load_prepared(directory):
    from investigator.uat_answer import UatAnswerRequest
    directory = private_path(directory)
    prepared = read_json(directory / "prepared.json")
    request_dict = read_json(directory / "request.json")
    payload = read_json(directory / "ollama-request.json")
    bundle = read_json(directory / "bundle.json")
    if (prepared.get("version") != "native-ollama-benchmark-v1"
            or prepared.get("payloadHash") != sha(canonical(payload))
            or prepared.get("requestHash") != sha(canonical(request_dict))
            or prepared.get("evidenceHash") != bundle.get("evidenceHash")
            or bundle.get("evidenceHash") != sha(canonical({k: v for k, v in bundle.items() if k != "evidenceHash"}))
            or request_dict.get("evidenceHash") != bundle.get("evidenceHash")
            or request_dict.get("snapshotId") != bundle.get("snapshotId")
            or request_dict.get("documents") != bundle.get("documents")
            or prepared.get("sourceHashes") != {file: sha((ROOT / file).read_bytes()) for file in SOURCE_FILES}):
        raise BenchError("PREPARED_INPUT_CHANGED")
    request = UatAnswerRequest.model_validate(request_dict)
    if canonical(freeze_payload(request)) != canonical(payload):
        raise BenchError("WORKER_PAYLOAD_CHANGED")
    return prepared, request, payload


def request_json(client, base, method, endpoint, payload=None):
    if (method, endpoint) not in ENDPOINTS:
        raise BenchError("ENDPOINT_NOT_ALLOWED")
    with client.stream(method, base_url(base) + endpoint, json=payload) as response:
        if response.status_code != 200:
            raise BenchError("OLLAMA_HTTP_ERROR")
        raw = bytearray()
        for chunk in response.iter_bytes():
            raw.extend(chunk)
            if len(raw) > MAX_JSON_BYTES:
                raise BenchError("OLLAMA_RESPONSE_TOO_LARGE")
    try:
        result = json.loads(raw)
    except (ValueError, UnicodeError) as exc:
        raise BenchError("OLLAMA_INVALID_JSON") from exc
    if not isinstance(result, dict):
        raise BenchError("OLLAMA_INVALID_JSON")
    return result


def matching_model(data):
    rows = data.get("models", [])
    if not isinstance(rows, list):
        raise BenchError("OLLAMA_INVALID_METADATA")
    matching = [row for row in rows if isinstance(row, dict) and (row.get("name") == MODEL or row.get("model") == MODEL)]
    if len(matching) > 1:
        raise BenchError("OLLAMA_AMBIGUOUS_MODEL")
    return matching[0] if matching else None


def validate_response(response, request, chat_wall_ms):
    from investigator.uat_answer import UatAnswerEngine
    if number(chat_wall_ms) is None:
        raise BenchError("INVALID_CHAT_WALL_DURATION")
    if response.get("done") is not True or response.get("done_reason") in {"length", "max_tokens", "max_length", "limit"}:
        raise BenchError("MODEL_INCOMPLETE_OR_TRUNCATED")
    if response.get("model") != MODEL:
        raise BenchError("MODEL_IDENTITY_MISMATCH")
    message = response.get("message")
    if not isinstance(message, dict) or message.get("role") != "assistant" or message.get("tool_calls"):
        raise BenchError("MODEL_MESSAGE_INVALID")
    content = message.get("content")
    if not isinstance(content, str) or not content.strip() or len(content) > 50000:
        raise BenchError("MODEL_CONTENT_INVALID")
    engine = UatAnswerEngine(settings())
    state = {"request": request, **engine._retrieve({"request": request}), "content": content,
             "usage": {"provider": "ollama", "name": MODEL, "actualCalls": 1,
                       "promptTokens": number(response.get("prompt_eval_count")),
                       "completionTokens": number(response.get("eval_count")), "durationMs": chat_wall_ms}}
    try:
        return engine._validate(state)["result"].model_dump(exclude_unset=True)
    except Exception as exc:
        raise BenchError("MODEL_STRUCTURE_OR_CITATIONS_INVALID") from exc


def number(value):
    return value if isinstance(value, int) and not isinstance(value, bool) and 0 <= value <= 10**18 else None


def digest(value):
    if isinstance(value, str) and re.fullmatch(r"(?:sha256:)?[0-9a-f]{64}", value):
        return value.removeprefix("sha256:")
    return None


def device_summary(data):
    row = matching_model(data or {})
    if not row:
        return None
    return {"modelDigest": digest(row.get("digest")), "sizeBytes": number(row.get("size")),
            "gpuAllocatedBytes": number(row.get("size_vram")), "contextLength": number(row.get("context_length")),
            "backendIdentity": "not-established-by-api"}


def execute(directory, trial, base):
    """Child process. The parent enforces the whole-run deadline and stores failures."""
    import httpx
    target = private_path(trial)
    result = {"status": "failed", "errorCode": "UNEXPECTED_FAILURE", "chatRequestsAttempted": 0}
    try:
        _, request, payload = load_prepared(directory)
        with httpx.Client(timeout=httpx.Timeout(350, connect=5, write=30, pool=5),
                          follow_redirects=False, trust_env=False) as client:
            metadata = {}
            for name, method, endpoint, body in [
                ("version", "GET", "/api/version", None), ("tags", "GET", "/api/tags", None),
                ("show", "POST", "/api/show", {"model": MODEL}), ("before", "GET", "/api/ps", None)]:
                metadata[name] = request_json(client, base, method, endpoint, body)
                write_json(target / f"{name}.json", metadata[name])
            listed = matching_model(metadata["tags"])
            if not listed or digest(listed.get("digest")) is None:
                raise BenchError("MODEL_NOT_INSTALLED_WITH_DIGEST")
            write_json(target / "chat-started.json", {"at": utc(), "chatRequestsAttempted": 1})
            result["chatRequestsAttempted"] = 1
            started = time.perf_counter()
            response = request_json(client, base, "POST", "/api/chat", payload)
            result["chatWallMs"] = round((time.perf_counter() - started) * 1000)
            write_json(target / "ollama-response.json", response)
            # Capture device metadata even when generated prose fails validation.
            after = request_json(client, base, "GET", "/api/ps")
            write_json(target / "after.json", after)
            loaded = matching_model(after)
            if not loaded or digest(loaded.get("digest")) != digest(listed.get("digest")):
                raise BenchError("MODEL_DIGEST_NOT_CONFIRMED_AFTER_CALL")
            answer = validate_response(response, request, result["chatWallMs"])
            write_json(target / "validated-answer.json", answer)
            result.update(status="completed", errorCode=None)
    except BenchError as exc:
        result["errorCode"] = str(exc)
    except httpx.TimeoutException:
        result["errorCode"] = "TRANSPORT_TIMEOUT"
    except httpx.HTTPError:
        result["errorCode"] = "TRANSPORT_FAILURE"
    except Exception:
        result["errorCode"] = "UNEXPECTED_FAILURE"
    write_json(target / "child-result.json", result)


def sanitized_receipt(prepared, trial, result, wall_ms, cap):
    def optional(name):
        path = trial / name
        try:
            value = read_json(path) if path.exists() else {}
            return value if isinstance(value, dict) else {}
        except (BenchError, OSError):
            # A wall-clock kill can interrupt a private artifact write. Preserve
            # its bytes, but still record a bounded sanitized failure receipt.
            return {}
    def safe_model(value):
        try:
            return matching_model(value)
        except BenchError:
            return None
    response, before, after, tags, show = [optional(name) for name in
                                         ["ollama-response.json", "before.json", "after.json", "tags.json", "show.json"]]
    listed = safe_model(tags)
    details = show.get("details", {})
    quantization = details.get("quantization_level") if isinstance(details, dict) else None
    if not isinstance(quantization, str) or not re.fullmatch(r"(?:Q[2-8](?:_[A-Z0-9]+)*|IQ[1-4](?:_[A-Z0-9]+)*|F16|F32|BF16)", quantization):
        quantization = None
    service_version = optional("version.json").get("version")
    if not isinstance(service_version, str) or not re.fullmatch(r"\d{1,3}\.\d{1,3}\.\d{1,3}(?:-rc\d{1,3})?", service_version):
        service_version = None
    timing = {key: number(response.get(key)) for key in ["total_duration", "load_duration", "prompt_eval_duration", "eval_duration",
                                                        "prompt_eval_count", "eval_count"]}
    rates = {}
    for name, count, duration in [("promptTokensPerSecond", "prompt_eval_count", "prompt_eval_duration"),
                                  ("completionTokensPerSecond", "eval_count", "eval_duration")]:
        rates[name] = round(timing[count] * 1_000_000_000 / timing[duration], 3) if timing[count] is not None and timing[duration] else None
    return {"version": "native-ollama-benchmark-v1", "createdAt": utc(), "profile": "native-ollama-experiment",
            "status": result["status"], "errorCode": result["errorCode"],
            "chatRequestsAttempted": 1 if (trial / "chat-started.json").exists() else 0,
            "baselineConfigurationModifiedByHarness": False, "inputKind": prepared["kind"],
            **{key: prepared[key] for key in ["evidenceHash", "requestHash", "payloadHash", "questionHash",
                                            "baselineReceiptSha256", "baselineRequestMatches", "sourceHashes", "packages", "settings"]},
            "wallCapSeconds": cap, "wholeRunWallMs": wall_ms, "chatWallMs": number(result.get("chatWallMs")),
            "ollamaVersion": service_version, "modelDigest": digest(listed.get("digest")) if listed else None,
            "quantizationLevel": quantization, "reportedTimingNanosecondsAndCounts": timing, "rates": rates,
            "loadedBefore": device_summary({"models": [safe_model(before)]}) if safe_model(before) else None,
            "loadedAfter": device_summary({"models": [safe_model(after)]}) if safe_model(after) else None,
            "rawResponseSha256": sha((trial / "ollama-response.json").read_bytes()) if (trial / "ollama-response.json").exists() else None,
            "validation": "structure-and-source-membership-only" if result["status"] == "completed" else "not-passed",
            "semanticReview": "not-performed-by-harness", "gpuBackend": "requires-native-log-review",
            "timeoutLimit": "Client process is terminated at deadline; server cancellation must be verified separately."}


def run(directory, label, base=BASE_URL, wall_seconds=360):
    base = base_url(base)
    if not isinstance(wall_seconds, (int, float)) or isinstance(wall_seconds, bool) or not math.isfinite(wall_seconds) or not 0 < wall_seconds <= 360:
        raise BenchError("WALL_CAP_MUST_BE_AT_MOST_360_SECONDS")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,39}", label):
        raise BenchError("INVALID_TRIAL_LABEL")
    directory = private_path(directory)
    prepared, _, _ = load_prepared(directory)
    trial = directory / "trials" / label
    trial.mkdir(parents=True, exist_ok=False)
    started = time.perf_counter()
    command = [sys.executable, str(Path(__file__).resolve()), "_execute", "--run-dir", str(directory),
               "--trial-dir", str(trial), "--base-url", base]
    # No shell, inherited stdin, shell expansions, proxy variables or public logs.
    process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                               creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    try:
        process.wait(timeout=wall_seconds)
        result = read_json(trial / "child-result.json") if (trial / "child-result.json").exists() else {
            "status": "failed", "errorCode": "CHILD_FAILED"}
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)
        result = {"status": "failed", "errorCode": "WALL_TIMEOUT"}
    wall_ms = round((time.perf_counter() - started) * 1000)
    receipt = sanitized_receipt(prepared, trial, result, wall_ms, wall_seconds)
    write_json(trial / "sanitized-receipt.json", receipt)
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    prep = sub.add_parser("prepare", help="Freeze the existing prompt/schema/options; zero HTTP/model calls")
    prep.add_argument("--run-dir", type=Path, required=True)
    choice = prep.add_mutually_exclusive_group(required=True)
    choice.add_argument("--bundle", type=Path)
    choice.add_argument("--smoke", choices=["unknown", "changed"])
    prep.add_argument("--question-file", type=Path)
    prep.add_argument("--baseline-receipt", type=Path)
    run_parser = sub.add_parser("run", help="One bounded native-only model request plus metadata")
    run_parser.add_argument("--run-dir", type=Path, required=True)
    run_parser.add_argument("--trial", required=True)
    run_parser.add_argument("--base-url", default=BASE_URL)
    run_parser.add_argument("--wall-seconds", type=float, default=360)
    child = sub.add_parser("_execute", help=argparse.SUPPRESS)
    child.add_argument("--run-dir", type=Path, required=True)
    child.add_argument("--trial-dir", type=Path, required=True)
    child.add_argument("--base-url", required=True)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            result = prepare(args.run_dir, bundle_path=args.bundle, question_path=args.question_file,
                             baseline_path=args.baseline_receipt, smoke=args.smoke)
            print(json.dumps({"prepared": True, "modelCalls": 0, "requestHash": result["requestHash"], "payloadHash": result["payloadHash"]}))
        elif args.command == "run":
            receipt = run(args.run_dir, args.trial, args.base_url, args.wall_seconds)
            print(json.dumps(receipt, indent=2))
            return 0 if receipt["status"] == "completed" else 1
        else:
            execute(args.run_dir, args.trial_dir, base_url(args.base_url))
    except Exception as exc:
        # Never expose Pydantic input values, private paths or provider bodies.
        code = str(exc) if isinstance(exc, BenchError) else "BENCHMARK_INPUT_OR_IO_ERROR"
        print(json.dumps({"ok": False, "errorCode": code}))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
