import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type ReactNode,
} from "react";
import {
  Activity,
  ArrowDownToLine,
  ArrowLeft,
  ArrowRight,
  BookOpen,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  CircleHelp,
  Clock3,
  Database,
  FileCheck2,
  FileText,
  Fingerprint,
  FlaskConical,
  FolderKanban,
  Layers3,
  LoaderCircle,
  LogOut,
  MoreHorizontal,
  Play,
  RefreshCw,
  Search,
  ShieldCheck,
  ShieldQuestion,
  SlidersHorizontal,
  Sparkles,
  TerminalSquare,
  TriangleAlert,
  Upload,
  X,
} from "lucide-react";
import {
  api,
  ApiError,
  canReview,
  date,
  human,
  money,
  setCsrfToken,
} from "./api";
import type {
  AuditEntry,
  CaseDetail,
  CaseSummary,
  Dashboard,
  Investigation,
  Session,
  SystemInfo,
  User,
} from "./types";
import { ObpmEvidence, ObpmImport } from "./Obpm";
import { CaseKnowledgePage } from "./CaseKnowledgePage";
import { EvidenceWorkspace } from "./EvidenceWorkspace";
import { PaymentCaseDetail, PaymentCasesPage } from "./PaymentDiscovery";
import {
  canonicalizeWorkspace,
  currentRoute,
  navigateLink,
  navigateQueue,
  navigateTo,
  workspaceDestination,
} from "./routing";
function useResource<T>(path: string, revision = 0) {
  const [state, setState] = useState<{
    data: T | null;
    error: Error | null;
    loading: boolean;
  }>({ data: null, error: null, loading: true });
  useEffect(() => {
    const controller = new AbortController();
    setState({ data: null, error: null, loading: true });
    api<T>(path, { signal: controller.signal })
      .then((data) => {
        if (!controller.signal.aborted)
          setState({ data, error: null, loading: false });
      })
      .catch((error) => {
        if (!controller.signal.aborted)
          setState({ data: null, error, loading: false });
      });
    return () => controller.abort();
  }, [path, revision]);
  return state;
}
export function ErrorNotice({
  error,
  retry,
}: {
  error: Error;
  retry?: () => void;
}) {
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
function Loading({ label = "Loading workspace…" }: { label?: string }) {
  return (
    <div className="loading" role="status">
      <LoaderCircle className="spin" size={21} />
      <span>{label}</span>
    </div>
  );
}
function Empty({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="empty">
      <FolderKanban size={29} />
      <h3>{title}</h3>
      <p>{children}</p>
    </div>
  );
}
function Badge({
  value,
  className = "",
  label,
}: {
  value: string;
  className?: string;
  label?: string;
}) {
  return (
    <span
      className={`badge ${value.toLowerCase().replaceAll(" ", "-")} ${className}`}
    >
      <i />
      {label || human(value)}
    </span>
  );
}
function ModeBadge({
  mode,
  synthesisScope,
}: {
  mode: string;
  synthesisScope?: string;
}) {
  const label =
    mode !== "ollama"
      ? "Replay · no language model"
      : synthesisScope === "fact-selection"
        ? "AI-selected evidence"
        : synthesisScope === "finding-only"
          ? "AI evidence explanation"
          : synthesisScope === "skipped-insufficient-evidence"
            ? "Ollama tool planning"
            : "Ollama inference";
  return (
    <span className={`mode-badge ${mode}`}>
      {mode === "ollama" ? <Sparkles size={13} /> : <FlaskConical size={13} />}
      {label}
    </span>
  );
}
function SectionTitle({
  icon,
  title,
  aside,
}: {
  icon?: ReactNode;
  title: string;
  aside?: ReactNode;
}) {
  return (
    <div className="section-title">
      <h2>
        {icon}
        {title}
      </h2>
      {aside}
    </div>
  );
}

export function Login({ onLogin }: { onLogin: (session: Session) => void }) {
  const [username, setUsername] = useState("analyst");
  const [password, setPassword] = useState("demo-pass-local");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const session = await api<Session>("/auth/login", {
        method: "POST",
        body: JSON.stringify({ username, password }),
      });
      setCsrfToken(session.csrfToken);
      onLogin(session);
    } catch (e) {
      setError(e as Error);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="login-layout">
      <section className="login-story">
        <div className="brand">
          <div className="brand-mark">
            <Layers3 size={24} />
          </div>
          <span>
            payment<span className="brand-secondary">operations</span>
          </span>
        </div>
        <div className="login-copy">
          <div className="eyebrow light">THE INVESTIGATOR WORKSPACE</div>
          <h1>
            Every payment
            <br />
            has a story.
            <br />
            <span>Follow the evidence.</span>
          </h1>
          <p>
            A focused workspace to investigate payment exceptions, trace the
            facts, and make a reviewed decision.
          </p>
          <div className="login-principles">
            <div>
              <Fingerprint size={20} />
              <span>Traceable evidence</span>
            </div>
            <div>
              <BookOpen size={20} />
              <span>Cited operating guidance</span>
            </div>
            <div>
              <ShieldCheck size={20} />
              <span>Independent review</span>
            </div>
          </div>
        </div>
        <div className="login-foot">
          <span className="live-dot" /> Local investigation workspace · No
          payment execution
        </div>
      </section>
      <main className="login-main">
        <div className="login-box">
          <span className="small-icon">
            <ShieldCheck size={24} />
          </span>
          <div className="eyebrow">WELCOME TO THE WORKBENCH</div>
          <h2>Sign in to investigate</h2>
          <p>Choose your workspace identity to continue your investigation.</p>
          <form onSubmit={submit}>
            <label htmlFor="username">Workspace identity</label>
            <div className="select-wrap">
              <select
                id="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
              >
                <option value="analyst">Analyst · Northstar</option>
                <option value="reviewer">Reviewer · Northstar</option>
                <option value="viewer">Viewer · Northstar</option>
                <option value="admin">Administrator · Northstar</option>
                <option value="other">Analyst · Silverline</option>
              </select>
              <ChevronDown size={16} />
            </div>
            <label htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
            {error && <ErrorNotice error={error} />}
            <button className="primary full" disabled={busy}>
              {busy ? <LoaderCircle className="spin" size={17} /> : null}
              {busy ? "Signing in…" : "Open workspace"}
              {!busy && <ArrowRight size={17} />}
            </button>
          </form>
          <div className="login-note">
            <CircleHelp size={17} />
            <span>
              Local demo password: <code>demo-pass-local</code>
              <br />
              Use the configured password if your setup overrides it.
            </span>
          </div>
          <div className="role-note">
            <strong>Analyst investigates. Reviewer decides.</strong>
            <span>
              Viewer access is read-only. Silverline has a separate tenant
              workspace. Administrators manage case archival and permanent
              deletion.
            </span>
          </div>
        </div>
      </main>
    </div>
  );
}

