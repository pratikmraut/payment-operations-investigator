export type InvestigationTiming = {
  preparationMs: number | null;
  queueMs: number | null;
  processingMs: number | null;
  totalMs: number | null;
  totalBasis: "request-received" | "job-created";
};

export type TimedJob = {
  id: string;
  question: string;
  status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED";
  createdAt: string;
  requestedAt?: string | null;
  startedAt?: string;
  finishedAt?: string;
  timing?: InvestigationTiming;
};

export type BrowserRun = {
  startedAt: number;
  stoppedAt?: number;
  jobId?: string;
  question: string;
  phase:
    | "preparing"
    | "queued"
    | "running"
    | "completed"
    | "failed"
    | "rejected"
    | "unknown";
};

export const terminalJob = (job: TimedJob) =>
  job.status === "COMPLETED" || job.status === "FAILED";

export function observeBrowserRun(
  run: BrowserRun,
  job: TimedJob,
  now: number,
): BrowserRun {
  if (run.stoppedAt !== undefined || (run.jobId && run.jobId !== job.id))
    return run;
  return {
    ...run,
    jobId: job.id,
    phase: job.status.toLowerCase() as BrowserRun["phase"],
    ...(terminalJob(job) ? { stoppedAt: now } : {}),
  };
}

export function elapsedMilliseconds(
  startedAt: number,
  now: number,
  initialMs = 0,
) {
  return Math.max(0, initialMs + Math.max(0, now - startedAt));
}

export function formatElapsed(milliseconds: number) {
  const seconds = Math.floor(Math.max(0, milliseconds) / 1000);
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  const pair = (value: number) => String(value).padStart(2, "0");
  return hours > 0
    ? `${pair(hours)}:${pair(minutes % 60)}:${pair(seconds % 60)}`
    : `${pair(minutes)}:${pair(seconds % 60)}`;
}

function timestamp(value: string | null | undefined) {
  const parsed = value ? Date.parse(value) : NaN;
  return Number.isFinite(parsed) ? parsed : null;
}

function interval(start: string | null | undefined, end: string | undefined) {
  const from = timestamp(start);
  const to = timestamp(end);
  return from !== null && to !== null && to >= from ? to - from : null;
}

export function savedTiming(job: TimedJob): InvestigationTiming {
  if (job.timing) return job.timing;
  const hasRequest = timestamp(job.requestedAt) !== null;
  return {
    preparationMs: hasRequest ? interval(job.requestedAt, job.createdAt) : null,
    queueMs: interval(
      job.createdAt,
      job.startedAt ?? (terminalJob(job) ? job.finishedAt : undefined),
    ),
    processingMs: interval(job.startedAt, job.finishedAt),
    totalMs: terminalJob(job)
      ? interval(hasRequest ? job.requestedAt : job.createdAt, job.finishedAt)
      : null,
    totalBasis: hasRequest ? "request-received" : "job-created",
  };
}

export function pendingEstimate(job: TimedJob, wallNow: number) {
  const request = timestamp(job.requestedAt);
  const origin = request ?? timestamp(job.createdAt);
  return {
    initialMs: origin === null || origin > wallNow ? null : wallNow - origin,
    basis: request === null ? "job created" : "server request received",
  };
}

export function validTiming(value: unknown): value is InvestigationTiming {
  if (!value || typeof value !== "object") return false;
  const timing = value as InvestigationTiming;
  return (
    ["request-received", "job-created"].includes(timing.totalBasis) &&
    [
      timing.preparationMs,
      timing.queueMs,
      timing.processingMs,
      timing.totalMs,
    ].every(
      (duration) =>
        duration === null ||
        (typeof duration === "number" &&
          Number.isSafeInteger(duration) &&
          duration >= 0),
    )
  );
}
