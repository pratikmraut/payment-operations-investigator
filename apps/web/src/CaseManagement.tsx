import { useEffect, useRef, useState, type FormEvent } from "react";
import { api, ApiError, human } from "./api";
import type { User } from "./types";
import { CaseLifecycle, type CaseLifecycleState } from "./CaseLifecycle";
import "./CaseManagement.css";

type Actor = { id: string; name: string };
type Note = {
  id: string;
  text: string;
  createdAt: string;
  createdBy: string;
  createdByName: string;
};
type RequestUpdate = {
  id: string;
  status: string;
  note: string;
  evidenceId: string | null;
  evidenceVersion: number | null;
  evidenceHash: string | null;
  createdAt: string;
  createdBy: string;
  createdByName: string;
};
type EvidenceRequest = {
  id: string;
  title: string;
  detail: string;
  dueDate: string | null;
  status: string;
  createdAt: string;
  createdBy: string;
  createdByName: string;
  updatedAt: string;
  updatedBy: string;
  updatedByName: string;
  evidenceId: string | null;
  evidenceVersion: number | null;
  evidenceHash: string | null;
  updates: RequestUpdate[];
};
type Conclusion = {
  id: string;
  status: "RECORDED";
  conclusion: string;
  evidenceId: string;
  evidenceHash: string;
  evidenceVersion: number;
  investigationIds: string[];
  createdAt: string;
  createdBy: string;
  createdByName: string;
};
export type Management = {
  caseId: string;
  version: number;
  owner: Actor | null;
  priority: string;
  assignees: (Actor & { role: string })[];
  notes: Note[];
  evidenceRequests: EvidenceRequest[];
  reviewerConclusions: Conclusion[];
  audit: {
    id: string;
    action: string;
    occurredAt: string;
    actor: string;
    actorName: string;
    detail: string;
    version: number;
  }[];
};
type Evidence = {
  id: string;
  evidenceHash: string;
  version: number;
  caseId: string;
};
type Job = {
  id: string;
  caseId: string;
  evidenceId: string;
  evidenceHash: string;
  question: string;
  status: string;
  createdBy: string;
  evidenceVersion: number;
};
type Workbench = {
  caseId: string;
  latestEvidenceId: string | null;
  evidence: Evidence[];
  investigations: Job[];
};
type Props = {
  caseId: string;
  createdBy: string;
  user?: User;
  evidenceRevision?: number;
  onChanged?: () => void;
  lifecycleState?: CaseLifecycleState;
  onLifecycleChanged?: (state: CaseLifecycleState) => void;
};
type Tab =
  | "Overview"
  | "Notes"
  | "Evidence requests"
  | "Reviewer conclusion"
  | "Case lifecycle"
  | "Activity";
type Attempt = {
  path: string;
  body: Record<string, unknown>;
  key: string;
  kind: string;
};
const priorities = ["LOW", "MEDIUM", "HIGH", "CRITICAL"];
const text = (v: unknown): v is string => typeof v === "string";
const list = (v: unknown): v is unknown[] => Array.isArray(v);
const object = (v: unknown): v is Record<string, unknown> =>
  !!v && typeof v === "object" && !Array.isArray(v);
const actor = (v: unknown): v is Actor =>
  object(v) && text(v.id) && text(v.name);
