import { useEffect, useRef, useState } from "react";
import {
  ArrowRight,
  ArrowUpRight,
  ChevronLeft,
  ChevronRight,
  Database,
  Eye,
  FileStack,
  FileText,
  FolderOpen,
  LoaderCircle,
  RefreshCw,
  Search,
  TriangleAlert,
  X,
} from "lucide-react";
import { api, ApiError, date, human } from "./api";
import { navigateLink } from "./routing";
import { paymentCaseNumber, paymentCasePath } from "./paymentCaseIdentity";
import { PaymentWorkflow } from "./PaymentWorkflow";
import type { User } from "./types";
import {
  EvidenceProvenance,
  isSourceNull,
  isSourceOmitted,
  validateEvidenceUpstream,
  type EvidenceUpstream,
} from "./EvidenceProvenance";

const GROUPS = ["PAYMENT", "HOST", "HISTORY", "STATUS"] as const;
type Group = (typeof GROUPS)[number];
type CoverageState = "NO_EVIDENCE" | "EMPTY" | "PARTIAL" | "ALL_GROUPS";
const COVERAGE: Record<CoverageState, string> = {
  NO_EVIDENCE: "No evidence",
  EMPTY: "No rows",
  PARTIAL: "Partial groups",
  ALL_GROUPS: "Rows in all groups",
};
const SOURCES = {
  ALL: "All sources",
  BANK_API: "Inquiry API",
  EXCEL: "Excel",
  JSON: "JSON",
  MANUAL: "Manual",
};
type Summary = {
  id: string;
  caseId: string;
  version: number;
  sourceKind: string;
  createdAt: string;
  createdBy: string;
  evidenceHash: string;
  warnings: string[];
  coverage: Record<
    Group,
    { rowCount: number; completion: "COMPLETE" | "UNVERIFIED" }
  >;
};
type Item = {
  caseId: string;
  caseNumber?: string;
  lifecycleState?: "ACTIVE" | "ARCHIVED";
  reference: string;
  utr: string | null;
  orgBank: string;
  orgBranch: string;
  reason: string;
  amount: string;
  currency: string | null;
  updatedAt: string;
  versionCount: number;
  coverageState: CoverageState;
  latestEvidence: Summary | null;
};
type Library = {
  generatedAt: string;
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
  summary: {
    caseCount: number;
    casesWithRows: number;
    casesWithoutRows: number;
    evidenceVersions: number;
  };
  scopes: { orgBank: string; orgBranch: string; label: string }[];
  items: Item[];
};
type Snapshot = Summary & {
  upstream?: EvidenceUpstream;
  payload: {
    schemaVersion: "fcr-case-evidence-v1";
    payment: { reference: string; orgBank: string; orgBranch: string };
    sourceTimezone: string;
    sections: Record<Group, { rows: Record<string, string>[]; note: string }>;
  };
};
type Query = {
  search: string;
  coverage: "ALL" | CoverageState;
  source: keyof typeof SOURCES;
  bank: string;
  branch: string;
  page: number;
};
const initialQuery: Query = {
  search: "",
  coverage: "ALL",
  source: "ALL",
  bank: "",
  branch: "",
  page: 1,
};
const record = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === "object" && !Array.isArray(value);
const string = (value: unknown): value is string => typeof value === "string";
const count = (value: unknown): value is number =>
  typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
const strings = (value: unknown): value is string[] =>
  Array.isArray(value) && value.every(string);
const known = (value: string | null) =>
  value === null || value === "" ? "Not supplied" : value;
const sourceLabel = (value: string) =>
  SOURCES[value as keyof typeof SOURCES] ?? human(value);
const hasRows = (value: Summary | null) =>
  !!value && GROUPS.some((group) => value.coverage[group].rowCount > 0);
const questionsPath = (caseId: string, evidenceId: string) =>
  `/evidences/questions/${encodeURIComponent(caseId)}/${encodeURIComponent(evidenceId)}`;
