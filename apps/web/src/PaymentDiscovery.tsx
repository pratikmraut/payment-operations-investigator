import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
  type MouseEvent,
} from "react";
import {
  ArrowLeft,
  ArrowRight,
  CheckCircle2,
  Database,
  Download,
  FolderKanban,
  LoaderCircle,
  RefreshCw,
  Search,
  ShieldQuestion,
  TriangleAlert,
  Upload,
  X,
} from "lucide-react";
import { api, ApiError, human } from "./api";
import type { User } from "./types";
import { CaseEvidence } from "./CaseEvidence";
import { CaseInvestigation } from "./CaseInvestigation";
import { CaseManagement } from "./CaseManagement";
import { CaseReport } from "./CaseReport";
import { CasePageNavigation } from "./CasePageNavigation";
import { navigateLink, navigateTo, replaceDestination } from "./routing";
import { paymentCaseNumber, paymentCasePath } from "./paymentCaseIdentity";
import { PaymentWorkflow } from "./PaymentWorkflow";
import {
  CASE_LIFECYCLE_CHANGED,
  type CaseLifecycleState,
} from "./CaseLifecycle";

export type DiscoveryConfig = {
  mode: "MOCK" | "BANK_API" | "DISABLED";
  today: string;
  timezone: string;
  maxRecords: number;
  scopes: { orgBranch: string; orgBank: string; label: string }[];
  directLookupScope: string;
};
export type PaymentCandidate = {
  candidateId: string;
  reference: string;
  utr: string | null;
  orgBranch: string;
  orgBank: string;
  hostSubsequences: (string | null)[];
  initiatedAt: string;
  amount: string;
  currency: string | null;
  sourceKind: string;
  dataClassification: string;
  existingCaseId?: string;
  existingCaseNumber?: string;
};
export type DiscoveryBatch = {
  batchId: string;
  mode: DiscoveryConfig["mode"];
  sourceKind: string;
  observedAt: string;
  coverage: string;
  truncated: boolean;
  matchStatus?: "NOT_FOUND" | "EXACT_MATCH" | "AMBIGUOUS";
  items: PaymentCandidate[];
  warnings: string[];
};
export type PaymentCase = PaymentCandidate & {
  id: string;
  caseNumber?: string;
  lifecycleState?: CaseLifecycleState;
  lifecycleVersion?: number;
  reason: string;
  status: "OPEN";
  priority: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
  owner?: { id: string; name: string } | null;
  managementVersion?: number;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
  evidenceStatus:
    "DISCOVERY_ONLY" | "EVIDENCE_ATTACHED" | "EMPTY_EVIDENCE_ATTACHED";
};
type Counts = {
  openCases: number;
  highPriorityCases: number;
  awaitingReview: number;
  resolvedCases: number;
};
type DiscoveryMode = "reference" | "upload" | "api";
const SAVED_CASES_PAGE_SIZE = 10;

function hostSubsequenceText(values: PaymentCandidate["hostSubsequences"]) {
  return (
    values.map((value) => (value === null ? "Unknown" : value)).join(", ") ||
    "None supplied"
  );
}

function useRead<T>(path: string, revision = 0, preserveOnRefresh = false) {
  const [state, setState] = useState<{
    data: T | null;
    error: Error | null;
    loading: boolean;
    path: string;
  }>({ data: null, error: null, loading: true, path });
  useEffect(() => {
    const request = new AbortController();
    setState((current) => ({
      data: preserveOnRefresh && current.path === path ? current.data : null,
      error: null,
      loading: true,
      path,
    }));
    api<T>(path, { signal: request.signal })
      .then((data) => {
        if (!request.signal.aborted)
          setState({ data, error: null, loading: false, path });
      })
      .catch((error: Error) => {
        if (!request.signal.aborted)
          setState((current) => ({
            data:
              preserveOnRefresh && current.path === path ? current.data : null,
            error,
            loading: false,
            path,
          }));
      });
    return () => request.abort();
  }, [path, revision, preserveOnRefresh]);
  return state.path === path
    ? state
    : { data: null, error: null, loading: true, path };
}
function Failure({ error, retry }: { error: Error; retry?: () => void }) {
  return (
    <div className="notice danger" role="alert">
      <TriangleAlert size={18} />
      <div>
        <strong>{error.message}</strong>
        {error instanceof ApiError && error.requestId && (
          <small>Request {error.requestId}</small>
        )}
      </div>
      {retry && (
        <button className="text-button" onClick={retry}>
          Try again
        </button>
      )}
    </div>
  );
}
function Pending({ children }: { children: string }) {
  return (
    <div className="loading" role="status">
      <LoaderCircle size={20} className="spin" />
      {children}
    </div>
  );
}
function Amount({
  item,
}: {
  item: Pick<PaymentCandidate, "amount" | "currency">;
}) {
  return (
    <>
      <span className="payment-source-value">{item.amount}</span>
      <small>{item.currency || "Currency not supplied"}</small>
    </>
  );
}
function classificationLabel(classification: string) {
  return classification === "PRIVATE_UAT" ? "Private evidence" : classification;
}
function Provenance({
  item,
}: {
  item: Pick<PaymentCandidate, "sourceKind" | "dataClassification">;
}) {
  return (
    <>
      <span>
        {item.sourceKind === "MOCK" ? "MOCK" : human(item.sourceKind)}
      </span>
      <small>{classificationLabel(item.dataClassification)}</small>
    </>
  );
}

