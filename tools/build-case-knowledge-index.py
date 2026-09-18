#!/usr/bin/env python3
"""Build an immutable local GPU index from authorized case-knowledge exports.

Run with the investigator virtual environment (requires httpx). Export the
current GET /api/case-knowledge inventory separately for each authorized tenant.
Only changed canonical documents are embedded; existing compatible vectors are
reused. This tool never pulls models, writes source files or calls a bank API.
Output paths must be new. Activate the completed candidate index separately.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import sys
import tempfile
import time
from urllib.parse import urlsplit

import httpx


MODEL = "qwen3-embedding:0.6b"
DIMENSIONS = 1024
SCHEMA = "case-knowledge-index-v1"
EVIDENCE_SCHEMA = "fcr-case-evidence-v1"
MAX_BYTES = 64 * 1024 * 1024
MAX_DOCUMENTS = 1000
MAX_TOTAL_DOCUMENTS = 3000
MAX_SOURCE_CHARACTERS = 4 * 1024 * 1024
BATCH_SIZE = 8
DOC_KEYS = {"id", "kind", "title", "content", "source"}
HASH = re.compile(r"[a-f0-9]{64}\Z")
IDENTIFIER = re.compile(r"[A-Za-z0-9_-]{1,200}\Z")
TENANT = re.compile(r"[A-Za-z0-9_-]{1,100}\Z")


class IndexError(ValueError):
    """A bounded, sanitized builder error; source/provider text is not echoed."""


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False,
                      separators=(",", ":"), allow_nan=False)


def fingerprint(value):
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise IndexError("Duplicate JSON keys are not permitted.")
        result[key] = value
    return result


def _invalid_constant(value):
    raise IndexError("Non-finite JSON values are not permitted.")


def read_object(path):
    try:
        with path.open("rb") as source:
            content = source.read(MAX_BYTES + 1)
        if len(content) > MAX_BYTES:
            raise IndexError("An input exceeds the 64 MiB source bound.")
        value = json.loads(content.decode("utf-8-sig"), object_pairs_hook=_unique,
                           parse_constant=_invalid_constant)
    except (OSError, UnicodeError, json.JSONDecodeError, RecursionError) as exc:
        raise IndexError("An input could not be read as bounded UTF-8 JSON.") from exc
    if not isinstance(value, dict):
        raise IndexError("Each input must be a JSON object.")
    return value


def _keys(value, names):
    return isinstance(value, dict) and set(value) == names


def _text(value, maximum):
    return isinstance(value, str) and bool(value.strip()) and len(value) <= maximum


def _hash(value):
    return isinstance(value, str) and HASH.fullmatch(value) is not None


def _time(value):
    try:
        return isinstance(value, str) and datetime.fromisoformat(value.replace("Z", "+00:00")).utcoffset() is not None
    except (ValueError, OverflowError):
        return False


def document(item):
    if not _keys(item, DOC_KEYS):
        raise IndexError("A standard knowledge document has invalid keys.")
    if (not isinstance(item["id"], str) or not IDENTIFIER.fullmatch(item["id"])
            or item["kind"] != "knowledge" or not _text(item["title"], 500)
            or not _text(item["content"], 50000)):
        raise IndexError("A knowledge document has an invalid identity, kind or text bound.")
    source = item["source"]
    if (not isinstance(source, dict) or not set(source) <= {"file", "sheet", "range", "locator"}
            or not _text(source.get("file"), 1000)):
        raise IndexError("A knowledge document must have its original source locator.")
    for key in ("sheet", "range", "locator"):
        if key in source and source[key] is not None and not _text(source[key], 1000 if key == "locator" else 200):
            raise IndexError("A knowledge document has an invalid source locator field.")
    return item


def library(value):
    if (not _keys(value, {"schemaVersion", "tenantId", "evidenceSchema", "version", "embedding", "items", "warnings"})
            or value["schemaVersion"] != "case-knowledge-library-v1"
            or not isinstance(value["tenantId"], str) or not TENANT.fullmatch(value["tenantId"])
            or value["evidenceSchema"] != EVIDENCE_SCHEMA or not _hash(value["version"])
            or not isinstance(value["items"], list) or not 1 <= len(value["items"]) <= MAX_DOCUMENTS
            or not isinstance(value["warnings"], list) or any(not isinstance(x, str) for x in value["warnings"])):
        raise IndexError("The authorized library export does not match its versioned contract.")
    embedding = value["embedding"]
    if not _keys(embedding, {"enabled", "status", "model", "digest", "dimensions", "indexedAt", "indexedDocuments", "totalDocuments"}):
        raise IndexError("The library embedding summary has invalid keys.")
    if (type(embedding["enabled"]) is not bool or embedding["status"] not in {"CURRENT", "STALE", "MISSING", "DISABLED"}
            or type(embedding["indexedDocuments"]) is not int or not 0 <= embedding["indexedDocuments"] <= len(value["items"])
            or type(embedding["totalDocuments"]) is not int or embedding["totalDocuments"] != len(value["items"])):
        raise IndexError("The library embedding summary has invalid counts or state.")
    # Source inventory is authoritative for rebuilding. Embedding metadata may
    # refer to missing/stale weights; it never selects the model used here.
    docs, identifiers = [], set()
    source_characters = 0
    for item in value["items"]:
        if (not _keys(item, DOC_KEYS | {"version", "category", "selection", "embeddingStatus"})
                or item["category"] not in {"SCOPE", "GUIDANCE", "STATUS"}
                or item["selection"] not in {"ALWAYS", "EXACT_OR_SEMANTIC", "SEMANTIC"}
                or item["embeddingStatus"] not in {"CURRENT", "STALE", "MISSING", "DISABLED"}):
            raise IndexError("A current library item has invalid metadata.")
        doc = document({key: item[key] for key in DOC_KEYS})
        source_characters += len(canonical(doc))
        if source_characters > MAX_SOURCE_CHARACTERS:
            raise IndexError("The source inventory exceeds its bounded character allowance.")
        if doc["id"] in identifiers or fingerprint(doc) != item["version"]:
            raise IndexError("A library document is duplicated or its source fingerprint changed.")
        identifiers.add(doc["id"])
        docs.append(doc)
    if fingerprint(docs) != value["version"]:
        raise IndexError("The exported library fingerprint does not match its current ordered documents.")
    return {"tenantId": value["tenantId"], "version": value["version"], "documents": docs}


def vector(value):
    if (not isinstance(value, list) or len(value) != DIMENSIONS
            or any(type(x) not in (int, float) or not math.isfinite(x) or not -1 <= x <= 1 for x in value)
            or math.fsum(x * x for x in value) == 0):
        raise IndexError("An embedding is not a finite, nonzero 1024-dimensional vector.")
    return value


def existing_index(value):
    if (not _keys(value, {"schemaVersion", "model", "digest", "dimensions", "indexedAt", "tenants"})
            or value["schemaVersion"] != SCHEMA or value["model"] != MODEL
            or not _hash(value["digest"]) or type(value["dimensions"]) is not int or value["dimensions"] != DIMENSIONS
            or not _time(value["indexedAt"]) or not isinstance(value["tenants"], list)
            or not 1 <= len(value["tenants"]) <= 10):
        raise IndexError("The existing index does not match the canonical-document embedding contract.")
    hashes, tenant_ids = {}, set()
    total_documents = 0
    for tenant in value["tenants"]:
        if (not _keys(tenant, {"tenantId", "evidenceSchema", "documents"})
                or not isinstance(tenant["tenantId"], str) or not TENANT.fullmatch(tenant["tenantId"])
                or tenant["tenantId"] in tenant_ids or tenant["evidenceSchema"] != EVIDENCE_SCHEMA
                or not isinstance(tenant["documents"], list) or not 1 <= len(tenant["documents"]) <= MAX_DOCUMENTS):
            raise IndexError("The existing index has an invalid or duplicate tenant scope.")
        total_documents += len(tenant["documents"])
        if total_documents > MAX_TOTAL_DOCUMENTS:
            raise IndexError("The index exceeds its aggregate document allowance.")
        tenant_ids.add(tenant["tenantId"])
        identifiers = set()
        for doc in tenant["documents"]:
            if (not _keys(doc, {"id", "documentHash", "vector"}) or not isinstance(doc["id"], str)
                    or not IDENTIFIER.fullmatch(doc["id"]) or doc["id"] in identifiers or not _hash(doc["documentHash"])):
                raise IndexError("An existing index entry has an invalid identity or fingerprint.")
            identifiers.add(doc["id"])
            values = vector(doc["vector"])
            if doc["documentHash"] in hashes and hashes[doc["documentHash"]] != values:
                raise IndexError("The existing index contains inconsistent vectors for identical documents.")
            hashes[doc["documentHash"]] = values
    return value["digest"], hashes


class LocalEmbedder:
    def __init__(self, url, *, client=None):
        parsed = urlsplit(url)
        try:
            local = parsed.hostname == "localhost" or ipaddress.ip_address(parsed.hostname or "").is_loopback
            parsed.port  # Validate a supplied port without trusting it as a URL fragment.
        except ValueError:
            local = False
        if (parsed.scheme != "http" or not local or parsed.username or parsed.password
                or parsed.path not in {"", "/"} or parsed.query or parsed.fragment):
            raise IndexError("The embedding endpoint must be a credential-free loopback HTTP address.")
        self.client = client or httpx.Client(base_url=url.rstrip("/"), trust_env=False, follow_redirects=False)
        self.attempted = False
        self.batches = []

    def call(self, method, path, body=None, seconds=120):
        deadline = time.monotonic() + seconds
        try:
            with self.client.stream(method, path, json=body,
                                    timeout=httpx.Timeout(seconds, connect=5, write=10, pool=2)) as response:
                response.raise_for_status()
                raw = bytearray()
                for part in response.iter_bytes():
                    if time.monotonic() >= deadline or len(raw) + len(part) > MAX_BYTES:
                        raise IndexError("The local provider exceeded its response time or size bound.")
                    raw.extend(part)
            result = json.loads(raw, object_pairs_hook=_unique, parse_constant=_invalid_constant)
            if not isinstance(result, dict):
                raise IndexError("The local provider returned an invalid metadata object.")
            return result
        except (httpx.HTTPError, ValueError, UnicodeError, RecursionError) as exc:
            raise IndexError("The local embedding provider failed bounded response validation; no fallback was used.") from exc

    @staticmethod
    def model(response):
        records = response.get("models")
        if not isinstance(records, list):
            raise IndexError("The local model list is malformed.")
        matches = [record for record in records if isinstance(record, dict)
                   and record.get("name", record.get("model")) == MODEL]
        if len(matches) != 1:
            raise IndexError("The configured embedding model is not installed or its identity is ambiguous. No model was pulled.")
        digest = matches[0].get("digest")
        if not isinstance(digest, str) or not _hash(digest.removeprefix("sha256:")):
            raise IndexError("The local embedding model has an invalid fingerprint.")
        return matches[0], digest.removeprefix("sha256:")

    def installed_digest(self):
        return self.model(self.call("GET", "/api/tags", seconds=10))[1]

    def embed(self, texts, digest):
        self.attempted = True
        started = time.perf_counter()
        result = self.call("POST", "/api/embed", {"model": MODEL, "input": texts,
            "dimensions": DIMENSIONS, "truncate": False, "keep_alive": "30s", "options": {"num_gpu": 999}})
        if result.get("model") != MODEL or not isinstance(result.get("embeddings"), list) or len(result["embeddings"]) != len(texts):
            raise IndexError("The provider returned a different model or unexpected embedding count.")
        values = [vector(item) for item in result["embeddings"]]
        loaded, loaded_digest = self.model(self.call("GET", "/api/ps", seconds=10))
        gpu_bytes = loaded.get("size_vram")
        if loaded_digest != digest or type(gpu_bytes) is not int or gpu_bytes <= 0:
            raise IndexError("The actual embedding model fingerprint or GPU allocation was not verified. No CPU fallback was accepted.")
        self.batches.append({"documents": len(values), "seconds": round(time.perf_counter() - started, 3),
                             "gpuBytes": gpu_bytes})
        return values

    def unload(self):
        if self.attempted:
            result = self.call("POST", "/api/generate", {"model": MODEL, "prompt": "", "keep_alive": 0, "stream": False}, seconds=10)
            if result.get("model") != MODEL or result.get("done") is not True or result.get("done_reason") != "unload":
                raise IndexError("The embedding model unload was not acknowledged; no candidate index was written.")

    def close(self):
        self.client.close()


def build(libraries, previous, provider, *, now=None):
    started = time.perf_counter()
    inventories = [library(value) for value in libraries]
    if not 1 <= len(inventories) <= 10 or len({x["tenantId"] for x in inventories}) != len(inventories):
        raise IndexError("Provide one current export per tenant, up to ten unique tenants.")
    if sum(len(scope["documents"]) for scope in inventories) > MAX_TOTAL_DOCUMENTS:
        raise IndexError("The index exceeds its aggregate document allowance.")
    previous_digest, reusable = existing_index(previous) if previous is not None else (None, {})
    digest = provider.installed_digest()
    if not _hash(digest):
        raise IndexError("The configured provider did not supply a valid model fingerprint.")
    if previous_digest != digest:
        reusable = {}
    texts = {fingerprint(doc): canonical(doc) for scope in inventories for doc in scope["documents"]}
    missing = [(key, text) for key, text in texts.items() if key not in reusable]
    vectors = {key: reusable[key] for key in texts if key in reusable}
    failure = None
    try:
        for offset in range(0, len(missing), BATCH_SIZE):
            batch = missing[offset:offset + BATCH_SIZE]
            values = provider.embed([text for _, text in batch], digest)
            if len(values) != len(batch):
                raise IndexError("The provider returned an unexpected batch size.")
            for (key, _), values_for_document in zip(batch, values):
                vectors[key] = vector(values_for_document)
        if provider.installed_digest() != digest:
            raise IndexError("Embedding model weights changed during indexing; rebuild with stable local weights.")
    except Exception as exc:
        failure = exc
        raise
    finally:
        try:
            provider.unload()
        except Exception:
            if failure is None:
                raise
    indexed_at = (now or datetime.now(timezone.utc)).isoformat()
    if not _time(indexed_at):
        raise IndexError("Index creation time must include its timezone offset.")
    result = {"schemaVersion": SCHEMA, "model": MODEL, "digest": digest, "dimensions": DIMENSIONS,
              "indexedAt": indexed_at, "tenants": [{"tenantId": scope["tenantId"], "evidenceSchema": EVIDENCE_SCHEMA,
                "documents": [{"id": doc["id"], "documentHash": fingerprint(doc), "vector": vectors[fingerprint(doc)]}
                              for doc in scope["documents"]]} for scope in inventories]}
    serialized = canonical(result).encode("utf-8")
    if len(serialized) > MAX_BYTES:
        raise IndexError("The completed index exceeds 64 MiB; no output was written.")
    total = sum(len(scope["documents"]) for scope in inventories)
    receipt = {"schemaVersion": "case-knowledge-index-build-v1", "model": MODEL, "digest": digest,
               "dimensions": DIMENSIONS, "indexedAt": indexed_at, "tenantCount": len(inventories),
               "documents": total, "uniqueDocuments": len(texts), "embeddedDocuments": len(missing),
               "reusedDocuments": sum(fingerprint(doc) in reusable for scope in inventories for doc in scope["documents"]),
               "embeddingCalls": len(provider.batches), "processor": "GPU" if missing else "REUSED_INDEX",
               "gpuVerifiedThisBuild": bool(missing), "batches": provider.batches,
               "sourceVersions": [{"tenantId": scope["tenantId"], "version": scope["version"]} for scope in inventories],
               "indexSha256": hashlib.sha256(serialized).hexdigest(), "indexBytes": len(serialized),
               "seconds": round(time.perf_counter() - started, 3), "modelWeightsChanged": False}
    return serialized, canonical(receipt).encode("utf-8"), receipt


def atomic_create(path, content):
    """Publish a new file atomically without replacing any existing target."""
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix=".case-knowledge-", suffix=".tmp", dir=path.parent, delete=False) as target:
            temporary = Path(target.name)
            target.write(content)
            target.flush()
            os.fsync(target.fileno())
        # Same-directory hard-link creation is atomic and fails if path exists.
        # os.replace would silently overwrite an unrelated or concurrently
        # created file, so it is deliberately not used for immutable candidates.
        os.link(temporary, path)
    except OSError as exc:
        raise IndexError("An output could not be published as a new immutable file; no existing target was replaced.") from exc
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def output_paths(output, inputs):
    output = output.resolve()
    receipt = output.with_name(output.name + ".receipt.json")
    source_paths = {path.resolve() for path in inputs}
    if output in source_paths or receipt in source_paths or output.exists() or receipt.exists():
        raise IndexError("Use fresh output and receipt paths different from every source and existing index.")
    if not output.parent.is_dir():
        raise IndexError("Create the output directory before running the builder.")
    return output, receipt


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", action="append", type=Path, required=True,
                        help="Authorized GET /api/case-knowledge JSON export; repeat per tenant")
    parser.add_argument("--existing", type=Path, help="Previous compatible immutable index for vector reuse")
    parser.add_argument("--output", type=Path, required=True, help="New candidate index path; never overwritten")
    parser.add_argument("--ollama-url", default="http://127.0.0.1:11435")
    args = parser.parse_args(argv)
    provider = None
    try:
        sources = [*args.library, *([args.existing] if args.existing else [])]
        output, receipt_path = output_paths(args.output, sources)
        libraries = [read_object(path) for path in args.library]
        previous = read_object(args.existing) if args.existing else None
        provider = LocalEmbedder(args.ollama_url)
        index_data, receipt_data, receipt = build(libraries, previous, provider)
        atomic_create(output, index_data)
        atomic_create(receipt_path, receipt_data)
        print(json.dumps({"indexBuilt": True, "documents": receipt["documents"],
                          "embeddedDocuments": receipt["embeddedDocuments"], "reusedDocuments": receipt["reusedDocuments"],
                          "embeddingCalls": receipt["embeddingCalls"], "seconds": receipt["seconds"]}))
        return 0
    except IndexError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    except (OSError, ValueError, TypeError, RecursionError):
        print("Index construction failed validation; no source files were modified.", file=sys.stderr)
        return 1
    finally:
        if provider is not None:
            provider.close()


if __name__ == "__main__":
    raise SystemExit(main())
