from copy import deepcopy
from dataclasses import replace
import json

from fastapi.testclient import TestClient
import httpx
import pytest
from pydantic import ValidationError

from investigator.case_knowledge import (CaseKnowledgeSearch, KnowledgeSearchRequest,
                                        KnowledgeSearchTimeout, MODEL, QUERY_CACHE_ENTRIES,
                                        QUERY_CACHE_TTL_SECONDS)
from investigator.errors import ProviderUnavailable, UatModelBusy
from investigator.main import create_app


DIGEST = "a" * 64


def vector(first=1.0, second=0.0):
    return [first, second] + [0.0] * 1022


def request_body():
    return {"question": "What does the supplied synthetic status mean?", "model": MODEL,
            "digest": DIGEST, "limit": 3, "entries": [
                {"id": "Z-SAME", "vector": vector()},
                {"id": "A-SAME", "vector": vector()},
                {"id": "B-OTHER", "vector": vector(0.0, 1.0)},
                {"id": "C-OPPOSITE", "vector": vector(-1.0)},
            ]}


def mock_search(settings, override=None, clock=None):
    calls = []

    def respond(request):
        body = json.loads(request.content) if request.content else None
        calls.append((request.url.path, body))
        if override:
            result = override(request, body)
            if result is not None:
                return result
        if request.url.path == "/api/tags":
            return httpx.Response(200, json={"models": [{"name": MODEL, "digest": DIGEST}]})
        if request.url.path == "/api/embed":
            return httpx.Response(200, json={"model": MODEL, "embeddings": [vector()]})
        if request.url.path == "/api/ps":
            return httpx.Response(200, json={"models": [{"name": MODEL, "digest": DIGEST, "size_vram": 12345}]})
        if request.url.path == "/api/generate":
            return httpx.Response(200, json={"model": MODEL, "done": True, "done_reason": "unload"})
        pytest.fail(f"Unexpected provider path {request.url.path}")

    options = {"clock": clock} if clock else {}
    engine = CaseKnowledgeSearch(settings, client_factory=lambda: httpx.Client(
        base_url=settings.ollama_base_url, transport=httpx.MockTransport(respond)), **options)
    return engine, calls


def test_real_query_request_and_cosine_ranking_are_deterministic_and_unload(settings):
    engine, calls = mock_search(settings)
    original = request_body()
    body = deepcopy(original)
    body["digest"] = "sha256:" + DIGEST
    result = engine.run(KnowledgeSearchRequest.model_validate(body))
    assert result.model_dump() == {
        "model": MODEL, "digest": "sha256:" + DIGEST, "processor": "GPU", "matches": [
            {"id": "A-SAME", "score": 1.0}, {"id": "Z-SAME", "score": 1.0},
            {"id": "B-OTHER", "score": 0.0}]}
    assert [path for path, _ in calls] == ["/api/tags", "/api/embed", "/api/ps", "/api/generate"]
    assert calls[1][1] == {"model": MODEL, "input": original["question"], "truncate": False,
                          "dimensions": 1024, "keep_alive": "30s", "options": {"num_gpu": 999}}
    assert calls[3][1] == {"model": MODEL, "prompt": "", "keep_alive": 0, "stream": False}
    assert body["entries"] == original["entries"]


@pytest.mark.parametrize("change", [
    lambda body: body.update(question=" "),
    lambda body: body.update(question="x" * 2001),
    lambda body: body.update(model="other-model"),
    lambda body: body.update(digest="bad"),
    lambda body: body.update(limit=4),
    lambda body: body.update(url="https://unapproved.invalid"),
    lambda body: body.update(entries=[]),
    lambda body: body.update(entries=[{"id": str(i), "vector": vector()} for i in range(1001)]),
    lambda body: body["entries"][1].update(id="Z-SAME"),
    lambda body: body["entries"][0].update(vector=[0.0] * 1024),
    lambda body: body["entries"][0].update(vector=[1.0] * 1023),
    lambda body: body["entries"][0]["vector"].__setitem__(0, True),
    lambda body: body["entries"][0]["vector"].__setitem__(0, "1"),
    lambda body: body["entries"][0]["vector"].__setitem__(0, 2.0),
    lambda body: body["entries"][0]["vector"].__setitem__(0, float("nan")),
    lambda body: body["entries"][0]["vector"].__setitem__(0, float("inf")),
])
def test_invalid_or_ambiguous_index_is_rejected_before_provider_use(change):
    body = request_body()
    change(body)
    with pytest.raises(ValidationError):
        KnowledgeSearchRequest.model_validate(body)


