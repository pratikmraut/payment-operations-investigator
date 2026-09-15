import { useEffect, useRef, useState } from "react";
import { Download, Eye, FileText, X } from "lucide-react";
import { api, apiBlob, ApiError, human } from "./api";
import { paymentCaseNumber } from "./paymentCaseIdentity";
import "./CaseManagement.css";

type Evidence = {
  id: string;
  caseId: string;
  version: number;
  evidenceHash: string;
  sourceKind: string;
};
type Job = {
  id: string;
  caseId: string;
  evidenceId: string;
  evidenceVersion: number;
  question: string;
  status: string;
};
type Workbench = {
  caseId: string;
  latestEvidenceId: string | null;
  evidence: Evidence[];
  investigations: Job[];
};
type Scope = {
  evidenceId: string | null;
  investigationIds: string[];
  includeEvidenceRows: boolean;
  reportMode?: "SUMMARY" | "DETAILED";
};
type Preview = {
  schemaVersion: string;
  reportId: string;
  reportHash: string;
  generatedAt: string;
  generatedBy: { id: string; name: string; role: string };
  case: { id: string; caseNumber?: string; reference: string };
  scope: Scope;
  evidence: Evidence | null;
  investigations: Job[];
  review: {
    status: "RECORDED" | "PENDING";
    conclusion: {
      conclusion: string;
      createdByName: string;
      evidenceVersion: number;
    } | null;
  };
  warnings: string[];
};
const text = (v: unknown): v is string => typeof v === "string";
const unique = (ids: string[]) => new Set(ids).size === ids.length;
const validEvidence = (e: Evidence, caseId: string) =>
  !!e &&
  e.caseId === caseId &&
  text(e.id) &&
  text(e.sourceKind) &&
  text(e.evidenceHash) &&
  Number.isSafeInteger(e.version) &&
  e.version > 0;
const validJob = (j: Job, caseId: string) =>
  !!j &&
  j.caseId === caseId &&
  [j.id, j.evidenceId, j.question, j.status].every(text) &&
  Number.isSafeInteger(j.evidenceVersion) &&
  j.evidenceVersion > 0;
