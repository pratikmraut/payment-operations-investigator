from dataclasses import replace

from fastapi.testclient import TestClient
import pytest

from investigator.case_rag import CaseRagEngine
from investigator.errors import InvalidModelResult
from investigator.main import create_app
from test_case_answer import case_request
from test_case_rag import adapter, plain


def test_preflight_does_not_generate_and_keeps_the_same_model_request(settings, case_request):
    engine, calls = adapter(settings, plain())
    original = case_request.model_dump_json(exclude_unset=True)
    readiness = engine.preflight(case_request)
    assert readiness["ready"] is True
    assert readiness["modelChecked"] is False
    assert readiness["documentCount"] == len(case_request.documents)
    assert readiness["requiredBudget"] == readiness["serializedBytes"] + readiness["outputAndFramingReserve"]
    assert calls == []
    result = engine.run(case_request)
    assert len(calls) == result.model.actualCalls == 1
    assert case_request.model_dump_json(exclude_unset=True) == original


def test_preflight_and_generation_agree_at_the_context_boundary(settings, case_request):
    original = CaseRagEngine(settings).preflight(case_request)
    boundary = original["requiredBudget"]
    assert 4096 <= boundary <= 131072
    for delta, fits in [(0, True), (-1, False)]:
        engine, calls = adapter(replace(settings, uat_context_tokens=boundary + delta), plain())
        assert engine.preflight(case_request)["ready"] is fits
        if fits:
            engine.run(case_request)
            assert len(calls) == 1
        else:
            with pytest.raises(InvalidModelResult, match="context budget"):
                engine.run(case_request)
            assert calls == []


def test_authenticated_preflight_is_metadata_only_and_never_calls_model(settings, case_request):
    engine, calls = adapter(settings, plain())
    with TestClient(create_app(settings, case_engine=engine)) as client:
        body = case_request.model_dump(exclude_unset=True)
        assert client.post("/case/preflight", json=body).status_code == 401
        response = client.post("/case/preflight", json=body,
                               headers={"X-Service-Key": settings.service_key})
        assert response.status_code == 200
        value = response.json()
        assert value["schemaVersion"] == "case-context-readiness-v1"
        assert "documents" not in value and "question" not in value
        assert value["modelChecked"] is False
        assert calls == []


def test_preflight_still_rejects_a_bundle_without_native_rows(settings, case_request):
    from investigator.uat_answer import UatAnswerRequest
    body = case_request.model_dump(exclude_unset=True)
    body["documents"] = [d for d in body["documents"] if not d["id"].startswith("PAYMENT-ROW-")]
    request = UatAnswerRequest.model_validate(body)
    engine, calls = adapter(settings, plain())
    with pytest.raises(InvalidModelResult, match="native source row"):
        engine.preflight(request)
    assert calls == []
