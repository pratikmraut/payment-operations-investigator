"""Deterministic arithmetic and observed-record predicates, never scenario labels.

This is an explainable synthetic replay baseline, not an LLM prediction. Free-text
case titles, descriptions, event summaries and tags are deliberately excluded.
"""
from collections import Counter
from datetime import datetime
import json
from time import perf_counter

from langchain_core.tools import tool
from pydantic import BaseModel, ConfigDict, Field

from .models import CaseSnapshot

TOOL_NAMES = ("get_payment_timeline", "compare_settlement", "inspect_webhooks", "check_refund")


def code(value) -> str:
    return str(value or "").upper().replace(".", "_").replace("-", "_")


def minor(value):
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def instant(value):
    try:
        result = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        return result.timestamp() if result.tzinfo else None
    except (ValueError, TypeError, OverflowError):
        return None


def selected(record, keys):
    return {key: record[key] for key in keys if key in record}


def display_money(amount: int | None, currency: str) -> str:
    """Project synthetic currencies use two fractional digits; never convert via float."""
    if amount is None:
        return "unavailable"
    magnitude = abs(amount)
    whole, fractional = divmod(magnitude, 100)
    return f"{currency} {'-' if amount < 0 else ''}{whole:,}.{fractional:02d}"


class CaseToolInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    caseId: str = Field(description="The exact authorized case identifier from this investigation")


