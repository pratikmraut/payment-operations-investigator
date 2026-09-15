from dataclasses import replace
import json

from fastapi.testclient import TestClient
import httpx
from ollama import Client
import pytest

from investigator.case_rag import CaseRagEngine, CASE_RAG_PROMPT_HASH, checked_quotations
from investigator.uat_answer import UatAnswerRequest, UatClaim
from investigator.errors import InvalidModelResult
from investigator.main import create_app
from investigator.config import Settings
from investigator.case_answer import CaseAnswerEngine
from investigator.uat_answer import UatAnswerEngine
from test_case_answer import case_request


def plain(text='NUMAMOUNT_4038=125.005 is recorded.',ids=None):
    return {'claims':[{'text':text,'evidenceIds':ids or ['PAYMENT-ROW-1']}],
            'unknowns':['Final payment outcome is not established.'],
            'nextChecks':['Inspect the posted ledger for confirmation of beneficiary credit.']}


def adapter(settings, output):
    calls=[]
    def respond(request):
        body=json.loads(request.content);calls.append(body)
        generated=output(body,len(calls)) if callable(output) else output
        return httpx.Response(200,json={'model':'synthetic','message':{'role':'assistant','content':json.dumps(generated)},'done':True,'done_reason':'stop','prompt_eval_count':30,'eval_count':20})
    def factory():
        model=engine._model();model._client=Client(host=settings.ollama_base_url,transport=httpx.MockTransport(respond),**model.client_kwargs);return model
    engine=CaseRagEngine(settings,model_factory=factory)
    return engine,calls


@pytest.mark.parametrize('text,value',[
    ('NUMAMOUNT_4038=125.005 is recorded.','125.005'),
    ('The row has NUMAMOUNT_4038: 125.005.','125.005'),
    ('CODSTATUS Q77 is the raw code.','Q77'),
    ('CODSTATUS is `Q77`.','Q77'),
])
def test_explicit_literal_quotation_checked_against_cited_row(case_request,text,value):
    fields=checked_quotations(UatClaim.model_validate(plain(text)['claims'][0]),{d.id:d for d in case_request.documents})
    assert len(fields)==1 and fields[0].value==value and fields[0].documentId=='PAYMENT-ROW-1'


@pytest.mark.parametrize('text',['CODSTATUS=R88 is recorded.','NUMAMOUNT_4038=125.00 is recorded.','NUMAMOUNT_4038 is 999.99.'])
def test_forged_native_value_rejected(case_request,text):
    with pytest.raises(InvalidModelResult,match='disagrees'):
        checked_quotations(UatClaim.model_validate(plain(text)['claims'][0]),{d.id:d for d in case_request.documents})


@pytest.fixture
def reference_request(case_request):
    data = case_request.model_dump(exclude_unset=True)
    guide = next(d.copy() for d in data['documents'] if d['kind'] == 'knowledge')
    guide['id'] = 'GUIDE-CODSTATUS-2'
    data['documents'].append(guide)
    return UatAnswerRequest.model_validate(data)


@pytest.mark.parametrize('text', [
    'CODSTATUS is Q77, as explained by GUIDE-CODSTATUS-2.',
    'PM_NEFT_TXN_LOG.CODSTATUS=Q77 (GUIDE-CODSTATUS-2).',
    'See GUIDE-CODSTATUS-2; the recorded CODSTATUS is Q77.',
])
def test_citation_identifier_is_not_a_negative_field_value(reference_request, text):
    fields = checked_quotations(UatClaim.model_validate(plain(text)['claims'][0]),
                               {d.id:d for d in reference_request.documents})
    assert len(fields) == 1 and fields[0].value == 'Q77'


def test_reference_name_alone_is_not_field_support(reference_request):
    fields = checked_quotations(UatClaim.model_validate(plain('See GUIDE-CODSTATUS-2.')['claims'][0]),
                               {d.id:d for d in reference_request.documents})
    assert fields == []


@pytest.mark.parametrize('text', [
    'CODSTATUS=-2 is recorded (GUIDE-CODSTATUS-2).',
    'CODSTATUS -2 is recorded.',
    'PM_NEFT_TXN_LOG.CODSTATUS=R88 is recorded (GUIDE-CODSTATUS-2).',
    'GUIDE-CODSTATUS-2 says the row has CODSTATUS=R88.',
    '-CODSTATUS=R88 is recorded.',
    'CODSTATUS-2 is recorded.',
    'See UNKNOWN-CODSTATUS-2.',
])
def test_reference_name_does_not_hide_incorrect_literal(reference_request, text):
    with pytest.raises(InvalidModelResult, match='disagrees'):
        checked_quotations(UatClaim.model_validate(plain(text)['claims'][0]),
                           {d.id:d for d in reference_request.documents})


def test_cited_reference_in_prose_does_not_trigger_correction(settings, reference_request):
    engine, calls = adapter(settings, plain('CODSTATUS is Q77 (GUIDE-CODSTATUS-2).'))
    result = engine.run(reference_request)
    assert len(calls) == result.model.actualCalls == 1
    assert result.rag.claimSupports[0].fields[0].value == 'Q77'


def test_unparsed_prose_not_field_verified(settings,case_request):
    engine,calls=adapter(settings,plain('The amount is 125.005.'))
    result=engine.run(case_request)
    assert len(calls)==1 and result.rag.claimSupports[0].claimType=='source-cited'
    assert result.rag.claimSupports[0].fields==[]
    assert result.answer=='The amount is 125.005.'


def test_guidance_only_claim_is_preserved_without_field_check(settings,case_request):
    engine,_=adapter(settings,plain('The supplied guide says codes alone do not confirm credit.',['GUIDE-LIMITS']))
    result=engine.run(case_request)
    assert result.rag.claimSupports[0].claimType=='source-cited'
    assert result.rag.claimSupports[0].fields==[]


