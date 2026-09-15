"""Exercise the real LangChain/Ollama adapter through a fake HTTP transport."""
import json
from dataclasses import replace

import httpx
from ollama import Client
from pydantic import ValidationError
import pytest

from investigator.evidence import TOOL_NAMES
from investigator.graph import InvestigatorEngine, ollama_finding_schema
from investigator.models import Finding


@pytest.mark.parametrize("insufficient", [False, True])
def test_generation_controls_are_ollama_options_and_reasoning_is_disabled(settings, base_case, request_for, insufficient):
    settings = replace(settings, model_timeout_seconds=180)
    if insufficient:
        base_case["provider"]["status"] = "UNKNOWN"
        base_case["ledgerEntries"] = []
    requests = []

    def respond(request):
        body = json.loads(request.content)
        requests.append(body)
        assert body["think"] is False
        assert body["stream"] is False
        assert body["options"]["num_ctx"] == settings.model_context_tokens
        assert body["options"]["num_thread"] == 4
        assert "num_predict" not in body
        if body.get("tools"):
            assert body["options"]["num_predict"] == settings.tool_output_tokens
            assert json.loads(body["messages"][1]["content"])["question"] == "Investigate this payment"
            message = {"role": "assistant", "content": "", "tool_calls": [
                {"function": {"name": name, "arguments": {"caseId": base_case["id"]}}}
                for name in TOOL_NAMES
            ]}
        else:
            assert body["options"]["num_predict"] == settings.synthesis_output_tokens
            assert body["format"]["type"] == "object"
            assert "maxLength" not in json.dumps(body["format"])
            assert set(body["format"]["properties"]) == {"factIds"}
            assert body["format"]["additionalProperties"] is False
            selector = body["format"]["properties"]["factIds"]
            assert selector["minItems"] == 1
            assert selector["maxItems"] == 2
            assert selector["uniqueItems"] is True
            context = json.loads(body["messages"][1]["content"])
            assert set(context) == {"question", "factCatalogVersion", "facts", "policy"}
            assert context["question"] == "Investigate this payment"
            assert context["factCatalogVersion"] == 1
            assert selector["items"]["enum"] == [fact["id"] for fact in context["facts"]]
            assert all(fact["citationIds"] == ["RB-TIMEOUT:v1"] for fact in context["facts"])
            assert all(fact["sourceTools"] for fact in context["facts"])
            assert "untrusted data" in body["messages"][0]["content"].lower()
            message = {"role": "assistant", "content": json.dumps({"factIds": ["FACT-PROVIDER-STATUS", "FACT-CAPTURE-LEDGER"]})}
        return httpx.Response(200, content=json.dumps({"model": "adapter-contract", "message": message,
            "done": True, "done_reason": "stop", "prompt_eval_count": 10, "eval_count": 20}) + "\n")

    def factory():
        # Keep the production model construction and replace only its HTTP client.
        model = engine._model()
        assert model.client_kwargs["timeout"] == 180
        model._client = Client(host=settings.ollama_base_url, transport=httpx.MockTransport(respond), **model.client_kwargs)
        assert model._client._client.timeout.read == 180
        return model

    engine = InvestigatorEngine(settings, model_factory=factory)
    try:
        result = engine.run(request_for(base_case, mode="ollama"))
        assert len(requests) == (1 if insufficient else 2)
        assert result.metrics["modelCalls"] == (1 if insufficient else 2)
        assert result.metrics["modelThreads"] == 4
        assert result.metrics["modelContextTokens"] == 4096
        assert result.metrics["modelTimeoutSeconds"] == 180
        assert result.metrics["toolOutputTokenLimit"] == settings.tool_output_tokens
        assert result.metrics["synthesisOutputTokenLimit"] == settings.synthesis_output_tokens
        assert result.metrics["assessmentSource"] == "deterministic-evidence-rules"
        if insufficient:
            assert result.outcome == "INSUFFICIENT_EVIDENCE"
            assert result.metrics["synthesisScope"] == "skipped-insufficient-evidence"
            assert "synthesisMs" not in result.metrics
            assert result.findings == []
            assert result.confidence == "INSUFFICIENT"
            assert result.missingEvidence
            assert result.proposal.action == "REQUEST_EVIDENCE"
            assert any("fact selection was skipped" in warning for warning in result.warnings)
        else:
            assert result.outcome == "TIMEOUT_AFTER_SUCCESS"
            assert result.metrics["synthesisScope"] == "fact-selection"
            assert result.metrics["findingSource"] == "service-rendered-facts"
            assert result.metrics["factCatalogVersion"] == 1
            assert len(result.metrics["factCatalogHash"]) == 64
            assert result.metrics["selectedFactIds"] == ["FACT-PROVIDER-STATUS", "FACT-CAPTURE-LEDGER"]
            assert result.findings[0].text == "The provider reported SUCCEEDED as of 2026-09-11T09:03:00Z; this is a status observation time. The supplied ledger snapshot contains 1 capture entry totaling INR 1,000.00."
            assert result.findings[0].evidenceIds == ["LED-CAPTURE", "LED-FEE", "PROVIDER:PRV-TEST"]
            assert result.metrics["selectedFactProvenance"][1]["sourceTools"] == ["compare_settlement"]
            assert result.summary != result.findings[0].text
            assert any("selected authorized fact IDs" in warning for warning in result.warnings)
    finally:
        engine.close()


def test_compact_provider_grammar_keeps_final_length_validation():
    schema = ollama_finding_schema(evidence_ids=["EVT-1"], citation_ids=["RB-1:v1"])
    assert schema["additionalProperties"] is False
    assert schema["required"] == list(Finding.model_fields)
    with pytest.raises(ValidationError):
        Finding.model_validate({"id": "F-1", "text": "x" * 2001,
            "evidenceIds": ["EVT-1"], "citationIds": ["RB-1:v1"]})


@pytest.mark.parametrize("evidence,citations", [([], ["RB-EVIDENCE:v1"]), (["EVT-1"], []), ([], [])])
def test_missing_authorized_link_scope_cannot_generate_a_finding(evidence, citations):
    with pytest.raises(ValueError, match="authorized operational and policy links"):
        ollama_finding_schema(evidence_ids=evidence, citation_ids=citations)


def test_captured_repeated_policy_links_exceed_compact_provider_bound():
    # CASE-1011 returned this same policy ID three times in the initial sample.
    captured = ["RB-WEBHOOK-DEDUP:v1"] * 3
    schema = ollama_finding_schema(evidence_ids=["WH-1011-1"], citation_ids=captured)
    links = schema["properties"]["citationIds"]
    assert links["items"]["enum"] == ["RB-WEBHOOK-DEDUP:v1"]
    assert links["minItems"] == links["maxItems"] == 1
    assert len(captured) > links["maxItems"]
