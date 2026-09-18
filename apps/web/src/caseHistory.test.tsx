import { afterEach, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { HistoryMore, historyMeta, validateHistoryPage } from "./caseHistory";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const item = { id: "OLDER" };
const validate = (value: { id: string }) => {
  if (typeof value.id !== "string") throw new Error("Invalid item");
};
const page = {
  caseId: "CASE-HISTORY",
  items: [item],
  total: 2,
  limit: 10,
  nextCursor: null,
};

it.each([
  { ...page, caseId: "OTHER" },
  { ...page, limit: 100 },
  { ...page, total: -1 },
  { ...page, nextCursor: "WRONG" },
  { ...page, items: [item, item] },
])("rejects a malformed or differently scoped history page %#", (value) => {
  expect(() => validateHistoryPage(value, "CASE-HISTORY", validate)).toThrow();
});

it("uses only the server cursor on explicit load and does not fetch automatically", async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify(page)));
  vi.stubGlobal("fetch", fetcher);
  const loaded = vi.fn();
  render(
    <HistoryMore
      caseId="CASE-HISTORY"
      path="/payment-cases/CASE-HISTORY/investigations?status=COMPLETED"
      page={{ total: 2, limit: 10, nextCursor: "HEAD" }}
      loaded={1}
      label="questions"
      validate={validate}
      onPage={loaded}
      onRefresh={vi.fn()}
    />,
  );
  expect(fetcher).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "Load more questions" }));
  await waitFor(() => expect(loaded).toHaveBeenCalledWith(page));
  expect(fetcher.mock.calls[0][0]).toBe(
    "/api/payment-cases/CASE-HISTORY/investigations?status=COMPLETED&limit=10&cursor=HEAD",
  );
});

it("offers refresh for a stale server cursor without accepting or replacing records", async () => {
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockResolvedValue(
        new Response(
          JSON.stringify({
            code: "CASE_HISTORY_CURSOR_CHANGED",
            message: "This history position has changed. Refresh the history.",
          }),
          { status: 409 },
        ),
      ),
  );
  const loaded = vi.fn(),
    refresh = vi.fn();
  render(
    <HistoryMore
      caseId="CASE-HISTORY"
      path="/payment-cases/CASE-HISTORY/evidence"
      page={{ total: 2, limit: 10, nextCursor: "HEAD" }}
      loaded={1}
      label="versions"
      validate={validate}
      onPage={loaded}
      onRefresh={refresh}
    />,
  );
  fireEvent.click(screen.getByRole("button", { name: "Load more versions" }));
  await screen.findByRole("alert");
  expect(loaded).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "Refresh history" }));
  expect(refresh).toHaveBeenCalledOnce();
});

it("rejects a non-advancing page", async () => {
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ ...page, items: [{ id: "HEAD" }] })),
      ),
  );
  const loaded = vi.fn();
  render(
    <HistoryMore
      caseId="CASE-HISTORY"
      path="/payment-cases/CASE-HISTORY/evidence"
      page={{ total: 2, limit: 10, nextCursor: "HEAD" }}
      loaded={1}
      label="versions"
      validate={validate}
      onPage={loaded}
      onRefresh={vi.fn()}
    />,
  );
  fireEvent.click(screen.getByRole("button", { name: "Load more versions" }));
  await screen.findByText(/history position did not advance/);
  expect(loaded).not.toHaveBeenCalled();
});

it("accepts absent metadata only for retained pre-pagination snapshots", () => {
  expect(historyMeta(undefined, 40)).toBeUndefined();
  expect(() =>
    historyMeta({ total: 40, limit: 10, nextCursor: null }, 40),
  ).toThrow();
});
