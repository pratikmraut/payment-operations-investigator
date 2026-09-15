import { afterEach, describe, expect, it, vi } from "vitest";
import { act, render, screen } from "@testing-library/react";
import {
  InvestigationTimer,
  SavedInvestigationTiming,
} from "./InvestigationTimer";
import {
  formatElapsed,
  observeBrowserRun,
  pendingEstimate,
  savedTiming,
  validTiming,
  type BrowserRun,
  type TimedJob,
} from "./investigationTiming";

const job: TimedJob = {
  id: "TIMING-FIXTURE",
  question: "What do these source records show?",
  status: "RUNNING",
  requestedAt: "2026-09-15T10:00:00Z",
  createdAt: "2026-09-15T10:00:10Z",
  startedAt: "2026-09-15T10:00:15Z",
};
afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("investigation duration lifecycle", () => {
  it("includes preparation, keeps its origin across status changes and stops precisely at the first terminal response", () => {
    vi.useFakeTimers();
    let now = 100;
    vi.spyOn(performance, "now").mockImplementation(() => now);
    let run: BrowserRun = {
      startedAt: now,
      phase: "preparing",
      question: job.question,
    };
    const view = render(
      <InvestigationTimer run={run} statusUnavailable={false} />,
    );
    const tick = (value: number) =>
      act(() => {
        now = value;
        vi.advanceTimersByTime(1000);
      });
    expect(screen.getByRole("timer")).toHaveTextContent("00:00");
    tick(14100);
    expect(screen.getByRole("timer")).toHaveTextContent("00:14");
    run = observeBrowserRun(run, { ...job, status: "QUEUED" }, now);
    view.rerender(<InvestigationTimer run={run} statusUnavailable={false} />);
    expect(screen.getByRole("status")).toHaveTextContent("Queued");
    tick(21100);
    run = observeBrowserRun(run, job, now);
    view.rerender(<InvestigationTimer run={run} statusUnavailable={false} />);
    expect(screen.getByRole("timer")).toHaveTextContent("00:21");
    expect(screen.getByRole("status")).toHaveTextContent("Running");
    now = 32600;
    run = observeBrowserRun(run, { ...job, status: "COMPLETED" }, now);
    view.rerender(<InvestigationTimer run={run} statusUnavailable={false} />);
    expect(screen.getByRole("timer")).toHaveTextContent("00:32");
    expect(screen.getByText("Browser click-to-response")).toBeVisible();
    expect(vi.getTimerCount()).toBe(0);
    tick(95100);
    expect(screen.getByRole("timer")).toHaveTextContent("00:32");
    expect(observeBrowserRun(run, { ...job, status: "COMPLETED" }, now)).toBe(
      run,
    );
  });

  it("keeps waiting through unknown status, stops on failure and cleans up a new run on unmount", () => {
    vi.useFakeTimers();
    let now = 0;
    vi.spyOn(performance, "now").mockImplementation(() => now);
    const run: BrowserRun = {
      startedAt: now,
      jobId: job.id,
      phase: "running",
      question: job.question,
    };
    const view = render(<InvestigationTimer run={run} statusUnavailable />);
    act(() => {
      now = 65000;
      vi.advanceTimersByTime(1000);
    });
    expect(screen.getByRole("timer")).toHaveTextContent("01:05");
    expect(screen.getByRole("status")).toHaveTextContent(
      "Status unavailable · still waiting",
    );
    expect(
      observeBrowserRun(
        run,
        { ...job, id: "OTHER-JOB", status: "COMPLETED" },
        now,
      ),
    ).toBe(run);
    const failed = observeBrowserRun(run, { ...job, status: "FAILED" }, now);
    view.rerender(
      <InvestigationTimer run={failed} statusUnavailable={false} />,
    );
    expect(screen.getByRole("status")).toHaveTextContent(
      "Failed · no completed answer",
    );
    expect(vi.getTimerCount()).toBe(0);
    now = 70000;
    view.rerender(
      <InvestigationTimer
        run={{ startedAt: now, phase: "preparing", question: "New question" }}
        statusUnavailable={false}
      />,
    );
    expect(screen.getByRole("timer")).toHaveTextContent("00:00");
    expect(vi.getTimerCount()).toBe(1);
    view.unmount();
    expect(vi.getTimerCount()).toBe(0);
  });

  it("uses a fixed server estimate after refresh and ignores subsequent browser wall-clock adjustments", () => {
    vi.useFakeTimers();
    vi.setSystemTime("2026-09-15T10:00:30Z");
    let now = 0;
    vi.spyOn(performance, "now").mockImplementation(() => now);
    const view = render(
      <InvestigationTimer
        run={null}
        pendingJob={job}
        statusUnavailable={false}
      />,
    );
    expect(screen.getByRole("timer")).toHaveTextContent("00:30");
    expect(
      screen.getByText(/Estimated since server request received/),
    ).toBeVisible();
    vi.setSystemTime("2026-09-15T01:00:00Z");
    act(() => {
      now = 10000;
      vi.advanceTimersByTime(1000);
    });
    view.rerender(
      <InvestigationTimer
        run={null}
        pendingJob={{ ...job }}
        statusUnavailable
      />,
    );
    expect(screen.getByRole("timer")).toHaveTextContent("00:40");
    view.rerender(
      <InvestigationTimer
        run={null}
        pendingJob={{ ...job, requestedAt: undefined, createdAt: "unreadable" }}
        statusUnavailable
      />,
    );
    expect(screen.queryByRole("timer")).not.toBeInTheDocument();
    expect(screen.getByText("Unavailable")).toBeVisible();
    expect(vi.getTimerCount()).toBe(0);
  });

  it("renders saved totals independently of model duration and labels legacy timings and failures", () => {
    const finished = {
      ...job,
      status: "COMPLETED" as const,
      finishedAt: "2026-09-15T10:02:00Z",
    };
    expect(savedTiming(finished)).toEqual({
      preparationMs: 10000,
      queueMs: 5000,
      processingMs: 105000,
      totalMs: 120000,
      totalBasis: "request-received",
    });
    const view = render(<SavedInvestigationTiming job={finished} breakdown />);
    expect(screen.getByText(/Server total:/)).toHaveTextContent("02:00");
    expect(
      screen.getByText("Preparation 00:10 · Queue 00:05 · Processing 01:45"),
    ).toBeVisible();
    view.rerender(
      <SavedInvestigationTiming
        job={{ ...finished, requestedAt: undefined, status: "FAILED" }}
        breakdown
      />,
    );
    expect(
      screen.getByText(/Server elapsed since job created:/),
    ).toHaveTextContent("01:50");
    expect(
      screen.getByText("Request preparation was not recorded for this job."),
    ).toBeVisible();
    view.rerender(
      <SavedInvestigationTiming
        job={{ ...finished, requestedAt: undefined, finishedAt: undefined }}
      />,
    );
    expect(
      screen.getByText(/Server elapsed since job created:/),
    ).toHaveTextContent("unavailable");
  });

  it("does not fabricate negative, invalid, incomplete or future durations", () => {
    const earlier = "2026-09-15T09:00:00Z";
    expect(
      savedTiming({ ...job, status: "FAILED", finishedAt: earlier }).totalMs,
    ).toBeNull();
    expect(savedTiming(job).totalMs).toBeNull();
    expect(pendingEstimate(job, Date.parse(earlier)).initialMs).toBeNull();
    expect(
      pendingEstimate(
        { ...job, requestedAt: null },
        Date.parse("2026-09-15T10:00:20Z"),
      ),
    ).toEqual({ initialMs: 10000, basis: "job created" });
    expect(
      validTiming({
        preparationMs: null,
        queueMs: -1,
        processingMs: null,
        totalMs: null,
        totalBasis: "job-created",
      }),
    ).toBe(false);
    expect(
      validTiming({
        preparationMs: null,
        queueMs: 0,
        processingMs: null,
        totalMs: null,
        totalBasis: "job-created",
      }),
    ).toBe(true);
    expect(formatElapsed(3600000)).toBe("01:00:00");
    expect(formatElapsed(-1000)).toBe("00:00");
  });
});