def test_installed_digest_mismatch_never_embeds_or_unloads_another_model(settings):
    def override(request, body):
        return httpx.Response(200, json={"models": [{"name": MODEL, "digest": "b" * 64}]})
    engine, calls = mock_search(settings, override)
    with pytest.raises(ProviderUnavailable, match="fingerprint"):
        engine.run(KnowledgeSearchRequest.model_validate(request_body()))
    assert [path for path, _ in calls] == ["/api/tags"]


@pytest.mark.parametrize("record", [
    {"name": MODEL, "digest": DIGEST, "size_vram": 0},
    {"name": MODEL, "digest": DIGEST, "size_vram": True},
    {"name": MODEL, "digest": DIGEST},
    {"name": MODEL, "digest": "b" * 64, "size_vram": 200},
    {"name": "another-model", "digest": DIGEST, "size_vram": 200},
])
def test_cpu_or_mismatched_loaded_model_never_returns_matches_and_unloads(settings, record):
    def override(request, body):
        if request.url.path == "/api/ps":
            return httpx.Response(200, json={"models": [record]})
    engine, calls = mock_search(settings, override)
    with pytest.raises(ProviderUnavailable):
        engine.run(KnowledgeSearchRequest.model_validate(request_body()))
    assert [path for path, _ in calls] == ["/api/tags", "/api/embed", "/api/ps", "/api/generate"]


@pytest.mark.parametrize("response", [
    {"model": MODEL, "embeddings": [[1.0]]},
    {"model": MODEL, "embeddings": [vector(), vector()]},
    {"model": MODEL, "embeddings": [[0.0] * 1024]},
    {"model": "another-model", "embeddings": [vector()]},
])
def test_malformed_query_vector_fails_without_ps_or_fallback(settings, response):
    def override(request, body):
        if request.url.path == "/api/embed":
            return httpx.Response(200, json=response)
    engine, calls = mock_search(settings, override)
    with pytest.raises(ProviderUnavailable):
        engine.run(KnowledgeSearchRequest.model_validate(request_body()))
    assert [path for path, _ in calls] == ["/api/tags", "/api/embed", "/api/generate"]


def test_shared_lock_and_service_auth_protect_endpoint(settings):
    with TestClient(create_app(settings)) as client:
        assert client.app.state.knowledge_search.lock is client.app.state.engine.lock
        assert client.app.state.knowledge_search.lock is client.app.state.case_engine.lock
        assert client.app.state.knowledge_search.lock is client.app.state.uat_engine.lock
    engine, calls = mock_search(settings)
    with TestClient(create_app(settings, knowledge_search=engine)) as client:
        assert client.post("/case/knowledge-search", json=request_body()).status_code == 401
        assert calls == []
        bad = {**request_body(), "limit": 1}
        assert client.post("/case/knowledge-search", json=bad,
                           headers={"X-Service-Key": settings.service_key}).status_code == 422
        assert calls == []
        result = client.post("/case/knowledge-search", json=request_body(),
                             headers={"X-Service-Key": settings.service_key})
        assert result.status_code == 200 and result.json()["processor"] == "GPU"
        assert set(result.json()) == {"model", "digest", "matches", "processor"}


def test_busy_model_prevents_any_provider_request(settings):
    class BusyLock:
        def acquire(self, timeout):
            assert timeout == 1.0
            return False
    engine, calls = mock_search(settings)
    engine.lock = BusyLock()
    with pytest.raises(UatModelBusy):
        engine.run(KnowledgeSearchRequest.model_validate(request_body()))
    assert calls == []


def test_transport_timeout_is_visible_sanitized_and_cleanup_runs(settings):
    def override(request, body):
        if request.url.path == "/api/embed":
            raise httpx.ReadTimeout("private question and provider details", request=request)
    engine, calls = mock_search(settings, override)
    with TestClient(create_app(settings, knowledge_search=engine)) as client:
        result = client.post("/case/knowledge-search", json=request_body(),
                             headers={"X-Service-Key": settings.service_key})
    assert result.status_code == 504
    assert result.json()["code"] == "CASE_KNOWLEDGE_TIMEOUT"
    assert result.json()["requestId"]
    assert "private question" not in result.text
    assert [path for path, _ in calls] == ["/api/tags", "/api/embed", "/api/generate"]


