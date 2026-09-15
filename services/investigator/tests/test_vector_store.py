from datetime import date
import os
import uuid

from langchain_core.documents import Document
import pytest

from investigator.vector_store import PgVectorStore, document_key, vector_text


def doc(identifier, tenant="northstar", start="2026-01-01", end=None, version=1):
    return Document(page_content=f"Original test operating policy {identifier}", metadata={"id": identifier, "version": version, "tenantId": tenant, "effectiveFrom": start, "effectiveTo": end, "title": identifier, "source": "Synthetic test"})


@pytest.mark.parametrize("vector", [[], [float("nan")], [float("inf")], [True], ["1.0"]])
def test_invalid_embeddings_fail_before_sql(vector):
    with pytest.raises(ValueError):
        vector_text(vector)


def test_permission_and_date_changes_invalidate_cached_document_key():
    original = doc("ONE")
    assert document_key(original) != document_key(doc("ONE", tenant="silverline"))
    assert document_key(original) != document_key(doc("ONE", end="2026-09-01"))
    assert document_key(original) != document_key(doc("ONE", version=2))


@pytest.mark.integration
def test_actual_pgvector_persistence_scope_dates_and_model_filters():
    dsn = os.getenv("POI_TEST_VECTOR_DB_URL")
    if not dsn:
        pytest.skip("Set POI_TEST_VECTOR_DB_URL to run against actual local PostgreSQL/pgvector")
    namespace = "test-" + uuid.uuid4().hex
    store = PgVectorStore(dsn, namespace)
    eligible = doc("CURRENT", start="2026-06-01", version=2)
    private = doc("PRIVATE", tenant="silverline")
    expired = doc("EXPIRED", end="2026-06-01")
    unlisted = doc("REMOVED")
    wrong_dimension = doc("WRONG-DIMENSION")
    for document in (private, expired, unlisted):
        store.upsert(document, [1.0, 0.0], "test-model-a")
    store.upsert(eligible, [0.0, 1.0], "test-model-a")
    store.upsert(eligible, [1.0, 0.0, 0.0], "test-model-b")
    store.upsert(wrong_dimension, [1.0, 0.0, 0.0], "test-model-a")
    # Private, expired and removed chunks would be closer; server filters must exclude them.
    found = store.search([1.0, 0.0], model="test-model-a", tenant_id="northstar", policy_date=date(2026, 9, 11), active_documents=[eligible, private, expired, wrong_dimension], limit=1)
    assert found == [(document_key(eligible), 1.0)]
    restarted = PgVectorStore(dsn, namespace)
    assert restarted.cached_vectors([eligible], "test-model-a")[document_key(eligible)] == [0.0, 1.0]
    assert restarted.cached_vectors([eligible], "test-model-b")[document_key(eligible)] == [1.0, 0.0, 0.0]
    historical = restarted.search([1.0, 0.0], model="test-model-a", tenant_id="northstar", policy_date=date(2026, 5, 31), active_documents=[eligible, expired], limit=1)
    assert historical[0][0] == document_key(expired)
