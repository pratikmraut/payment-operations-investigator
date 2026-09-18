import { useEffect, useRef, useState } from "react";
import { CheckCircle2, LoaderCircle, ScanSearch } from "lucide-react";
import { api, ApiError } from "./api";
import "./CaseReadiness.css";

const GROUPS = ["PAYMENT", "HOST", "HISTORY", "STATUS"] as const;
type Group = (typeof GROUPS)[number];
type RowCoverage = {
  suppliedRows: number;
  selectedRows: number;
  omittedRows: number;
};
export type SourceSelection = {
  schemaVersion: "case-evidence-selection-v1";
  method: "question-ranked-whole-rows-v1";
  mode: "ALL" | "SELECTED";
  totalRows: number;
  selectedRows: number;
  omittedRows: number;
  selectedRowIds: string[];
  groups: Record<Group, RowCoverage>;
  meaning: string;
};
type Evidence = { id: string; evidenceHash: string; version: number };
type Readiness = {
  caseId: string;
  evidenceId: string;
  evidenceHash: string;
  evidenceVersion: number;
  ready: boolean;
  selection: SourceSelection;
  knowledgeVersion: string;
  knowledgeIndexStatus: "CURRENT" | "DISABLED" | "STALE" | "MISSING";
  modelAvailabilityChecked: false;
  modelContextChecked: false;
  warnings: string[];
};
const object = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === "object" && !Array.isArray(value);
const count = (value: unknown): value is number =>
  typeof value === "number" && Number.isSafeInteger(value) && value >= 0;

export function sourceSelection(value: unknown): SourceSelection | null {
  if (
    !object(value) ||
    value.schemaVersion !== "case-evidence-selection-v1" ||
    value.method !== "question-ranked-whole-rows-v1" ||
    typeof value.mode !== "string" ||
    !["ALL", "SELECTED"].includes(value.mode) ||
    !count(value.totalRows) ||
    value.totalRows > 2000 ||
    !count(value.selectedRows) ||
    value.selectedRows < 1 ||
    value.selectedRows > 100 ||
    !count(value.omittedRows) ||
    value.selectedRows + value.omittedRows !== value.totalRows ||
    (value.mode === "ALL") !== (value.omittedRows === 0) ||
    !Array.isArray(value.selectedRowIds) ||
    value.selectedRowIds.length !== value.selectedRows ||
    !value.selectedRowIds.every(
      (id) =>
        typeof id === "string" &&
        /^(PAYMENT|HOST|HISTORY|STATUS)-ROW-[1-9][0-9]*$/.test(id),
    ) ||
    new Set(value.selectedRowIds).size !== value.selectedRowIds.length ||
    !object(value.groups) ||
    typeof value.meaning !== "string"
  )
    return null;
  let supplied = 0,
    selected = 0;
  for (const group of GROUPS) {
    const coverage = value.groups[group];
    if (
      !object(coverage) ||
      !count(coverage.suppliedRows) ||
      coverage.suppliedRows > 500 ||
      !count(coverage.selectedRows) ||
      !count(coverage.omittedRows) ||
      coverage.selectedRows + coverage.omittedRows !== coverage.suppliedRows ||
      value.selectedRowIds.filter((id) => id.startsWith(`${group}-ROW-`))
        .length !== coverage.selectedRows
    )
      return null;
    const suppliedRows = coverage.suppliedRows;
    if (
      value.selectedRowIds.some(
        (id) =>
          id.startsWith(`${group}-ROW-`) &&
          (!Number.isSafeInteger(Number(id.split("-ROW-")[1])) ||
            Number(id.split("-ROW-")[1]) > suppliedRows),
      )
    )
      return null;
    supplied += coverage.suppliedRows;
    selected += coverage.selectedRows;
  }
  return supplied === value.totalRows && selected === value.selectedRows
    ? (value as SourceSelection)
    : null;
}

