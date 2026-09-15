"""Snapshot-only NEFT ECA observations; never a funds or settlement decision."""
from langchain_core.tools import tool

from .evidence import CaseToolInput, SnapshotTools, insufficient
from .facts import timestamp
from .obpm_models import OBPM_SCOPE, utc_instant

OBPM_TOOL_NAMES = ("inspect_obpm_payment", "inspect_obpm_queue", "inspect_obpm_eca_requests", "inspect_obpm_coverage")
OBPM_QUERY = "OBPM NEFT outbound 14.7 ECA EC T recorded timeout queue request correlation coverage external core outcome evidence"


class ObpmSnapshotTools(SnapshotTools):
    def __init__(self, snapshot):
        if snapshot.domain != "OBPM_NEFT" or snapshot.obpm is None:
            raise ValueError("OBPM tools require an authorized typed NEFT snapshot")
        self.snapshot, self.calls, self.outputs = snapshot, [], {}
        self.obpm = snapshot.obpm.model_dump(mode="json")

        @tool(args_schema=CaseToolInput)
        def inspect_obpm_payment(caseId: str) -> str:
            """Read the authorized NEFT identity, source release, exact amount and extraction cutoff."""
            return self._execute("inspect_obpm_payment", caseId, self.payment)

        @tool(args_schema=CaseToolInput)
        def inspect_obpm_queue(caseId: str) -> str:
            """Read supplied queue identities, native EC/T codes, current flags and observation times."""
            return self._execute("inspect_obpm_queue", caseId, self.queue)

        @tool(args_schema=CaseToolInput)
        def inspect_obpm_eca_requests(caseId: str) -> str:
            """Read ECA request attempts, correlation references and recorded timeout timestamps."""
            return self._execute("inspect_obpm_eca_requests", caseId, self.requests)

        @tool(args_schema=CaseToolInput)
        def inspect_obpm_coverage(caseId: str) -> str:
            """Read per-source scope, availability, cutoff and pagination completeness for this extract."""
            return self._execute("inspect_obpm_coverage", caseId, self.coverage)

        self.tools = {t.name: t for t in (inspect_obpm_payment, inspect_obpm_queue, inspect_obpm_eca_requests, inspect_obpm_coverage)}

    def payment(self):
        return {"payment": self.obpm["payment"], "source": self.obpm["source"], "snapshotId": self.obpm["snapshotId"],
                "extractedAt": self.obpm["extractedAt"], "mappingVersion": self.obpm["mappingVersion"], "evidenceIds": []}

    def queue(self):
        records = self.obpm["queueRecords"]
        return {"records": records, "evidenceIds": [r["evidenceId"] for r in records]}

    def requests(self):
        records = self.obpm["externalRequestAttempts"]
        return {"records": records, "evidenceIds": [r["evidenceId"] for r in records]}

    def coverage(self):
        return {"sources": self.obpm["sourceCoverage"], "extractedAt": self.obpm["extractedAt"], "evidenceIds": []}


def obpm_insufficient(requests):
    requests = list(dict.fromkeys(requests))
    # Preserve every observed conflict while respecting the public list bound.
    if len(requests) > 12:
        requests = requests[:11] + [" ".join(requests[11:])]
    result = insufficient(requests)
    result.update(summary="The supplied NEFT ECA evidence does not establish an unambiguous current recorded timeout. Obtain or reconcile the listed source evidence.",
                  query=OBPM_QUERY + " incomplete unknown conflicting evidence scope", reason="Request the listed NEFT evidence for independent review; do not change payment state.")
    return result