export default function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [booting, setBooting] = useState(true);
  const [bootError, setBootError] = useState<Error | null>(null);
  const [route, setRoute] = useState(currentRoute);
  const [revision, setRevision] = useState(0);
  const [logoutError, setLogoutError] = useState<Error | null>(null);
  const returnDestination = useRef<string | null>(null);
  const refresh = useCallback(() => setRevision((v) => v + 1), []);
  const privatePayments =
    route.page === "payment-cases" || (route.page === "cases" && !route.caseId);
  useEffect(() => {
    let active = true;
    setBootError(null);
    setBooting(true);
    api<Session>("/auth/me")
      .then((value) => {
        if (active) {
          setCsrfToken(value.csrfToken);
          setSession(value);
        }
      })
      .catch((e) => {
        if (active && (!(e instanceof ApiError) || e.status !== 401))
          setBootError(e);
      })
      .finally(() => {
        if (active) setBooting(false);
      });
    return () => {
      active = false;
    };
  }, [revision]);
  useEffect(() => {
    if (booting || bootError) return;
    const onRoute = () => {
      if (!session) {
        // Show sign-in at root, retaining an explicitly opened page for login.
        if (window.location.hash || window.location.pathname !== "/") {
          returnDestination.current = workspaceDestination();
        }
        window.history.replaceState(null, "", "/");
      } else {
        canonicalizeWorkspace();
      }
      setRoute(currentRoute());
      window.scrollTo(0, 0);
    };
    onRoute();
    window.addEventListener("hashchange", onRoute);
    window.addEventListener("popstate", onRoute);
    return () => {
      window.removeEventListener("hashchange", onRoute);
      window.removeEventListener("popstate", onRoute);
    };
  }, [session, booting, bootError]);
  useEffect(() => {
    const expired = () => {
      setCsrfToken(null);
      setSession(null);
    };
    window.addEventListener("session-expired", expired);
    return () => {
      window.removeEventListener("session-expired", expired);
    };
  }, []);
  function login(value: Session) {
    window.history.replaceState(
      null,
      "",
      returnDestination.current ?? "/cases",
    );
    returnDestination.current = null;
    setRoute(currentRoute());
    setSession(value);
  }
  async function logout() {
    try {
      await api("/auth/logout", { method: "POST" });
      returnDestination.current = null;
      window.history.replaceState(null, "", "/");
      setRoute(currentRoute());
      setCsrfToken(null);
      setSession(null);
      setLogoutError(null);
    } catch (e) {
      setLogoutError(e as Error);
    }
  }
  if (booting)
    return (
      <div className="boot-screen">
        <Layers3 size={34} />
        <Loading label="Connecting to your workspace…" />
      </div>
    );
  if (bootError)
    return (
      <div className="boot-screen">
        <h1>Workspace connection</h1>
        <ErrorNotice error={bootError} retry={refresh} />
        <p>Start the API on port 8088 and the web app on port 5178.</p>
      </div>
    );
  if (!session) return <Login onLogin={login} />;
  return (
    <div
      className={`app-shell${privatePayments ? " payment-route" : ""}`}
      key={`${session.user.id}:${session.user.tenantId}`}
    >
      <a
        className="skip-link"
        href="#main-content"
        onClick={(event) => {
          event.preventDefault();
          document.getElementById("main-content")?.focus();
          document.getElementById("main-content")?.scrollIntoView();
        }}
      >
        Skip to main content
      </a>
      <aside className="sidebar">
        <a className="brand" href="/cases" onClick={navigateQueue}>
          <div className="brand-mark">
            <Layers3 size={23} />
          </div>
          <span>
            payment<span className="brand-secondary">operations</span>
          </span>
        </a>
        <div className="workspace-label">WORKSPACE</div>
        <div className="workspace-switch">
          <div className="workspace-avatar">
            {session.user.tenantId.slice(0, 1).toUpperCase()}
          </div>
          <div>
            <strong>{human(session.user.tenantId)}</strong>
            <small>Operations team</small>
          </div>
        </div>
        <div className="nav-label">WORKBENCH</div>
        <nav aria-label="Primary navigation">
          <a
            className={
              route.page === "cases" || route.page === "payment-cases"
                ? "active"
                : ""
            }
            href="/cases"
            aria-current={
              route.page === "cases" || route.page === "payment-cases"
                ? "page"
                : undefined
            }
            onClick={navigateQueue}
          >
            <FolderKanban size={19} />
            Case queue
            <span className="nav-current" />
          </a>
          <a
            className={route.page === "evidences" ? "active" : ""}
            href="/evidences"
            aria-current={route.page === "evidences" ? "page" : undefined}
            onClick={navigateLink}
          >
            <Database size={19} />
            Evidence library
          </a>
          <a
            className={route.page === "knowledge" ? "active" : ""}
            href="/knowledge"
            aria-current={route.page === "knowledge" ? "page" : undefined}
            onClick={navigateLink}
          >
            <BookOpen size={19} />
            Knowledge library
          </a>
          <a
            className={route.page === "system" ? "active" : ""}
            href="/system"
            aria-current={route.page === "system" ? "page" : undefined}
            onClick={navigateLink}
          >
            <Activity size={19} />
            System & health
          </a>
        </nav>
        <div className="sidebar-bottom">
          <div className="environment-card">
            <span className="live-dot" />
            <strong>
              {privatePayments
                ? "Private payment workspace"
                : route.page === "evidences"
                  ? route.view === "exports"
                    ? "Export review"
                    : route.view === "questions"
                      ? "Case questions"
                      : "Case evidence workspace"
                  : route.page === "system"
                    ? "Local services"
                    : route.page === "knowledge"
                      ? "Case investigation guidance"
                      : "Synthetic environment"}
            </strong>
            <p>
              {privatePayments
                ? "Saved discovery records."
                : route.page === "evidences"
                  ? route.view === "exports"
                    ? "Staged evidence snapshots."
                    : route.view === "questions"
                      ? "Questions and saved evidence versions."
                      : "Saved case evidence and versions."
                  : route.page === "system"
                    ? "Service status and reference fixtures."
                    : route.page === "knowledge"
                      ? "Versioned sources and embedding coverage."
                      : "Original demo records."}
              <br />
              No real payment execution.
            </p>
          </div>
          <div className="profile">
            <div className="avatar">
              {session.user.name.slice(0, 1).toUpperCase()}
            </div>
            <div>
              <strong>{session.user.name}</strong>
              <small>{human(session.user.role)}</small>
            </div>
            <button title="Sign out" aria-label="Sign out" onClick={logout}>
              <LogOut size={17} />
            </button>
          </div>
        </div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <div className="breadcrumb">
            <span>Workspace</span>
            <ChevronRight size={13} />
            <strong>
              {route.page === "knowledge"
                ? "Knowledge library"
                : route.page === "system"
                  ? "System & health"
                  : route.page === "evidences"
                    ? route.view === "exports"
                      ? "Export demo"
                      : route.view === "questions"
                        ? "Evidence Q&A"
                        : "Evidence library"
                    : "Case queue"}
            </strong>
            {route.caseId && (
              <>
                <ChevronRight size={13} />
                <span>{route.caseId}</span>
              </>
            )}
          </div>
          <div className="topbar-right">
            <span className="environment-pill">
              <i />
              {privatePayments
                ? "PRIVATE CASES"
                : route.page === "evidences"
                  ? route.view === "exports"
                    ? "IMPORTED EVIDENCE"
                    : "CASE EVIDENCE"
                  : route.page === "system"
                    ? "SERVICE STATUS"
                    : route.page === "knowledge"
                      ? "CASE GUIDANCE"
                      : "DEMO DATA"}
            </span>
            <span className="topbar-divider" />
            <span className="top-role">
              <ShieldCheck size={15} />
              {human(session.user.role)}
            </span>
          </div>
        </header>
        <main className="main-content" id="main-content" tabIndex={-1}>
          {logoutError && <ErrorNotice error={logoutError} />}
          <div className="mobile-brand">
            <Layers3 size={19} /> Payment Operations
          </div>
          {route.caseId && route.page === "payment-cases" ? (
            <PaymentCaseDetail
              key={route.caseId}
              caseId={route.caseId}
              onBack={navigateQueue}
              user={session.user}
              reportOpen={route.reportOpen}
              canWrite={["ANALYST", "REVIEWER"].includes(
                session.user.role.toUpperCase(),
              )}
            />
          ) : route.caseId && route.page === "cases" ? (
            <CaseWorkspace
              key={route.caseId}
              caseId={route.caseId}
              user={session.user}
            />
          ) : route.page === "knowledge" ? (
            <CaseKnowledgePage user={session.user} />
          ) : route.page === "system" ? (
            <SystemPage />
          ) : route.page === "evidences" ? (
            <EvidenceWorkspace
              user={session.user}
              view={route.view}
              caseId={route.caseId}
              evidenceId={route.evidenceId}
            />
          ) : (
            <PaymentCasesPage
              user={session.user}
              onOpen={(id) => {
                navigateTo(`/payment-cases/${encodeURIComponent(id)}`);
              }}
            />
          )}
        </main>
        <footer className="workspace-footer">
          <span>PAYMENT OPERATIONS INVESTIGATOR</span>
          <span>Evidence first. Every decision recorded.</span>
        </footer>
      </div>
    </div>
  );
}

