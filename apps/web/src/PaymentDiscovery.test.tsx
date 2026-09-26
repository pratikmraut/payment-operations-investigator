import { casePageFixture } from "./testCasePage";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Profiler } from "react";
import App from "./App";
import {
  FindPayment,
  PaymentCaseDetail,
  PaymentCasesPage,
  type DiscoveryBatch,
  type DiscoveryConfig,
  type PaymentCandidate,
  type PaymentCase,
} from "./PaymentDiscovery";
import { setCsrfToken } from "./api";
import type { User } from "./types";

const user: User = {
  id: "fixture-analyst",
  name: "Fixture analyst",
  role: "ANALYST",
  tenantId: "fixture-tenant",
};
const config: DiscoveryConfig = {
  mode: "MOCK",
  today: "2026-09-14",
  timezone: "Asia/Kolkata",
  maxRecords: 200,
  scopes: [
    { orgBranch: "0100", orgBank: "099", label: "Fixture branch" },
    { orgBranch: "0200", orgBank: "099", label: "Second fixture branch" },
  ],
  directLookupScope: "Exact reference or UTR in the selected bank and branch.",
};
const candidate: PaymentCandidate = {
  candidateId: "candidate-fixture-A",
  reference: "0001234567890123456789012345",
  utr: "FIXTURE-UTR-01",
  orgBranch: "0100",
  orgBank: "099",
  hostSubsequences: ["0", "1"],
  initiatedAt: "2026-09-14T10:15:00",
  amount: "12345678901234567890.1234",
  currency: null,
  sourceKind: "MOCK",
  dataClassification: "SYNTHETIC",
};
const saved: PaymentCase = {
  ...candidate,
  id: "PC-FIXTURE-1",
  reason: "Check the reported processing delay.",
  status: "OPEN",
  priority: "MEDIUM",
  createdAt: "2026-09-14T10:30:00Z",
  updatedAt: "2026-09-14T10:30:00Z",
  createdBy: user.id,
  evidenceStatus: "DISCOVERY_ONLY",
};
const batch: DiscoveryBatch = {
  batchId: "batch-fixture",
  mode: "MOCK",
  sourceKind: "MOCK",
  observedAt: "2026-09-14T10:20:00Z",
  coverage: "Exact match within the selected bank and branch.",
  truncated: false,
  items: [candidate],
  warnings: [],
};
const reply = (value: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(value), { status }));
type Options = {
  configuration?: Partial<DiscoveryConfig>;
  records?: PaymentCase[];
  caseDetail?: PaymentCase;
  discover?: (body: RequestInit, index: number) => Promise<Response>;
  create?: (body: RequestInit, index: number) => Promise<Response>;
};
function mockApi(options: Options = {}) {
  let searches = 0;
  let creates = 0;
  const fetcher = vi.fn((url: string, request: RequestInit) => {
    if (url === "/api/auth/me")
      return reply({ user, csrfToken: "fixture-csrf" });
    if (url === "/api/payment-discovery/config")
      return reply({ ...config, ...options.configuration });
    if (
      url === "/api/payment-discovery/search" ||
      url === "/api/payment-discovery/lookup" ||
      url === "/api/payment-discovery/uploads"
    )
      return options.discover?.(request, ++searches) ?? reply(batch);
    if (url === "/api/payment-cases" && request.method === "POST")
      return (
        options.create?.(request, ++creates) ??
        reply({ caseId: saved.id, status: "CREATED", item: saved })
      );
    if (url.startsWith("/api/payment-cases?"))
      return reply(casePageFixture(url, options.records ?? []));
    if (url === "/api/payment-cases/dashboard")
      return reply({
        openCases: options.records?.length ?? 0,
        highPriorityCases: 0,
        awaitingReview: 0,
        resolvedCases: 0,
      });
    if (url === `/api/payment-cases/${saved.id}`)
      return reply(options.caseDetail ?? saved);
    if (url === `/api/payment-cases/${saved.id}/management`)
      return reply({
        caseId: saved.id,
        version: 0,
        owner: null,
        priority: "MEDIUM",
        assignees: [],
        notes: [],
        evidenceRequests: [],
        reviewerConclusions: [],
        audit: [],
      });
    if (url === `/api/payment-cases/${saved.id}/workbench`)
      return reply({
        caseId: saved.id,
        evidence: [],
        latestEvidenceId: null,
        audit: [],
        investigations: [],
      });
    if (url === `/api/payment-cases/${saved.id}/evidence`)
      return reply({ items: [] });
    if (url === `/api/payment-cases/${saved.id}/evidence/config`)
      return reply({
        schemaVersion: "fcr-case-evidence-v1",
        api: { enabled: false, mode: "DISABLED" },
        limits: { maxRowsPerSection: 500, maxFileBytes: 5242880 },
        groups: ["PAYMENT", "HOST", "HISTORY", "STATUS"].map((key) => ({
          key,
          functionName: `AP_BA_NEFT_${key}_INQ`,
          columns: ["SOURCE_TABLE"],
        })),
        template: {
          schemaVersion: "fcr-case-evidence-v1",
          sourceTimezone: "UNKNOWN",
          payment: {
            reference: saved.reference,
            orgBank: saved.orgBank,
            orgBranch: saved.orgBranch,
          },
          sections: Object.fromEntries(
            ["PAYMENT", "HOST", "HISTORY", "STATUS"].map((key) => [
              key,
              { rows: [], note: "" },
            ]),
          ),
        },
      });
    if (url === "/api/dashboard")
      return reply({
        openCases: 48,
        highPriorityCases: 16,
        awaitingReview: 0,
        resolvedCases: 0,
        totalAmountMinor: 0,
        currency: "INR",
        recentActivity: [],
      });
    if (url.startsWith("/api/cases?")) return reply({ items: [], total: 0 });
    throw new Error(`Unexpected test endpoint: ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
  return fetcher;
}
async function searchReference(value = candidate.reference) {
  await screen.findByLabelText("Payment reference or UTR");
  await waitFor(() =>
    expect(
      screen.getByRole("button", { name: "Find transaction" }),
    ).toBeEnabled(),
  );
  fireEvent.change(screen.getByLabelText("Payment reference or UTR"), {
    target: { value },
  });
  await userEvent.click(
    screen.getByRole("button", { name: "Find transaction" }),
  );
}
async function selectAndExplain() {
  await userEvent.click(await screen.findByRole("radio"));
  fireEvent.change(screen.getByLabelText("Investigation reason"), {
    target: { value: saved.reason },
  });
}
beforeEach(() => {
  window.history.replaceState(null, "", "/");
  vi.spyOn(window, "scrollTo").mockImplementation(() => undefined);
});
afterEach(() => {
  vi.unstubAllGlobals();
  setCsrfToken(null);
  window.history.replaceState(null, "", "/");
});

describe("private payment discovery", () => {
  it("initializes scope before the first interactive render and preserves explicit edits and clears", async () => {
    const fetcher = mockApi();
    let firstScope: { orgBank: string; orgBranch: string } | null = null;
    render(
      <Profiler
        id="scope-input-arrival"
        onRender={() => {
          const bank = screen.queryByLabelText(
            "Authorized bank",
          ) as HTMLInputElement | null;
          const branch = screen.queryByLabelText(
            "Authorized branch",
          ) as HTMLInputElement | null;
          // Observe the first committed form, before passive effects can seed defaults.
          if (bank && branch && !firstScope)
            firstScope = { orgBank: bank.value, orgBranch: branch.value };
        }}
      >
        <FindPayment user={user} onOpen={vi.fn()} />
      </Profiler>,
    );
    await waitFor(() =>
      expect(firstScope).toEqual({ orgBank: "099", orgBranch: "0100" }),
    );
    const bank = screen.getByLabelText("Authorized bank");
    const branch = screen.getByLabelText("Authorized branch");
    fireEvent.change(bank, { target: { value: "808" } });
    fireEvent.change(branch, { target: { value: "007777" } });
    expect(bank).toHaveValue("808");
    expect(branch).toHaveValue("007777");
    fireEvent.change(bank, { target: { value: "" } });
    expect(bank).toHaveValue("");
    expect(branch).toHaveValue("007777");
    fireEvent.change(branch, { target: { value: "" } });
    expect(bank).toHaveValue("");
    expect(branch).toHaveValue("");
    expect(
      fetcher.mock.calls.some(([, request]) => request.method === "POST"),
    ).toBe(false);
  });
  it("renders bank inquiry records returned through the application API without creating a case", async () => {
    const fetcher = mockApi({
      configuration: { mode: "BANK_API" },
      discover: () =>
        reply({
          ...batch,
          mode: "BANK_API",
          sourceKind: "BANK_API",
          items: [
            {
              ...candidate,
              sourceKind: "BANK_API",
              dataClassification: "PRIVATE_EVIDENCE",
            },
          ],
        }),
    });
    render(<FindPayment user={user} onOpen={vi.fn()} />);
    await screen.findByLabelText("Payment reference or UTR");
    fireEvent.change(screen.getByLabelText("Authorized bank"), {
      target: { value: config.scopes[0].orgBank },
    });
    fireEvent.change(screen.getByLabelText("Authorized branch"), {
      target: { value: config.scopes[0].orgBranch },
    });
    await userEvent.click(screen.getByRole("button", { name: "Inquiry API" }));
    fireEvent.change(screen.getByLabelText(/Inquiry date/), {
      target: { value: "2026-09-15" },
    });
    fireEvent.change(screen.getByLabelText(/Maximum records/), {
      target: { value: "20" },
    });
    await userEvent.click(
      screen.getByRole("button", { name: "Find transactions" }),
    );
    const row = (await screen.findByRole("radio")).closest("tr")!;
    expect(within(row).getByText(candidate.reference)).toBeVisible();
    expect(within(row).getByText(candidate.amount)).toBeVisible();
    const posts = fetcher.mock.calls.filter(
      ([, request]) => request.method === "POST",
    );
    expect(posts.map(([url]) => url)).toEqual([
      "/api/payment-discovery/search",
    ]);
    expect(JSON.parse(posts[0][1].body as string)).toEqual({
      orgBank: config.scopes[0].orgBank,
      orgBranch: config.scopes[0].orgBranch,
      inquiryDate: "2026-09-15",
      recordCount: 20,
    });
  });

  it("shows a bank TLS failure without substituting mock records or opening a case", async () => {
    const message =
      "The inquiry TLS certificate could not be verified. Ask the administrator to check the certificate hostname and trusted certificate chain.";
    const fetcher = mockApi({
      configuration: { mode: "BANK_API" },
      discover: () => reply({ code: "DISCOVERY_TLS_ERROR", message }, 503),
    });
    render(<FindPayment user={user} onOpen={vi.fn()} />);
    await screen.findByLabelText("Authorized bank");
    fireEvent.change(screen.getByLabelText("Authorized bank"), {
      target: { value: config.scopes[0].orgBank },
    });
    fireEvent.change(screen.getByLabelText("Authorized branch"), {
      target: { value: config.scopes[0].orgBranch },
    });
    await searchReference();
    expect(await screen.findByText(message)).toBeVisible();
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
    expect(
      fetcher.mock.calls
        .filter(([, request]) => request.method === "POST")
        .map(([url]) => url),
    ).toEqual(["/api/payment-discovery/lookup"]);
  });

  it.each([
    { name: "known zero and unknown", values: ["0", null], text: "0, Unknown" },
    { name: "only unknown", values: [null], text: "Unknown" },
  ])(
    "shows $name host subsequences in discovery and the saved case",
    async ({ values, text }) => {
      const fetcher = mockApi({
        discover: () =>
          reply({
            ...batch,
            items: [{ ...candidate, hostSubsequences: values }],
          }),
        caseDetail: { ...saved, hostSubsequences: values },
      });
      const view = render(<FindPayment user={user} onOpen={vi.fn()} />);
      await searchReference();
      const row = (await screen.findByRole("radio")).closest("tr")!;
      expect(within(row).getByText(text)).toBeVisible();
      expect(within(row).queryByText("None supplied")).not.toBeInTheDocument();
      expect(screen.getByLabelText("Authorized bank")).toHaveValue(
        config.scopes[0].orgBank,
      );
      expect(screen.getByLabelText("Authorized branch")).toHaveValue(
        config.scopes[0].orgBranch,
      );

      view.rerender(<PaymentCaseDetail caseId={saved.id} onBack={vi.fn()} />);
      await screen.findByRole("heading", { name: "Payment investigation" });
      const field = screen.getByText("Host subsequences").parentElement!;
      expect(within(field).getByText(text)).toBeVisible();
      expect(
        within(field).queryByText("None supplied"),
      ).not.toBeInTheDocument();
      expect(
        fetcher.mock.calls
          .filter(([, request]) => request.method === "POST")
          .map(([url]) => url),
      ).toEqual(["/api/payment-discovery/lookup"]);
    },
  );

  it("opens report options from the saved-case action and workbench with a clean report URL", async () => {
    const fetcher = mockApi({ records: [saved] });
    window.history.replaceState(null, "", "/cases");
    render(<App />);
    const entry = await screen.findByRole("link", {
      name: `Export PDF for ${saved.reference}`,
    });
    expect(entry).toHaveAttribute(
      "href",
      `/payment-cases/${saved.id}?report=1`,
    );
    fireEvent.click(entry);
    await screen.findByRole("heading", { name: "Export case report" });
    expect(window.location.pathname).toBe(`/payment-cases/${saved.id}`);
    expect(window.location.search).toBe("?report=1");
    expect(window.location.hash).toBe("");
    fireEvent.click(screen.getByRole("button", { name: "Close report" }));
    expect(window.location.search).toBe("");
    expect(
      screen.queryByRole("heading", { name: "Export case report" }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("link", { name: "Export PDF" }));
    await screen.findByRole("heading", { name: "Export case report" });
    expect(window.location.search).toBe("?report=1");
    expect(
      fetcher.mock.calls.some(([, request]) => request.method === "POST"),
    ).toBe(false);
  });
  it("opens a directly loaded report URL after restoring the authenticated session", async () => {
    mockApi();
    window.history.replaceState(
      null,
      "",
      `/payment-cases/${saved.id}?report=1`,
    );
    render(<App />);
    await screen.findByRole("heading", { name: "Export case report" });
    expect(window.location.search).toBe("?report=1");
  });
  it("searches current owners and priorities while retaining ten-case pages", async () => {
    const records = Array.from({ length: 12 }, (_, index) => ({
      ...saved,
      id: `PC-OWNED-${index}`,
      reference: `OWNED-${index}`,
      owner: {
        id: "owner-a",
        name: index < 11 ? "Assigned analyst" : "Other owner",
      },
      priority: (index < 11 ? "HIGH" : "LOW") as PaymentCase["priority"],
    }));
    mockApi({ records });
    render(<PaymentCasesPage user={user} onOpen={vi.fn()} />);
    await screen.findByText("12 cases");
    fireEvent.change(screen.getByLabelText("Search saved payment cases"), {
      target: { value: "Assigned high" },
    });
    const region = screen.getByRole("region", { name: "Saved payment cases" });
    await within(region).findByText("Showing 1–10 of 11 matching cases");
    expect(within(region).getAllByRole("row")).toHaveLength(11);
    expect(
      within(region).getByText("Showing 1–10 of 11 matching cases"),
    ).toBeVisible();
    expect(
      within(region).queryByText("Owner: Other owner"),
    ).not.toBeInTheDocument();
    expect(within(region).getByText("Page 1 of 2 · 10 per page")).toBeVisible();
    fireEvent.change(screen.getByLabelText("Search saved payment cases"), {
      target: { value: "Other low" },
    });
    expect(await within(region).findByText("Owner: Other owner")).toBeVisible();
    expect(within(region).getAllByRole("row")).toHaveLength(2);
  });
  it("places management after selected payment and opens a case-scoped report panel on demand", async () => {
    const fetcher = mockApi();
    render(
      <PaymentCaseDetail caseId={saved.id} user={user} onBack={vi.fn()} />,
    );
    const selected = await screen.findByRole("heading", {
      name: "Selected payment",
    });
    const management = await screen.findByRole("region", {
      name: "Case management",
    });
    expect(
      selected.compareDocumentPosition(management) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    const intake = await screen.findByRole("region", { name: "Case evidence" });
    expect(
      management.compareDocumentPosition(intake) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      screen.queryByRole("heading", { name: "Export case report" }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Export PDF" }));
    expect(
      await screen.findByRole("heading", { name: "Export case report" }),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: "Export PDF" })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    expect(fetcher.mock.calls.some(([, req]) => req.method === "POST")).toBe(
      false,
    );
  });
  it("shows every saved case below the discovery form, including a newly added sixth case", async () => {
    const records = Array.from({ length: 6 }, (_, index) => ({
      ...saved,
      id: `PC-FIXTURE-${index + 1}`,
      reference: `FIXTURE-REFERENCE-${index + 1}`,
      reason:
        index === 5 ? "Newly created testing case." : "Review imported record.",
    }));
    mockApi({ records });
    render(<PaymentCasesPage user={user} onOpen={vi.fn()} />);
    const savedCases = screen.getByRole("region", {
      name: "Saved payment cases",
    });
    await within(savedCases).findByText("6 cases");
    expect(within(savedCases).getAllByRole("row")).toHaveLength(7);
    for (const item of records) {
      expect(
        within(savedCases).getByRole("link", { name: `Open ${item.id}` }),
      ).toHaveAttribute("href", `/payment-cases/${item.id}`);
    }
    expect(
      within(savedCases).getByText("Newly created testing case."),
    ).toBeVisible();
    const findPayment = screen.getByRole("heading", { name: "Find payment" });
    expect(
      findPayment.compareDocumentPosition(savedCases) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });
  it("redirects the authenticated root to the payment queue without demo navigation or reads", async () => {
    const fetcher = mockApi();
    render(<App />);
    await screen.findByRole("heading", { name: "No active payment cases" });
    expect(
      screen.queryByRole("group", { name: "Case queue dataset" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Demo cases" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Find payment" })).toBeVisible();
    expect(window.location.hash).toBe("");
    expect(window.location.pathname).toBe("/cases");
    expect(fetcher.mock.calls.some(([url]) => url === "/api/dashboard")).toBe(
      false,
    );
    expect(
      fetcher.mock.calls.some(([url]) => url.startsWith("/api/cases?")),
    ).toBe(false);
  });
  it.each(["CREATED", "EXISTING"])(
    "opens the returned case number for a %s case and shows that number on saved discovery matches",
    async (status) => {
      const caseNumber = "2026091600001";
      mockApi({
        discover: () =>
          reply({
            ...batch,
            items: [
              {
                ...candidate,
                existingCaseId: saved.id,
                existingCaseNumber: caseNumber,
              },
            ],
          }),
        create: () =>
          reply({ caseId: saved.id, status, item: { ...saved, caseNumber } }),
      });
      const onOpen = vi.fn();
      render(<FindPayment user={user} onOpen={onOpen} />);
      await searchReference();
      expect(await screen.findByText(`Saved case ${caseNumber}`)).toBeVisible();
      fireEvent.click(screen.getByRole("radio"));
      fireEvent.change(screen.getByLabelText("Investigation reason"), {
        target: { value: saved.reason },
      });
      fireEvent.click(
        screen.getByRole("button", { name: "Open or resume case" }),
      );
      await waitFor(() =>
        expect(onOpen).toHaveBeenCalledExactlyOnceWith(caseNumber),
      );
    },
  );
  it("preserves a long reference and source amount, requires selection and reason, and never opens a search result automatically", async () => {
    const fetcher = mockApi({
      discover: () => reply({ ...batch, matchStatus: "EXACT_MATCH" }),
    });
    const onOpen = vi.fn();
    render(<FindPayment user={user} onOpen={onOpen} />);
    await searchReference();
    const radio = await screen.findByRole("radio");
    expect(radio).not.toBeChecked();
    expect(screen.getByText(candidate.amount)).toBeVisible();
    expect(screen.getByText("INR")).toHaveAttribute(
      "title",
      expect.stringContaining("display default"),
    );
    expect(screen.getByText("MOCK inquiry source")).toBeVisible();
    expect(
      screen.getByText(
        /Exact reference match\. Review the transaction and its grouped host/,
      ),
    ).toBeVisible();
    expect(screen.getAllByRole("radio")).toHaveLength(1);
    expect(onOpen).not.toHaveBeenCalled();
    expect(
      fetcher.mock.calls.some(
        ([url, req]) => url === "/api/payment-cases" && req.method === "POST",
      ),
    ).toBe(false);
    expect(
      screen.getByRole("button", { name: "Open or resume case" }),
    ).toBeDisabled();
    const lookup = fetcher.mock.calls.find(([url]) =>
      url.endsWith("/lookup"),
    )![1];
    expect(JSON.parse(lookup.body as string)).toEqual({
      orgBank: "099",
      orgBranch: "0100",
      referenceType: "FCR",
      reference: candidate.reference,
    });
    await userEvent.click(radio);
    expect(
      screen.getByRole("button", { name: "Open or resume case" }),
    ).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Investigation reason"), {
      target: { value: saved.reason },
    });
    await userEvent.click(
      screen.getByRole("button", { name: "Open or resume case" }),
    );
    await waitFor(() =>
      expect(onOpen).toHaveBeenCalledExactlyOnceWith(saved.id),
    );
    const creation = fetcher.mock.calls.find(
      ([url, req]) => url === "/api/payment-cases" && req.method === "POST",
    )![1];
    expect(JSON.parse(creation.body as string)).toEqual({
      candidateId: candidate.candidateId,
      reason: saved.reason,
    });
    expect(new Headers(creation.headers).get("Idempotency-Key")).toBeTruthy();
  });
  it("has no date or count inputs for exact lookup and ignores invalid values left in the API list form", async () => {
    const fetcher = mockApi();
    render(<FindPayment user={user} onOpen={vi.fn()} />);
    await screen.findByLabelText("Payment reference or UTR");
    expect(
      screen.queryByLabelText("Inquiry date", { exact: false }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByLabelText("Maximum records", { exact: false }),
    ).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Inquiry API" }));
    const date = screen.getByLabelText("Inquiry date", { exact: false });
    expect(date).toHaveValue(config.today);
    fireEvent.change(date, { target: { value: "" } });
    fireEvent.change(
      screen.getByLabelText("Maximum records", { exact: false }),
      { target: { value: "0" } },
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Reference / UTR" }),
    );
    expect(
      screen.queryByLabelText("Inquiry date", { exact: false }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByLabelText("Maximum records", { exact: false }),
    ).not.toBeInTheDocument();
    await searchReference(`  ${candidate.reference}  `);
    await screen.findByRole("radio");
    const requests = fetcher.mock.calls.filter(([url]) =>
      url.endsWith("/lookup"),
    );
    expect(requests).toHaveLength(1);
    expect(JSON.parse(requests[0][1].body as string)).toEqual({
      orgBank: "099",
      orgBranch: "0100",
      referenceType: "FCR",
      reference: candidate.reference,
    });
    expect(fetcher.mock.calls.some(([url]) => url.endsWith("/search"))).toBe(
      false,
    );
  });
  it("shows an exact lookup with no match without suggesting date or record-limit changes", async () => {
    mockApi({
      discover: () => reply({ ...batch, matchStatus: "NOT_FOUND", items: [] }),
    });
    const onOpen = vi.fn();
    render(<FindPayment user={user} onOpen={onOpen} />);
    await searchReference("UNMATCHED-ORIGINAL-FIXTURE");
    await screen.findByRole("heading", {
      name: "No transactions in this result",
    });
    expect(
      screen.getByText(
        /Check the exact reference or UTR and the selected bank and branch/,
      ),
    ).toBeVisible();
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
    expect(
      screen.queryByText(/previously loaded|local records|record limit/),
    ).not.toBeInTheDocument();
    expect(onOpen).not.toHaveBeenCalled();
  });
  it("sends UTR lookup as text and API listing with exactly bank, branch, date and bounded count", async () => {
    const fetcher = mockApi();
    render(<FindPayment user={user} onOpen={vi.fn()} />);
    await screen.findByLabelText("Reference type");
    await userEvent.selectOptions(
      screen.getByLabelText("Reference type"),
      "UTR",
    );
    await searchReference(candidate.utr!);
    await screen.findByRole("radio");
    let requests = fetcher.mock.calls.filter(([url]) =>
      url.endsWith("/lookup"),
    );
    expect(JSON.parse(requests[0][1].body as string)).toEqual({
      orgBank: "099",
      orgBranch: "0100",
      referenceType: "UTR",
      reference: candidate.utr,
    });
    await userEvent.click(screen.getByRole("button", { name: "Inquiry API" }));
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Authorized branch"), {
      target: { value: "0200" },
    });
    fireEvent.change(
      screen.getByLabelText("Maximum records", { exact: false }),
      { target: { value: "25" } },
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Find transactions" }),
    );
    await screen.findByRole("radio");
    requests = fetcher.mock.calls.filter(([url]) => url.endsWith("/search"));
    expect(JSON.parse(requests[0][1].body as string)).toEqual({
      orgBank: "099",
      orgBranch: "0200",
      inquiryDate: config.today,
      recordCount: 25,
    });
  });
  it("uploads multipart Excel with session/CSRF headers and preserves PRIVATE_UAT classification", async () => {
    const fetcher = mockApi({
      discover: () =>
        reply({
          ...batch,
          sourceKind: "EXCEL",
          items: [
            {
              ...candidate,
              sourceKind: "EXCEL",
              dataClassification: "PRIVATE_UAT",
            },
          ],
        }),
    });
    setCsrfToken("fixture-upload-csrf");
    render(<FindPayment user={user} onOpen={vi.fn()} />);
    await userEvent.click(
      await screen.findByRole("button", { name: "Excel upload" }),
    );
    const file = new File(["synthetic test workbook bytes"], "fixture.xlsx", {
      type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    });
    await userEvent.upload(
      screen.getByLabelText("Discovery Excel file", { exact: false }),
      file,
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Read Excel records" }),
    );
    await screen.findByRole("radio");
    const request = fetcher.mock.calls.find(([url]) =>
      url.endsWith("/uploads"),
    )![1];
    expect(request.body).toBeInstanceOf(FormData);
    expect((request.body as FormData).get("file")).toBe(file);
    expect((request.body as FormData).get("orgBank")).toBe("099");
    expect((request.body as FormData).get("orgBranch")).toBe("0100");
    expect(new Headers(request.headers).get("Content-Type")).toBeNull();
    expect(new Headers(request.headers).get("X-CSRF-Token")).toBe(
      "fixture-upload-csrf",
    );
    expect(request.credentials).toBe("same-origin");
    expect(screen.getByText("Private evidence")).toBeVisible();
    expect(screen.queryByText("PRIVATE_UAT")).not.toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Download discovery Excel template" }),
    ).toHaveAttribute("href", "/payment-discovery-template.xlsx");
  });
  it("requires a newly selected Excel file after leaving and returning to upload mode", async () => {
    const fetcher = mockApi();
    render(<FindPayment user={user} onOpen={vi.fn()} />);
    await userEvent.click(
      await screen.findByRole("button", { name: "Excel upload" }),
    );
    await userEvent.upload(
      screen.getByLabelText("Discovery Excel file", { exact: false }),
      new File(["first synthetic workbook"], "first.xlsx"),
    );
    await userEvent.click(screen.getByRole("button", { name: "Inquiry API" }));
    await userEvent.click(screen.getByRole("button", { name: "Excel upload" }));
    expect(
      (
        screen.getByLabelText("Discovery Excel file", {
          exact: false,
        }) as HTMLInputElement
      ).files,
    ).toHaveLength(0);
    await userEvent.click(
      screen.getByRole("button", { name: "Read Excel records" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Choose a non-empty .xlsx file",
    );
    expect(fetcher.mock.calls.some(([url]) => url.endsWith("/uploads"))).toBe(
      false,
    );

    const replacement = new File(["second synthetic workbook"], "second.xlsx");
    await userEvent.upload(
      screen.getByLabelText("Discovery Excel file", { exact: false }),
      replacement,
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Read Excel records" }),
    );
    await screen.findByRole("radio");
    const uploads = fetcher.mock.calls.filter(([url]) =>
      url.endsWith("/uploads"),
    );
    expect(uploads).toHaveLength(1);
    expect((uploads[0][1].body as FormData).get("file")).toBe(replacement);
  });
  it("uses editable text codes with initial scope defaults and does not replace the branch when the bank changes", async () => {
    const fetcher = mockApi({
      configuration: {
        scopes: [
          {
            orgBank: "999",
            orgBranch: "1352",
            label: "Bank 999 · Branch 1352",
          },
          {
            orgBank: "760",
            orgBranch: "1352",
            label: "Bank 760 · Branch 1352",
          },
          {
            orgBank: "760",
            orgBranch: "2000",
            label: "Bank 760 · Branch 2000",
          },
          {
            orgBank: "999",
            orgBranch: "9000",
            label: "Bank 999 · Branch 9000",
          },
        ],
      },
      discover: (request) => {
        const scope = JSON.parse(request.body as string);
        return reply({
          ...batch,
          items: [
            {
              ...candidate,
              orgBank: scope.orgBank,
              orgBranch: scope.orgBranch,
            },
          ],
        });
      },
    });
    render(<FindPayment user={user} onOpen={vi.fn()} />);
    const bank = await screen.findByRole("textbox", {
      name: "Authorized bank",
    });
    await waitFor(() => expect(bank).toHaveValue("760"));
    const branch = screen.getByRole("textbox", { name: "Authorized branch" });
    expect(branch).toHaveValue("1352");
    for (const input of [bank, branch]) {
      expect(input).toHaveAttribute("type", "text");
      expect(input).toHaveAttribute("inputmode", "numeric");
      expect(input).toHaveAttribute("maxlength", "10");
      expect(input).toHaveAttribute("pattern", "[0-9]{1,10}");
      expect(input).toBeRequired();
    }
    expect(
      screen.queryByRole("combobox", { name: "Authorized bank" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("combobox", { name: "Authorized branch" }),
    ).not.toBeInTheDocument();
    await userEvent.clear(branch);
    await userEvent.type(branch, "2000");
    await userEvent.clear(bank);
    await userEvent.type(bank, "999");
    expect(branch).toHaveValue("2000");
    await userEvent.click(screen.getByRole("button", { name: "Inquiry API" }));
    await userEvent.click(
      screen.getByRole("button", { name: "Find transactions" }),
    );
    await screen.findByRole("radio");
    const request = fetcher.mock.calls.find(([url]) =>
      url.endsWith("/search"),
    )![1];
    expect(JSON.parse(request.body as string)).toEqual({
      orgBank: "999",
      orgBranch: "2000",
      inquiryDate: config.today,
      recordCount: 50,
    });
  });
  it.each([
    {
      method: "Reference / UTR",
      endpoint: "/lookup",
      submit: "Find transaction",
    },
    { method: "Inquiry API", endpoint: "/search", submit: "Find transactions" },
    {
      method: "Excel upload",
      endpoint: "/uploads",
      submit: "Read Excel records",
    },
  ])(
    "sends typed codes unchanged through $method even when not listed in initial scopes",
    async ({ method, endpoint, submit }) => {
      const fetcher = mockApi({
        discover: () =>
          reply({ ...batch, items: [], matchStatus: "NOT_FOUND" }),
      });
      render(<FindPayment user={user} onOpen={vi.fn()} />);
      const bank = await screen.findByRole("textbox", {
        name: "Authorized bank",
      });
      const branch = screen.getByRole("textbox", { name: "Authorized branch" });
      await waitFor(() => expect(bank).toHaveValue(config.scopes[0].orgBank));
      await userEvent.clear(bank);
      await userEvent.type(bank, "0000000042");
      await userEvent.clear(branch);
      await userEvent.type(branch, "0098765432");
      await userEvent.click(screen.getByRole("button", { name: method }));
      expect(
        screen.getByRole("textbox", { name: "Authorized bank" }),
      ).toHaveValue("0000000042");
      expect(
        screen.getByRole("textbox", { name: "Authorized branch" }),
      ).toHaveValue("0098765432");
      if (endpoint === "/lookup") {
        fireEvent.change(screen.getByLabelText("Payment reference or UTR"), {
          target: { value: candidate.reference },
        });
      } else if (endpoint === "/uploads") {
        await userEvent.upload(
          screen.getByLabelText("Discovery Excel file", { exact: false }),
          new File(["original fixture workbook bytes"], "typed-scope.xlsx"),
        );
      }
      expect(screen.getByRole("button", { name: submit })).toBeEnabled();
      await userEvent.click(screen.getByRole("button", { name: submit }));
      await screen.findByRole("heading", {
        name: "No transactions in this result",
      });
      const requests = fetcher.mock.calls.filter(([url]) =>
        url.endsWith(endpoint),
      );
      expect(requests).toHaveLength(1);
      const request = requests[0][1];
      if (endpoint === "/uploads") {
        expect(request.body).toBeInstanceOf(FormData);
        expect((request.body as FormData).get("orgBank")).toBe("0000000042");
        expect((request.body as FormData).get("orgBranch")).toBe("0098765432");
      } else {
        expect(JSON.parse(request.body as string)).toEqual(
          endpoint === "/lookup"
            ? {
                orgBank: "0000000042",
                orgBranch: "0098765432",
                referenceType: "FCR",
                reference: candidate.reference,
              }
            : {
                orgBank: "0000000042",
                orgBranch: "0098765432",
                inquiryDate: config.today,
                recordCount: 50,
              },
        );
      }
      expect(
        fetcher.mock.calls.some(
          ([url, request]) =>
            url === "/api/payment-cases" && request.method === "POST",
        ),
      ).toBe(false);
    },
  );
  it("preserves typed codes across mode switches and parent queue refresh", async () => {
    const options: Options = { records: [] };
    const fetcher = mockApi(options);
    render(<PaymentCasesPage user={user} onOpen={vi.fn()} />);
    await screen.findByRole("textbox", { name: "Authorized bank" });
    fireEvent.change(screen.getByLabelText("Authorized bank"), {
      target: { value: "808" },
    });
    fireEvent.change(screen.getByLabelText("Authorized branch"), {
      target: { value: "007777" },
    });
    for (const method of ["Inquiry API", "Excel upload", "Reference / UTR"]) {
      await userEvent.click(screen.getByRole("button", { name: method }));
      expect(screen.getByLabelText("Authorized bank")).toHaveValue("808");
      expect(screen.getByLabelText("Authorized branch")).toHaveValue("007777");
    }
    options.configuration = {
      scopes: [
        { orgBank: "555", orgBranch: "8888", label: "Changed server scope" },
      ],
    };
    await userEvent.click(
      screen.getByRole("button", { name: "Refresh queue" }),
    );
    await waitFor(() =>
      expect(
        fetcher.mock.calls.filter(([url]) =>
          url.startsWith("/api/payment-cases?"),
        ),
      ).toHaveLength(2),
    );
    expect(screen.getByLabelText("Authorized bank")).toHaveValue("808");
    expect(screen.getByLabelText("Authorized branch")).toHaveValue("007777");
    expect(
      fetcher.mock.calls.some(([, request]) => request.method === "POST"),
    ).toBe(false);
  });
  it.each([
    { field: "Authorized bank", value: "" },
    { field: "Authorized bank", value: "76A" },
    { field: "Authorized bank", value: "12345678901" },
    { field: "Authorized branch", value: "" },
    { field: "Authorized branch", value: "-1352" },
    { field: "Authorized branch", value: "12345678901" },
  ])(
    "rejects invalid typed $field '$value' before any inquiry request",
    async ({ field, value }) => {
      const fetcher = mockApi();
      render(<FindPayment user={user} onOpen={vi.fn()} />);
      await screen.findByRole("textbox", { name: field });
      fireEvent.change(screen.getByLabelText("Payment reference or UTR"), {
        target: { value: candidate.reference },
      });
      fireEvent.change(screen.getByLabelText(field), { target: { value } });
      const button = screen.getByRole("button", { name: "Find transaction" });
      // A programmatic submit bypasses browser constraint validation, exercising the handler's guard too.
      fireEvent.submit(button.closest("form")!);
      expect(await screen.findByRole("alert")).toHaveTextContent(
        "Enter bank and branch codes using 1 to 10 digits each.",
      );
      expect(
        fetcher.mock.calls.some(([, request]) => request.method === "POST"),
      ).toBe(false);
    },
  );
  it("lets the backend reject typed scope without replacing the codes or showing fallback records", async () => {
    const fetcher = mockApi({
      discover: () =>
        reply(
          {
            code: "DISCOVERY_SCOPE_FORBIDDEN",
            message:
              "The selected bank and branch pair is not authorized for this tenant.",
            requestId: "typed-scope-forbidden",
          },
          403,
        ),
    });
    const onOpen = vi.fn();
    render(<FindPayment user={user} onOpen={onOpen} />);
    await screen.findByRole("textbox", { name: "Authorized bank" });
    fireEvent.change(screen.getByLabelText("Authorized bank"), {
      target: { value: "808" },
    });
    fireEvent.change(screen.getByLabelText("Authorized branch"), {
      target: { value: "007777" },
    });
    await searchReference();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The selected bank and branch pair is not authorized for this tenant.",
    );
    expect(screen.getByText("Request typed-scope-forbidden")).toBeVisible();
    expect(screen.getByLabelText("Authorized bank")).toHaveValue("808");
    expect(screen.getByLabelText("Authorized branch")).toHaveValue("007777");
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
    expect(onOpen).not.toHaveBeenCalled();
    expect(
      fetcher.mock.calls.filter(([url]) => url.endsWith("/lookup")),
    ).toHaveLength(1);
    expect(
      fetcher.mock.calls.some(
        ([url, request]) =>
          url === "/api/payment-cases" && request.method === "POST",
      ),
    ).toBe(false);
  });
  it("clears selection and reason when the bank changes even if its branch number is unchanged", async () => {
    const fetcher = mockApi({
      configuration: {
        scopes: [
          ...config.scopes,
          { orgBank: "111", orgBranch: "0100", label: "Other bank branch" },
        ],
      },
    });
    const onOpen = vi.fn();
    render(<FindPayment user={user} onOpen={onOpen} />);
    await searchReference();
    await selectAndExplain();
    expect(
      screen.getByRole("button", { name: "Open or resume case" }),
    ).toBeEnabled();
    fireEvent.change(screen.getByLabelText("Authorized bank"), {
      target: { value: "111" },
    });
    expect(screen.getByLabelText("Authorized branch")).toHaveValue("0100");
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
    expect(screen.queryByDisplayValue(saved.reason)).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Open or resume case" }),
    ).not.toBeInTheDocument();
    expect(onOpen).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "Excel upload" }));
    const file = new File(["original test bytes"], "other-bank.xlsx");
    await userEvent.upload(
      screen.getByLabelText("Discovery Excel file", { exact: false }),
      file,
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Read Excel records" }),
    );
    await screen.findByRole("radio");
    const upload = fetcher.mock.calls.find(([url]) =>
      url.endsWith("/uploads"),
    )![1].body as FormData;
    expect(upload.get("orgBank")).toBe("111");
    expect(upload.get("orgBranch")).toBe("0100");
  });
  it("aborts the previous bank search and ignores its late response after a new bank search", async () => {
    let finish: ((response: Response) => void) | undefined;
    const next = {
      ...candidate,
      candidateId: "candidate-other-bank",
      reference: "OTHER-BANK-REFERENCE",
      orgBank: "111",
    };
    const fetcher = mockApi({
      configuration: {
        scopes: [
          ...config.scopes,
          { orgBank: "111", orgBranch: "0100", label: "Other bank branch" },
        ],
      },
      discover: (_request, index) =>
        index === 1
          ? new Promise<Response>((resolve) => {
              finish = resolve;
            })
          : reply({ ...batch, items: [next] }),
    });
    render(<FindPayment user={user} onOpen={vi.fn()} />);
    await searchReference();
    const bank = screen.getByLabelText("Authorized bank");
    expect(bank).toBeEnabled();
    fireEvent.change(bank, { target: { value: "111" } });
    expect(
      fetcher.mock.calls.find(([url]) => url.endsWith("/lookup"))![1].signal
        ?.aborted,
    ).toBe(true);
    await searchReference(next.reference);
    await screen.findByRole("radio", {
      name: `Select ${next.reference} branch 0100 SYNTHETIC`,
    });
    await act(async () => {
      finish!(new Response(JSON.stringify(batch)));
    });
    expect(
      screen.queryByRole("radio", {
        name: `Select ${candidate.reference} branch 0100 SYNTHETIC`,
      }),
    ).not.toBeInTheDocument();
    expect(screen.getAllByRole("radio")).toHaveLength(1);
    const requests = fetcher.mock.calls.filter(([url]) =>
      url.endsWith("/lookup"),
    );
    expect(JSON.parse(requests[1][1].body as string)).toMatchObject({
      orgBank: "111",
      orgBranch: "0100",
    });
  });
  it("rejects oversized uploads before sending private file contents", async () => {
    const fetcher = mockApi();
    render(<FindPayment user={user} onOpen={vi.fn()} />);
    await userEvent.click(
      await screen.findByRole("button", { name: "Excel upload" }),
    );
    const file = new File(
      [new Uint8Array(5 * 1024 * 1024 + 1)],
      "oversized.xlsx",
    );
    fireEvent.change(
      screen.getByLabelText("Discovery Excel file", { exact: false }),
      { target: { files: [file] } },
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Read Excel records" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "no larger than 5 MB",
    );
    expect(fetcher.mock.calls.some(([url]) => url.endsWith("/uploads"))).toBe(
      false,
    );
  });
  it("retains the idempotency key after an uncertain create failure and blocks duplicate submission", async () => {
    let finish: ((value: Response) => void) | undefined;
    const fetcher = mockApi({
      create: (_req, index) =>
        index === 1
          ? Promise.reject(new TypeError("Connection lost"))
          : new Promise<Response>((resolve) => {
              finish = resolve;
            }),
    });
    const onOpen = vi.fn();
    render(<FindPayment user={user} onOpen={onOpen} />);
    await searchReference();
    await selectAndExplain();
    const button = screen.getByRole("button", { name: "Open or resume case" });
    await userEvent.click(button);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Could not reach the API",
    );
    expect(onOpen).not.toHaveBeenCalled();
    await userEvent.click(button);
    expect(button).toBeDisabled();
    await userEvent.click(button);
    const attempts = fetcher.mock.calls.filter(
      ([url, req]) => url === "/api/payment-cases" && req.method === "POST",
    );
    expect(attempts).toHaveLength(2);
    expect(new Headers(attempts[0][1].headers).get("Idempotency-Key")).toBe(
      new Headers(attempts[1][1].headers).get("Idempotency-Key"),
    );
    await act(async () => {
      finish!(
        new Response(
          JSON.stringify({ caseId: saved.id, status: "EXISTING", item: saved }),
        ),
      );
    });
    expect(onOpen).toHaveBeenCalledExactlyOnceWith(saved.id);
  });
  it("discards a cancelled late response when a newer search has completed", async () => {
    let finish: ((value: Response) => void) | undefined;
    const next = {
      ...candidate,
      candidateId: "candidate-fixture-B",
      reference: "NEW-FIXTURE-REFERENCE",
    };
    const fetcher = mockApi({
      discover: (_req, index) =>
        index === 1
          ? new Promise<Response>((resolve) => {
              finish = resolve;
            })
          : reply({ ...batch, items: [next] }),
    });
    const onOpen = vi.fn();
    render(<FindPayment user={user} onOpen={onOpen} />);
    await searchReference();
    await userEvent.click(
      screen.getByRole("button", { name: "Cancel search" }),
    );
    expect(
      fetcher.mock.calls.find(([url]) => url.endsWith("/lookup"))![1].signal
        ?.aborted,
    ).toBe(true);
    await searchReference(next.reference);
    await screen.findByRole("radio", {
      name: `Select ${next.reference} branch 0100 SYNTHETIC`,
    });
    await act(async () => {
      finish!(new Response(JSON.stringify(batch)));
    });
    expect(
      screen.queryByRole("radio", {
        name: `Select ${candidate.reference} branch 0100 SYNTHETIC`,
      }),
    ).not.toBeInTheDocument();
    expect(screen.getAllByRole("radio")).toHaveLength(1);
    expect(onOpen).not.toHaveBeenCalled();
  });
  it("shows actionable backend errors and bounded empty coverage without opening a case", async () => {
    const onOpen = vi.fn();
    mockApi({
      discover: (_req, index) =>
        index === 1
          ? reply(
              {
                code: "SOURCE_UNAVAILABLE",
                message: "Inquiry source unavailable. Retry later.",
                requestId: "fixture-request",
              },
              503,
            )
          : reply({
              ...batch,
              items: [],
              truncated: true,
              warnings: ["Only a bounded source window was searched."],
            }),
    });
    render(<FindPayment user={user} onOpen={onOpen} />);
    await searchReference();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Inquiry source unavailable. Retry later.",
    );
    expect(screen.getByText("Request fixture-request")).toBeVisible();
    await userEvent.click(
      screen.getByRole("button", { name: "Find transaction" }),
    );
    await screen.findByRole("heading", {
      name: "No transactions in this result",
    });
    expect(screen.getByText(/response reached its limit/)).toBeVisible();
    expect(
      screen.getByText("Only a bounded source window was searched."),
    ).toBeVisible();
    expect(onOpen).not.toHaveBeenCalled();
  });
  it("disables exact lookup and API listing while allowing local uploads when the inquiry API is disabled", async () => {
    const fetcher = mockApi({ configuration: { mode: "DISABLED" } });
    render(<FindPayment user={user} onOpen={vi.fn()} />);
    await screen.findByLabelText("Payment reference or UTR");
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Find transaction" }),
      ).toBeDisabled(),
    );
    await userEvent.click(screen.getByRole("button", { name: "Inquiry API" }));
    expect(
      screen.getByRole("button", { name: "Find transactions" }),
    ).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Excel upload" }));
    expect(
      screen.getByRole("button", { name: "Read Excel records" }),
    ).toBeEnabled();
    expect(
      fetcher.mock.calls.every(
        ([, request]) => !request.method || request.method === "GET",
      ),
    ).toBe(true);
  });
  it.each(["VIEWER", "ADMIN"])(
    "keeps %s discovery access read-only while letting them inspect saved private cases",
    async (role) => {
      const fetcher = mockApi({ records: [saved] });
      render(<PaymentCasesPage user={{ ...user, role }} onOpen={vi.fn()} />);
      await screen.findByText(/Your role has read-only access/);
      expect(
        screen.queryByRole("button", { name: "Find transaction" }),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByLabelText("Discovery Excel file", { exact: false }),
      ).not.toBeInTheDocument();
      expect(
        screen.getByRole("link", { name: `Open ${saved.id}` }),
      ).toHaveAttribute("href", `/payment-cases/${saved.id}`);
      expect(
        fetcher.mock.calls.every(
          ([, request]) => !request.method || request.method === "GET",
        ),
      ).toBe(true);
    },
  );
  it("requires a reviewer to choose between ambiguous transactions returned by exact UTR lookup", async () => {
    const otherTransaction = {
      ...candidate,
      candidateId: "candidate-other-fixture",
      reference: "OTHER-ORIGINAL-REFERENCE",
    };
    const fetcher = mockApi({
      discover: () =>
        reply({
          ...batch,
          matchStatus: "AMBIGUOUS",
          items: [candidate, otherTransaction],
        }),
    });
    const onOpen = vi.fn();
    render(
      <FindPayment user={{ ...user, role: "REVIEWER" }} onOpen={onOpen} />,
    );
    await screen.findByLabelText("Reference type");
    await userEvent.selectOptions(
      screen.getByLabelText("Reference type"),
      "UTR",
    );
    await searchReference(candidate.utr!);
    expect(await screen.findByRole("status")).toHaveTextContent(
      "Multiple transactions match this reference. Choose the correct transaction",
    );
    expect(screen.getByRole("status")).toHaveTextContent(
      "Host subsequences are grouped within each transaction",
    );
    expect(
      screen.getByRole("button", { name: "Open or resume case" }),
    ).toBeDisabled();
    expect(
      screen
        .getAllByRole("radio")
        .every((radio) => !(radio as HTMLInputElement).checked),
    ).toBe(true);
    expect(onOpen).not.toHaveBeenCalled();
    await userEvent.click(
      await screen.findByRole("radio", {
        name: `Select ${otherTransaction.reference} branch 0100 SYNTHETIC`,
      }),
    );
    expect(
      screen.getByRole("radio", {
        name: `Select ${candidate.reference} branch 0100 SYNTHETIC`,
      }),
    ).not.toBeChecked();
    fireEvent.change(screen.getByLabelText("Investigation reason"), {
      target: {
        value: "Review this selected transaction.\nCheck its recorded status.",
      },
    });
    await userEvent.click(
      screen.getByRole("button", { name: "Open or resume case" }),
    );
    await waitFor(() => expect(onOpen).toHaveBeenCalledOnce());
    const request = fetcher.mock.calls.find(
      ([url, req]) => url === "/api/payment-cases" && req.method === "POST",
    )![1];
    expect(JSON.parse(request.body as string)).toEqual({
      candidateId: otherTransaction.candidateId,
      reason: "Review this selected transaction.\nCheck its recorded status.",
    });
  });
  it("routes a saved private case directly and returns to the payment queue at /cases", async () => {
    mockApi({ records: [saved] });
    window.history.replaceState(null, "", `/payment-cases/${saved.id}`);
    render(<App />);
    await screen.findByRole("heading", { name: "Payment investigation" });
    expect(screen.getByRole("link", { name: "Case queue" })).toHaveClass(
      "active",
    );
    expect(screen.getByText("PRIVATE CASES")).toBeVisible();
    await userEvent.click(
      screen.getByRole("link", { name: "Back to payment cases" }),
    );
    await screen.findByRole("heading", { name: "Case queue" });
    expect(window.location.hash).toBe("");
    expect(window.location.pathname).toBe("/cases");
    expect(screen.getByRole("heading", { name: "Find payment" })).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "Demo cases" }),
    ).not.toBeInTheDocument();
  });
  it("renders the selected payment with evidence currency while retaining its original amount", async () => {
    mockApi({
      caseDetail: {
        ...saved,
        evidenceCurrency: {
          currency: "INR",
          evidenceId: "EVD-DETAIL",
          version: 1,
          sourceKind: "JSON",
        },
      },
    });
    render(<PaymentCaseDetail caseId={saved.id} onBack={vi.fn()} />);
    expect(await screen.findByText(`${saved.amount} INR`)).toBeVisible();
    expect(screen.getByText(`${saved.amount} INR`)).toHaveAttribute(
      "title",
      expect.stringContaining("original discovery amount is unchanged"),
    );
  });
  it("shows the saved case and evidence intake without fabricating an assessment", async () => {
    const fetcher = mockApi();
    render(<PaymentCaseDetail caseId={saved.id} onBack={vi.fn()} />);
    await screen.findByRole("heading", { name: "Payment investigation" });
    expect(screen.getByText(saved.reason)).toBeVisible();
    expect(screen.getByText(`${saved.amount} INR`)).toBeVisible();
    await screen.findByText(
      "No evidence versions have been saved for this case.",
    );
    expect(
      within(
        screen.getByRole("region", { name: "Case evidence" }),
      ).getAllByRole("tab"),
    ).toHaveLength(3);
    expect(
      screen.getByText(/it does not run an AI analysis or execute/),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", { name: /Approve/ }),
    ).not.toBeInTheDocument();
    expect(
      fetcher.mock.calls.every(
        ([, request]) => !request.method || request.method === "GET",
      ),
    ).toBe(true);
  });
  it("aborts an in-flight open when the page unmounts and does not navigate on a late response", async () => {
    let finish: ((value: Response) => void) | undefined;
    const fetcher = mockApi({
      create: () =>
        new Promise<Response>((resolve) => {
          finish = resolve;
        }),
    });
    const onOpen = vi.fn();
    const view = render(<FindPayment user={user} onOpen={onOpen} />);
    await searchReference();
    await selectAndExplain();
    await userEvent.click(
      screen.getByRole("button", { name: "Open or resume case" }),
    );
    view.unmount();
    const request = fetcher.mock.calls.find(
      ([url, req]) => url === "/api/payment-cases" && req.method === "POST",
    )![1];
    expect(request.signal?.aborted).toBe(true);
    await act(async () => {
      finish!(
        new Response(
          JSON.stringify({ caseId: saved.id, status: "CREATED", item: saved }),
        ),
      );
    });
    expect(onOpen).not.toHaveBeenCalled();
  });
});
