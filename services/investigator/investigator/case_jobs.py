"""Durable receipts for one explicitly submitted case investigation.

Receipts are keyed by authorized caller scope and job identity, never by a shared
question cache. A lost RUNNING lease fails closed: generation is not repeated.
"""
from contextlib import contextmanager
from hashlib import sha256
import json
from pathlib import Path
import sqlite3
from threading import Event, Thread
import time
from typing import Annotated
import uuid

from fastapi import HTTPException
from pydantic import StringConstraints

from .errors import InvalidModelResult, UatModelBusy, UatModelTimeout
from .uat_answer import StrictModel, UatAnswerRequest


Scope = Annotated[str, StringConstraints(min_length=1, max_length=200, pattern=r"^[A-Za-z0-9_.:-]+$")]
Fingerprint = Annotated[str, StringConstraints(pattern=r"^[a-f0-9]{64}$")]


class CaseJobScope(StrictModel):
    tenantId: Scope
    caseId: Scope


class CaseJobIdentity(CaseJobScope):
    jobId: Scope
    inputHash: Fingerprint


class CaseJobSubmission(CaseJobIdentity):
    input: UatAnswerRequest


class CaseJobCancelled(Exception):
    pass


class CaseJobStore:
    LEASE_SECONDS = 90
    MAX_PENDING = 32

    def __init__(self, path: Path, engine, *, clock=time.time, start=True):
        self.path, self.engine, self.clock = Path(path), engine, clock
        self.owner = str(uuid.uuid4())
        self.stop = Event()
        self.wake = Event()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._db() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS case_model_job (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                tenant TEXT NOT NULL, case_id TEXT NOT NULL, job_id TEXT NOT NULL,
                input_hash TEXT NOT NULL, payload_hash TEXT NOT NULL, payload TEXT NOT NULL,
                status TEXT NOT NULL, cancel INTEGER NOT NULL DEFAULT 0,
                owner TEXT, lease REAL, token TEXT, result TEXT, error_code TEXT,
                created REAL NOT NULL, next_attempt REAL NOT NULL DEFAULT 0,
                UNIQUE(tenant, case_id, job_id))""")
            db.execute("CREATE INDEX IF NOT EXISTS case_model_job_pending ON case_model_job(status,sequence)")
            db.execute("CREATE TABLE IF NOT EXISTS case_model_deleted (tenant TEXT NOT NULL,case_id TEXT NOT NULL,PRIMARY KEY(tenant,case_id))")
        self.runner = Thread(target=self._loop, name="case-model-jobs", daemon=True)
        self.heartbeat = Thread(target=self._heartbeat, name="case-model-lease", daemon=True)
        if start:
            self.runner.start()
            self.heartbeat.start()

    @contextmanager
    def _db(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            db.execute("PRAGMA busy_timeout=10000")
            db.execute("PRAGMA secure_delete=ON")
            db.execute("BEGIN IMMEDIATE")
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    @staticmethod
    def _scope(identity):
        return identity.tenantId, identity.caseId, identity.jobId

    def _expire(self, db):
        db.execute("""UPDATE case_model_job SET status='FAILED',error_code='CASE_WORKER_INTERRUPTED',
                   owner=NULL,lease=NULL,token=NULL WHERE status='RUNNING' AND lease < ?""", (self.clock(),))

    def _row(self, db, identity):
        row = db.execute("SELECT * FROM case_model_job WHERE tenant=? AND case_id=? AND job_id=?",
                         self._scope(identity)).fetchone()
        if row is None:
            raise HTTPException(404, detail={"code": "CASE_WORKER_JOB_NOT_FOUND"})
        if row["input_hash"] != identity.inputHash:
            raise HTTPException(409, detail={"code": "CASE_WORKER_JOB_CONFLICT"})
        return row

    @staticmethod
    def _public(row):
        value = {"tenantId": row["tenant"], "caseId": row["case_id"], "jobId": row["job_id"],
                 "inputHash": row["input_hash"], "status": row["status"],
                 "cancellationRequested": bool(row["cancel"])}
        if row["status"] == "COMPLETED":
            value["answer"] = json.loads(row["result"])
        if row["error_code"]:
            value["error"] = {"code": row["error_code"],
                              "message": "The local model job did not complete. No answer was substituted or automatically regenerated."}
        return value

    def submit(self, submission):
        payload = json.dumps(submission.input.model_dump(exclude_unset=True), sort_keys=True,
                             ensure_ascii=False, separators=(",", ":"))
        digest = sha256(payload.encode()).hexdigest()
        with self._db() as db:
            self._expire(db)
            if db.execute("SELECT 1 FROM case_model_deleted WHERE tenant=? AND case_id=?",
                          (submission.tenantId, submission.caseId)).fetchone():
                raise HTTPException(409, detail={"code": "CASE_WORKER_CASE_DELETED"})
            existing = db.execute("SELECT * FROM case_model_job WHERE tenant=? AND case_id=? AND job_id=?",
                                  self._scope(submission)).fetchone()
            if existing:
                cancelled_before_arrival = existing["status"] == "CANCELLED" and existing["payload_hash"] == ""
                if existing["input_hash"] != submission.inputHash or (not cancelled_before_arrival and existing["payload_hash"] != digest):
                    raise HTTPException(409, detail={"code": "CASE_WORKER_JOB_CONFLICT"})
                return self._public(existing)
            count = db.execute("SELECT count(*) FROM case_model_job WHERE status IN ('QUEUED','RUNNING')").fetchone()[0]
            if count >= self.MAX_PENDING:
                raise HTTPException(429, detail={"code": "CASE_WORKER_QUEUE_FULL"})
            db.execute("""INSERT INTO case_model_job(tenant,case_id,job_id,input_hash,payload_hash,payload,status,created)
                       VALUES(?,?,?,?,?,?,'QUEUED',?)""",
                       (*self._scope(submission), submission.inputHash, digest, payload, self.clock()))
            result = self._public(self._row(db, submission))
        self.wake.set()
        return result

    def status(self, identity):
        with self._db() as db:
            self._expire(db)
            return self._public(self._row(db, identity))

    def cancel(self, identity):
        with self._db() as db:
            self._expire(db)
            # Cancellation can race an HTTP submission whose reply was lost.
            # Remember the identity even if that request has not arrived yet.
            exists = db.execute("SELECT 1 FROM case_model_job WHERE tenant=? AND case_id=? AND job_id=?",
                                self._scope(identity)).fetchone()
            if not exists:
                if db.execute("SELECT 1 FROM case_model_deleted WHERE tenant=? AND case_id=?",
                              (identity.tenantId, identity.caseId)).fetchone():
                    raise HTTPException(409, detail={"code": "CASE_WORKER_CASE_DELETED"})
                db.execute("""INSERT INTO case_model_job(tenant,case_id,job_id,input_hash,payload_hash,payload,status,cancel,created)
                           VALUES(?,?,?,?,'','','CANCELLED',1,?)""",
                           (*self._scope(identity), identity.inputHash, self.clock()))
            row = self._row(db, identity)
            if row["status"] in {"QUEUED", "RUNNING"}:
                db.execute("""UPDATE case_model_job SET cancel=1,status=CASE WHEN status='QUEUED'
                           THEN 'CANCELLED' ELSE status END WHERE sequence=?""", (row["sequence"],))
            return self._public(self._row(db, identity))

    def forget_case(self, scope):
        """Idempotent post-tombstone cleanup; prevents late request resurrection."""
        with self._db() as db:
            self._expire(db)
            values = (scope.tenantId, scope.caseId)
            if db.execute("SELECT 1 FROM case_model_job WHERE tenant=? AND case_id=? AND status IN ('QUEUED','RUNNING') LIMIT 1",
                          values).fetchone():
                raise HTTPException(409, detail={"code": "CASE_WORKER_CASE_ACTIVE"})
            db.execute("INSERT OR IGNORE INTO case_model_deleted(tenant,case_id) VALUES(?,?)", values)
            db.execute("DELETE FROM case_model_job WHERE tenant=? AND case_id=?", values)
        return {"tenantId": scope.tenantId, "caseId": scope.caseId, "forgotten": True}

    def _claim(self):
        with self._db() as db:
            self._expire(db)
            if db.execute("SELECT 1 FROM case_model_job WHERE status='RUNNING' LIMIT 1").fetchone():
                return None
            row = db.execute("SELECT * FROM case_model_job WHERE status='QUEUED' AND next_attempt<=? ORDER BY sequence LIMIT 1",
                             (self.clock(),)).fetchone()
            if row is None:
                return None
            token = str(uuid.uuid4())
            db.execute("UPDATE case_model_job SET status='RUNNING',owner=?,lease=?,token=? WHERE sequence=?",
                       (self.owner, self.clock() + self.LEASE_SECONDS, token, row["sequence"]))
            return dict(row) | {"token": token}

    def _cancelled(self, row):
        with self._db() as db:
            current = db.execute("SELECT status,cancel,token,lease FROM case_model_job WHERE sequence=?", (row["sequence"],)).fetchone()
            return (not current or current["status"] != "RUNNING" or current["token"] != row["token"]
                    or current["cancel"] or current["lease"] < self.clock())

    def run_once(self):
        row = self._claim()
        if row is None:
            return False
        state, result, code = "FAILED", None, None
        try:
            if self._cancelled(row):
                raise CaseJobCancelled()
            request = UatAnswerRequest.model_validate_json(row["payload"])
            ready = self.engine.preflight(request)
            if not ready["ready"]:
                raise InvalidModelResult("Context capacity exceeded before inference")
            answer = self.engine.run_cancellable(request, lambda: self._cancelled(row))
            if self._cancelled(row):
                raise CaseJobCancelled()
            result = answer.model_dump_json(exclude_unset=True)
            state = "COMPLETED"
        except CaseJobCancelled:
            state = "CANCELLED"
        except UatModelBusy:
            # The shared local model lock was not acquired: no inference started.
            state = "QUEUED"
        except UatModelTimeout:
            code = "UAT_MODEL_TIMEOUT"
        except InvalidModelResult:
            code = "INVALID_MODEL_RESULT"
        except Exception:
            code = "CASE_WORKER_FAILED"
        with self._db() as db:
            current = db.execute("SELECT * FROM case_model_job WHERE sequence=?", (row["sequence"],)).fetchone()
            if (current and current["status"] == "RUNNING" and current["token"] == row["token"]
                    and current["lease"] >= self.clock()):
                if current["cancel"]:
                    state, result, code = "CANCELLED", None, None
                db.execute("""UPDATE case_model_job SET status=?,result=?,error_code=?,owner=NULL,lease=NULL,
                           token=NULL,next_attempt=? WHERE sequence=?""",
                           (state, result, code, self.clock() + 5 if state == "QUEUED" else 0, row["sequence"]))
        return True

    def _loop(self):
        while not self.stop.is_set():
            try:
                self.run_once()
            except Exception:
                # Never log source-bearing exception strings; a failed receipt
                # write is fenced and expires, rather than repeating generation.
                pass
            self.wake.wait(0.5)
            self.wake.clear()

    def _heartbeat(self):
        while not self.stop.wait(5):
            try:
                with self._db() as db:
                    db.execute("UPDATE case_model_job SET lease=? WHERE status='RUNNING' AND owner=? AND lease>=?",
                               (self.clock() + self.LEASE_SECONDS, self.owner, self.clock()))
            except Exception:
                pass

    def close(self):
        self.stop.set()
        self.wake.set()
        if self.runner.is_alive():
            self.runner.join(timeout=2)
        if self.heartbeat.is_alive():
            self.heartbeat.join(timeout=2)
