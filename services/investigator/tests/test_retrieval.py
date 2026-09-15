from dataclasses import replace
from datetime import date
import json

import pytest

from investigator.errors import ProviderUnavailable
from investigator.retrieval import KnowledgeStore


def test_permissions_and_version_dates_are_filtered_before_retrieval(settings):
    docs = json.loads(settings.knowledge_path.read_text())
    template = docs[0]
    docs = [dict(template, id="VERSIONED", version=1, effectiveTo="2026-06-01"), dict(template, id="VERSIONED", version=2, effectiveFrom="2026-06-01"), dict(template, id="SECRET", tenantId="silverline", content="timeout transport succeeded capture "*30)]
    settings.knowledge_path.write_text(json.dumps(docs))
    store = KnowledgeStore(settings)
    current = store.retrieve("timeout capture", "northstar", date(2026, 9, 11))
    assert [c["id"] for c in current] == ["VERSIONED:v2"]
    historical = store.retrieve("timeout", "northstar", date(2026, 5, 31))
    assert [c["id"] for c in historical] == ["VERSIONED:v1"]
    assert "SECRET" not in [doc["id"] for doc in store.all_for_tenant("northstar")]


def test_real_embedding_path_is_explicit_and_filtered(settings):
    class RecordingEmbeddings:
        def __init__(self):
            self.seen = []
        def embed_documents(self, texts):
            self.seen.extend(texts)
            return [[float("timeout" in text.lower()), 1.0] for text in texts]
        def embed_query(self, query):
            return [1.0, 1.0]
    docs = json.loads(settings.knowledge_path.read_text())
    docs.append(dict(docs[0], id="PRIVATE", tenantId="silverline", content="SECRET-TENANT-CONTENT"))
    settings.knowledge_path.write_text(json.dumps(docs))
    embedder = RecordingEmbeddings()
    store = KnowledgeStore(replace(settings, retrieval_mode="hybrid"), embeddings=embedder)
    result = store.retrieve("timeout", "northstar", date(2026, 9, 11))
    assert result and embedder.seen
    assert all("SECRET-TENANT-CONTENT" not in text for text in embedder.seen)
    count = len(embedder.seen)
    store.retrieve("timeout", "northstar", date(2026, 9, 11))
    assert len(embedder.seen) == count


def test_embedding_failure_does_not_silently_use_lexical(settings):
    class BrokenEmbeddings:
        def embed_documents(self, texts):
            raise RuntimeError("Provider is down")
    store = KnowledgeStore(replace(settings, retrieval_mode="hybrid"), embeddings=BrokenEmbeddings())
    with pytest.raises(ProviderUnavailable, match="did not fall back"):
        store.retrieve("timeout", "northstar", date(2026, 9, 11))
