import { afterEach, describe, expect, it, vi } from "vitest";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { EvidenceLibraryPage } from "./EvidenceLibrary";
import type { User } from "./types";

const user: User = {
  id: "library-fixture-user",
  name: "Original fixture analyst",
  role: "ANALYST",
  tenantId: "library-fixture-tenant",
};
const groups = ["PAYMENT", "HOST", "HISTORY", "STATUS"] as const;
const scope = { orgBank: "009", orgBranch: "0012" };
function snapshot(caseId = "CASE-LIBRARY-A", version = 2) {
  const sections = {
    PAYMENT: {
      rows: [
        {
          REFERENCE: `ORIGINAL-${caseId}`,
          AMOUNT: "123456789012345678901.0007",
          STATUS_VALUE: "00",
          NOTE: "",
        },
      ] as Record<string, string>[],
      note: "Original synthetic fixture only.",
    },
    HOST: {
      rows: [
        { MSG_STAT: "PENDING", RAW_DATE: "2026-09-01T10:15:00" },
        { MSG_STAT: "ORIGINAL_SECOND_ROW", RAW_DATE: "" },
      ],
      note: "",
    },
    HISTORY: { rows: [] as Record<string, string>[], note: "" },
    STATUS: { rows: [] as Record<string, string>[], note: "" },
  };
  return {
    id: `EVIDENCE-${caseId}-${version}`,
    caseId,
    version,
    sourceKind: version === 2 ? "JSON" : "EXCEL",
    createdAt: `2026-09-14T10:00:0${version}Z`,
    createdBy: "Original fixture analyst",
    evidenceHash: `original-hash-${caseId}-${version}`,
    warnings: ["Original fixture: query completion unverified."],
    coverage: Object.fromEntries(
      groups.map((group) => [
        group,
        { rowCount: sections[group].rows.length, completion: "UNVERIFIED" },
      ]),
    ),
    payload: {
      schemaVersion: "fcr-case-evidence-v1",
      payment: { reference: `ORIGINAL-${caseId}`, ...scope },
      sourceTimezone: "UNKNOWN",
      sections,
    },
  };
}
function summary(value = snapshot()) {
  const { payload: _payload, ...rest } = value;
  return rest;
}
function item(caseId = "CASE-LIBRARY-A", withEvidence = true) {
  return {
    caseId,
    reference: `ORIGINAL-${caseId}`,
    utr: null,
    ...scope,
    reason: "Original fixture investigation",
    amount: "123456789012345678901.0007",
    currency: null,
    updatedAt: "2026-09-14T10:00:02Z",
    versionCount: withEvidence ? 2 : 0,
    coverageState: withEvidence ? "PARTIAL" : "NO_EVIDENCE",
    latestEvidence: withEvidence ? summary(snapshot(caseId)) : null,
  };
}
function library(items = [item(), item("CASE-LIBRARY-B", false)]) {
  return {
    generatedAt: "2026-09-14T11:00:00Z",
    page: 1,
    pageSize: 10,
    total: items.length,
    totalPages: 1,
    summary: {
      caseCount: 12,
      casesWithRows: 5,
      casesWithoutRows: 7,
      evidenceVersions: 18,
    },
    scopes: [
      { ...scope, label: "Bank 009 · Branch 0012" },
      { orgBank: "008", orgBranch: "0012", label: "Bank 008 · Branch 0012" },
      { orgBank: "008", orgBranch: "0013", label: "Bank 008 · Branch 0013" },
    ],
    items,
  };
}
const reply = (body: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(body), { status }));
function deferred() {
  let resolve!: (body: unknown) => void;
  const promise = new Promise<Response>((done) => {
    resolve = (body) => done(new Response(JSON.stringify(body)));
  });
  return { promise, resolve };
}
function mockApi(
  handle?: (url: string, request: RequestInit) => Promise<Response> | undefined,
) {
  const fetcher = vi.fn((url: string, request: RequestInit) => {
    const handled = handle?.(url, request);
    if (handled) return handled;
    if (url.startsWith("/api/evidences?")) return reply(library());
    const match =
      /^\/api\/payment-cases\/(CASE-LIBRARY-[A-Z0-9]+)\/evidence(?:\/(.*))?$/.exec(
        url,
      );
    if (match) {
      const [, caseId, id] = match;
      return id
        ? reply(snapshot(caseId, id.endsWith("-1") ? 1 : 2))
        : reply({
            items: [summary(snapshot(caseId, 2)), summary(snapshot(caseId, 1))],
          });
    }
    throw new Error(`Unexpected fixture request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
  return fetcher;
}
const listQueries = (fetcher: ReturnType<typeof mockApi>) =>
  fetcher.mock.calls
    .filter(([url]) => url.startsWith("/api/evidences?"))
    .map(([url]) => new URL(url, "http://localhost").searchParams);
const inspect = (caseId = "CASE-LIBRARY-A") =>
  fireEvent.click(
    screen.getByRole("button", { name: `Inspect evidence for ${caseId}` }),
  );
async function loaded() {
  await screen.findByRole("button", {
    name: "Inspect evidence for CASE-LIBRARY-A",
  });
}
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("Evidence library", () => {
  it("shows case numbers and numbered page links while inspecting evidence with the canonical case id", async () => {
    const numbered = { ...item(), caseNumber: "2026091600001" };
    const fetcher = mockApi((url) =>
      url.startsWith("/api/evidences?")
        ? reply(library([numbered]))
        : undefined,
    );
    render(<EvidenceLibraryPage user={user} />);
    expect(await screen.findByText(numbered.caseNumber)).toBeVisible();
    expect(
      screen.getByRole("link", { name: `Open case ${numbered.caseNumber}` }),
    ).toHaveAttribute("href", `/payment-cases/${numbered.caseNumber}`);
    expect(
      screen.getByRole("link", {
        name: `Ask questions for ${numbered.caseNumber} using evidence version 2`,
      }),
    ).toHaveAttribute(
      "href",
      `/evidences/questions/${numbered.caseNumber}/${numbered.latestEvidence!.id}`,
    );
    inspect(numbered.caseNumber);
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([url]) => url === `/api/payment-cases/${numbered.caseId}/evidence`,
        ),
      ).toBe(true),
    );
    expect(
      fetcher.mock.calls.some(([url]) =>
        url.includes(`/payment-cases/${numbered.caseNumber}/evidence`),
      ),
    ).toBe(false);
  });
  it("inspects PO02 source nulls and provenance using read-only requests", async () => {
    const value = {
      ...snapshot(),
      sourceKind: "BANK_API",
      upstream: {
        schemaVersion: "flexcube-neft-evidence-v1",
        receivedAt: "2026-09-14T10:10:00Z",
        request: {
          args0: {
            serviceCode: "PO02",
            externalReferenceNo: "ORIGINAL-LIBRARY-PO02",
          },
          args1: {},
        },
        rawResponse: { neftPaymentEvidenceDetails: [{ note: null }] },
        nullFields: [{ group: "PAYMENT", rowIndex: 1, field: "NOTE" }],
      },
    };
    const result = library();
    result.items[0].latestEvidence!.sourceKind = "BANK_API";
    const fetcher = mockApi((url) => {
      if (url.startsWith("/api/evidences?")) return reply(result);
      if (url.endsWith("/evidence")) return reply({ items: [summary(value)] });
      if (url.endsWith(value.id)) return reply(value);
    });
    render(<EvidenceLibraryPage user={{ ...user, role: "VIEWER" }} />);
    await loaded();
    inspect();
    await screen.findByText("(source null)");
    expect(screen.getByText("ORIGINAL-LIBRARY-PO02")).toBeInTheDocument();
    expect(screen.getAllByText("Query fetch unverified")).toHaveLength(4);
    expect(
      fetcher.mock.calls.every(
        ([, request]) => !request.method || request.method === "GET",
      ),
    ).toBe(true);
  });
  it("inspects sparse PO02 v2 records without confusing omitted fields with nulls or blank text", async () => {
    const value = {
      ...snapshot(),
      sourceKind: "BANK_API",
      upstream: {
        schemaVersion: "flexcube-neft-evidence-v2",
        receivedAt: "2026-09-14T10:10:01Z",
        request: {
          args0: {
            serviceCode: "PO02",
            externalReferenceNo: "ORIGINAL-SPARSE-LIBRARY",
          },
          args1: { referenceTransactionNumber: "ORIGINAL-CASE-LIBRARY-A" },
        },
        rawResponse: {
          neftPaymentEvidenceDetails: [
            {
              sourceTable: "PM_NEFT_TXN_LOG",
              queryObservedAt: "2026-09-14T15:40:00+05:30",
              scopeRowCount: "1",
              refTxnNumber: "ORIGINAL-CASE-LIBRARY-A",
              numAmount4038: "125.005",
              idRelatedRef2006: null,
              idMsgReference2020: "",
            },
          ],
          neftHostEvidenceDetails: [],
          neftHistoryEvidenceDetails: [],
          neftStatusEvidenceDetails: [],
        },
        nullFields: [
          { group: "PAYMENT", rowIndex: 1, field: "IDRELATEDREF_2006" },
        ],
        omittedFields: [{ group: "PAYMENT", rowIndex: 1, field: "N10_MSGID" }],
      },
    };
    value.payload.sections.PAYMENT.rows = [
      {
        SOURCE_TABLE: "PM_NEFT_TXN_LOG",
        QUERY_OBSERVED_AT: "2026-09-14T15:40:00+05:30",
        SCOPE_ROW_COUNT: "1",
        REFTXNNUMBER: "ORIGINAL-CASE-LIBRARY-A",
        NUMAMOUNT_4038: "125.005",
        N10_MSGID: "",
        IDRELATEDREF_2006: "",
        IDMSGREFERENCE_2020: "",
      },
    ];
    value.payload.sections.HOST.rows = [];
    value.coverage.HOST.rowCount = 0;
    const result = library();
    result.items[0].latestEvidence = summary(value);
    const fetcher = mockApi((url) => {
      if (url.startsWith("/api/evidences?")) return reply(result);
      if (url.endsWith("/evidence")) return reply({ items: [summary(value)] });
      if (url.endsWith(value.id)) return reply(value);
    });
    render(<EvidenceLibraryPage user={{ ...user, role: "VIEWER" }} />);
    await loaded();
    inspect();
    await screen.findByText("Not supplied");
    expect(screen.getByText("(source null)")).toBeInTheDocument();
    expect(screen.getByText("(blank)")).toBeInTheDocument();
    expect(screen.getByText("125.005")).toBeInTheDocument();
    expect(
      screen.getByText("Fields not supplied").parentElement,
    ).toHaveTextContent("1");
    expect(
      screen.queryByText(/provenance does not match/),
    ).not.toBeInTheDocument();
    expect(
      fetcher.mock.calls.every(
        ([, request]) => !request.method || request.method === "GET",
      ),
    ).toBe(true);
  });
  it("keeps the library metadata-only until inspection, then preserves native strings and read-only viewer access", async () => {
    const fetcher = mockApi();
    render(<EvidenceLibraryPage user={{ ...user, role: "VIEWER" }} />);
    await loaded();
    expect(
      screen.getByRole("heading", { level: 1, name: "Evidence library" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "Totals across all cases you can access, before filters.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Source rows in the latest version"),
    ).toBeInTheDocument();
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(listQueries(fetcher)[0].has("bank")).toBe(false);
    expect(listQueries(fetcher)[0].has("branch")).toBe(false);
    expect(
      screen.getByRole("link", { name: "Open case CASE-LIBRARY-B" }),
    ).toHaveAttribute("href", "/payment-cases/CASE-LIBRARY-B");
    expect(
      screen.queryByRole("link", { name: /Collect evidence/ }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("link", {
        name: "View questions for CASE-LIBRARY-A using evidence version 2",
      }),
    ).toHaveAttribute(
      "href",
      "/evidences/questions/CASE-LIBRARY-A/EVIDENCE-CASE-LIBRARY-A-2",
    );
    expect(
      screen.queryByRole("link", { name: /Ask questions/ }),
    ).not.toBeInTheDocument();
    inspect();
    const preview = await screen.findByRole("region", {
      name: "Evidence preview",
    });
    expect(
      await within(preview).findByText("123456789012345678901.0007"),
    ).toBeInTheDocument();
    expect(within(preview).getByText("00")).toBeInTheDocument();
    expect(within(preview).getByText("(blank)")).toBeInTheDocument();
    expect(within(preview).getByText("UNKNOWN")).toBeInTheDocument();
    expect(
      within(preview).getByRole("link", {
        name: "View questions for this version",
      }),
    ).toHaveAttribute(
      "href",
      "/evidences/questions/CASE-LIBRARY-A/EVIDENCE-CASE-LIBRARY-A-2",
    );
    expect(
      within(preview).getByText(
        "Original fixture: query completion unverified.",
      ),
    ).toBeInTheDocument();
    fireEvent.change(within(preview).getByLabelText("Source group"), {
      target: { value: "HOST" },
    });
    expect(
      within(preview).getByText("2026-09-01T10:15:00"),
    ).toBeInTheDocument();
    fireEvent.change(within(preview).getByLabelText("Source row"), {
      target: { value: "1" },
    });
    expect(
      within(preview).getByText("ORIGINAL_SECOND_ROW"),
    ).toBeInTheDocument();
    fireEvent.change(within(preview).getByLabelText("Find field or value"), {
      target: { value: "second_row" },
    });
    expect(within(preview).getByText("MSG_STAT")).toBeInTheDocument();
    expect(within(preview).queryByText("RAW_DATE")).not.toBeInTheDocument();
    fireEvent.change(within(preview).getByLabelText("Source group"), {
      target: { value: "STATUS" },
    });
    expect(
      within(preview).getByText(/No STATUS rows were saved/),
    ).toBeInTheDocument();
    expect(
      fetcher.mock.calls.every(
        ([, request]) => !request.method || request.method === "GET",
      ),
    ).toBe(true);
  });

  it("carries the exact latest or explicitly inspected earlier version to case Q&A without submitting a question", async () => {
    const older = deferred();
    const fetcher = mockApi((url) =>
      url.endsWith("/EVIDENCE-CASE-LIBRARY-A-1") ? older.promise : undefined,
    );
    render(<EvidenceLibraryPage user={user} />);
    await loaded();
    const latestLink = screen.getByRole("link", {
      name: "Ask questions for CASE-LIBRARY-A using evidence version 2",
    });
    expect(latestLink).toHaveAttribute(
      "href",
      "/evidences/questions/CASE-LIBRARY-A/EVIDENCE-CASE-LIBRARY-A-2",
    );
    expect(
      screen.queryByRole("link", { name: /Ask questions for CASE-LIBRARY-B/ }),
    ).not.toBeInTheDocument();
    inspect();
    const preview = screen.getByRole("region", { name: "Evidence preview" });
    await within(preview).findByRole("link", {
      name: "Ask about this version",
    });
    fireEvent.change(within(preview).getByLabelText("Evidence version"), {
      target: { value: "EVIDENCE-CASE-LIBRARY-A-1" },
    });
    expect(
      within(preview).queryByRole("link", { name: "Ask about this version" }),
    ).not.toBeInTheDocument();
    await act(async () => older.resolve(snapshot("CASE-LIBRARY-A", 1)));
    const olderLink = await within(preview).findByRole("link", {
      name: "Ask about this version",
    });
    expect(olderLink).toHaveAttribute(
      "href",
      "/evidences/questions/CASE-LIBRARY-A/EVIDENCE-CASE-LIBRARY-A-1",
    );
    expect(
      within(preview).getByText(/Continue to Evidence Q&A with version 1/),
    ).toBeInTheDocument();
    expect(latestLink).toHaveAttribute(
      "href",
      "/evidences/questions/CASE-LIBRARY-A/EVIDENCE-CASE-LIBRARY-A-2",
    );
    fireEvent.click(olderLink);
    expect(
      fetcher.mock.calls.every(
        ([, request]) => !request.method || request.method === "GET",
      ),
    ).toBe(true);
  });

  it("offers evidence collection for empty latest versions and questions only for an earlier version containing rows", async () => {
    const empty = snapshot();
    for (const group of groups) {
      empty.payload.sections[group].rows = [];
      empty.coverage[group].rowCount = 0;
    }
    const emptyItem = {
      ...item(),
      latestEvidence: summary(empty),
      coverageState: "EMPTY",
    };
    const fetcher = mockApi((url) => {
      if (url.startsWith("/api/evidences?")) return reply(library([emptyItem]));
      if (url.endsWith("/evidence"))
        return reply({
          items: [summary(empty), summary(snapshot("CASE-LIBRARY-A", 1))],
        });
      if (url.endsWith("/EVIDENCE-CASE-LIBRARY-A-2")) return reply(empty);
    });
    render(<EvidenceLibraryPage user={user} />);
    await loaded();
    expect(
      screen.queryByRole("link", { name: /Ask questions/ }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Collect evidence for CASE-LIBRARY-A" }),
    ).toHaveAttribute("href", "/payment-cases/CASE-LIBRARY-A");
    inspect();
    const preview = screen.getByRole("region", { name: "Evidence preview" });
    await within(preview).findByText(/Version 2 has no source rows/);
    expect(
      within(preview).queryByRole("link", { name: "Ask about this version" }),
    ).not.toBeInTheDocument();
    expect(
      within(preview).getByRole("link", { name: "Collect evidence" }),
    ).toHaveAttribute("href", "/payment-cases/CASE-LIBRARY-A");
    fireEvent.change(within(preview).getByLabelText("Evidence version"), {
      target: { value: "EVIDENCE-CASE-LIBRARY-A-1" },
    });
    expect(
      await within(preview).findByRole("link", {
        name: "Ask about this version",
      }),
    ).toHaveAttribute(
      "href",
      "/evidences/questions/CASE-LIBRARY-A/EVIDENCE-CASE-LIBRARY-A-1",
    );
    expect(
      fetcher.mock.calls.every(
        ([, request]) => !request.method || request.method === "GET",
      ),
    ).toBe(true);
  });

  it("paginates in tens and applies exact typed bank/branch filters from page one", async () => {
    const fetcher = mockApi((url) => {
      if (!url.startsWith("/api/evidences?")) return;
      const page = Number(
        new URL(url, "http://localhost").searchParams.get("page"),
      );
      return reply({
        ...library(
          Array.from({ length: page === 1 ? 10 : 2 }, (_, i) =>
            item(`CASE-LIBRARY-${page === 1 ? i : i + 10}`, false),
          ),
        ),
        page,
        total: 12,
        totalPages: 2,
      });
    });
    render(<EvidenceLibraryPage user={user} />);
    await screen.findByText("Showing 1–10 of 12 matching cases");
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await screen.findByText("Showing 11–12 of 12 matching cases");
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Row coverage"), {
      target: { value: "PARTIAL" },
    });
    await screen.findByText("Showing 1–10 of 12 matching cases");
    expect(listQueries(fetcher).at(-1)?.get("page")).toBe("1");
    expect(listQueries(fetcher).at(-1)?.get("coverage")).toBe("PARTIAL");
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await screen.findByText("Showing 11–12 of 12 matching cases");
    const beforeTyping = fetcher.mock.calls.length;
    expect(screen.getByRole("textbox", { name: "Bank" })).toHaveAttribute(
      "inputmode",
      "numeric",
    );
    expect(screen.getByRole("textbox", { name: "Branch" })).toBeEnabled();
    fireEvent.change(screen.getByLabelText("Bank"), {
      target: { value: "009" },
    });
    fireEvent.change(screen.getByLabelText("Branch"), {
      target: { value: "0012" },
    });
    expect(fetcher).toHaveBeenCalledTimes(beforeTyping);
    expect(
      screen.getByText(/Bank\/branch edits are not applied/),
    ).toBeInTheDocument();
    fireEvent.click(
      screen.getByRole("button", { name: "Apply bank / branch" }),
    );
    await waitFor(() =>
      expect(listQueries(fetcher).at(-1)?.get("bank")).toBe("009"),
    );
    expect(listQueries(fetcher).at(-1)?.get("branch")).toBe("0012");
    expect(listQueries(fetcher).at(-1)?.get("page")).toBe("1");
    await screen.findByText("Showing 1–10 of 12 matching cases");
    fireEvent.change(screen.getByLabelText("Bank"), {
      target: { value: "008" },
    });
    expect(screen.getByLabelText("Branch")).toHaveValue("0012");
    fireEvent.keyDown(screen.getByLabelText("Bank"), { key: "Enter" });
    await waitFor(() =>
      expect(listQueries(fetcher).at(-1)?.get("bank")).toBe("008"),
    );
    expect(listQueries(fetcher).at(-1)?.get("branch")).toBe("0012");
    fireEvent.change(screen.getByLabelText("Bank"), {
      target: { value: "" },
    });
    fireEvent.change(screen.getByLabelText("Branch"), {
      target: { value: "0000000012" },
    });
    fireEvent.click(
      screen.getByRole("button", { name: "Apply bank / branch" }),
    );
    await waitFor(() =>
      expect(listQueries(fetcher).at(-1)?.get("branch")).toBe("0000000012"),
    );
    expect(listQueries(fetcher).at(-1)?.has("bank")).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    expect(screen.getByLabelText("Bank")).toHaveValue("");
    expect(screen.getByLabelText("Branch")).toHaveValue("");
    await waitFor(() =>
      expect(listQueries(fetcher).at(-1)?.has("branch")).toBe(false),
    );
    expect(listQueries(fetcher).at(-1)?.has("bank")).toBe(false);
    expect(listQueries(fetcher).at(-1)?.get("coverage")).toBe("ALL");
    expect(listQueries(fetcher).at(-1)?.get("page")).toBe("1");
    expect(
      fetcher.mock.calls.every(
        ([, request]) => !request.method || request.method === "GET",
      ),
    ).toBe(true);
  });

  it("keeps invalid scope drafts local and reports server authorization errors without writing data", async () => {
    vi.useFakeTimers();
    const fetcher = mockApi((url) => {
      if (
        url.startsWith("/api/evidences?") &&
        new URL(url, "http://localhost").searchParams.get("bank") === "999"
      ) {
        return reply(
          {
            code: "EVIDENCE_SCOPE_FORBIDDEN",
            message:
              "The selected bank or branch filter is outside your authorized scopes.",
            requestId: "original-scope-request",
          },
          403,
        );
      }
    });
    render(<EvidenceLibraryPage user={{ ...user, role: "VIEWER" }} />);
    await act(async () => vi.advanceTimersByTimeAsync(250));
    expect(fetcher).toHaveBeenCalledTimes(1);
    const bank = screen.getByRole("textbox", { name: "Bank" });
    fireEvent.change(bank, { target: { value: "1e3" } });
    await act(async () => vi.advanceTimersByTimeAsync(1000));
    expect(fetcher).toHaveBeenCalledTimes(1);
    fireEvent.click(
      screen.getByRole("button", { name: "Apply bank / branch" }),
    );
    expect(screen.getByRole("alert")).toHaveTextContent("Use up to 10 digits");
    expect(bank).toHaveAttribute("aria-invalid", "true");
    await act(async () => vi.advanceTimersByTimeAsync(1000));
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(bank).toHaveValue("1e3");
    fireEvent.change(bank, { target: { value: "12345678901" } });
    fireEvent.keyDown(bank, { key: "Enter" });
    expect(screen.getByRole("alert")).toHaveTextContent("Use up to 10 digits");
    expect(fetcher).toHaveBeenCalledTimes(1);
    fireEvent.change(bank, { target: { value: "999" } });
    fireEvent.click(
      screen.getByRole("button", { name: "Apply bank / branch" }),
    );
    await act(async () => vi.advanceTimersByTimeAsync(250));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "outside your authorized scopes",
    );
    expect(screen.getByRole("alert")).toHaveTextContent(
      "original-scope-request",
    );
    expect(bank).toHaveValue("999");
    expect(
      screen.queryByRole("button", {
        name: "Inspect evidence for CASE-LIBRARY-A",
      }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    await act(async () => vi.advanceTimersByTimeAsync(250));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(bank).toHaveValue("");
    expect(listQueries(fetcher).at(-1)?.has("bank")).toBe(false);
    expect(
      fetcher.mock.calls.every(
        ([, request]) => !request.method || request.method === "GET",
      ),
    ).toBe(true);
  });

  it("debounces edits and ignores an aborted older search that finishes after the new search", async () => {
    const older = deferred();
    let olderSignal: AbortSignal | undefined;
    const fetcher = mockApi((url, request) => {
      if (!url.startsWith("/api/evidences?")) return;
      const search = new URL(url, "http://localhost").searchParams.get(
        "search",
      );
      if (search === "older") {
        olderSignal = request.signal as AbortSignal;
        return older.promise;
      }
      return reply(
        library(search === "newer" ? [item("CASE-LIBRARY-NEW")] : undefined),
      );
    });
    render(<EvidenceLibraryPage user={user} />);
    await loaded();
    const search = screen.getByLabelText("Search cases and payments");
    fireEvent.change(search, { target: { value: "o" } });
    fireEvent.change(search, { target: { value: "older" } });
    expect(fetcher).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(olderSignal).toBeDefined());
    expect(
      listQueries(fetcher).some((query) => query.get("search") === "o"),
    ).toBe(false);
    fireEvent.change(search, { target: { value: "newer" } });
    expect(olderSignal?.aborted).toBe(true);
    await screen.findByText("ORIGINAL-CASE-LIBRARY-NEW");
    await act(async () => older.resolve(library([item("CASE-LIBRARY-OLD")])));
    expect(
      screen.queryByText("ORIGINAL-CASE-LIBRARY-OLD"),
    ).not.toBeInTheDocument();
    expect(screen.getByText("ORIGINAL-CASE-LIBRARY-NEW")).toBeInTheDocument();
  });

  it("aborts case and version preview races and closes raw rows when the source filter changes", async () => {
    const heldA = deferred(),
      heldOldB = deferred();
    let signalA: AbortSignal | undefined, signalOldB: AbortSignal | undefined;
    mockApi((url, request) => {
      if (url.startsWith("/api/evidences?"))
        return reply(library([item(), item("CASE-LIBRARY-B")]));
      if (url.endsWith("/EVIDENCE-CASE-LIBRARY-A-2")) {
        signalA = request.signal as AbortSignal;
        return heldA.promise;
      }
      if (url.endsWith("/EVIDENCE-CASE-LIBRARY-B-1")) {
        signalOldB = request.signal as AbortSignal;
        return heldOldB.promise;
      }
    });
    render(<EvidenceLibraryPage user={user} />);
    await loaded();
    inspect();
    await waitFor(() => expect(signalA).toBeDefined());
    inspect("CASE-LIBRARY-B");
    expect(signalA?.aborted).toBe(true);
    const preview = screen.getByRole("region", { name: "Evidence preview" });
    await within(preview).findByText("original-hash-CASE-LIBRARY-B-2");
    await act(async () => heldA.resolve(snapshot()));
    expect(
      within(preview).queryByText("original-hash-CASE-LIBRARY-A-2"),
    ).not.toBeInTheDocument();
    fireEvent.change(within(preview).getByLabelText("Evidence version"), {
      target: { value: "EVIDENCE-CASE-LIBRARY-B-1" },
    });
    await waitFor(() => expect(signalOldB).toBeDefined());
    expect(
      within(preview).queryByText("123456789012345678901.0007"),
    ).not.toBeInTheDocument();
    fireEvent.change(within(preview).getByLabelText("Evidence version"), {
      target: { value: "EVIDENCE-CASE-LIBRARY-B-2" },
    });
    expect(signalOldB?.aborted).toBe(true);
    await within(preview).findByText("original-hash-CASE-LIBRARY-B-2");
    await act(async () => heldOldB.resolve(snapshot("CASE-LIBRARY-B", 1)));
    expect(
      within(preview).queryByText("original-hash-CASE-LIBRARY-B-1"),
    ).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Latest source"), {
      target: { value: "EXCEL" },
    });
    expect(
      screen.queryByRole("region", { name: "Evidence preview" }),
    ).not.toBeInTheDocument();
  });

  it("distinguishes an empty authorized library from an empty filtered result", async () => {
    let empty = true;
    mockApi((url) =>
      url.startsWith("/api/evidences?")
        ? reply({
            ...library([]),
            ...(empty
              ? {
                  summary: {
                    caseCount: 0,
                    casesWithRows: 0,
                    casesWithoutRows: 0,
                    evidenceVersions: 0,
                  },
                }
              : {}),
          })
        : undefined,
    );
    render(<EvidenceLibraryPage user={user} />);
    await screen.findByRole("heading", { name: "Start with a payment case" });
    expect(
      screen
        .getAllByRole("link", { name: "Find payment" })
        .every((link) => link.getAttribute("href") === "/cases"),
    ).toBe(true);
    empty = false;
    fireEvent.change(screen.getByLabelText("Search cases and payments"), {
      target: { value: "unmatched" },
    });
    await screen.findByRole("heading", {
      name: "No cases match these filters",
    });
    fireEvent.click(screen.getByRole("button", { name: "Reset filters" }));
    expect(screen.getByLabelText("Search cases and payments")).toHaveValue("");
  });

  it("shows request failures with retry and withholds a mismatched immutable payload", async () => {
    let failure = true;
    mockApi((url) => {
      if (url.startsWith("/api/evidences?") && failure)
        return reply(
          {
            message: "Fixture metadata is temporarily unavailable.",
            requestId: "original-library-request-id",
          },
          503,
        );
      if (url.endsWith("/EVIDENCE-CASE-LIBRARY-A-2"))
        return reply({ ...snapshot(), evidenceHash: "different-version-hash" });
    });
    render(<EvidenceLibraryPage user={user} />);
    const alert = await screen.findByRole("alert");
    expect(
      within(alert).getByText("Request original-library-request-id"),
    ).toBeInTheDocument();
    failure = false;
    fireEvent.click(within(alert).getByRole("button", { name: "Retry" }));
    await loaded();
    inspect();
    const preview = screen.getByRole("region", { name: "Evidence preview" });
    expect(await within(preview).findByRole("alert")).toHaveTextContent(
      "does not match the selected case and immutable version",
    );
    expect(
      within(preview).queryByText("123456789012345678901.0007"),
    ).not.toBeInTheDocument();
  });

  it("bounds hung metadata reads at thirty seconds and aborts again on unmount", async () => {
    vi.useFakeTimers();
    const signals: AbortSignal[] = [];
    mockApi(
      (_url, request) =>
        new Promise((_resolve, reject) => {
          const signal = request.signal as AbortSignal;
          signals.push(signal);
          signal.addEventListener("abort", () =>
            reject(new DOMException("Aborted", "AbortError")),
          );
        }),
    );
    const view = render(<EvidenceLibraryPage user={user} />);
    await act(async () => vi.advanceTimersByTimeAsync(250));
    expect(signals).toHaveLength(1);
    await act(async () => vi.advanceTimersByTimeAsync(30000));
    expect(signals[0].aborted).toBe(true);
    expect(screen.getByRole("alert")).toHaveTextContent(
      "did not finish within 30 seconds",
    );
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    await act(async () => vi.advanceTimersByTimeAsync(250));
    view.unmount();
    expect(signals[1].aborted).toBe(true);
    await act(async () => vi.advanceTimersByTimeAsync(30000));
    expect(signals).toHaveLength(2);
  });

  it("clears private metadata and an open preview when the authenticated identity changes", async () => {
    let changed = false;
    mockApi((url) =>
      changed && url.startsWith("/api/evidences?")
        ? reply(library([item("CASE-LIBRARY-OTHER", false)]))
        : undefined,
    );
    const view = render(<EvidenceLibraryPage user={user} />);
    await loaded();
    inspect();
    await screen.findByText("original-hash-CASE-LIBRARY-A-2");
    changed = true;
    view.rerender(
      <EvidenceLibraryPage
        user={{ ...user, tenantId: "original-other-tenant" }}
      />,
    );
    expect(
      screen.queryByRole("region", { name: "Evidence preview" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("ORIGINAL-CASE-LIBRARY-A"),
    ).not.toBeInTheDocument();
    await screen.findByText("ORIGINAL-CASE-LIBRARY-OTHER");
    expect(
      screen.getByRole("link", {
        name: "Collect evidence for CASE-LIBRARY-OTHER",
      }),
    ).toHaveAttribute("href", "/payment-cases/CASE-LIBRARY-OTHER");
  });
});