class SnapshotTools:
    def __init__(self, snapshot: CaseSnapshot):
        self.snapshot = snapshot
        self.calls: list[dict] = []
        self.outputs: dict[str, dict] = {}

        @tool(args_schema=CaseToolInput)
        def get_payment_timeline(caseId: str) -> str:
            """Read immutable payment events and current provider status for this case."""
            return self._execute("get_payment_timeline", caseId, self.timeline)

        @tool(args_schema=CaseToolInput)
        def compare_settlement(caseId: str) -> str:
            """Compare integer-minor-unit ledger capture/refund/payout values to provider facts."""
            return self._execute("compare_settlement", caseId, self.settlement)

        @tool(args_schema=CaseToolInput)
        def inspect_webhooks(caseId: str) -> str:
            """Inspect provider event identities, application counts and occurrence/receipt ordering."""
            return self._execute("inspect_webhooks", caseId, self.webhook_analysis)

        @tool(args_schema=CaseToolInput)
        def check_refund(caseId: str) -> str:
            """Compare explicit requested refund amounts to observed refund ledger/provider records."""
            return self._execute("check_refund", caseId, self.refunds)

        self.tools = {t.name: t for t in (get_payment_timeline, compare_settlement, inspect_webhooks, check_refund)}

    def _execute(self, name, case_id, operation):
        if case_id != self.snapshot.id:
            raise PermissionError("Tool access is restricted to the authorized snapshot")
        if name in self.outputs:
            return json.dumps(self.outputs[name], sort_keys=True)
        started = perf_counter()
        output = operation()
        self.outputs[name] = output
        self.calls.append({"name": name, "status": "COMPLETED", "durationMs": round((perf_counter()-started)*1000, 3), "evidenceIds": output["evidenceIds"]})
        return json.dumps(output, sort_keys=True)

    @property
    def provider_id(self):
        return f"PROVIDER:{self.snapshot.provider.get('paymentId', self.snapshot.paymentId)}" if self.snapshot.provider else None

    def timeline(self):
        events = []
        for row in self.snapshot.events:
            event = selected(row, ("id", "occurredAt", "type", "source", "status", "correlationId"))
            event["attributes"] = selected(row.get("attributes", {}), ("idempotencyKey", "providerPaymentId", "amountMinor", "refundMinor", "refundAmountMinor", "errorCode", "reasonCode"))
            events.append(event)
        provider = selected(self.snapshot.provider or {}, ("status", "paymentId", "amountMinor", "feeMinor", "refundMinor", "payoutMinor", "asOf", "errorCode"))
        return {"events": events, "provider": provider, "evidenceIds": [e["id"] for e in events] + ([self.provider_id] if self.provider_id else [])}

    def settlement(self):
        entries = [selected(row, ("id", "type", "amountMinor", "currency", "occurredAt", "reference")) for row in self.snapshot.ledgerEntries]
        captures = [r for r in entries if code(r.get("type")) in {"CAPTURE", "PAYMENT_CAPTURED", "SALE"}]
        refunds = [r for r in entries if code(r.get("type")) in {"REFUND", "REFUND_POSTED"}]
        fees = [r for r in entries if code(r.get("type")) == "FEE"]
        valid_money = all(minor(r.get("amountMinor")) is not None and r.get("currency") == self.snapshot.currency for r in entries)
        valid_money = valid_money and all(r["amountMinor"] > 0 for r in captures) and all(r["amountMinor"] < 0 for r in refunds) and all(r["amountMinor"] <= 0 for r in fees)
        money_issues = [] if valid_money else ["Ledger records require integer amounts in the case currency, positive captures, negative refunds and non-positive fees."]
        capture_minor = sum(abs(r["amountMinor"]) for r in captures) if valid_money else None
        refund_minor = sum(abs(r["amountMinor"]) for r in refunds) if valid_money else None
        ledger_net = sum(r["amountMinor"] for r in entries) if valid_money else None
        provider = self.snapshot.provider or {}
        provider_payout = minor(provider.get("payoutMinor"))
        provider_status = code(provider.get("status"))
        provider_available = provider_payout is not None and bool(provider_status.strip()) and provider_status not in {"UNKNOWN", "UNAVAILABLE"}
        if not provider_available:
            provider_payout = None
        discrepancy = ledger_net - provider_payout if ledger_net is not None and provider_available else None
        authoritative = self.snapshot.reconciliation or {}
        source = "worker-fixture-baseline"
        capture_count = len(captures)
        if authoritative.get("calculatedBy") == "java-api":
            source = "java-api"
            expected = {"captureMinor": capture_minor, "refundMinor": refund_minor, "captureCount": capture_count,
                        "ledgerNetMinor": ledger_net, "providerPayoutMinor": provider_payout,
                        "discrepancyMinor": discrepancy, "providerAvailable": provider_available}
            # Java owns money. Recomputing from the visible immutable snapshot
            # only checks its asserted aggregates; contradictions never cause a
            # silent substitution or a catalog sentence about invalid amounts.
            inconsistent = [key for key, value in expected.items()
                            if key not in authoritative or authoritative[key] != value
                            or (isinstance(value, int) and not isinstance(value, bool) and minor(authoritative[key]) is None)
                            or (isinstance(value, bool) and not isinstance(authoritative[key], bool))]
            if inconsistent:
                money_issues.append("Java reconciliation conflicts with the supplied snapshot for: " + ", ".join(inconsistent) + ". Obtain corrected authoritative reconciliation.")
            if authoritative.get("validMoney") is not True or authoritative.get("currency") != self.snapshot.currency:
                money_issues.append("Java reconciliation must declare valid money in the case currency.")
            capture_minor = minor(authoritative.get("captureMinor"))
            refund_minor = minor(authoritative.get("refundMinor"))
            capture_count = minor(authoritative.get("captureCount"))
            valid_money = valid_money and not money_issues
            ledger_net = minor(authoritative.get("ledgerNetMinor"))
            provider_payout = minor(authoritative.get("providerPayoutMinor"))
            discrepancy = minor(authoritative.get("discrepancyMinor"))
            provider_available = authoritative.get("providerAvailable") is True
        return {"expectedAmountMinor": self.snapshot.amountMinor, "currency": self.snapshot.currency,
                "entries": entries, "captureCount": capture_count, "captureMinor": capture_minor,
                "refundMinor": refund_minor, "validMoney": valid_money, "moneyIssues": money_issues, "calculationSource": source,
                "ledgerNetMinor": ledger_net, "providerPayoutMinor": provider_payout,
                "discrepancyMinor": discrepancy, "providerAvailable": provider_available,
                "reconciliation": authoritative if source == "java-api" else None,
                "provider": selected(provider, ("status", "paymentId", "amountMinor", "feeMinor", "refundMinor", "payoutMinor", "asOf")),
                "evidenceIds": [r["id"] for r in entries] + ([self.provider_id] if self.provider_id else [])}

    def webhook_analysis(self):
        records = [selected(r, ("id", "providerEventId", "type", "occurredAt", "receivedAt", "processingStatus", "providerPaymentId")) for r in self.snapshot.webhooks]
        groups = {}
        for row in records:
            if row.get("providerEventId"):
                groups.setdefault(row["providerEventId"], []).append(row)
        duplicates = []
        for key, rows in groups.items():
            if len(rows) > 1:
                duplicates.append({"providerEventId": key, "deliveryCount": len(rows), "appliedCount": sum(code(row.get("processingStatus")) == "APPLIED" for row in rows), "evidenceIds": [r["id"] for r in rows]})
        timed = [r for r in records if instant(r.get("occurredAt")) is not None and instant(r.get("receivedAt")) is not None]
        ordered = sorted(timed, key=lambda r: instant(r["receivedAt"]))
        inversions = []
        # Receipt ties do not establish an arrival order. Compare each group
        # only with strictly earlier receipt groups, keeping one maximum-time
        # witness per record rather than materializing quadratic pairs.
        witness = None
        index = 0
        while index < len(ordered):
            end = index + 1
            received_at = instant(ordered[index]["receivedAt"])
            while end < len(ordered) and instant(ordered[end]["receivedAt"]) == received_at:
                end += 1
            group = ordered[index:end]
            for row in group:
                if witness is not None and instant(witness["occurredAt"]) > instant(row["occurredAt"]):
                    inversions.append([witness["id"], row["id"]])
            for row in group:
                if witness is None or instant(row["occurredAt"]) > instant(witness["occurredAt"]):
                    witness = row
            index = end
        return {"records": records, "duplicates": duplicates, "orderingInversions": inversions, "timestampsComplete": len(timed) == len(records), "evidenceIds": [r["id"] for r in records]}

    def refunds(self):
        requests = []
        groups = {}
        for row in self.snapshot.events:
            if code(row.get("type")) not in {"REFUND_REQUESTED", "REFUND_APPROVED", "REFUND_SUBMITTED", "REFUND_CONFIRMED"}:
                continue
            attrs = row.get("attributes", {})
            identity = attrs.get("providerRefundId") or attrs.get("idempotencyKey") or row["id"]
            amount = next((minor(attrs[k]) for k in ("refundAmountMinor", "refundMinor", "amountMinor") if k in attrs), None)
            observation = {"id": row["id"], "type": row.get("type"), "amountMinor": amount, "status": row.get("status"), "occurredAt": row.get("occurredAt"), "refundIdentity": identity}
            requests.append(observation)
            groups.setdefault(identity, []).append(observation)
        # Retain every evidence ID while counting a request/confirmation pair only once.
        grouped_amounts = []
        confirmed_amounts = []
        confirmation_ids = []
        for observations in groups.values():
            amounts = {r["amountMinor"] for r in observations if r["amountMinor"] is not None}
            grouped_amounts.append(next(iter(amounts)) if len(amounts) == 1 else None)
            confirmations = [r for r in observations if code(r["type"]) == "REFUND_CONFIRMED" and code(r["status"]) in {"SUCCESS", "SUCCEEDED", "CONFIRMED"}]
            if confirmations:
                confirmation_ids.extend(r["id"] for r in confirmations)
                known = {r["amountMinor"] for r in confirmations if r["amountMinor"] is not None}
                confirmed_amounts.append(next(iter(known)) if len(known) == 1 else None)
        ledger = [r for r in self.snapshot.ledgerEntries if code(r.get("type")) in {"REFUND", "REFUND_POSTED"}]
        settlement = self.settlement()
        ledger_refund = settlement["refundMinor"] if settlement["validMoney"] else None
        return {"requests": requests, "requestedMinor": sum(abs(amount) for amount in grouped_amounts) if grouped_amounts and all(amount is not None for amount in grouped_amounts) else None,
                "confirmedMinor": sum(abs(amount) for amount in confirmed_amounts) if all(amount is not None for amount in confirmed_amounts) else None, "confirmationIds": confirmation_ids,
                "ledgerRefundMinor": ledger_refund,
                "providerRefundMinor": minor((self.snapshot.provider or {}).get("refundMinor")),
                "evidenceIds": [r["id"] for r in requests] + [r["id"] for r in ledger] + ([self.provider_id] if self.provider_id else [])}


