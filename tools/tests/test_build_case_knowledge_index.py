"""Original small curated-library fixtures; all provider calls are mocked."""
from copy import deepcopy
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path

import httpx
import pytest


SPEC = importlib.util.spec_from_file_location("build_case_knowledge_index",
    Path(__file__).resolve().parents[1] / "build-case-knowledge-index.py")
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)
DIGEST = "a" * 64


def vector(first=1.0):
    return [first] + [0.0] * 1023


def library(tenant="northstar", docs=None):
    docs = docs or [{"id": "GUIDE-TEST", "kind": "knowledge", "title": "Original test guidance",
                     "content": "Keep the exact source field value.", "source": {"file": "original-test", "locator": "Rule 1"}}]
    return {"schemaVersion": "case-knowledge-library-v1", "tenantId": tenant,
            "evidenceSchema": builder.EVIDENCE_SCHEMA, "version": builder.fingerprint(docs),
            "embedding": {"enabled": False, "status": "DISABLED", "model": None, "digest": None,
                "dimensions": None, "indexedAt": None, "indexedDocuments": 0, "totalDocuments": len(docs)},
            "items": [{**doc, "version": builder.fingerprint(doc), "category": "GUIDANCE", "selection": "ALWAYS",
                       "embeddingStatus": "DISABLED"} for doc in docs], "warnings": []}


class FakeProvider:
    def __init__(self, digest=DIGEST):
        self.digest = digest
        self.text_batches = []
        self.batches = []
        self.unloaded = False

    def installed_digest(self):
        return self.digest

    def embed(self, texts, digest):
        assert digest == self.digest
        self.text_batches.append(texts)
        self.batches.append({"documents": len(texts), "gpuBytes": 12345})
        return [vector() for _ in texts]

    def unload(self):
        self.unloaded = True


def build(libraries=None, previous=None, provider=None):
    return builder.build(libraries or [library()], previous, provider or FakeProvider(),
                         now=datetime(2026, 9, 15, tzinfo=timezone.utc))


def test_build_canonical_inputs_and_versioned_output_with_gpu_receipt():
    provider = FakeProvider()
    source = library()
    original = deepcopy(source)
    serialized, receipt_data, receipt = build([source], provider=provider)
    index = json.loads(serialized)
    doc = {key: source["items"][0][key] for key in builder.DOC_KEYS}
    assert provider.text_batches == [[builder.canonical(doc)]]
    assert provider.unloaded
    assert index["schemaVersion"] == "case-knowledge-index-v1"
    assert index["tenants"][0]["documents"] == [{"id": doc["id"], "documentHash": source["items"][0]["version"], "vector": vector()}]
    assert builder.existing_index(index)[0] == DIGEST
    assert receipt["embeddedDocuments"] == 1 and receipt["embeddingCalls"] == 1
    assert receipt["processor"] == "GPU" and receipt["gpuVerifiedThisBuild"] is True
    assert json.loads(receipt_data) == receipt
    assert source == original


def test_reuse_all_current_vectors_and_deduplicate_identical_docs_across_tenants():
    initial = json.loads(build()[0])
    provider = FakeProvider()
    result, _, receipt = build([library(), library("silverline")], initial, provider)
    assert provider.text_batches == []
    assert receipt["documents"] == 2 and receipt["uniqueDocuments"] == 1
    assert receipt["reusedDocuments"] == 2 and receipt["embeddedDocuments"] == 0
    assert receipt["processor"] == "REUSED_INDEX" and not receipt["gpuVerifiedThisBuild"]
    assert [tenant["tenantId"] for tenant in json.loads(result)["tenants"]] == ["northstar", "silverline"]


def test_changed_source_locator_or_weights_reembeds_instead_of_reusing_stale_vector():
    initial = json.loads(build()[0])
    doc = {key: library()["items"][0][key] for key in builder.DOC_KEYS}
    doc["source"]["locator"] = "Rule 1 revised"
    provider = FakeProvider()
    _, _, receipt = build([library(docs=[doc])], initial, provider)
    assert receipt["embeddedDocuments"] == 1 and receipt["reusedDocuments"] == 0
    provider = FakeProvider("b" * 64)
    result, _, receipt = build(previous=initial, provider=provider)
    assert json.loads(result)["digest"] == "b" * 64 and receipt["embeddedDocuments"] == 1


