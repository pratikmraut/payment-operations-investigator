"""Date/tenant filtering precedes lexical and true optional embedding retrieval."""
from collections import Counter
from datetime import date
import hashlib
import json
import math
from pathlib import Path
import re
from threading import RLock

from langchain_core.documents import Document
from langchain_ollama import OllamaEmbeddings

from .config import Settings
from .errors import ProviderUnavailable
from .obpm_models import OBPM_SCOPE
from .vector_store import PgVectorStore, document_key, vector_text


def tokens(text: str) -> list[str]:
    return re.findall(r"[a-z0-9]+", text.lower())


def cosine(left: list[float], right: list[float]) -> float:
    if len(left) != len(right):
        raise ValueError("Embedding dimensions changed; use a consistent model")
    denominator = math.sqrt(sum(x*x for x in left) * sum(x*x for x in right))
    return sum(x*y for x, y in zip(left, right)) / denominator if denominator else 0.0


class KnowledgeStore:
    def __init__(self, settings: Settings, embeddings=None):
        self.settings = settings
        self._lock = RLock()
        self._vectors: dict[str, list[float]] = {}
        self.embeddings = embeddings
        self.vector_store = PgVectorStore(settings.vector_db_url, settings.vector_namespace) if settings.vector_db_url else None

    def all_for_tenant(self, tenant_id: str) -> list[dict]:
        data = json.loads(self.settings.knowledge_path.read_text(encoding="utf-8-sig"))
        if not isinstance(data, list):
            raise ValueError("Knowledge source must be an array of runbooks")
        sidecar = self.settings.knowledge_path.parent / "obpm-runbooks.json"
        if sidecar != self.settings.knowledge_path and sidecar.exists():
            extra = json.loads(sidecar.read_text(encoding="utf-8-sig"))
            if not isinstance(extra, list):
                raise ValueError("OBPM knowledge sidecar must be an array of runbooks")
            if any(not isinstance(doc, dict) or any(not doc.get(key) for key in OBPM_SCOPE) for doc in extra):
                raise ValueError("Every OBPM sidecar policy requires explicit domain, rail, direction and release family")
            data.extend(extra)
        return [doc for doc in data if doc.get("tenantId") in {"*", tenant_id}]

    def documents(self, tenant_id: str, policy_date: date, *, scope: dict | None = None) -> list[Document]:
        if scope is not None and scope != OBPM_SCOPE:
            raise ValueError("Unsupported or incomplete OBPM policy scope")
        docs = []
        for doc in self.all_for_tenant(tenant_id):
            if scope is None:
                if any(doc.get(key) is not None for key in OBPM_SCOPE):
                    continue
            elif any(doc.get(key) != value for key, value in scope.items()):
                continue
            start = date.fromisoformat(doc["effectiveFrom"])
            end = date.fromisoformat(doc["effectiveTo"]) if doc.get("effectiveTo") else None
            # EffectiveTo is exclusive: adjacent versions cannot overlap on a boundary.
            if start <= policy_date and (end is None or policy_date < end):
                docs.append(Document(page_content=doc["content"], metadata=doc))
        return docs

    def retrieve(self, query: str, tenant_id: str, policy_date: date, limit: int = 4, *, scope: dict | None = None):
        documents = self.documents(tenant_id, policy_date, scope=scope)
        if not documents:
            return []
        query_terms = set(tokens(query))
        # Transparent lexical overlap + inverse document frequency; not claimed BM25.
        term_sets = [set(tokens(d.metadata["title"] + " " + d.page_content + " " + " ".join(d.metadata.get("keywords", [])))) for d in documents]
        frequencies = Counter(term for terms in term_sets for term in terms)
        scores = [sum(math.log(1 + len(documents) / frequencies[t]) for t in query_terms & terms) / math.sqrt(max(1, len(terms))) for terms in term_sets]
        lexical = sorted(range(len(documents)), key=lambda i: (-scores[i], documents[i].metadata["id"]))
        mode = self.settings.retrieval_mode
        if mode == "hybrid":
            try:
                if self.embeddings is None:
                    self.embeddings = OllamaEmbeddings(model=self.settings.ollama_embed_model, base_url=self.settings.ollama_base_url, client_kwargs={"timeout": self.settings.model_timeout_seconds})
                with self._lock:
                    keys = [document_key(d) for d in documents]
                    model = self.settings.ollama_embed_model
                    if self.vector_store:
                        self._vectors.update(self.vector_store.cached_vectors(documents, model))
                    missing = [(key, doc) for key, doc in zip(keys, documents) if key not in self._vectors]
                    if missing:
                        vectors = self.embeddings.embed_documents([doc.page_content for _, doc in missing])
                        if len(vectors) != len(missing):
                            raise ValueError("Embedding provider returned an incomplete document batch")
                        for vector in vectors:
                            vector_text(vector)
                        self._vectors.update({key: vec for (key, _), vec in zip(missing, vectors)})
                        if self.vector_store:
                            for (_, doc), vector in zip(missing, vectors):
                                self.vector_store.upsert(doc, vector, model)
                    query_vector = self.embeddings.embed_query(query)
                    vector_text(query_vector)
                    if self.vector_store:
                        found = self.vector_store.search(query_vector, model=model, tenant_id=tenant_id, policy_date=policy_date, active_documents=documents, limit=min(len(documents), max(20, limit*4)))
                        lookup = {key: i for i, key in enumerate(keys)}
                        semantic = [lookup[key] for key, _ in found]
                    else:
                        semantic_scores = [cosine(query_vector, self._vectors[key]) for key in keys]
                        semantic = sorted(range(len(documents)), key=lambda i: (-semantic_scores[i], documents[i].metadata["id"]))
                # Reciprocal rank fusion; lexical results with zero overlap add no rank.
                fused = {i: 1/(60+rank) for rank, i in enumerate(semantic, 1)}
                for rank, i in enumerate((i for i in lexical if scores[i] > 0), 1):
                    fused[i] = fused.get(i, 0) + 1/(60+rank)
                ranked = sorted(fused, key=lambda i: (-fused[i], documents[i].metadata["id"]))
                scores = [fused.get(i, 0) for i in range(len(documents))]
            except Exception as exc:
                raise ProviderUnavailable("Hybrid embedding/vector retrieval failed; hybrid mode did not fall back to lexical.") from exc
        else:
            ranked = [i for i in lexical if scores[i] > 0]
        result = []
        for i in ranked[:limit]:
            doc = documents[i]
            meta = doc.metadata
            result.append({"id": f"{meta['id']}:v{meta['version']}", "documentId": meta["id"], "version": meta["version"], "title": meta["title"], "excerpt": doc.page_content[:2500], "source": meta.get("source", "Original simulated operating policy"), "score": round(scores[i], 6)})
        return result
