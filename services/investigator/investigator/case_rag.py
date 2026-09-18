"""Default case RAG: model-written prose with code-checked literal quotations.

The stricter model-authored support experiment is retained in case_answer.py.
It is not the serving path: live evaluation showed unnecessary answer rejection.
"""
from hashlib import sha256
from dataclasses import replace
from contextvars import ContextVar
import json
import re
from typing import Annotated

from pydantic import Field, ValidationError

from .case_answer import (CaseAnswerEngine, CaseAnswerResponse, ClaimSupport,
                          FieldSupport, RagReceipt, CHECKS, PIPELINE, ROW_ID, unique_json)
from .errors import InvalidModelResult
from .case_jobs import CaseJobCancelled
from .uat_answer import (StrictModel, UatClaim, ShortText, UatAnswerEngine,
                         SYSTEM_PROMPT, synthesis_schema)


class PlainCaseSynthesis(StrictModel):
    claims: Annotated[list[UatClaim], Field(min_length=1, max_length=4)]
    unknowns: Annotated[list[ShortText], Field(min_length=1, max_length=3)]
    nextChecks: Annotated[list[ShortText], Field(min_length=1, max_length=3)]


CASE_RAG_PROMPT = SYSTEM_PROMPT + """
CASE WORKBENCH:
Return at most FOUR concise claims. This is a saved payment case, not a generic
payment tutorial. Answer its question first. For beneficiary credit or OBPM
acceptance without direct confirmation, start with the cited unknown outcome.
Use native rows for current observations; CASE-CONTEXT discovery is an earlier
observation and its operator reason is not proof. Explain any disagreement.
For code interpretations, quote the field and exact raw value, cite its native
row AND its field-specific definition, and preserve deployment qualifications.
Use explicit forms such as FIELD=value when quoting a native field. Never
borrow meanings between equal codes in different columns.
Always return 1-3 useful unknowns AND 1-3 read-only next checks. A narrow factual
answer may leave broader payment outcome and source verification unresolved.
Do not fabricate uncertainty if a particular fact is directly present; distinguish
that recorded fact from final outcome. Do not return empty unknowns/nextChecks.
Do not generate proof metadata, field lists or any extra JSON keys. The application
checks explicit field quotations separately; you write all explanation prose.
"""
CASE_RAG_PROMPT_HASH = sha256(CASE_RAG_PROMPT.encode()).hexdigest()
_cancel_check = ContextVar("case_job_cancellation", default=None)


def checked_quotations(claim, documents):
    """Check recognizable FIELD=value or FIELD value quotations, not entailment.

Friendly aliases, indirect references and general conclusions are not marked
field-verified. All supplied source text remains available for human inspection.
"""
    rows = {}
    for identifier in claim.evidenceIds:
        document = documents.get(identifier)
        if document and document.kind == "evidence" and ROW_ID.fullmatch(identifier):
            try:
                row = unique_json(document.content)
            except (ValueError, RecursionError) as exc:
                raise InvalidModelResult("A cited native row is not a unique JSON object.") from exc
            if not isinstance(row, dict):
                raise InvalidModelResult("A cited native row is not a JSON object.")
            rows[identifier] = row
    fields = {key for row in rows.values() for key, value in row.items()
              if isinstance(value, str) and value and re.fullmatch(r"[A-Z][A-Z0-9_]{1,99}", key)}
    # A known reference ID is metadata, not a native field/value assertion.
    # Skip only its exact token spans; retain the existing checks elsewhere,
    # including negative values and unknown hyphenated strings.
    reference_spans = [match.span() for identifier in documents if '-' in identifier
                       for match in re.finditer(r"(?<![\w-])" + re.escape(identifier) + r"(?![\w-])", claim.text)]
    selected = {}
    for field in sorted(fields):
        expression = re.compile(r"(?<!\w)" + re.escape(field) + r"(?!\w)\s*(?:=|:|\bis\b|\bwas\b|\bof\b)?\s*[`'\"]?([A-Za-z0-9_:+.-]+)")
        for match in expression.finditer(claim.text):
            if any(start <= match.start() < end for start, end in reference_spans):
                continue
            value = match.group(1)
            available = {row[field] for row in rows.values() if isinstance(row.get(field), str)}
            if value not in available and value.endswith('.'):
                value = value[:-1]  # Sentence punctuation, never numeric coercion.
            if value not in available and not re.fullmatch(r"(?:[+-]?\d[\w.+:-]*|[A-Z][A-Z0-9_.:-]{0,49})", value):
                continue  # e.g. 'CODSTATUS changed': no literal value assertion.
            if value not in available:
                raise InvalidModelResult("An explicit native field quotation disagrees with the cited source rows. Copy its exact raw value or remove the unsupported quotation.")
            for identifier, row in rows.items():
                if row.get(field) == value:
                    selected[(identifier, field)] = FieldSupport(documentId=identifier, field=field, value=value)
    if len(selected) > 6:
        raise InvalidModelResult("Split the quoted native fields into shorter claims with at most six source-field references per claim.")
    return list(selected.values())


