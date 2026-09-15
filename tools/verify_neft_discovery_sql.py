"""Local relational checks for the actual discovery SELECTs, not Oracle compilation.

Uses original synthetic rows and explicitly adapts Oracle formatting/ROWNUM syntax
to SQLite. PL/SQL typing, exception execution, NLS behavior and query plans still
require the user's Oracle environment. Never connects to a bank or database server.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sqlite3
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / "runtime/obpm-uat/api/PK_BA_NEFT_EVIDENCE_INQ.sql"
BEFORE = ROOT / "runtime/obpm-uat/api/validation/discovery-20260914/PK_BA_NEFT_EVIDENCE_INQ.before.sql"
FIELDS = ["PIO_REF_TXN_NO", "PIO_ORG_BRN", "PIO_ORG_BANK", "REF_SUBSEQ_NO", "UTR_REF_NO", "DATINITIATION", "NUMAMOUNT_4038"]


def extract_sql(source):
    body = source.split("CREATE OR REPLACE PACKAGE BODY", 1)[1]
    function = body.split("  FUNCTION AP_BA_NEFT_DISCOVERY_INQ(", 1)[1]
    queries = re.findall(r"OPEN PIO_RESULT FOR\s+(WITH .*?);", function, re.S)
    assert len(queries) == 3, "Expected exactly three static cursor SELECTs"
    adapted = []
    for sql in queries:
        assert "GROUP BY REFTXNNUMBER" in sql
        assert sql.count("h.COD_ORG_BRN = PIO_ORG_BRN") == 2
        assert sql.count("h.COD_ORG_BANK = PIO_ORG_BANK") == 2
        sql, count = re.subn(
            r"limited_keys AS \(.*?\n        \)",
            """limited_keys AS (
              SELECT REFTXNNUMBER, SORT_INITIATION,
                ROW_NUMBER() OVER (ORDER BY SORT_INITIATION DESC NULLS LAST, REFTXNNUMBER DESC) AS DISCOVERY_ORDER
              FROM payment_keys
              ORDER BY SORT_INITIATION DESC NULLS LAST, REFTXNNUMBER DESC
              LIMIT :V_PAYMENT_LIMIT
            )""", sql, count=1, flags=re.S)
        assert count == 1
        sql, count = re.subn(r"TO_CHAR\(([^,]+), 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,'''\)", r"MINIMAL_NUMBER_TEXT(\1)", sql)
        assert count == 6
        sql, count = re.subn(r"TO_CHAR\(p.DATINITIATION, 'YYYY-MM-DD\"T\"HH24:MI:SS', 'NLS_DATE_LANGUAGE=English'\)", "CAST(p.DATINITIATION AS TEXT)", sql)
        assert count == 1
        for name in ("V_FCR_REFERENCE", "V_UTR_REFERENCE", "V_DAY_START", "V_DAY_END"):
            sql = re.sub(r"\b" + name + r"\b", ":" + name, sql)
        # Only RHS parameters, never the identically named projected API aliases.
        sql = sql.replace("= PIO_ORG_BRN", "= :PIO_ORG_BRN").replace("= PIO_ORG_BANK", "= :PIO_ORG_BANK")
        adapted.append(sql)
    return function, adapted


def database():
    db = sqlite3.connect(":memory:")
    # Explicit test model for the minimal-number-text leading-dot edge case.
    # This does not emulate all of Oracle's NLS/TM9 formatting behavior.
    db.create_function("MINIMAL_NUMBER_TEXT", 1,
        lambda value: None if value is None else
        (str(value)[1:] if str(value).startswith("0.") else str(value)))
    db.execute("CREATE TABLE PM_NEFT_TXN_LOG(REFTXNNUMBER TEXT, UTR_REF_NO TEXT, DATINITIATION TEXT, NUMAMOUNT_4038 TEXT)")
    db.execute("CREATE TABLE PM_TXN_LOG(REF_TXN_NO TEXT, COD_ORG_BRN INTEGER, COD_ORG_BANK INTEGER, REF_SUBSEQ_NO INTEGER)")
    return db


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    source = PACKAGE.read_text(encoding="utf-8")
    function, queries = extract_sql(source)
    checks = []

    def passed(name):
        checks.append(name)
        print("PASS " + name)

    baseline = BEFORE.read_text(encoding="utf-8")
    for name in ("PAYMENT", "HOST", "HISTORY", "STATUS"):
        pattern = r"  FUNCTION AP_BA_NEFT_" + name + r"_INQ\(.*?  END AP_BA_NEFT_" + name + r"_INQ;"
        old = re.search(pattern, baseline.split("CREATE OR REPLACE PACKAGE BODY", 1)[1], re.S)
        new = re.search(pattern, source.split("CREATE OR REPLACE PACKAGE BODY", 1)[1], re.S)
        assert old and new and old.group() == new.group(), name + " body changed"
        declaration = r"  FUNCTION AP_BA_NEFT_" + name + r"_INQ\(.*?RETURN NUMBER;"
        assert re.search(declaration, baseline, re.S).group() == re.search(declaration, source, re.S).group()
    passed("four original function signatures and bodies unchanged")

    assert "EXECUTE IMMEDIATE" not in function and "OPEN PIO_RESULT FOR '" not in function
    assert "PI_REFERENCE_TYPE NOT IN ('FCR', 'UTR')" in function
    assert "PI_INQUIRY_DATE IS NOT NULL OR PI_RECORD_COUNT IS NOT NULL" in function
    assert "PI_REFERENCE_TYPE IS NULL OR PI_REFERENCE IS NULL" in function
    assert "PI_RECORD_COUNT <> TRUNC(PI_RECORD_COUNT)" in function
    assert "PI_RECORD_COUNT < 1 OR PI_RECORD_COUNT > 200" in function
    assert "PIO_RESULT%ISOPEN" in function and "RETURN V_ERROR_CODE" in function
    passed("static inspection: input-mode, count, cursor and error guards present")

    db = database()
    def payment(ref, utr, date, amount="1250.004", branch=1352, bank=760, subs=(0,)):
        db.execute("INSERT INTO PM_NEFT_TXN_LOG VALUES(?,?,?,?)", (ref, utr, date, amount))
        db.executemany("INSERT INTO PM_TXN_LOG VALUES(?,?,?,?)", [(ref, branch, bank, sub) for sub in subs])

    def run(mode, ref="", limit=201, connection=db):
        cursor = connection.execute(queries[{"FCR":0,"UTR":1,"LIST":2}[mode]], {
            "V_FCR_REFERENCE":ref, "V_UTR_REFERENCE":ref,
            "PIO_ORG_BRN":1352, "PIO_ORG_BANK":760,
            "V_DAY_START":"2026-01-07T00:00:00", "V_DAY_END":"2026-01-08T00:00:00", "V_PAYMENT_LIMIT":limit})
        assert [item[0] for item in cursor.description] == FIELDS
        return cursor.fetchall()

    payment("DEMO-OLD-A", None, "2020-01-01T00:00:00", subs=(0,1))
    db.execute("INSERT INTO PM_TXN_LOG VALUES(?,?,?,?)", ("DEMO-OLD-A", 9999, 760, 2))
    db.execute("INSERT INTO PM_TXN_LOG VALUES(?,?,?,?)", ("DEMO-OLD-A", 1352, 999, 3))
    rows = run("FCR", "DEMO-OLD-A")
    assert len(rows) == 2 and [row[3] for row in rows] == ["0", "1"]
    assert all(row[1:3] == ("1352", "760") and row[4] is None for row in rows)
    passed("historical alphanumeric FCR, null UTR, complete host rows and bank/branch isolation")

    long_ref = "0000000000000000000000000000000123456789"
    payment(long_ref, "DEMO-ONE", "2021-01-01T00:00:00", "123456789.000123")
    row = run("FCR", long_ref)[0]
    assert row[0] == long_ref and row[6] == "123456789.000123"
    passed("long leading-zero identifiers and exact decimal strings retained")

    for i, amount in enumerate(("0", "0.01", "0.5", "1", "1250.004")):
        ref = f"DEMO-FRACTION-{i}"
        payment(ref, "DEMO-FRACTION", "2022-01-01T00:00:00", amount)
        assert run("FCR", ref)[0][6] == amount
    passed("zero, fractional and whole amounts preserve API-compatible decimal text")

    payment("DEMO-A", "DEMO-SHARED", "2026-01-07T00:00:00", subs=(0,1))
    payment("DEMO-B", "DEMO-SHARED", "2026-01-07T12:00:00")
    payment("DEMO-C", "DEMO-THREE", "2026-01-07T12:00:00")
    payment("DEMO-OTHER-BRANCH", "DEMO-SHARED", "2026-01-07T14:00:00", branch=2468)
    db.execute("INSERT INTO PM_NEFT_TXN_LOG VALUES(?,?,?,?)", ("DEMO-ORPHAN", "DEMO-SHARED", "2026-01-07T15:00:00", "9"))
    assert {row[0] for row in run("UTR", "DEMO-SHARED")} == {"DEMO-A", "DEMO-B"}
    assert not run("UTR", "DEMO-NOT-FOUND") and not run("FCR", "DEMO-NOT-FOUND")
    passed("shared UTR retains multiple scoped payments; absent references return no rows")

    payment("DEMO-BEFORE", "DEMO-TIME", "2026-01-06T23:59:59")
    payment("DEMO-AFTER", "DEMO-TIME", "2026-01-08T00:00:00")
    rows = run("LIST", limit=3)
    assert list(dict.fromkeys(row[0] for row in rows)) == ["DEMO-C", "DEMO-B", "DEMO-A"]
    assert len(rows) == 4
    passed("selected-day boundaries, deterministic tie sorting and payment-level look-ahead")

    # Deliberate inconsistent same-key source row must remain available for rejection.
    db.execute("INSERT INTO PM_NEFT_TXN_LOG VALUES(?,?,?,?)", ("DEMO-C", "DEMO-THREE", "2026-01-07T12:00:00", "999.50"))
    rows = run("LIST", limit=2)
    assert list(dict.fromkeys(row[0] for row in rows)) == ["DEMO-C", "DEMO-B"]
    assert {row[6] for row in rows if row[0] == "DEMO-C"} == {"1250.004", "999.50"}
    passed("duplicate NEFT rows do not consume payment limit or hide conflicting matched metadata")

    large = database()
    for i in range(202):
        ref = f"DEMO-MANY-{i:04d}"
        large.execute("INSERT INTO PM_NEFT_TXN_LOG VALUES(?,?,?,?)", (ref, "DEMO-MANY", "2026-01-07T00:00:00", "1"))
        large.execute("INSERT INTO PM_TXN_LOG VALUES(?,?,?,?)", (ref,1352,760,0))
    assert len({row[0] for row in run("UTR", "DEMO-MANY", 201, large)}) == 201
    passed("201st exact match remains visible for wrapper rejection")

    oversized = database()
    oversized.execute("INSERT INTO PM_NEFT_TXN_LOG VALUES(?,?,?,?)", ("DEMO-LARGE", "DEMO-LARGE", "2026-01-07T00:00:00", "1"))
    oversized.executemany("INSERT INTO PM_TXN_LOG VALUES(?,?,?,?)", [("DEMO-LARGE",1352,760,i) for i in range(2001)])
    assert len(run("FCR", "DEMO-LARGE", 201, oversized)) == 2001
    passed("oversized host group is not silently row-truncated; wrapper must reject over 2000")

    result = {"status":"PASS", "timestamp":datetime.now(timezone.utc).isoformat(),
        "checkCount":len(checks), "checks":checks, "oracleCompiled":False, "oracleExecuted":False,
        "method":"Structural review and actual SELECT relational logic executed with explicit SQLite dialect adaptations; original synthetic rows only.",
        "packageSha256":hashlib.sha256(PACKAGE.read_bytes()).hexdigest(),
        "limitations":["Not an Oracle PL/SQL compiler or Oracle NLS/precision/query-plan test.",
            "PL/SQL guard presence checked statically, not executed.", "API wrapper integration and installed key uniqueness remain to be verified."]}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result,indent=2)+"\n", encoding="utf-8")
    print(json.dumps({"status":"PASS", "checkCount":len(checks), "oracleCompiled":False}))


if __name__ == "__main__":
    main()
