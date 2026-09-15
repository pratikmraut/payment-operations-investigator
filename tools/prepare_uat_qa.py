"""Prepare private, exact-text FCR function evidence for local model questions.

No database, HTTP, model or answer-generation calls. This is a narrower export
review contract, not a replacement for the full UAT staging validator.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta
from decimal import Decimal, InvalidOperation
import hashlib
import json
from pathlib import Path
import re

from validate_uat_extract import Cell, ExportError, read_xlsx, DEFAULT_CONTRACT, PRIVATE

GROUPS = {
    "PAYMENT": ("FCR_Payment", "AP_BA_NEFT_PAYMENT_INQ"),
    "HOST": ("FCR_Host", "AP_BA_NEFT_HOST_INQ"),
    "HISTORY": ("FCR_History", "AP_BA_NEFT_HISTORY_INQ"),
    "STATUS": ("FCR_Status", "AP_BA_NEFT_STATUS_INQ"),
}
SAFE_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}\Z")
SOURCE_LOCAL_TIME = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?\Z")
EXPORT_METADATA = {"SOURCE_TABLE", "QUERY_OBSERVED_AT", "SCOPE_ROW_COUNT"}


def canonical_hash(bundle: dict) -> str:
    value = {k: v for k, v in bundle.items() if k != "evidenceHash"}
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                    ensure_ascii=False).encode("utf-8")).hexdigest()


def col_name(number: int) -> str:
    result = ""
    while number:
        number, remainder = divmod(number - 1, 26)
        result = chr(65 + remainder) + result
    return result


def exact_duration(delta: timedelta) -> dict:
    """Represent a nonnegative local-time difference without floats or rounding."""
    microseconds = (delta.days * 86400 + delta.seconds) * 1000000 + delta.microseconds
    if microseconds < 0:
        raise ExportError("An observation interval cannot be negative.")
    whole_seconds, fractional = divmod(microseconds, 1000000)
    hours, remainder = divmod(whole_seconds, 3600)
    minutes, seconds = divmod(remainder, 60)
    fraction = f".{fractional:06d}".rstrip("0") if fractional else ""
    return {"seconds": f"{whole_seconds}{fraction}" if fractional else whole_seconds,
            "humanReadable": f"{hours} hours {minutes} minutes {seconds}{fraction} seconds"}


def history_checks(records: list[dict]) -> dict:
    """Compare exported observations, never infer an operational event sequence."""
    timed: dict[datetime, list[tuple[str, dict]]] = {}
    missing = []
    for index, record in enumerate(records, 1):
        source_id = f"HISTORY-ROW-{index}"
        value = record["DAT_TXN"]
        if not value:
            missing.append(source_id)
            continue
        if not SOURCE_LOCAL_TIME.fullmatch(value):
            raise ExportError("History DAT_TXN must be a source-local ISO timestamp without a timezone offset.")
        try:
            instant = datetime.fromisoformat(value)
        except ValueError as exc:
            raise ExportError("History DAT_TXN contains an invalid calendar timestamp.") from exc
        timed.setdefault(instant, []).append((source_id, record))
    ordered = sorted(timed)
    result = {
        "historyOrdering": {
            "basis": "DAT_TXN source-local timestamp, ascending; file order and query observation time do not establish history order.",
            "timestampGroups": [
                {"sourceLocalTimestamp": instant.isoformat(),
                 "sourceIds": [source_id for source_id, _ in timed[instant]],
                 "orderWithinGroupKnown": len(timed[instant]) == 1}
                for instant in ordered],
            "missingTimestampSourceIds": missing,
            "allExportedRowsHaveTimestamps": not missing,
            "duplicateRecordGroups": []},
        "historyAdjacentComparisons": [],
        "historyAmbiguousAdjacentGroups": [],
        "historyComparisonMeaning": (
            "Exact raw field differences between adjacent timestamp groups among dated exported records only. "
            "DAT_TXN is reported separately; export metadata fields are excluded from changedFields. "
            "Tied timestamps have no verified order and are not paired as transitions. Missing timestamps and "
            "unexported records may exist. A field difference does not prove the cause, retry, reprocessing, "
            "queue entry, send, completion, or any other operational event. Unchanged fields do not prove no event occurred.")}
    duplicate_groups: dict[str, list[str]] = {}
    for index, record in enumerate(records, 1):
        duplicate_groups.setdefault(json.dumps(record, sort_keys=True, ensure_ascii=False), []).append(f"HISTORY-ROW-{index}")
    result["historyOrdering"]["duplicateRecordGroups"] = [ids for ids in duplicate_groups.values() if len(ids) > 1]
    if len(ordered) >= 1 and sum(len(rows) for rows in timed.values()) > 1:
        span = exact_duration(ordered[-1] - ordered[0])
        result.update({"historyObservationSpanSeconds": span["seconds"],
                       "historyObservationSpanHumanReadable": span["humanReadable"],
                       "historyObservationSpanSourceIds": {
                           "earliest": [source_id for source_id, _ in timed[ordered[0]]],
                           "latest": [source_id for source_id, _ in timed[ordered[-1]]]},
                       "intervalMeaning": "Span between exported source-local history timestamps; not a measured queue wait or SLA breach. Timezone and timestamp semantics are unverified."})
    for earlier, later in zip(ordered, ordered[1:]):
        before, after = timed[earlier], timed[later]
        if len(before) != 1 or len(after) != 1:
            result["historyAmbiguousAdjacentGroups"].append({
                "earlierSourceIds": [source_id for source_id, _ in before],
                "laterSourceIds": [source_id for source_id, _ in after],
                "reason": "At least one timestamp group has multiple rows; no unique adjacent row comparison is established."})
            continue
        before_id, before_record = before[0]
        after_id, after_record = after[0]
        duration = exact_duration(later - earlier)
        fields = set(before_record) | set(after_record)
        excluded = EXPORT_METADATA | {"DAT_TXN"}
        changes = [{"field": key, "before": before_record.get(key), "after": after_record.get(key)}
                   for key in sorted(fields - excluded) if before_record.get(key) != after_record.get(key)]
        result["historyAdjacentComparisons"].append({
            "earlierSourceId": before_id, "laterSourceId": after_id,
            "earlierSourceLocalTimestamp": before_record["DAT_TXN"],
            "laterSourceLocalTimestamp": after_record["DAT_TXN"],
            "elapsedSourceLocalSeconds": duration["seconds"],
            "elapsedSourceLocalHumanReadable": duration["humanReadable"],
            "changedFields": changes})
    return result


def extract(tables: dict, spec: dict) -> tuple[list[dict], list[int], int]:
    populated = [(name, rows) for name, rows in tables.items() if any(rows)]
    if len(populated) != 1 or populated[0][0] != "Sheet1":
        raise ExportError("Each function workbook needs one populated Sheet1; other sheets must be empty.")
    rows = populated[0][1]
    expected = spec["columns"]
    offset = 1 if rows[0] and not rows[0][0].value else 0
    if [c.value for c in rows[0][offset:]] != expected:
        raise ExportError("Function result headers do not exactly match the selected contract.")
    records, positions = [], []
    for position, raw in enumerate(rows[1:], 2):
        if not raw:
            continue
        if any(c.kind != "text" for c in raw[offset:]):
            raise ExportError("Export data must be text: numeric, date, formula and error cells are unsupported.")
        if len(raw) > len(expected) + offset:
            raise ExportError("Result row has extra columns.")
        if offset and raw[0].value and not raw[0].value.isdigit():
            raise ExportError("Unnamed first column must be a display ordinal.")
        cells = raw[offset:] + [Cell()] * (len(expected) - len(raw[offset:]))
        if not any(c.value for c in cells):
            continue
        record = dict(zip(expected, (c.value for c in cells)))
        if record["SOURCE_TABLE"] != spec["sourceTable"]:
            raise ExportError("Source table differs from the function contract.")
        for key in spec.get("requiredNonBlank", []):
            if not record[key]:
                raise ExportError("A required function field is blank.")
        for key in spec.get("decimalColumns", []):
            if record[key]:
                try:
                    if not Decimal(record[key]).is_finite():
                        raise InvalidOperation()
                except InvalidOperation as exc:
                    raise ExportError("A monetary field is not a finite decimal.") from exc
        records.append(record)
        positions.append(position)
    if any(r["SCOPE_ROW_COUNT"] != str(len(records)) for r in records):
        raise ExportError("Exported rows disagree with SCOPE_ROW_COUNT.")
    return records, positions, offset


def build_bundle(groups: dict, contract: dict, knowledge: list, snapshot_id: str,
                 tenant_id: str, expected_reference: str) -> dict:
    if not SAFE_ID.fullmatch(snapshot_id) or not SAFE_ID.fullmatch(tenant_id):
        raise ExportError("Snapshot and tenant identifiers must be safe identifiers of at most 80 characters.")
    documents, coverage, parsed, hashes = [], [], {}, {}
    for kind, (sheet, _) in GROUPS.items():
        source = groups[kind]
        spec = contract["sheets"][sheet]
        records, positions, offset = extract(source["tables"], spec)
        parsed[kind] = records
        hashes[kind] = source["sha256"]
        coverage.append({"name": kind, "rowCount": len(records), "completion": "EXPORT_ONLY_UNVERIFIED"})
        source_ref = {"file": source["file"], "sheet": "Sheet1",
                      "range": f"{col_name(offset + 1)}1:{col_name(offset + len(spec['columns']))}1"}
        documents.append({"id": f"{kind}-COVERAGE", "kind": "evidence", "title": f"{kind} export coverage",
                          "content": json.dumps({"sourceTable": spec["sourceTable"], "exportedRowCount": len(records),
                            "sourceSha256": source["sha256"], "fieldNames": spec["columns"],
                            "completion": "Only the exported file is observed; function return value, cursor fetch completion and full-system coverage are unverified."}),
                          "source": source_ref})
        for index, (record, row) in enumerate(zip(records, positions), 1):
            documents.append({"id": f"{kind}-ROW-{index}", "kind": "evidence",
                              "title": f"{kind} exported row {index}",
                              "content": json.dumps(record, ensure_ascii=False, separators=(",", ":")),
                              "source": {**source_ref, "range": f"{col_name(offset + 1)}{row}:{col_name(offset + len(spec['columns']))}{row}"}})
    if len(parsed["PAYMENT"]) != 1:
        raise ExportError("Select exactly one originating payment for this review workspace.")
    payment = parsed["PAYMENT"][0]
    reference = payment["REFTXNNUMBER"]
    if reference != expected_reference:
        raise ExportError("Export reference differs from the selected reference.")
    if any(r["REF_TXN_NO"] != reference for kind in ("HOST", "HISTORY") for r in parsed[kind]):
        raise ExportError("Function exports contain different payment references.")
    scopes = {(r["COD_ORG_BRN"], r["COD_ORG_BANK"]) for kind in ("HOST", "HISTORY") for r in parsed[kind]}
    if len(scopes) > 1:
        raise ExportError("Function exports cross branch/bank scopes; review them separately.")
    warnings = ["UAT exports; source query completion and deployed source version are unverified.",
                "Source DATE timezone is unconfirmed. Separate SELECTs are not an atomic snapshot.",
                "Model-written answers require evidence review; valid source IDs do not prove every claim."]
    if not parsed["STATUS"]:
        warnings.append("Status lookup has zero exported data rows; successful zero-row completion is unverified.")
    calculations = {"referenceAgreement": True, "sourceHashes": hashes,
                    "hostAmountsMatchPayment": all(Decimal(r["AMT_TXN_TCY"]) == Decimal(payment["NUMAMOUNT_4038"]) for r in parsed["HOST"]) if parsed["HOST"] else None}
    calculations.update(history_checks(parsed["HISTORY"]))
    calculations["inputs"] = [d["id"] for d in documents if "-ROW-" in d["id"]]
    documents.append({"id": "EXPORT-CHECKS", "kind": "evidence", "title": "Calculated export consistency",
                      "content": json.dumps(calculations, separators=(",", ":")),
                      "source": {"file": "prepare_uat_qa.py", "locator": "Exact decimal/reference comparison, source-local history timestamp grouping, duration arithmetic and adjacent raw-field comparison; source row IDs listed in content."}})
    for item in knowledge:
        if set(item) != {"id", "kind", "title", "content", "source"} or item["kind"] != "knowledge":
            raise ExportError("Knowledge must contain source documents, not case answer objects.")
    documents.extend(knowledge)
    if len(documents) > 100 or sum(len(d["id"]) + len(d["title"]) + len(d["content"])
                                  + sum(len(value) for value in d["source"].values() if value is not None)
                                  for d in documents) > 50000:
        raise ExportError("Evidence exceeds the bounded question workspace; narrow the export.")
    if len({d["id"] for d in documents}) != len(documents):
        raise ExportError("Document identifiers must be unique.")
    bundle = {"snapshotId": snapshot_id, "classification": "UAT", "title": "FCR NEFT export review",
              "paymentReference": reference, "utr": payment["UTR_REF_NO"],
              "amount": payment["NUMAMOUNT_4038"], "currency": payment["CODCURR"], "tenantId": tenant_id,
              "documents": documents, "coverage": coverage, "warnings": warnings}
    bundle["evidenceHash"] = canonical_hash(bundle)
    return bundle


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, required=True)
    parser.add_argument("--reference", required=True)
    parser.add_argument("--snapshot-id", required=True)
    parser.add_argument("--tenant-id", required=True)
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--knowledge", type=Path)
    parser.add_argument("--output-dir", type=Path, default=PRIVATE / "qa" / "bundles")
    args = parser.parse_args()
    try:
        output_dir = args.output_dir.resolve()
        if not output_dir.is_relative_to(PRIVATE.resolve()):
            raise ExportError("UAT bundles must remain under runtime/obpm-uat/private.")
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", args.reference):
            raise ExportError("Reference cannot contain file path characters.")
        contract = json.loads(args.contract.read_text(encoding="utf-8-sig"))
        groups = {}
        for kind, (_, function) in GROUPS.items():
            path = args.input_dir / f"{args.reference}_{function}.xlsx"
            groups[kind] = {"file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "tables": read_xlsx(path)}
        knowledge = json.loads(args.knowledge.read_text(encoding="utf-8-sig")) if args.knowledge else []
        bundle = build_bundle(groups, contract, knowledge, args.snapshot_id, args.tenant_id, args.reference)
        output_dir.mkdir(parents=True, exist_ok=True)
        target = output_dir / f"{args.snapshot_id}.json"
        if target.exists():
            raise ExportError("Snapshot filename exists; use a new snapshot identifier to preserve the earlier evidence.")
        with target.open("x", encoding="utf-8") as stream:
            json.dump(bundle, stream, ensure_ascii=False, indent=2)
        print(json.dumps({"prepared": True, "documents": len(bundle["documents"]),
                          "evidenceHash": bundle["evidenceHash"], "output": str(target), "modelCalls": 0}))
    except (ExportError, ValueError, KeyError, OSError) as exc:
        parser.exit(2, f"Preparation failed: {exc}\n")


if __name__ == "__main__":
    main()