export function PaymentCasesPage({
  user,
  onOpen,
}: {
  user: User;
  onOpen: (id: string) => void;
}) {
  const [revision, setRevision] = useState(0);
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [lifecycle, setLifecycle] = useState<CaseLifecycleState | "ALL">(
    "ACTIVE",
  );
  const records = useRead<{ items: PaymentCase[]; total: number }>(
    lifecycle === "ACTIVE"
      ? "/payment-cases"
      : `/payment-cases?lifecycle=${lifecycle}`,
    revision,
  );
  const dashboard = useRead<Counts>("/payment-cases/dashboard", revision);
  const refresh = () => {
    setPage(1);
    setRevision((value) => value + 1);
  };
  const filteredCases = useMemo(() => {
    const terms = search.trim().toLowerCase().split(/\s+/).filter(Boolean);
    return (records.data?.items ?? []).filter((item) => {
      const fields = [
        item.id,
        item.caseNumber ?? "",
        item.reference,
        item.utr ?? "",
        item.reason,
        item.owner?.name ?? "",
        item.owner?.id ?? "",
        item.priority,
      ].map((value) => value.toLowerCase());
      return terms.every((term) =>
        fields.some((value) => value.includes(term)),
      );
    });
  }, [records.data, search]);
  const pageCount = Math.max(
    1,
    Math.ceil(filteredCases.length / SAVED_CASES_PAGE_SIZE),
  );
  const currentPage = Math.min(page, pageCount);
  const offset = (currentPage - 1) * SAVED_CASES_PAGE_SIZE;
  const visibleCases = filteredCases.slice(
    offset,
    offset + SAVED_CASES_PAGE_SIZE,
  );
  const firstPageButton = Math.max(1, Math.min(currentPage - 2, pageCount - 4));
  const pageButtons = Array.from(
    { length: Math.min(5, pageCount) },
    (_, index) => firstPageButton + index,
  );
  function changeSearch(value: string) {
    setSearch(value);
    setPage(1);
  }
  return (
    <div className="payment-discovery queue-workspace">
      <div className="page-heading">
        <div>
          <div className="eyebrow">PRIVATE PAYMENT WORKSPACE</div>
          <h1>Case queue</h1>
          <p>
            Find a payment, record why it needs investigation, and resume its
            saved case.
          </p>
        </div>
        <button
          className="secondary"
          onClick={refresh}
          disabled={records.loading || dashboard.loading}
        >
          <RefreshCw size={16} />
          Refresh queue
        </button>
      </div>
      <PaymentWorkflow current="find" />
      {dashboard.error && <Failure error={dashboard.error} retry={refresh} />}
      <div className="kpi-grid">
        {[
          {
            label: "Unresolved cases",
            value: dashboard.data?.openCases,
            icon: <FolderKanban />,
            hint: "Private cases requiring follow-up",
            kind: "blue",
          },
          {
            label: "High priority",
            value: dashboard.data?.highPriorityCases,
            icon: <TriangleAlert />,
            hint: "Unresolved high-priority cases",
            kind: "amber",
          },
          {
            label: "Awaiting review",
            value: dashboard.data?.awaitingReview,
            icon: <ShieldQuestion />,
            hint: "Independent decision required",
            kind: "purple",
          },
          {
            label: "Resolved",
            value: dashboard.data?.resolvedCases,
            icon: <CheckCircle2 />,
            hint: "Closed investigation cases",
            kind: "teal",
          },
        ].map((item) => (
          <div className="kpi" key={item.label}>
            <div className="kpi-top">
              <span>{item.label}</span>
              <span className={`kpi-icon ${item.kind}`}>{item.icon}</span>
            </div>
            <strong>{item.value ?? "—"}</strong>
            <small>{item.hint}</small>
          </div>
        ))}
      </div>
      <FindPayment user={user} onOpen={onOpen} />
      <section
        className="panel payment-saved-cases"
        aria-labelledby="saved-payments-title"
      >
        <div className="section-title">
          <h2 id="saved-payments-title">
            <FolderKanban size={21} aria-hidden="true" /> Saved payment cases
          </h2>
          <span className="queue-total">
            {records.data ? `${records.data.total} cases` : "Private records"}
          </span>
        </div>
        <p className="queue-section-description">
          Continue an investigation, review its evidence or export a report.
        </p>
        <p className="payment-help queue-count-guidance">
          These counts describe investigation cases, not payment success or
          failure. Discovery-only cases have no completed payment analysis.
        </p>
        <div className="payment-saved-toolbar">
          <label className="payment-lifecycle-filter">
            Case visibility
            <select
              value={lifecycle}
              onChange={(event) => {
                setLifecycle(event.target.value as CaseLifecycleState | "ALL");
                setPage(1);
              }}
            >
              <option value="ACTIVE">Active cases</option>
              <option value="ARCHIVED">Archived cases</option>
              <option value="ALL">All cases</option>
            </select>
          </label>
          <div className="payment-saved-search">
            <label htmlFor="saved-case-search">
              Search saved payment cases
            </label>
            <div className="search-field">
              <Search size={17} aria-hidden="true" />
              <input
                id="saved-case-search"
                type="search"
                placeholder="Case number, payment reference, reason or owner…"
                value={search}
                onChange={(event) => changeSearch(event.target.value)}
                aria-describedby="saved-case-search-help"
              />
              {search && (
                <button
                  type="button"
                  aria-label="Clear search"
                  onClick={() => changeSearch("")}
                >
                  <X size={15} />
                </button>
              )}
            </div>
            <small id="saved-case-search-help">
              Matches case number, payment reference, UTR, investigation reason,
              owner or priority.
            </small>
          </div>
          {records.data && !records.error && (
            <p className="payment-saved-count" role="status" aria-live="polite">
              {filteredCases.length
                ? `Showing ${offset + 1}–${Math.min(offset + SAVED_CASES_PAGE_SIZE, filteredCases.length)} of ${filteredCases.length} ${search.trim() ? "matching cases" : "cases"}`
                : search.trim()
                  ? "0 matching cases"
                  : "0 cases"}
            </p>
          )}
        </div>
        {records.error ? (
          <Failure error={records.error} retry={refresh} />
        ) : records.loading ? (
          <Pending>Loading saved payment cases…</Pending>
        ) : !records.data?.items.length ? (
          <div className="empty">
            <Database size={28} />
            <h3>
              {lifecycle === "ARCHIVED"
                ? "No archived cases"
                : lifecycle === "ACTIVE"
                  ? "No active payment cases"
                  : "No payment cases yet"}
            </h3>
            <p>
              {lifecycle === "ARCHIVED"
                ? "Archived cases appear here with their evidence and answers preserved."
                : "Use Find payment above, select a result and provide an investigation reason to open a case. Select Archived cases to find a case to restore."}
            </p>
          </div>
        ) : !filteredCases.length ? (
          <div className="empty">
            <Search size={28} />
            <h3>No matching cases</h3>
            <p>
              Try a different case/reference or words from the investigation
              reason.
            </p>
            <button
              className="secondary"
              type="button"
              onClick={() => changeSearch("")}
            >
              Show all cases
            </button>
          </div>
        ) : (
          <>
            <div className="table-scroll">
              <table className="case-table payment-records queue-case-table">
                <thead>
                  <tr>
                    <th>Case / payment reference</th>
                    <th>Bank / branch</th>
                    <th>Source amount</th>
                    <th>Investigation reason</th>
                    <th>State / evidence</th>
                    <th>Created / updated</th>
                    <th>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleCases.map((item) => (
                    <tr key={item.id}>
                      <td
                        data-label="Case / payment reference"
                        className="queue-case-identity"
                      >
                        <a
                          className="case-link"
                          href={paymentCasePath(item)}
                          onClick={navigateLink}
                        >
                          <span className="case-id queue-case-number">
                            {paymentCaseNumber(item)}
                          </span>
                          <strong className="queue-payment-reference">
                            {item.reference}
                          </strong>
                        </a>
                        <small>UTR: {item.utr || "Not supplied"}</small>
                        <div className="queue-provenance">
                          <Provenance item={item} />
                        </div>
                      </td>
                      <td data-label="Bank / branch">
                        {item.orgBank}
                        <small>Branch {item.orgBranch}</small>
                      </td>
                      <td className="amount" data-label="Source amount">
                        <Amount item={item} />
                      </td>
                      <td
                        className="payment-reason-cell"
                        data-label="Investigation reason"
                      >
                        {item.reason}
                      </td>
                      <td data-label="State / evidence">
                        <span
                          className={`queue-case-state priority-${item.priority.toLowerCase()}`}
                        >
                          {human(item.status)} · {human(item.priority)}
                        </span>
                        {item.lifecycleState === "ARCHIVED" && (
                          <small className="payment-source-label">
                            Archived
                          </small>
                        )}
                        <small>Owner: {item.owner?.name ?? "Unassigned"}</small>
                        <small>{human(item.evidenceStatus)}</small>
                      </td>
                      <td className="date-cell" data-label="Created / updated">
                        <time dateTime={item.createdAt}>{item.createdAt}</time>
                        <small>
                          Updated{" "}
                          <time dateTime={item.updatedAt}>
                            {item.updatedAt}
                          </time>
                        </small>
                      </td>
                      <td className="queue-case-actions" data-label="Actions">
                        <a
                          className="row-open queue-open-link"
                          aria-label={`Open ${paymentCaseNumber(item)}`}
                          href={paymentCasePath(item)}
                          onClick={navigateLink}
                        >
                          Open case <ArrowRight size={16} aria-hidden="true" />
                        </a>
                        <a
                          className="case-export-link"
                          href={`${paymentCasePath(item)}?report=1`}
                          onClick={navigateLink}
                          aria-label={`Export PDF for ${item.reference}`}
                        >
                          <Download size={15} aria-hidden="true" /> Export PDF
                        </a>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <nav
              className="payment-saved-pagination"
              aria-label="Saved cases pagination"
            >
              <span>
                Page {currentPage} of {pageCount} · 10 per page
              </span>
              <div className="payment-page-buttons">
                <button
                  type="button"
                  className="secondary"
                  aria-label="Previous page"
                  disabled={currentPage === 1}
                  onClick={() => setPage(currentPage - 1)}
                >
                  <ArrowLeft size={15} /> Previous
                </button>
                {firstPageButton > 1 && (
                  <>
                    <button
                      type="button"
                      className="secondary"
                      aria-label="Go to page 1"
                      onClick={() => setPage(1)}
                    >
                      1
                    </button>
                    {firstPageButton > 2 && <span aria-hidden="true">…</span>}
                  </>
                )}
                {pageButtons.map((number) => (
                  <button
                    type="button"
                    key={number}
                    className="secondary"
                    aria-label={`Go to page ${number}`}
                    aria-current={currentPage === number ? "page" : undefined}
                    onClick={() => setPage(number)}
                  >
                    {number}
                  </button>
                ))}
                {pageButtons[pageButtons.length - 1] < pageCount && (
                  <>
                    {pageButtons[pageButtons.length - 1] < pageCount - 1 && (
                      <span aria-hidden="true">…</span>
                    )}
                    <button
                      type="button"
                      className="secondary"
                      aria-label={`Go to page ${pageCount}`}
                      onClick={() => setPage(pageCount)}
                    >
                      {pageCount}
                    </button>
                  </>
                )}
                <button
                  type="button"
                  className="secondary"
                  aria-label="Next page"
                  disabled={currentPage === pageCount}
                  onClick={() => setPage(currentPage + 1)}
                >
                  Next <ArrowRight size={15} />
                </button>
              </div>
            </nav>
          </>
        )}
      </section>
    </div>
  );
}

export function FindPayment({
  user,
  onOpen,
}: {
  user: User;
  onOpen: (id: string) => void;
}) {
  const [configRevision, setConfigRevision] = useState(0);
  const config = useRead<DiscoveryConfig>(
    "/payment-discovery/config",
    configRevision,
  );
  const [mode, setMode] = useState<DiscoveryMode>("reference");
  const [scope, setScope] = useState<{ orgBank?: string; orgBranch?: string }>(
    {},
  );
  const initialScope = useRef<{ orgBank: string; orgBranch: string } | null>(
    null,
  );
  if (!initialScope.current && config.data) {
    const initial =
      config.data.scopes.find((item) => item.orgBank === "760") ??
      config.data.scopes[0];
    initialScope.current = {
      orgBank: initial?.orgBank ?? "",
      orgBranch: initial?.orgBranch ?? "",
    };
  }
  const orgBank = scope.orgBank ?? initialScope.current?.orgBank ?? "";
  const orgBranch = scope.orgBranch ?? initialScope.current?.orgBranch ?? "";
  const [inquiryDate, setDate] = useState("");
  const [recordCount, setCount] = useState("50");
  const [referenceType, setReferenceType] = useState<"FCR" | "UTR">("FCR");
  const [reference, setReference] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [batch, setBatch] = useState<DiscoveryBatch | null>(null);
  const [selectedId, setSelectedId] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<Error | null>(null);
  const [openError, setOpenError] = useState<Error | null>(null);
  const [busy, setBusy] = useState<"find" | "open" | null>(null);
  const request = useRef<AbortController | null>(null);
  const openAttempt = useRef<{ fingerprint: string; key: string } | null>(null);
  const canWrite = ["ANALYST", "REVIEWER"].includes(user.role.toUpperCase());
  const selected = batch?.items.find(
    (candidate) => candidate.candidateId === selectedId,
  );
  const maxRecords = Math.min(200, config.data?.maxRecords ?? 200);
  useEffect(() => {
    if (!config.data) return;
    clearResults();
    setDate((current) => current || config.data!.today);
    setCount((current) => String(Math.min(Number(current), maxRecords)));
  }, [config.data, maxRecords]);
  useEffect(
    () => () => {
      request.current?.abort();
      request.current = null;
    },
    [],
  );
  function clearResults() {
    request.current?.abort();
    request.current = null;
    setBusy(null);
    setBatch(null);
    setSelectedId("");
    setReason("");
    setError(null);
    setOpenError(null);
    openAttempt.current = null;
  }
  function changeMode(next: DiscoveryMode) {
    if (request.current) return;
    if (next !== mode) setFile(null);
    setMode(next);
    clearResults();
  }
  function cancel() {
    request.current?.abort();
    request.current = null;
    setBusy(null);
  }
  async function find(event: FormEvent) {
    event.preventDefault();
    if (request.current || !canWrite || !config.data) return;
    clearResults();
    if (!/^[0-9]{1,10}$/.test(orgBank) || !/^[0-9]{1,10}$/.test(orgBranch)) {
      setError(
        new Error("Enter bank and branch codes using 1 to 10 digits each."),
      );
      return;
    }
    if (mode !== "upload" && config.data.mode === "DISABLED") {
      setError(
        new Error(
          "The inquiry API is disabled. Use an Excel export to read transaction records.",
        ),
      );
      return;
    }
    const count = Number(recordCount);
    if (
      mode === "api" &&
      (!inquiryDate ||
        !Number.isInteger(count) ||
        count < 1 ||
        count > maxRecords)
    ) {
      setError(
        new Error(
          `Choose an inquiry date and a record count from 1 to ${maxRecords}.`,
        ),
      );
      return;
    }
    if (mode === "reference" && !reference.trim()) {
      setError(new Error("Enter the payment reference or UTR."));
      return;
    }
    if (
      mode === "upload" &&
      (!file ||
        !file.name.toLowerCase().endsWith(".xlsx") ||
        file.size > 5 * 1024 * 1024 ||
        file.size === 0)
    ) {
      setError(new Error("Choose a non-empty .xlsx file no larger than 5 MB."));
      return;
    }
    const active = new AbortController();
    request.current = active;
    setBusy("find");
    try {
      let body: BodyInit;
      if (mode === "upload") {
        const form = new FormData();
        form.append("file", file!);
        form.append("orgBank", orgBank);
        form.append("orgBranch", orgBranch);
        body = form;
      } else if (mode === "reference")
        body = JSON.stringify({
          orgBank,
          orgBranch,
          referenceType,
          reference: reference.trim(),
        });
      else
        body = JSON.stringify({
          orgBank,
          orgBranch,
          inquiryDate,
          recordCount: count,
        });
      const result = await api<DiscoveryBatch>(
        mode === "upload"
          ? "/payment-discovery/uploads"
          : mode === "reference"
            ? "/payment-discovery/lookup"
            : "/payment-discovery/search",
        { method: "POST", body, signal: active.signal },
      );
      if (!active.signal.aborted) setBatch(result);
    } catch (failure) {
      if (!active.signal.aborted) setError(failure as Error);
    } finally {
      if (request.current === active) {
        request.current = null;
        setBusy(null);
      }
    }
  }
  async function open(event: FormEvent) {
    event.preventDefault();
    if (request.current || !canWrite || !selected || !reason.trim()) return;
    const body = { candidateId: selected.candidateId, reason: reason.trim() };
    const fingerprint = JSON.stringify(body);
    if (openAttempt.current?.fingerprint !== fingerprint)
      openAttempt.current = { fingerprint, key: crypto.randomUUID() };
    const active = new AbortController();
    request.current = active;
    setBusy("open");
    setOpenError(null);
    try {
      const result = await api<{
        caseId: string;
        status: "CREATED" | "EXISTING";
        item: PaymentCase;
      }>("/payment-cases", {
        method: "POST",
        body: JSON.stringify(body),
        headers: { "Idempotency-Key": openAttempt.current.key },
        signal: active.signal,
      });
      if (!active.signal.aborted)
        onOpen(result.item?.caseNumber || result.caseId);
    } catch (failure) {
      if (!active.signal.aborted) setOpenError(failure as Error);
    } finally {
      if (request.current === active) {
        request.current = null;
        setBusy(null);
      }
    }
  }
  return (
    <section
      className="panel payment-find"
      aria-labelledby="find-payment-title"
    >
      <div className="section-title">
        <h2 id="find-payment-title">
          <Search size={18} />
          Find payment
        </h2>
        {config.data && (
          <span
            className={`payment-source-label ${mode !== "upload" && config.data.mode === "MOCK" ? "mock" : ""}`}
          >
            {mode === "upload"
              ? "Excel file import"
              : config.data.mode === "BANK_API"
                ? "Configured inquiry API"
                : config.data.mode === "MOCK"
                  ? "Mock inquiry API"
                  : "Inquiry API disabled"}
          </span>
        )}
      </div>
      <p className="payment-help queue-section-description">
        Find transaction records first. Opening a case saves the selected record
        and your reason; it does not run payment analysis.
      </p>
      {config.loading ? (
        <Pending>Loading authorized discovery options…</Pending>
      ) : config.error ? (
        <Failure
          error={config.error}
          retry={() => setConfigRevision((value) => value + 1)}
        />
      ) : (
        config.data && (
          <>
            {mode !== "upload" && config.data.mode === "MOCK" && (
              <div className="notice payment-mock">
                <TriangleAlert size={18} />
                <div>
                  <strong>MOCK inquiry source</strong>
                  <p>
                    Reference lookup and transaction lists use original
                    synthetic responses from the internal inquiry API. No bank
                    is connected. Check each result's source and classification.
                  </p>
                </div>
              </div>
            )}
            {!config.data.scopes.length && (
              <div className="notice">
                <ShieldQuestion size={18} />
                <p>
                  No branches are authorized for this account. An administrator
                  must configure discovery scope before you can search or
                  upload.
                </p>
              </div>
            )}
            {!canWrite ? (
              <div className="notice">
                <ShieldQuestion size={18} />
                <p>
                  Your role has read-only access. You can open saved cases; an
                  analyst or reviewer must discover payments and create cases.
                </p>
              </div>
            ) : (
              <>
                <div
                  className="queue-tabs payment-methods"
                  role="group"
                  aria-label="Find payment method"
                >
                  {[
                    {
                      id: "reference",
                      label: "Reference / UTR",
                      description: "Find an exact reference",
                      icon: <Search size={20} aria-hidden="true" />,
                    },
                    {
                      id: "upload",
                      label: "Excel upload",
                      description: "Import a transaction list",
                      icon: <Upload size={20} aria-hidden="true" />,
                    },
                    {
                      id: "api",
                      label: "Inquiry API",
                      description: "Search by date and branch",
                      icon: <Database size={20} aria-hidden="true" />,
                    },
                  ].map((tab) => (
                    <button
                      key={tab.id}
                      aria-label={tab.label}
                      aria-pressed={mode === tab.id}
                      className={mode === tab.id ? "selected" : ""}
                      disabled={busy !== null}
                      onClick={() => changeMode(tab.id as DiscoveryMode)}
                    >
                      <span className="discovery-method-icon">{tab.icon}</span>
                      <span className="discovery-method-copy">
                        <span>{tab.label}</span>
                        <small aria-hidden="true">{tab.description}</small>
                      </span>
                    </button>
                  ))}
                </div>
                <form
                  onSubmit={find}
                  className={`discovery-form discovery-form-${mode}`}
                >
                  <fieldset
                    className="payment-form-fields"
                    disabled={busy === "open"}
                  >
                    <legend className="sr-only">
                      Payment discovery inputs
                    </legend>
                    <label>
                      Authorized bank
                      <input
                        type="text"
                        inputMode="numeric"
                        pattern="[0-9]{1,10}"
                        maxLength={10}
                        autoComplete="off"
                        placeholder="Enter bank code"
                        aria-describedby="payment-scope-help"
                        required
                        value={orgBank}
                        onChange={(event) => {
                          const value = event.target.value;
                          setScope((current) => ({
                            ...current,
                            orgBank: value,
                          }));
                          clearResults();
                        }}
                      />
                    </label>
                    <label>
                      Authorized branch
                      <input
                        type="text"
                        inputMode="numeric"
                        pattern="[0-9]{1,10}"
                        maxLength={10}
                        autoComplete="off"
                        placeholder="Enter branch code"
                        aria-describedby="payment-scope-help"
                        required
                        value={orgBranch}
                        onChange={(event) => {
                          const value = event.target.value;
                          setScope((current) => ({
                            ...current,
                            orgBranch: value,
                          }));
                          clearResults();
                        }}
                      />
                    </label>
                    {mode === "reference" && (
                      <>
                        <label>
                          Reference type
                          <select
                            value={referenceType}
                            onChange={(event) => {
                              setReferenceType(
                                event.target.value as "FCR" | "UTR",
                              );
                              clearResults();
                            }}
                          >
                            <option value="FCR">FCR reference</option>
                            <option value="UTR">UTR</option>
                          </select>
                        </label>
                        <label className="payment-reference-input">
                          Payment reference or UTR
                          <input
                            type="text"
                            value={reference}
                            maxLength={100}
                            autoComplete="off"
                            required
                            onChange={(event) => {
                              setReference(event.target.value);
                              clearResults();
                            }}
                            placeholder="Paste the exact reference as text"
                          />
                        </label>
                      </>
                    )}
                    {mode === "api" && (
                      <>
                        <label>
                          Inquiry date
                          <input
                            type="date"
                            required
                            value={inquiryDate}
                            onChange={(event) => {
                              setDate(event.target.value);
                              clearResults();
                            }}
                          />
                          <small>
                            Today uses {config.data.timezone}; confirm the
                            bank's source date separately.
                          </small>
                        </label>
                        <label>
                          Maximum records
                          <input
                            type="number"
                            min={1}
                            max={maxRecords}
                            step={1}
                            required
                            value={recordCount}
                            onChange={(event) => {
                              setCount(event.target.value);
                              clearResults();
                            }}
                          />
                          <small>Up to {maxRecords} records</small>
                        </label>
                      </>
                    )}
                    {mode === "upload" && (
                      <label className="payment-upload-input">
                        Discovery Excel file
                        <input
                          type="file"
                          accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                          onChange={(event) => {
                            setFile(event.target.files?.[0] ?? null);
                            clearResults();
                          }}
                        />
                        <small>
                          .xlsx only, maximum 5 MB. Keep reference and UTR
                          columns as Text, with dates in ISO format.
                        </small>
                      </label>
                    )}
                  </fieldset>
                  <p
                    className="payment-help discovery-scope-help"
                    id="payment-scope-help"
                  >
                    Enter bank and branch codes. Access is checked for your
                    workspace.
                  </p>
                  {mode === "reference" && (
                    <details className="discovery-source-details">
                      <summary>Exact lookup scope</summary>
                      <p className="payment-help">
                        {config.data.directLookupScope} Review the returned
                        transaction before opening a case.
                      </p>
                    </details>
                  )}
                  {mode === "api" && (
                    <p className="payment-help">
                      Request only the authorized bank, branch, inquiry date and
                      record limit. Select a returned transaction before opening
                      a case.
                    </p>
                  )}
                  {mode === "upload" && (
                    <p className="payment-help">
                      <a
                        className="discovery-template-link"
                        href="/payment-discovery-template.xlsx"
                        download
                      >
                        <Download size={16} aria-hidden="true" />
                        Download discovery Excel template
                      </a>
                    </p>
                  )}
                  {mode !== "upload" && config.data.mode === "DISABLED" && (
                    <p className="payment-help">
                      The inquiry API is disabled for reference lookups and
                      transaction lists. Excel uploads remain available.
                    </p>
                  )}
                  <div className="payment-actions">
                    <button
                      className="primary"
                      type="submit"
                      disabled={
                        busy !== null ||
                        (mode !== "upload" && config.data.mode === "DISABLED")
                      }
                    >
                      {busy === "find" ? (
                        <LoaderCircle className="spin" size={17} />
                      ) : mode === "upload" ? (
                        <Upload size={17} />
                      ) : (
                        <Search size={17} />
                      )}
                      {mode === "upload"
                        ? "Read Excel records"
                        : mode === "reference"
                          ? "Find transaction"
                          : "Find transactions"}
                    </button>
                    {busy === "find" && (
                      <button
                        className="secondary"
                        type="button"
                        onClick={cancel}
                      >
                        Cancel search
                      </button>
                    )}
                  </div>
                </form>
                {busy === "find" && (
                  <Pending>Loading transaction records…</Pending>
                )}
                {error && <Failure error={error} />}
              </>
            )}
          </>
        )
      )}
      {batch && (
        <div className="payment-results">
          <div className="discovery-result-heading">
            <h3>Discovery results</h3>
            <span>
              {batch.items.length}{" "}
              {batch.items.length === 1 ? "transaction" : "transactions"}
            </span>
          </div>
          <p className="payment-help">
            Select a transaction, then add your investigation reason. Detailed
            payment evidence is collected after you open the case.
          </p>
          <details className="discovery-source-details">
            <summary>
              Result source and coverage · {human(batch.sourceKind)}
            </summary>
            <p className="payment-help">
              Inquiry configuration: {batch.mode} · Observed {batch.observedAt}
              <br />
              Coverage: {batch.coverage} Discovery metadata only; payment, host,
              status and history evidence has not been acquired for this case.
            </p>
          </details>
          {batch.matchStatus === "AMBIGUOUS" && (
            <div className="notice payment-mock" role="status">
              <TriangleAlert size={18} />
              <p>
                Multiple transactions match this reference. Choose the correct
                transaction using its reference, bank, branch and source
                details. Host subsequences are grouped within each transaction.
              </p>
            </div>
          )}
          {batch.matchStatus === "EXACT_MATCH" && (
            <p className="payment-help">
              Exact reference match. Review the transaction and its grouped host
              subsequences before opening a case.
            </p>
          )}
          {batch.truncated && (
            <div className="notice payment-mock">
              <TriangleAlert size={18} />
              <p>
                The response reached its limit. These results are incomplete;
                narrow the inquiry scope.
              </p>
            </div>
          )}
          {!!batch.warnings.length && (
            <ul className="payment-warnings">
              {batch.warnings.map((warning, index) => (
                <li key={index}>{warning}</li>
              ))}
            </ul>
          )}
          {!batch.items.length ? (
            <div className="empty">
              <h3>No transactions in this result</h3>
              <p>
                {mode === "reference"
                  ? "Check the exact reference or UTR and the selected bank and branch."
                  : mode === "api"
                    ? "Check the bank, branch, inquiry date and record limit."
                    : "Check the workbook records and the selected bank and branch."}{" "}
                No case was opened.
              </p>
            </div>
          ) : (
            <>
              <div className="table-scroll">
                <table className="case-table payment-records discovery-results-table">
                  <thead>
                    <tr>
                      <th>Select</th>
                      <th>Reference / UTR</th>
                      <th>Bank / branch</th>
                      <th>Host subsequences</th>
                      <th>Initiated at</th>
                      <th>Source amount</th>
                      <th>Source</th>
                    </tr>
                  </thead>
                  <tbody>
                    {batch.items.map((candidate) => (
                      <tr
                        key={candidate.candidateId}
                        className={
                          selectedId === candidate.candidateId
                            ? "discovery-selected-row"
                            : ""
                        }
                      >
                        <td
                          data-label="Select"
                          className="discovery-select-cell"
                        >
                          <input
                            type="radio"
                            name="payment-candidate"
                            aria-label={`Select ${candidate.reference} branch ${candidate.orgBranch} ${classificationLabel(candidate.dataClassification)}`}
                            value={candidate.candidateId}
                            checked={selectedId === candidate.candidateId}
                            disabled={busy !== null || !canWrite}
                            onChange={() => {
                              setSelectedId(candidate.candidateId);
                              setOpenError(null);
                              openAttempt.current = null;
                            }}
                          />
                        </td>
                        <td
                          data-label="Reference / UTR"
                          className="discovery-result-identity"
                        >
                          <strong className="payment-source-value">
                            {candidate.reference}
                          </strong>
                          <small>UTR: {candidate.utr || "Not supplied"}</small>
                          {candidate.existingCaseId && (
                            <small>
                              Saved case{" "}
                              {candidate.existingCaseNumber ||
                                candidate.existingCaseId}
                            </small>
                          )}
                        </td>
                        <td data-label="Bank / branch">
                          {candidate.orgBank}
                          <small>Branch {candidate.orgBranch}</small>
                        </td>
                        <td data-label="Host subsequences">
                          {candidate.hostSubsequences.length}
                          <small>
                            {hostSubsequenceText(candidate.hostSubsequences)}
                          </small>
                        </td>
                        <td className="date-cell" data-label="Initiated at">
                          {candidate.initiatedAt}
                        </td>
                        <td className="amount" data-label="Source amount">
                          <Amount item={candidate} />
                        </td>
                        <td data-label="Source">
                          <Provenance item={candidate} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {canWrite && (
                <form className="payment-open-case" onSubmit={open}>
                  <label>
                    Investigation reason
                    <textarea
                      required
                      minLength={1}
                      maxLength={1000}
                      rows={3}
                      placeholder="Describe the issue to investigate, without unnecessary personal data."
                      value={reason}
                      disabled={busy !== null}
                      onChange={(event) => {
                        setReason(event.target.value);
                        setOpenError(null);
                      }}
                    />
                  </label>
                  <p className="payment-help">
                    {selected
                      ? `Selected reference: ${selected.reference}.`
                      : "Select one transaction above."}{" "}
                    Creating a case saves this discovery record. Existing cases
                    are resumed without overwriting their original reason.
                  </p>
                  <button
                    className="primary"
                    disabled={!selected || !reason.trim() || busy !== null}
                  >
                    {busy === "open" ? (
                      <LoaderCircle className="spin" size={17} />
                    ) : (
                      <ArrowRight size={17} />
                    )}
                    Open or resume case
                  </button>
                  {busy === "open" && (
                    <p role="status" className="payment-help">
                      Saving or resuming the selected case…
                    </p>
                  )}
                  {openError && (
                    <>
                      <Failure error={openError} />
                      <p className="payment-help">
                        Retry with the same selection and reason to safely
                        resume this request.
                      </p>
                    </>
                  )}
                </form>
              )}
            </>
          )}
        </div>
      )}
    </section>
  );
}

export function PaymentCaseDetail({
  caseId,
  onBack,
  canWrite = false,
  user,
  reportOpen = false,
}: {
  caseId: string;
  onBack: (event: MouseEvent<HTMLAnchorElement>) => void;
  canWrite?: boolean;
  user?: User;
  reportOpen?: boolean;
}) {
  const [revision, setRevision] = useState(0);
  const [evidenceRevision, setEvidenceRevision] = useState(0);
  const [showReport, setShowReport] = useState(reportOpen);
  const [lifecycleOverride, setLifecycleOverride] =
    useState<CaseLifecycleState | null>(null);
  useEffect(() => {
    setLifecycleOverride(null);
  }, [caseId]);
  useEffect(() => setShowReport(reportOpen), [reportOpen]);
  const record = useRead<PaymentCase>(
    `/payment-cases/${encodeURIComponent(caseId)}`,
    revision,
    true,
  );
  const item = record.data;
  useEffect(() => {
    if (item?.lifecycleState) setLifecycleOverride(null);
  }, [item]);
  const archived = (lifecycleOverride ?? item?.lifecycleState) === "ARCHIVED";
  useEffect(() => {
    const changed = (event: Event) => {
      const detail = (event as CustomEvent<{ caseId: string; state: string }>)
        .detail;
      if (detail?.caseId !== item?.id) return;
      if (detail.state === "DELETED") {
        navigateTo("/cases");
        return;
      }
      setLifecycleOverride(detail.state as CaseLifecycleState);
      setRevision((v) => v + 1);
      setEvidenceRevision((v) => v + 1);
    };
    window.addEventListener(CASE_LIFECYCLE_CHANGED, changed);
    return () => window.removeEventListener(CASE_LIFECYCLE_CHANGED, changed);
  }, [item?.id]);
  const reportPath = item
    ? paymentCasePath(item)
    : paymentCasePath({ id: caseId });
  useEffect(() => {
    if (
      item?.caseNumber &&
      caseId !== item.caseNumber &&
      window.location.pathname ===
        `/payment-cases/${encodeURIComponent(caseId)}`
    ) {
      replaceDestination(
        `${paymentCasePath(item)}${reportOpen ? "?report=1" : ""}`,
      );
    }
  }, [item?.caseNumber, caseId, reportOpen]);
  return (
    <div className="payment-discovery">
      <a className="back-link" href="/cases" onClick={onBack}>
        <ArrowLeft size={15} />
        Back to payment cases
      </a>
      {record.error && (
        <Failure
          error={record.error}
          retry={() => setRevision((value) => value + 1)}
        />
      )}
      {record.loading && !item && (
        <Pending>Loading saved payment case…</Pending>
      )}
      {item && (
        <>
          <div className="page-heading">
            <div>
              <div className="eyebrow">
                PRIVATE PAYMENT CASE · {paymentCaseNumber(item)}
              </div>
              <h1>Payment investigation</h1>
              <p>
                The selected payment, investigation reason and attached
                evidence.
              </p>
            </div>
            <div className="heading-actions">
              <span className="payment-source-label">
                {archived ? "Archived" : human(item.status)} ·{" "}
                {human(item.priority)}
              </span>
              <button
                className="primary"
                aria-expanded={showReport}
                onClick={() => {
                  setShowReport(true);
                  navigateTo(`${reportPath}?report=1`);
                }}
              >
                <Download size={16} aria-hidden="true" /> Export PDF
              </button>
            </div>
          </div>
          <CasePageNavigation />
          {showReport && (
            <CaseReport
              caseId={item.id}
              onClose={() => {
                setShowReport(false);
                navigateTo(reportPath);
              }}
            />
          )}
          {archived && (
            <div className="notice neutral" role="status">
              This case is archived. You can read its evidence, saved answers
              and reports. Restore it in Case management → Case lifecycle to
              continue investigation.
            </div>
          )}
          <section className="panel payment-case-detail">
            <h2>Selected payment</h2>
            <dl className="payment-detail-fields">
              {[
                ["Payment reference", item.reference],
                ["UTR", item.utr || "Not supplied"],
                ["Organization bank", item.orgBank],
                ["Organization branch", item.orgBranch],
                [
                  "Source amount",
                  `${item.amount} ${item.currency || "(currency not supplied)"}`,
                ],
                ["Initiated at (as supplied)", item.initiatedAt],
                [
                  "Host subsequences",
                  hostSubsequenceText(item.hostSubsequences),
                ],
                [
                  "Source",
                  `${human(item.sourceKind)} · ${classificationLabel(item.dataClassification)}`,
                ],
                ["Created", item.createdAt],
                ["Updated", item.updatedAt],
                ["Created by", item.createdBy],
                ["Case owner", item.owner?.name ?? "Unassigned"],
                ["Evidence availability", human(item.evidenceStatus)],
              ].map(([label, value]) => (
                <div key={label}>
                  <dt>{label}</dt>
                  <dd>{value}</dd>
                </div>
              ))}
            </dl>
            <h3>Investigation reason</h3>
            <p className="payment-stored-reason">{item.reason}</p>
            {record.loading && (
              <p role="status">Refreshing saved payment details…</p>
            )}
          </section>
          <CaseManagement
            caseId={item.id}
            createdBy={item.createdBy}
            user={user}
            evidenceRevision={evidenceRevision}
            onChanged={() => setRevision((v) => v + 1)}
            lifecycleState={archived ? "ARCHIVED" : "ACTIVE"}
            onLifecycleChanged={setLifecycleOverride}
          />
          <CaseEvidence
            caseId={item.id}
            canWrite={canWrite && !archived}
            archived={archived}
            onSaved={() => {
              setRevision((value) => value + 1);
              setEvidenceRevision((value) => value + 1);
            }}
          />
          <CaseInvestigation
            caseId={item.id}
            caseNumber={item.caseNumber}
            canWrite={canWrite && !archived}
            archived={archived}
            evidenceRevision={evidenceRevision}
          />
        </>
      )}
    </div>
  );
}