export function SourceSelectionCoverage({
  selection,
}: {
  selection?: unknown;
}) {
  if (selection === undefined || selection === null) return null;
  const value = sourceSelection(selection);
  if (!value)
    return (
      <p className="notice danger" role="alert">
        The saved source selection could not be verified. Inspect the preserved
        source documents.
      </p>
    );
  return (
    <div
      className="notice neutral case-source-selection"
      aria-label="Investigation source coverage"
    >
      <div>
        <strong>
          {value.selectedRows} of {value.totalRows} source rows selected
        </strong>
        <p>
          {value.omittedRows > 0
            ? `${value.omittedRows} rows are outside this question's context. They remain in saved evidence and may contain relevant or conflicting facts.`
            : "All supplied rows are included. Source completeness and payment outcome still require verification."}
        </p>
        <details>
          <summary>Coverage by source group</summary>
          <table>
            <thead>
              <tr>
                <th>Source</th>
                <th>Selected</th>
                <th>Supplied</th>
                <th>Omitted</th>
              </tr>
            </thead>
            <tbody>
              {GROUPS.map((group) => (
                <tr key={group}>
                  <th scope="row">{group}</th>
                  <td>{value.groups[group].selectedRows}</td>
                  <td>{value.groups[group].suppliedRows}</td>
                  <td>{value.groups[group].omittedRows}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p>
            Selection uses whole original rows. Source-row order does not
            establish event chronology.
          </p>
        </details>
      </div>
    </div>
  );
}

export function CaseReadiness({
  caseId,
  evidence,
  question,
  disabled = false,
}: {
  caseId: string;
  evidence?: Evidence | null;
  question: string;
  disabled?: boolean;
}) {
  const [result, setResult] = useState<Readiness | null>(null);
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<Error | null>(null);
  const operation = useRef<AbortController | null>(null);
  useEffect(() => {
    operation.current?.abort();
    operation.current = null;
    setResult(null);
    setError(null);
    setBusy(false);
    return () => {
      operation.current?.abort();
      operation.current = null;
    };
  }, [
    caseId,
    evidence?.id,
    evidence?.evidenceHash,
    evidence?.version,
    question,
  ]);

  async function check() {
    if (
      disabled ||
      busy ||
      !evidence ||
      !question.trim() ||
      question.length > 2000 ||
      operation.current
    )
      return;
    const controller = new AbortController();
    operation.current = controller;
    const timer = window.setTimeout(() => controller.abort(), 30000);
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const value = await api<Readiness>(
        `/payment-cases/${encodeURIComponent(caseId)}/investigations/readiness`,
        {
          method: "POST",
          body: JSON.stringify({
            question,
            evidenceId: evidence.id,
            evidenceHash: evidence.evidenceHash,
          }),
          signal: controller.signal,
        },
      );
      if (
        !value ||
        value.caseId !== caseId ||
        value.evidenceId !== evidence.id ||
        value.evidenceHash !== evidence.evidenceHash ||
        value.evidenceVersion !== evidence.version ||
        typeof value.ready !== "boolean" ||
        !sourceSelection(value.selection) ||
        typeof value.knowledgeVersion !== "string" ||
        !/^[a-f0-9]{64}$/.test(value.knowledgeVersion) ||
        !["CURRENT", "DISABLED", "STALE", "MISSING"].includes(
          value.knowledgeIndexStatus,
        ) ||
        value.modelAvailabilityChecked !== false ||
        value.modelContextChecked !== false ||
        !Array.isArray(value.warnings) ||
        !value.warnings.every((warning) => typeof warning === "string")
      )
        throw new Error(
          "The readiness response does not match this case and evidence version. Check again.",
        );
      if (!controller.signal.aborted && operation.current === controller)
        setResult(value);
    } catch (failure) {
      if (operation.current === controller)
        setError(
          controller.signal.aborted
            ? new Error(
                "Readiness did not finish within 30 seconds. Check the local API and retry.",
              )
            : (failure as Error),
        );
    } finally {
      window.clearTimeout(timer);
      if (operation.current === controller) {
        operation.current = null;
        setBusy(false);
      }
    }
  }
  return (
    <div className="case-readiness">
      <button
        type="button"
        className="secondary"
        disabled={
          disabled ||
          busy ||
          !evidence ||
          !question.trim() ||
          question.length > 2000
        }
        onClick={() => void check()}
      >
        {busy ? (
          <LoaderCircle className="spin" size={16} />
        ) : (
          <ScanSearch size={16} />
        )}
        {busy ? "Checking readiness…" : "Check readiness"}
      </button>
      <p className="payment-help">
        Checks saved sources, capacity and knowledge freshness. It does not run
        the model.
      </p>
      {error && (
        <p className="notice danger" role="alert">
          {error.message}
          {error instanceof ApiError && error.requestId && (
            <small>Request {error.requestId}</small>
          )}
        </p>
      )}
      {result && (
        <div aria-live="polite">
          <p className={`notice ${result.ready ? "neutral" : "danger"}`}>
            <CheckCircle2 size={16} />
            {result.ready
              ? "Ready to prepare this question. Model availability and the final prompt budget are checked when you run it."
              : "Knowledge embeddings need refreshing before a new investigation can run."}
          </p>
          <SourceSelectionCoverage selection={result.selection} />
          {result.warnings.length > 0 && (
            <details>
              <summary>Source and knowledge limitations</summary>
              <ul>
                {result.warnings.map((warning, index) => (
                  <li key={index}>{warning}</li>
                ))}
              </ul>
            </details>
          )}
        </div>
      )}
    </div>
  );
}
