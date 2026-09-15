"""Original fixtures only: prove lossless staging and prevent false readiness."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from validate_uat_extract import Cell, ExportError, read_xlsx, validate, private_output


STAMP = "2026-09-13T11:00:00+05:30"
LONG_ID = "0012345678901234567890"
COLS = ["SOURCE_TABLE", "QUERY_OBSERVED_AT", "SCOPE_ROW_COUNT", "PAYMENT_ID", "AMOUNT", "CREATED_AT"]


def text_rows(rows):
    return [[Cell(str(value)) if value is not None else Cell() for value in row] for row in rows]


def fixture():
    contract = {"version": "neft-uat-stage-v1", "sheets": {"Payment": {
        "sourceTable": "ORIGINAL_TEST_PAYMENT", "columns": COLS,
        "requiredNonBlank": ["PAYMENT_ID"], "decimalColumns": ["AMOUNT"],
        "localTimestampColumns": ["CREATED_AT"], "uniqueKey": ["PAYMENT_ID"],
        "ownerField": "fcr_owner", "numericIdentifierColumns": ["PAYMENT_ID"]}},
        "paymentLookup": {"sheet": "Payment", "column": "PAYMENT_ID"}}
    tables = {
        "Manifest": text_rows([
            ["FIELD", "VALUE"], ["format_version", "neft-uat-stage-v1"],
            ["data_classification", "UAT"], ["deployment_id", "ORIGINAL-TEST"],
            ["source_timezone", "Asia/Kolkata"], ["payment_reference", LONG_ID],
            ["read_consistency", "SEPARATE_STATEMENTS"], ["exported_at", STAMP],
            ["fcr_owner", "TEST_OWNER"]]),
        "Coverage": text_rows([
            ["SHEET", "STATUS", "EXPORTED_ROWS", "QUERY_OBSERVED_AT", "SCOPE", "REASON"],
            ["Payment", "COMPLETE", "1", STAMP, "Original unit-test lookup", None]]),
        "Payment": text_rows([COLS, ["ORIGINAL_TEST_PAYMENT", STAMP, "1", LONG_ID, "123.456", "2026-09-13T10:00:00"]])}
    return tables, contract


class StagingValidationTest(unittest.TestCase):
    def setUp(self):
        self.tables, self.contract = fixture()

    def report(self):
        return validate(self.tables, self.contract)[0]

    def codes(self):
        return {issue["code"] for issue in self.report()["issues"]}

    def test_lossless_original_fixture_never_dashboard_ready(self):
        report, staged = validate(self.tables, self.contract)
        self.assertTrue(report["structurallyValid"])
        self.assertFalse(report["readyForDashboard"])
        self.assertEqual(report["modelCalls"], 0)
        self.assertEqual(staged["dataClassification"], "UAT")
        self.assertEqual(staged["sections"]["Payment"][0]["PAYMENT_ID"], LONG_ID)
        self.assertEqual(staged["sections"]["Payment"][0]["AMOUNT"], "123.456")
        self.assertNotIn(LONG_ID, json.dumps(report))

    def test_numeric_identifier_cell_requires_reexport(self):
        self.tables["Payment"][1][3] = Cell("12345678901234568000", "number")
        self.assertIn("LOSSLESS_TEXT_REQUIRED_REEXPORT_FROM_SQL", self.codes())

    def test_scientific_notation_identifier_is_not_repaired(self):
        self.tables["Payment"][1][3] = Cell("1.23456789012346E+19")
        self.assertIn("NUMERIC_IDENTIFIER_REQUIRES_ALL_DIGITS", self.codes())

    def test_numeric_money_cell_requires_reexport(self):
        self.tables["Payment"][1][4] = Cell("123.456", "number")
        self.assertIn("LOSSLESS_TEXT_REQUIRED_REEXPORT_FROM_SQL", self.codes())

    def test_exact_tm9_exponent_and_signed_amount_preserved(self):
        self.tables["Payment"][1][4] = Cell("-1.23456E-120")
        report, staged = validate(self.tables, self.contract)
        self.assertTrue(report["structurallyValid"])
        self.assertEqual(staged["sections"]["Payment"][0]["AMOUNT"], "-1.23456E-120")

    def test_tm9_fraction_without_leading_zero(self):
        for value in [".125", "-.125"]:
            self.tables["Payment"][1][4] = Cell(value)
            report, staged = validate(self.tables, self.contract)
            self.assertTrue(report["structurallyValid"])
            self.assertEqual(staged["sections"]["Payment"][0]["AMOUNT"], value)

    def test_invalid_money_formats(self):
        for value in ["NaN", "Infinity", "1,234.56", "1.23E999", "1 234", " 12.3 "]:
            with self.subTest(value=value):
                self.tables["Payment"][1][4] = Cell(value)
                self.assertIn("EXACT_DECIMAL_TEXT_REQUIRED", self.codes())

    def test_impossible_local_time_and_unjustified_z(self):
        for value in ["2026-02-30T10:00:00", "2026-09-13T10:00:00Z"]:
            self.tables["Payment"][1][5] = Cell(value)
            self.assertIn("LOCAL_SOURCE_TIMESTAMP_FORMAT", self.codes())

    def test_formula_rejected_even_with_cached_value(self):
        self.tables["Payment"][1][4] = Cell("123.456", "formula")
        self.assertIn("FORMULA_OR_ERROR_CELL", self.codes())

    def test_error_and_formula_on_readme_rejected(self):
        for kind in ["error", "formula"]:
            self.tables["Readme"] = [[Cell("", kind)]]
            self.assertIn("FORMULA_OR_ERROR_CELL", self.codes())

    def test_missing_or_unknown_headers(self):
        self.tables["Payment"][0][3] = Cell("ACCOUNT_NUMBER")
        self.assertIn("HEADERS_MUST_MATCH_EXPORT_CONTRACT", self.codes())

    def test_extra_sheet_not_silently_skipped(self):
        self.tables["Extra"] = text_rows([["secret"]])
        self.assertIn("UNEXPECTED_SHEET", self.codes())

    def test_truncated_complete_export(self):
        self.tables["Payment"][1][2] = Cell("10")
        self.assertIn("SOURCE_ROW_COUNT_MISMATCH_OR_TRUNCATION", self.codes())

    def test_partial_export_retains_rows_and_missing_evidence(self):
        self.tables["Payment"][1][2] = Cell("10")
        self.tables["Coverage"][1][1] = Cell("PARTIAL")
        self.tables["Coverage"][1][5] = Cell("User exported only first row")
        self.assertTrue(self.report()["structurallyValid"])
        self.assertFalse(self.report()["readyForDashboard"])

    def test_export_count_mismatch(self):
        self.tables["Coverage"][1][2] = Cell("2")
        self.assertIn("EXPORTED_ROW_COUNT_MISMATCH", self.codes())

    def test_mixed_query_observations(self):
        row = copy.deepcopy(self.tables["Payment"][1])
        row[1] = Cell("2026-09-13T10:59:00+05:30")
        row[3] = Cell("900")
        self.tables["Payment"].append(row)
        self.assertIn("MIXED_QUERY_EXECUTIONS", self.codes())

    def test_duplicate_verified_key(self):
        self.tables["Payment"].append(copy.deepcopy(self.tables["Payment"][1]))
        self.tables["Coverage"][1][2] = Cell("2")
        for row in self.tables["Payment"][1:]:
            row[2] = Cell("2")
        self.assertIn("MISSING_OR_DUPLICATE_SOURCE_KEY", self.codes())

    def test_unkeyed_history_is_not_deduplicated(self):
        self.test_duplicate_verified_key()
        self.contract["sheets"]["Payment"].pop("uniqueKey")
        report, staged = validate(self.tables, self.contract)
        self.assertTrue(report["structurallyValid"])
        self.assertEqual(len(staged["sections"]["Payment"]), 2)

    def test_unqueried_sheet_cannot_contain_data(self):
        self.tables["Coverage"][1][1] = Cell("NOT_REQUESTED")
        self.tables["Coverage"][1][5] = Cell("Query not run")
        self.assertIn("UNQUERIED_SOURCE_CONTAINS_ROWS", self.codes())

    def test_untouched_empty_template_not_valid_extract(self):
        self.tables["Payment"] = self.tables["Payment"][:1]
        self.tables["Coverage"][1] = text_rows([["Payment", "NOT_REQUESTED", "0", None, None, "Query not run"]])[0]
        self.assertIn("NO_TRANSACTION_ROWS_EXPORTED", self.codes())

    def test_dictionary_only_not_transaction_export(self):
        self.tables["FCR_Status"] = self.tables.pop("Payment")
        self.contract["sheets"]["FCR_Status"] = self.contract["sheets"].pop("Payment")
        self.tables["Coverage"][1][0] = Cell("FCR_Status")
        self.assertIn("NO_TRANSACTION_ROWS_EXPORTED", self.codes())

    def test_classification_cannot_be_changed_to_synthetic(self):
        self.tables["Manifest"][2][1] = Cell("SYNTHETIC")
        self.assertIn("CLASSIFICATION_MUST_REMAIN_UAT", self.codes())

    def test_missing_scope_and_placeholder_owner(self):
        self.tables["Manifest"][-1][1] = Cell("PENDING")
        self.tables["Manifest"][4][1] = Cell("   ")
        self.assertIn("SOURCE_OWNER_REQUIRED", self.codes())
        self.assertIn("REQUIRED_MANIFEST_VALUE", self.codes())

    def test_original_reference_not_trimmed_or_replaced(self):
        self.tables["Payment"][1][3] = Cell(" " + LONG_ID)
        self.assertIn("SOURCE_KEY_WHITESPACE_REQUIRES_MAPPING", self.codes())
        self.assertIn("PAYMENT_REFERENCE_MISMATCH", self.codes())

    def test_naive_observation_time_rejected(self):
        self.tables["Coverage"][1][3] = Cell("2026-09-13T11:00:00")
        self.assertIn("OFFSET_TIMESTAMP_REQUIRED", self.codes())

    def test_orphan_requires_complete_parent_evidence(self):
        self.contract["sheets"]["Parent"] = copy.deepcopy(self.contract["sheets"]["Payment"])
        self.contract["relationships"] = [{"parentSheet": "Parent", "parentColumns": ["PAYMENT_ID"],
                                          "childSheet": "Payment", "childColumns": ["PAYMENT_ID"]}]
        self.tables["Parent"] = text_rows([COLS])
        self.tables["Coverage"].extend(text_rows([["Parent", "COMPLETE", "0", STAMP, "Original unit-test lookup", None]]))
        self.tables["Payment"].insert(1, [])
        report = self.report()
        issue = next(i for i in report["issues"] if i["code"] == "UNMATCHED_PARENT_REFERENCE")
        self.assertEqual(issue["row"], 3)
        self.tables["Coverage"][-1][1] = Cell("UNAVAILABLE")
        self.tables["Coverage"][-1][5] = Cell("Parent query unavailable")
        report = self.report()
        self.assertTrue(report["structurallyValid"])
        self.assertTrue(any("parent linkage" in w for w in report["warnings"]))

    def test_source_scope_selectors(self):
        self.contract["selectors"] = [{"sheet": "Payment", "column": "PAYMENT_ID", "manifestField": "fcr_ref_txn_no"}]
        self.assertIn("QUERY_SELECTOR_REQUIRED", self.codes())
        self.tables["Manifest"].extend(text_rows([["fcr_ref_txn_no", "900"]]))
        self.assertIn("QUERY_SELECTOR_MISMATCH", self.codes())

    def test_output_outside_private_runtime_rejected(self):
        with self.assertRaises(ExportError):
            private_output(Path(__file__).parent / "would-leak.json")


class XlsxReaderTest(unittest.TestCase):
    def test_reader_retains_string_number_formula_and_sparse_rows(self):
        # Minimal original parser fixture, never delivered as a user workbook.
        parts = {
            "xl/workbook.xml": '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Payment" sheetId="1" r:id="rId1"/></sheets></workbook>',
            "xl/_rels/workbook.xml.rels": '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>',
            "xl/worksheets/sheet1.xml": '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>0012345678901234567890</t></is></c><c r="B1"><v>123.456</v></c><c r="C1"><f>1+1</f><v>2</v></c></row><row r="3"><c r="A3" t="e"><v>#VALUE!</v></c></row></sheetData></worksheet>'}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.xlsx"
            with zipfile.ZipFile(path, "w") as archive:
                for name, content in parts.items():
                    archive.writestr(name, content)
            rows = read_xlsx(path)["Payment"]
        self.assertEqual(rows[0][0], Cell(LONG_ID))
        self.assertEqual(rows[0][1].kind, "number")
        self.assertEqual(rows[0][2].kind, "formula")
        self.assertEqual(rows[1], [])
        self.assertEqual(rows[2][0].kind, "error")


if __name__ == "__main__":
    unittest.main()
