"""Synthetic contract checks, separate from actual-model evaluation."""
from copy import deepcopy
from dataclasses import replace
import json

from fastapi.testclient import TestClient
import httpx
from ollama import Client
import pytest

from investigator.case_answer import CaseAnswerEngine, CASE_PROMPT, PROMPT_HASH, question_terms, rank_documents
from investigator.errors import InvalidModelResult, ProviderUnavailable
from investigator.main import create_app
from investigator.uat_answer import UatAnswerRequest


def document(id, kind, content):
    return {"id": id, "kind": kind, "title": id,
            "content": json.dumps(content) if isinstance(content, dict) else content,
            "source": {"file": "original-synthetic.json", "locator": id}}


@pytest.fixture
def case_request():
    return UatAnswerRequest.model_validate({"question": "What amount is recorded?", "snapshotId": "SYN-EVD-1", "evidenceHash": "a"*64,
        "documents": [document("CASE-CONTEXT", "evidence", {"sourceTimezone": "UNKNOWN"}),
                      document("PAYMENT-ROW-1", "evidence", {"SOURCE_TABLE": "PM_NEFT_TXN_LOG", "NUMAMOUNT_4038": "125.005", "CODSTATUS": "Q77"}),
                      document("GUIDE-LIMITS", "knowledge", "Recorded amounts and statuses do not establish beneficiary credit.")]})


def output(value="125.005"):
    return {"claims": [{"text": f"NUMAMOUNT_4038 records {value}.", "evidenceIds": ["PAYMENT-ROW-1"],
                        "claimType": "observation", "fields": [{"documentId": "PAYMENT-ROW-1", "field": "NUMAMOUNT_4038", "value": value}]}],
            "unknowns": ["Beneficiary credit is not established."],
            "nextChecks": ["Inspect posted beneficiary accounting to verify credit."]}


def engine_for(settings, response=None, failure=False):
    requests=[]
    def respond(request):
        body=json.loads(request.content); requests.append(body)
        if failure:
            raise httpx.ConnectError("private-source-must-not-leak",request=request)
        generated=response(body) if callable(response) else response if response is not None else output()
        return httpx.Response(200,json={"model":"synthetic", "message":{"role":"assistant","content":generated if isinstance(generated,str) else json.dumps(generated)},
                                        "done":True,"done_reason":"stop","prompt_eval_count":40,"eval_count":20})
    def factory():
        model=engine._model()
        model._client=Client(host=settings.ollama_base_url,transport=httpx.MockTransport(respond),**model.client_kwargs)
        return model
    engine=CaseAnswerEngine(settings,model_factory=factory)
    return engine,requests


def test_dynamic_values_questions_prose_and_preserved_support(settings,case_request):
    def generated(body):
        payload=json.loads(body['messages'][1]['content'])
        row=next(d['content'] for d in payload['documents'] if d['id']=='PAYMENT-ROW-1')
        value=output(row['NUMAMOUNT_4038'])
        value['claims'][0]['text'] += ' This wording came from this model response.'
        return value
    engine,requests=engine_for(settings,generated)
    first=engine.run(case_request)
    changed=case_request.model_dump()
    changed.update(question='What changed in the amount?',snapshotId='SYN-EVD-2',evidenceHash='b'*64)
    changed['documents'][1]['content']=changed['documents'][1]['content'].replace('125.005','900.001')
    second=engine.run(UatAnswerRequest.model_validate(changed))
    assert len(requests)==2 and first.answer!=second.answer
    assert '125.005' in first.answer and '900.001' in second.answer
    assert first.rag.promptHash==PROMPT_HASH
    assert first.rag.claimSupports[0].fields[0].value=='125.005'
    assert first.answer==json.loads(requests[0]['messages'][1]['content'])['documents'][1]['content']['NUMAMOUNT_4038'].join(['NUMAMOUNT_4038 records ','. This wording came from this model response.'])
    assert requests[0]['think'] is False
    assert requests[0]['messages'][0]['content']==CASE_PROMPT
    assert first.model.actualCalls==1 and first.model.promptTokens==40
    assert first.citations[0].content==case_request.documents[1].content
    assert set(first.retrieval.documentIds)=={d.id for d in case_request.documents}


@pytest.mark.parametrize('mutation',[
    lambda x:x['claims'][0]['fields'][0].update(value='999.99'),
    lambda x:x['claims'][0]['fields'][0].update(field='MISSING'),
    lambda x:x['claims'][0]['fields'][0].update(documentId='OTHER-CASE'),
    lambda x:x['claims'][0]['fields'][0].update(documentId='CASE-CONTEXT'),
    lambda x:x['claims'][0].update(text='The payment succeeded.'),
    lambda x:x['claims'][0].update(evidenceIds=['GUIDE-LIMITS']),
    lambda x:x['claims'][0].update(evidenceIds=['OTHER-CASE']),
    lambda x:x['claims'][0].update(fields=[]),
    lambda x:x['claims'][0].update(claimType='interpretation'),
    lambda x:x.update(unknowns=[]),
    lambda x:x.update(nextChecks=[]),
    lambda x:x.update(nextChecks=['PAYMENT-ROW-1']),
    lambda x:x['claims'][0]['fields'].append(deepcopy(x['claims'][0]['fields'][0])),
])
def test_unsupported_output_rejected_after_bounded_model_correction(settings,case_request,mutation):
    candidate=output();mutation(candidate)
    engine,requests=engine_for(settings,candidate)
    with pytest.raises(InvalidModelResult):engine.run(case_request)
    assert len(requests)==2