function validateSummary(value: Summary, caseId: string) {
  if (
    !value ||
    value.caseId !== caseId ||
    ![
      value.id,
      value.createdAt,
      value.createdBy,
      value.sourceKind,
      value.evidenceHash,
    ].every((entry) => string(entry) && !!entry) ||
    !count(value.version) ||
    value.version < 1 ||
    !strings(value.warnings) ||
    !record(value.coverage) ||
    !GROUPS.every(
      (group) =>
        count(value.coverage[group]?.rowCount) &&
        ["COMPLETE", "UNVERIFIED"].includes(value.coverage[group]?.completion),
    )
  )
    throw new Error(
      "The evidence version metadata is unreadable or belongs to a different case.",
    );
}
function validateLibrary(value: Library) {
  if (
    !value ||
    !string(value.generatedAt) ||
    value.pageSize !== 10 ||
    !count(value.page) ||
    value.page < 1 ||
    !count(value.total) ||
    !count(value.totalPages) ||
    value.totalPages < 1 ||
    value.page > value.totalPages ||
    !value.summary ||
    ![
      value.summary.caseCount,
      value.summary.casesWithRows,
      value.summary.casesWithoutRows,
      value.summary.evidenceVersions,
    ].every(count) ||
    !Array.isArray(value.scopes) ||
    !value.scopes.every(
      (scope) =>
        scope && [scope.orgBank, scope.orgBranch, scope.label].every(string),
    ) ||
    !Array.isArray(value.items) ||
    value.items.length > 10 ||
    !value.items.every(
      (item) =>
        item &&
        [
          item.caseId,
          item.reference,
          item.orgBank,
          item.orgBranch,
          item.reason,
          item.amount,
          item.updatedAt,
        ].every(string) &&
        (item.caseNumber === undefined || string(item.caseNumber)) &&
        [item.utr, item.currency].every(
          (entry) => entry === null || string(entry),
        ) &&
        count(item.versionCount) &&
        Object.hasOwn(COVERAGE, item.coverageState),
    )
  )
    throw new Error(
      "The evidence library response is unreadable. Retry the current search.",
    );
  value.items.forEach((item) => {
    if (item.latestEvidence !== null)
      validateSummary(item.latestEvidence, item.caseId);
  });
}
function validateSnapshot(value: Snapshot, expected: Summary, item: Item) {
  validateSummary(value, item.caseId);
  if (
    value.id !== expected.id ||
    value.version !== expected.version ||
    value.evidenceHash !== expected.evidenceHash ||
    value.sourceKind !== expected.sourceKind ||
    value.payload?.schemaVersion !== "fcr-case-evidence-v1" ||
    !string(value.payload.sourceTimezone) ||
    value.payload.payment?.reference !== item.reference ||
    value.payload.payment.orgBank !== item.orgBank ||
    value.payload.payment.orgBranch !== item.orgBranch ||
    !record(value.payload.sections) ||
    !GROUPS.every((group) => {
      const section = value.payload.sections[group];
      return (
        section &&
        string(section.note) &&
        Array.isArray(section.rows) &&
        section.rows.length <= 500 &&
        section.rows.length === value.coverage[group].rowCount &&
        section.rows.every(
          (row) =>
            record(row) &&
            Object.values(row).every(
              (entry) => string(entry) && entry.length <= 4000,
            ),
        )
      );
    })
  )
    throw new Error(
      "The evidence payload does not match the selected case and immutable version. No source values have been displayed.",
    );
  validateEvidenceUpstream(value.upstream, value.payload.sections);
}
async function read<T>(path: string, signal: AbortSignal) {
  const controller = new AbortController();
  let timeout = false;
  const abort = () => controller.abort();
  if (signal.aborted) controller.abort();
  else signal.addEventListener("abort", abort, { once: true });
  const timer = window.setTimeout(() => {
    timeout = true;
    controller.abort();
  }, 30000);
  try {
    return await api<T>(path, { signal: controller.signal });
  } catch (failure) {
    if (timeout)
      throw new ApiError(
        504,
        "EVIDENCE_LIBRARY_TIMEOUT",
        "The evidence request did not finish within 30 seconds. Retry when the API is available.",
      );
    throw failure;
  } finally {
    window.clearTimeout(timer);
    signal.removeEventListener("abort", abort);
  }
}
function Failure({ error, retry }: { error: Error; retry: () => void }) {
  return (
    <div className="notice danger" role="alert">
      <TriangleAlert size={18} />
      <div>
        <strong>{error.message}</strong>
        {error instanceof ApiError && error.requestId && (
          <small>Request {error.requestId}</small>
        )}
      </div>
      <button className="text-button" onClick={retry}>
        Retry
      </button>
    </div>
  );
}
function Coverage({ summary }: { summary: Summary }) {
  return (
    <div
      className="evidence-library-group-counts"
      aria-label="Supplied rows by group"
    >
      {GROUPS.map((group) => (
        <span key={group}>
          <small>{human(group)}</small>
          <strong>{summary.coverage[group].rowCount}</strong>
        </span>
      ))}
    </div>
  );
}
export function EvidenceLibraryPage({ user }: { user: User }) {
  return (
    <LibraryView key={`${user.tenantId}:${user.id}:${user.role}`} user={user} />
  );
}
function LibraryView({ user }: { user: User }) {
  const [query, setQuery] = useState<Query>(initialQuery);
  const [scopeDraft, setScopeDraft] = useState({ bank: "", branch: "" });
  const [scopeError, setScopeError] = useState<string | null>(null);
  const [data, setData] = useState<Library | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const [revision, setRevision] = useState(0);
  const [inspected, setInspected] = useState<Item | null>(null);
  const inspectorRef = useRef<HTMLDivElement | null>(null);
  const inspectTrigger = useRef<HTMLButtonElement | null>(null);
  const canWrite = ["ANALYST", "REVIEWER"].includes(user.role.toUpperCase());
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    const timer = window.setTimeout(() => {
      const params = new URLSearchParams({
        search: query.search,
        coverage: query.coverage,
        source: query.source,
        page: String(query.page),
      });
      if (query.bank) params.set("bank", query.bank);
      if (query.branch) params.set("branch", query.branch);
      read<Library>(`/evidences?${params}`, controller.signal)
        .then((value) => {
          if (controller.signal.aborted) return;
          validateLibrary(value);
          setData(value);
        })
        .catch((failure: Error) => {
          if (!controller.signal.aborted) setError(failure);
        })
        .finally(() => {
          if (!controller.signal.aborted) setLoading(false);
        });
    }, 250);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [query, revision]);
  useEffect(() => {
    if (inspected)
      inspectorRef.current?.scrollIntoView?.({
        behavior: "smooth",
        block: "start",
      });
  }, [inspected?.caseId]);
  function change(next: Partial<Query>, resetPage = true) {
    setInspected(null);
    setQuery((current) => ({
      ...current,
      ...next,
      ...(resetPage ? { page: 1 } : {}),
    }));
  }
  function refresh() {
    setInspected(null);
    setRevision((value) => value + 1);
  }
  function applyScope() {
    if (
      !Object.values(scopeDraft).every((value) => /^[0-9]{0,10}$/.test(value))
    ) {
      setScopeError(
        "Use up to 10 digits for bank and branch, or leave them blank for all.",
      );
      return;
    }
    setScopeError(null);
    change(scopeDraft);
  }
  function resetFilters() {
    setScopeDraft({ bank: "", branch: "" });
    setScopeError(null);
    change(initialQuery);
  }
  const scopeDirty =
    scopeDraft.bank !== query.bank || scopeDraft.branch !== query.branch;
  const filtered =
    !!query.search ||
    query.coverage !== "ALL" ||
    query.source !== "ALL" ||
    !!query.bank ||
    !!query.branch;
  const page = data?.page ?? 1;
  const firstButton = Math.max(
    1,
    Math.min(page - 2, (data?.totalPages ?? 1) - 4),
  );
  const pages = Array.from(
    { length: Math.min(5, data?.totalPages ?? 1) },
    (_, index) => firstButton + index,
  );
  return (
    <div className="evidence-library">
      <div className="page-heading">
        <div>
          <div className="eyebrow">SAVED CASE EVIDENCE</div>
          <h1>Evidence library</h1>
          <p>
            See which payment cases have supporting records, inspect their saved
            versions, and ask questions using a selected version.
          </p>
        </div>
        <a
          className="secondary evidence-library-link"
          href="/cases"
          data-case-queue-mode="payment"
          onClick={navigateLink}
        >
          <FolderOpen size={16} /> Find payment <ArrowUpRight size={14} />
        </a>
      </div>
      <PaymentWorkflow current="evidence" />
      <div className="evidence-library-summary-label">
        <h2>Your evidence at a glance</h2>
        <p>Totals across all cases you can access, before filters.</p>
      </div>
      <div className="evidence-library-summary">
        {[
          {
            label: "Saved cases",
            value: data?.summary.caseCount,
            detail: "Authorized payment cases",
            icon: FolderOpen,
          },
          {
            label: "Cases with rows",
            value: data?.summary.casesWithRows,
            detail: "Source rows in the latest version",
            icon: Database,
          },
          {
            label: "Cases without rows",
            value: data?.summary.casesWithoutRows,
            detail: "No version or an empty latest version",
            icon: FileText,
          },
          {
            label: "Evidence versions",
            value: data?.summary.evidenceVersions,
            detail: "Preserved across all case histories",
            icon: FileStack,
          },
        ].map(({ label, value, detail, icon: Icon }) => (
          <article key={label}>
            <div>
              <span>{label}</span>
              <Icon size={18} />
            </div>
            <strong>{value?.toLocaleString() ?? "—"}</strong>
            <small>{detail}</small>
          </article>
        ))}
      </div>
      <section
        className="panel evidence-library-records"
        aria-labelledby="evidence-library-cases-title"
      >
        <div className="section-title">
          <div>
            <h2 id="evidence-library-cases-title">Case evidence</h2>
            <p className="muted">
              One row per case. Coverage describes which groups contain rows; it
              does not establish a payment outcome or source completeness.
            </p>
          </div>
          <button className="secondary" disabled={loading} onClick={refresh}>
            <RefreshCw size={15} /> Refresh
          </button>
        </div>
        <div
          className="evidence-library-filters"
          role="search"
          aria-label="Filter case evidence"
        >
          <div className="evidence-library-search">
            <label htmlFor="evidence-library-search">
              Search cases and payments
            </label>
            <div>
              <Search size={16} />
              <input
                id="evidence-library-search"
                type="search"
                maxLength={200}
                value={query.search}
                placeholder="Case number, payment reference, UTR or reason"
                onChange={(event) => change({ search: event.target.value })}
              />
            </div>
          </div>
          <div>
            <label htmlFor="evidence-library-coverage">Row coverage</label>
            <select
              id="evidence-library-coverage"
              value={query.coverage}
              onChange={(event) =>
                change({ coverage: event.target.value as Query["coverage"] })
              }
            >
              <option value="ALL">All coverage</option>
              {Object.entries(COVERAGE).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label htmlFor="evidence-library-source">Latest source</label>
            <select
              id="evidence-library-source"
              value={query.source}
              onChange={(event) =>
                change({ source: event.target.value as Query["source"] })
              }
            >
              {Object.entries(SOURCES).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label htmlFor="evidence-library-bank">Bank</label>
            <input
              id="evidence-library-bank"
              type="text"
              inputMode="numeric"
              maxLength={10}
              placeholder="All banks"
              value={scopeDraft.bank}
              aria-describedby="evidence-library-scope-help"
              aria-invalid={
                !!scopeError && !/^[0-9]{0,10}$/.test(scopeDraft.bank)
              }
              onChange={(event) => {
                setScopeDraft((current) => ({
                  ...current,
                  bank: event.target.value,
                }));
                setScopeError(null);
              }}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  event.preventDefault();
                  applyScope();
                }
              }}
            />
          </div>
          <div>
            <label htmlFor="evidence-library-branch">Branch</label>
            <input
              id="evidence-library-branch"
              type="text"
              inputMode="numeric"
              maxLength={10}
              placeholder="All branches"
              value={scopeDraft.branch}
              aria-describedby="evidence-library-scope-help"
              aria-invalid={
                !!scopeError && !/^[0-9]{0,10}$/.test(scopeDraft.branch)
              }
              onChange={(event) => {
                setScopeDraft((current) => ({
                  ...current,
                  branch: event.target.value,
                }));
                setScopeError(null);
              }}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  event.preventDefault();
                  applyScope();
                }
              }}
            />
          </div>
        </div>
        <div className="evidence-library-results-heading">
          <p id="evidence-library-scope-help">
            {scopeDirty
              ? `Bank/branch edits are not applied. Current filters: bank ${query.bank || "all"}, branch ${query.branch || "all"}.`
              : "Leave bank or branch blank for all. Apply to filter saved cases."}
          </p>
          <button
            className="secondary"
            disabled={!scopeDirty}
            onClick={applyScope}
          >
            Apply bank / branch
          </button>
        </div>
        {scopeError && (
          <div className="notice danger" role="alert">
            {scopeError}
          </div>
        )}
        <div className="evidence-library-results-heading">
          <p role="status">
            {loading
              ? "Loading matching cases…"
              : error
                ? "Search unavailable"
                : data?.total
                  ? `Showing ${(data.page - 1) * 10 + 1}–${(data.page - 1) * 10 + data.items.length} of ${data.total} matching cases`
                  : "No matching cases"}
          </p>
          {(filtered || scopeDirty) && (
            <button className="text-button" onClick={resetFilters}>
              Clear filters
            </button>
          )}
        </div>
        {error ? (
          <Failure error={error} retry={refresh} />
        ) : loading ? (
          <div className="evidence-library-loading">
            <LoaderCircle size={23} className="spin" />
            <p>Reading saved evidence metadata…</p>
          </div>
        ) : data?.items.length ? (
          <>
            <div className="evidence-library-table-wrap">
              <table role="table">
                <thead>
                  <tr>
                    <th>Payment / case</th>
                    <th>Bank / branch</th>
                    <th>Latest evidence</th>
                    <th>Row coverage</th>
                    <th>Rows by group</th>
                    <th>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((item) => (
                    <tr
                      key={item.caseId}
                      className={
                        inspected?.caseId === item.caseId ? "selected" : ""
                      }
                    >
                      <td data-label="Payment / case">
                        <span
                          className="evidence-library-mobile-label"
                          aria-hidden="true"
                        >
                          Payment / case
                        </span>
                        <strong className="evidence-library-reference">
                          {item.reference}
                        </strong>
                        <span>{paymentCaseNumber(item)}</span>
                        <small>UTR: {known(item.utr)}</small>
                        <details className="evidence-library-reason">
                          <summary>Case context</summary>
                          <p>{item.reason}</p>
                          <p>
                            Source amount: {item.amount} {known(item.currency)}
                          </p>
                        </details>
                      </td>
                      <td data-label="Bank / branch">
                        <span
                          className="evidence-library-mobile-label"
                          aria-hidden="true"
                        >
                          Bank / branch
                        </span>
                        <strong>{item.orgBank}</strong>
                        <span>{item.orgBranch}</span>
                      </td>
                      <td data-label="Latest evidence">
                        <span
                          className="evidence-library-mobile-label"
                          aria-hidden="true"
                        >
                          Latest evidence
                        </span>
                        {item.latestEvidence ? (
                          <>
                            <strong>
                              Version {item.latestEvidence.version}{" "}
                              <small>of {item.versionCount}</small>
                            </strong>
                            <span>
                              {sourceLabel(item.latestEvidence.sourceKind)}
                            </span>
                            <time
                              dateTime={item.latestEvidence.createdAt}
                              title={item.latestEvidence.createdAt}
                            >
                              {date(item.latestEvidence.createdAt)}
                            </time>
                          </>
                        ) : (
                          <>
                            <strong>No version saved</strong>
                            <small>Case updated</small>
                            <time
                              dateTime={item.updatedAt}
                              title={item.updatedAt}
                            >
                              {date(item.updatedAt)}
                            </time>
                          </>
                        )}
                      </td>
                      <td data-label="Row coverage">
                        <span
                          className="evidence-library-mobile-label"
                          aria-hidden="true"
                        >
                          Row coverage
                        </span>
                        <span
                          className={`evidence-library-coverage-badge ${item.coverageState.toLowerCase()}`}
                        >
                          {COVERAGE[item.coverageState]}
                        </span>
                      </td>
                      <td data-label="Rows by group">
                        <span
                          className="evidence-library-mobile-label"
                          aria-hidden="true"
                        >
                          Rows by group
                        </span>
                        {item.latestEvidence ? (
                          <Coverage summary={item.latestEvidence} />
                        ) : (
                          <span className="muted">
                            No saved rows to inspect
                          </span>
                        )}
                      </td>
                      <td data-label="Actions">
                        <span
                          className="evidence-library-mobile-label"
                          aria-hidden="true"
                        >
                          Actions
                        </span>
                        <div className="evidence-library-actions">
                          {item.latestEvidence && (
                            <button
                              className="secondary"
                              aria-label={`Inspect evidence for ${paymentCaseNumber(item)}`}
                              onClick={(event) => {
                                inspectTrigger.current = event.currentTarget;
                                setInspected(item);
                              }}
                            >
                              <Eye size={14} /> Inspect
                            </button>
                          )}
                          {item.latestEvidence &&
                            hasRows(item.latestEvidence) && (
                              <a
                                href={questionsPath(
                                  paymentCaseNumber(item),
                                  item.latestEvidence.id,
                                )}
                                onClick={navigateLink}
                                aria-label={`${canWrite && item.lifecycleState !== "ARCHIVED" ? "Ask questions for" : "View questions for"} ${paymentCaseNumber(item)} using evidence version ${item.latestEvidence.version}`}
                              >
                                {canWrite && item.lifecycleState !== "ARCHIVED"
                                  ? "Ask questions"
                                  : "View questions"}
                                <ArrowUpRight size={13} />
                              </a>
                            )}
                          <a
                            href={paymentCasePath(item)}
                            onClick={navigateLink}
                            aria-label={`${!hasRows(item.latestEvidence) && canWrite && item.lifecycleState !== "ARCHIVED" ? "Collect evidence for" : "Open case"} ${paymentCaseNumber(item)}`}
                          >
                            {!hasRows(item.latestEvidence) &&
                            canWrite &&
                            item.lifecycleState !== "ARCHIVED"
                              ? "Collect evidence"
                              : "Open case"}
                            <ArrowUpRight size={13} />
                          </a>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <nav
              className="evidence-library-pagination"
              aria-label="Evidence library pagination"
            >
              <button
                className="secondary"
                disabled={page <= 1}
                onClick={() => change({ page: page - 1 }, false)}
              >
                <ChevronLeft size={15} /> Previous
              </button>
              <div>
                {pages.map((number) => (
                  <button
                    key={number}
                    aria-label={`Page ${number}`}
                    aria-current={page === number ? "page" : undefined}
                    className={page === number ? "current" : ""}
                    onClick={() => change({ page: number }, false)}
                  >
                    {number}
                  </button>
                ))}
              </div>
              <button
                className="secondary"
                disabled={page >= data.totalPages}
                onClick={() => change({ page: page + 1 }, false)}
              >
                Next <ChevronRight size={15} />
              </button>
            </nav>
          </>
        ) : (
          <div className="evidence-library-empty">
            <FileStack size={30} />
            <h3>
              {data?.summary.caseCount === 0
                ? "Start with a payment case"
                : "No cases match these filters"}
            </h3>
            <p>
              {data?.summary.caseCount === 0
                ? "Find a payment and save a case. Its evidence versions will appear here as you collect source records."
                : "Adjust the search, source or row-coverage filters to find a saved case."}
            </p>
            {data?.summary.caseCount === 0 ? (
              <a
                className="secondary evidence-library-link"
                href="/cases"
                data-case-queue-mode="payment"
                onClick={navigateLink}
              >
                Find payment <ArrowRight size={15} />
              </a>
            ) : (
              <button className="secondary" onClick={resetFilters}>
                Reset filters
              </button>
            )}
          </div>
        )}
        {data && (
          <p className="evidence-library-asof">
            Metadata read at{" "}
            <time dateTime={data.generatedAt}>{data.generatedAt}</time>. Source
            rows are loaded only when you inspect a version.
          </p>
        )}
      </section>
      {inspected && (
        <div ref={inspectorRef}>
          <EvidenceInspector
            key={`${inspected.caseId}:${inspected.latestEvidence?.id}`}
            item={inspected}
            canWrite={canWrite && inspected.lifecycleState !== "ARCHIVED"}
            onClose={() => {
              setInspected(null);
              inspectTrigger.current?.focus();
            }}
          />
        </div>
      )}
    </div>
  );
}