function validate(value: Management, caseId: string) {
  const authored = (v: unknown) =>
    object(v) && [v.id, v.createdAt, v.createdBy, v.createdByName].every(text);
  const evidenceLink = (v: Record<string, unknown>) =>
    (v.evidenceId === null &&
      v.evidenceHash === null &&
      v.evidenceVersion === null) ||
    (text(v.evidenceId) &&
      text(v.evidenceHash) &&
      Number.isSafeInteger(v.evidenceVersion) &&
      (v.evidenceVersion as number) > 0);
  if (
    !value ||
    value.caseId !== caseId ||
    !Number.isSafeInteger(value.version) ||
    value.version < 0 ||
    !priorities.includes(value.priority) ||
    !(value.owner === null || actor(value.owner)) ||
    !list(value.assignees) ||
    !value.assignees.every((v) => actor(v) && object(v) && text(v.role)) ||
    !list(value.notes) ||
    !value.notes.every((v) => authored(v) && object(v) && text(v.text)) ||
    !list(value.evidenceRequests) ||
    !value.evidenceRequests.every(
      (v) =>
        authored(v) &&
        object(v) &&
        [v.title, v.detail, v.updatedAt, v.updatedBy, v.updatedByName].every(
          text,
        ) &&
        ["OPEN", "FULFILLED", "CANCELLED"].includes(String(v.status)) &&
        (v.dueDate === null || text(v.dueDate)) &&
        evidenceLink(v) &&
        list(v.updates) &&
        v.updates.every(
          (u) =>
            authored(u) &&
            object(u) &&
            text(u.note) &&
            ["OPEN", "FULFILLED", "CANCELLED"].includes(String(u.status)) &&
            evidenceLink(u),
        ),
    ) ||
    !list(value.reviewerConclusions) ||
    !value.reviewerConclusions.every(
      (v) =>
        authored(v) &&
        object(v) &&
        v.status === "RECORDED" &&
        [v.conclusion, v.evidenceId, v.evidenceHash].every(text) &&
        Number.isSafeInteger(v.evidenceVersion) &&
        list(v.investigationIds) &&
        v.investigationIds.every(text),
    ) ||
    !list(value.audit) ||
    !value.audit.every(
      (v) =>
        object(v) &&
        [v.id, v.action, v.occurredAt, v.actor, v.actorName, v.detail].every(
          text,
        ) &&
        Number.isSafeInteger(v.version),
    )
  )
    throw new Error(
      "The case management response is unreadable or belongs to another case.",
    );
}
function validateWorkbench(value: Workbench, caseId: string) {
  if (
    !value ||
    value.caseId !== caseId ||
    !Array.isArray(value.evidence) ||
    !Array.isArray(value.investigations) ||
    !value.evidence.every(
      (v) =>
        !!v &&
        v.caseId === caseId &&
        text(v.id) &&
        text(v.evidenceHash) &&
        Number.isSafeInteger(v.version),
    ) ||
    !value.investigations.every(
      (v) =>
        !!v &&
        v.caseId === caseId &&
        [
          v.id,
          v.evidenceId,
          v.evidenceHash,
          v.question,
          v.status,
          v.createdBy,
        ].every(text),
    )
  )
    throw new Error(
      "The saved evidence and investigation list could not be read for this case.",
    );
}
async function bounded<T>(
  path: string,
  signal: AbortSignal,
  options: RequestInit = {},
) {
  const controller = new AbortController();
  const abort = () => controller.abort();
  signal.addEventListener("abort", abort, { once: true });
  if (signal.aborted) controller.abort();
  const timeout = window.setTimeout(abort, 30000);
  try {
    return await api<T>(path, { ...options, signal: controller.signal });
  } finally {
    window.clearTimeout(timeout);
    signal.removeEventListener("abort", abort);
  }
}
export function CaseManagement(props: Props) {
  return <ManagementView key={props.caseId} {...props} />;
}
function ManagementView({
  caseId,
  createdBy,
  user,
  evidenceRevision = 0,
  onChanged,
  lifecycleState = "ACTIVE",
  onLifecycleChanged,
}: Props) {
  const base = `/payment-cases/${encodeURIComponent(caseId)}`;
  const canWrite =
    lifecycleState === "ACTIVE" &&
    !!user &&
    ["ANALYST", "REVIEWER"].includes(user.role.toUpperCase());
  const isReviewer =
    lifecycleState === "ACTIVE" && user?.role.toUpperCase() === "REVIEWER";
  const [tab, setTab] = useState<Tab>("Overview"),
    [data, setData] = useState<Management | null>(null),
    [workbench, setWorkbench] = useState<Workbench | null>(null);
  const [lifecycleVisited, setLifecycleVisited] = useState(false);
  const [revision, setRevision] = useState(0),
    [loading, setLoading] = useState(true),
    [busy, setBusy] = useState(false),
    [conflict, setConflict] = useState(false);
  const [error, setError] = useState<Error | null>(null),
    [success, setSuccess] = useState("");
  const [overview, setOverview] = useState({
      ownerId: "",
      priority: "MEDIUM",
      reason: "",
    }),
    dirty = useRef(false);
  const [note, setNote] = useState(""),
    [request, setRequest] = useState({ title: "", detail: "", dueDate: "" });
  const [requestId, setRequestId] = useState(""),
    [update, setUpdate] = useState({
      status: "FULFILLED",
      note: "",
      evidenceId: "",
    });
  const [evidenceId, setEvidenceId] = useState(""),
    [jobIds, setJobIds] = useState<string[]>([]),
    [conclusion, setConclusion] = useState("");
  const [uncertain, setUncertain] = useState<Attempt | null>(null),
    operation = useRef<AbortController | null>(null);
  useEffect(() => () => operation.current?.abort(), []);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    Promise.all([
      bounded<Management>(`${base}/management`, controller.signal),
      bounded<Workbench>(`${base}/workbench`, controller.signal),
    ])
      .then(([management, work]) => {
        if (controller.signal.aborted) return;
        validate(management, caseId);
        validateWorkbench(work, caseId);
        setData(management);
        setWorkbench(work);
        setConflict(false);
        if (!dirty.current)
          setOverview({
            ownerId: management.owner?.id ?? "",
            priority: management.priority,
            reason: "",
          });
        setEvidenceId((current) => current || work.latestEvidenceId || "");
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(e as Error);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [base, caseId, revision, evidenceRevision]);
  const disabled = busy || loading || conflict || !!uncertain;
  const selectedEvidence = workbench?.evidence.find((e) => e.id === evidenceId);
  const eligibleJobs =
    workbench?.investigations.filter(
      (j) =>
        j.status === "COMPLETED" &&
        j.evidenceId === evidenceId &&
        j.evidenceHash === selectedEvidence?.evidenceHash,
    ) ?? [];
  const independent =
    isReviewer &&
    createdBy !== user?.id &&
    jobIds.length > 0 &&
    jobIds.every((id) =>
      eligibleJobs.some((j) => j.id === id && j.createdBy !== user?.id),
    );
  async function perform(attempt: Attempt) {
    if (busy) return;
    const controller = new AbortController();
    operation.current = controller;
    setBusy(true);
    setError(null);
    setSuccess("");
    try {
      const next = await bounded<Management>(attempt.path, controller.signal, {
        method: "POST",
        headers: { "Idempotency-Key": attempt.key },
        body: JSON.stringify(attempt.body),
      });
      if (controller.signal.aborted) return;
      validate(next, caseId);
      setData(next);
      setUncertain(null);
      setConflict(false);
      setSuccess("Saved to this case.");
      if (attempt.kind === "overview") {
        dirty.current = false;
        setOverview({
          ownerId: next.owner?.id ?? "",
          priority: next.priority,
          reason: "",
        });
      }
      if (attempt.kind === "note") setNote("");
      if (attempt.kind === "request")
        setRequest({ title: "", detail: "", dueDate: "" });
      if (attempt.kind === "update") {
        setRequestId("");
        setUpdate({ status: "FULFILLED", note: "", evidenceId: "" });
      }
      if (attempt.kind === "conclusion") {
        setConclusion("");
        setJobIds([]);
      }
      onChanged?.();
    } catch (failure) {
      if (controller.signal.aborted) return;
      const e = failure as Error;
      setError(e);
      if (e instanceof ApiError && e.status === 409) {
        setUncertain(null);
        setConflict(true);
      } else if (e instanceof ApiError && e.status >= 400 && e.status < 500) {
        setUncertain(null);
      } else setUncertain(attempt);
    } finally {
      if (!controller.signal.aborted) setBusy(false);
    }
  }
  function save(
    event: FormEvent,
    suffix: string,
    body: Record<string, unknown>,
    kind: string,
  ) {
    event.preventDefault();
    if (!data || disabled || !canWrite) return;
    void perform({
      path: `${base}${suffix}`,
      body: { expectedVersion: data.version, ...body },
      key: crypto.randomUUID(),
      kind,
    });
  }
  const meta = (v: { createdByName: string; createdAt: string }) => (
    <small>
      {v.createdByName} · {v.createdAt}
    </small>
  );
  return (
    <section
      className="panel case-management"
      aria-labelledby="case-management-title"
    >
      <div className="section-title">
        <h2 id="case-management-title">Case management</h2>
        <button
          className="secondary"
          disabled={busy || loading}
          onClick={() => setRevision((v) => v + 1)}
        >
          Refresh management
        </button>
      </div>
      <p className="muted">
        Assign responsibility, keep investigation notes and request supporting
        evidence. These records do not execute or approve a payment.
      </p>
      <div
        className="queue-tabs"
        role="tablist"
        aria-label="Case management sections"
      >
        {(
          [
            "Overview",
            "Notes",
            "Evidence requests",
            "Reviewer conclusion",
            "Activity",
            "Case lifecycle",
          ] as Tab[]
        ).map((name) => (
          <button
            key={name}
            role="tab"
            className={tab === name ? "selected" : ""}
            aria-selected={tab === name}
            onClick={() => {
              if (name === "Case lifecycle") setLifecycleVisited(true);
              setTab(name);
            }}
          >
            {name}
          </button>
        ))}
      </div>
      {loading && <p role="status">Loading case management…</p>}
      {error && (
        <div className="notice danger" role="alert">
          <div>
            <strong>{error.message}</strong>
            {error instanceof ApiError && error.requestId && (
              <small>Request {error.requestId}</small>
            )}
          </div>
        </div>
      )}
      {conflict && (
        <p className="notice neutral">
          The saved case changed. Your draft is preserved. Refresh management,
          review the current values, then save again.
        </p>
      )}
      {uncertain && (
        <div className="notice neutral">
          <p>
            The save outcome is not confirmed. Your draft is preserved. Retry
            the same request before starting another change.
          </p>
          <button
            className="secondary"
            disabled={busy}
            onClick={() => void perform(uncertain)}
          >
            Retry pending save
          </button>
        </div>
      )}
      {success && <p role="status">{success}</p>}
      {lifecycleState === "ARCHIVED" ? (
        <p className="notice neutral">
          This case is archived. Saved management records remain available.
          Restore it from Case lifecycle to make changes.
        </p>
      ) : (
        !canWrite && (
          <p className="muted">
            Your role can read case management. An analyst or reviewer can make
            changes.
          </p>
        )
      )}
      {data && (
        <div role="tabpanel" aria-label={tab}>
          {lifecycleVisited && (
            <div hidden={tab !== "Case lifecycle"}>
              <CaseLifecycle
                caseId={caseId}
                user={user}
                onChanged={onLifecycleChanged}
                visible={tab === "Case lifecycle"}
              />
            </div>
          )}
          {tab === "Overview" && (
            <>
              <dl className="case-management-facts">
                <div>
                  <dt>Owner</dt>
                  <dd>{data.owner?.name ?? "Unassigned"}</dd>
                </div>
                <div>
                  <dt>Priority</dt>
                  <dd>{human(data.priority)}</dd>
                </div>
                <div>
                  <dt>Management version</dt>
                  <dd>{data.version}</dd>
                </div>
              </dl>
              {canWrite && (
                <form
                  onSubmit={(e) =>
                    save(
                      e,
                      "/management",
                      {
                        ownerId: overview.ownerId || null,
                        priority: overview.priority,
                        reason: overview.reason,
                      },
                      "overview",
                    )
                  }
                >
                  <div className="case-management-grid">
                    <label>
                      Case owner
                      <select
                        value={overview.ownerId}
                        disabled={disabled}
                        onChange={(e) => {
                          dirty.current = true;
                          setOverview({ ...overview, ownerId: e.target.value });
                        }}
                      >
                        <option value="">Unassigned</option>
                        {data.assignees.map((a) => (
                          <option key={a.id} value={a.id}>
                            {a.name}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      Case priority
                      <select
                        value={overview.priority}
                        disabled={disabled}
                        onChange={(e) => {
                          dirty.current = true;
                          setOverview({
                            ...overview,
                            priority: e.target.value,
                          });
                        }}
                      >
                        {priorities.map((p) => (
                          <option key={p} value={p}>
                            {human(p)}
                          </option>
                        ))}
                      </select>
                    </label>
                  </div>
                  <label>
                    Reason for assignment or priority change
                    <textarea
                      required
                      maxLength={4000}
                      value={overview.reason}
                      disabled={disabled}
                      onChange={(e) => {
                        dirty.current = true;
                        setOverview({ ...overview, reason: e.target.value });
                      }}
                    />
                  </label>
                  <button
                    className="primary"
                    disabled={disabled || !overview.reason.trim()}
                  >
                    Save owner and priority
                  </button>
                </form>
              )}
            </>
          )}
          {tab === "Notes" && (
            <>
              {canWrite && (
                <form
                  onSubmit={(e) => save(e, "/notes", { text: note }, "note")}
                >
                  <label>
                    New case note
                    <textarea
                      required
                      maxLength={4000}
                      value={note}
                      disabled={disabled}
                      onChange={(e) => setNote(e.target.value)}
                    />
                  </label>
                  <button
                    className="primary"
                    disabled={disabled || !note.trim()}
                  >
                    Add note
                  </button>
                </form>
              )}
              {data.notes.length ? (
                data.notes.map((n) => (
                  <article key={n.id}>
                    <p>{n.text}</p>
                    {meta(n)}
                  </article>
                ))
              ) : (
                <p>No notes recorded.</p>
              )}
            </>
          )}
          {tab === "Evidence requests" && (
            <>
              {canWrite && (
                <form
                  onSubmit={(e) =>
                    save(
                      e,
                      "/evidence-requests",
                      { ...request, dueDate: request.dueDate || null },
                      "request",
                    )
                  }
                >
                  <label>
                    Evidence request title
                    <input
                      required
                      maxLength={200}
                      value={request.title}
                      disabled={disabled}
                      onChange={(e) =>
                        setRequest({ ...request, title: e.target.value })
                      }
                    />
                  </label>
                  <label>
                    Evidence needed and reason
                    <textarea
                      required
                      maxLength={4000}
                      value={request.detail}
                      disabled={disabled}
                      onChange={(e) =>
                        setRequest({ ...request, detail: e.target.value })
                      }
                    />
                  </label>
                  <label>
                    Due date (optional)
                    <input
                      type="date"
                      value={request.dueDate}
                      disabled={disabled}
                      onChange={(e) =>
                        setRequest({ ...request, dueDate: e.target.value })
                      }
                    />
                  </label>
                  <button
                    className="primary"
                    disabled={
                      disabled ||
                      !request.title.trim() ||
                      !request.detail.trim()
                    }
                  >
                    Add evidence request
                  </button>
                  <p className="muted">
                    This records a request within the case; it does not send a
                    message or fetch evidence.
                  </p>
                </form>
              )}
              {data.evidenceRequests.length ? (
                data.evidenceRequests.map((r) => (
                  <article key={r.id}>
                    <h3>
                      {r.title} · {human(r.status)}
                    </h3>
                    <p>{r.detail}</p>
                    {meta(r)}
                    {r.dueDate && <p>Due: {r.dueDate}</p>}
                    {r.evidenceId && (
                      <p>
                        Linked evidence version {r.evidenceVersion}:{" "}
                        <code>{r.evidenceId}</code>
                      </p>
                    )}
                    {r.updates.length > 0 && (
                      <details>
                        <summary>Request history</summary>
                        {r.updates.map((u) => (
                          <p key={u.id}>
                            {human(u.status)} · {u.note}
                            <br />
                            {meta(u)}
                          </p>
                        ))}
                      </details>
                    )}
                    {canWrite && (
                      <button
                        className="secondary"
                        disabled={disabled}
                        onClick={() => {
                          setRequestId(r.id);
                          setUpdate({
                            status: r.status === "OPEN" ? "FULFILLED" : "OPEN",
                            note: "",
                            evidenceId: "",
                          });
                        }}
                      >
                        Update request: {r.title}
                      </button>
                    )}
                    {requestId === r.id && canWrite && (
                      <form
                        onSubmit={(e) =>
                          save(
                            e,
                            `/evidence-requests/${encodeURIComponent(r.id)}`,
                            {
                              status: update.status,
                              note: update.note,
                              ...(update.status === "FULFILLED"
                                ? { evidenceId: update.evidenceId }
                                : {}),
                            },
                            "update",
                          )
                        }
                      >
                        <label>
                          Request status
                          <select
                            value={update.status}
                            disabled={disabled}
                            onChange={(e) =>
                              setUpdate({ ...update, status: e.target.value })
                            }
                          >
                            {["OPEN", "FULFILLED", "CANCELLED"].map((s) => (
                              <option key={s}>{s}</option>
                            ))}
                          </select>
                        </label>
                        {update.status === "FULFILLED" && (
                          <label>
                            Evidence that fulfils this request
                            <select
                              required
                              value={update.evidenceId}
                              disabled={disabled}
                              onChange={(e) =>
                                setUpdate({
                                  ...update,
                                  evidenceId: e.target.value,
                                })
                              }
                            >
                              <option value="">Select saved evidence</option>
                              {workbench?.evidence.map((v) => (
                                <option key={v.id} value={v.id}>
                                  Version {v.version} · {v.id}
                                </option>
                              ))}
                            </select>
                          </label>
                        )}
                        <label>
                          Request update note
                          <textarea
                            required
                            maxLength={4000}
                            value={update.note}
                            disabled={disabled}
                            onChange={(e) =>
                              setUpdate({ ...update, note: e.target.value })
                            }
                          />
                        </label>
                        <button
                          className="primary"
                          disabled={
                            disabled ||
                            !update.note.trim() ||
                            (update.status === "FULFILLED" &&
                              !update.evidenceId)
                          }
                        >
                          Save request update
                        </button>
                      </form>
                    )}
                  </article>
                ))
              ) : (
                <p>No evidence requests recorded.</p>
              )}
            </>
          )}
          {tab === "Reviewer conclusion" && (
            <>
              <p className="muted">
                A reviewer records a conclusion for selected evidence and
                completed investigations. This is a case review record, not
                payment approval or closure.
              </p>
              {isReviewer && (
                <form
                  onSubmit={(e) => {
                    if (selectedEvidence && independent)
                      save(
                        e,
                        "/reviewer-conclusions",
                        {
                          evidenceId: selectedEvidence.id,
                          evidenceHash: selectedEvidence.evidenceHash,
                          investigationIds: jobIds,
                          conclusion,
                        },
                        "conclusion",
                      );
                    else e.preventDefault();
                  }}
                >
                  <label>
                    Review evidence version
                    <select
                      value={evidenceId}
                      disabled={disabled}
                      onChange={(e) => {
                        setEvidenceId(e.target.value);
                        setJobIds([]);
                      }}
                    >
                      <option value="">Select saved evidence</option>
                      {workbench?.evidence.map((v) => (
                        <option key={v.id} value={v.id}>
                          Version {v.version} · {v.id}
                        </option>
                      ))}
                    </select>
                  </label>
                  <fieldset disabled={disabled}>
                    <legend>Completed investigations to review</legend>
                    {eligibleJobs.length ? (
                      eligibleJobs.map((j) => (
                        <label className="case-management-choice" key={j.id}>
                          <input
                            type="checkbox"
                            checked={jobIds.includes(j.id)}
                            disabled={
                              j.createdBy === user?.id ||
                              (jobIds.length >= 20 && !jobIds.includes(j.id))
                            }
                            onChange={(e) =>
                              setJobIds((current) =>
                                e.target.checked
                                  ? [...current, j.id]
                                  : current.filter((id) => id !== j.id),
                              )
                            }
                          />
                          <span>
                            {j.question}
                            <small>
                              Evidence version {j.evidenceVersion} · {j.id}
                              {j.createdBy === user?.id
                                ? " · Created by you; another reviewer is required"
                                : ""}
                            </small>
                          </span>
                        </label>
                      ))
                    ) : (
                      <p>No completed investigations for this version.</p>
                    )}
                  </fieldset>
                  {createdBy === user?.id && (
                    <p>A different reviewer must review a case you created.</p>
                  )}
                  <label>
                    Reviewer conclusion
                    <textarea
                      required
                      maxLength={4000}
                      value={conclusion}
                      disabled={disabled}
                      onChange={(e) => setConclusion(e.target.value)}
                    />
                  </label>
                  <button
                    className="primary"
                    disabled={disabled || !independent || !conclusion.trim()}
                  >
                    Record reviewer conclusion
                  </button>
                </form>
              )}
              {!isReviewer && canWrite && (
                <p>
                  A reviewer can record the conclusion after independent review.
                </p>
              )}
              {data.reviewerConclusions.length ? (
                data.reviewerConclusions.map((c) => (
                  <article key={c.id}>
                    <h3>Recorded · Evidence version {c.evidenceVersion}</h3>
                    <p>{c.conclusion}</p>
                    {meta(c)}
                    <small>{c.investigationIds.join(", ")}</small>
                  </article>
                ))
              ) : (
                <p>No reviewer conclusion recorded.</p>
              )}
            </>
          )}
          {tab === "Activity" &&
            (data.audit.length ? (
              <ol>
                {data.audit.map((a) => (
                  <li key={a.id}>
                    <strong>{human(a.action)}</strong>
                    <p>{a.detail}</p>
                    <small>
                      {a.actorName} · {a.occurredAt} · Version {a.version}
                    </small>
                  </li>
                ))}
              </ol>
            ) : (
              <p>No management activity recorded.</p>
            ))}
        </div>
      )}
    </section>
  );
}
