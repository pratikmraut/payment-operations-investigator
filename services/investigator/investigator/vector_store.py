"""Optional PostgreSQL/pgvector persistence. Every search is scoped before top-k."""
from datetime import date
import hashlib
import json
import math

from langchain_core.documents import Document
import psycopg
from psycopg.types.json import Jsonb


def document_key(document: Document) -> str:
    # Include permission and validity metadata, not just text, to invalidate changed grants.
    data = {"content": document.page_content, "metadata": document.metadata}
    return hashlib.sha256(json.dumps(data, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def content_hash(document: Document) -> str:
    return hashlib.sha256(document.page_content.encode()).hexdigest()


def vector_text(vector: list[float]) -> str:
    if not vector or len(vector) > 16000 or any(not isinstance(x, (int, float)) or isinstance(x, bool) or not math.isfinite(x) for x in vector):
        raise ValueError("Embedding must contain 1-16000 finite numeric dimensions")
    return "[" + ",".join(str(float(x)) for x in vector) + "]"


class PgVectorStore:
    def __init__(self, dsn: str, namespace: str = "payment-runbooks"):
        self.dsn = dsn
        self.namespace = namespace
        self.ready = False

    def _connect(self):
        return psycopg.connect(self.dsn, connect_timeout=5)

    def ensure_schema(self):
        if self.ready:
            return
        with self._connect() as connection:
            if not connection.execute("SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname='vector')").fetchone()[0]:
                raise RuntimeError("The pgvector extension must be installed by database setup, not by the application role")
            connection.execute("""CREATE TABLE IF NOT EXISTS poi_vector_documents (
                namespace TEXT NOT NULL,
                model_key TEXT NOT NULL,
                document_key TEXT NOT NULL,
                document_id TEXT NOT NULL,
                document_version INTEGER NOT NULL,
                tenant_id TEXT NOT NULL,
                effective_from DATE NOT NULL,
                effective_to DATE,
                content_hash TEXT NOT NULL,
                dimension INTEGER NOT NULL CHECK (dimension > 0),
                content TEXT NOT NULL,
                metadata JSONB NOT NULL,
                embedding VECTOR NOT NULL,
                indexed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY(namespace, model_key, document_key)
            )""")
            connection.execute("CREATE INDEX IF NOT EXISTS poi_vector_scope_idx ON poi_vector_documents(namespace, model_key, tenant_id, effective_from, effective_to)")
        self.ready = True

    def cached_vectors(self, documents: list[Document], model: str) -> dict[str, list[float]]:
        self.ensure_schema()
        keys = [document_key(doc) for doc in documents]
        if not keys:
            return {}
        with self._connect() as connection:
            rows = connection.execute("SELECT document_key, embedding::text FROM poi_vector_documents WHERE namespace=%s AND model_key=%s AND document_key=ANY(%s::text[])", (self.namespace, model, keys)).fetchall()
        return {key: json.loads(vector) for key, vector in rows}

    def upsert(self, document: Document, vector: list[float], model: str):
        self.ensure_schema()
        serialized = vector_text(vector)
        meta = document.metadata
        with self._connect() as connection:
            connection.execute("""INSERT INTO poi_vector_documents(namespace,model_key,document_key,document_id,document_version,tenant_id,effective_from,effective_to,content_hash,dimension,content,metadata,embedding)
                VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s::vector)
                ON CONFLICT(namespace,model_key,document_key) DO UPDATE SET
                dimension=EXCLUDED.dimension,embedding=EXCLUDED.embedding,metadata=EXCLUDED.metadata,indexed_at=CURRENT_TIMESTAMP""",
                (self.namespace, model, document_key(document), meta["id"], meta["version"], meta["tenantId"], meta["effectiveFrom"], meta.get("effectiveTo"), content_hash(document), len(vector), document.page_content, Jsonb(meta), serialized))

    def search(self, query_vector: list[float], *, model: str, tenant_id: str, policy_date: date, active_documents: list[Document], limit: int) -> list[tuple[str, float]]:
        self.ensure_schema()
        if not active_documents:
            return []
        serialized = vector_text(query_vector)
        keys = [document_key(doc) for doc in active_documents]
        with self._connect() as connection:
            rows = connection.execute("""SELECT document_key, embedding <=> %s::vector AS distance
                FROM poi_vector_documents
                WHERE namespace=%s AND model_key=%s AND dimension=%s
                  AND tenant_id IN ('*', %s)
                  AND effective_from <= %s AND (effective_to IS NULL OR %s < effective_to)
                  AND document_key=ANY(%s::text[])
                ORDER BY distance, document_key LIMIT %s""",
                (serialized, self.namespace, model, len(query_vector), tenant_id, policy_date, policy_date, keys, limit)).fetchall()
        return [(key, float(distance)) for key, distance in rows]
