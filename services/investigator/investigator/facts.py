"""Scope-explicit catalog facts; the model selects IDs and never writes prose."""
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json

from .errors import InvalidModelResult
from .evidence import code, display_money, instant

FACT_CATALOG_VERSION = 1


def canonical_hash(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


def timestamp(value):
    """Render only an actual timezone-aware timestamp, never a raw record string."""
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            return None
        return parsed.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
    except (TypeError, ValueError, OverflowError):
        return None


def build_fact_catalog(outputs, citations):
    """Only typed observed data controls catalog content, never scenario labels.

    sourceTools identifies the immutable snapshot reads behind aggregate facts.
    Their real record links cannot independently prove an absent record; the
    complete source-tool output establishes the observed snapshot membership.
    """
    if not citations:
        return []
    timeline = outputs["get_payment_timeline"]
    settlement = outputs["compare_settlement"]
    webhooks = outputs["inspect_webhooks"]
    refunds = outputs["check_refund"]
    currency = settlement["currency"]
    money = lambda amount: display_money(amount, currency)
    catalog = []
    allowed = {i for output in outputs.values() for i in output["evidenceIds"]}
    provider_ids = [i for i in timeline["evidenceIds"] if i.startswith("PROVIDER:")]

    def add(identifier, text, evidence_ids, source_tools):
        ids = sorted(set(evidence_ids))
        if not ids or not set(ids).issubset(allowed):
            return
        catalog.append({"id": identifier, "text": text, "evidenceIds": ids,
                        "citationIds": [citations[0]["id"]], "sourceTools": source_tools})

    provider_status = code(timeline["provider"].get("status"))
    provider_asof = timestamp(timeline["provider"].get("asOf"))
    if provider_status in {"SUCCEEDED", "SUCCESS", "CAPTURED", "SETTLED", "FAILED", "FAILURE", "DECLINED", "CANCELLED"} and provider_asof:
        add("FACT-PROVIDER-STATUS", f"The provider reported {provider_status} as of {provider_asof}; this is a status observation time.", provider_ids, ["get_payment_timeline"])

    if settlement["validMoney"] and settlement["captureMinor"] is not None and settlement["captureCount"] is not None:
        entry_word = "entry" if settlement["captureCount"] == 1 else "entries"
        add("FACT-CAPTURE-LEDGER", f"The supplied ledger snapshot contains {settlement['captureCount']} capture {entry_word} totaling {money(settlement['captureMinor'])}.", settlement["evidenceIds"], ["compare_settlement"])
    if settlement["validMoney"] and settlement["providerAvailable"] and all(settlement[key] is not None for key in ("ledgerNetMinor", "providerPayoutMinor", "discrepancyMinor")):
        add("FACT-SETTLEMENT", f"The supplied ledger net is {money(settlement['ledgerNetMinor'])}; provider payout is {money(settlement['providerPayoutMinor'])}; ledger-minus-payout difference is {money(settlement['discrepancyMinor'])}.", settlement["evidenceIds"], ["compare_settlement"])

    timeouts = [r for r in timeline["events"] if "TIMEOUT" in code(r.get("type")) or code(r.get("status")) in {"TIMEOUT", "TIMED_OUT"} or code(r.get("attributes", {}).get("errorCode")) in {"TIMEOUT", "ETIMEDOUT", "GATEWAY_TIMEOUT"}]
    if timeouts:
        observed_time = timestamp(timeouts[0].get("occurredAt"))
        at = f" at {observed_time}" if observed_time else ""
        add("FACT-TIMEOUT-OBSERVATION", f"The supplied timeline records a timeout observation{at}; a timeout alone does not establish the payment outcome.", [r["id"] for r in timeouts], ["get_payment_timeline"])

    confirmations = [r for r in timeline["events"] if code(r.get("type")) in {"PROCESSOR_CONFIRMED", "PAYMENT_SUCCEEDED", "PAYMENT_CAPTURED", "PAYMENT_SETTLED"} and code(r.get("status")) in {"SUCCEEDED", "SUCCESS", "CAPTURED", "SETTLED"}]
    if confirmations and timestamp(confirmations[0].get("occurredAt")):
        add("FACT-SUCCESS-EVENT", f"The supplied timeline records a successful processor/payment event at {timestamp(confirmations[0]['occurredAt'])}; this is event occurrence time.", [confirmations[0]["id"]], ["get_payment_timeline"])

    if webhooks["duplicates"]:
        group = webhooks["duplicates"][0]
        records = [r for r in webhooks["records"] if r["id"] in group["evidenceIds"]]
        counts = Counter(code(r.get("processingStatus")) for r in records)
        if counts and set(counts).issubset({"APPLIED", "IGNORED_DUPLICATE", "IGNORED_STALE"}):
            statuses = ", ".join(f"{count} {status}" for status, count in sorted(counts.items()))
            add("FACT-REPEATED-DELIVERY", f"One provider event has {len(records)} delivery records in the supplied webhook snapshot: {statuses}. Delivery records are separate from financial events.", [r["id"] for r in records], ["inspect_webhooks"])

    records_by_id = {r["id"]: r for r in webhooks["records"]}
    for first_id, second_id in webhooks["orderingInversions"]:
        first, second = records_by_id[first_id], records_by_id[second_id]
        values = [instant(r.get(key)) for r in (first, second) for key in ("occurredAt", "receivedAt")]
        if None not in values and values[0] > values[2] and values[1] < values[3]:
            text = (f"The earlier provider event occurred at {timestamp(second['occurredAt'])}, and its webhook was received at {timestamp(second['receivedAt'])}. "
                    f"The later provider event occurred at {timestamp(first['occurredAt'])}, and its webhook was received at {timestamp(first['receivedAt'])}.")
            add("FACT-WEBHOOK-TIMING", text, [first_id, second_id], ["inspect_webhooks"])
            break

    if refunds["requests"] or (refunds["providerRefundMinor"] or 0) > 0:
        entries = [r for r in settlement["entries"] if code(r.get("type")) in {"REFUND", "REFUND_POSTED"}]
        if refunds["ledgerRefundMinor"] is not None:
            if refunds["confirmationIds"] and refunds["confirmedMinor"] is not None:
                confirmation = f"Confirmed refund events total {money(refunds['confirmedMinor'])}."
            else:
                confirmation = "No refund confirmation event is present in the supplied timeline."
            entry_word = "entry" if len(entries) == 1 else "entries"
            text = f"{confirmation} The supplied ledger snapshot contains {len(entries)} refund {entry_word} totaling {money(refunds['ledgerRefundMinor'])}."
            if refunds["providerRefundMinor"] is not None:
                text += f" The provider refund amount is {money(refunds['providerRefundMinor'])}."
            add("FACT-REFUND-OBSERVATIONS", text, refunds["evidenceIds"] + settlement["evidenceIds"], ["check_refund", "compare_settlement"])

    rejected = [r for r in timeline["events"] if code(r.get("type")) in {"PROCESSOR_REJECTED", "PAYMENT_FAILED", "PAYMENT_DECLINED", "PAYMENT_CANCELLED"} and code(r.get("status")) in {"FAILED", "FAILURE", "DECLINED", "CANCELLED"}]
    if rejected:
        at = timestamp(rejected[0].get("occurredAt"))
        time_clause = f" at {at}" if at else ""
        terminal_status = code(rejected[0]["status"])
        add("FACT-TERMINAL-EVENT", f"The supplied timeline records a terminal processor/payment event with status {terminal_status}{time_clause}.", [rejected[0]["id"]], ["get_payment_timeline"])
    return catalog


def render_selected_facts(catalog, selected_ids):
    """No prose repair: there is no model-authored prose in this contract."""
    if not 1 <= len(selected_ids) <= 2 or len(set(selected_ids)) != len(selected_ids):
        raise InvalidModelResult("Select one or two distinct authorized fact IDs.")
    facts = {fact["id"]: fact for fact in catalog}
    if any(identifier not in facts for identifier in selected_ids):
        raise InvalidModelResult("Selected fact is absent from the authorized catalog.")
    selected = [facts[identifier] for identifier in selected_ids]
    return {"id": "F-1", "text": " ".join(fact["text"] for fact in selected),
            "evidenceIds": sorted({identifier for fact in selected for identifier in fact["evidenceIds"]}),
            "citationIds": sorted({identifier for fact in selected for identifier in fact["citationIds"]})}