def test_overall_deadline_stops_verification_but_still_allows_bounded_unload(settings):
    now = [0.0]
    def override(request, body):
        if request.url.path == "/api/embed":
            now[0] = settings.case_knowledge_timeout_seconds + 1
    engine, calls = mock_search(settings, override, clock=lambda: now[0])
    with pytest.raises(KnowledgeSearchTimeout):
        engine.run(KnowledgeSearchRequest.model_validate(request_body()))
    assert [path for path, _ in calls] == ["/api/tags", "/api/embed", "/api/generate"]


def test_unacknowledged_unload_does_not_claim_success(settings):
    def override(request, body):
        if request.url.path == "/api/generate":
            return httpx.Response(200, json={"model": MODEL, "done": True, "done_reason": "stop"})
    engine, _ = mock_search(settings, override)
    with pytest.raises(ProviderUnavailable, match="unload"):
        engine.run(KnowledgeSearchRequest.model_validate(request_body()))


@pytest.mark.parametrize("response", [
    httpx.Response(307, headers={"location": "https://unapproved.invalid/api/tags"}),
    httpx.Response(200, content=b"x" * 262145),
    httpx.Response(200, content=b'{"models":[],"models":[]}'),
])
def test_redirect_oversized_or_duplicate_metadata_fails_without_model_request(settings, response):
    engine, calls = mock_search(settings, lambda request, body: response)
    with pytest.raises(ProviderUnavailable):
        engine.run(KnowledgeSearchRequest.model_validate(request_body()))
    assert [path for path, _ in calls] == ["/api/tags"]


@pytest.mark.parametrize("url", ["https://cloud.invalid", "http://127.0.0.1:11435/redirect",
                                  "http://user:secret@127.0.0.1:11435", "http://127.0.0.1:11435?token=x"])
def test_configuration_cannot_redirect_query_to_remote_or_credentialled_target(settings, url):
    engine = CaseKnowledgeSearch(replace(settings, ollama_base_url=url))
    with pytest.raises(ProviderUnavailable, match="loopback"):
        engine._client()


@pytest.mark.parametrize("value", [0, 121, True, float("nan")])
def test_embedding_deadline_configuration_is_bounded(settings, value):
    with pytest.raises(ValueError, match="POI_CASE_KNOWLEDGE_TIMEOUT_SECONDS"):
        replace(settings, case_knowledge_timeout_seconds=value)


def test_query_cache_checks_fresh_model_and_avoids_another_model_swap(settings):
    engine, calls = mock_search(settings)
    body = request_body()
    initial = engine.run(KnowledgeSearchRequest.model_validate(body))
    assert engine.last_search_metrics == {"queryVectorCache": "miss", "queryEmbeddingCalls": 1}
    calls.clear()
    # Both supported digest spellings identify the same model weights.
    body["digest"] = "sha256:" + DIGEST
    cached = engine.run(KnowledgeSearchRequest.model_validate(body))
    assert cached.matches == initial.matches
    assert cached.digest == body["digest"]
    assert calls == [("/api/tags", None)]
    assert engine.last_search_metrics == {"queryVectorCache": "hit", "queryEmbeddingCalls": 0}
    assert body["question"] not in repr(engine._query_vectors)


def test_cached_query_reranks_new_vectors_and_cannot_reuse_another_scope_ids(settings):
    engine, calls = mock_search(settings)
    body = request_body()
    engine.run(KnowledgeSearchRequest.model_validate(body))
    calls.clear()
    # Same query, entirely different authorized scope and vector ordering.
    body["entries"] = [{"id": "OTHER-SCOPE-A", "vector": vector(-1.0)},
                       {"id": "OTHER-SCOPE-B", "vector": vector()}]
    result = engine.run(KnowledgeSearchRequest.model_validate(body))
    assert [(match.id, match.score) for match in result.matches] == [
        ("OTHER-SCOPE-B", 1.0), ("OTHER-SCOPE-A", -1.0)]
    assert calls == [("/api/tags", None)]
    body["entries"][0]["vector"] = vector()
    body["entries"][1]["vector"] = vector(-1.0)
    result = engine.run(KnowledgeSearchRequest.model_validate(body))
    assert result.matches[0].id == "OTHER-SCOPE-A"