function validWorkbench(v: Workbench, caseId: string) {
  if (
    !v ||
    v.caseId !== caseId ||
    !Array.isArray(v.evidence) ||
    !v.evidence.every((e) => validEvidence(e, caseId)) ||
    !unique(v.evidence.map((e) => e.id)) ||
    !(
      v.latestEvidenceId === null ||
      v.evidence.some((e) => e.id === v.latestEvidenceId)
    ) ||
    !Array.isArray(v.investigations) ||
    !v.investigations.every((j) => validJob(j, caseId)) ||
    !unique(v.investigations.map((j) => j.id))
  )
    throw new Error("Report options could not be read for this case.");
}
function validate(v: Preview, caseId: string, scope: Scope) {
  if (
    !v ||
    v.schemaVersion !== "payment-case-report-v1" ||
    v.case?.id !== caseId ||
    !text(v.case.reference) ||
    (v.case.caseNumber !== undefined && !text(v.case.caseNumber)) ||
    !text(v.reportId) ||
    !v.reportId ||
    !text(v.reportHash) ||
    !/^[a-f0-9]{64}$/.test(v.reportHash) ||
    !text(v.generatedAt) ||
    ![v.generatedBy?.id, v.generatedBy?.name, v.generatedBy?.role].every(
      text,
    ) ||
    !v.scope ||
    v.scope.evidenceId !== scope.evidenceId ||
    v.scope.includeEvidenceRows !== scope.includeEvidenceRows ||
    (v.scope.reportMode ?? "DETAILED") !== (scope.reportMode ?? "DETAILED") ||
    !Array.isArray(v.scope.investigationIds) ||
    JSON.stringify([...v.scope.investigationIds].sort()) !==
      JSON.stringify([...scope.investigationIds].sort()) ||
    !Array.isArray(v.investigations) ||
    !unique(v.investigations.map((j) => j?.id)) ||
    v.investigations.length !== scope.investigationIds.length ||
    !v.investigations.every(
      (j) => validJob(j, caseId) && scope.investigationIds.includes(j.id),
    ) ||
    (scope.evidenceId === null
      ? v.evidence !== null
      : !v.evidence ||
        !validEvidence(v.evidence, caseId) ||
        v.evidence.id !== scope.evidenceId) ||
    !Array.isArray(v.warnings) ||
    !v.warnings.every(text) ||
    !["RECORDED", "PENDING"].includes(v.review?.status) ||
    (v.review.status === "RECORDED" &&
      (!v.review.conclusion || !text(v.review.conclusion.conclusion))) ||
    (v.review.status === "PENDING" && v.review.conclusion !== null)
  )
    throw new Error(
      "The report preview does not match the selected case and report scope. Generate a new preview.",
    );
}
export function CaseReport({
  caseId,
  onClose,
}: {
  caseId: string;
  onClose: () => void;
}) {
  return <ReportView key={caseId} caseId={caseId} onClose={onClose} />;
}
function ReportView({
  caseId,
  onClose,
}: {
  caseId: string;
  onClose: () => void;
}) {
  const base = `/payment-cases/${encodeURIComponent(caseId)}`;
  const [workbench, setWorkbench] = useState<Workbench | null>(null),
    [evidenceId, setEvidenceId] = useState(""),
    [jobs, setJobs] = useState<string[]>([]),
    [rows, setRows] = useState(false),
    [mode, setMode] = useState<"SUMMARY" | "DETAILED">("SUMMARY"),
    [preview, setPreview] = useState<Preview | null>(null),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<Error | null>(null),
    [revision, setRevision] = useState(0);
  const [attempt, setAttempt] = useState<{ scope: Scope; key: string } | null>(
      null,
    ),
    controller = useRef<AbortController | null>(null);
  useEffect(
    () => () => {
      controller.current?.abort();
      controller.current = null;
    },
    [],
  );
  useEffect(() => {
    const c = new AbortController();
    let timedOut = false;
    const timer = window.setTimeout(() => {
      timedOut = true;
      c.abort();
    }, 30000);
    setError(null);
    api<Workbench>(`${base}/workbench`, { signal: c.signal })
      .then((w) => {
        if (c.signal.aborted) return;
        validWorkbench(w, caseId);
        setWorkbench(w);
        setEvidenceId(w.latestEvidenceId ?? "");
        setJobs(
          w.investigations
            .filter(
              (j) =>
                j.status === "COMPLETED" && j.evidenceId === w.latestEvidenceId,
            )
            .slice(0, 1)
            .map((j) => j.id),
        );
        setPreview(null);
      })
      .catch((e) => {
        if (timedOut)
          setError(
            new Error(
              "Report options timed out. Reload the options to try again.",
            ),
          );
        else if (!c.signal.aborted) setError(e);
      })
      .finally(() => window.clearTimeout(timer));
    return () => {
      window.clearTimeout(timer);
      c.abort();
    };
  }, [base, caseId, revision]);
  function selectVersion(id: string) {
    setEvidenceId(id);
    if (!id) setRows(false);
    setJobs(
      workbench?.investigations
        .filter((j) => j.status === "COMPLETED" && j.evidenceId === id)
        .slice(0, mode === "SUMMARY" ? 1 : 20)
        .map((j) => j.id) ?? [],
    );
    setPreview(null);
  }
  function selectMode(next: "SUMMARY" | "DETAILED") {
    setMode(next);
    setRows(false);
    setPreview(null);
    if (next === "SUMMARY")
      setJobs(
        workbench?.investigations
          .filter(
            (j) => j.status === "COMPLETED" && j.evidenceId === evidenceId,
          )
          .slice(0, 1)
          .map((j) => j.id) ?? [],
      );
  }
  async function generate(retry = false) {
    if (busy || !workbench) return;
    const selected =
      retry && attempt
        ? attempt
        : {
            scope: {
              evidenceId: evidenceId || null,
              investigationIds: jobs,
              includeEvidenceRows: mode === "DETAILED" && rows,
              reportMode: mode,
            },
            key: crypto.randomUUID(),
          };
    const c = new AbortController();
    controller.current = c;
    setBusy(true);
    setError(null);
    setAttempt(selected);
    const timer = window.setTimeout(() => c.abort(), 30000);
    try {
      const result = await api<Preview>(`${base}/report-preview`, {
        method: "POST",
        headers: { "Idempotency-Key": selected.key },
        body: JSON.stringify(selected.scope),
        signal: c.signal,
      });
      if (c.signal.aborted) return;
      validate(result, caseId, selected.scope);
      setPreview(result);
      setAttempt(null);
    } catch (e) {
      if (!c.signal.aborted) {
        setError(e as Error);
        if (e instanceof ApiError && e.status >= 400 && e.status < 500)
          setAttempt(null);
      } else if (controller.current === c)
        setError(
          new Error(
            "Report generation was not confirmed within 30 seconds. Retry the same preview request.",
          ),
        );
    } finally {
      window.clearTimeout(timer);
      if (controller.current === c) setBusy(false);
    }
  }
  async function download() {
    if (!preview || busy) return;
    const c = new AbortController();
    controller.current = c;
    setBusy(true);
    setError(null);
    const timer = window.setTimeout(() => c.abort(), 30000);
    try {
      const file = await apiBlob(`${base}/report.pdf`, {
        method: "POST",
        body: JSON.stringify({
          reportId: preview.reportId,
          reportHash: preview.reportHash,
        }),
        signal: c.signal,
      });
      if (c.signal.aborted) return;
      const url = URL.createObjectURL(file),
        link = document.createElement("a");
      link.href = url;
      link.download = `payment-case-${(preview.case.caseNumber || preview.reportId).replace(/[^a-zA-Z0-9-]/g, "")}.pdf`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) {
      if (controller.current === c) setError(e as Error);
    } finally {
      window.clearTimeout(timer);
      if (controller.current === c) setBusy(false);
    }
  }
  const disabled = busy || !!attempt;
  return (
    <section
      className="panel case-management case-report"
      aria-labelledby="case-report-title"
    >
      <div className="section-title">
        <h2 id="case-report-title">
          <FileText size={20} aria-hidden="true" /> Export case report
        </h2>
        <button className="secondary" onClick={onClose} disabled={busy}>
          <X size={16} aria-hidden="true" />
          Close report
        </button>
      </div>
      <p className="muted">
        Choose the evidence and saved investigations to include. Preview creates
        a saved report snapshot; the PDF uses that same snapshot. No model or
        bank request is run.
      </p>
      {error && (
        <div className="notice danger" role="alert">
          <strong>{error.message}</strong>
          {error instanceof ApiError && error.requestId && (
            <small>Request {error.requestId}</small>
          )}
        </div>
      )}
      {!workbench && (
        <button className="secondary" onClick={() => setRevision((v) => v + 1)}>
          Reload report options
        </button>
      )}
      {workbench && (
        <>
          <fieldset disabled={disabled} className="case-report-format">
            <legend>Report format</legend>
            <label className="case-management-choice">
              <input
                type="radio"
                name="case-report-mode"
                checked={mode === "SUMMARY"}
                onChange={() => selectMode("SUMMARY")}
              />
              <span>
                <strong>Case summary</strong>
                <small>
                  Short findings and source references. Starts with the latest
                  completed question for the selected version.
                </small>
              </span>
            </label>
            <label className="case-management-choice">
              <input
                type="radio"
                name="case-report-mode"
                checked={mode === "DETAILED"}
                onChange={() => selectMode("DETAILED")}
              />
              <span>
                <strong>Detailed report</strong>
                <small>
                  Full selected answers and complete cited source documents,
                  with an optional raw evidence appendix.
                </small>
              </span>
            </label>
          </fieldset>
          <label>
            Report evidence version
            <select
              disabled={disabled}
              value={evidenceId}
              onChange={(e) => selectVersion(e.target.value)}
            >
              <option value="">Case summary without an evidence version</option>
              {workbench.evidence.map((v) => (
                <option key={v.id} value={v.id}>
                  Version {v.version} · {human(v.sourceKind)}
                </option>
              ))}
            </select>
          </label>
          <fieldset disabled={disabled} className="case-report-questions">
            <legend>
              Saved investigations to include (up to{" "}
              {mode === "SUMMARY" ? 2 : 20})
            </legend>
            <p className="payment-help">
              {mode === "SUMMARY"
                ? "One question is selected by default. You may add one more; additional questions increase the page count. Choose Detailed report for more questions."
                : "Select up to 20 questions. More questions and source documents increase the page count."}
            </p>
            {workbench.investigations.length ? (
              workbench.investigations.map((j) => (
                <label className="case-management-choice" key={j.id}>
                  <input
                    type="checkbox"
                    checked={jobs.includes(j.id)}
                    disabled={
                      !evidenceId ||
                      (jobs.length >= (mode === "SUMMARY" ? 2 : 20) &&
                        !jobs.includes(j.id))
                    }
                    onChange={(e) => {
                      setJobs((current) =>
                        e.target.checked
                          ? [...current, j.id]
                          : current.filter((id) => id !== j.id),
                      );
                      setPreview(null);
                    }}
                  />
                  <span>
                    {j.question}
                    <small>
                      {human(j.status)} · Evidence version {j.evidenceVersion}
                      {j.status !== "COMPLETED"
                        ? " · Saved status only; no completed answer"
                        : ""}
                      {j.evidenceId !== evidenceId
                        ? " · Different version; its own saved source references are used"
                        : ""}
                    </small>
                  </span>
                </label>
              ))
            ) : (
              <p>No saved investigations.</p>
            )}
          </fieldset>
          {mode === "DETAILED" && (
            <label className="case-management-choice">
              <input
                type="checkbox"
                disabled={disabled || !evidenceId}
                checked={rows && !!evidenceId}
                onChange={(e) => {
                  setRows(e.target.checked);
                  setPreview(null);
                }}
              />
              <span>
                Include all raw rows from the selected evidence version
                <small>
                  Cited source documents are always included with their own
                  investigation, even when the raw-row appendix is off.
                </small>
              </span>
            </label>
          )}
          {attempt && !busy ? (
            <button className="primary" onClick={() => void generate(true)}>
              Retry same report preview
            </button>
          ) : (
            <button
              className="primary"
              disabled={busy}
              onClick={() => void generate()}
            >
              <Eye size={16} aria-hidden="true" />
              Preview report
            </button>
          )}
          {busy && <p role="status">Preparing report…</p>}
          {preview && (
            <div className="case-report-preview">
              <h3>Saved report preview</h3>
              <p>
                {preview.scope.reportMode === "SUMMARY"
                  ? "Case summary · findings and source references"
                  : "Detailed report · complete cited source documents"}
              </p>
              <dl className="case-management-facts">
                <div>
                  <dt>Case number</dt>
                  <dd>{paymentCaseNumber(preview.case)}</dd>
                </div>
                <div>
                  <dt>Payment reference</dt>
                  <dd>{preview.case.reference}</dd>
                </div>
                <div>
                  <dt>Selected evidence</dt>
                  <dd>
                    {preview.evidence
                      ? `Version ${preview.evidence.version}`
                      : "Case summary only"}
                  </dd>
                </div>
                <div>
                  <dt>Reviewer conclusion</dt>
                  <dd>
                    {preview.review.status === "RECORDED"
                      ? "Recorded for this report scope"
                      : "Pending for this report scope"}
                  </dd>
                </div>
              </dl>
              <p>
                Prepared by {preview.generatedBy.name} · {preview.generatedAt}
              </p>
              <ul>
                {preview.investigations.map((j) => (
                  <li key={j.id}>
                    {j.question} · Evidence version {j.evidenceVersion}
                    {j.status !== "COMPLETED"
                      ? ` · ${human(j.status)}: saved status only; no completed answer`
                      : ""}
                    {j.evidenceId !== preview.scope.evidenceId
                      ? " (different version; its own saved sources)"
                      : ""}
                  </li>
                ))}
              </ul>
              {preview.review.conclusion && (
                <blockquote>{preview.review.conclusion.conclusion}</blockquote>
              )}
              {preview.warnings.length > 0 && (
                <ul className="payment-warnings">
                  {preview.warnings.map((w, i) => (
                    <li key={i}>{w}</li>
                  ))}
                </ul>
              )}
              <p>
                <small>
                  Report fingerprint: <code>{preview.reportHash}</code>
                </small>
              </p>
              <button
                className="primary"
                disabled={busy}
                onClick={() => void download()}
              >
                <Download size={16} aria-hidden="true" />
                Download PDF
              </button>
            </div>
          )}
        </>
      )}
    </section>
  );
}