def diagnose_obpm(outputs):
    missing = [name for name in OBPM_TOOL_NAMES if name not in outputs]
    if missing:
        return obpm_insufficient(["Inspect all four authorized NEFT evidence groups: payment identity, queue history, ECA attempts and source coverage."])
    payment_output = outputs["inspect_obpm_payment"]
    payment = payment_output["payment"]
    queues = outputs["inspect_obpm_queue"]["records"]
    attempts = outputs["inspect_obpm_eca_requests"]["records"]
    coverage = outputs["inspect_obpm_coverage"]["sources"]
    cutoff = utc_instant(payment_output["extractedAt"])
    created = utc_instant(payment["createdAt"])
    issues = []
    if created > cutoff:
        issues.append("Reconcile payment creation time with the extraction cutoff; creation is after this snapshot.")
    by_attempt = {}
    for attempt in attempts:
        by_attempt.setdefault(attempt["requestAttemptId"], []).append(attempt)
        requested = utc_instant(attempt["requestedAt"])
        if attempt["sourcePaymentId"] != payment["sourcePaymentId"]:
            issues.append("Obtain ECA attempts correlated to this exact source payment; a supplied attempt belongs to another payment.")
        if not created <= requested <= cutoff:
            issues.append("Reconcile ECA request times with payment creation and the extraction cutoff.")
        if attempt.get("timeoutRecordedAt") and not requested <= utc_instant(attempt["timeoutRecordedAt"]) <= cutoff:
            issues.append("Reconcile the recorded timeout time with its request time and extraction cutoff.")
    if any(len(rows) != 1 for rows in by_attempt.values()):
        issues.append("Obtain a unique ECA attempt for each requestAttemptId; duplicate attempt identities are ambiguous.")

    for record in queues:
        observed = utc_instant(record["observedAt"])
        entered = utc_instant(record["enteredAt"]) if record.get("enteredAt") else None
        exited = utc_instant(record["exitedAt"]) if record.get("exitedAt") else None
        if record["sourcePaymentId"] != payment["sourcePaymentId"]:
            issues.append("Obtain queue records correlated to this exact source payment; a supplied queue record belongs to another payment.")
        if not created <= observed <= cutoff or (entered is not None and not created <= entered <= observed) or (exited is not None and not (entered or created) <= exited <= observed):
            issues.append("Reconcile queue entry, exit and observation times with payment creation and extraction cutoff.")
        matching = by_attempt.get(record["requestAttemptId"], [])
        if len(matching) != 1:
            issues.append("Obtain the unique ECA request attempt referenced by each supplied queue record.")
        elif utc_instant(matching[0]["requestedAt"]) > (entered or observed):
            issues.append("Reconcile queue timing: the referenced ECA request occurs after queue entry or observation.")
        elif matching[0].get("timeoutRecordedAt") and utc_instant(matching[0]["timeoutRecordedAt"]) > observed:
            issues.append("Reconcile the ECA timeout timestamp with the queue observation; the timeout is recorded later.")
        if record["isCurrentQueueRecord"] and exited is not None:
            issues.append("Obtain consistent current-queue evidence; a record marked current also has an exit time.")

    for name, source in coverage.items():
        as_of = utc_instant(source["asOf"]) if source.get("asOf") else None
        if as_of and as_of > cutoff:
            issues.append(f"Reconcile {name} coverage cutoff with the snapshot extraction time.")
    queue_coverage = coverage["queueRecords"]
    if queue_coverage["status"] != "COMPLETE":
        issues.append("Obtain complete queue history for this payment through a stated cutoff, including pagination and current-record coverage.")
    elif any(utc_instant(row["observedAt"]) > utc_instant(queue_coverage["asOf"]) for row in queues):
        issues.append("Reconcile queue coverage: a supplied observation occurs after its declared coverage cutoff.")

    current = [r for r in queues if r["isCurrentQueueRecord"]]
    if len(current) != 1:
        issues.append("Obtain exactly one current queue record for this payment; do not choose between missing or multiple current records using timestamp order.")
    elif current[0]["nativeQueueCode"] != "EC" or current[0]["nativeResponseStatus"] != "T":
        issues.append("Obtain the supported current EC/T ECA timeout codes or verified meanings for the supplied native queue/response codes.")
    if issues:
        return obpm_insufficient(issues)
    row = current[0]
    attempt = by_attempt[row["requestAttemptId"]][0]
    if attempt.get("externalSystemFinalOutcome") not in {None, "UNKNOWN", "UNAVAILABLE"}:
        return obpm_insufficient(["Obtain verified external-core response semantics and reconcile the supplied final-outcome code with the current EC/T timeout; this slice cannot interpret that outcome."])
    external = coverage["externalCoreResponses"]
    if external["status"] == "COMPLETE" and utc_instant(external["asOf"]) < utc_instant(attempt.get("timeoutRecordedAt") or attempt["requestedAt"]):
        return obpm_insufficient(["Obtain external-core response coverage through the ECA request/recorded-timeout time; its declared complete cutoff is earlier."])
    evidence_ids = sorted({r["evidenceId"] for r in queues} | {attempt["evidenceId"]})
    return {
        "outcome": "OBPM_ECA_TIMEOUT", "action": "REQUEST_EVIDENCE", "query": OBPM_QUERY,
        "summary": "The supplied NEFT snapshot establishes one current EC/T queue record correlated to an ECA request: a recorded ECA timeout only. External funds, block/posting outcome, beneficiary credit and settlement are not established.",
        "reason": "Request correlated external-core outcome and accounting evidence for independent review. This proposal does not authorize resending or changing a payment.",
        "evidenceIds": evidence_ids,
        "missingEvidence": ["Obtain the external-core response or confirmed response absence for this ECA request through a stated cutoff.",
                            "Obtain separately correlated accounting/block confirmation with source coverage before drawing a funds or posting conclusion."],
    }


def build_obpm_fact_catalog(outputs, citations):
    """Exact service observations with full queue-membership and request links."""
    if not citations or diagnose_obpm(outputs)["outcome"] != "OBPM_ECA_TIMEOUT":
        return []
    queues = outputs["inspect_obpm_queue"]["records"]
    row = next(r for r in queues if r["isCurrentQueueRecord"])
    attempt = next(r for r in outputs["inspect_obpm_eca_requests"]["records"] if r["requestAttemptId"] == row["requestAttemptId"])
    ids = sorted({r["evidenceId"] for r in queues} | {attempt["evidenceId"]})
    return [{
        "id": "FACT-OBPM-ECA-TIMEOUT",
        "text": f"The supplied NEFT queue snapshot has one current EC/T record observed at {timestamp(row['observedAt'])}, correlated to an ECA request made at {timestamp(attempt['requestedAt'])}. This establishes a recorded ECA timeout only, not an external funds or settlement outcome.",
        "evidenceIds": ids, "citationIds": [citations[0]["id"]], "sourceTools": list(OBPM_TOOL_NAMES),
    }]
