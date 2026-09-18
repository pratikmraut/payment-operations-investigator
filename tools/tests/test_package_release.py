"""Source-release privacy checks use temporary synthetic repositories only."""

from datetime import datetime, timezone
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import subprocess
import zipfile

import pytest

SPEC = importlib.util.spec_from_file_location("package_release", Path(__file__).parents[1] / "package_release.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)
STAMP = datetime(2026, 1, 2, 3, 4, 6, tzinfo=timezone.utc)


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True).stdout


def write(repo, name, content):
    path = repo / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content if isinstance(content, bytes) else content.encode())
    return path


def commit(repo):
    git(repo, "add", "--all")
    git(repo, "-c", "user.name=Release Test", "-c", "user.email=release@example.invalid", "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "Synthetic source")


@pytest.fixture
def repo(tmp_path):
    git(tmp_path, "init", "--quiet")
    git(tmp_path, "config", "core.autocrlf", "false")
    write(tmp_path, "README.md", "Original synthetic application\n")
    write(tmp_path, ".gitignore", "runtime/\nrelease/\ndbdata/\nAGENTS.md\n.env\nservices/local-only.json\n")
    write(tmp_path, "services/app.py", "value = 'synthetic'\n")
    commit(tmp_path)
    return tmp_path


def endpoint():
    # Original dummy address assembled to keep the scanner's own test source clean.
    return "https://" + ".".join(map(str, [10, 23, 45, 67])) + ":8443/inquiry"


def workbook(value="REFERENCE-DEMO"):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>')
        archive.writestr("xl/sharedStrings.xml", "<sst><si><t>" + value + "</t></si></sst>")
        archive.writestr("_rels/.rels", '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>')
    return output.getvalue()


def test_clean_reviewed_source_is_reproducible_with_verified_manifest(repo):
    write(repo, ".vscode/settings.json", '{"files.trimTrailingWhitespace":true}\n')
    write(repo, "apps/web/public/template.xlsx", workbook())
    commit(repo)
    first = release.package(repo, STAMP)
    second = release.package(repo, STAMP)
    assert first == second
    path = Path(first["path"])
    assert hashlib.sha256(path.read_bytes()).hexdigest() == first["sha256"]
    assert path.with_suffix(".sha256").read_text().startswith(first["sha256"])
    manifest = release.verify_archive(path)
    assert manifest["policy"] == release.POLICY
    assert manifest["gitCommit"] == git(repo, "rev-parse", "HEAD").decode().strip()
    assert manifest["selection"] == "clean-git-tracked-source"
    assert len(manifest["files"]) == 5


def test_ignored_private_and_untracked_non_source_files_are_never_read(repo, monkeypatch):
    names = ["runtime/private/api.json", "services/runtime/branch-map.json", "dbdata/payments.json",
             "AGENTS.md", ".env", "services/local-only.json", "personal.txt"]
    for name in names:
        write(repo, name, endpoint())
    original = release.read_source
    reads = []
    def guard(path, root):
        name = path.relative_to(root).as_posix()
        assert name not in names
        reads.append(name)
        return original(path, root)
    monkeypatch.setattr(release, "read_source", guard)
    assert release.check_sources(repo)["status"] == "ready"
    result = release.package(repo, STAMP)
    assert set(reads) == {"README.md", ".gitignore", "services/app.py"}
    assert result["files"] == 3


@pytest.mark.parametrize("change", ["unstaged", "staged", "untracked"])
def test_review_required_for_dirty_or_untracked_source(repo, change):
    write(repo, "services/new.py" if change == "untracked" else "services/app.py", "changed = True\n")
    if change == "staged":
        git(repo, "add", "services/app.py")
    audit = release.check_sources(repo)
    assert audit["status"] == "not-ready"
    assert audit["untrackedSource" if change == "untracked" else "changedTracked"]
    with pytest.raises(ValueError, match="not ready"):
        release.package(repo, STAMP)
    assert not (repo / "release").exists()


@pytest.mark.parametrize("name", ["AGENTS.md", "runtime/config.json", "services/private/config.json",
    "docs/dbdata/evidence.json", "data/dump.mv.db", "services/credentials.p12", ".vscode/tool-call-log.json",
    "docs/validation/package-history/receipt.json", "compose.override.yaml"])
def test_force_tracked_private_paths_block_release_without_reading_contents(repo, name, monkeypatch):
    write(repo, name, endpoint())
    git(repo, "add", "--force", name)
    git(repo, "-c", "user.name=Release Test", "-c", "user.email=release@example.invalid", "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "Synthetic forbidden entry")
    original = release.read_source
    def guard(path, root):
        assert path.relative_to(root).as_posix() != name
        return original(path, root)
    monkeypatch.setattr(release, "read_source", guard)
    audit = release.check_sources(repo)
    assert audit["forbiddenTracked"] == [name]
    with pytest.raises(ValueError, match="not ready"):
        release.package(repo, STAMP)


def test_repository_is_required_and_parent_repository_is_not_accepted(tmp_path, repo):
    other = tmp_path / "plain"
    other.mkdir()
    write(other, "README.md", "Public-looking copy")
    with pytest.raises(ValueError):
        release.package(other, STAMP)
    with pytest.raises(ValueError, match="working-tree root"):
        release.package(repo / "services", STAMP)


def test_alternate_git_environment_cannot_redirect_enumeration(repo, tmp_path, monkeypatch):
    monkeypatch.setenv("GIT_WORK_TREE", str(tmp_path / "outside"))
    monkeypatch.setenv("GIT_INDEX_FILE", str(tmp_path / "other-index"))
    assert release.check_sources(repo)["status"] == "ready"


@pytest.mark.parametrize("mode", ["120000", "160000"])
def test_git_symlinks_and_submodules_are_rejected_even_without_following_them(repo, mode):
    blob = git(repo, "rev-parse", "HEAD" if mode == "160000" else "HEAD:README.md").decode().strip()
    git(repo, "update-index", "--add", "--cacheinfo", mode + "," + blob + ",services/link")
    with pytest.raises(ValueError, match="Linked, submodule"):
        release.check_sources(repo)


def test_linked_parent_is_rejected_before_read(repo, monkeypatch):
    original = release.linked
    monkeypatch.setattr(release, "linked", lambda path: path == repo / "services" or original(path))
    with pytest.raises(ValueError, match="Linked source"):
        release.check_sources(repo)


@pytest.mark.parametrize("path", ["../secrets", "/outside", "docs//file", "docs/./file", "C:/private", "docs\\file", "docs/line\nname"])
def test_unsafe_paths_are_rejected(path):
    with pytest.raises(ValueError, match="unsafe source path"):
        release.safe_relative(path)


@pytest.mark.parametrize("source", [lambda: endpoint(), lambda: endpoint().replace("/", "\\/"),
    lambda: "https://[" + "fd12:3456::1" + "]/api", lambda: "gh" + "p_" + "Z" * 36,
    lambda: "-----BEGIN " + "RSA PRIVATE KEY-----", lambda: "AK" + "IA" + "Z" * 16,
    lambda: "Bearer " + "token" * 12, lambda: "https://" + "user:credential" + "@example.invalid/api"])
def test_content_findings_block_release_and_do_not_echo_values(repo, source):
    text = source()
    write(repo, "docs/example.txt", text)
    commit(repo)
    audit = release.check_sources(repo)
    assert audit["findings"]
    assert text not in json.dumps(audit)
    with pytest.raises(ValueError, match="not ready"):
        release.package(repo, STAMP)


def test_compressed_workbook_cells_are_checked(repo):
    write(repo, "apps/template.xlsx", workbook(endpoint()))
    commit(repo)
    audit = release.check_sources(repo)
    assert audit["findings"] == [{"path": "apps/template.xlsx!xl/sharedStrings.xml", "rule": "private-network-endpoint"}]
    assert audit["status"] == "not-ready"


def test_workbook_bounds_and_opaque_embedded_objects_are_rejected(monkeypatch):
    monkeypatch.setattr(release, "MAX_XLSX_BYTES", 3)
    with pytest.raises(ValueError, match="safely inspected"):
        release.scan_content(workbook(), "apps/template.xlsx")
    monkeypatch.setattr(release, "MAX_XLSX_BYTES", 100000)
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("xl/embedded.bin", b"opaque")
    with pytest.raises(ValueError, match="safely inspected"):
        release.scan_content(buffer.getvalue(), "apps/template.xlsx")


def test_fixture_exception_is_hash_pinned_and_only_applies_to_network_rule(monkeypatch):
    text = endpoint().encode()
    name = "tools/tests/synthetic.py"
    monkeypatch.setitem(release.REVIEWED_NETWORK_FIXTURES, name, hashlib.sha256(text).hexdigest())
    assert release.scan_content(text, name) == []
    assert release.scan_content(text + b"\n", name)
    assert release.scan_content(text, "tools/tests/other.py")
    credential = text + ("\ngh" + "p_" + "Z" * 36).encode()
    monkeypatch.setitem(release.REVIEWED_NETWORK_FIXTURES, name, hashlib.sha256(credential).hexdigest())
    assert release.scan_content(credential, name) == [{"path": name, "rule": "github-token"}]


def rewrite_archive(path, mutate):
    with zipfile.ZipFile(path) as archive:
        contents = {item.filename: archive.read(item) for item in archive.infolist()}
    mutate(contents)
    with zipfile.ZipFile(path, "w") as archive:
        for name, content in contents.items():
            archive.writestr(release.zip_info(name, STAMP), content)


def test_verification_rechecks_content_even_when_attacker_updates_manifest_hash(repo):
    path = Path(release.package(repo, STAMP)["path"])
    def mutate(contents):
        contents[release.PREFIX + "README.md"] = endpoint().encode()
        manifest = json.loads(contents[release.PREFIX + release.MANIFEST])
        for item in manifest["files"]:
            if item["path"] == "README.md":
                item.update(bytes=len(endpoint()), sha256=hashlib.sha256(endpoint().encode()).hexdigest())
        contents[release.PREFIX + release.MANIFEST] = json.dumps(manifest).encode()
    rewrite_archive(path, mutate)
    with pytest.raises(ValueError, match="privacy checks"):
        release.verify_archive(path)


def test_historical_manifest_does_not_receive_new_policy_approval(repo):
    path = Path(release.package(repo, STAMP)["path"])
    def mutate(contents):
        manifest = json.loads(contents[release.PREFIX + release.MANIFEST])
        del manifest["policy"]
        contents[release.PREFIX + release.MANIFEST] = json.dumps(manifest).encode()
    rewrite_archive(path, mutate)
    with pytest.raises(ValueError, match="historical archives"):
        release.verify_archive(path)


def test_verification_rejects_added_private_unmanifested_file(repo):
    path = Path(release.package(repo, STAMP)["path"])
    rewrite_archive(path, lambda contents: contents.update({release.PREFIX + "docs/private/data.json": b"{}"}))
    with pytest.raises(ValueError, match="Excluded"):
        release.verify_archive(path)


def test_source_changed_during_packaging_never_publishes_zip(repo, monkeypatch):
    original = release.read_source
    reads = 0
    def change(path, root):
        nonlocal reads
        content = original(path, root)
        reads += 1
        if reads == 4:
            write(repo, "services/app.py", "changed = True\n")
        return content
    monkeypatch.setattr(release, "read_source", change)
    with pytest.raises(ValueError, match="changed"):
        release.package(repo, STAMP)
    assert not list((repo / "release").glob("*.zip"))
    assert not list((repo / "release").glob("*.tmp"))


def test_oversized_sources_fail_before_creating_archive(repo, monkeypatch):
    monkeypatch.setattr(release, "MAX_FILE_BYTES", 2)
    with pytest.raises(ValueError, match="bounded regular file"):
        release.package(repo, STAMP)
    assert not (repo / "release").exists()
