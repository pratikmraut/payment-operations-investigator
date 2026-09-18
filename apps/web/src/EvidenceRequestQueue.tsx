import { useEffect, useState } from "react";
import { AlertCircle, RefreshCw } from "lucide-react";
import { api, human } from "./api";
import { navigateLink } from "./routing";
type WorkItem = {
  caseId: string;
  caseNumber: string;
  reference: string;
  requestId: string;
  title: string;
  caseStatus: string;
  assignee: { id: string; name: string } | null;
  dueDate: string | null;
  daysOverdue: number;
};
type Work = {
  items: WorkItem[];
  total: number;
  openCount: number;
  overdueCount: number;
  mineCount: number;
  timezone: string;
  today: string;
  hasMore: boolean;
};
function validWork(value: unknown, view: string, offset: number): Work {
  const work = value as Work & { view?: string; offset?: number };
  const text = (v: unknown) => typeof v === "string" && v.length > 0;
  const count = (v: unknown) => Number.isSafeInteger(v) && (v as number) >= 0;
  const date = (v: unknown) =>
    typeof v === "string" && /^\d{4}-\d{2}-\d{2}$/.test(v);
  if (
    !work ||
    !Array.isArray(work.items) ||
    work.items.length > 10 ||
    ![work.total, work.openCount, work.overdueCount, work.mineCount].every(
      count,
    ) ||
    !text(work.timezone) ||
    !date(work.today) ||
    typeof work.hasMore !== "boolean" ||
    (work.view !== undefined && work.view !== view) ||
    (work.offset !== undefined && work.offset !== offset) ||
    !work.items.every(
      (item) =>
        item &&
        [
          item.caseId,
          item.caseNumber,
          item.reference,
          item.requestId,
          item.title,
          item.caseStatus,
        ].every(text) &&
        count(item.daysOverdue) &&
        (item.dueDate === null || date(item.dueDate)) &&
        (item.assignee === null ||
          (item.assignee &&
            text(item.assignee.id) &&
            text(item.assignee.name))),
    )
  )
    throw new Error(
      "The API returned invalid follow-up data. Refresh follow-ups to try again.",
    );
  return work;
}
export function EvidenceRequestQueue({ revision }: { revision: number }) {
  const [open, setOpen] = useState(false),
    [view, setView] = useState("ALL"),
    [offset, setOffset] = useState(0);
  const [refresh, setRefresh] = useState(0),
    [data, setData] = useState<Work | null>(null),
    [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      setError(
        "Loading follow-ups timed out. Refresh follow-ups to try again.",
      );
      setLoading(false);
      controller.abort();
    }, 30000);
    setLoading(true);
    setError("");
    setData(null);
    api<unknown>(`/payment-case-work?view=${view}&offset=${offset}&limit=10`, {
      signal: controller.signal,
    })
      .then((value) => {
        if (!controller.signal.aborted) setData(validWork(value, view, offset));
      })
      .catch((cause) => {
        if (!controller.signal.aborted)
          setError(
            cause instanceof Error
              ? cause.message
              : "Could not load follow-ups.",
          );
      })
      .finally(() => {
        window.clearTimeout(timer);
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [open, view, offset, refresh, revision]);
  return (
    <details
      className="panel evidence-followups"
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary>
        Evidence follow-ups{" "}
        <small>Assigned requests and overdue reminders</small>
      </summary>
      {open && (
        <div className="evidence-followups-body">
          <div className="section-title">
            <h3>Outstanding evidence requests</h3>
            <button
              className="secondary"
              disabled={loading}
              onClick={() => {
                setOffset(0);
                setRefresh((n) => n + 1);
              }}
            >
              <RefreshCw size={16} /> Refresh follow-ups
            </button>
          </div>
          <p>
            Open requests from active, unresolved cases you can access. Due
            dates use {data?.timezone ?? "the configured case timezone"}.
          </p>
          {data && data.overdueCount > 0 && (
            <p className="warning" role="status">
              <AlertCircle size={16} /> {data.overdueCount} evidence{" "}
              {data.overdueCount === 1 ? "request is" : "requests are"} overdue.
              Open a case to record a follow-up or update its request.
            </p>
          )}
          <label>
            Follow-up view
            <select
              value={view}
              onChange={(event) => {
                setView(event.target.value);
                setOffset(0);
              }}
            >
              <option value="ALL">All outstanding requests</option>
              <option value="MINE">Assigned to me</option>
              <option value="OVERDUE">Overdue requests</option>
            </select>
          </label>
          {loading ? (
            <p role="status">Loading evidence follow-ups…</p>
          ) : error ? (
            <p role="alert">{error}</p>
          ) : (
            data && (
              <>
                <p role="status">
                  {data.total} matching requests · {data.openCount} open ·{" "}
                  {data.mineCount} assigned to you
                </p>
                {!data.items.length ? (
                  <p>No outstanding requests match this view.</p>
                ) : (
                  <div className="table-scroll">
                    <table>
                      <thead>
                        <tr>
                          <th>Case / payment</th>
                          <th>Evidence request</th>
                          <th>Assigned to</th>
                          <th>Due date</th>
                        </tr>
                      </thead>
                      <tbody>
                        {data.items.map((item) => (
                          <tr key={item.requestId}>
                            <td>
                              <a
                                href={`/payment-cases/${encodeURIComponent(item.caseNumber)}`}
                                onClick={navigateLink}
                              >
                                {item.caseNumber}
                              </a>
                              <small>
                                {item.reference} · {human(item.caseStatus)}
                              </small>
                            </td>
                            <td>{item.title}</td>
                            <td>{item.assignee?.name ?? "Unassigned"}</td>
                            <td>
                              {item.dueDate ?? "No due date"}
                              {item.daysOverdue > 0 && (
                                <small>
                                  {item.daysOverdue}{" "}
                                  {item.daysOverdue === 1 ? "day" : "days"}{" "}
                                  overdue
                                </small>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                <nav
                  className="payment-saved-pagination"
                  aria-label="Evidence follow-up pages"
                >
                  <button
                    className="secondary"
                    disabled={!offset}
                    onClick={() => setOffset((n) => Math.max(0, n - 10))}
                  >
                    Previous requests
                  </button>
                  <span>Page {Math.floor(offset / 10) + 1}</span>
                  <button
                    className="secondary"
                    disabled={!data.hasMore}
                    onClick={() => setOffset((n) => n + 10)}
                  >
                    Next requests
                  </button>
                </nav>
              </>
            )
          )}
        </div>
      )}
    </details>
  );
}
