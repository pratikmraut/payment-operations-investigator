"""Offline, lossless UAT export staging. No database, model, or application calls.

Read the private SQL export contract and an XLSX workbook (or a directory of
UTF-8 CSV result sets). Reports contain field locations, never transaction values.
Staged records retain classification UAT and cannot be used by the demo importer.
"""
from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal, InvalidOperation
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT / "runtime" / "obpm-uat" / "private"
DEFAULT_CONTRACT = ROOT / "runtime" / "obpm-uat" / "export-contract.json"
VERSION = "neft-uat-stage-v1"
LIMIT_ROWS, LIMIT_COLUMNS = 10000, 100
MAX_FILE_BYTES = 20 * 1024 * 1024
NS = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
DECIMAL = re.compile(r"-?(?:(?:0|[1-9][0-9]*)(?:\.[0-9]+)?|\.[0-9]+)(?:[Ee][+-]?[0-9]{1,3})?\Z")
INTEGER = re.compile(r"(?:0|[1-9][0-9]*)\Z")
LOCAL_TIME = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?\Z")
COVERAGE_COLUMNS = ["SHEET", "STATUS", "EXPORTED_ROWS", "QUERY_OBSERVED_AT", "SCOPE", "REASON"]


@dataclass(frozen=True)
class Cell:
    value: str = ""
    kind: str = "text"


class ExportError(ValueError):
    pass


def column_number(reference: str) -> int:
    match = re.fullmatch(r"([A-Z]+)[1-9][0-9]*", reference)
    if not match:
        raise ExportError("Invalid Excel cell coordinate.")
    result = 0
    for ch in match[1]:
        result = result * 26 + ord(ch) - 64
    return result


def read_xlsx(path: Path) -> dict[str, list[list[Cell]]]:
    if path.stat().st_size > MAX_FILE_BYTES:
        raise ExportError("Workbook exceeds the 20 MiB staging limit; split by payment.")
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if len(entries) > 1000 or sum(e.file_size for e in entries) > 100 * 1024 * 1024:
            raise ExportError("Workbook expanded size exceeds the staging limit.")
        if len({e.filename for e in entries}) != len(entries):
            raise ExportError("Workbook contains duplicate ZIP entries.")
        if any(e.filename.lower().endswith("vbaproject.bin") for e in entries):
            raise ExportError("Use a macro-free .xlsx export.")
        shared = []
        if "xl/sharedStrings.xml" in archive.namelist():
            for item in ET.fromstring(archive.read("xl/sharedStrings.xml")).findall("s:si", NS):
                shared.append("".join(t.text or "" for t in item.iter(f"{{{NS['s']}}}t")))
        relationships = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
        rels = {r.attrib["Id"]: r.attrib for r in relationships}
        workbook = ET.fromstring(archive.read("xl/workbook.xml"))
        result = {}
        for sheet in workbook.findall("s:sheets/s:sheet", NS):
            name = sheet.attrib["name"]
            if name in result:
                raise ExportError("Workbook has duplicate worksheet names.")
            relation = rels[sheet.attrib[f"{{{REL}}}id"]]
            if relation.get("TargetMode") == "External":
                raise ExportError("External worksheets are unsupported.")
            target = relation["Target"].replace("\\", "/")
            part = PurePosixPath(target.lstrip("/")) if target.startswith("/") else PurePosixPath("xl") / target
            if ".." in part.parts or not str(part).startswith("xl/"):
                raise ExportError("Worksheet target is outside the workbook.")
            document = ET.fromstring(archive.read(str(part)))
            rows = {}
            for row in document.findall("s:sheetData/s:row", NS):
                number = int(row.attrib["r"])
                if not 1 <= number <= LIMIT_ROWS + 1:
                    raise ExportError("Worksheet exceeds 10,000 data rows; split the export.")
                if number in rows:
                    raise ExportError("Worksheet has duplicate row coordinates.")
                cells = {}
                for node in row.findall("s:c", NS):
                    coordinate = node.attrib["r"]
                    if int(re.search(r"[0-9]+$", coordinate)[0]) != number:
                        raise ExportError("Cell and row coordinates disagree.")
                    col = column_number(coordinate)
                    if col > LIMIT_COLUMNS or col in cells:
                        raise ExportError("Worksheet has duplicate or unsupported cell columns.")
                    kind = node.attrib.get("t", "n")
                    raw = node.findtext("s:v", default="", namespaces=NS)
                    if node.find("s:f", NS) is not None:
                        cell = Cell("", "formula")
                    elif kind == "s":
                        index = int(raw)
                        if not 0 <= index < len(shared):
                            raise ExportError("Invalid shared string index.")
                        cell = Cell(shared[index])
                    elif kind == "inlineStr":
                        cell = Cell("".join(t.text or "" for t in node.iter(f"{{{NS['s']}}}t")))
                    elif kind == "e":
                        cell = Cell("", "error")
                    elif kind == "b":
                        cell = Cell(raw, "boolean")
                    elif kind in {"str", "d"}:
                        cell = Cell(raw, "text" if kind == "str" else "excel-date")
                    else:
                        cell = Cell(raw, "number" if raw else "text")
                    cells[col] = cell
                values = [cells.get(col, Cell()) for col in range(1, max(cells, default=0) + 1)]
                if any(c.value or c.kind in {"formula", "error"} for c in values):
                    if row.attrib.get("hidden") in {"true", "1"}:
                        raise ExportError("Unhide populated rows before submitting the export.")
                    rows[number] = values
            result[name] = [rows.get(i, []) for i in range(1, max(rows, default=0) + 1)]
        return result


