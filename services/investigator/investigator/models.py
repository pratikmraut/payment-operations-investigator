from datetime import date, datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator, model_serializer

from .obpm_models import OBPM_SCOPE, ObpmEvidence, ObpmUnknownReconciliation

Outcome = Literal["TIMEOUT_AFTER_SUCCESS", "DUPLICATE_WEBHOOK", "OUT_OF_ORDER_WEBHOOK", "MISSING_REFUND", "INSUFFICIENT_EVIDENCE", "PROVIDER_FAILURE", "OBPM_ECA_TIMEOUT"]
Action = Literal["RESOLVE_CASE", "ESCALATE", "REQUEST_EVIDENCE"]


class CaseSnapshot(BaseModel):
    # Extra UI fields can arrive, but tools explicitly select operational fields.
    model_config = ConfigDict(extra="ignore")
    id: str = Field(min_length=1, max_length=100)
    tenantId: str = Field(min_length=1, max_length=100, pattern=r"^[A-Za-z0-9_-]+$")
    paymentId: str = Field(min_length=1, max_length=100)
    amountMinor: int = Field(ge=0)
    currency: str = Field(pattern=r"^[A-Z]{3}$")
    events: list[dict[str, Any]] = Field(default_factory=list, max_length=1000)
    ledgerEntries: list[dict[str, Any]] = Field(default_factory=list, max_length=1000)
    webhooks: list[dict[str, Any]] = Field(default_factory=list, max_length=1000)
    provider: dict[str, Any] | None = None
    reconciliation: dict[str, Any] | None = None
    policyDate: date
    domain: Literal["OBPM_NEFT"] | None = None
    rail: str | None = None
    obpm: ObpmEvidence | None = None

    @model_validator(mode="after")
    def domain_contract(self):
        if self.domain == "OBPM_NEFT":
            if self.obpm is None or self.rail != "NEFT":
                raise ValueError("OBPM_NEFT requires the typed outbound NEFT evidence snapshot")
            payment = self.obpm.payment
            if (self.paymentId, self.amountMinor, self.currency) != (payment.sourcePaymentId, payment.amountMinor, payment.currency):
                raise ValueError("Case payment identity/money must match its OBPM snapshot")
            if self.policyDate != payment.activationDate:
                raise ValueError("OBPM policy date must match the authorized activation date")
            if self.events or self.ledgerEntries or self.webhooks or self.provider is not None:
                raise ValueError("OBPM evidence cannot be mixed with simulated provider/ledger records")
            if self.reconciliation is not None:
                ObpmUnknownReconciliation.model_validate(self.reconciliation)
        elif self.obpm is not None or self.rail == "NEFT":
            raise ValueError("NEFT evidence requires explicit OBPM_NEFT domain")
        return self

    @model_serializer(mode="wrap")
    def preserve_legacy_fingerprint(self, handler):
        data = handler(self)
        if self.domain is None:
            for name in ("domain", "rail", "obpm"):
                data.pop(name, None)
        return data

    @field_validator("events", "ledgerEntries", "webhooks")
    @classmethod
    def unique_record_ids(cls, records):
        ids = [record.get("id") for record in records]
        if any(not isinstance(record_id, str) or not record_id for record_id in ids):
            raise ValueError("Every operational record requires a non-empty id")
        if len(set(ids)) != len(ids):
            raise ValueError("Operational record ids must be unique within each collection")
        return records


class InvestigationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    investigationId: str = Field(min_length=1, max_length=100)
    case: CaseSnapshot
    question: str = Field(default="Investigate this payment using the available evidence.", max_length=4000)
    mode: Literal["replay", "ollama"] = "replay"
    actorId: str = Field(min_length=1, max_length=100)


class RetrieveRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    query: str = Field(min_length=1, max_length=4000)
    tenantId: str = Field(min_length=1, max_length=100, pattern=r"^[A-Za-z0-9_-]+$")
    policyDate: date
    limit: int = Field(default=4, ge=1, le=10)
    domain: str | None = None
    rail: str | None = None
    direction: str | None = None
    releaseFamily: str | None = None

    @model_validator(mode="after")
    def supported_scope(self):
        supplied = {key: getattr(self, key) for key in OBPM_SCOPE}
        if any(supplied.values()) and supplied != OBPM_SCOPE:
            raise ValueError("OBPM retrieval requires domain, rail, direction and release family")
        return self

    def policy_scope(self):
        return dict(OBPM_SCOPE) if self.domain == "OBPM_NEFT" else None


class Finding(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: str
    text: str = Field(min_length=1, max_length=2000)
    evidenceIds: list[str]
    citationIds: list[str]


class FactSelection(BaseModel):
    model_config = ConfigDict(extra="forbid")
    factIds: list[str] = Field(min_length=1, max_length=2)


class ObpmToolOrder(BaseModel):
    model_config = ConfigDict(extra="forbid")
    toolOrder: list[Literal["inspect_obpm_payment", "inspect_obpm_queue", "inspect_obpm_eca_requests", "inspect_obpm_coverage"]] = Field(min_length=4, max_length=4)

    @field_validator("toolOrder")
    @classmethod
    def mandatory_permutation(cls, names):
        if len(set(names)) != 4:
            raise ValueError("Every mandatory OBPM evidence tool must appear exactly once")
        return names


class Proposal(BaseModel):
    model_config = ConfigDict(extra="forbid")
    action: Action
    reason: str = Field(min_length=1, max_length=1000)


class Synthesis(BaseModel):
    model_config = ConfigDict(extra="forbid")
    outcome: Outcome
    summary: str = Field(min_length=1, max_length=3000)
    confidence: Literal["HIGH", "MEDIUM", "INSUFFICIENT"]
    findings: list[Finding] = Field(max_length=8)
    missingEvidence: list[str] = Field(max_length=12)
    proposal: Proposal


class Citation(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: str
    documentId: str
    version: int
    title: str
    excerpt: str
    source: str
    score: float


class InvestigationResult(Synthesis):
    id: str
    caseId: str
    createdAt: datetime
    createdBy: str
    mode: Literal["replay", "ollama"]
    status: Literal["AWAITING_REVIEW"] = "AWAITING_REVIEW"
    citations: list[Citation]
    toolCalls: list[dict[str, Any]]
    metrics: dict[str, Any]
    warnings: list[str]