class CaseRagEngine(CaseAnswerEngine):
    def __init__(self, settings, **kwargs):
        # Reuse the local runner and its prompt cache between case questions.
        # The original export engine and strict case experiment keep their
        # existing model lifetime; no generated answer is cached here.
        super().__init__(replace(settings,
                                 uat_model_keep_alive_seconds=settings.case_model_keep_alive_seconds),
                         **kwargs)

    def _system_prompt(self):
        return CASE_RAG_PROMPT

    def run_cancellable(self, request, cancelled):
        token = _cancel_check.set(cancelled)
        try:
            if cancelled():
                raise CaseJobCancelled()
            result = self.run(request)
            if cancelled():
                raise CaseJobCancelled()
            return result
        finally:
            _cancel_check.reset(token)

    def _generate(self, state):
        check = _cancel_check.get()
        if check and check():
            raise CaseJobCancelled()
        result = super()._generate(state)
        if check and check():
            raise CaseJobCancelled()
        return result

    def preflight(self, request):
        """Check the actual serving serializer without acquiring inference or embedding."""
        state = {"request": request}
        state.update(self._plan(state))
        state.update(self._retrieve(state))
        _, _, _, input_bytes = self._generation_request(state)
        reserve = self.settings.uat_output_tokens + 512
        return {
            "schemaVersion": "case-context-readiness-v1",
            "ready": input_bytes + reserve <= self.settings.uat_context_tokens,
            "documentCount": len(request.documents),
            "serializedBytes": input_bytes,
            "requiredBudget": input_bytes + reserve,
            "contextLimit": self.settings.uat_context_tokens,
            "outputAndFramingReserve": reserve,
            "method": "utf8-byte-upper-bound",
            "modelChecked": False,
            "promptHash": CASE_RAG_PROMPT_HASH,
        }

    def _schema(self, documents):
        schema = synthesis_schema([d.id for d in documents])
        schema['properties']['claims']['maxItems'] = 4
        for name in ('unknowns', 'nextChecks'):
            schema['properties'][name]['minItems'] = 1
        return schema

    def _feedback(self, failure):
        return ("Correct the previous response using the same original documents and question. "
                + failure + " Return only claims [{text,evidenceIds}], unknowns and nextChecks. "
                "Quote native values exactly, use only supplied citation IDs, and include 1-3 genuine unknowns "
                "and 1-3 read-only next checks. Generate the complete corrected response; do not add proof metadata.")

    def _accept(self, state):
        try:
            synthesis = PlainCaseSynthesis.model_validate(unique_json(state['content']))
        except (ValueError, ValidationError, RecursionError) as exc:
            raise InvalidModelResult("A case answer requires at most four cited claims and nonempty unknowns and next checks.") from exc
        validated = UatAnswerEngine._validate(self, {**state, 'usage': {**state['usage'], 'actualCalls': 1}})['result']
        documents = {d.id: d for d in state['documents']}
        supports = []
        for index, claim in enumerate(synthesis.claims):
            fields = checked_quotations(claim, documents)
            kind = ('interpretation' if any(documents[id].kind == 'knowledge' for id in claim.evidenceIds) else 'observation') if fields else 'source-cited'
            supports.append(ClaimSupport(claimIndex=index, claimType=kind, fields=fields))
        response = validated.model_dump(exclude_unset=True)
        response['model'] = state['usage']
        return {'result': CaseAnswerResponse(**response, rag=RagReceipt(
            pipeline=PIPELINE, promptHash=CASE_RAG_PROMPT_HASH, checks=CHECKS, claimSupports=supports)), 'feedback': ''}