def test_changed_query_and_model_fingerprint_require_new_verified_vectors(settings):
    current_digest = [DIGEST]
    def override(request, body):
        if request.url.path in {"/api/tags", "/api/ps"}:
            return httpx.Response(200, json={"models": [{"name": MODEL,
                "digest": current_digest[0], "size_vram": 12345}]})
    engine, calls = mock_search(settings, override)
    body = request_body()
    engine.run(KnowledgeSearchRequest.model_validate(body))
    body["question"] += " "  # Exact text, no lossy whitespace normalization.
    engine.run(KnowledgeSearchRequest.model_validate(body))
    current_digest[0] = body["digest"] = "b" * 64
    engine.run(KnowledgeSearchRequest.model_validate(body))
    assert [path for path, _ in calls].count("/api/embed") == 3


def test_cached_vector_never_bypasses_changed_installed_model(settings):
    changed = [False]
    def override(request, body):
        if changed[0] and request.url.path == "/api/tags":
            return httpx.Response(200, json={"models": [{"name": MODEL, "digest": "b" * 64}]})
    engine, calls = mock_search(settings, override)
    body = KnowledgeSearchRequest.model_validate(request_body())
    engine.run(body)
    changed[0] = True
    calls.clear()
    with pytest.raises(ProviderUnavailable, match="fingerprint"):
        engine.run(body)
    assert calls == [("/api/tags", None)]
    assert engine.last_search_metrics is None


def test_query_cache_expires_without_sliding_ttl(settings):
    now = [0.0]
    engine, calls = mock_search(settings, clock=lambda: now[0])
    body = KnowledgeSearchRequest.model_validate(request_body())
    engine.run(body)
    now[0] = QUERY_CACHE_TTL_SECONDS - 1
    engine.run(body)
    assert engine.last_search_metrics["queryVectorCache"] == "hit"
    now[0] = QUERY_CACHE_TTL_SECONDS
    engine.run(body)
    assert engine.last_search_metrics["queryVectorCache"] == "miss"
    assert [path for path, _ in calls].count("/api/embed") == 2
    assert len(engine._query_vectors) == 1


def test_query_cache_evicts_least_recently_used_at_fixed_bound(settings):
    engine, calls = mock_search(settings)
    body = request_body()
    for index in range(QUERY_CACHE_ENTRIES):
        engine.run(KnowledgeSearchRequest.model_validate({**body, "question": f"Question {index}"}))
    # Preserve Question 0 by using it again; Question 1 becomes oldest.
    engine.run(KnowledgeSearchRequest.model_validate({**body, "question": "Question 0"}))
    engine.run(KnowledgeSearchRequest.model_validate({**body, "question": "Question new"}))
    assert len(engine._query_vectors) == QUERY_CACHE_ENTRIES
    calls.clear()
    engine.run(KnowledgeSearchRequest.model_validate({**body, "question": "Question 0"}))
    assert calls == [("/api/tags", None)]
    engine.run(KnowledgeSearchRequest.model_validate({**body, "question": "Question 1"}))
    assert [path for path, _ in calls].count("/api/embed") == 1
    assert len(engine._query_vectors) == QUERY_CACHE_ENTRIES


@pytest.mark.parametrize("failure_path", ["/api/ps", "/api/generate"])
def test_failed_gpu_verification_or_unload_never_populates_query_cache(settings, failure_path):
    failing = [True]
    def override(request, body):
        if failing[0] and request.url.path == failure_path:
            if failure_path == "/api/ps":
                return httpx.Response(200, json={"models": [{"name": MODEL, "digest": DIGEST, "size_vram": 0}]})
            return httpx.Response(200, json={"model": MODEL, "done": True, "done_reason": "stop"})
    engine, calls = mock_search(settings, override)
    body = KnowledgeSearchRequest.model_validate(request_body())
    with pytest.raises(ProviderUnavailable):
        engine.run(body)
    assert not engine._query_vectors
    failing[0] = False
    engine.run(body)
    assert [path for path, _ in calls].count("/api/embed") == 2
    assert engine.last_search_metrics["queryVectorCache"] == "miss"


def test_more_than_one_hundred_entries_rank_without_changing_query_recipe(settings):
    engine, calls = mock_search(settings)
    body = request_body()
    body["entries"] = [{"id": f"GUIDE-{i:03}", "vector": vector()} for i in range(125)]
    result = engine.run(KnowledgeSearchRequest.model_validate(body))
    assert [match.id for match in result.matches] == ["GUIDE-000", "GUIDE-001", "GUIDE-002"]
    assert sum(path == "/api/embed" for path, _ in calls) == 1
    assert result.processor == "GPU"