def test_null_optional_source_fields_preserve_exact_canonical_fingerprint():
    doc = {key: library()["items"][0][key] for key in builder.DOC_KEYS}
    provider = FakeProvider()
    initial_hash = builder.fingerprint(doc)
    doc["source"].update(sheet=None, range=None, locator=None)
    result, _, _ = build([library(docs=[doc])], provider=provider)
    saved = json.loads(result)["tenants"][0]["documents"][0]
    assert saved["documentHash"] == builder.fingerprint(doc) != initial_hash
    assert json.loads(provider.text_batches[0][0])["source"] == doc["source"]
    assert '"sheet":null' in provider.text_batches[0][0]


def test_incremental_build_embeds_only_changed_documents_in_bounded_batches():
    docs = [{"id": f"GUIDE-{index}", "kind": "knowledge", "title": "Original rule", "content": f"Rule {index}",
             "source": {"file": "original-test"}} for index in range(19)]
    provider = FakeProvider()
    initial = json.loads(build([library(docs=docs)], provider=provider)[0])
    assert [len(batch) for batch in provider.text_batches] == [8, 8, 3]
    docs[5]["content"] = "A reviewed replacement rule"
    provider = FakeProvider()
    _, _, receipt = build([library(docs=docs)], initial, provider)
    assert receipt["embeddedDocuments"] == 1 and receipt["reusedDocuments"] == 18
    assert len(provider.text_batches) == 1
    assert json.loads(provider.text_batches[0][0])["id"] == "GUIDE-5"


@pytest.mark.parametrize("change", [
    lambda value: value.update(version="b" * 64),
    lambda value: value["items"][0].update(content="Changed without its fingerprint"),
    lambda value: value["items"][0].update(kind="evidence"),
    lambda value: value["items"][0].update(approval="unreviewed"),
    lambda value: value["items"].append(deepcopy(value["items"][0])),
    lambda value: value.update(tenantId="../other"),
    lambda value: value.update(evidenceSchema="other-schema"),
    lambda value: value.update(items=[]),
])
def test_untrusted_or_stale_source_contract_rejected_before_embedding(change):
    source = library()
    change(source)
    provider = FakeProvider()
    with pytest.raises(builder.IndexError):
        build([source], provider=provider)
    assert provider.text_batches == []


def test_repeated_tenant_exports_are_not_combined_ambiguously():
    with pytest.raises(builder.IndexError, match="tenant"):
        build([library(), library()])


@pytest.mark.parametrize("change", [
    lambda value: value.update(schemaVersion="fcr-status-knowledge-v1"),
    lambda value: value.update(dimensions=512),
    lambda value: value.update(indexedAt="2026-09-15T12:00:00"),
    lambda value: value["tenants"][0]["documents"][0].update(vector=[0.0] * 1024),
    lambda value: value["tenants"][0]["documents"][0]["vector"].__setitem__(0, True),
    lambda value: value["tenants"][0]["documents"][0]["vector"].__setitem__(0, float("nan")),
])
def test_invalid_or_different_recipe_existing_index_is_not_reused(change):
    previous = json.loads(build()[0])
    change(previous)
    with pytest.raises(builder.IndexError):
        build(previous=previous)


def test_conflicting_cross_tenant_vectors_for_identical_hash_fail():
    previous = json.loads(build()[0])
    other = deepcopy(previous["tenants"][0])
    other["tenantId"] = "silverline"
    other["documents"][0]["vector"] = vector(-1.0)
    previous["tenants"].append(other)
    with pytest.raises(builder.IndexError, match="inconsistent"):
        build(previous=previous)


def test_provider_failure_unloads_and_no_partial_index_is_returned():
    class FailingProvider(FakeProvider):
        def embed(self, texts, digest):
            raise builder.IndexError("Synthetic provider failure")
    provider = FailingProvider()
    with pytest.raises(builder.IndexError, match="Synthetic"):
        build(provider=provider)
    assert provider.unloaded


def test_unacknowledged_unload_prevents_candidate_result():
    class FailingProvider(FakeProvider):
        def unload(self):
            raise builder.IndexError("Synthetic unload failure")
    with pytest.raises(builder.IndexError, match="unload"):
        build(provider=FailingProvider())


def test_completed_index_byte_limit_prevents_oversized_candidate():
    sources = []
    for tenant_index in range(10):
        docs = [{"id": f"GUIDE-{index}", "kind": "knowledge", "title": "Original rule",
                 "content": f"Tenant {tenant_index} rule {index}", "source": {"file": "original-test"}}
                for index in range(100)]
        sources.append(library(f"tenant-{tenant_index}", docs))
    provider = FakeProvider()
    with pytest.raises(builder.IndexError, match="4 MiB"):
        build(sources, provider=provider)
    assert provider.unloaded


