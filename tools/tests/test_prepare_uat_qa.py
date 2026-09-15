"""Original small function-export fixtures for private Q&A preparation."""
import copy
from datetime import timedelta
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_uat_qa import GROUPS, build_bundle, canonical_hash, exact_duration, history_checks
from validate_uat_extract import Cell, ExportError


def fixture():
    ref = "0012345678901234567890123"
    common = ["SOURCE_TABLE", "QUERY_OBSERVED_AT", "SCOPE_ROW_COUNT"]
    columns = {
        "PAYMENT": common + ["REFTXNNUMBER", "UTR_REF_NO", "NUMAMOUNT_4038", "CODCURR"],
        "HOST": common + ["REF_TXN_NO", "COD_ORG_BRN", "COD_ORG_BANK", "AMT_TXN_TCY"],
        "HISTORY": common + ["REF_TXN_NO", "COD_ORG_BRN", "COD_ORG_BANK", "DAT_TXN"],
        "STATUS": common + ["CODSTATUS"],
    }
    values = {"PAYMENT": [[ref, "TEST-UTR", "123.456", "INR"]],
              "HOST": [[ref, "101", "7", "123.456"]],
              "HISTORY": [[ref, "101", "7", "2026-01-01T12:00:00"], [ref, "101", "7", "2026-01-01T12:03:05"]],
              "STATUS": []}
    contract, groups = {"sheets": {}}, {}
    for kind, (sheet, _) in GROUPS.items():
        table = "ORIGINAL_" + kind
        contract["sheets"][sheet] = {"columns": columns[kind], "sourceTable": table}
        rows = [[Cell("")] + [Cell(c) for c in columns[kind]]]
        for index, record in enumerate(values[kind], 1):
            rows.append([Cell(str(index))] + [Cell(v) for v in [table, "2026-01-02T12:00:00+05:30", str(len(values[kind])), *record]])
        groups[kind] = {"file": kind + ".xlsx", "sha256": "a" * 64, "tables": {"Sheet1": rows, "Sheet2": []}}
    return groups, contract, ref


class PrepareQaTest(unittest.TestCase):
    def build(self, groups=None, contract=None):
        original, original_contract, ref = fixture()
        return build_bundle(groups or original, contract or original_contract, [], "original-export", "northstar", ref)

    def test_exact_text_and_empty_coverage_without_answers(self):
        result = self.build()
        self.assertEqual(result["amount"], "123.456")
        self.assertTrue(result["paymentReference"].startswith("00"))
        self.assertEqual(result["coverage"][-1], {"name": "STATUS", "rowCount": 0, "completion": "EXPORT_ONLY_UNVERIFIED"})
        self.assertNotIn("answer", result)
        self.assertEqual(result["evidenceHash"], canonical_hash(result))
        self.assertIn('"historyObservationSpanSeconds":185', result["documents"][-1]["content"])

    def test_changed_data_changes_context_and_hash(self):
        groups, contract, _ = fixture()
        before = self.build(groups, contract)
        groups["PAYMENT"]["tables"]["Sheet1"][1][-2] = Cell("998.765")
        after = self.build(groups, contract)
        self.assertNotEqual(before["evidenceHash"], after["evidenceHash"])
        self.assertEqual(after["amount"], "998.765")
        self.assertIn('"hostAmountsMatchPayment":false', after["documents"][-1]["content"])

    def test_numeric_reference_rejected(self):
        groups, contract, _ = fixture()
        groups["PAYMENT"]["tables"]["Sheet1"][1][4] = Cell("123456789012345000000000", "number")
        with self.assertRaisesRegex(ExportError, "must be text"):
            self.build(groups, contract)

    def test_different_payment_and_scope_rejected(self):
        groups, contract, _ = fixture()
        groups["HOST"]["tables"]["Sheet1"][1][4] = Cell("OTHER-REF")
        with self.assertRaisesRegex(ExportError, "different payment"):
            self.build(groups, contract)
        groups, contract, _ = fixture()
        groups["HOST"]["tables"]["Sheet1"][1][5] = Cell("202")
        with self.assertRaisesRegex(ExportError, "branch/bank"):
            self.build(groups, contract)

    def test_false_scope_count_rejected(self):
        groups, contract, _ = fixture()
        groups["HISTORY"]["tables"]["Sheet1"][1][3] = Cell("1")
        with self.assertRaisesRegex(ExportError, "SCOPE_ROW_COUNT"):
            self.build(groups, contract)

    def test_hash_ignores_key_order_but_includes_documents_and_tenant(self):
        value = self.build()
        reverse = dict(reversed(list(value.items())))
        self.assertEqual(canonical_hash(value), canonical_hash(reverse))
        altered = copy.deepcopy(value)
        altered["documents"][0]["content"] += " changed"
        self.assertNotEqual(canonical_hash(value), canonical_hash(altered))
        altered = copy.deepcopy(value)
        altered["tenantId"] = "silverline"
        self.assertNotEqual(canonical_hash(value), canonical_hash(altered))

    def test_history_raw_field_changes_retain_original_row_documents_and_provenance(self):
        groups, contract, _ = fixture()
        columns = contract["sheets"]["FCR_History"]["columns"]
        columns.extend(["COD_MSG_STAT", "COD_ACCT_STAT"])
        rows = groups["HISTORY"]["tables"]["Sheet1"]
        rows[0].extend([Cell("COD_MSG_STAT"), Cell("COD_ACCT_STAT")])
        rows[1].extend([Cell("4"), Cell("A")])
        rows[2].extend([Cell("9"), Cell("A")])
        original = copy.deepcopy(groups)
        result = self.build(groups, contract)
        checks = json.loads(next(d["content"] for d in result["documents"] if d["id"] == "EXPORT-CHECKS"))
        comparison = checks["historyAdjacentComparisons"][0]
        self.assertEqual(comparison["changedFields"], [{"field": "COD_MSG_STAT", "before": "4", "after": "9"}])
        self.assertEqual((comparison["earlierSourceId"], comparison["laterSourceId"]), ("HISTORY-ROW-1", "HISTORY-ROW-2"))
        self.assertEqual(comparison["elapsedSourceLocalSeconds"], 185)
        self.assertEqual(comparison["elapsedSourceLocalHumanReadable"], "0 hours 3 minutes 5 seconds")
        for index, value in enumerate(("4", "9"), 1):
            document = next(d for d in result["documents"] if d["id"] == f"HISTORY-ROW-{index}")
            self.assertEqual(json.loads(document["content"])["COD_MSG_STAT"], value)
            self.assertEqual(document["source"]["file"], "HISTORY.xlsx")
            self.assertEqual(document["source"]["range"], f"B{index + 1}:J{index + 1}")
        self.assertEqual(groups, original)
        self.assertEqual(result["evidenceHash"], canonical_hash(result))