def read_csv_directory(path: Path) -> dict[str, list[list[Cell]]]:
    result = {}
    files = list(path.glob("*.csv"))
    if not files or len(files) > 30 or sum(p.stat().st_size for p in files) > MAX_FILE_BYTES:
        raise ExportError("CSV directory is empty or exceeds the staging limit.")
    for file in files:
        with file.open(encoding="utf-8-sig", newline="") as stream:
            rows = []
            for row in csv.reader(stream, strict=True):
                if len(rows) > LIMIT_ROWS or len(row) > LIMIT_COLUMNS:
                    raise ExportError("CSV exceeds row or column limits.")
                rows.append([Cell(value) for value in row])
        if file.stem in result:
            raise ExportError("Duplicate CSV result names.")
        result[file.stem] = rows
    return result


def offset_time(value: str) -> datetime:
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})", value):
        raise ValueError("Timestamp requires an explicit offset.")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.utcoffset() is None:
        raise ValueError("Timestamp requires an explicit offset.")
    return parsed


def validate(tables: dict, contract: dict) -> tuple[dict, dict]:
    issues, warnings, sections, source_rows = [], [], {}, {}

    def issue(code, sheet, row=None, column=None):
        issues.append({k: v for k, v in {"code": code, "sheet": sheet, "row": row, "column": column}.items() if v is not None})

    def meaningful(value):
        return (isinstance(value, str) and bool(value.strip()) and value == value.strip()
                and value.upper() not in {"UNKNOWN", "PENDING", "TBD", "REPLACE_ME"})

    # No formula evaluation anywhere, including explanatory workbook tabs.
    for name, rows in tables.items():
        for number, row in enumerate(rows, 1):
            for col, cell in enumerate(row, 1):
                if cell.kind in {"formula", "error"}:
                    issue("FORMULA_OR_ERROR_CELL", name, number, col)

    def records(name, headers, text_only=True):
        rows = tables.get(name)
        if not rows:
            issue("MISSING_SHEET_OR_HEADER", name)
            return []
        head = rows[0]
        while head and head[-1] == Cell():
            head = head[:-1]
        if [c.value for c in head] != headers or any(c.kind != "text" for c in head):
            issue("HEADERS_MUST_MATCH_EXPORT_CONTRACT", name, 1)
            return []
        result = []
        for number, row in enumerate(rows[1:], 2):
            if not any(c.value or c.kind in {"formula", "error"} for c in row):
                continue
            if len(row) > len(headers) and any(c.value or c.kind != "text" for c in row[len(headers):]):
                issue("UNEXPECTED_EXTRA_CELLS", name, number)
                continue
            values = {}
            for col, header in enumerate(headers):
                cell = row[col] if col < len(row) else Cell()
                if cell.kind not in {"formula", "error"} and cell.value and cell.kind != "text" and (text_only or header != "EXPORTED_ROWS"):
                    issue("LOSSLESS_TEXT_REQUIRED_REEXPORT_FROM_SQL", name, number, header)
                if any(ord(ch) < 32 and ch not in "\t\n\r" for ch in cell.value):
                    issue("CONTROL_CHARACTER", name, number, header)
                if len(cell.value) > 2000:
                    issue("CELL_TOO_LONG", name, number, header)
                values[header] = cell.value if cell.value != "" else None
            result.append((number, values))
        return result

    known = set(contract["sheets"]) | {"Manifest", "Coverage", "Readme", "Sources"}
    for name in tables.keys() - known:
        issue("UNEXPECTED_SHEET", name)
    manifest = {}
    for number, row in records("Manifest", ["FIELD", "VALUE"]):
        if not row["FIELD"] or row["FIELD"] in manifest:
            issue("MISSING_OR_DUPLICATE_MANIFEST_FIELD", "Manifest", number, "FIELD")
        else:
            manifest[row["FIELD"]] = row["VALUE"]
    for field in ("deployment_id", "source_timezone", "payment_reference", "exported_at"):
        if not meaningful(manifest.get(field)):
            issue("REQUIRED_MANIFEST_VALUE", "Manifest", column=field)
    if manifest.get("format_version") != VERSION or contract.get("version") != VERSION:
        issue("UNSUPPORTED_STAGING_VERSION", "Manifest", column="format_version")
    if manifest.get("data_classification") != "UAT":
        issue("CLASSIFICATION_MUST_REMAIN_UAT", "Manifest", column="data_classification")
    if manifest.get("read_consistency") != "SEPARATE_STATEMENTS":
        issue("UNVERIFIED_READ_CONSISTENCY", "Manifest", column="read_consistency")
    export_time = None
    if manifest.get("exported_at"):
        try:
            export_time = offset_time(manifest["exported_at"])
        except ValueError:
            issue("OFFSET_TIMESTAMP_REQUIRED", "Manifest", column="exported_at")
    if not manifest.get("exact_obpm_release"):
        warnings.append("Exact OBPM maintenance release still needs confirmation.")
    coverage = {}
    for number, row in records("Coverage", COVERAGE_COLUMNS, text_only=False):
        name = row["SHEET"]
        if name not in contract["sheets"] or name in coverage:
            issue("UNKNOWN_OR_DUPLICATE_COVERAGE_SHEET", "Coverage", number, "SHEET")
        else:
            coverage[name] = row
    for name, spec in contract["sheets"].items():
        parsed = records(name, spec["columns"])
        sections[name] = [row for _, row in parsed]
        source_rows[name] = [number for number, _ in parsed]
        cov = coverage.get(name)
        if cov is None:
            issue("MISSING_COVERAGE", name)
            continue
        status = cov["STATUS"]
        if status not in {"COMPLETE", "PARTIAL", "UNAVAILABLE", "NOT_REQUESTED"}:
            issue("INVALID_COVERAGE_STATUS", name)
        count = cov["EXPORTED_ROWS"] or ""
        if not INTEGER.fullmatch(count) or int(count) != len(parsed):
            issue("EXPORTED_ROW_COUNT_MISMATCH", name)
        if status in {"UNAVAILABLE", "NOT_REQUESTED"} and parsed:
            issue("UNQUERIED_SOURCE_CONTAINS_ROWS", name)
        if status != "COMPLETE" and not cov["REASON"]:
            issue("INCOMPLETE_COVERAGE_REQUIRES_REASON", name)
        if status in {"COMPLETE", "PARTIAL"}:
            if not cov["SCOPE"]:
                issue("QUERY_SCOPE_REQUIRED", name)
            try:
                observed = offset_time(cov["QUERY_OBSERVED_AT"] or "")
                if export_time and observed > export_time:
                    issue("OBSERVATION_AFTER_EXPORT", name)
            except ValueError:
                issue("OFFSET_TIMESTAMP_REQUIRED", name, column="QUERY_OBSERVED_AT")
        expected_counts, observed_values = set(), set()
        for number, row in parsed:
            for key in spec.get("requiredNonBlank", []):
                if not meaningful(row.get(key)):
                    issue("REQUIRED_SOURCE_VALUE", name, number, key)
            for key in spec.get("decimalColumns", []):
                value = row.get(key)
                if value is not None:
                    try:
                        if (not DECIMAL.fullmatch(value) or not Decimal(value).is_finite()
                                or len(Decimal(value).as_tuple().digits) > 64
                                or abs(Decimal(value).as_tuple().exponent) > 200):
                            raise InvalidOperation()
                    except InvalidOperation:
                        issue("EXACT_DECIMAL_TEXT_REQUIRED", name, number, key)
            for key in spec.get("localTimestampColumns", []):
                value = row.get(key)
                if value is not None:
                    try:
                        if not LOCAL_TIME.fullmatch(value):
                            raise ValueError()
                        datetime.fromisoformat(value)
                    except ValueError:
                        issue("LOCAL_SOURCE_TIMESTAMP_FORMAT", name, number, key)
            for key in spec.get("integerColumns", []):
                value = row.get(key)
                if value is not None and not INTEGER.fullmatch(value):
                    issue("NONNEGATIVE_INTEGER_TEXT_REQUIRED", name, number, key)
            for key in spec.get("numericIdentifierColumns", []):
                value = row.get(key)
                if value is not None and not re.fullmatch(r"[0-9]+", value):
                    issue("NUMERIC_IDENTIFIER_REQUIRES_ALL_DIGITS", name, number, key)
            for key in spec.get("uniqueKey", []):
                if row.get(key) and row[key] != row[key].strip():
                    issue("SOURCE_KEY_WHITESPACE_REQUIRES_MAPPING", name, number, key)
            if "SOURCE_TABLE" in row and row["SOURCE_TABLE"] != spec.get("sourceTable"):
                issue("SOURCE_TABLE_MISMATCH", name, number, "SOURCE_TABLE")
            value = row.get("SCOPE_ROW_COUNT")
            if not value or not INTEGER.fullmatch(value):
                issue("SOURCE_ROW_COUNT_REQUIRED", name, number, "SCOPE_ROW_COUNT")
            else:
                expected_counts.add(int(value))
            value = row.get("QUERY_OBSERVED_AT")
            try:
                observed_values.add(offset_time(value or ""))
                if value != cov["QUERY_OBSERVED_AT"]:
                    issue("COVERAGE_OBSERVATION_MISMATCH", name, number, "QUERY_OBSERVED_AT")
            except ValueError:
                issue("OFFSET_TIMESTAMP_REQUIRED", name, number, "QUERY_OBSERVED_AT")
        if len(expected_counts) > 1 or len(observed_values) > 1:
            issue("MIXED_QUERY_EXECUTIONS", name)
        if expected_counts:
            source_count = max(expected_counts)
            if source_count < len(parsed) or (status == "COMPLETE" and source_count != len(parsed)):
                issue("SOURCE_ROW_COUNT_MISMATCH_OR_TRUNCATION", name)
            if status == "PARTIAL" and source_count < len(parsed):
                issue("PARTIAL_EXPORT_EXCEEDS_SOURCE_ROWS", name)
        # Only source-backed unique keys are checked; do not invent event keys.
        key_columns = spec.get("uniqueKey", [])
        if key_columns:
            keys = [tuple(row.get(k) for k in key_columns) for _, row in parsed]
            if any(any(k is None for k in key) for key in keys) or len(keys) != len(set(keys)):
                issue("MISSING_OR_DUPLICATE_SOURCE_KEY", name)
        owner_field = spec.get("ownerField")
        if parsed and owner_field and not meaningful(manifest.get(owner_field)):
            issue("SOURCE_OWNER_REQUIRED", "Manifest", column=owner_field)
    for link in contract.get("relationships", []):
        parents = {tuple(row.get(k) for k in link["parentColumns"]) for row in sections.get(link["parentSheet"], [])}
        for number, row in zip(source_rows.get(link["childSheet"], []), sections.get(link["childSheet"], [])):
            key = tuple(row.get(k) for k in link["childColumns"])
            if key not in parents:
                if coverage.get(link["parentSheet"], {}).get("STATUS") == "COMPLETE":
                    issue("UNMATCHED_PARENT_REFERENCE", link["childSheet"], number)
                else:
                    warning = f"{link['childSheet']}: parent linkage is unverified because parent coverage is incomplete."
                    if warning not in warnings:
                        warnings.append(warning)
    lookup = contract.get("paymentLookup")
    if lookup:
        for number, row in zip(source_rows.get(lookup["sheet"], []), sections.get(lookup["sheet"], [])):
            if row.get(lookup["column"]) != manifest.get("payment_reference"):
                issue("PAYMENT_REFERENCE_MISMATCH", lookup["sheet"], number, lookup["column"])
    for selector in contract.get("selectors", []):
        name, column, field = selector["sheet"], selector["column"], selector["manifestField"]
        for number, row in zip(source_rows.get(name, []), sections.get(name, [])):
            if not meaningful(manifest.get(field)):
                issue("QUERY_SELECTOR_REQUIRED", "Manifest", column=field)
                break
            if row.get(column) != manifest[field]:
                issue("QUERY_SELECTOR_MISMATCH", name, number, column)
    nonempty = any(rows for name, rows in sections.items() if name != "FCR_Status")
    if not nonempty:
        issue("NO_TRANSACTION_ROWS_EXPORTED", "Manifest")
    warnings.extend([
        "Separate SQL statements can observe different database states; there is no common snapshot guarantee.",
        "Installed schema, status meanings, timezone and cross-product references require source reconciliation.",
        "Native OBPM columns have been discovered; view dependencies, keys and current-queue/attempt semantics still require verification.",
        "Omitted account keys limit account-specific ECA/detail/error and ledger-role correlation.",
        "Validation checks export structure, not authenticity, full redaction, payment outcome or bank-wide coverage.",
    ])
    report = {"formatVersion": VERSION, "dataClassification": "UAT", "structurallyValid": not issues,
              "readyForDashboard": False, "modelCalls": 0, "databaseCalls": 0,
              "rowCounts": {name: len(rows) for name, rows in sections.items()},
              "issues": issues, "warnings": warnings}
    staged = {"schemaVersion": VERSION, "dataClassification": "UAT", "manifest": manifest,
              "coverage": list(coverage.values()), "sections": sections,
              "readyForDashboard": False, "mappingStatus": "PENDING_INSTALLED_OBPM_MAPPING"}
    return report, staged


