"""Audit, create and independently verify a reviewed Git source-only local ZIP.

Use --source-date-epoch (or SOURCE_DATE_EPOCH) for byte-identical output from an
unchanged source tree with the same Python/zlib runtime. This is an exclusion
policy with bounded content checks, not a complete secret/PII detector or an
authenticity signature. See docs/RELEASE_PRIVACY.md before sharing a release.
"""

import argparse
import hashlib
import io
import ipaddress
import json
import os
import re
import stat
import subprocess
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
PREFIX = "payment-operations-investigator/"
MANIFEST = "SOURCE_MANIFEST.json"
FOLDERS = ("apps", "services", "data", "docs", "infra", "tools", ".github", ".vscode")
TOP_FILES = ("README.md", "compose.yaml", ".env.example", ".gitignore",
             "Payment Operations Investigator.code-workspace", "LICENSE", "LICENSE.md",
             "LICENSE.txt", "CHANGELOG.md", "SECURITY.md", ".editorconfig", ".gitattributes", ".dockerignore")
POLICY = "reviewed-git-source-v2"
ARTIFACT = "Local source snapshot, not a publication or production release"
VALIDATION = "Read docs/STATUS.md and each dated validation report inside the archive."
MAX_FILE_BYTES = 64 * 1024 * 1024
MAX_TOTAL_BYTES = 512 * 1024 * 1024
EXCLUDED = {
    "node_modules", ".venv", "venv", "target", "dist", "build", "release",
    "runtime", ".runtime", "__pycache__", ".pytest_cache", ".ruff_cache",
    ".git", ".mypy_cache", ".cache", ".npm-cache", ".npm", ".pnpm-store",
    ".yarn", ".ollama", ".huggingface", "huggingface", "model-cache",
    "model_cache", ".models", "playwright-report", "test-results", "coverage",
    ".coverage", ".envrc", ".ds_store", "thumbs.db",
    "dbdata", "outputs", "private", "secrets", ".secrets", "confidential",
    "uploads", "attachments", ".codex", ".agents", "agents.md", "jt",
    "compose.override.yaml", "compose.override.yml", "modernize", "package-history",
}
EXCLUDED_SUFFIXES = {
    ".pyc", ".pyo", ".log", ".pid", ".sqlite", ".sqlite-wal",
    ".sqlite-shm", ".db", ".db-wal", ".db-shm", ".tsbuildinfo",
    ".zip", ".tar", ".gz", ".tgz", ".bz2", ".xz", ".7z", ".rar",
    ".sha256", ".jar", ".war", ".class", ".exe", ".dll", ".so",
    ".gguf", ".safetensors", ".onnx", ".pt", ".pth", ".bin",
    ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore",
    ".bak", ".dump", ".dmp",
}
EXCLUDED_NAMES = {"package-cleanup.json", "obpm-components.json", "source-packaging.json",
                  "source-folder-delivery.json"}
MAX_GIT_BYTES = 16 * 1024 * 1024
MAX_XLSX_MEMBERS = 2000
MAX_XLSX_BYTES = 64 * 1024 * 1024
PRIVATE_NETWORKS = tuple(ipaddress.ip_network(value) for value in (
    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "fc00::/7", "fe80::/10"))
URL_PATTERN = re.compile(r"(?i)\b(?:https?|wss?|ftp|postgres(?:ql)?|mysql|oracle|mongodb(?:\+srv)?):/{2}[^\s\"'<>`]+")
SECRET_PATTERNS = {
    "private-key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----"),
    "github-token": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})\b"),
    "aws-access-key": re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    "google-api-key": re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b"),
    "slack-token": re.compile(r"\bxox[baprs]-[0-9A-Za-z-]{20,}\b"),
    "bearer-token": re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~-]{32,}"),
}
# A deliberately rejected non-loopback URL in this original synthetic test is
# reviewed once, by exact file content. This never exempts credentials or keys.
REVIEWED_NETWORK_FIXTURES = {
    "tools/tests/test_benchmark_native_ollama.py": "91e702cbbe95065198c2705272c9651187201968e0f95887253dc8443c1494ae",
    "services/api/src/test/java/dev/pratik/poi/CaseEvidenceClientTest.java": "c86dbd442e19546533278a342a4f35fd8dda49a68b47c1515f9cb4f20e05d56b",
    "services/api/src/test/java/dev/pratik/poi/PaymentDiscoveryClientTest.java": "8d967dd52d653971b69d24ab49980cd0dc5b0a09405e1e1ce907f7afe45dfe4b",
}