def test_correction_of_literal_value_is_model_generated_and_bounded(settings,case_request):
    def generated(body,index):return plain('NUMAMOUNT_4038=999.99.' if index==1 else 'NUMAMOUNT_4038=125.005.')
    engine,calls=adapter(settings,generated)
    result=engine.run(case_request)
    assert result.answer=='NUMAMOUNT_4038=125.005.' and result.model.actualCalls==2
    assert result.model.promptTokens==60 and result.model.completionTokens==40
    assert len(calls)==2 and 'APPLICATION VALIDATION' in calls[1]['messages'][0]['content']


def test_no_repair_or_substitution_after_second_invalid_response(settings,case_request):
    engine,calls=adapter(settings,plain('CODSTATUS=R88.'))
    with pytest.raises(InvalidModelResult,match='one correction'):engine.run(case_request)
    assert len(calls)==2


def test_default_http_contract_preserves_citations_and_original_model_format(settings,case_request):
    engine,calls=adapter(settings,plain())
    with TestClient(create_app(settings,case_engine=engine)) as client:
        result=client.post('/case/answer',headers={'X-Service-Key':settings.service_key},json=case_request.model_dump(exclude_unset=True))
        assert result.status_code==200
        data=result.json()
        assert data['citations']==[case_request.documents[1].model_dump(exclude_unset=True)]
        assert data['rag']['promptHash']==CASE_RAG_PROMPT_HASH
        assert data['rag']['checks']==['source-membership','literal-field-quotations','required-unknowns-and-next-checks']
        assert set(calls[0]['format']['properties'])=={'claims','unknowns','nextChecks'}
        assert set(calls[0]['format']['$defs']['UatClaim']['properties'])=={'text','evidenceIds'}


def test_changed_source_value_controls_dynamic_answer(settings,case_request):
    def generated(body,index):
        payload=json.loads(body['messages'][1]['content'])
        row=next(d['content'] for d in payload['documents'] if d['id']=='PAYMENT-ROW-1')
        return plain('NUMAMOUNT_4038='+row['NUMAMOUNT_4038']+'.')
    engine,calls=adapter(settings,generated)
    first=engine.run(case_request)
    changed=case_request.model_dump(exclude_unset=True)
    changed['snapshotId']='SYN-CHANGED';changed['evidenceHash']='b'*64
    changed['documents'][1]['content']=changed['documents'][1]['content'].replace('125.005','900.001')
    second=engine.run(UatAnswerRequest.model_validate(changed))
    assert first.answer=='NUMAMOUNT_4038=125.005.' and second.answer=='NUMAMOUNT_4038=900.001.'
    assert len(calls)==2


def test_literal_check_is_not_semantic_entailment(case_request):
    claim=UatClaim.model_validate(plain('CODSTATUS=Q77 therefore the beneficiary was credited.')['claims'][0])
    fields=checked_quotations(claim,{d.id:d for d in case_request.documents})
    # The Q77 quotation is checkable; the unsupported conclusion still needs
    # factual review. A receipt must never claim full factual validation.
    assert fields[0].value=='Q77'


def test_serving_path_uses_default_rag_not_strict_experiment(settings):
    with TestClient(create_app(settings)) as client:
        assert type(client.app.state.case_engine) is CaseRagEngine
        assert client.app.state.case_engine.lock is client.app.state.uat_engine.lock


@pytest.mark.parametrize('keep_alive', [0, 600, 1800, 3600])
def test_case_runner_lifetime_is_independent_of_original_engines(settings, case_request, keep_alive):
    configuration = replace(settings, uat_model_keep_alive_seconds=0,
                            case_model_keep_alive_seconds=keep_alive)
    engine, calls = adapter(configuration, plain())
    first = engine.run(case_request)
    second = engine.run(case_request)
    # Two new questions still generate twice; only the provider's runner and
    # prompt state may be reused. Frozen citations remain the supplied source.
    assert len(calls) == 2
    assert first.citations == second.citations
    assert all(body['keep_alive'] == f'{keep_alive}s' for body in calls)
    assert all(body['think'] is False for body in calls)
    assert all(body['options']['num_ctx'] == configuration.uat_context_tokens for body in calls)
    assert all(body['options']['num_predict'] == configuration.uat_output_tokens for body in calls)
    assert all(body['options']['temperature'] == 0 for body in calls)
    assert UatAnswerEngine(configuration)._model().keep_alive == '0s'
    assert CaseAnswerEngine(configuration)._model().keep_alive == '0s'
    assert configuration.uat_model_keep_alive_seconds == 0


@pytest.mark.parametrize('value', [-1, 3601, True, 1.5, '300'])
def test_case_runner_lifetime_rejects_unbounded_or_noninteger_value(settings, value):
    with pytest.raises(ValueError, match='POI_CASE_MODEL_KEEP_ALIVE_SECONDS'):
        replace(settings, case_model_keep_alive_seconds=value)


def test_case_runner_lifetime_default_and_environment_are_separate(monkeypatch):
    monkeypatch.delenv('POI_CASE_MODEL_KEEP_ALIVE_SECONDS', raising=False)
    monkeypatch.setenv('POI_UAT_MODEL_KEEP_ALIVE_SECONDS', '0')
    assert Settings.from_env().case_model_keep_alive_seconds == 1800
    monkeypatch.setenv('POI_CASE_MODEL_KEEP_ALIVE_SECONDS', '600')
    configuration = Settings.from_env()
    assert configuration.case_model_keep_alive_seconds == 600
    assert configuration.uat_model_keep_alive_seconds == 0
