"""Strict original-synthetic OBPM evidence contract; native semantics stay explicit."""
from datetime import date, datetime, timezone
from decimal import Decimal
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictInt, field_validator, model_validator

Text = Annotated[str, Field(min_length=1, max_length=200)]
NativeCode = Annotated[str, Field(min_length=1, max_length=80)]
Stamp = Annotated[str, Field(min_length=1, max_length=50)]
OBPM_SCOPE = {"domain": "OBPM_NEFT", "rail": "NEFT", "direction": "OUTBOUND", "releaseFamily": "14.7"}


def utc_instant(value):
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            raise ValueError("An explicit timestamp timezone is required")
        return parsed.astimezone(timezone.utc)
    except OverflowError as exc:
        raise ValueError("Timestamp is outside the supported UTC calendar") from exc


class ObpmRecord(BaseModel):
    model_config = ConfigDict(extra="forbid")

    @field_validator("*", mode="after")
    @classmethod
    def timestamps(cls, value, info):
        if info.field_name in {"extractedAt", "createdAt", "enteredAt", "exitedAt", "observedAt", "requestedAt", "timeoutRecordedAt", "asOf"} and value is not None:
            utc_instant(value)
        return value


class ObpmSource(ObpmRecord):
    deploymentId: Literal["SYNTHETIC-OBPM"]
    releaseFamily: Literal["14.7"]
    exactMaintenanceRelease: Text | None = None
    hostCode: Literal["DEMO-HOST"]
    branchCode: Literal["DEMO-BRANCH"]


class ObpmPayment(ObpmRecord):
    sourcePaymentId: Text
    rail: Literal["NEFT"]
    direction: Literal["OUTBOUND"]
    sourceAmountDecimal: Annotated[str, Field(pattern=r"^(0|[1-9][0-9]{0,13})(\.[0-9]{1,2})?$")]
    amountMinor: Annotated[StrictInt, Field(ge=0, le=9007199254740991)]
    currency: Literal["INR"]
    activationDate: date
    createdAt: Stamp
    nativeTransactionStatus: NativeCode | None = None
    statusUnavailableReason: Annotated[str, Field(max_length=500)] | None = None

    @model_validator(mode="after")
    def exact_amount(self):
        if Decimal(self.sourceAmountDecimal) * 100 != self.amountMinor:
            raise ValueError("Source decimal and exact INR minor units disagree")
        return self


class ObpmQueueRecord(ObpmRecord):
    evidenceId: Text
    sourcePaymentId: Text
    queueReference: Text
    requestAttemptId: Text
    nativeQueueCode: NativeCode
    nativeResponseStatus: NativeCode
    enteredAt: Stamp | None = None
    exitedAt: Stamp | None = None
    isCurrentQueueRecord: StrictBool
    observedAt: Stamp


class ObpmAttempt(ObpmRecord):
    evidenceId: Text
    requestAttemptId: Text
    sourcePaymentId: Text
    requestType: Literal["ECA"]
    requestedAt: Stamp
    timeoutRecordedAt: Stamp | None = None
    externalSystemFinalOutcome: NativeCode | None = None


class ObpmCoverage(ObpmRecord):
    status: Literal["COMPLETE", "PARTIAL", "UNAVAILABLE", "NOT_REQUESTED"]
    scope: Annotated[str, Field(min_length=1, max_length=500)] | None = None
    asOf: Stamp | None = None
    paginationComplete: StrictBool | None = None
    reason: Annotated[str, Field(min_length=1, max_length=500)] | None = None

    @model_validator(mode="after")
    def completeness(self):
        if self.status == "COMPLETE" and (not self.scope or not self.asOf or self.paginationComplete is not True):
            raise ValueError("COMPLETE coverage requires scope, asOf and paginationComplete=true")
        if self.status != "COMPLETE" and not self.reason:
            raise ValueError("Incomplete source coverage requires a reason")
        return self


class ObpmSourceCoverage(ObpmRecord):
    queueRecords: ObpmCoverage
    messages: ObpmCoverage
    externalCoreResponses: ObpmCoverage
    accountingEntries: ObpmCoverage


class ObpmEvidence(ObpmRecord):
    schemaVersion: Literal["obpm-evidence-v1"]
    dataClassification: Literal["SYNTHETIC"]
    snapshotId: Text
    mappingVersion: Text
    extractedAt: Stamp
    source: ObpmSource
    payment: ObpmPayment
    queueRecords: list[ObpmQueueRecord] = Field(max_length=1000)
    externalRequestAttempts: list[ObpmAttempt] = Field(max_length=1000)
    messages: list = Field(max_length=0)
    accountingEntries: list = Field(max_length=0)
    sourceCoverage: ObpmSourceCoverage

    @model_validator(mode="after")
    def supported_groups_and_ids(self):
        ids = [r.evidenceId for r in self.queueRecords + self.externalRequestAttempts]
        if len(ids) != len(set(ids)):
            raise ValueError("OBPM evidence IDs must be unique across supplied record groups")
        for name in ("messages", "accountingEntries"):
            if getattr(self.sourceCoverage, name).status not in {"UNAVAILABLE", "NOT_REQUESTED"}:
                raise ValueError(f"{name} coverage is unsupported in this milestone")
        return self


class ObpmUnknownReconciliation(ObpmRecord):
    """Java marks validated NEFT money without pretending card totals exist."""
    scope: Literal["OBPM_EVIDENCE"]
    validMoney: Literal[True]
    providerAvailable: Literal[False]
    currency: Literal["INR"]
    calculatedBy: Literal["java-api"]
    captureCount: None
    captureMinor: None
    refundMinor: None
    ledgerNetMinor: None
    providerPayoutMinor: None
    discrepancyMinor: None

    @model_validator(mode="before")
    @classmethod
    def exact_boolean_flags(cls, data):
        if isinstance(data, dict) and (data.get("validMoney") is not True or data.get("providerAvailable") is not False):
            raise ValueError("OBPM reconciliation must explicitly mark valid money and unavailable provider totals")
        return data
