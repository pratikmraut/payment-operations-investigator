import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  caseListPath,
  isSavedPaymentCase,
  useCaseRead,
  useCaseSearch,
  validateCasePage,
} from "./paymentCaseList";
import { casePageFixture } from "./testCasePage";

const item = {
  id: "FIXTURE",
  reference: "LITERAL_%+",
  orgBank: "001",
  orgBranch: "0002",
  amount: "1.00",
  utr: null,
  currency: null,
  reason: "Review",
  evidenceStatus: "DISCOVERY_ONLY",
};
const validate = (value: unknown) =>
  validateCasePage(value, isSavedPaymentCase);
const reply = (value: unknown) => new Response(JSON.stringify(value));
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("bounded saved-case queries", () => {
  it("encodes literal search and exact identity separately without losing leading zeros", () => {
    const url = caseListPath({
      search: "  literal_% +  ",
      bank: "001",
      branch: "0002",
      reference: item.reference,
      work: "MINE",
      page: 4,
      sort: "PRIORITY_DESC",
    });
    const parameters = new URL(url, "http://fixture.test").searchParams;
    expect(Object.fromEntries(parameters)).toEqual({
      lifecycle: "ACTIVE",
      search: "literal_% +",
      bank: "001",
      branch: "0002",
      reference: "LITERAL_%+",
      work: "MINE",
      page: "4",
      pageSize: "10",
      sort: "PRIORITY_DESC",
    });
  });
  it.each([
    { total: -1 },
    { page: 0 },
    { pageSize: 51 },
    { totalPages: 0 },
    { sort: "SQL" },
    { total: 20 },
    { items: [item, item], total: 2 },
    { items: [{ ...item, reference: 123 }] },
  ])("rejects inconsistent or unsafe pagination metadata %j", (change) => {
    expect(() =>
      validate({ ...casePageFixture(caseListPath(), [item]), ...change }),
    ).toThrow(/could not be read/);
  });
  it("accepts an empty clamped first page and never requests a follow-up page automatically", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(reply(casePageFixture(caseListPath({ page: 9 }), [])));
    vi.stubGlobal("fetch", fetcher);
    const { result } = renderHook(() =>
      useCaseRead(caseListPath({ page: 9 }), validate),
    );
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.data).toMatchObject({
      page: 1,
      totalPages: 1,
      total: 0,
      items: [],
    });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it("debounces typing, immediately cancels old reads and ignores delayed results after the next filter", async () => {
    vi.useFakeTimers();
    const pending: {
      url: string;
      signal: AbortSignal;
      resolve: (value: Response) => void;
    }[] = [];
    const fetcher = vi.fn(
      (url: string, options: RequestInit) =>
        new Promise<Response>((resolve) =>
          pending.push({ url, signal: options.signal!, resolve }),
        ),
    );
    vi.stubGlobal("fetch", fetcher);
    const { result, rerender } = renderHook(
      ({ search }) => {
        const debounced = useCaseSearch(search);
        return useCaseRead(
          caseListPath({ search: debounced.debounced }),
          validate,
          0,
          !debounced.pending,
        );
      },
      { initialProps: { search: "" } },
    );
    rerender({ search: "o" });
    expect(pending[0].signal.aborted).toBe(true);
    rerender({ search: "old" });
    await act(() => vi.advanceTimersByTimeAsync(249));
    expect(fetcher).toHaveBeenCalledTimes(1);
    await act(() => vi.advanceTimersByTimeAsync(1));
    expect(fetcher).toHaveBeenCalledTimes(2);
    rerender({ search: "Review" });
    expect(pending[1].signal.aborted).toBe(true);
    await act(() => vi.advanceTimersByTimeAsync(250));
    await act(async () =>
      pending[2].resolve(reply(casePageFixture(pending[2].url, [item]))),
    );
    expect(result.current.data?.items[0].id).toBe("FIXTURE");
    await act(async () => {
      pending[1].resolve(reply(casePageFixture(pending[1].url, [])));
      pending[0].resolve(reply(casePageFixture(pending[0].url, [])));
    });
    expect(result.current.data?.search).toBe("Review");
    expect(result.current.data?.total).toBe(1);
  });
  it("ends a hung read after 30 seconds and ignores a late response", async () => {
    vi.useFakeTimers();
    let finish!: (value: Response) => void;
    const fetcher = vi.fn(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve;
        }),
    );
    vi.stubGlobal("fetch", fetcher);
    const { result } = renderHook(() => useCaseRead(caseListPath(), validate));
    await act(() => vi.advanceTimersByTimeAsync(30000));
    expect(result.current.loading).toBe(false);
    expect(result.current.error?.message).toContain("30 seconds");
    await act(async () =>
      finish(reply(casePageFixture(caseListPath(), [item]))),
    );
    expect(result.current.data).toBeNull();
    expect(result.current.error?.message).toContain("30 seconds");
  });
});