// Retained for isolated legacy fixture checks; the workspace queue uses saved payments.
export function DemoCasesPage({ user }: { user: User }) {
  const [search, setSearch] = useState("");
  const [debounced, setDebounced] = useState("");
  const [status, setStatus] = useState("");
  const [priority, setPriority] = useState("");
  const [revision, setRevision] = useState(0);
  const [showImport, setShowImport] = useState(false);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(search), 200);
    return () => clearTimeout(timer);
  }, [search]);
  const dashboard = useResource<Dashboard>("/dashboard", revision);
  const query = new URLSearchParams({ search: debounced, status, priority });
  const cases = useResource<{ items: CaseSummary[]; total: number }>(
    `/cases?${query}`,
    revision,
  );
  const counts = dashboard.data;
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">IMPORTED CASE OVERVIEW</div>
          <h1>
            Case queue <span className="heading-dot" />
          </h1>
          <p>
            Investigate imported exceptions. These counts describe app cases,
            not bank throughput or payment success rates.
          </p>
        </div>
        <div className="heading-actions queue-actions">
          <button
            className="secondary"
            aria-expanded={showImport}
            onClick={() => setShowImport((value) => !value)}
          >
            <Upload size={16} />
            Import synthetic NEFT evidence
          </button>
          <button
            className="secondary"
            onClick={() => setRevision((v) => v + 1)}
          >
            <RefreshCw size={16} />
            Refresh
          </button>
        </div>
      </div>
      {showImport && (
        <ObpmImport
          user={user}
          onImported={() => setRevision((value) => value + 1)}
          onClose={() => setShowImport(false)}
        />
      )}
      {dashboard.error && <ErrorNotice error={dashboard.error} />}
      <div className="kpi-grid">
        {[
          {
            label: "Unresolved cases",
            value: counts?.openCases,
            icon: <FolderKanban />,
            hint: "Cases requiring follow-up",
            kind: "blue",
          },
          {
            label: "High priority",
            value: counts?.highPriorityCases,
            icon: <TriangleAlert />,
            hint: "Needs timely attention",
            kind: "amber",
          },
          {
            label: "Awaiting review",
            value: counts?.awaitingReview,
            icon: <ShieldQuestion />,
            hint: "Independent decision required",
            kind: "purple",
          },
          {
            label: "Resolved",
            value: counts?.resolvedCases,
            icon: <CheckCircle2 />,
            hint: "Review completed",
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
      <section className="panel queue-panel">
        <div className="queue-tabs">
          <button
            className={!status ? "selected" : ""}
            onClick={() => setStatus("")}
          >
            All cases{cases.data && !status && <span>{cases.data.total}</span>}
          </button>
          <button
            className={status === "OPEN" ? "selected" : ""}
            onClick={() => setStatus("OPEN")}
          >
            Open
          </button>
          <button
            className={status === "AWAITING_REVIEW" ? "selected" : ""}
            onClick={() => setStatus("AWAITING_REVIEW")}
          >
            Awaiting review
          </button>
          <button
            className={status === "RESOLVED" ? "selected" : ""}
            onClick={() => setStatus("RESOLVED")}
          >
            Resolved
          </button>
        </div>
        <div className="table-toolbar">
          <div className="search-field">
            <Search size={17} />
            <input
              aria-label="Search cases"
              placeholder="Search cases, payments, or merchants…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
            {search && (
              <button aria-label="Clear search" onClick={() => setSearch("")}>
                <X size={14} />
              </button>
            )}
          </div>
          <div className="filter-select">
            <SlidersHorizontal size={15} />
            <select
              aria-label="Filter priority"
              value={priority}
              onChange={(e) => setPriority(e.target.value)}
            >
              <option value="">All priorities</option>
              <option value="HIGH">High priority</option>
              <option value="MEDIUM">Medium priority</option>
              <option value="LOW">Low priority</option>
            </select>
          </div>
          <select
            aria-label="Filter status"
            className="status-select"
            value={status}
            onChange={(e) => setStatus(e.target.value)}
          >
            <option value="">All statuses</option>
            {[
              "OPEN",
              "AWAITING_REVIEW",
              "RESOLVED",
              "ESCALATED",
              "NEEDS_EVIDENCE",
            ].map((v) => (
              <option key={v} value={v}>
                {human(v)}
              </option>
            ))}
          </select>
        </div>
        {cases.error ? (
          <ErrorNotice
            error={cases.error}
            retry={() => setRevision((v) => v + 1)}
          />
        ) : cases.loading ? (
          <Loading label="Loading cases…" />
        ) : !cases.data?.items.length ? (
          <Empty title="No cases in this view">
            Try another search or remove a filter to see more cases.
          </Empty>
        ) : (
          <div className="table-scroll">
            <table className="case-table">
              <thead>
                <tr>
                  <th>Case / exception</th>
                  <th>Context / rail</th>
                  <th>Amount</th>
                  <th>Priority</th>
                  <th>Status</th>
                  <th>Updated</th>
                  <th>
                    <span className="sr-only">Open case</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {cases.data.items.map((item) => (
                  <tr key={item.id}>
                    <td>
                      <a
                        className="case-link"
                        href={`/cases/${encodeURIComponent(item.id)}`}
                        onClick={navigateLink}
                      >
                        <span className="case-id">{item.id}</span>
                        <strong>{item.title}</strong>
                      </a>
                      <span className="case-payment">{item.paymentId}</span>
                    </td>
                    <td>
                      <span className="merchant-name">
                        {item.domain === "OBPM_NEFT"
                          ? "Banking evidence"
                          : item.merchant}
                      </span>
                      <small>
                        {item.rail === "NEFT"
                          ? "NEFT · Outbound"
                          : human(item.rail)}
                      </small>
                    </td>
                    <td className="amount">
                      {money(item.amountMinor, item.currency)}
                      <small>{item.currency}</small>
                    </td>
                    <td>
                      <Badge value={item.priority} />
                    </td>
                    <td>
                      <Badge value={item.status} />
                    </td>
                    <td className="date-cell">{date(item.updatedAt)}</td>
                    <td>
                      <a
                        className="row-open"
                        aria-label={`Open ${item.id}`}
                        href={`/cases/${encodeURIComponent(item.id)}`}
                        onClick={navigateLink}
                      >
                        <ArrowRight size={17} />
                      </a>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="table-footer">
          <span>
            {cases.data
              ? `${cases.data.items.length} of ${cases.data.total} cases`
              : "Case records"}
            {(search || status || priority) && " · filtered"}
          </span>
          <span>
            <Fingerprint size={13} />
            Tenant-scoped records
          </span>
        </div>
      </section>
      <div className="context-note">
        <ShieldCheck size={16} />
        <p>
          Investigations assemble evidence and propose a case action. A separate
          reviewer approves or rejects the proposal.
        </p>
      </div>
    </>
  );
}

function CaseWorkspace({ caseId, user }: { caseId: string; user: User }) {
  const [revision, setRevision] = useState(0);
  const [tab, setTab] = useState("timeline");
  const [error, setError] = useState<Error | null>(null);
  const [exporting, setExporting] = useState(false);
  const [focusEvidence, setFocusEvidence] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const caseData = useResource<CaseDetail>(
    `/cases/${encodeURIComponent(caseId)}`,
    revision,
  );
  const investigations = useResource<{ items: Investigation[] }>(
    `/cases/${encodeURIComponent(caseId)}/investigations`,
    revision,
  );
  const audit = useResource<{ items: AuditEntry[] }>(
    `/cases/${encodeURIComponent(caseId)}/audit`,
    revision,
  );
  useEffect(() => {
    setSelectedId(null);
    setTab("timeline");
    setFocusEvidence(null);
    setError(null);
  }, [caseId]);
  const refresh = () => setRevision((v) => v + 1);
  const current = caseData.data;
  const banking = current?.domain === "OBPM_NEFT";
  const sorted = [...(investigations.data?.items ?? [])].sort((a, b) =>
    b.createdAt.localeCompare(a.createdAt),
  );
  const selected = sorted.find((v) => v.id === selectedId) || sorted[0];
  async function exportCase() {
    setExporting(true);
    setError(null);
    try {
      const data = await api(`/cases/${encodeURIComponent(caseId)}/export`);
      const url = URL.createObjectURL(
        new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }),
      );
      const link = document.createElement("a");
      link.href = url;
      link.download = `${caseId}-evidence-export.json`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) {
      setError(e as Error);
    } finally {
      setExporting(false);
    }
  }
  if (caseData.error)
    return (
      <>
        <a className="back-link" href="/cases" onClick={navigateQueue}>
          <ArrowLeft size={15} />
          Back to queue
        </a>
        <ErrorNotice error={caseData.error} retry={refresh} />
      </>
    );
  if (!current) return <Loading label="Loading case evidence…" />;
  return (
    <>
      <a className="back-link" href="/cases" onClick={navigateQueue}>
        <ArrowLeft size={15} />
        Back to case queue
      </a>
      <div className="page-heading case-heading">
        <div>
          <div className="case-heading-label">
            <span className="case-id">{current.id}</span>
            <Badge value={current.priority} />
            <Badge value={current.status} />
          </div>
          <h1>{current.title}</h1>
          <p>{current.description}</p>
        </div>
        <div className="heading-actions">
          <button
            className="icon-button secondary"
            aria-label="Refresh case"
            onClick={refresh}
          >
            <RefreshCw size={16} />
          </button>
          <button
            className="secondary"
            onClick={exportCase}
            disabled={exporting}
          >
            {exporting ? (
              <LoaderCircle className="spin" size={16} />
            ) : (
              <ArrowDownToLine size={16} />
            )}
            Export case
          </button>
        </div>
      </div>
      {error && <ErrorNotice error={error} />}
      <div className="case-facts">
        <div>
          <span>Payment reference</span>
          <strong className="mono">{current.paymentId}</strong>
        </div>
        <div>
          <span>{banking ? "Native payment status" : "Merchant"}</span>
          <strong>
            {banking
              ? (current.obpm?.payment.nativeTransactionStatus ??
                "Unknown · not supplied")
              : current.merchant}
          </strong>
        </div>
        <div>
          <span>Payment amount</span>
          <strong>{money(current.amountMinor, current.currency)}</strong>
        </div>
        <div>
          <span>Payment rail</span>
          <strong>{banking ? "NEFT · Outbound" : human(current.rail)}</strong>
        </div>
        <div>
          <span>{banking ? "Payment created (source)" : "Case opened"}</span>
          <strong>{date(current.createdAt)}</strong>
        </div>
      </div>
      <div className="case-layout">
        <section className="panel evidence-panel">
          <div className="detail-tabs">
            {[
              {
                id: "timeline",
                text: banking ? "Bank evidence" : "Timeline",
                count: banking
                  ? (current.obpm?.queueRecords.length ?? 0) +
                    (current.obpm?.externalRequestAttempts.length ?? 0)
                  : current.events.length,
              },
              {
                id: "evidence",
                text: banking ? "Source coverage" : "Evidence",
                count: banking
                  ? Object.keys(current.obpm?.sourceCoverage ?? {}).length
                  : current.ledgerEntries.length + current.webhooks.length + 1,
              },
              { id: "history", text: "Investigations", count: sorted.length },
              {
                id: "audit",
                text: "Audit trail",
                count: audit.data?.items.length,
              },
            ].map((t) => (
              <button
                key={t.id}
                onClick={() => {
                  setTab(t.id);
                  setFocusEvidence(null);
                }}
                className={tab === t.id ? "selected" : ""}
              >
                {t.text}
                <span>{t.count ?? "—"}</span>
              </button>
            ))}
          </div>
          <div className="evidence-body">
            {tab === "timeline" && banking && (
              <ObpmEvidence
                item={current}
                focusedId={focusEvidence}
                view="records"
                revision={revision}
              />
            )}
            {tab === "timeline" && !banking && (
              <>
                <SectionTitle
                  title="Payment timeline"
                  icon={<Clock3 size={17} />}
                  aside={
                    <span className="muted tiny">
                      Local time · ordered by occurrence
                    </span>
                  }
                />
                <div className="timeline">
                  {[...current.events]
                    .sort((a, b) => a.occurredAt.localeCompare(b.occurredAt))
                    .map((event) => (
                      <div
                        className="timeline-event"
                        key={event.id}
                        id={event.id}
                      >
                        <span
                          className={`timeline-node ${event.status.toLowerCase()}`}
                        >
                          {["FAILED", "ERROR", "TIMEOUT"].includes(
                            event.status,
                          ) ? (
                            <TriangleAlert size={13} />
                          ) : (
                            <Check size={13} />
                          )}
                        </span>
                        <div className="timeline-event-top">
                          <strong>{human(event.type)}</strong>
                          <time>{date(event.occurredAt)}</time>
                        </div>
                        <p>{event.summary}</p>
                        <div className="event-meta">
                          <span>{event.source}</span>
                          <span className="mono">{event.id}</span>
                          {event.correlationId && (
                            <span className="mono">{event.correlationId}</span>
                          )}
                        </div>
                        {event.attributes &&
                          Object.keys(event.attributes).length > 0 && (
                            <details className="event-attributes">
                              <summary>Event attributes</summary>
                              <pre>
                                {JSON.stringify(event.attributes, null, 2)}
                              </pre>
                            </details>
                          )}
                      </div>
                    ))}
                </div>
                {!current.events.length && (
                  <Empty title="No timeline events">
                    This case has no recorded payment events.
                  </Empty>
                )}
              </>
            )}
            {tab === "evidence" && banking && (
              <ObpmEvidence
                item={current}
                focusedId={focusEvidence}
                view="coverage"
                revision={revision}
              />
            )}
            {tab === "evidence" && !banking && (
              <EvidenceView item={current} focusedId={focusEvidence} />
            )}{" "}
            {tab === "history" && (
              <>
                <SectionTitle
                  title="Investigation history"
                  icon={<Sparkles size={17} />}
                />
                {investigations.error && (
                  <ErrorNotice error={investigations.error} />
                )}{" "}
                {investigations.loading ? (
                  <Loading />
                ) : !sorted.length ? (
                  <Empty title="No investigations yet">
                    Run the first investigation to assemble a cited case
                    assessment.
                  </Empty>
                ) : (
                  sorted.map((inv) => (
                    <button
                      className={`history-card ${selected?.id === inv.id ? "selected" : ""}`}
                      key={inv.id}
                      onClick={() => setSelectedId(inv.id)}
                    >
                      <div>
                        <ModeBadge
                          mode={inv.mode}
                          synthesisScope={inv.metrics.synthesisScope}
                        />
                        <span className="tiny muted">
                          {date(inv.createdAt)}
                        </span>
                      </div>
                      <strong>{human(inv.outcome)}</strong>
                      <p>{inv.summary}</p>
                      <small>
                        {inv.createdBy} · {inv.id}
                      </small>
                    </button>
                  ))
                )}
              </>
            )}
            {tab === "audit" && (
              <>
                <SectionTitle
                  title="Immutable activity trail"
                  icon={<Fingerprint size={17} />}
                  aside={
                    <span className="tiny muted">
                      Recorded by the business API
                    </span>
                  }
                />
                {audit.error && <ErrorNotice error={audit.error} />}{" "}
                {audit.loading ? (
                  <Loading />
                ) : !audit.data?.items.length ? (
                  <Empty title="No audit entries">
                    Recorded activity will appear here as the case progresses.
                  </Empty>
                ) : (
                  <div className="audit-list">
                    {audit.data.items.map((entry) => (
                      <div className="audit-entry" key={entry.id}>
                        <span className="audit-icon">
                          <Fingerprint size={15} />
                        </span>
                        <div>
                          <strong>{human(entry.action)}</strong>
                          <p>
                            {typeof entry.detail === "string"
                              ? entry.detail
                              : JSON.stringify(entry.detail)}
                          </p>
                          <small>
                            {entry.actor} · {date(entry.occurredAt)}
                          </small>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </>
            )}
          </div>
        </section>
        <InvestigationPanel
          key={caseId}
          item={current}
          user={user}
          investigation={selected}
          isLatest={!selected || selected.id === sorted[0]?.id}
          onCompleted={(id) => {
            setSelectedId(id);
            refresh();
          }}
          onDecision={refresh}
          onEvidence={(id) => {
            setFocusEvidence(id);
            const recordIds = [
              ...(current.obpm?.queueRecords ?? []),
              ...(current.obpm?.externalRequestAttempts ?? []),
            ].map((row) => row.evidenceId);
            setTab(
              banking && (recordIds.includes(id) || id === current.paymentId)
                ? "timeline"
                : "evidence",
            );
          }}
          loading={investigations.loading}
          historyError={investigations.error}
        />
      </div>
    </>
  );
}

const providerFieldLabels = new Map([
  ["amountMinor", "Payment amount"],
  ["feeMinor", "Provider fee"],
  ["refundMinor", "Refund amount"],
  ["payoutMinor", "Payout amount"],
  ["paymentId", "Provider payment reference"],
  ["asOf", "Snapshot at"],
]);

function EvidenceView({
  item,
  focusedId,
}: {
  item: CaseDetail;
  focusedId: string | null;
}) {
  return (
    <>
      <SectionTitle
        title="Source evidence"
        icon={<Database size={17} />}
        aside={<span className="tiny muted">Read-only case snapshot</span>}
      />
      {focusedId && (
        <div className="notice info">
          <Fingerprint size={16} />
          <span>
            Referenced evidence: <code>{focusedId}</code>
          </span>
        </div>
      )}
      <div className="evidence-group">
        <h3>Provider snapshot</h3>
        <div className="provider-grid">
          {Object.entries(item.provider ?? {}).map(([key, value]) => (
            <div key={key}>
              <span>
                {providerFieldLabels.get(key) ??
                  human(key.replace(/([A-Z])/g, "_$1"))}
              </span>
              <strong>
                {key.endsWith("Minor") && typeof value === "number"
                  ? money(value, item.currency)
                  : String(value ?? "Not available")}
              </strong>
            </div>
          ))}
        </div>
      </div>
      <div className="evidence-group">
        <h3>
          Ledger entries <span>{item.ledgerEntries.length}</span>
        </h3>
        {!item.ledgerEntries.length ? (
          <p className="muted">No ledger records supplied.</p>
        ) : (
          item.ledgerEntries.map((row) => (
            <div
              className={`ledger-row ${row.id === focusedId ? "focused" : ""}`}
              key={row.id}
            >
              <div>
                <strong>{human(row.type)}</strong>
                <small className="mono">{row.id}</small>
                <small>
                  {row.reference} · {date(row.occurredAt)}
                </small>
              </div>
              <strong className="amount">
                {money(row.amountMinor, row.currency)}
              </strong>
            </div>
          ))
        )}
      </div>
      <div className="evidence-group">
        <h3>
          Webhook deliveries <span>{item.webhooks.length}</span>
        </h3>
        {!item.webhooks.length ? (
          <p className="muted">No webhook deliveries supplied.</p>
        ) : (
          item.webhooks.map((row) => (
            <div
              className={`webhook-row ${row.id === focusedId || row.providerEventId === focusedId ? "focused" : ""}`}
              key={row.id}
            >
              <div>
                <strong>{row.type}</strong>
                <Badge value={row.processingStatus} />
              </div>
              <small className="mono">
                {row.id} · {row.providerEventId}
              </small>
              <small>
                Occurred {date(row.occurredAt)} · Received{" "}
                {date(row.receivedAt)}
              </small>
            </div>
          ))
        )}
      </div>
      {focusedId && item.events.some((e) => e.id === focusedId) && (
        <div className="evidence-group focused">
          <h3>Referenced event</h3>
          {item.events
            .filter((e) => e.id === focusedId)
            .map((e) => (
              <div key={e.id}>
                <strong>
                  {e.id} · {human(e.type)}
                </strong>
                <p>{e.summary}</p>
                <small>
                  {e.source} · {date(e.occurredAt)}
                </small>
                <pre>{JSON.stringify(e.attributes, null, 2)}</pre>
              </div>
            ))}
        </div>
      )}
    </>
  );
}

export function InvestigationPanel({
  item,
  user,
  investigation,
  isLatest,
  onCompleted,
  onDecision,
  onEvidence,
  loading,
  historyError,
}: {
  item: CaseDetail;
  user: User;
  investigation?: Investigation;
  isLatest: boolean;
  onCompleted: (id: string) => void;
  onDecision: () => void;
  onEvidence: (id: string) => void;
  loading: boolean;
  historyError: Error | null;
}) {
  const [mode, setMode] = useState<"replay" | "ollama">("replay");
  const [question, setQuestion] = useState(
    "Explain what happened, identify supporting evidence, and recommend the next case action.",
  );
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [resultTab, setResultTab] = useState("findings");
  const [showForm, setShowForm] = useState(true);
  const [citationId, setCitationId] = useState<string | null>(null);
  const sourceDialogRef = useRef<HTMLElement>(null);
  const pendingRequest = useRef<AbortController | null>(null);
  useEffect(() => {
    return () => {
      // Stop observing this request when its case is left. Server work may continue.
      pendingRequest.current?.abort();
      pendingRequest.current = null;
    };
  }, [item.id]);
  useEffect(() => {
    if (!citationId) return;
    const previousFocus = document.activeElement as HTMLElement | null;
    const dialog = sourceDialogRef.current;
    dialog?.querySelector<HTMLButtonElement>("button")?.focus();
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") setCitationId(null);
      if (event.key === "Tab" && dialog) {
        const targets = dialog.querySelectorAll<HTMLElement>(
          'button, a[href], input, select, textarea, [tabindex="0"]',
        );
        const first = targets[0];
        const last = targets[targets.length - 1];
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault();
          last?.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault();
          first?.focus();
        }
      }
    }
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      previousFocus?.focus();
    };
  }, [citationId]);
  const canInvestigate = ["ANALYST", "REVIEWER"].includes(
    user.role.toUpperCase(),
  );
  async function run(event: FormEvent) {
    event.preventDefault();
    if (pendingRequest.current) return;
    const controller = new AbortController();
    pendingRequest.current = controller;
    setRunning(true);
    setError(null);
    try {
      const result = await api<Investigation>(
        `/cases/${encodeURIComponent(item.id)}/investigations`,
        {
          method: "POST",
          body: JSON.stringify({ question, mode }),
          signal: controller.signal,
        },
      );
      if (controller.signal.aborted || pendingRequest.current !== controller)
        return;
      if (result.caseId !== item.id)
        throw new ApiError(
          502,
          "CASE_MISMATCH",
          "The service returned a result for a different case. Refresh this case to load its saved investigations.",
        );
      setShowForm(false);
      setResultTab("findings");
      onCompleted(result.id);
    } catch (e) {
      if (!controller.signal.aborted && pendingRequest.current === controller)
        setError(e as Error);
    } finally {
      if (pendingRequest.current === controller) {
        pendingRequest.current = null;
        setRunning(false);
      }
    }
  }
  const citation = investigation?.citations.find((c) => c.id === citationId);
  return (
    <aside className="investigation-panel">
      <section className="panel">
        <div className="investigator-heading">
          <span className="investigator-icon">
            <Sparkles size={19} />
          </span>
          <div>
            <h2>Investigator</h2>
            <p>Evidence-led case assessment</p>
          </div>
          <span className="tiny label">ASSISTED</span>
        </div>
        {showForm || !investigation ? (
          <form className="investigation-form" onSubmit={run}>
            <label id="mode-label">Investigation mode</label>
            <div
              className="mode-switch"
              role="group"
              aria-labelledby="mode-label"
            >
              <button
                type="button"
                className={mode === "replay" ? "selected" : ""}
                onClick={() => setMode("replay")}
                disabled={running}
              >
                <FlaskConical size={15} />
                Replay
              </button>
              <button
                type="button"
                className={mode === "ollama" ? "selected" : ""}
                onClick={() => setMode("ollama")}
                disabled={running}
              >
                <Sparkles size={15} />
                Ollama
              </button>
            </div>
            <p className="mode-explanation">
              {mode === "replay"
                ? "Reproducible tool workflow. No language model inference."
                : "Local model inference. Requires a running Ollama model; failures are surfaced."}
            </p>
            <label htmlFor="question">Investigation question</label>
            <textarea
              id="question"
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              rows={3}
              maxLength={2000}
              disabled={running || !canInvestigate}
            />
            {canInvestigate ? (
              <button
                className="primary full"
                disabled={running || !question.trim()}
              >
                {running ? (
                  <LoaderCircle size={16} className="spin" />
                ) : (
                  <Play size={15} />
                )}{" "}
                {running
                  ? "Investigation running…"
                  : investigation
                    ? "Run another investigation"
                    : "Run investigation"}
              </button>
            ) : (
              <div className="notice neutral">
                <ShieldCheck size={16} />
                <span>
                  Viewer access. An analyst or reviewer can run investigations.
                </span>
              </div>
            )}
            {running && (
              <div className="pending-state" role="status">
                <LoaderCircle size={18} className="spin" />
                <div>
                  <strong>Waiting for the investigation service</strong>
                  <p>
                    {mode === "ollama" &&
                      "Local inference can take several minutes. "}
                    The completed evidence and assessment will appear when the
                    request returns. You can leave this case; server work may
                    continue. Refresh its saved investigations when you return.
                  </p>
                </div>
              </div>
            )}
            {error && (
              <>
                <ErrorNotice error={error} />
                {error instanceof ApiError &&
                  (error.status === 0 ||
                    error.status >= 500 ||
                    error.code === "INVALID_RESPONSE") && (
                    <p className="mode-explanation">
                      The request ended without a confirmed result. The service
                      may still be processing this case. Refresh saved
                      investigations before starting another run.
                    </p>
                  )}
              </>
            )}
          </form>
        ) : (
          <div className="investigation-run-again">
            <ModeBadge
              mode={investigation.mode}
              synthesisScope={investigation.metrics.synthesisScope}
            />
            {canInvestigate && (
              <button className="text-button" onClick={() => setShowForm(true)}>
                New investigation
                <RefreshCw size={12} />
              </button>
            )}
          </div>
        )}
        {historyError && (
          <div className="padded">
            <ErrorNotice error={historyError} />
          </div>
        )}
        {loading ? (
          <Loading label="Loading saved investigation…" />
        ) : investigation ? (
          <>
            <div className="result-summary">
              {running && (
                <p className="tiny muted">
                  Previously saved investigation · the new request is still
                  pending
                </p>
              )}
              <div className="result-top">
                <span className="eyebrow">{human(investigation.outcome)}</span>
                <Badge
                  value={investigation.confidence}
                  className="confidence"
                  label={`Evidence: ${human(investigation.confidence)}`}
                />
              </div>
              <p>{investigation.summary}</p>
              <div className="result-mode">
                <ModeBadge
                  mode={investigation.mode}
                  synthesisScope={investigation.metrics.synthesisScope}
                />
                {investigation.mode === "ollama" &&
                  investigation.metrics.synthesisScope === "fact-selection" &&
                  investigation.metrics.findingSource ===
                    "service-rendered-facts" && (
                    <p>
                      The model selected facts. Their wording and evidence links
                      are supplied by the service.
                    </p>
                  )}
                {investigation.mode === "ollama" &&
                  investigation.metrics.synthesisScope ===
                    "skipped-insufficient-evidence" && (
                    <p>
                      No model explanation was generated because the evidence
                      was insufficient.
                    </p>
                  )}
                {investigation.metrics.assessmentSource ===
                  "deterministic-evidence-rules" && (
                  <p>
                    Summary and proposed action use validated evidence rules.
                  </p>
                )}
              </div>
              <div className="tiny muted">
                {date(investigation.createdAt)} · by {investigation.createdBy}
                {!isLatest && " · Historical investigation"}
              </div>
            </div>
            <div className="result-tabs">
              {[
                ["findings", "Findings"],
                ["sources", `Sources (${investigation.citations.length})`],
                ["tools", "Tool trace"],
              ].map(([id, name]) => (
                <button
                  key={id}
                  className={resultTab === id ? "selected" : ""}
                  onClick={() => setResultTab(id)}
                >
                  {name}
                </button>
              ))}
            </div>
            <div className="result-body">
              {resultTab === "findings" && (
                <>
                  {investigation.findings.map((finding, index) => (
                    <div className="finding" key={finding.id}>
                      <span className="finding-number">
                        {String(index + 1).padStart(2, "0")}
                      </span>
                      <div>
                        <p>{finding.text}</p>
                        <div className="evidence-chips">
                          {finding.evidenceIds.map((id) => (
                            <button key={id} onClick={() => onEvidence(id)}>
                              <Fingerprint size={11} />
                              {id}
                            </button>
                          ))}
                          {finding.citationIds.map((id) => (
                            <button
                              className="source-chip"
                              key={id}
                              onClick={() => setCitationId(id)}
                            >
                              <BookOpen size={11} />
                              {id}
                            </button>
                          ))}
                        </div>
                      </div>
                    </div>
                  ))}
                  {investigation.missingEvidence.length > 0 && (
                    <div className="missing-evidence">
                      <h3>
                        <TriangleAlert size={16} />
                        Evidence still needed
                      </h3>
                      <ul>
                        {investigation.missingEvidence.map((text, i) => (
                          <li key={i}>{text}</li>
                        ))}
                      </ul>
                    </div>
                  )}
                </>
              )}
              {resultTab === "sources" && (
                <>
                  {investigation.citations.length ? (
                    investigation.citations.map((source) => (
                      <button
                        className="citation-card"
                        key={source.id}
                        onClick={() => setCitationId(source.id)}
                      >
                        <div>
                          <BookOpen size={17} />
                          <span>VERSION {source.version}</span>
                        </div>
                        <strong>{source.title}</strong>
                        <p>{source.excerpt}</p>
                        <small>{source.source}</small>
                        <span className="citation-ref">
                          {source.id}
                          <ArrowRight size={13} />
                        </span>
                      </button>
                    ))
                  ) : (
                    <Empty title="No guidance cited">
                      This result did not include a retrieved source.
                    </Empty>
                  )}
                </>
              )}
              {resultTab === "tools" && (
                <>
                  <div className="tool-trace">
                    {investigation.toolCalls.map((tool, i) => (
                      <div className="tool-row" key={`${tool.name}-${i}`}>
                        <span className="tool-step">{i + 1}</span>
                        <div>
                          <strong>{tool.name}</strong>
                          <small>
                            {human(tool.status)} · {tool.durationMs} ms
                          </small>
                          <div className="evidence-chips">
                            {tool.evidenceIds.map((id) => (
                              <button key={id} onClick={() => onEvidence(id)}>
                                {id}
                              </button>
                            ))}
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                  <div className="metrics-grid">
                    <div>
                      <span>Duration</span>
                      <strong>
                        {(investigation.metrics.durationMs / 1000).toFixed(2)}s
                      </strong>
                    </div>
                    <div>
                      <span>Tool calls</span>
                      <strong>{investigation.metrics.toolCount}</strong>
                    </div>
                    <div>
                      <span>Model calls</span>
                      <strong>{investigation.metrics.modelCalls}</strong>
                    </div>
                    <div>
                      <span>Input / output tokens</span>
                      <strong>
                        {investigation.metrics.inputTokens} /{" "}
                        {investigation.metrics.outputTokens}
                      </strong>
                    </div>
                  </div>
                  <div className="metric-detail">
                    <span>Retrieval</span>
                    <strong>
                      {investigation.metrics.retrievalMode} ·{" "}
                      {investigation.metrics.retrievalMs} ms
                    </strong>
                    <span>Model</span>
                    <strong>{investigation.metrics.model}</strong>
                  </div>
                </>
              )}
              {resultTab === "tools" && investigation.warnings.length > 0 && (
                <div className="result-warnings">
                  {investigation.warnings.map((warning, i) => (
                    <p key={i}>
                      <CircleHelp size={13} />
                      {warning}
                    </p>
                  ))}
                </div>
              )}
            </div>
            <ReviewDecision
              user={user}
              item={item}
              investigation={investigation}
              isLatest={isLatest}
              onDecision={onDecision}
            />
          </>
        ) : (
          !running && (
            <div className="investigation-placeholder">
              <div className="placeholder-orbit">
                <FileCheck2 size={28} />
              </div>
              <h3>A complete picture, with receipts.</h3>
              <p>
                {item.domain === "OBPM_NEFT"
                  ? "Run an investigation to connect banking queue records, ECA request attempts, source coverage, and operating guidance."
                  : "Run an investigation to connect transaction events, ledger records, and operating guidance."}
              </p>
              <div>
                <span>
                  <Check size={13} />
                  Cited findings
                </span>
                <span>
                  <Check size={13} />
                  Recorded tool trace
                </span>
              </div>
            </div>
          )
        )}
      </section>
      <div className="guard-note">
        <ShieldCheck size={15} />
        <span>Proposals change case workflow only. No funds are moved.</span>
      </div>
      {citation && (
        <div className="modal-backdrop" onClick={() => setCitationId(null)}>
          <section
            ref={sourceDialogRef}
            className="source-modal"
            role="dialog"
            aria-modal="true"
            aria-label="Source citation"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="source-modal-top">
              <span className="eyebrow">RETRIEVED GUIDANCE</span>
              <button
                className="icon-button"
                aria-label="Close citation"
                onClick={() => setCitationId(null)}
              >
                <X size={18} />
              </button>
            </div>
            <h2>{citation.title}</h2>
            <div className="source-modal-meta">
              <code>{citation.id}</code>
              <span>Version {citation.version}</span>
            </div>
            <blockquote>{citation.excerpt}</blockquote>
            <p>{citation.source}</p>
            <small>Source included in the saved investigation snapshot.</small>
          </section>
        </div>
      )}
    </aside>
  );
}

export function ReviewDecision({
  user,
  item,
  investigation,
  isLatest,
  onDecision,
}: {
  user: User;
  item: Pick<CaseDetail, "id" | "version" | "status">;
  investigation: Investigation;
  isLatest: boolean;
  onDecision: () => void;
}) {
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [decision, setDecision] = useState<string | null>(null);
  const retryKey = useRef<{ signature: string; key: string } | null>(null);
  const eligible =
    canReview(user, investigation) &&
    isLatest &&
    item.status === "AWAITING_REVIEW" &&
    !decision;
  useEffect(() => {
    setDecision(null);
    setError(null);
    setNote("");
    retryKey.current = null;
  }, [investigation.id]);
  async function decide(value: "APPROVE" | "REJECT") {
    if (!eligible) return;
    setBusy(true);
    setError(null);
    const payload = {
      investigationId: investigation.id,
      decision: value,
      note: note.trim(),
      expectedVersion: item.version,
    };
    const signature = JSON.stringify(payload);
    if (retryKey.current?.signature !== signature)
      retryKey.current = { signature, key: crypto.randomUUID() };
    try {
      await api(`/cases/${encodeURIComponent(item.id)}/decisions`, {
        method: "POST",
        headers: { "Idempotency-Key": retryKey.current.key },
        body: signature,
      });
      setDecision(value);
      onDecision();
    } catch (e) {
      setError(e as Error);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="decision-panel">
      <div className="proposal-label">
        <ShieldCheck size={17} />
        <span>PROPOSED CASE ACTION</span>
      </div>
      <h3>{human(investigation.proposal.action)}</h3>
      <p>{investigation.proposal.reason}</p>
      {decision ? (
        <div className="notice success" role="status">
          <CheckCircle2 size={17} />
          Decision recorded: {human(decision)}
        </div>
      ) : eligible ? (
        <>
          <label htmlFor="review-note">
            Review note <span className="muted">(required)</span>
          </label>
          <textarea
            id="review-note"
            rows={2}
            placeholder="Record the basis for your decision…"
            value={note}
            maxLength={2000}
            disabled={busy}
            onChange={(e) => setNote(e.target.value)}
          />
          <div className="decision-actions">
            <button
              className="primary"
              onClick={() => decide("APPROVE")}
              disabled={busy || !note.trim()}
            >
              {busy ? (
                <LoaderCircle size={15} className="spin" />
              ) : (
                <Check size={15} />
              )}
              Approve proposal
            </button>
            <button
              className="secondary reject"
              onClick={() => decide("REJECT")}
              disabled={busy || !note.trim()}
            >
              <X size={15} />
              Reject
            </button>
          </div>
          <small>
            Decision uses case version {item.version}. Recorded with your
            identity.
          </small>
        </>
      ) : (
        <div className="review-gate">
          <ShieldCheck size={16} />
          <span>
            {user.role.toUpperCase() !== "REVIEWER"
              ? "A reviewer must approve or reject this proposal."
              : user.id === investigation.createdBy
                ? "Independent review required. You cannot review your own investigation."
                : !isLatest
                  ? "Historical result. Review the latest investigation."
                  : "This case is not awaiting a new review."}
          </span>
        </div>
      )}
      {error && <ErrorNotice error={error} />}
    </div>
  );
}

function SystemPage() {
  const [revision, setRevision] = useState(0);
  const health = useResource<{ status: string; service: string; mode: string }>(
    "/health",
    revision,
  );
  const system = useResource<SystemInfo>("/system", revision);
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">ENVIRONMENT & OBSERVABILITY</div>
          <h1>System & health</h1>
          <p>
            Check local services and understand how saved payment evidence is
            investigated.
          </p>
        </div>
        <button className="secondary" onClick={() => setRevision((v) => v + 1)}>
          <RefreshCw size={16} />
          Check health
        </button>
      </div>
      {system.error && <ErrorNotice error={system.error} />}{" "}
      {health.error && <ErrorNotice error={health.error} />}
      <div className="system-grid">
        <section className="panel system-card">
          <SectionTitle title="Service health" icon={<Activity size={18} />} />
          <div className="service-row">
            <div>
              <span className="service-icon">
                <Database size={19} />
              </span>
              <div>
                <strong>Business API</strong>
                <small>Authoritative case & review service</small>
              </div>
            </div>
            {health.loading ? (
              <LoaderCircle size={16} className="spin" />
            ) : (
              <Badge value={health.data?.status || "UNAVAILABLE"} />
            )}
          </div>
          <div className="service-row">
            <div>
              <span className="service-icon">
                <Sparkles size={19} />
              </span>
              <div>
                <strong>Investigation worker</strong>
                <small>Evidence tools & orchestration</small>
              </div>
            </div>
            {system.loading ? (
              <LoaderCircle size={16} className="spin" />
            ) : (
              <Badge value={system.data?.workerStatus || "UNKNOWN"} />
            )}
          </div>
          <p className="tiny muted">
            Health is checked when this page loads or you select Check health.
            Service status does not confirm that a model is loaded or a bank
            inquiry endpoint is reachable.
          </p>
        </section>
        <section className="panel system-card">
          <SectionTitle
            title="Preserved demo dataset"
            icon={<Layers3 size={18} />}
          />
          <dl className="system-facts">
            <div>
              <dt>Dataset version</dt>
              <dd>{system.data?.datasetVersion ?? "—"}</dd>
            </div>
            <div>
              <dt>Demo cases</dt>
              <dd>{system.data?.caseCount ?? "—"}</dd>
            </div>
            <div>
              <dt>Runbooks</dt>
              <dd>
                {system.data?.knowledgeAvailable === false
                  ? "Unavailable"
                  : (system.data?.runbookCount ?? "—")}
              </dd>
            </div>
            <div>
              <dt>Record origin</dt>
              <dd>Original synthetic fixtures</dd>
            </div>
          </dl>
          <p className="tiny muted">
            These counts describe retained test fixtures. Your saved payment
            cases and attached evidence are listed in Case queue and Evidence
            library.
          </p>
        </section>
        <section className="panel system-card">
          <SectionTitle
            title="Payment case investigation"
            icon={<TerminalSquare size={18} />}
          />
          <div className="mode-description">
            <span className="mode-badge ollama">
              <Sparkles size={13} /> Local model · Ollama
            </span>
            <p>
              Select a saved payment case and evidence version, then submit a
              question. The local model uses those records and their supplied
              source guidance; the answer and citations stay with that version.
            </p>
          </div>
          <p className="tiny muted">
            A completed investigation records the model name, actual calls and
            duration. A failed model request does not produce a substitute
            answer.
          </p>
        </section>
        <section className="panel system-card">
          <SectionTitle
            title="Operating boundaries"
            icon={<ShieldCheck size={18} />}
          />
          <ul className="limitations">
            <li>
              Payment evidence can come from configured inquiry APIs, Excel
              files or manual and JSON input.
            </li>
            <li>
              Model answers require source review. They do not change payment
              status or establish an outcome from missing records.
            </li>
            <li>This application does not execute payments.</li>
          </ul>
          <details>
            <summary>Legacy test details</summary>
            <p className="tiny muted">
              Retained synthetic test capabilities reported by the API. This
              list does not report local model readiness.
            </p>
            <p className="tiny muted">
              Legacy service modes:{" "}
              {system.data?.supportedModes.join(", ") || "Not reported"}
            </p>
            <ul className="limitations">
              {system.data?.limitations.map((text, i) => (
                <li key={i}>{text}</li>
              ))}
            </ul>
          </details>
        </section>
      </div>
    </>
  );
}