function EvidenceInspector({
  item,
  canWrite,
  onClose,
}: {
  item: Item;
  canWrite: boolean;
  onClose: () => void;
}) {
  const base = `/payment-cases/${encodeURIComponent(item.caseId)}/evidence`;
  const [versions, setVersions] = useState<Summary[]>([]);
  const [selected, setSelected] = useState("");
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [group, setGroup] = useState<Group>("PAYMENT");
  const [rowIndex, setRowIndex] = useState(0);
  const [fieldSearch, setFieldSearch] = useState("");
  const [loading, setLoading] = useState(true);
  const [snapshotLoading, setSnapshotLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [snapshotError, setSnapshotError] = useState<Error | null>(null);
  const [revision, setRevision] = useState(0);
  const [snapshotRevision, setSnapshotRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    setSnapshot(null);
    read<{ items: Summary[] }>(base, controller.signal)
      .then((result) => {
        if (controller.signal.aborted) return;
        if (!Array.isArray(result?.items))
          throw new Error("The saved evidence versions could not be read.");
        result.items.forEach((value) => validateSummary(value, item.caseId));
        setVersions(result.items);
        setSelected(
          result.items.some((value) => value.id === item.latestEvidence?.id)
            ? item.latestEvidence!.id
            : (result.items[0]?.id ?? ""),
        );
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setError(failure);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [base, item.caseId, revision]);
  useEffect(() => {
    const controller = new AbortController();
    setSnapshot(null);
    setSnapshotError(null);
    setGroup("PAYMENT");
    setRowIndex(0);
    setFieldSearch("");
    const expected = versions.find((version) => version.id === selected);
    if (!expected) {
      setSnapshotLoading(false);
      return () => controller.abort();
    }
    setSnapshotLoading(true);
    read<Snapshot>(`${base}/${encodeURIComponent(selected)}`, controller.signal)
      .then((value) => {
        if (controller.signal.aborted) return;
        validateSnapshot(value, expected, item);
        setSnapshot(value);
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setSnapshotError(failure);
      })
      .finally(() => {
        if (!controller.signal.aborted) setSnapshotLoading(false);
      });
    return () => controller.abort();
  }, [base, item.caseId, selected, versions, snapshotRevision]);
  const rows = snapshot?.payload.sections[group].rows ?? [];
  const fields = Object.entries(rows[rowIndex] ?? {});
  const matchingFields = fields.filter(([key, value]) =>
    `${key} ${value}`.toLowerCase().includes(fieldSearch.toLowerCase()),
  );
  return (
    <section
      className="panel evidence-library-inspector"
      aria-labelledby="evidence-inspector-title"
    >
      <div className="section-title">
        <div>
          <div className="eyebrow">READ-ONLY VERSION INSPECTION</div>
          <h2 id="evidence-inspector-title">Evidence preview</h2>
          <p className="evidence-library-preview-reference">
            {item.reference} <span>{paymentCaseNumber(item)}</span>
          </p>
        </div>
        <button
          className="icon-button"
          aria-label="Close evidence preview"
          onClick={onClose}
        >
          <X size={20} />
        </button>
      </div>
      <div className="evidence-library-preview-intro">
        <p>
          Inspect preserved source values and choose a version for Evidence
          Q&amp;A. Open the case to collect more evidence.
        </p>
        <a
          className="secondary evidence-library-link"
          href={paymentCasePath(item)}
          onClick={navigateLink}
        >
          Open case <ArrowUpRight size={14} />
        </a>
      </div>
      {loading && (
        <p role="status">
          <LoaderCircle className="spin" size={16} /> Loading saved versions…
        </p>
      )}
      {error && (
        <Failure
          error={error}
          retry={() => setRevision((value) => value + 1)}
        />
      )}
      {!loading && !error && !versions.length && (
        <p>
          No evidence versions were returned for this case. Refresh the library
          to check its latest metadata.
        </p>
      )}
      {!loading && !error && versions.length > 0 && (
        <>
          <label htmlFor="evidence-inspector-version">Evidence version</label>
          <select
            id="evidence-inspector-version"
            value={selected}
            onChange={(event) => setSelected(event.target.value)}
          >
            {versions.map((version) => (
              <option key={version.id} value={version.id}>
                Version {version.version} · {sourceLabel(version.sourceKind)} ·{" "}
                {version.createdAt}
              </option>
            ))}
          </select>
          {snapshotLoading && (
            <p role="status">
              <LoaderCircle className="spin" size={16} /> Loading selected
              source rows…
            </p>
          )}
          {snapshotError && (
            <Failure
              error={snapshotError}
              retry={() => setSnapshotRevision((value) => value + 1)}
            />
          )}
          {snapshot && snapshot.id === selected && !snapshotLoading && (
            <>
              <div className="evidence-library-preview-intro">
                <p>
                  {hasRows(snapshot)
                    ? `Continue to Evidence Q&A with version ${snapshot.version}. Saved questions retain the exact evidence version used.`
                    : `Version ${snapshot.version} has no source rows. Collect evidence before asking a question about this version.`}
                </p>
                {hasRows(snapshot) ? (
                  <a
                    className="secondary evidence-library-link"
                    href={questionsPath(paymentCaseNumber(item), snapshot.id)}
                    onClick={navigateLink}
                  >
                    {canWrite
                      ? "Ask about this version"
                      : "View questions for this version"}
                    <ArrowUpRight size={14} />
                  </a>
                ) : canWrite ? (
                  <a
                    className="secondary evidence-library-link"
                    href={paymentCasePath(item)}
                    onClick={navigateLink}
                  >
                    Collect evidence <ArrowUpRight size={14} />
                  </a>
                ) : null}
              </div>
              <dl className="evidence-library-preview-facts">
                <div>
                  <dt>Bank / branch</dt>
                  <dd>
                    {snapshot.payload.payment.orgBank} /{" "}
                    {snapshot.payload.payment.orgBranch}
                  </dd>
                </div>
                <div>
                  <dt>Source</dt>
                  <dd>{sourceLabel(snapshot.sourceKind)}</dd>
                </div>
                <div>
                  <dt>Saved by</dt>
                  <dd>{snapshot.createdBy}</dd>
                </div>
                <div>
                  <dt>Source timezone</dt>
                  <dd>{snapshot.payload.sourceTimezone}</dd>
                </div>
              </dl>
              {snapshot.id !== versions[0]?.id && (
                <p className="notice neutral">
                  You are inspecting an earlier immutable version. Its records
                  are preserved separately from the latest version.
                </p>
              )}
              <div className="evidence-library-inspector-coverage">
                {GROUPS.map((key) => (
                  <div key={key}>
                    <strong>{human(key)}</strong>
                    <span>
                      {snapshot.coverage[key].rowCount}{" "}
                      {snapshot.coverage[key].rowCount === 1 ? "row" : "rows"}
                    </span>
                    <small>
                      {snapshot.coverage[key].completion === "COMPLETE"
                        ? "Query fetch recorded"
                        : "Query fetch unverified"}
                    </small>
                  </div>
                ))}
              </div>
              <p className="muted">
                Row presence and query fetch metadata are separate from the
                payment outcome. Missing rows do not establish that an event did
                not occur.
              </p>
              {snapshot.warnings.length > 0 && (
                <div className="notice neutral">
                  <TriangleAlert size={17} />
                  <div>
                    <strong>Source limitations</strong>
                    <ul>
                      {snapshot.warnings.map((warning, index) => (
                        <li key={index}>{warning}</li>
                      ))}
                    </ul>
                  </div>
                </div>
              )}
              <EvidenceProvenance upstream={snapshot.upstream} />
              <div className="evidence-library-field-toolbar">
                <div>
                  <label htmlFor="evidence-inspector-group">Source group</label>
                  <select
                    id="evidence-inspector-group"
                    value={group}
                    onChange={(event) => {
                      setGroup(event.target.value as Group);
                      setRowIndex(0);
                      setFieldSearch("");
                    }}
                  >
                    {GROUPS.map((key) => (
                      <option key={key} value={key}>
                        {key} · {snapshot.payload.sections[key].rows.length}{" "}
                        {snapshot.payload.sections[key].rows.length === 1
                          ? "row"
                          : "rows"}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label htmlFor="evidence-inspector-row">Source row</label>
                  <select
                    id="evidence-inspector-row"
                    value={rowIndex}
                    disabled={!rows.length}
                    onChange={(event) => {
                      setRowIndex(Number(event.target.value));
                      setFieldSearch("");
                    }}
                  >
                    {rows.length ? (
                      rows.map((_, index) => (
                        <option key={index} value={index}>
                          Row {index + 1}
                        </option>
                      ))
                    ) : (
                      <option value={0}>No rows</option>
                    )}
                  </select>
                </div>
                <div>
                  <label htmlFor="evidence-inspector-fields">
                    Find field or value
                  </label>
                  <input
                    id="evidence-inspector-fields"
                    type="search"
                    maxLength={200}
                    value={fieldSearch}
                    disabled={!rows.length}
                    placeholder="Filter native fields"
                    onChange={(event) => setFieldSearch(event.target.value)}
                  />
                </div>
              </div>
              {rows.length ? (
                <>
                  <p className="evidence-library-field-count">
                    Showing {matchingFields.length} of {fields.length} native
                    fields · values retained as text
                  </p>
                  {matchingFields.length ? (
                    <dl className="evidence-library-native-fields">
                      {matchingFields.map(([key, value]) => (
                        <div key={key}>
                          <dt>{key}</dt>
                          <dd>
                            {isSourceOmitted(
                              snapshot.upstream,
                              group,
                              rowIndex,
                              key,
                            ) ? (
                              <span className="muted">Not supplied</span>
                            ) : isSourceNull(
                                snapshot.upstream,
                                group,
                                rowIndex,
                                key,
                              ) ? (
                              <span className="muted">(source null)</span>
                            ) : value === "" ? (
                              <span className="muted">(blank)</span>
                            ) : (
                              value
                            )}
                          </dd>
                        </div>
                      ))}
                    </dl>
                  ) : (
                    <p>No field names or values match this filter.</p>
                  )}
                </>
              ) : (
                <div className="evidence-library-empty-group">
                  <FileText size={22} />
                  <p>
                    No {group} rows were saved in this version. This does not
                    establish a negative payment outcome.
                  </p>
                </div>
              )}
              <p className="evidence-library-source-note">
                <strong>Source note:</strong>{" "}
                {snapshot.payload.sections[group].note || "No note supplied."}
              </p>
              <details className="evidence-library-fingerprint">
                <summary>Version provenance</summary>
                <dl>
                  <div>
                    <dt>Evidence ID</dt>
                    <dd>{snapshot.id}</dd>
                  </div>
                  <div>
                    <dt>Saved at</dt>
                    <dd>{snapshot.createdAt}</dd>
                  </div>
                  <div>
                    <dt>Evidence fingerprint</dt>
                    <dd>{snapshot.evidenceHash}</dd>
                  </div>
                </dl>
              </details>
            </>
          )}
        </>
      )}
    </section>
  );
}