def private_output(path: Path) -> Path:
    resolved = path.resolve()
    if not resolved.is_relative_to(PRIVATE.resolve()):
        raise ExportError("Output must stay under runtime/obpm-uat/private (excluded from releases).")
    return resolved


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="Filled .xlsx or directory containing one CSV per data/metadata sheet")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--output-dir", type=Path, default=PRIVATE / "checked")
    parser.add_argument("--stage", action="store_true", help="Also save validated native rows in private staging JSON; never imports")
    args = parser.parse_args(argv)
    try:
        output = private_output(args.output_dir)
        contract = json.loads(args.contract.read_text(encoding="utf-8-sig"))
        if args.input.is_dir():
            tables = read_csv_directory(args.input)
            provenance = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(args.input.glob("*.csv"))}
        elif args.input.suffix.lower() == ".xlsx":
            tables = read_xlsx(args.input)
            provenance = {"workbookSha256": hashlib.sha256(args.input.read_bytes()).hexdigest()}
        else:
            raise ExportError("Input must be .xlsx or a CSV directory.")
        report, staged = validate(tables, contract)
        # A new receipt directory prevents an earlier successful staging file
        # from being mistaken for output from a failed later validation.
        output.mkdir(parents=True, exist_ok=False)
        report["sourceHashes"] = provenance
        report["contractSha256"] = hashlib.sha256(args.contract.read_bytes()).hexdigest()
        (output / "validation-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        if args.stage and report["structurallyValid"]:
            staged["sourceHashes"] = provenance
            staged["contractSha256"] = report["contractSha256"]
            (output / "native-evidence.json").write_text(json.dumps(staged, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"structurallyValid": report["structurallyValid"], "readyForDashboard": False,
                          "issueCount": len(report["issues"]), "report": str(output / "validation-report.json")}))
        return 0 if report["structurallyValid"] else 2
    except (OSError, ValueError, KeyError, IndexError, TypeError, zipfile.BadZipFile, ET.ParseError, csv.Error):
        # Do not echo exception strings that could contain raw customer fields.
        print("Unable to read or validate this export. Check the file format, contract and a new private output directory.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
