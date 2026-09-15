"""Query embeddings over an authorized, caller-supplied reference index.

Java owns index eligibility and freezes the selected original documents. This
service receives no source prose, changes no model weights, and never pulls a
model or falls back to CPU/lexical retrieval. Answer generation is unchanged.
"""
import ipaddress
import json
import math
from collections import OrderedDict
from hashlib import sha256
from threading import RLock
from time import monotonic
from typing import Annotated, Literal
from urllib.parse import urlsplit

import httpx
from pydantic import Field, StringConstraints, field_validator, model_validator

from .config import Settings
from .errors import ProviderUnavailable, UatModelBusy
from .uat_answer import StrictModel


MODEL = "qwen3-embedding:0.6b"
DIMENSIONS = 1024
# Changing query formatting or embedding options requires a new recipe. Cache
# only verified query vectors, never document IDs or authorized search results.
QUERY_RECIPE = "qwen3-raw-query-v1"
QUERY_CACHE_ENTRIES = 128
QUERY_CACHE_TTL_SECONDS = 1800.0
Digest = Annotated[str, StringConstraints(pattern=r"^(sha256:)?[a-f0-9]{64}$")]
Vector = Annotated[list[Annotated[float, Field(ge=-1, le=1, allow_inf_nan=False)]],
                   Field(min_length=DIMENSIONS, max_length=DIMENSIONS)]


class KnowledgeSearchTimeout(ProviderUnavailable):
    """The embedding/verification deadline elapsed; no matches are returned."""


class KnowledgeEntry(StrictModel):
    id: Annotated[str, StringConstraints(min_length=1, max_length=200, pattern=r"\S")]
    vector: Vector

    @field_validator("vector")
    @classmethod
    def nonzero_vector(cls, value):
        if math.fsum(number * number for number in value) == 0:
            raise ValueError("Embedding vectors must have nonzero length")
        return value


class KnowledgeSearchRequest(StrictModel):
    question: Annotated[str, StringConstraints(min_length=1, max_length=2000, pattern=r"\S")]
    model: Literal["qwen3-embedding:0.6b"]
    digest: Digest
    entries: Annotated[list[KnowledgeEntry], Field(min_length=1, max_length=100)]
    limit: Literal[3]

    @model_validator(mode="after")
    def unique_entries(self):
        if len({entry.id for entry in self.entries}) != len(self.entries):
            raise ValueError("Knowledge entry IDs must be unique")
        return self


class KnowledgeMatch(StrictModel):
    id: str
    score: Annotated[float, Field(ge=-1, le=1, allow_inf_nan=False)]


class KnowledgeSearchResponse(StrictModel):
    model: Literal["qwen3-embedding:0.6b"]
    digest: Digest
    matches: list[KnowledgeMatch]
    processor: Literal["GPU"]


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate response key")
        result[key] = value
    return result