class HistoryChecksTest(unittest.TestCase):
    def record(self, time, state="A", **values):
        return {"SOURCE_TABLE": "ORIGINAL_HISTORY", "QUERY_OBSERVED_AT": "2026-01-03T00:00:00+05:30",
                "SCOPE_ROW_COUNT": "3", "DAT_TXN": time, "REF_TXN_NO": "ORIGINAL-REF",
                "COD_MSG_STAT": state, "COD_ACCT_STAT": "ACCOUNT-STATE-UNKNOWN", **values}

    def test_duration_arithmetic_has_no_float_rounding_or_minute_conversion_guess(self):
        self.assertEqual(exact_duration(timedelta(seconds=2242)),
                         {"seconds": 2242, "humanReadable": "0 hours 37 minutes 22 seconds"})
        self.assertEqual(exact_duration(timedelta(hours=26, minutes=3, seconds=4)),
                         {"seconds": 93784, "humanReadable": "26 hours 3 minutes 4 seconds"})
        self.assertEqual(exact_duration(timedelta(seconds=1, microseconds=1)),
                         {"seconds": "1.000001", "humanReadable": "0 hours 0 minutes 1.000001 seconds"})
        with self.assertRaises(ExportError):
            exact_duration(timedelta(microseconds=-1))

    def test_unsorted_file_rows_are_compared_in_local_timestamp_order_with_original_ids(self):
        records = [self.record("2026-01-01T13:02:00", "C"), self.record("2026-01-01T12:00:00", "A"),
                   self.record("2026-01-01T12:01:30", "B")]
        original = copy.deepcopy(records)
        checks = history_checks(records)
        self.assertEqual([g["sourceIds"] for g in checks["historyOrdering"]["timestampGroups"]],
                         [["HISTORY-ROW-2"], ["HISTORY-ROW-3"], ["HISTORY-ROW-1"]])
        comparisons = checks["historyAdjacentComparisons"]
        self.assertEqual([(c["earlierSourceId"], c["laterSourceId"]) for c in comparisons],
                         [("HISTORY-ROW-2", "HISTORY-ROW-3"), ("HISTORY-ROW-3", "HISTORY-ROW-1")])
        self.assertEqual([c["elapsedSourceLocalSeconds"] for c in comparisons], [90, 3630])
        self.assertEqual(checks["historyObservationSpanSeconds"], 3720)
        self.assertEqual(checks["historyObservationSpanSourceIds"], {"earliest": ["HISTORY-ROW-2"], "latest": ["HISTORY-ROW-1"]})
        self.assertEqual(records, original)
        self.assertIn("does not prove the cause", checks["historyComparisonMeaning"])

    def test_adjacent_changes_capture_intermediate_reversal_without_inventing_events(self):
        records = [self.record("2026-01-01T12:00:00", "A"), self.record("2026-01-01T12:00:10", "B"),
                   self.record("2026-01-01T12:00:20", "A")]
        checks = history_checks(records)
        self.assertEqual([c["changedFields"] for c in checks["historyAdjacentComparisons"]],
                         [[{"field": "COD_MSG_STAT", "before": "A", "after": "B"}],
                          [{"field": "COD_MSG_STAT", "before": "B", "after": "A"}]])
        self.assertNotIn("event", checks)
        self.assertNotIn("rootCause", checks)

    def test_tied_different_rows_have_no_invented_sequence_or_adjacent_pair(self):
        records = [self.record("2026-01-01T12:00:00", "A"), self.record("2026-01-01T12:01:00", "B"),
                   self.record("2026-01-01T12:01:00", "C"), self.record("2026-01-01T12:02:00", "D")]
        checks = history_checks(records)
        self.assertEqual(checks["historyAdjacentComparisons"], [])
        self.assertEqual(len(checks["historyAmbiguousAdjacentGroups"]), 2)
        tied = checks["historyOrdering"]["timestampGroups"][1]
        self.assertEqual(tied["sourceIds"], ["HISTORY-ROW-2", "HISTORY-ROW-3"])
        self.assertFalse(tied["orderWithinGroupKnown"])
        self.assertEqual(checks["historyOrdering"]["duplicateRecordGroups"], [])
        self.assertEqual(checks["historyObservationSpanSeconds"], 120)

    def test_exact_duplicate_rows_are_preserved_and_identified_without_dedup_or_retry_claim(self):
        row = self.record("2026-01-01T12:00:00")
        checks = history_checks([row, copy.deepcopy(row)])
        self.assertEqual(checks["historyOrdering"]["duplicateRecordGroups"], [["HISTORY-ROW-1", "HISTORY-ROW-2"]])
        self.assertEqual(checks["historyAdjacentComparisons"], [])
        self.assertEqual(checks["historyObservationSpanSeconds"], 0)
        self.assertEqual(checks["historyObservationSpanHumanReadable"], "0 hours 0 minutes 0 seconds")

    def test_missing_timestamps_remain_explicit_exclusions_not_zero_or_guessed_order(self):
        checks = history_checks([self.record(""), self.record("2026-01-01T12:00:00"), self.record("2026-01-01T12:01:00")])
        self.assertEqual(checks["historyOrdering"]["missingTimestampSourceIds"], ["HISTORY-ROW-1"])
        self.assertFalse(checks["historyOrdering"]["allExportedRowsHaveTimestamps"])
        self.assertEqual(checks["historyAdjacentComparisons"][0]["earlierSourceId"], "HISTORY-ROW-2")
        self.assertEqual(history_checks([])["historyAdjacentComparisons"], [])
        self.assertNotIn("historyObservationSpanSeconds", history_checks([self.record("2026-01-01T12:00:00")]))

    def test_unverified_timezone_and_invalid_or_date_only_values_are_rejected(self):
        for value in ("2026-01-01", "2026-01-01T12:00:00Z", "2026-01-01T12:00:00+05:30",
                      "2026-02-30T12:00:00", "not-a-timestamp", "2026-01-01T12:00:00.1234567"):
            with self.subTest(value=value), self.assertRaises(ExportError):
                history_checks([self.record(value)])

    def test_fractional_local_timestamps_keep_exact_difference_and_raw_inputs(self):
        checks = history_checks([self.record("2026-01-01T12:00:00.999999"), self.record("2026-01-01T12:00:01.000000")])
        interval = checks["historyAdjacentComparisons"][0]
        self.assertEqual(interval["elapsedSourceLocalSeconds"], "0.000001")
        self.assertEqual(interval["earlierSourceLocalTimestamp"], "2026-01-01T12:00:00.999999")
        self.assertEqual(interval["laterSourceLocalTimestamp"], "2026-01-01T12:00:01.000000")

    def test_export_metadata_changes_are_not_native_state_changes(self):
        records = [self.record("2026-01-01T12:00:00"),
                   self.record("2026-01-01T12:01:00", QUERY_OBSERVED_AT="2026-01-04T00:00:00+05:30", SCOPE_ROW_COUNT="4")]
        self.assertEqual(history_checks(records)["historyAdjacentComparisons"][0]["changedFields"], [])


if __name__ == "__main__":
    unittest.main()
