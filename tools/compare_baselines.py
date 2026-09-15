"""Offline retrieval ablation over the complete frozen test split.

Rules, tools and lexical retrieval come from the worker. Labels are consumed only
by this scoring harness, never supplied to those functions. No HTTP, LLM,
embedding, database or checkpoint invocation is permitted by this program.
"""

import argparse
import hashlib
import importlib.metadata
import json
import os
import platform
import statistics
import sys
import time
from collections import Counter
from datetime import date, datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKER = ROOT / "services/investigator"
LABEL_PATH = ROOT / "data/evaluation/labels.json"
BASELINES = ("rules_only", "fixed_tools_lexical_rag")
SOURCE_PATHS = [
    "tools/compare_baselines.py",
    "services/investigator/investigator/evidence.py",
    "services/investigator/investigator/models.py",
    "services/investigator/investigator/retrieval.py",
    "services/investigator/investigator/config.py",
    "services/investigator/investigator/graph.py",
]
GUARD = {"runningBaseline": False, "networkAttempts": 0, "labelReadAttempts": 0}


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def file_hash(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def offline_guard(event, arguments):
    if event in {"socket.connect", "socket.connect_ex", "socket.getaddrinfo"}:
        GUARD["networkAttempts"] += 1
        raise RuntimeError("Offline baseline comparator prohibits network connections.")
    if GUARD["runningBaseline"] and event == "open" and isinstance(arguments[0], (str, bytes, os.PathLike)):
        path = Path(os.fsdecode(arguments[0])).resolve()
        if path == LABEL_PATH.resolve():
            GUARD["labelReadAttempts"] += 1
            raise RuntimeError("Baseline functions may not read the scoring labels.")


def keep(record, fields):
    return {key: record[key] for key in fields if key in record}


def operational_input(record):
    """Remove UI prose/tags and preserve only fields used by snapshot tools."""
    value = keep(record, ("id", "tenantId", "paymentId", "amountMinor", "currency", "policyDate"))
    value["events"] = []
    for event in record["events"]:
        selected = keep(event, ("id", "occurredAt", "type", "source", "status", "correlationId"))
        selected["attributes"] = keep(event.get("attributes", {}), (
            "idempotencyKey", "providerPaymentId", "providerRefundId", "amountMinor",
            "refundMinor", "refundAmountMinor", "errorCode", "reasonCode",
        ))
        value["events"].append(selected)
    value["ledgerEntries"] = [keep(row, ("id", "type", "amountMinor", "currency", "occurredAt", "reference")) for row in record["ledgerEntries"]]
    value["webhooks"] = [keep(row, ("id", "providerEventId", "type", "occurredAt", "receivedAt", "processingStatus", "providerPaymentId")) for row in record["webhooks"]]
    provider = record.get("provider")
    value["provider"] = keep(provider, ("status", "paymentId", "amountMinor", "feeMinor", "refundMinor", "payoutMinor", "asOf", "errorCode")) if provider else None
    return value


def run_baseline(name, payload, knowledge, modules):
    snapshot_type, tool_type, tool_names, diagnose, insufficient = modules
    started = time.perf_counter()
    GUARD["runningBaseline"] = True
    try:
        snapshot = snapshot_type.model_validate(payload)
        tools = tool_type(snapshot)
        for tool_name in tool_names:
            tools.tools[tool_name].invoke({"caseId": snapshot.id})
        rule_assessment = diagnose(tools.outputs)
        assessment = rule_assessment
        retrieval_started = time.perf_counter()
        citations = []
        if name == "fixed_tools_lexical_rag":
            citations = knowledge.retrieve(rule_assessment["query"], snapshot.tenantId, snapshot.policyDate, 3)
            # Match the worker graph's explicit policy-availability gate.
            if not citations and assessment["outcome"] != "INSUFFICIENT_EVIDENCE":
                assessment = insufficient(["No applicable authorized operating guidance was retrieved for this policy date."])
        retrieval_ms = (time.perf_counter() - retrieval_started) * 1000 if name != "rules_only" else 0.0
        finding_citations = [citation["id"] for citation in citations[:1]] if assessment["evidenceIds"] else []
        result = {
            "outcome": assessment["outcome"], "action": assessment["action"],
            "confidence": "INSUFFICIENT" if assessment["outcome"] == "INSUFFICIENT_EVIDENCE" else "HIGH",
            "summary": assessment["summary"], "reason": assessment["reason"],
            "missingEvidence": assessment["missingEvidence"], "evidenceIds": assessment["evidenceIds"],
            "retrievalQuery": rule_assessment["query"] if name != "rules_only" else None,
            "citations": citations, "findingCitationIds": finding_citations,
            "rawRuleOutcome": rule_assessment["outcome"], "rawRuleAction": rule_assessment["action"],
            "policyGateChangedDecision": assessment is not rule_assessment,
            "toolNames": [call["name"] for call in tools.calls],
            "calculationSource": tools.outputs["compare_settlement"]["calculationSource"],
            "modelCalls": 0, "embeddingCalls": 0,
        }
        return {"result": result, "semanticResultSha256": digest(result), "wallMs": round((time.perf_counter() - started) * 1000, 3), "retrievalMs": round(retrieval_ms, 3), "error": None}
    except Exception as error:
        return {"result": None, "semanticResultSha256": None, "wallMs": round((time.perf_counter() - started) * 1000, 3), "retrievalMs": None, "error": {"type": type(error).__name__, "message": str(error)[:500]}}
    finally:
        GUARD["runningBaseline"] = False


def score(row, label, payload, runbooks):
    result = row["result"]
    row.update(caseId=label["caseId"], operationalInputSha256=digest(payload), expectedOutcome=label["expectedOutcome"], expectedAction=label["expectedAction"], expectedAbstention=label["expectedAbstention"], expectedRelevantCitations=label["relevantCitations"])
    if result is None:
        row["scores"] = {name: False for name in ("outcomeCorrect", "actionCorrect", "abstentionCorrect", "evidenceIdsValid", "relevantPolicyHitAt1", "relevantPolicyHitAt3")}
        row["scores"].update(eligibleCitations=None, relevantFindingCitation=None, firstRelevantRank=None, policyContextProvided=False, nonemptyEvidence=False)
        return row
    expected = set(label["relevantCitations"])
    citations = result["citations"]
    ids = [citation["id"] for citation in citations]
    allowed_evidence = {event["id"] for group in ("events", "ledgerEntries", "webhooks") for event in payload[group]}
    if payload.get("provider"):
        allowed_evidence.add("PROVIDER:" + payload["provider"].get("paymentId", payload["paymentId"]))
    policy_date = date.fromisoformat(payload["policyDate"])
    eligible_ids = {
        f"{book['id']}:v{book['version']}"
        for book in runbooks
        if book["tenantId"] in ("*", payload["tenantId"])
        and date.fromisoformat(book["effectiveFrom"]) <= policy_date
        and (book.get("effectiveTo") is None or policy_date < date.fromisoformat(book["effectiveTo"]))
    }
    row["scores"] = {
        "outcomeCorrect": result["outcome"] == label["expectedOutcome"],
        "actionCorrect": result["action"] == label["expectedAction"],
        "abstentionCorrect": (result["confidence"] == "INSUFFICIENT") == label["expectedAbstention"],
        "nonemptyEvidence": bool(result["evidenceIds"]),
        "evidenceIdsValid": set(result["evidenceIds"]) <= allowed_evidence if result["evidenceIds"] else None,
        "policyContextProvided": bool(ids),
        "relevantPolicyHitAt1": bool(expected.intersection(ids[:1])),
        "relevantPolicyHitAt3": bool(expected.intersection(ids[:3])),
        "firstRelevantRank": next((rank for rank, citation in enumerate(ids, 1) if citation in expected), None),
        "eligibleCitations": set(ids) <= eligible_ids if ids else None,
        "relevantFindingCitation": bool(expected.intersection(result["findingCitationIds"])) if result["evidenceIds"] else None,
    }
    return row


def ratio(rows, key):
    values = [row["scores"][key] for row in rows if row["scores"].get(key) is not None]
    return {"passed": sum(bool(value) for value in values), "denominator": len(values), "rate": round(sum(bool(value) for value in values) / len(values), 6) if values else None}


def summarize(rows):
    ranks = [row["scores"].get("firstRelevantRank") for row in rows]
    return {
        "cases": len(rows), "failures": sum(row["error"] is not None for row in rows),
        "metrics": {key: ratio(rows, key) for key in ("outcomeCorrect", "actionCorrect", "abstentionCorrect", "nonemptyEvidence", "evidenceIdsValid", "policyContextProvided", "relevantPolicyHitAt1", "relevantPolicyHitAt3", "eligibleCitations", "relevantFindingCitation")},
        "meanReciprocalRelevantRankAt3": round(sum(1 / rank if rank else 0 for rank in ranks) / len(rows), 6),
        "policyGateChanges": sum(bool((row["result"] or {}).get("policyGateChangedDecision")) for row in rows),
        "modelCalls": 0, "embeddingCalls": 0,
        "localTimingMs": {"median": round(statistics.median(row["wallMs"] for row in rows), 3), "minimum": min(row["wallMs"] for row in rows), "maximum": max(row["wallMs"] for row in rows), "retrievalMedian": round(statistics.median(row["retrievalMs"] for row in rows if row["retrievalMs"] is not None), 3) if any(row["retrievalMs"] is not None for row in rows) else None},
    }


def saved_context(path, labels, manifest_hash):
    saved = json.loads(path.read_text(encoding="utf-8-sig"))
    rows = saved.get("rows", [])
    required_ids = {label["caseId"] for label in labels}
    ids = [row.get("caseId") for row in rows]
    if saved.get("split") != "test" or saved.get("datasetManifestSha256") != manifest_hash:
        raise ValueError("Saved evaluator report must use this frozen test split and manifest hash.")
    if len(ids) != len(set(ids)) or set(ids) != required_ids or saved.get("sampleSize") != 30:
        raise ValueError("Saved comparison requires exactly the same 30 unique test cases; partial/cherry-picked reports are rejected.")
    lookup = {label["caseId"]: label for label in labels}
    return {
        "comparisonType": "historical_context_only", "sourcePath": str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path),
        "sourceSha256": file_hash(path), "timestamp": saved.get("timestamp"), "mode": saved.get("mode"),
        "sameManifestAndFullCaseCoverage": True, "exactOperationalInputHashesAvailable": False,
        "outcomeCorrectRescored": {"passed": sum(row.get("outcome") == lookup[row["caseId"]]["expectedOutcome"] and not row.get("error") for row in rows), "denominator": len(rows)},
        "actionCorrectRescored": {"passed": sum(row.get("action") == lookup[row["caseId"]]["expectedAction"] and not row.get("error") for row in rows), "denominator": len(rows)},
        "previouslyScoredRelevantCitationHits": {"passed": sum(row.get("relevantCitationRetrieved") is True and not row.get("error") for row in rows), "denominator": len(rows)},
        "previouslyRecordedModelCalls": saved.get("modelCalls"), "callsMadeByThisComparator": 0,
        "previouslyRecordedFailures": saved.get("failures"),
        "limitations": ["This archived harness format does not include exact sanitized input hashes, raw citations, confidence or full generated prose; those aspects cannot be reverified here.", "Citation flags are reproduced from the saved harness, while outcome/action are rescored from saved predictions.", "Saved HTTP/graph timings and historical source versions are not a controlled comparison with direct-module timings.", "Saved replay remains deterministic; do not call replay-versus-replay agreement an AI uplift."],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, default=ROOT / "docs/validation/baselines-test.json")
    parser.add_argument("--saved-report", type=Path, help="Optional complete tools/evaluate.py report for historical context only; never triggers model calls.")
    parser.add_argument("--verify-repeat", action="store_true", help="Repeat deterministic functions and compare semantic hashes, excluding timings.")
    args = parser.parse_args()
    # Disable optional tracing in this child process before importing LangChain.
    for key in ("LANGSMITH_TRACING", "LANGCHAIN_TRACING", "LANGCHAIN_TRACING_V2"):
        os.environ[key] = "false"
    sys.addaudithook(offline_guard)
    sys.path.insert(0, str(WORKER))
    from investigator.config import Settings
    from investigator.evidence import SnapshotTools, TOOL_NAMES, diagnose, insufficient
    from investigator.models import CaseSnapshot
    from investigator.retrieval import KnowledgeStore

    manifest_path = ROOT / "data/manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    for relative, expected in manifest["files"].items():
        if file_hash(ROOT / relative) != expected:
            raise ValueError(f"Frozen manifest mismatch: {relative}")
    manifest_hash = file_hash(manifest_path)
    source_before = {path: file_hash(ROOT / path) for path in SOURCE_PATHS}
    cases = {case["id"]: case for case in json.loads((ROOT / "data/fixtures/cases.json").read_text(encoding="utf-8-sig"))}
    labels = sorted((row for row in json.loads(LABEL_PATH.read_text(encoding="utf-8-sig")) if row["split"] == "test"), key=lambda row: row["caseId"])
    if len(labels) != 30 or len({row["caseId"] for row in labels}) != 30:
        raise ValueError("This comparator requires the complete frozen 30-case test split.")
    runbooks = json.loads((ROOT / "data/knowledge/runbooks.json").read_text(encoding="utf-8-sig"))
    inputs = {label["caseId"]: operational_input(cases[label["caseId"]]) for label in labels}
    settings = Settings(service_key="unused-offline", knowledge_path=ROOT / "data/knowledge/runbooks.json", checkpoint_path=ROOT / "services/investigator/runtime/unused-offline-baseline.sqlite", retrieval_mode="lexical", vector_db_url=None, ollama_base_url="http://127.0.0.1:1")
    knowledge = KnowledgeStore(settings)
    modules = (CaseSnapshot, SnapshotTools, TOOL_NAMES, diagnose, insufficient)
    results = {name: [] for name in BASELINES}
    for index, label in enumerate(labels):
        # Alternate execution order to reduce systematic first-run/caching bias.
        for name in BASELINES if index % 2 == 0 else reversed(BASELINES):
            row = run_baseline(name, inputs[label["caseId"]], knowledge, modules)
            results[name].append(score(row, label, inputs[label["caseId"]], runbooks))
    repeated = {"requested": args.verify_repeat, "comparisons": 0, "mismatches": []}
    if args.verify_repeat:
        for name, rows in results.items():
            for row in rows:
                rerun = run_baseline(name, inputs[row["caseId"]], knowledge, modules)
                repeated["comparisons"] += 1
                if row["error"] or rerun["error"] or row["semanticResultSha256"] != rerun["semanticResultSha256"]:
                    repeated["mismatches"].append({"baseline": name, "caseId": row["caseId"]})
    paired = []
    for left, right in zip(results[BASELINES[0]], results[BASELINES[1]]):
        assert left["caseId"] == right["caseId"] and left["operationalInputSha256"] == right["operationalInputSha256"]
        left_result, right_result = left["result"] or {}, right["result"] or {}
        paired.append({"caseId": left["caseId"], "sameOperationalInput": True, "rawRuleDecisionEqual": bool(left_result and right_result) and (left_result["rawRuleOutcome"], left_result["rawRuleAction"]) == (right_result["rawRuleOutcome"], right_result["rawRuleAction"]), "finalDecisionEqual": bool(left_result and right_result) and (left_result["outcome"], left_result["action"]) == (right_result["outcome"], right_result["action"]), "relevantPolicyAdded": not left["scores"]["relevantPolicyHitAt3"] and right["scores"]["relevantPolicyHitAt3"]})
    source_after = {path: file_hash(ROOT / path) for path in SOURCE_PATHS}
    source_stable = source_before == source_after
    families = sorted({label["expectedOutcome"] for label in labels})
    report = {
        "status": "PASS" if source_stable and not repeated["mismatches"] and not any(row["error"] for rows in results.values() for row in rows) and GUARD["networkAttempts"] == 0 and GUARD["labelReadAttempts"] == 0 else "CHECK_FAILURES",
        "timestamp": datetime.now(timezone.utc).isoformat(), "split": "test", "sampleSize": len(labels),
        "method": "Paired retrieval ablation: identical fixed snapshot tools and deterministic diagnose rules; lexical arm additionally retrieves up to 3 eligible policies and applies the existing no-policy abstention gate.",
        "runtime": {"python": platform.python_version(), "platform": platform.system(), "packages": {name: importlib.metadata.version(name) for name in ("langchain-core", "langgraph", "langchain-ollama")}},
        "provenance": {"datasetManifestSha256": manifest_hash, "datasetFiles": manifest["files"], "sourceFilesSha256": source_before, "sourceFilesStableDuringRun": source_stable, "sourceFilesAfterRun": source_after if not source_stable else None, "operationalInputSetSha256": digest(inputs), "caseIds": list(inputs), "testCasesByExpectedOutcome": dict(Counter(label["expectedOutcome"] for label in labels)), "testCasesByTenant": dict(Counter(inputs[label["caseId"]]["tenantId"] for label in labels))},
        "isolation": {"modelCalls": 0, "embeddingCalls": 0, "networkAttempts": GUARD["networkAttempts"], "baselineLabelFileReadAttempts": GUARD["labelReadAttempts"], "databaseUsed": False, "checkpointUsed": False, "labelsPassedToWorkerFunctions": False, "settingsFromEnvironment": False, "javaApiBypassed": True, "calculationSource": "worker-fixture-baseline"},
        "repeatability": repeated,
        "baselines": {name: {"summary": summarize(rows), "byExpectedOutcome": {family: summarize([row for row in rows if row["expectedOutcome"] == family]) for family in families}, "rows": rows} for name, rows in results.items()},
        "paired": {"rawRuleDecisionsEqual": sum(row["rawRuleDecisionEqual"] for row in paired), "finalDecisionsEqual": sum(row["finalDecisionEqual"] for row in paired), "relevantPolicyAdded": sum(row["relevantPolicyAdded"] for row in paired), "denominator": len(paired), "rows": paired},
        "savedReportContext": saved_context(args.saved_report.resolve(), labels, manifest_hash) if args.saved_report else None,
        "limitations": [
            "No baseline invokes a generative model or adaptive tool planner. Rules-only and lexical arms share the same engineered decision rules; equal decisions cannot establish AI uplift.",
            "The retrieval arm reproduces the graph's no-policy abstention gate; retrieval can therefore restrict a decision, but it cannot improve the underlying classifier in this ablation.",
            "Rules-only has zero retrieved policies by construction; that is an ablation setting, not a misleading claim of retrieval failures. It is an offline classifier and would not satisfy Java's grounded-resolution proposal requirement.",
            "The 30 test cases are held-out template variants, with five cases per engineered outcome; generator, rules, query templates, runbooks and labels share domain assumptions. This is not independent real-world generalization evidence.",
            "Relevant-citation labels identify required supporting policy versions, not every possibly useful policy; report hit/rank, not precision or recall over an assumed exhaustive relevance set.",
            "Eligible citation IDs and expected policy hits do not establish sentence-level entailment, hallucination rates or explanatory quality. Deterministic template summaries are used in both arms.",
            "The replay template links only its first retrieved citation when evidence exists. Relevant finding citation therefore has a separate nonempty-evidence denominator; abstentions may correctly have no finding.",
            "Direct module execution bypasses Java sessions, authoritative money calculations, HTTP, persistence and reviewer enforcement; those have separate acceptance tests.",
            "One alternating-order local timing sample per case is descriptive; it is not an HTTP/load benchmark or a valid speed comparison against historical graph/model runs.",
        ],
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "report": str(args.report), "sampleSize": len(labels), "baselines": {name: entry["summary"] for name, entry in report["baselines"].items()}, "paired": {key: value for key, value in report["paired"].items() if key != "rows"}, "repeatability": repeated, "isolation": report["isolation"]}, indent=2))
    if report["status"] != "PASS":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