def diagnose(outputs: dict[str, dict]) -> dict:
    """Classify only cases that satisfy explicit facts; unresolved conflicts abstain."""
    required = set(TOOL_NAMES)
    missing = [f"Diagnostic tool was not run: {name}" for name in sorted(required-set(outputs))]
    if missing:
        return insufficient(missing)
    timeline = outputs["get_payment_timeline"]
    settlement = outputs["compare_settlement"]
    webhooks = outputs["inspect_webhooks"]
    refunds = outputs["check_refund"]
    provider = timeline["provider"]
    events = timeline["events"]
    if not provider or not provider.get("status") or not provider.get("paymentId"):
        missing.append("Current provider status and provider payment identity are required.")
    if not provider.get("asOf") or instant(provider.get("asOf")) is None:
        missing.append("A timestamped provider status snapshot is required.")
    if not events:
        missing.append("Payment event timeline is unavailable.")
    if not settlement["validMoney"]:
        missing.extend(settlement.get("moneyIssues") or ["Ledger amounts/currencies must be valid integer-minor-unit records."])
    provider_payment_id = provider.get("paymentId")
    referenced_ids = {r.get("attributes", {}).get("providerPaymentId") for r in events if r.get("attributes", {}).get("providerPaymentId")}
    referenced_ids.update(r.get("providerPaymentId") for r in webhooks["records"] if r.get("providerPaymentId"))
    referenced_ids.update(r.get("reference") for r in settlement["entries"] if code(r.get("type")) in {"CAPTURE", "SALE", "PAYMENT_CAPTURED"} and r.get("reference"))
    if provider_payment_id and any(key != provider_payment_id for key in referenced_ids):
        missing.append("Provider payment identities conflict across operational records.")
    provider_asof = instant(provider.get("asOf"))
    known_provider_times = [instant(r.get("occurredAt")) for r in webhooks["records"]]
    if provider_asof is not None and any(t is not None and t > provider_asof for t in known_provider_times):
        missing.append("Provider snapshot predates an observed provider event; obtain a current status.")
    status = code(provider.get("status"))
    succeeded = status in {"SUCCEEDED", "SUCCESS", "CAPTURED", "SETTLED"}
    failed = status in {"FAILED", "FAILURE", "DECLINED", "CANCELLED"}
    # Only explicit processor/payment terminal observations participate. Accepted,
    # authorized, pending and transport/client responses are not final payment facts.
    terminal_types = {"PROCESSOR_CONFIRMED", "PROCESSOR_REJECTED", "PROCESSOR_STATUS", "PROVIDER_STATUS", "PAYMENT_STATUS", "PAYMENT_SUCCEEDED", "PAYMENT_CAPTURED", "PAYMENT_SETTLED", "PAYMENT_FAILED", "PAYMENT_DECLINED", "PAYMENT_CANCELLED"}
    terminal_events = [r for r in events if code(r.get("type")) in terminal_types]
    successful_events = [r["id"] for r in terminal_events if code(r.get("status")) in {"SUCCEEDED", "SUCCESS", "CAPTURED", "SETTLED"}]
    failed_events = [r["id"] for r in terminal_events if code(r.get("status")) in {"FAILED", "FAILURE", "DECLINED", "CANCELLED"}]
    if (successful_events and failed_events) or (succeeded and failed_events) or (failed and successful_events):
        ids = ", ".join(successful_events + failed_events)
        missing.append(f"Contradictory terminal processor evidence ({ids}) must be reconciled with the provider status before concluding the payment outcome.")
    captures = settlement["captureMinor"]
    expected = settlement["expectedAmountMinor"]
    if succeeded and (captures != expected or settlement["captureCount"] != 1 or minor(provider.get("amountMinor")) != expected):
        missing.append("Successful provider amount and exactly one ledger capture must reconcile to the requested amount.")
    if failed and captures:
        missing.append("Provider failure conflicts with a posted ledger capture.")
    if any(group["appliedCount"] > 1 for group in webhooks["duplicates"]):
        missing.append("The same provider event was applied more than once; duplicate financial effects require review.")
    webhook_records = {row["id"]: row for row in webhooks["records"]}
    if succeeded:
        for group in webhooks["duplicates"]:
            statuses = [code(webhook_records[eid].get("processingStatus")) for eid in group["evidenceIds"]]
            if statuses.count("APPLIED") != 1 or any(status not in {"APPLIED", "IGNORED_DUPLICATE"} for status in statuses):
                missing.append("Obtain final duplicate-delivery processing evidence showing exactly one APPLIED record and every remaining delivery IGNORED_DUPLICATE before resolving the case.")
                break
        if any(code(webhook_records[late_id].get("processingStatus")) != "IGNORED_STALE" for _, late_id in webhooks["orderingInversions"]):
            missing.append("Obtain evidence that each earlier-occurring event delivered late was IGNORED_STALE; pending, applied or unknown handling does not establish safe ordering resolution.")
    if missing:
        return insufficient(missing)
    candidates = []
    timeout = [r for r in events if "TIMEOUT" in code(r.get("type")) or code(r.get("status")) in {"TIMEOUT", "TIMED_OUT"} or code(r.get("attributes", {}).get("errorCode")) in {"TIMEOUT", "ETIMEDOUT", "GATEWAY_TIMEOUT"}]
    if timeout and succeeded:
        candidates.append({"outcome": "TIMEOUT_AFTER_SUCCESS", "summary": "The timeline records a timeout, while the provider reports success and one matching ledger capture is present. Do not initiate another payment from this evidence.", "evidenceIds": [r["id"] for r in timeout] + settlement["evidenceIds"], "query": "transport timeout succeeded successful capture idempotency retry", "action": "RESOLVE_CASE", "reason": "Record the observed successful processing and resolve the investigation without moving funds."})
    duplicates = [g for g in webhooks["duplicates"] if g["appliedCount"] == 1]
    if duplicates and succeeded:
        candidates.append({"outcome": "DUPLICATE_WEBHOOK", "summary": "Multiple deliveries share a provider event identity, with exactly one applied delivery and one matching ledger capture. The evidence supports duplicate delivery without a second observed capture.", "evidenceIds": [eid for g in duplicates for eid in g["evidenceIds"]] + settlement["evidenceIds"], "query": "duplicate webhook repeated delivery providerEventId exactly once idempotency", "action": "RESOLVE_CASE", "reason": "Record the duplicate notification and preserve the existing payment state; no financial command is proposed."})
    if webhooks["orderingInversions"] and succeeded:
        candidates.append({"outcome": "OUT_OF_ORDER_WEBHOOK", "summary": "Webhook receipt order differs from provider occurrence order. The provider success snapshot and matching ledger capture establish the observed final payment state.", "evidenceIds": list(dict.fromkeys(eid for pair in webhooks["orderingInversions"] for eid in pair)) + settlement["evidenceIds"], "query": "out of order webhook late stale terminal state event timestamp", "action": "RESOLVE_CASE", "reason": "Resolve the ordering investigation using the verified current state without replaying financial events."})
    refund_discrepancy = refunds["providerRefundMinor"] is not None and refunds["ledgerRefundMinor"] is not None and refunds["providerRefundMinor"] != refunds["ledgerRefundMinor"]
    if refunds["requests"] or refund_discrepancy:
        requested = refunds["requestedMinor"]
        if refunds["providerRefundMinor"] is None or refunds["ledgerRefundMinor"] is None or refunds["confirmedMinor"] is None:
            return insufficient(["Confirmed refund amounts and both provider/ledger refund amounts are required."])
        confirmed = refunds["confirmedMinor"]
        confirmation_proved = confirmed > 0 or refunds["providerRefundMinor"] > 0
        if confirmation_proved and (refund_discrepancy or confirmed > refunds["ledgerRefundMinor"] or confirmed > refunds["providerRefundMinor"]):
            currency = settlement["currency"]
            event_amount = display_money(confirmed, currency) if refunds["confirmationIds"] else "unavailable"
            candidates.append({"outcome": "MISSING_REFUND", "summary": f"The confirmed refund has not reconciled with the ledger: confirmation events {event_amount}, ledger {display_money(refunds['ledgerRefundMinor'], currency)}, provider {display_money(refunds['providerRefundMinor'], currency)}. Investigate the missing or conflicting posting before any refund is retried.", "evidenceIds": refunds["evidenceIds"], "query": "refund confirmed requested missing ledger provider reconciliation refund amount", "action": "ESCALATE", "reason": "Escalate the confirmed refund discrepancy for independent review; this application does not issue refunds."})
        elif not confirmation_proved:
            return insufficient(["A refund request alone does not prove completion. Obtain provider refund confirmation before concluding a ledger posting is missing."])
    if failed and captures == 0:
        candidates.append({"outcome": "PROVIDER_FAILURE", "summary": "The timestamped provider snapshot reports failure and no ledger capture is recorded. The payment requires operational follow-up; automatic payment retries are outside this investigation.", "evidenceIds": timeline["evidenceIds"], "query": "provider failed declined no capture failure escalation", "action": "ESCALATE", "reason": "Escalate the observed provider failure with its evidence; no retry or money movement is proposed."})
    if len(candidates) != 1:
        if candidates:
            return insufficient(["Multiple independently observed anomalies require human investigation."])
        # UNKNOWN is an observed lack of current status, not evidence of a
        # payment failure or a missing posting. Ask for concrete checks without
        # claiming records exist outside this authorized snapshot.
        if status in {"UNKNOWN", "UNAVAILABLE"}:
            requests = [f"Obtain an authoritative processor status for the original payment reference, with its observation timestamp; the supplied provider state is {status}."]
            if not settlement["entries"]:
                requests.append("Confirm whether the empty ledger snapshot is complete for this payment through a stated cutoff; supply any capture, fee or refund records found.")
            if not webhooks["records"]:
                requests.append("Check provider delivery history for the original payment reference; supply any events found, including occurrence time, receipt time and processing status, or confirm that none were delivered.")
            return insufficient(requests)
        return insufficient(["Available records do not establish one of the supported exception patterns."])
    candidate = candidates[0]
    if candidate["action"] == "RESOLVE_CASE":
        if not settlement["providerAvailable"] or any(settlement[key] is None for key in ("ledgerNetMinor", "providerPayoutMinor", "discrepancyMinor")):
            return insufficient(["A current provider payout and complete ledger-to-payout reconciliation are required before this case can be resolved."])
        if settlement["discrepancyMinor"] != 0:
            currency = settlement["currency"]
            mismatch = (f"The ledger net is {display_money(settlement['ledgerNetMinor'], currency)} but the provider payout is "
                        f"{display_money(settlement['providerPayoutMinor'], currency)}, leaving a ledger-minus-payout discrepancy of "
                        f"{display_money(settlement['discrepancyMinor'], currency)}. Reconcile this mismatch before resolving the case.")
            unresolved = insufficient([mismatch])
            unresolved["summary"] = mismatch
            unresolved["evidenceIds"] = list(dict.fromkeys(settlement["evidenceIds"]))
            return unresolved
    candidate["evidenceIds"] = list(dict.fromkeys(candidate["evidenceIds"]))
    candidate["missingEvidence"] = []
    return candidate


def insufficient(missing):
    return {"outcome": "INSUFFICIENT_EVIDENCE", "summary": "The available evidence does not support a reliable conclusion. Obtain or reconcile the missing facts before changing case disposition.", "evidenceIds": [], "query": "insufficient evidence missing conflicting provider ledger reconciliation escalation", "action": "REQUEST_EVIDENCE", "reason": "Request the listed evidence and keep financial state unchanged.", "missingEvidence": missing}
