import { afterEach, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { CaseInvestigation } from "./CaseInvestigation";
import { setCsrfToken } from "./api";

const caseId = "CASE-QUEUE-SYNTHETIC";
const base = `/api/payment-cases/${caseId}`;
const user = { id: "fixture-author", role: "ANALYST" };
const evidence = {
  id: "EV-1",
  caseId,
  version: 1,
  evidenceHash: "a".repeat(64),
  sourceKind: "MANUAL",
  createdAt: "2026-01-01T00:00:00Z",
  warnings: [],
};
const original = {
  id: "JOB-1",
  caseId,
  evidenceId: evidence.id,
  evidenceHash: evidence.evidenceHash,
  evidenceVersion: 1,
  question: "Inspect the original source rows.",
  status: "RUNNING",
  phase: "GENERATING",
  queueVersion: "case-job-v1",
  createdAt: "2026-01-01T00:00:01Z",
  createdBy: user.id,
  cancellationRequested: false,
};
const response = (value: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(value), { status }));
function setup(
  options: {
    job?: Partial<typeof original>;
    queuedCancel?: boolean;
    cancelFailure?: boolean;
    wrongJob?: boolean;
    moreActive?: boolean;
    heldCancel?: boolean;
  } = {},
) {
  let job = { ...original, ...options.job };
  let cancelled = false;
  let cancelCount = 0;
  let finishCancel: (() => void) | undefined;
  const fetcher = vi.fn((url: string, request: RequestInit = {}) => {
    if (url === `${base}/workbench`)
      return response({
        caseId,
        latestEvidenceId: evidence.id,
        evidence: [evidence],
        investigations: [job],
        activeInvestigations: [job].filter((value) =>
          ["RUNNING", "QUEUED"].includes(value.status),
        ),
        ...(options.moreActive
          ? {
              activeInvestigationPage: {
                total: 26,
                limit: 25,
                nextCursor: "JOB-25",
              },
            }
          : {}),
        audit: [],
      });
    if (url === `${base}/evidence/EV-1/context`)
      return response({
        evidenceId: evidence.id,
        evidenceHash: evidence.evidenceHash,
        evidenceVersion: 1,
        guidanceHash: "b".repeat(64),
        nonEmptyRows: 1,
        documents: [],
        timeline: [],
        warnings: [],
      });
    if (url === `${base}/investigations/JOB-1/cancel`) {
      cancelCount++;
      if (options.cancelFailure && cancelCount === 1)
        return response(
          {
            code: "WORKER_UNAVAILABLE",
            message: "The response was not received.",
          },
          503,
        );
      const next = {
        ...job,
        cancellationRequested: true,
        phase: options.queuedCancel ? "CANCELLED" : "CANCELLING",
        status: options.queuedCancel ? "CANCELLED" : "RUNNING",
        ...(options.queuedCancel ? { finishedAt: "2026-01-01T00:00:07Z" } : {}),
      };
      if (options.wrongJob) return response({ ...next, id: "OTHER-JOB" });
      job = next;
      if (options.heldCancel)
        return new Promise<Response>((resolve) => {
          finishCancel = () => resolve(new Response(JSON.stringify(next)));
        });
      return response(next);
    }
    if (url === `${base}/investigations/JOB-1`) {
      if (cancelled) job = { ...job, status: "CANCELLED", phase: "CANCELLED" };
      return response({
        ...job,
        ...(cancelled ? { finishedAt: "2026-01-01T00:00:07Z" } : {}),
        documents: [],
      });
    }
    throw new Error(`Unexpected request: ${request.method ?? "GET"} ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
  return {
    fetcher,
    acknowledge: () => {
      cancelled = true;
    },
    finishCancel: () => finishCancel?.(),
  };
}
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
  setCsrfToken(null);
});

it("requests cancellation for the same saved question with CSRF, keeps polling until acknowledgement, and preserves its draft", async () => {
  const fixture = setup();
  setCsrfToken("fixture-csrf");
  render(<CaseInvestigation caseId={caseId} user={user} canWrite />);
  const cancel = await screen.findByRole("button", {
    name: "Cancel investigation",
  });
  // The draft is disabled during a run, but the pending question is never replaced by the cancellation action.
  fireEvent.change(screen.getByLabelText("Your case question"), {
    target: { value: "Keep my next question draft." },
  });
  fireEvent.click(cancel);
  await screen.findByRole("button", { name: "Cancellation requested" });
  expect(
    screen.getByRole("button", { name: "Cancellation requested" }),
  ).toBeDisabled();
  expect(screen.getByLabelText("Your case question")).toHaveValue(
    "Keep my next question draft.",
  );
  expect(
    screen.queryByText(/This investigation was cancelled/),
  ).not.toBeInTheDocument();
  expect(screen.getByRole("timer")).toBeInTheDocument();
  const requests = fixture.fetcher.mock.calls.filter(([url]) =>
    url.endsWith("/cancel"),
  );
  expect(requests).toHaveLength(1);
  expect(requests[0][1]).toMatchObject({ method: "POST", body: "{}" });
  expect(new Headers(requests[0][1]?.headers).get("X-CSRF-Token")).toBe(
    "fixture-csrf",
  );
  fixture.acknowledge();
  fireEvent.click(screen.getByRole("button", { name: "Refresh workbench" }));
  await screen.findByText(/This investigation was cancelled/);
  expect(
    screen.queryByRole("button", { name: "Cancel investigation" }),
  ).not.toBeInTheDocument();
  expect(screen.getByLabelText("Your case question")).toHaveValue(
    "Keep my next question draft.",
  );
  expect(screen.queryByRole("timer")).not.toBeInTheDocument();
});

it("handles immediate queued cancellation without claiming a completed answer", async () => {
  setup({ job: { status: "QUEUED", phase: "QUEUED" }, queuedCancel: true });
  render(<CaseInvestigation caseId={caseId} user={user} canWrite />);
  fireEvent.click(
    await screen.findByRole("button", { name: "Cancel investigation" }),
  );
  await screen.findByText(/This investigation was cancelled/);
  expect(screen.queryByText("Model-generated answer")).not.toBeInTheDocument();
});

it("retries an uncertain cancellation against the same job without submitting another question", async () => {
  const { fetcher } = setup({ cancelFailure: true });
  render(<CaseInvestigation caseId={caseId} user={user} canWrite />);
  fireEvent.click(
    await screen.findByRole("button", { name: "Cancel investigation" }),
  );
  await screen.findByText(/Cancellation is unconfirmed/);
  expect(
    screen.queryByText(/This investigation was cancelled/),
  ).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Retry cancellation" }));
  await screen.findByRole("button", { name: "Cancellation requested" });
  expect(
    fetcher.mock.calls
      .filter(([, request]) => request?.method === "POST")
      .map(([url]) => url),
  ).toEqual([
    `${base}/investigations/JOB-1/cancel`,
    `${base}/investigations/JOB-1/cancel`,
  ]);
});

it("rejects a cancellation response that belongs to another job", async () => {
  setup({ wrongJob: true });
  render(<CaseInvestigation caseId={caseId} user={user} canWrite />);
  fireEvent.click(
    await screen.findByRole("button", { name: "Cancel investigation" }),
  );
  await screen.findByText(/cancellation response does not match/);
  expect(
    screen.queryByText(/This investigation was cancelled/),
  ).not.toBeInTheDocument();
});

it.each([
  ["ANALYST", "other-author", false],
  ["REVIEWER", "other-author", true],
  ["VIEWER", user.id, false],
  ["ADMIN", user.id, false],
])(
  "only exposes cancellation to an eligible %s account",
  async (role, id, visible) => {
    setup();
    render(
      <CaseInvestigation
        caseId={caseId}
        user={{ id: String(id), role: String(role) }}
        canWrite={role === "ANALYST" || role === "REVIEWER"}
      />,
    );
    await screen.findByRole("region", { name: "Selected investigation" });
    expect(
      !!screen.queryByRole("button", { name: "Cancel investigation" }),
    ).toBe(visible);
  },
);

it("keeps historical jobs readable without exposing a cancellation endpoint they do not support", async () => {
  setup({ job: { queueVersion: undefined } });
  render(<CaseInvestigation caseId={caseId} user={user} canWrite />);
  await screen.findByRole("region", { name: "Selected investigation" });
  expect(
    screen.queryByRole("button", { name: "Cancel investigation" }),
  ).not.toBeInTheDocument();
});

it.each([
  ["PREPARING", "Preparing evidence and guidance"],
  ["PREFLIGHT", "Checking model context"],
  ["SUBMITTING", "Submitting to the local model"],
  ["GENERATING", "Generating an answer"],
  ["WAITING", "Waiting for the local model worker"],
])(
  "shows the saved %s phase without implying completion",
  async (phase, text) => {
    setup({ job: { phase } });
    render(<CaseInvestigation caseId={caseId} user={user} canWrite />);
    await screen.findByText(text);
    expect(
      screen.queryByText("Model-generated answer"),
    ).not.toBeInTheDocument();
  },
);

it("shows active-history continuation outside the list and opens it on request", async () => {
  const { fetcher } = setup({ moreActive: true });
  render(<CaseInvestigation caseId={caseId} user={user} canWrite />);
  fireEvent.click(
    await screen.findByRole("button", { name: "View active investigations" }),
  );
  await screen.findByRole("heading", { name: "Saved investigations" });
  expect(
    screen.getByRole("button", { name: "Load more active investigations" }),
  ).toBeInTheDocument();
  expect(fetcher.mock.calls.some(([url]) => url.includes("cursor="))).toBe(
    false,
  );
});

it("ignores a late cancellation reply after navigating to a different case", async () => {
  const { finishCancel } = setup({ heldCancel: true });
  const view = render(
    <CaseInvestigation caseId={caseId} user={user} canWrite />,
  );
  fireEvent.click(
    await screen.findByRole("button", { name: "Cancel investigation" }),
  );
  await screen.findByRole("button", { name: "Requesting cancellation…" });
  view.unmount();
  await act(async () => finishCancel());
  expect(screen.queryByText("Cancellation requested")).not.toBeInTheDocument();
});

it("keeps saved jobs in timestamp order when Java Instant omits fractions or includes nanoseconds", async () => {
  const jobs = [
    {
      ...original,
      id: "WHOLE",
      question: "Whole second question",
      status: "FAILED",
      phase: "FAILED",
      createdAt: "2026-01-01T00:00:00Z",
    },
    {
      ...original,
      id: "NANO",
      question: "One nanosecond later",
      status: "FAILED",
      phase: "FAILED",
      createdAt: "2026-01-01T00:00:00.000000001Z",
    },
    {
      ...original,
      id: "FRACTION",
      question: "One tenth second later",
      status: "FAILED",
      phase: "FAILED",
      createdAt: "2026-01-01T00:00:00.1Z",
    },
  ];
  vi.stubGlobal(
    "fetch",
    vi.fn((url: string) => {
      if (url.endsWith("/workbench"))
        return response({
          caseId,
          latestEvidenceId: evidence.id,
          evidence: [evidence],
          investigations: jobs,
          audit: [],
        });
      if (url.endsWith("/context"))
        return response({
          evidenceId: evidence.id,
          evidenceHash: evidence.evidenceHash,
          evidenceVersion: 1,
          guidanceHash: "b".repeat(64),
          nonEmptyRows: 1,
          documents: [],
          timeline: [],
          warnings: [],
        });
      const selected = jobs.find((job) =>
        url.endsWith(`/investigations/${job.id}`),
      );
      if (selected) return response({ ...selected, documents: [] });
      throw new Error(url);
    }),
  );
  render(
    <CaseInvestigation
      caseId={caseId}
      user={user}
      canWrite
      presentation="qa"
    />,
  );
  await screen.findByRole("heading", { name: "Saved investigations" });
  const list = screen.getByRole("button", {
    name: /Whole second question/,
  }).parentElement!;
  expect(
    Array.from(list.children).map(
      (node) => node.querySelector("strong")?.textContent,
    ),
  ).toEqual([
    "One tenth second later",
    "One nanosecond later",
    "Whole second question",
  ]);
});
