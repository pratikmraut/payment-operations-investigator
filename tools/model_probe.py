"""Record one genuine model investigation through Java, without approving its proposal."""

import argparse
import hashlib
import json
import time
from datetime import datetime, timezone
from pathlib import Path

from acceptance import Client

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:5178")
    parser.add_argument("--case-id", default="CASE-1031")
    parser.add_argument("--expected-retrieval-mode", choices=("lexical", "hybrid"))
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    started = time.perf_counter()
    report = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "baseUrl": args.base_url,
        "caseId": args.case_id,
        "mode": "ollama",
        "datasetManifestSha256": hashlib.sha256(
            (ROOT / "data/manifest.json").read_bytes()
        ).hexdigest(),
        "limitations": [
            "One development case; not an accuracy, capacity or generalization benchmark.",
            "The recorded synthesisScope distinguishes model-selected/service-rendered facts from historical model-written prose; assessment remains rule-owned.",
            "Identifier validation does not establish catalog correctness, source completeness or selection relevance; inspect the saved facts and provenance.",
            "Creates a synthetic investigation; no review decision or payment action is submitted.",
        ],
    }
    try:
        client = Client(args.base_url)
        client.login("analyst")
        before = client.request("GET", "/api/cases/" + args.case_id)
        report["operationalSnapshot"] = before
        result = client.request(
            "POST",
            "/api/cases/" + args.case_id + "/investigations",
            {
                "mode": "ollama",
                "question": "Explain the observed payment state and propose the next case action using authorized evidence and current policy.",
            },
        )
        report["investigation"] = result
        assert result["mode"] == "ollama" and result["metrics"]["modelCalls"] >= 1
        if args.expected_retrieval_mode:
            assert result["metrics"]["retrievalMode"] == args.expected_retrieval_mode
        assert result["caseId"] == args.case_id
        after = client.request("GET", "/api/cases/" + args.case_id)
        assert after["status"] == "AWAITING_REVIEW"
        report["caseStateAfter"] = {
            "status": after["status"],
            "version": after["version"],
        }
        report["status"] = "passed"
    except Exception as error:
        report["status"] = "failed"
        report["error"] = str(error)
        report["failedRequestModelCalls"] = (
            "Unknown unless provider metrics were returned."
        )
    report["wallSeconds"] = round(time.perf_counter() - started, 3)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: report[k] for k in ("status", "caseId", "wallSeconds")}))
    if report["status"] != "passed":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
