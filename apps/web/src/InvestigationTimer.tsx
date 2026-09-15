import { useEffect, useMemo, useState } from "react";
import { Clock3 } from "lucide-react";
import {
  elapsedMilliseconds,
  formatElapsed,
  pendingEstimate,
  savedTiming,
  terminalJob,
  type BrowserRun,
  type TimedJob,
} from "./investigationTiming";

function Stopwatch({
  startedAt,
  stoppedAt,
  initialMs = 0,
}: {
  startedAt: number;
  stoppedAt?: number;
  initialMs?: number;
}) {
  const [now, setNow] = useState(() => performance.now());
  useEffect(() => {
    if (stoppedAt !== undefined) return;
    setNow(performance.now());
    const timer = window.setInterval(() => setNow(performance.now()), 1000);
    return () => window.clearInterval(timer);
  }, [startedAt, stoppedAt]);
  return (
    <time role="timer" aria-live="off">
      {formatElapsed(
        elapsedMilliseconds(startedAt, stoppedAt ?? now, initialMs),
      )}
    </time>
  );
}

export function InvestigationTimer({
  run,
  pendingJob,
  statusUnavailable,
}: {
  run: BrowserRun | null;
  pendingJob?: TimedJob;
  statusUnavailable: boolean;
}) {
  const estimate = useMemo(
    () =>
      pendingJob
        ? {
            ...pendingEstimate(pendingJob, Date.now()),
            startedAt: performance.now(),
          }
        : null,
    [pendingJob?.id, pendingJob?.requestedAt, pendingJob?.createdAt],
  );
  if (!run && !pendingJob) return null;
  const stopped = run?.stoppedAt !== undefined;
  const unknown = !stopped && (statusUnavailable || run?.phase === "unknown");
  const phase = unknown
    ? "Status unavailable · still waiting"
    : run?.phase === "preparing"
      ? "Preparing and saving request…"
      : run?.phase === "completed"
        ? "Completed · status received"
        : run?.phase === "rejected"
          ? "Request rejected"
          : run?.phase === "failed"
            ? "Failed · no completed answer"
            : run?.phase === "queued" ||
                (!run && pendingJob?.status === "QUEUED")
              ? "Queued · waiting to start"
              : "Running investigation…";
  return (
    <div
      className="case-investigation-timer"
      aria-label="Investigation elapsed time"
    >
      <div className="case-investigation-timer-heading">
        <span>
          <Clock3 size={15} aria-hidden="true" />
          {run
            ? stopped
              ? "Browser click-to-response"
              : "Browser elapsed"
            : "Approximate elapsed"}
        </span>
        {run ? (
          <Stopwatch startedAt={run.startedAt} stoppedAt={run.stoppedAt} />
        ) : estimate?.initialMs !== null && estimate ? (
          <Stopwatch
            startedAt={estimate.startedAt}
            initialMs={estimate.initialMs}
          />
        ) : (
          <span>Unavailable</span>
        )}
      </div>
      <p role="status">{phase}</p>
      <small className="case-investigation-timer-question">
        {run?.question ?? pendingJob?.question}
      </small>
      <small>
        {run
          ? "Measured from your click; includes request preparation, network and status polling. Server and model durations are shown separately."
          : `Estimated since ${estimate?.basis}; browser and server clocks may differ. Original click time is unavailable.`}
      </small>
      {!stopped && (
        <small>
          {unknown
            ? "Completion is unconfirmed. Refresh status to check again."
            : "Leaving this page does not cancel a saved server job."}
        </small>
      )}
    </div>
  );
}

export function SavedInvestigationTiming({
  job,
  breakdown = false,
}: {
  job: TimedJob;
  breakdown?: boolean;
}) {
  const timing = savedTiming(job);
  const label =
    timing.totalBasis === "request-received"
      ? "Server total"
      : "Server elapsed since job created";
  if (!terminalJob(job)) return null;
  const stages = [
    ["Preparation", timing.preparationMs],
    ["Queue", timing.queueMs],
    ["Processing", timing.processingMs],
  ] as const;
  return (
    <div className="case-investigation-saved-timing">
      <span>
        {label}:{" "}
        <strong>
          {timing.totalMs === null
            ? "unavailable"
            : formatElapsed(timing.totalMs)}
        </strong>
      </span>
      {breakdown && (
        <>
          <small>
            {timing.totalBasis === "request-received"
              ? "From server request receipt to the saved completion or failure."
              : "Request preparation was not recorded for this job."}
          </small>
          {stages.some(([, value]) => value !== null) && (
            <small>
              {stages
                .filter(([, value]) => value !== null)
                .map(([label, value]) => `${label} ${formatElapsed(value!)}`)
                .join(" · ")}
            </small>
          )}
        </>
      )}
    </div>
  );
}
