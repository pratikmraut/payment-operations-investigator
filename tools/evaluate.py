"""Evaluate a frozen fixture split through the worker HTTP API; truth stays in this process."""

import argparse, hashlib, json, os, statistics, time, urllib.error, urllib.request, uuid
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def post(base, path, body, key):
    request = urllib.request.Request(
        base + path,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "X-Service-Key": key},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=430) as response:
        return json.load(response)


def percentile(values, p):
    ordered = sorted(values)
    return (
        ordered[min(len(ordered) - 1, max(0, int((len(ordered) - 1) * p)))]
        if ordered
        else None
    )


def main():
    started_at = datetime.now(timezone.utc).isoformat()
    parser = argparse.ArgumentParser()
    parser.add_argument("--worker-url", default="http://127.0.0.1:8091")
    parser.add_argument("--split", choices=["development", "test"], default="test")
    parser.add_argument("--mode", choices=["replay", "ollama"], default="replay")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument(
        "--case-id",
        action="append",
        default=[],
        help="Select an explicit development case (repeatable); diagnostic selection is not held-out evidence.",
    )
    parser.add_argument(
        "--representative-development",
        action="store_true",
        help="Use the first development example of each scenario family; never a held-out accuracy sample.",
    )
    parser.add_argument(
        "--report",
        type=Path,
        help="Optional report path, preserving other evaluation runs.",
    )
    args = parser.parse_args()
    local_sources = sorted((ROOT / "services/investigator/investigator").glob("*.py"))
    local_sources += [Path(__file__), ROOT / "services/investigator/requirements.txt"]
    source_hashes = {
        str(path.resolve().relative_to(ROOT))
        .replace("\\", "/"): hashlib.sha256(path.read_bytes())
        .hexdigest()
        for path in local_sources
    }
    if args.representative_development and (args.split != "development" or args.limit):
        parser.error(
            "Representative selection requires --split development and cannot combine with --limit."
        )
    if args.case_id and (
        args.split != "development" or args.limit or args.representative_development
    ):
        parser.error(
            "Explicit cases require --split development without --limit or --representative-development."
        )
    if len(args.case_id) != len(set(args.case_id)):
        parser.error("Explicit development case IDs must be unique.")
    cases = {
        c["id"]: c for c in json.loads((ROOT / "data/fixtures/cases.json").read_text())
    }
    labels = [
        l
        for l in json.loads((ROOT / "data/evaluation/labels.json").read_text())
        if l["split"] == args.split
    ]
    if args.case_id:
        by_id = {label["caseId"]: label for label in labels}
        if set(args.case_id) - by_id.keys():
            parser.error("Every explicit case must belong to the development split.")
        labels = [by_id[case_id] for case_id in args.case_id]
    if args.limit:
        labels = labels[: args.limit]
    if args.representative_development:
        representatives = {}
        for label in labels:
            representatives.setdefault(label["expectedOutcome"], label)
        labels = list(representatives.values())
    key = os.getenv("POI_SERVICE_KEY", "poi-local-service-key")
    rows = []
    for label in labels:
        case = cases[label["caseId"]]
        started = time.perf_counter()
        # Request contains only operational evidence and a neutral question.
        body = dict(
            investigationId="EVAL-" + str(uuid.uuid4()),
            case=case,
            question="Explain the observed payment state and propose the next case action using authorized evidence and current policy.",
            mode=args.mode,
            actorId="offline-evaluator",
        )
        row = dict(
            caseId=case["id"],
            expectedOutcome=label["expectedOutcome"],
            expectedAction=label["expectedAction"],
        )
        try:
            result = post(args.worker_url, "/investigate", body, key)
            cited = {c["id"] for c in result["citations"]}
            row.update(
                outcome=result["outcome"],
                action=result["proposal"]["action"],
                confidence=result["confidence"],
                investigationId=result["id"],
                toolCalls=result["toolCalls"],
                outcomeCorrect=result["outcome"] == label["expectedOutcome"],
                actionCorrect=result["proposal"]["action"] == label["expectedAction"],
                relevantCitationRetrieved=bool(
                    cited.intersection(label["relevantCitations"])
                ),
                abstentionCorrect=(result["confidence"] == "INSUFFICIENT")
                == label["expectedAbstention"],
                metrics=result["metrics"],
                warnings=result["warnings"],
                error=None,
            )
            allowed = {
                e["id"]
                for group in ("events", "ledgerEntries", "webhooks")
                for e in case[group]
            }
            allowed.update(
                [
                    case["paymentId"],
                    case["provider"]["paymentId"],
                    "PROVIDER:" + case["provider"]["paymentId"],
                    "PROVIDER-" + case["paymentId"],
                ]
            )
            allowed.update(w["providerEventId"] for w in case["webhooks"])
            row["evidenceReferencesValid"] = all(
                set(f["evidenceIds"]).issubset(allowed)
                and set(f["citationIds"]).issubset(cited)
                for f in result["findings"]
            )
            if args.mode == "ollama":
                scope = result["metrics"].get("synthesisScope")
                row["fieldProvenance"] = (
                    {
                        "summaryConfidenceMissingEvidenceProposal": "deterministic-evidence-rules",
                        "factSelection": "ollama",
                        "findingTextAndEvidenceLinks": result["metrics"].get(
                            "findingSource", "not recorded; inspect provenance"
                        ),
                    }
                    if scope == "fact-selection"
                    else (
                        {
                            "summaryConfidenceMissingEvidenceProposal": "deterministic-evidence-rules",
                            "findings": (
                                "ollama"
                                if scope == "finding-only"
                                else "none: synthesis intentionally skipped"
                            ),
                        }
                        if scope in {"finding-only", "skipped-insufficient-evidence"}
                        else {
                            "returnedProse": "historical full model synthesis; inspect its recorded scope"
                        }
                    )
                )
                row["acceptedExplanation"] = {
                    field: result[field]
                    for field in (
                        "summary",
                        "findings",
                        "citations",
                        "missingEvidence",
                        "proposal",
                    )
                }
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as error:
            detail = (
                error.read().decode()[:500]
                if isinstance(error, urllib.error.HTTPError)
                else str(error)
            )
            row.update(
                error=detail,
                outcomeCorrect=False,
                actionCorrect=False,
                relevantCitationRetrieved=False,
                abstentionCorrect=False,
                evidenceReferencesValid=False,
            )
        row["wallMs"] = round((time.perf_counter() - started) * 1000, 3)
        rows.append(row)
        print(
            f"{case['id']}: {'PASS' if row['outcomeCorrect'] and row['actionCorrect'] else 'FAIL'}",
            flush=True,
        )
    n = len(rows)
    rates = {
        metric: round(sum(bool(r.get(metric)) for r in rows) / n, 4) if n else None
        for metric in (
            "outcomeCorrect",
            "actionCorrect",
            "relevantCitationRetrieved",
            "abstentionCorrect",
            "evidenceReferencesValid",
        )
    }
    report = dict(
        startedAt=started_at,
        timestamp=datetime.now(timezone.utc).isoformat(),
        workerUrl=args.worker_url,
        clientTimeoutSeconds=430,
        localSourceSha256=source_hashes,
        localSourcesUnchanged=all(
            hashlib.sha256((ROOT / relative).read_bytes()).hexdigest() == expected
            for relative, expected in source_hashes.items()
        ),
        sourceProvenanceLimit="Local source hashes are a reference snapshot; match them with the separately verified worker image/test receipt before attributing a remote worker run to these files.",
        split=args.split,
        mode=args.mode,
        sampleSize=n,
        sampleSelection=(
            "explicitly selected development cases; diagnostic selection, not independent accuracy"
            if args.case_id
            else (
                "first development case per scenario family"
                if args.representative_development
                else "fixture split in source order, with explicit limit if supplied"
            )
        ),
        datasetManifestSha256=hashlib.sha256(
            (ROOT / "data/manifest.json").read_bytes()
        ).hexdigest(),
        rates=rates,
        latencyMs=dict(
            p50=percentile([r["wallMs"] for r in rows], 0.5),
            p95=percentile([r["wallMs"] for r in rows], 0.95),
        ),
        latencyQuantileMethod="Lower order statistic at floor((n - 1) * p); includes failed attempts and is descriptive only.",
        failures=sum(bool(r["error"]) for r in rows),
        modelCalls=(
            None
            if args.mode == "ollama" and any(r["error"] for r in rows)
            else sum(r.get("metrics", {}).get("modelCalls", 0) for r in rows)
        ),
        acceptedResultModelCalls=sum(
            r.get("metrics", {}).get("modelCalls", 0) for r in rows
        ),
        failedRequestModelCalls=(
            "unknown: failed requests do not return provider usage"
            if args.mode == "ollama" and any(r["error"] for r in rows)
            else "no unreported failed-model usage"
        ),
        limitations=(
            [
                "Template variants share scenario structure; scores do not establish real-world generalization.",
                "Identifier validation checks citation existence, not semantic entailment of generated prose.",
                "This worker benchmark bypasses Java; HTTP acceptance separately validates authentication, Java reconciliation and review.",
                "Replay is a deterministic evidence baseline, not a language-model result.",
            ]
            if args.mode == "replay"
            else [
                "Template variants share structure; this is a local model sample, not a real-world accuracy claim.",
                "Outcome is supplied by deterministic evidence rules; model selection quality is a separate question.",
                "Identifier validation does not prove catalog correctness, snapshot completeness or selection relevance.",
                "acceptedExplanation preserves returned fields; fieldProvenance distinguishes model-selected/service-rendered facts, historical model prose and rule-owned assessment.",
                "Direct worker benchmark; Java API controls tested separately.",
            ]
        ),
        rows=rows,
    )
    path = (
        args.report
        or ROOT / "docs/validation" / f"evaluation-{args.mode}-{args.split}.json"
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "rows"}, indent=2))
    if report["failures"] or not report["localSourcesUnchanged"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
