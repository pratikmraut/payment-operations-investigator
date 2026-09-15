import { useEffect, useRef, useState, type FormEvent } from "react";
import { Archive, RotateCcw, Trash2 } from "lucide-react";
import { api, ApiError, human } from "./api";
import { navigateTo } from "./routing";
import type { User } from "./types";

export type CaseLifecycleState = "ACTIVE" | "ARCHIVED";
export type Lifecycle = {
  caseId: string;
  caseNumber: string;
  state: CaseLifecycleState;
  version: number;
  canArchive: boolean;
  canRestore: boolean;
  canDelete: boolean;
  activeInvestigationCount: number;
  audit: {
    id: string;
    action: string;
    reason: string;
    occurredAt: string;
    actor: string;
    actorName: string;
  }[];
};
export const CASE_LIFECYCLE_CHANGED = "payment-case-lifecycle-changed";
function validate(value: Lifecycle, caseId: string) {
  if (
    !value ||
    value.caseId !== caseId ||
    typeof value.caseNumber !== "string" ||
    !["ACTIVE", "ARCHIVED"].includes(value.state) ||
    !Number.isSafeInteger(value.version) ||
    value.version < 0 ||
    ![value.canArchive, value.canRestore, value.canDelete].every(
      (v) => typeof v === "boolean",
    ) ||
    !Number.isSafeInteger(value.activeInvestigationCount) ||
    value.activeInvestigationCount < 0 ||
    !Array.isArray(value.audit) ||
    !value.audit.every(
      (v) =>
        v &&
        [v.id, v.action, v.reason, v.occurredAt, v.actor, v.actorName].every(
          (field) => typeof field === "string",
        ),
    )
  )
    throw new Error(
      "The case lifecycle response could not be read for this case. Refresh to try again.",
    );
}
type Attempt = {
  action: "archive" | "restore" | "permanent-delete";
  body: Record<string, unknown>;
  key: string;
};
export function CaseLifecycle({
  caseId,
  user,
  onChanged,
  visible = true,
}: {
  caseId: string;
  user?: User;
  onChanged?: (state: CaseLifecycleState) => void;
  visible?: boolean;
}) {
  const [data, setData] = useState<Lifecycle | null>(null);
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [reason, setReason] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [conflict, setConflict] = useState(false);
  const [uncertain, setUncertain] = useState<Attempt | null>(null);
  const [success, setSuccess] = useState("");
  const operation = useRef<AbortController | null>(null);
  const base = `/payment-cases/${encodeURIComponent(caseId)}`;
  const role = user?.role.toUpperCase();
  const canManage = !!role && ["ANALYST", "REVIEWER", "ADMIN"].includes(role);
  useEffect(() => () => operation.current?.abort(), []);
  useEffect(() => {
    // Reopening the tab must not replace an action whose outcome is unresolved.
    if (!visible || busy || uncertain) return;
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), 30000);
    let mounted = true;
    setLoading(true);
    setError(null);
    api<Lifecycle>(`${base}/lifecycle`, { signal: controller.signal })
      .then((value) => {
        if (!mounted) return;
        validate(value, caseId);
        setData(value);
        if (value.state !== "ARCHIVED") {
          setDeleting(false);
          setConfirmation("");
        }
        onChanged?.(value.state);
        setConflict(false);
      })
      .catch((failure: Error) => {
        if (mounted) {
          setConflict(true);
          setError(
            controller.signal.aborted
              ? new Error(
                  "Loading case lifecycle took too long. Refresh to retry.",
                )
              : failure,
          );
        }
      })
      .finally(() => {
        clearTimeout(timer);
        if (mounted) setLoading(false);
      });
    return () => {
      mounted = false;
      clearTimeout(timer);
      controller.abort();
    };
  }, [base, caseId, revision, visible]);
  async function perform(attempt: Attempt) {
    if (busy) return;
    const controller = new AbortController();
    operation.current = controller;
    const timer = window.setTimeout(() => controller.abort(), 30000);
    setBusy(true);
    setError(null);
    setSuccess("");
    try {
      const value = await api<Lifecycle & { state: string }>(
        `${base}/${attempt.action}`,
        {
          method: "POST",
          headers: { "Idempotency-Key": attempt.key },
          body: JSON.stringify(attempt.body),
          signal: controller.signal,
        },
      );
      if (attempt.action === "permanent-delete") {
        if (
          value.caseId !== caseId ||
          (value.state as string) !== "DELETED" ||
          value.caseNumber !== data?.caseNumber
        )
          throw new Error(
            "The delete outcome could not be confirmed. Retry the same request.",
          );
        window.dispatchEvent(
          new CustomEvent(CASE_LIFECYCLE_CHANGED, {
            detail: { caseId, state: "DELETED" },
          }),
        );
        navigateTo("/cases");
        return;
      }
      validate(value, caseId);
      setData(value);
      setReason("");
      setConfirmation("");
      setDeleting(false);
      setUncertain(null);
      setConflict(false);
      setSuccess(
        value.state === "ARCHIVED"
          ? `Case ${value.caseNumber} archived. Its evidence and answers remain available.`
          : `Case ${value.caseNumber} restored to the active queue.`,
      );
      onChanged?.(value.state);
      window.dispatchEvent(
        new CustomEvent(CASE_LIFECYCLE_CHANGED, {
          detail: { caseId, state: value.state },
        }),
      );
    } catch (failure) {
      const problem = failure as Error;
      setError(
        controller.signal.aborted
          ? new Error("The request timed out. Its outcome is not confirmed.")
          : problem,
      );
      if (
        problem instanceof ApiError &&
        problem.status >= 400 &&
        problem.status < 500
      ) {
        setUncertain(null);
        setConflict(problem.status === 409);
      } else setUncertain(attempt);
    } finally {
      clearTimeout(timer);
      setBusy(false);
    }
  }
  const disabled = loading || busy || conflict || !!uncertain || !data;
  function submit(event: FormEvent) {
    event.preventDefault();
    if (
      disabled ||
      !data ||
      !reason.trim() ||
      !canManage ||
      data.activeInvestigationCount > 0
    )
      return;
    const action = deleting
      ? "permanent-delete"
      : data.state === "ACTIVE"
        ? "archive"
        : "restore";
    if (
      (action === "archive" && !data.canArchive) ||
      (action === "restore" && !data.canRestore) ||
      (action === "permanent-delete" &&
        (role !== "ADMIN" ||
          !data.canDelete ||
          confirmation !== data.caseNumber))
    )
      return;
    void perform({
      action,
      key: crypto.randomUUID(),
      body: {
        expectedVersion: data.version,
        reason: reason.trim(),
        ...(deleting ? { confirmation } : {}),
      },
    });
  }
  return (
    <div className="case-lifecycle">
      <div className="section-title">
        <h3>Archive or restore this case</h3>
        <button
          className="secondary"
          disabled={busy || loading || !!uncertain}
          onClick={() => setRevision((v) => v + 1)}
        >
          Refresh lifecycle
        </button>
      </div>
      <p>
        Archive removes the case from the active queue and keeps its number,
        evidence, answers, notes and reports. Restore it to continue
        investigation.
      </p>
      {loading && <p role="status">Loading case lifecycle…</p>}
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
          The case changed. Refresh lifecycle, review its current state, then
          submit again. Your reason is preserved.
        </p>
      )}
      {uncertain && (
        <div className="notice neutral">
          <div>
            <p>
              The outcome is unconfirmed. Retry the same request before making
              another change.
            </p>
            <button
              className="secondary"
              disabled={busy}
              onClick={() => void perform(uncertain)}
            >
              Retry pending action
            </button>
          </div>
        </div>
      )}
      {success && <p role="status">{success}</p>}
      {data && (
        <>
          <p>
            Case <strong>{data.caseNumber}</strong> ·{" "}
            <span className="payment-source-label">{human(data.state)}</span>
          </p>
          {data.activeInvestigationCount > 0 && (
            <p className="notice neutral">
              {data.activeInvestigationCount} investigation(s) are queued or
              running. Wait for them to finish, then refresh lifecycle.
            </p>
          )}
          {!canManage && (
            <p className="muted">
              An analyst, reviewer or administrator can archive or restore this
              case.
            </p>
          )}
          {canManage && (
            <form onSubmit={submit}>
              {deleting && (
                <div className="notice danger">
                  <p>
                    Permanent deletion removes this case and its saved evidence,
                    answers, notes and reports. This cannot be undone. Its case
                    number stays reserved.
                  </p>
                </div>
              )}
              <label>
                Reason for{" "}
                {deleting
                  ? "permanent deletion"
                  : data.state === "ACTIVE"
                    ? "archiving"
                    : "restoring"}
                <textarea
                  value={reason}
                  maxLength={2000}
                  required
                  disabled={disabled}
                  onChange={(e) => setReason(e.target.value)}
                  placeholder="Record why this case should be changed"
                />
              </label>
              {deleting && (
                <label>
                  Type case number {data.caseNumber} to confirm
                  <input
                    value={confirmation}
                    autoComplete="off"
                    disabled={disabled}
                    onChange={(e) => setConfirmation(e.target.value)}
                  />
                </label>
              )}
              <button
                className={
                  deleting ? "secondary case-delete-button" : "primary"
                }
                disabled={
                  disabled ||
                  !reason.trim() ||
                  data.activeInvestigationCount > 0 ||
                  (deleting
                    ? !data.canDelete || confirmation !== data.caseNumber
                    : data.state === "ACTIVE"
                      ? !data.canArchive
                      : !data.canRestore)
                }
              >
                {deleting ? (
                  <Trash2 size={15} />
                ) : data.state === "ACTIVE" ? (
                  <Archive size={15} />
                ) : (
                  <RotateCcw size={15} />
                )}
                {busy
                  ? "Saving…"
                  : deleting
                    ? "Permanently delete case"
                    : data.state === "ACTIVE"
                      ? "Archive case"
                      : "Restore case"}
              </button>
              {role === "ADMIN" && data.state === "ARCHIVED" && (
                <button
                  type="button"
                  className="text-button case-delete-button"
                  disabled={disabled || data.activeInvestigationCount > 0}
                  onClick={() => {
                    setDeleting((v) => !v);
                    setReason("");
                    setConfirmation("");
                  }}
                >
                  {deleting
                    ? "Cancel permanent deletion"
                    : "Delete an unwanted test or mistaken case permanently"}
                </button>
              )}
            </form>
          )}
          <h3>Lifecycle activity</h3>
          {data.audit.length ? (
            data.audit.map((entry) => (
              <article key={entry.id}>
                <strong>{human(entry.action)}</strong>
                <p>{entry.reason}</p>
                <small>
                  {entry.actorName} · {entry.occurredAt}
                </small>
              </article>
            ))
          ) : (
            <p className="muted">No archive or restore actions recorded.</p>
          )}
        </>
      )}
    </div>
  );
}