def mock_local(override=None):
    calls = []
    def respond(request):
        body = json.loads(request.content) if request.content else None
        calls.append((request.url.path, body))
        if override:
            result = override(request, body)
            if result is not None:
                return result
        if request.url.path in {"/api/tags", "/api/ps"}:
            return httpx.Response(200, json={"models": [{"name": builder.MODEL, "digest": "sha256:" + DIGEST, "size_vram": 12345}]})
        if request.url.path == "/api/embed":
            return httpx.Response(200, json={"model": builder.MODEL, "embeddings": [vector() for _ in body["input"]]})
        if request.url.path == "/api/generate":
            return httpx.Response(200, json={"model": builder.MODEL, "done": True, "done_reason": "unload"})
        pytest.fail("Unexpected provider path")
    return builder.LocalEmbedder("http://127.0.0.1:11435", client=httpx.Client(
        base_url="http://127.0.0.1:11435", transport=httpx.MockTransport(respond))), calls


def test_local_gpu_model_contract_and_cleanup_without_real_network():
    provider, calls = mock_local()
    _, _, receipt = build(provider=provider)
    assert [path for path, _ in calls] == ["/api/tags", "/api/embed", "/api/ps", "/api/tags", "/api/generate"]
    embedding = calls[1][1]
    assert embedding["options"] == {"num_gpu": 999} and embedding["truncate"] is False
    assert embedding["dimensions"] == 1024 and embedding["keep_alive"] == "30s"
    assert receipt["batches"][0]["gpuBytes"] == 12345
    assert calls[-1][1] == {"model": builder.MODEL, "prompt": "", "keep_alive": 0, "stream": False}


@pytest.mark.parametrize("record", [
    {"name": builder.MODEL, "digest": DIGEST, "size_vram": 0},
    {"name": builder.MODEL, "digest": DIGEST, "size_vram": True},
    {"name": builder.MODEL, "digest": "b" * 64, "size_vram": 12345},
])
def test_cpu_or_wrong_model_vectors_never_produce_index(record):
    def override(request, body):
        if request.url.path == "/api/ps":
            return httpx.Response(200, json={"models": [record]})
    provider, calls = mock_local(override)
    with pytest.raises(builder.IndexError, match="GPU"):
        build(provider=provider)
    assert calls[-1][0] == "/api/generate"


@pytest.mark.parametrize("url", ["https://remote.invalid", "http://127.0.0.1:11435/other",
    "http://user:secret@localhost:11435", "http://127.0.0.1:11435?token=secret"])
def test_remote_or_credentialed_provider_is_rejected(url):
    with pytest.raises(builder.IndexError, match="loopback"):
        builder.LocalEmbedder(url)


@pytest.mark.parametrize("response", [
    httpx.Response(302, headers={"location": "https://remote.invalid"}),
    httpx.Response(200, content=b'{"models":[],"models":[]}'),
    httpx.Response(200, content=b"x" * (builder.MAX_BYTES + 1)),
])
def test_provider_redirect_duplicate_and_oversized_metadata_fail(response):
    provider, calls = mock_local(lambda request, body: response)
    with pytest.raises(builder.IndexError):
        build(provider=provider)
    assert len(calls) == 1


def test_source_read_duplicate_keys_and_size_bounds(tmp_path):
    source = tmp_path / "source.json"
    source.write_text('{"items":[],"items":[]}', encoding="utf-8")
    with pytest.raises(builder.IndexError, match="Duplicate"):
        builder.read_object(source)
    source.write_bytes(b"x" * (builder.MAX_BYTES + 1))
    with pytest.raises(builder.IndexError, match="4 MiB"):
        builder.read_object(source)


def test_output_refuses_source_existing_targets_and_receipt_collisions(tmp_path):
    source = tmp_path / "source.json"
    source.write_text("original", encoding="utf-8")
    with pytest.raises(builder.IndexError, match="fresh"):
        builder.output_paths(source, [source])
    output = tmp_path / "candidate.json"
    receipt = tmp_path / "candidate.json.receipt.json"
    receipt.write_text("original", encoding="utf-8")
    with pytest.raises(builder.IndexError, match="fresh"):
        builder.output_paths(output, [source])
    assert source.read_text() == "original" and receipt.read_text() == "original"


def test_atomic_publication_never_overwrites_a_concurrently_created_file(tmp_path):
    output = tmp_path / "candidate.json"
    builder.atomic_create(output, b"first")
    with pytest.raises(builder.IndexError, match="immutable"):
        builder.atomic_create(output, b"replacement")
    assert output.read_bytes() == b"first"
    assert list(tmp_path.iterdir()) == [output]