class CaseKnowledgeSearch:
    def __init__(self, settings: Settings, *, lock=None, client_factory=None, clock=monotonic):
        self.settings = settings
        self.lock = lock if lock is not None else RLock()
        self.clock = clock
        self.client_factory = client_factory or self._client
        self._query_vectors = OrderedDict()
        self.last_search_metrics = None

    def _client(self):
        # Separate trust boundary from generation: a configured local address
        # only, no proxy inheritance, redirects, remote model tags or user URL.
        url = urlsplit(self.settings.ollama_base_url)
        try:
            loopback = url.hostname == "localhost" or ipaddress.ip_address(url.hostname or "").is_loopback
        except ValueError:
            loopback = False
        if (url.scheme != "http" or not loopback or url.username or url.password
                or url.path not in {"", "/"} or url.query or url.fragment):
            raise ProviderUnavailable("Case knowledge search requires a configured loopback Ollama address.")
        return httpx.Client(base_url=self.settings.ollama_base_url, trust_env=False,
                            follow_redirects=False)

    def _request(self, client, method, path, deadline, *, body=None, max_bytes=262144):
        remaining = deadline - self.clock()
        if remaining <= 0:
            raise KnowledgeSearchTimeout("Case knowledge search exceeded its bounded local embedding deadline.")
        timeout = httpx.Timeout(remaining, connect=min(5, remaining), write=min(5, remaining), pool=min(1, remaining))
        try:
            with client.stream(method, path, json=body, timeout=timeout) as response:
                response.raise_for_status()
                content = bytearray()
                for chunk in response.iter_bytes():
                    if self.clock() >= deadline:
                        raise KnowledgeSearchTimeout("Case knowledge search exceeded its bounded local embedding deadline.")
                    content.extend(chunk)
                    if len(content) > max_bytes:
                        raise ProviderUnavailable("Local embedding metadata exceeded the response size limit.")
            result = json.loads(content, object_pairs_hook=_unique_object)
            if not isinstance(result, dict):
                raise ValueError("Expected response object")
            return result
        except httpx.TimeoutException as exc:
            raise KnowledgeSearchTimeout("Case knowledge search timed out waiting for local Ollama.") from exc
        except (httpx.HTTPError, ValueError, UnicodeError) as exc:
            # Never include provider body, request text, vectors or URL secrets.
            raise ProviderUnavailable("Local embedding search could not verify its Ollama response; no fallback was used.") from exc

    @staticmethod
    def _model_record(response, digest, *, gpu=False):
        models = response.get("models")
        if not isinstance(models, list):
            raise ProviderUnavailable("Local embedding model metadata is malformed.")
        matches = [item for item in models if isinstance(item, dict)
                   and item.get("name", item.get("model")) == MODEL]
        installed = matches[0].get("digest") if len(matches) == 1 else None
        if not isinstance(installed, str) or installed.removeprefix("sha256:") != digest.removeprefix("sha256:"):
            raise ProviderUnavailable("The installed embedding model fingerprint differs from the supplied index, or the model is unavailable. Rebuild the index with the configured model.")
        if gpu:
            allocated = matches[0].get("size_vram")
            if isinstance(allocated, bool) or not isinstance(allocated, int) or allocated <= 0:
                raise ProviderUnavailable("GPU allocation was not verified for the query embedding; no CPU or lexical fallback was used.")

    @staticmethod
    def _query_key(request):
        return (QUERY_RECIPE, MODEL, request.digest.removeprefix("sha256:"), DIMENSIONS,
                sha256(request.question.encode("utf-8")).hexdigest())

    def _cached_query(self, key):
        cached = self._query_vectors.get(key)
        if cached is None:
            return None
        generated_at, vector = cached
        if self.clock() - generated_at >= QUERY_CACHE_TTL_SECONDS:
            del self._query_vectors[key]
            return None
        self._query_vectors.move_to_end(key)
        return vector

    def _remember_query(self, key, query):
        now = self.clock()
        for expired in [cached_key for cached_key, (generated_at, _) in self._query_vectors.items()
                        if now - generated_at >= QUERY_CACHE_TTL_SECONDS]:
            del self._query_vectors[expired]
        self._query_vectors[key] = (now, tuple(query))
        self._query_vectors.move_to_end(key)
        while len(self._query_vectors) > QUERY_CACHE_ENTRIES:
            self._query_vectors.popitem(last=False)

    def _embed_query(self, client, request, deadline):
        attempted = False
        error = None
        try:
            attempted = True
            response = self._request(client, "POST", "/api/embed", deadline, body={
                "model": MODEL, "input": request.question, "truncate": False,
                "dimensions": DIMENSIONS, "keep_alive": "30s", "options": {"num_gpu": 999},
            })
            vectors = response.get("embeddings")
            if response.get("model") != MODEL or not isinstance(vectors, list) or len(vectors) != 1:
                raise ProviderUnavailable("Local Ollama returned an unexpected query embedding model or vector count.")
            try:
                query = KnowledgeEntry(id="query", vector=vectors[0]).vector
            except ValueError as exc:
                raise ProviderUnavailable("Local Ollama returned an invalid 1024-dimensional query embedding.") from exc
            self._model_record(self._request(client, "GET", "/api/ps", deadline), request.digest, gpu=True)
            return query
        except Exception as exc:
            error = exc
            raise
        finally:
            if attempted:
                try:
                    # Pinned Ollama 0.34.0 handles empty-prompt/keep_alive=0
                    # before completion-capability checks: this unloads an
                    # embedding model without generating text or a vector.
                    unloaded = self._request(client, "POST", "/api/generate", self.clock() + 5, body={
                        "model": MODEL, "prompt": "", "keep_alive": 0, "stream": False,
                    })
                    if unloaded.get("model") != MODEL or unloaded.get("done") is not True or unloaded.get("done_reason") != "unload":
                        raise ProviderUnavailable("Embedding model unload was not acknowledged; retry after checking the local model service.")
                except Exception:
                    if error is None:
                        raise

    def _embed_and_rank(self, client, request, deadline):
        # Even a cache hit checks the current installed fingerprint. It can
        # neither reuse a vector from changed weights nor bypass availability.
        self._model_record(self._request(client, "GET", "/api/tags", deadline), request.digest)
        key = self._query_key(request)
        query = self._cached_query(key)
        cached = query is not None
        if query is None:
            query = self._embed_query(client, request, deadline)
            # _embed_query returns only after both GPU proof and acknowledged
            # unloading. Failed attempts never populate the cache.
            self._remember_query(key, query)
        query_norm = math.sqrt(math.fsum(value * value for value in query))
        scores = []
        for entry in request.entries:
            norm = math.sqrt(math.fsum(value * value for value in entry.vector))
            score = math.fsum(a * b for a, b in zip(query, entry.vector)) / (query_norm * norm)
            scores.append(KnowledgeMatch(id=entry.id, score=max(-1.0, min(1.0, score))))
        scores.sort(key=lambda item: (-item.score, item.id))
        self.last_search_metrics = {"queryVectorCache": "hit" if cached else "miss",
                                    "queryEmbeddingCalls": 0 if cached else 1}
        # GPU identifies the vector's verified origin. On a cache hit no new
        # inference runs; ranks are recomputed from this caller's supplied index.
        return KnowledgeSearchResponse(model=MODEL, digest=request.digest,
                                       matches=scores[:request.limit], processor="GPU")

    def run(self, request: KnowledgeSearchRequest) -> KnowledgeSearchResponse:
        if not self.lock.acquire(timeout=1.0):
            raise UatModelBusy("The local model is busy. Wait for the active request before searching case knowledge. No embedding call was started.")
        try:
            self.last_search_metrics = None
            deadline = self.clock() + self.settings.case_knowledge_timeout_seconds
            with self.client_factory() as client:
                return self._embed_and_rank(client, request, deadline)
        finally:
            self.lock.release()