def test_limitation_cites_evidence_and_can_have_no_field(settings,case_request):
    candidate=output()
    candidate['claims']=[{'text':'The supplied rows do not establish beneficiary credit.','evidenceIds':['PAYMENT-ROW-1','GUIDE-LIMITS'], 'claimType':'limitation','fields':[]}]
    engine,_=engine_for(settings,candidate)
    assert engine.run(case_request).rag.claimSupports[0].fields==[]


def test_interpretation_with_guidance_and_exact_fields(settings,case_request):
    candidate=output();candidate['claims'][0].update(claimType='interpretation',evidenceIds=['PAYMENT-ROW-1','GUIDE-LIMITS'])
    engine,_=engine_for(settings,candidate)
    assert engine.run(case_request).claims[0].text==candidate['claims'][0]['text']


def test_friendly_field_label_keeps_exact_native_support(settings,case_request):
    candidate=output();candidate['claims'][0]['text']='The recorded amount is 125.005.'
    engine,_=engine_for(settings,candidate)
    result=engine.run(case_request)
    assert result.answer=='The recorded amount is 125.005.'
    assert result.rag.claimSupports[0].fields[0].field=='NUMAMOUNT_4038'


def test_duplicate_decoded_model_keys_rejected(settings,case_request):
    candidate=json.dumps(output()).replace('"claims":','"claims":[],"clai\\u006ds":',1)
    engine,requests=engine_for(settings,candidate)
    with pytest.raises(InvalidModelResult):engine.run(case_request)
    assert len(requests)==2


def test_one_model_correction_preserves_scope_and_counts_both_calls(settings,case_request):
    calls=[]
    def generated(body):
        calls.append(body)
        candidate=output()
        if len(calls)==1:candidate['claims'][0]['fields'][0]['value']='999.99'
        return candidate
    engine,requests=engine_for(settings,generated)
    result=engine.run(case_request)
    assert len(requests)==2 and result.model.actualCalls==2
    assert result.model.promptTokens==80 and result.model.completionTokens==40
    assert result.answer==output()['claims'][0]['text']
    first,second=[json.loads(call['messages'][1]['content']) for call in calls]
    for key in ['documents','question','snapshotId','evidenceHash']:assert first[key]==second[key]
    assert '999.99' in second['previousInvalidResponse']
    assert 'Correct the previous invalid JSON' in calls[1]['messages'][0]['content']
    assert engine.settings.uat_model_timeout_seconds==settings.uat_model_timeout_seconds/2


def test_budget_exceeded_before_model_no_row_truncation(settings,case_request):
    engine,requests=engine_for(replace(settings,uat_context_tokens=4096),output())
    with pytest.raises(InvalidModelResult,match='No rows were dropped'):engine.run(case_request)
    assert not requests


def test_missing_native_rows_fails_before_model(settings,case_request):
    data=case_request.model_dump();data['documents'].pop(1)
    engine,requests=engine_for(settings)
    with pytest.raises(InvalidModelResult,match='native source row'):engine.run(UatAnswerRequest.model_validate(data))
    assert not requests


def test_provider_failure_is_not_a_substitute_answer(settings,case_request):
    engine,requests=engine_for(settings,failure=True)
    with pytest.raises(ProviderUnavailable,match='No substitute') as failure:engine.run(case_request)
    assert 'private-source' not in str(failure.value) and len(requests)==1


def test_scoped_retrieval_retains_every_document(settings,case_request):
    data=case_request.model_dump()
    data['documents'] += [document('GUIDE-UNRELATED','knowledge','Archived report storage'),document('GUIDE-CREDIT','knowledge','beneficiary credit accounting outcome')]
    request=UatAnswerRequest.model_validate(data)
    ranked=rank_documents(question_terms('Was the beneficiary credited?'),request.documents)
    assert ranked[:2]==request.documents[:2]
    assert ranked[2].id=='GUIDE-CREDIT'
    assert {d.id for d in ranked}=={d.id for d in request.documents}


def test_authenticated_distinct_endpoint_and_baseline_untouched(settings,case_request):
    engine,requests=engine_for(settings)
    with TestClient(create_app(settings,case_engine=engine)) as client:
        denied=client.post('/case/answer',json=case_request.model_dump())
        assert denied.status_code==401 and not requests
        result=client.post('/case/answer',headers={'X-Service-Key':settings.service_key},json=case_request.model_dump(exclude_unset=True))
        assert result.status_code==200 and result.json()['rag']['pipeline']=='case-evidence-rag-v1'
        assert result.json()['citations']==[case_request.documents[1].model_dump(exclude_unset=True)]
        assert client.app.state.case_engine.lock is not client.app.state.uat_engine.lock # injected test engine
        assert type(client.app.state.uat_engine).__name__=='UatAnswerEngine'


def test_default_engines_share_serial_inference_lock(settings):
    with TestClient(create_app(settings)) as client:
        assert client.app.state.case_engine.lock is client.app.state.uat_engine.lock