def linked(path):
    """lstat also detects Windows junctions and other reparse points."""
    info = path.lstat()
    return stat.S_ISLNK(info.st_mode) or bool(
        getattr(info, "st_file_attributes", 0)
        & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
    )


def excluded(relative):
    parts = tuple(part.casefold() for part in relative.parts)
    name = parts[-1]
    return bool(EXCLUDED.intersection(parts)) or name in EXCLUDED_NAMES or name.startswith("tool-call") or (
        (name == ".env" or name.startswith(".env.")) and name != ".env.example"
    ) or name.endswith((".local", ".local.json", ".local.yaml", ".local.yml", ".local.properties")) or PurePosixPath(name).suffix in EXCLUDED_SUFFIXES


def allowed(relative):
    return relative.as_posix() in TOP_FILES or (
        len(relative.parts) > 1 and relative.parts[0] in FOLDERS
    )


def git(root, *arguments):
    """Read local Git metadata only; do not inherit an alternate index/worktree."""
    environment = {key: value for key, value in os.environ.items() if not key.upper().startswith("GIT_")}
    environment.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull, GIT_TERMINAL_PROMPT="0")
    try:
        result = subprocess.run(
            ["git", "--no-optional-locks", "-c", "core.fsmonitor=false", "-C", str(root), *arguments],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=environment, timeout=30, check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ValueError("Local Git metadata is unavailable; no source-folder fallback is permitted") from error
    if result.returncode != 0 or len(result.stdout) > MAX_GIT_BYTES:
        raise ValueError("Local Git metadata check failed or exceeded its bound; no archive was approved")
    return result.stdout


def safe_relative(value):
    if (not value or "\\" in value or ":" in value
            or any(ord(char) < 32 or ord(char) == 127 for char in value)
            or any(part in ("", ".", "..") for part in value.split("/"))):
        raise ValueError("Git or archive contains an unsafe source path")
    return PurePosixPath(value)


def assert_source_path(path, root):
    """Reject a linked parent as well as a linked file, including Windows junctions."""
    relative = path.relative_to(root)
    current = root
    for part in relative.parts:
        current /= part
        if linked(current):
            raise ValueError(f"Linked source paths are prohibited: {relative.as_posix()}")
    if not path.resolve(strict=True).is_relative_to(root):
        raise ValueError(f"Source path leaves project: {relative.as_posix()}")


def git_inventory(root=ROOT):
    root = root.resolve(strict=True)
    top = Path(os.fsdecode(git(root, "rev-parse", "--show-toplevel")).strip()).resolve(strict=True)
    if top != root:
        raise ValueError("Package root must be the Git working-tree root")
    commit = git(root, "rev-parse", "--verify", "HEAD^{commit}").decode("ascii").strip()
    ignored = set(os.fsdecode(value) for value in git(root, "ls-files", "-z", "--cached", "--ignored", "--exclude-standard").split(b"\0") if value)
    paths, seen, forbidden = [], set(), []
    for entry in git(root, "ls-files", "--stage", "-z").split(b"\0"):
        if not entry:
            continue
        metadata, raw_name = entry.split(b"\t", 1)
        mode, _, stage = metadata.split(b" ")
        name = os.fsdecode(raw_name)
        relative = safe_relative(name)
        if mode not in (b"100644", b"100755") or stage != b"0":
            raise ValueError(f"Linked, submodule or conflicted Git entry is prohibited: {name}")
        if name.casefold() in seen:
            raise ValueError(f"Case-insensitive source path collision: {name}")
        seen.add(name.casefold())
        if name in ignored or excluded(relative) or not allowed(relative):
            forbidden.append(name)
            continue
        path = root / name
        assert_source_path(path, root)
        if not path.is_file():
            raise ValueError(f"Tracked source is not a regular file: {name}")
        paths.append((path, name))
    changed = sorted(os.fsdecode(value) for value in git(root, "diff", "--no-ext-diff", "--name-only", "-z", "HEAD", "--").split(b"\0") if value)
    untracked = []
    for value in git(root, "ls-files", "--others", "--exclude-standard", "-z").split(b"\0"):
        if value:
            name = os.fsdecode(value)
            relative = safe_relative(name)
            if allowed(relative) and not excluded(relative):
                untracked.append(name)
    return {"commit": commit, "sources": sorted(paths, key=lambda item: item[1]),
            "forbiddenTracked": sorted(forbidden), "changedTracked": changed,
            "untrackedSource": sorted(untracked)}


def source_files(root=ROOT):
    inventory = git_inventory(root)
    if inventory["forbiddenTracked"]:
        raise ValueError("Tracked files violate the release path/ignore policy; run --check and remove them from Git")
    return inventory["sources"]


def read_source(path, root):
    assert_source_path(path, root)
    before = path.lstat()
    if not stat.S_ISREG(before.st_mode) or before.st_size > MAX_FILE_BYTES:
        raise ValueError(f"Source is not a bounded regular file: {path}")
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    with os.fdopen(os.open(path, flags), "rb") as stream:
        opened = os.fstat(stream.fileno())
        if (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino):
            raise ValueError(f"Source changed while opening: {path}")
        content = stream.read(MAX_FILE_BYTES + 1)
        after = os.fstat(stream.fileno())
    if len(content) > MAX_FILE_BYTES or (
        before.st_size, before.st_mtime_ns
    ) != (after.st_size, after.st_mtime_ns):
        raise ValueError(f"Source changed while reading: {path}")
    return content


def text_findings(text, relative, reviewed_network=False):
    """Return category and path only: never echo a suspected endpoint or credential."""
    findings = {name for name, pattern in SECRET_PATTERNS.items() if pattern.search(text)}
    # JSON and XML escaping must not hide an ordinary endpoint in a source file.
    normalized = text.replace("\\/", "/").replace("&#58;", ":").replace("&#x3a;", ":")
    for match in URL_PATTERN.finditer(normalized):
        try:
            candidate = match.group(0).rstrip(").,;}")
            value = urlsplit(candidate.rstrip("]") if "[" not in candidate else candidate)
            host = value.hostname
            if not host:
                continue
            try:
                address = ipaddress.ip_address(host)
                address = getattr(address, "ipv4_mapped", None) or address
                if not reviewed_network and any(address in network for network in PRIVATE_NETWORKS if address.version == network.version):
                    findings.add("private-network-endpoint")
                local = address.is_loopback
            except ValueError:
                local = host in {"localhost", "postgres", "api", "worker", "ollama"}
            placeholder = value.password and value.password.startswith("${") and value.password.endswith("}")
            if value.password and not local and not placeholder:
                findings.add("credential-in-remote-url")
        except ValueError:
            # An incomplete source-code URL is not interpreted as a real endpoint.
            continue
    return [{"path": relative, "rule": rule} for rule in sorted(findings)]


def scan_content(content, relative):
    # Git autocrlf may change only line endings; all other bytes must match.
    digest = hashlib.sha256(content.replace(b"\r\n", b"\n")).hexdigest()
    reviewed = REVIEWED_NETWORK_FIXTURES.get(relative) == digest
    texts = [content.decode("utf-8", errors="replace")]
    if content.startswith((b"\xff\xfe", b"\xfe\xff")):
        texts.append(content.decode("utf-16", errors="replace"))
    findings = []
    for value in texts:
        findings.extend(text_findings(value, relative, reviewed))
    if PurePosixPath(relative).suffix.casefold() == ".xlsx":
        # The public discovery template is OOXML. Inspect compressed XML rather
        # than pretending that scanning the opaque ZIP bytes checked its cells.
        try:
            with zipfile.ZipFile(io.BytesIO(content)) as workbook:
                members = workbook.infolist()
                if len(members) > MAX_XLSX_MEMBERS:
                    raise ValueError("Workbook has too many members")
                total, seen = 0, set()
                for member in members:
                    name = member.filename
                    safe_relative(name.rstrip("/"))
                    if name.casefold() in seen or member.flag_bits & 1 or stat.S_ISLNK(member.external_attr >> 16):
                        raise ValueError("Workbook contains an unsafe member")
                    seen.add(name.casefold())
                    if member.is_dir():
                        continue
                    total += member.file_size
                    if member.file_size > MAX_FILE_BYTES or total > MAX_XLSX_BYTES:
                        raise ValueError("Workbook exceeds inspection bounds")
                    if not name.casefold().endswith((".xml", ".rels")):
                        raise ValueError("Workbook contains opaque embedded content requiring separate review")
                    raw = workbook.read(member)
                    text = raw.decode("utf-16" if raw.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8", errors="strict")
                    findings.extend(text_findings(text, relative + "!" + name))
        except (zipfile.BadZipFile, UnicodeError, RuntimeError, ValueError) as error:
            raise ValueError(f"Workbook cannot be safely inspected: {relative}") from error
    return [dict(path=path, rule=rule) for path, rule in sorted({(item["path"], item["rule"]) for item in findings})]


def check_sources(root=ROOT):
    """Audit only. Ignored/untracked contents are never opened or copied."""
    root = root.resolve(strict=True)
    inventory = git_inventory(root)
    findings, total = [], 0
    for source, relative in inventory["sources"]:
        content = read_source(source, root)
        total += len(content)
        if total > MAX_TOTAL_BYTES - MAX_FILE_BYTES:
            raise ValueError("Source snapshot exceeds size limit")
        findings.extend(scan_content(content, relative))
    ready = bool(inventory["sources"]) and not any((inventory["forbiddenTracked"], inventory["changedTracked"], inventory["untrackedSource"], findings))
    return {"status": "ready" if ready else "not-ready", "policy": POLICY, "gitCommit": inventory["commit"],
            "files": len(inventory["sources"]), "sourceBytes": total, "findings": findings,
            "forbiddenTracked": inventory["forbiddenTracked"], "changedTracked": inventory["changedTracked"],
            "untrackedSource": inventory["untrackedSource"], "createsArchive": False}


def zip_info(name, stamp):
    info = zipfile.ZipInfo(name, max((1980, 1, 1, 0, 0, 0), stamp.timetuple()[:6]))
    info.create_system = 3
    info.external_attr = (stat.S_IFREG | 0o644) << 16
    info.compress_type = zipfile.ZIP_DEFLATED
    return info


def verify_archive(path, expected_manifest=None):
    """Verify member names, limits and every manifest hash without extraction."""
    with zipfile.ZipFile(path) as archive:
        members = {}
        folded = set()
        total = 0
        for info in archive.infolist():
            name = info.filename
            if not name.startswith(PREFIX):
                raise ValueError(f"Unexpected archive root: {name}")
            relative = name[len(PREFIX):]
            safe_relative(relative)
            if (
                not relative or "\\" in relative or ":" in relative
                or any(part in ("", ".", "..") for part in relative.split("/"))
                or name.casefold() in folded
                or info.flag_bits & 1
                or not stat.S_ISREG(info.external_attr >> 16)
            ):
                raise ValueError(f"Unsafe or duplicate archive member: {name}")
            if relative != MANIFEST and (
                not allowed(PurePosixPath(relative)) or excluded(PurePosixPath(relative))
            ):
                raise ValueError(f"Excluded or unexpected archive member: {name}")
            total += info.file_size
            if info.file_size > MAX_FILE_BYTES or total > MAX_TOTAL_BYTES:
                raise ValueError("Archive exceeds source size limits")
            members[relative] = info
            folded.add(name.casefold())
        if MANIFEST not in members:
            raise ValueError("Source manifest is missing")
        manifest = json.loads(archive.read(members[MANIFEST]))
        if expected_manifest is not None and manifest != expected_manifest:
            raise ValueError("Packed source manifest mismatch")
        if not isinstance(manifest, dict):
            raise ValueError("Source manifest must be an object")
        if (manifest.get("policy") != POLICY or manifest.get("selection") != "clean-git-tracked-source"
                or not re.fullmatch(r"[a-f0-9]{40}(?:[a-f0-9]{24})?", str(manifest.get("gitCommit", "")))):
            raise ValueError("Archive has no current Git/privacy policy receipt; historical archives require separate review")
        if (set(manifest) != {"createdAt", "policy", "selection", "gitCommit", "artifact", "validationStatus", "files"}
                or manifest.get("artifact") != ARTIFACT or manifest.get("validationStatus") != VALIDATION
                or not isinstance(manifest.get("createdAt"), str)):
            raise ValueError("Unexpected source manifest metadata")
        try:
            created = datetime.fromisoformat(manifest["createdAt"])
            if created.utcoffset() != timezone.utc.utcoffset(created) or not 1970 <= created.year <= 2107:
                raise ValueError("Unexpected source manifest timestamp")
        except (ValueError, TypeError) as error:
            raise ValueError("Unexpected source manifest timestamp") from error
        rows = manifest.get("files")
        if not isinstance(rows, list) or not rows:
            raise ValueError("Source manifest has no files")
        expected = {MANIFEST}
        for row in rows:
            if not isinstance(row, dict) or set(row) != {"path", "bytes", "sha256"} or not isinstance(row.get("path"), str):
                raise ValueError("Invalid source manifest row")
            relative = row["path"]
            if relative in expected or relative not in members:
                raise ValueError(f"Duplicate or missing manifest member: {relative}")
            expected.add(relative)
            content = archive.read(members[relative])
            if (
                type(row.get("bytes")) is not int or len(content) != row["bytes"]
                or hashlib.sha256(content).hexdigest() != row.get("sha256")
            ):
                raise ValueError(f"Packed source length/hash mismatch: {relative}")
            if scan_content(content, relative):
                raise ValueError(f"Archive content violates release privacy checks: {relative}")
        if expected != set(members):
            raise ValueError("Archive contains unmanifested files")
    return manifest


def package(root=ROOT, stamp=None):
    root = root.resolve(strict=True)
    stamp = (stamp or datetime.now(timezone.utc)).astimezone(timezone.utc).replace(microsecond=0)
    if not 1970 <= stamp.year <= 2107:
        raise ValueError("Source timestamp must be between 1970 and 2107")
    audit = check_sources(root)
    if audit["status"] != "ready":
        raise ValueError("Release is not ready: run --check, review source changes and resolve every finding before packaging")
    sources = source_files(root)
    destination = root / "release"
    if os.path.lexists(destination) and (linked(destination) or not destination.is_dir()):
        raise ValueError("Release destination must be a real project directory")
    destination.mkdir(exist_ok=True)
    if not destination.resolve(strict=True).is_relative_to(root):
        raise ValueError("Release destination leaves project")
    if not sources:
        raise ValueError("No source files found")
    manifest = {
        "createdAt": stamp.isoformat(),
        "policy": POLICY,
        "selection": "clean-git-tracked-source",
        "gitCommit": audit["gitCommit"],
        "artifact": ARTIFACT,
        "validationStatus": VALIDATION,
        "files": [],
    }
    descriptor, temporary = tempfile.mkstemp(prefix=".source-", suffix=".tmp", dir=destination)
    os.close(descriptor)
    temporary = Path(temporary)
    try:
        total = 0
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for source, relative in sources:
                content = read_source(source, root)
                if scan_content(content, relative):
                    raise ValueError(f"Source violates release privacy checks: {relative}")
                total += len(content)
                if total > MAX_TOTAL_BYTES - MAX_FILE_BYTES:
                    raise ValueError("Source snapshot exceeds size limit")
                archive.writestr(zip_info(PREFIX + relative, stamp), content, compresslevel=9)
                manifest["files"].append({
                    "path": relative, "bytes": len(content),
                    "sha256": hashlib.sha256(content).hexdigest(),
                })
            archive.writestr(
                zip_info(PREFIX + MANIFEST, stamp),
                json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
                compresslevel=9,
            )
        verify_archive(temporary, manifest)
        final = git_inventory(root)
        if (sources != final["sources"] or final["commit"] != audit["gitCommit"]
                or final["forbiddenTracked"] or final["changedTracked"] or final["untrackedSource"]):
            raise ValueError("Source file set changed during packaging; retry after edits finish")
        for (source, relative), row in zip(sources, manifest["files"], strict=True):
            if hashlib.sha256(read_source(source, root)).hexdigest() != row["sha256"]:
                raise ValueError(f"Source changed during packaging: {relative}")
        digest = hashlib.sha256(temporary.read_bytes()).hexdigest()
        path = destination / (
            "payment-operations-investigator-source-"
            + stamp.strftime("%Y%m%d-%H%M%S") + "-" + digest[:12] + ".zip"
        )
        if os.path.lexists(path):
            if linked(path) or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
                raise ValueError("Refusing to replace a different or linked release file")
        else:
            os.replace(temporary, path)
        checksum = path.with_suffix(".sha256")
        if os.path.lexists(checksum) and (linked(checksum) or not checksum.is_file()):
            raise ValueError("Refusing to write a linked checksum file")
        checksum.write_text(f"{digest}  {path.name}\n", encoding="utf-8", newline="\n")
        return {
            "status": "verified", "path": str(path), "files": len(manifest["files"]),
            "sourceBytes": total, "createdAt": manifest["createdAt"], "sha256": digest,
        }
    finally:
        if temporary.exists():
            temporary.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-date-epoch", type=int, default=os.environ.get("SOURCE_DATE_EPOCH"))
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--verify", type=Path, help="Verify an existing ZIP against the current privacy policy without extracting it")
    mode.add_argument("--check", action="store_true", help="Audit Git-tracked source and report readiness without creating any files")
    args = parser.parse_args()
    try:
        if args.verify:
            manifest = verify_archive(args.verify)
            result = {"status": "verified", "path": str(args.verify), "files": len(manifest["files"]), "policy": POLICY}
        elif args.check:
            result = check_sources()
        else:
            stamp = None if args.source_date_epoch is None else datetime.fromtimestamp(args.source_date_epoch, timezone.utc)
            result = package(stamp=stamp)
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        # Do not emit raw file contents, matched secrets, or subprocess stderr.
        print(json.dumps({"status": "blocked", "error": str(error) if isinstance(error, ValueError) else "Source or archive could not be safely read"}))
        return 1
    print(json.dumps(result))
    return 1 if result["status"] == "not-ready" else 0


if __name__ == "__main__":
    raise SystemExit(main())
