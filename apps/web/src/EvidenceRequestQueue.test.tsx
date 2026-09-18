import { afterEach, expect, it, vi } from "vitest";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { EvidenceRequestQueue } from "./EvidenceRequestQueue";
import userEvent from "@testing-library/user-event";
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
it("rejects malformed successful responses without crashing the case queue", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response(JSON.stringify({ items: null }))),
  );
  const { container } = render(<EvidenceRequestQueue revision={0} />);
  await userEvent.click(container.querySelector("summary")!);
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "invalid follow-up data",
  );
  expect(
    screen.getByRole("button", { name: "Refresh follow-ups" }),
  ).toBeEnabled();
});
it("bounds a stalled read and allows retry even when the transport never resolves", async () => {
  let signal: AbortSignal | null | undefined;
  vi.stubGlobal(
    "fetch",
    vi.fn((_url, options: RequestInit) => {
      signal = options.signal;
      return new Promise(() => {});
    }),
  );
  const { container } = render(<EvidenceRequestQueue revision={0} />);
  vi.useFakeTimers();
  const details = container.querySelector("details")!;
  details.open = true;
  fireEvent(details, new Event("toggle"));
  expect(
    screen.getByRole("button", { name: "Refresh follow-ups" }),
  ).toBeDisabled();
  await act(async () => {
    await vi.advanceTimersByTimeAsync(30000);
  });
  expect(signal?.aborted).toBe(true);
  expect(screen.getByRole("alert")).toHaveTextContent("timed out");
  expect(
    screen.getByRole("button", { name: "Refresh follow-ups" }),
  ).toBeEnabled();
});
it("only reads follow-ups when opened, displays overdue reminders and changes the assigned view", async () => {
  const fetcher = vi.fn(
    async (_url: string, _options?: RequestInit) =>
      new Response(
        JSON.stringify({
          items: [
            {
              caseId: "case-a",
              caseNumber: "2026091600001",
              reference: "SYNTHETIC-REF",
              requestId: "request-a",
              title: "Collect receipt",
              caseStatus: "AWAITING_EVIDENCE",
              assignee: null,
              dueDate: "2026-09-15",
              daysOverdue: 1,
            },
          ],
          total: 1,
          openCount: 1,
          overdueCount: 1,
          mineCount: 0,
          timezone: "Asia/Kolkata",
          today: "2026-09-16",
          hasMore: false,
        }),
      ),
  );
  vi.stubGlobal("fetch", fetcher);
  const { container } = render(<EvidenceRequestQueue revision={0} />);
  expect(fetcher).not.toHaveBeenCalled();
  await userEvent.click(container.querySelector("summary")!);
  expect(await screen.findByText("Collect receipt")).toBeInTheDocument();
  expect(screen.getByText(/1 evidence request is overdue/)).toBeInTheDocument();
  expect(screen.getByText("Unassigned")).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "2026091600001" })).toHaveAttribute(
    "href",
    "/payment-cases/2026091600001",
  );
  fireEvent.change(screen.getByLabelText("Follow-up view"), {
    target: { value: "MINE" },
  });
  await waitFor(() =>
    expect(fetcher.mock.calls.at(-1)?.[0]).toContain("view=MINE&offset=0"),
  );
  for (const call of fetcher.mock.calls)
    expect((call as unknown as [string, RequestInit])[1]?.method ?? "GET").toBe(
      "GET",
    );
});
it("keeps a failed follow-up read visible and retryable", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(
      async () =>
        new Response(
          JSON.stringify({ code: "FAILED", message: "Read unavailable" }),
          { status: 503 },
        ),
    ),
  );
  const { container } = render(<EvidenceRequestQueue revision={0} />);
  await userEvent.click(container.querySelector("summary")!);
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "Read unavailable",
  );
  expect(
    screen.getByRole("button", { name: "Refresh follow-ups" }),
  ).toBeEnabled();
});
