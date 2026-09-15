"""Record current component evidence and deployed artifact correspondence locally."""
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True, cwd=ROOT).strip()


def main():
    junit = list((ROOT / "services/api/target/surefire-reports").glob("TEST-*.xml"))
    totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    suites = []
    for path in junit:
        suite = ET.parse(path).getroot()
        summary = {key: int(suite.attrib.get(key, 0)) for key in totals}
        for key, value in summary.items(): totals[key] += value
        suites.append({"name": suite.attrib["name"], **summary, "reportSha256": sha(path)})
    assert totals["tests"] >= 52 and not sum(totals[k] for k in ("failures", "errors", "skipped"))
    worker = json.loads((ROOT / "services/investigator/runtime/worker-obpm-tests.json").read_text(encoding="utf-8"))
    web = json.loads((ROOT / "apps/web/validation-obpm.json").read_text(encoding="utf-8"))
    for relative, expected in worker["sourceSha256"].items():
        assert sha(ROOT / "services/investigator" / relative) == expected, relative
    for relative, expected in web["sourceHashes"].items():
        assert sha(ROOT / "apps/web" / relative) == expected, relative
    api_jar = sha(ROOT / "services/api/target/payment-operations-api-0.1.0.jar")
    assert docker("exec", "payment-operations-investigator-api-1", "sha256sum", "/app/api.jar").split()[0] == api_jar
    images = {name: docker("inspect", "--format", "{{.Image}}", "payment-operations-investigator-" + name + "-1") for name in ("api", "investigator", "web")}
    worker_sources = {}
    for path in sorted((ROOT / "services/investigator/investigator").glob("*.py")):
        actual = docker("exec", "payment-operations-investigator-investigator-1", "sha256sum", "/app/investigator/" + path.name).split()[0]
        assert actual == sha(path), path.name
        worker_sources[path.name] = actual
    web_bundles = {}
    for path in sorted((ROOT / "apps/web/dist/assets").iterdir()):
        if path.is_file():
            actual = docker("exec", "payment-operations-investigator-web-1", "sha256sum", "/usr/share/nginx/html/assets/" + path.name).split()[0]
            assert actual == sha(path), path.name
            web_bundles[path.name] = actual
    tracked_sources = {}
    for pattern in ("services/api/src/**/*.java", "services/api/src/main/resources/*", "data/obpm/**/*.json", "data/knowledge/obpm-runbooks.json", "tools/*obpm*.py", "tools/acceptance.py", "compose.yaml"):
        for path in ROOT.glob(pattern):
            if path.is_file(): tracked_sources[path.relative_to(ROOT).as_posix()] = sha(path)
    receipt = {"status": "PASS", "recordedAt": datetime.now(timezone.utc).isoformat(),
               "java": {"totals": totals, "suites": suites, "testedAndRunningJarSha256": api_jar},
               "worker": worker, "web": web, "runningImages": images,
               "runningWorkerSourceSha256": worker_sources, "runningWebBundleSha256": web_bundles,
               "sourceSha256": tracked_sources,
               "buildNotes": "Docker Hub base metadata timed out. Java was tested/packaged offline with the cached Maven image; app images were rebuilt from local runtime images using the tested Java jar/current Python source/native-built React bundle. This proves selected content correspondence, not clean-room image reproducibility."}
    destination = ROOT / "docs/validation/obpm-components.json"
    destination.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print("PASS component tests, source hashes, tested/running Java jar, worker modules and React bundles")


if __name__ == "__main__":
    main()
