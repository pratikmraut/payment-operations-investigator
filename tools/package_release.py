"""Create and independently verify a bounded, source-only local ZIP.

Use --source-date-epoch (or SOURCE_DATE_EPOCH) for byte-identical output from an
unchanged source tree with the same Python/zlib runtime. This is an exclusion
policy, not a content-based secret scanner or an authenticity signature.
"""

import argparse
import hashlib
import json
import os
import stat
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
PREFIX = "payment-operations-investigator/"
MANIFEST = "SOURCE_MANIFEST.json"
FOLDERS = ("apps", "services", "data", "docs", "infra", "tools", ".github")
TOP_FILES = ("README.md", "AGENTS.md", "compose.yaml", ".env.example", ".gitignore")
MAX_FILE_BYTES = 64 * 1024 * 1024
MAX_TOTAL_BYTES = 512 * 1024 * 1024
EXCLUDED = {
    "node_modules", ".venv", "venv", "target", "dist", "build", "release",
    "runtime", ".runtime", "__pycache__", ".pytest_cache", ".ruff_cache",
    ".git", ".mypy_cache", ".cache", ".npm-cache", ".npm", ".pnpm-store",
    ".yarn", ".ollama", ".huggingface", "huggingface", "model-cache",
    "model_cache", ".models", "playwright-report", "test-results", "coverage",
    ".coverage", ".envrc", ".ds_store", "thumbs.db",
}
EXCLUDED_SUFFIXES = {
    ".pyc", ".pyo", ".log", ".pid", ".sqlite", ".sqlite-wal",
    ".sqlite-shm", ".db", ".db-wal", ".db-shm", ".tsbuildinfo",
    ".zip", ".tar", ".gz", ".tgz", ".bz2", ".xz", ".7z", ".rar",
    ".sha256", ".jar", ".war", ".class", ".exe", ".dll", ".so",
    ".gguf", ".safetensors", ".onnx", ".pt", ".pth", ".bin",
    ".pem", ".key", ".p12", ".pfx",
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
    return bool(EXCLUDED.intersection(parts)) or (
        (name == ".env" or name.startswith(".env.")) and name != ".env.example"
    ) or PurePosixPath(name).suffix in EXCLUDED_SUFFIXES


def allowed(relative):
    return relative.as_posix() in TOP_FILES or (
        len(relative.parts) > 1 and relative.parts[0] in FOLDERS
    )


def walk_error(error):
    # A partially unreadable source tree must not produce a silently incomplete ZIP.
    raise error


def source_files(root=ROOT):
    root = root.resolve(strict=True)
    candidates = [root / name for name in TOP_FILES]
    for folder in FOLDERS:
        base = root / folder
        if not base.exists() or linked(base):
            continue
        for current, directories, files in os.walk(
            base, followlinks=False, onerror=walk_error
        ):
            current = Path(current)
            # Prune before traversal: rglob followed by filtering can still walk
            # dependency trees or a Windows junction outside the source tree.
            directories[:] = sorted(
                name for name in directories
                if not excluded((current / name).relative_to(root))
                and not linked(current / name)
            )
            candidates.extend(current / name for name in files)
    selected = []
    seen = set()
    for path in candidates:
        relative = path.relative_to(root)
        if excluded(relative) or not path.exists() or linked(path):
            continue
        if not path.is_file():
            continue
        if not path.resolve(strict=True).is_relative_to(root):
            raise ValueError(f"Source path leaves project: {relative}")
        key = relative.as_posix().casefold()
        if key in seen:
            raise ValueError(f"Case-insensitive source path collision: {relative}")
        seen.add(key)
        selected.append((path, relative.as_posix()))
    return sorted(selected, key=lambda item: item[1])


def read_source(path, root):
    if linked(path) or not path.resolve(strict=True).is_relative_to(root):
        raise ValueError(f"Source became a link or left project: {path}")
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
        rows = manifest.get("files")
        if not isinstance(rows, list) or not rows:
            raise ValueError("Source manifest has no files")
        expected = {MANIFEST}
        for row in rows:
            if not isinstance(row, dict) or not isinstance(row.get("path"), str):
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
        if expected != set(members):
            raise ValueError("Archive contains unmanifested files")
    return manifest


def package(root=ROOT, stamp=None):
    root = root.resolve(strict=True)
    stamp = (stamp or datetime.now(timezone.utc)).astimezone(timezone.utc).replace(microsecond=0)
    if not 1970 <= stamp.year <= 2107:
        raise ValueError("Source timestamp must be between 1970 and 2107")
    destination = root / "release"
    if os.path.lexists(destination) and (linked(destination) or not destination.is_dir()):
        raise ValueError("Release destination must be a real project directory")
    destination.mkdir(exist_ok=True)
    if not destination.resolve(strict=True).is_relative_to(root):
        raise ValueError("Release destination leaves project")
    sources = source_files(root)
    if not sources:
        raise ValueError("No source files found")
    manifest = {
        "createdAt": stamp.isoformat(),
        "artifact": "Local source snapshot, not a publication or production release",
        "validationStatus": "Read docs/STATUS.md and each dated validation report inside the archive.",
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
        if sources != source_files(root):
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
    parser.add_argument("--verify", type=Path, help="Verify an existing ZIP without extracting it")
    args = parser.parse_args()
    if args.verify:
        manifest = verify_archive(args.verify)
        result = {"status": "verified", "path": str(args.verify), "files": len(manifest["files"])}
    else:
        stamp = None if args.source_date_epoch is None else datetime.fromtimestamp(args.source_date_epoch, timezone.utc)
        result = package(stamp=stamp)
    print(json.dumps(result))


if __name__ == "__main__":
    main()
