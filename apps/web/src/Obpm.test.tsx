import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App, { DemoCasesPage } from "./App";
import { ObpmEvidence, ObpmImport, elapsedAt } from "./Obpm";
import { setCsrfToken } from "./api";
import type { CaseDetail, ObpmSnapshot, User } from "./types";

const analyst: User = {
  id: "analyst",
  name: "Demo analyst",
  role: "ANALYST",
  tenantId: "northstar",
};
const snapshot: ObpmSnapshot = {
  schemaVersion: "obpm-evidence-v1",
  dataClassification: "SYNTHETIC",
  snapshotId: "SYNTHETIC-SNAPSHOT-1",
  mappingVersion: "test-v1",
  extractedAt: "2026-09-12T10:10:00Z",
  source: {
    deploymentId: "SYNTHETIC-OBPM",
    releaseFamily: "14.7",
    exactMaintenanceRelease: null,
    hostCode: "DEMO-HOST",
    branchCode: "DEMO-BRANCH",
  },
  payment: {
    sourcePaymentId: "DEMO-NEFT-1",
    rail: "NEFT",
    direction: "OUTBOUND",
    sourceAmountDecimal: "15000.25",
    amountMinor: 1500025,
    currency: "INR",
    activationDate: "2026-09-12",
    createdAt: "2026-09-12T09:59:00Z",
    nativeTransactionStatus: null,
    statusUnavailableReason: "Native status was not extracted.",
  },
  queueRecords: [
    {
      evidenceId: "QUEUE-EVIDENCE-1",
      sourcePaymentId: "DEMO-NEFT-1",
      queueReference: "QUEUE-1",
      requestAttemptId: "ATTEMPT-1",
      nativeQueueCode: "EC",
      nativeResponseStatus: "T",
      enteredAt: "2026-09-12T10:00:00Z",
      exitedAt: null,
      isCurrentQueueRecord: true,
      observedAt: "2026-09-12T10:09:00Z",
    },
  ],
  externalRequestAttempts: [
    {
      evidenceId: "REQUEST-EVIDENCE-1",
      requestAttemptId: "ATTEMPT-1",
      sourcePaymentId: "DEMO-NEFT-1",
      requestType: "ECA",
      requestedAt: "2026-09-12T10:00:00Z",
      timeoutRecordedAt: "2026-09-12T10:01:00Z",
      externalSystemFinalOutcome: null,
    },
  ],
  messages: [],
  accountingEntries: [],
  sourceCoverage: {
    queueRecords: {
      status: "COMPLETE",
      scope: "Only this payment and its declared cutoff",
      asOf: "2026-09-12T10:10:00Z",
      paginationComplete: true,
    },
    messages: {
      status: "NOT_REQUESTED",
      reason: "Messages outside this synthetic slice.",
    },
    externalCoreResponses: {
      status: "UNAVAILABLE",
      reason: "The core response extract was unavailable.",
    },
    accountingEntries: {
      status: "UNAVAILABLE",
      reason: "No accounting extract supplied.",
    },
  },
};
const detail: CaseDetail = {
  id: "CASE-NEFT-1",
  paymentId: "DEMO-NEFT-1",
  title: "Synthetic NEFT ECA timeout",
  description: "Imported original synthetic snapshot",
  priority: "HIGH",
  status: "OPEN",
  amountMinor: 1500025,
  currency: "INR",
  rail: "NEFT",
  domain: "OBPM_NEFT",
  evidenceVersion: 2,
  evidenceHash: "hash-current",
  createdAt: snapshot.extractedAt,
  updatedAt: snapshot.extractedAt,
  version: 3,
  tags: [],
  tenantId: "northstar",
  events: [],
  ledgerEntries: [],
  webhooks: [],
  provider: null,
  policyDate: "2026-09-12",
  obpm: snapshot,
};
const receipt = {
  importId: "IMPORT-1",
  caseId: detail.id,
  status: "CREATED",
  evidenceVersion: 1,
  evidenceHash: "hash-one",
  caseVersion: 1,
  importedAt: "2026-09-12T10:12:00Z",
};
const reply = (data: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(data), { status }));
afterEach(() => {
  vi.unstubAllGlobals();
  setCsrfToken(null);
  window.history.replaceState({}, "", "/");
});
function importsApi(status = "CREATED", failure = false) {
  const fetcher = vi.fn((url: string, options: RequestInit) => {
    if (url === "/api/obpm/inquiry")
      return reply({
        enabled: false,
        mode: "SYNTHETIC_MOCK",
        referenceType: "PAYMENT_REFERENCE",
        examples: [],
      });
    if (url === "/api/obpm/samples")
      return reply({
        items: [
          {
            id: "timeout",
            title: "Original ECA timeout sample",
            description: "An original synthetic ECA exception.",
            payload: snapshot,
          },
        ],
      });
    if (url === "/api/obpm/imports" && options.method === "POST")
      return failure
        ? reply(
            {
              code: "STALE_SNAPSHOT",
              message: "A newer source cutoff is already imported.",
              requestId: "REQ-STALE",
            },
            409,
          )
        : reply({ ...receipt, status });
    if (url === "/api/obpm/imports") return reply({ items: [receipt] });
    return Promise.reject(new Error(`Unexpected request: ${url}`));
  });
  vi.stubGlobal("fetch", fetcher);
  return fetcher;
}
describe("synthetic NEFT imports", () => {
  it("preserves the isolated legacy queue when a synthetic import is rejected", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string, options: RequestInit) => {
        if (url === "/api/obpm/inquiry")
          return reply({
            enabled: false,
            mode: "SYNTHETIC_MOCK",
            referenceType: "PAYMENT_REFERENCE",
            examples: [],
          });
        if (url === "/api/auth/me")
          return reply({ user: analyst, csrfToken: "csrf-neft" });
        if (url === "/api/payment-cases") return reply({ items: [], total: 0 });
        if (url === "/api/payment-cases/dashboard")
          return reply({
            openCases: 0,
            highPriorityCases: 0,
            awaitingReview: 0,
            resolvedCases: 0,
          });
        if (url === "/api/payment-discovery/config")
          return reply({
            mode: "DISABLED",
            today: "2026-09-14",
            timezone: "Asia/Kolkata",
            maxRecords: 200,
            scopes: [],
            directLookupScope: "Previously loaded local records",
          });
        if (url === "/api/dashboard")
          return reply({
            openCases: 1,
            highPriorityCases: 1,
            awaitingReview: 0,
            resolvedCases: 0,
            totalAmountMinor: detail.amountMinor,
            currency: "INR",
            recentActivity: [],
            mode: "synthetic",
          });
        if (url.startsWith("/api/cases?"))
          return reply({
            items: [
              {
                ...detail,
                id: "CASE-EXISTING",
                title: "Existing payment exception",
                domain: undefined,
                merchant: "Original merchant",
                rail: "SIMULATED_TRANSFER",
              },
            ],
            total: 1,
          });
        if (url === "/api/obpm/samples")
          return reply({
            items: [
              {
                id: "timeout",
                title: "Original ECA timeout sample",
                description: "Synthetic",
                payload: snapshot,
              },
            ],
          });
        if (url === "/api/obpm/imports" && options.method === "POST")
          return reply(
            {
              code: "STALE_SNAPSHOT",
              message: "A newer source cutoff is already imported.",
            },
            409,
          );
        if (url === "/api/obpm/imports") return reply({ items: [] });
        return Promise.reject(new Error(`Unexpected request: ${url}`));
      }),
    );
    render(<DemoCasesPage user={analyst} />);
    await screen.findByText("Existing payment exception");
    await user.click(
      screen.getByRole("button", { name: "Import synthetic NEFT evidence" }),
    );
    await screen.findByRole("option", { name: "Original ECA timeout sample" });
    await user.selectOptions(
      screen.getByLabelText("Synthetic sample"),
      "timeout",
    );
    await user.click(screen.getByRole("button", { name: "Import snapshot" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "A newer source cutoff is already imported.",
    );
    expect(screen.getByText("Existing payment exception")).toBeVisible();
    expect(screen.getByText("Original merchant")).toBeVisible();
    expect(
      screen.getByRole("link", { name: "Open CASE-EXISTING" }),
    ).toHaveAttribute("href", "/cases/CASE-EXISTING");
  });
  it.each(["CREATED", "UPDATED", "UNCHANGED"])(
    "sends the exact selected snapshot with CSRF and displays %s only after the API receipt",
    async (status) => {
      const user = userEvent.setup();
      const fetcher = importsApi(status);
      const imported = vi.fn();
      setCsrfToken("csrf-neft");
      render(
        <ObpmImport user={analyst} onImported={imported} onClose={vi.fn()} />,
      );
      await screen.findByRole("option", {
        name: "Original ECA timeout sample",
      });
      await user.selectOptions(
        screen.getByLabelText("Synthetic sample"),
        "timeout",
      );
      expect(
        JSON.parse(
          (screen.getByLabelText("Snapshot JSON") as HTMLTextAreaElement).value,
        ),
      ).toEqual(snapshot);
      await user.click(screen.getByRole("button", { name: "Import snapshot" }));
      await waitFor(() => expect(imported).toHaveBeenCalledOnce());
      const call = fetcher.mock.calls.find(
        ([, options]) => options.method === "POST",
      )!;
      expect(call[1].headers).toBeInstanceOf(Headers);
      expect((call[1].headers as Headers).get("X-CSRF-Token")).toBe(
        "csrf-neft",
      );
      expect(call[1].credentials).toBe("same-origin");
      expect(JSON.parse(call[1].body as string)).toEqual(snapshot);
      expect(
        screen.getByText(
          new RegExp(
            `${status[0]}${status.slice(1).toLowerCase()} · CASE-NEFT-1`,
          ),
        ),
      ).toBeVisible();
      expect(
        screen.getByRole("link", { name: "Open imported case" }),
      ).toHaveAttribute("href", "/cases/CASE-NEFT-1");
      if (status === "UNCHANGED")
        expect(screen.getByText(/No evidence version was added/)).toBeVisible();
    },
  );
  it("preserves receipts and editable source on rejection without reporting success", async () => {
    const user = userEvent.setup();
    importsApi("CREATED", true);
    const imported = vi.fn();
    render(
      <ObpmImport user={analyst} onImported={imported} onClose={vi.fn()} />,
    );
    await screen.findByRole("option", { name: "Original ECA timeout sample" });
    await user.selectOptions(
      screen.getByLabelText("Synthetic sample"),
      "timeout",
    );
    await user.click(screen.getByRole("button", { name: "Import snapshot" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "A newer source cutoff is already imported.",
    );
    expect(screen.getByRole("alert")).toHaveTextContent("REQ-STALE");
    expect(screen.getByText("IMPORT-1")).toBeVisible();
    expect(screen.getByLabelText("Snapshot JSON")).toHaveValue(
      JSON.stringify(snapshot, null, 2),
    );
    expect(
      screen.queryByRole("link", { name: "Open imported case" }),
    ).not.toBeInTheDocument();
    expect(imported).not.toHaveBeenCalled();
  });
  it("does not submit invalid JSON or expose import commands to a viewer", async () => {
    const user = userEvent.setup();
    const fetcher = importsApi();
    const view = render(
      <ObpmImport user={analyst} onImported={vi.fn()} onClose={vi.fn()} />,
    );
    await user.type(screen.getByLabelText("Snapshot JSON"), "invalid");
    await user.click(screen.getByRole("button", { name: "Import snapshot" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Snapshot JSON is not valid",
    );
    expect(
      fetcher.mock.calls.some(([, options]) => options.method === "POST"),
    ).toBe(false);
    view.rerender(
      <ObpmImport
        user={{ ...analyst, role: "VIEWER" }}
        onImported={vi.fn()}
        onClose={vi.fn()}
      />,
    );
    expect(
      screen.queryByRole("button", { name: "Import snapshot" }),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText("Snapshot JSON")).toHaveAttribute("readonly");
  });
});
describe("banking evidence scope", () => {
  it("retains unknown native outcomes and raw queue codes, and fixes queue age at the extraction cutoff", () => {
    render(
      <ObpmEvidence
        item={detail}
        focusedId="QUEUE-EVIDENCE-1"
        view="records"
      />,
    );
    expect(screen.getByText("EC / T")).toBeVisible();
    expect(screen.getByText("Native status was not extracted.")).toBeVisible();
    expect(
      within(
        screen.getByText("External system final outcome").parentElement!,
      ).getByText("Unknown · not supplied"),
    ).toBeVisible();
    expect(
      within(
        screen.getByText("Queue age at snapshot").parentElement!,
      ).getByText("10m 0s"),
    ).toBeVisible();
    expect(
      screen
        .getByText("QUEUE-EVIDENCE-1", { selector: "h4 code" })
        .closest("article"),
    ).toHaveClass("focused");
    expect(screen.getByText("15000.25")).toBeVisible();
    expect(screen.getByText("₹15,000.25")).toBeVisible();
    expect(screen.queryByText("Provider snapshot")).not.toBeInTheDocument();
    expect(screen.queryByText("Webhook deliveries")).not.toBeInTheDocument();
  });
  it("does not turn unknown codes or missing/invalid timestamps into inferred statuses or zero age", () => {
    const unknown: CaseDetail = {
      ...detail,
      obpm: {
        ...snapshot,
        queueRecords: [
          {
            ...snapshot.queueRecords[0],
            nativeQueueCode: "ZZ",
            nativeResponseStatus: "NEW_CODE",
            enteredAt: null,
          },
        ],
      },
    };
    render(<ObpmEvidence item={unknown} focusedId={null} view="records" />);
    expect(screen.getByText("ZZ / NEW_CODE")).toBeVisible();
    expect(screen.getByText("Unknown · timestamp missing")).toBeVisible();
    expect(elapsedAt(snapshot.extractedAt, "2026-09-12T09:00:00Z")).toBe(
      "Unknown · timestamps inconsistent",
    );
    expect(elapsedAt("invalid", snapshot.extractedAt)).toBe(
      "Unknown · timestamps inconsistent",
    );
  });
  it("shows declared coverage and immutable versions without implying missing accounting is zero", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() =>
        reply({
          items: [
            {
              evidenceVersion: 1,
              evidenceHash: "hash-one",
              sourceSnapshotId: "SNAPSHOT-OLD",
              extractedAt: "2026-09-12T09:00:00Z",
              importedAt: "2026-09-12T09:05:00Z",
            },
            {
              evidenceVersion: 2,
              evidenceHash: "hash-current",
              sourceSnapshotId: snapshot.snapshotId,
              extractedAt: snapshot.extractedAt,
              importedAt: "2026-09-12T10:12:00Z",
            },
          ],
        }),
      ),
    );
    render(<ObpmEvidence item={detail} focusedId={null} view="coverage" />);
    expect(screen.getByText("No accounting extract supplied.")).toBeVisible();
    expect(
      screen.getByText("Only this payment and its declared cutoff"),
    ).toBeVisible();
    expect(
      screen.getByText(/not zero balances or proof of settlement/),
    ).toBeVisible();
    expect(
      await screen.findByRole("heading", { name: "Evidence v2 · Current" }),
    ).toBeVisible();
    expect(screen.getByText("SNAPSHOT-OLD")).toBeVisible();
    expect(screen.queryByText("Ledger entries")).not.toBeInTheDocument();
  });
  it("uses banking tabs in the actual case workspace while retaining investigation controls", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string) => {
        if (url === "/api/auth/me")
          return reply({ user: analyst, csrfToken: "csrf" });
        if (url === `/api/cases/${detail.id}`) return reply(detail);
        if (
          url.endsWith("/investigations") ||
          url.endsWith("/audit") ||
          url.endsWith("/evidence-versions")
        )
          return reply({ items: [] });
        return Promise.reject(new Error(`Unexpected request: ${url}`));
      }),
    );
    window.history.replaceState({}, "", `/cases/${detail.id}`);
    render(<App />);
    await screen.findByRole("heading", { name: detail.title });
    expect(screen.getByRole("button", { name: /Bank evidence/ })).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Run investigation" }),
    ).toBeVisible();
    expect(screen.queryByText("Provider snapshot")).not.toBeInTheDocument();
    expect(screen.queryByText("No timeline events")).not.toBeInTheDocument();
    expect(screen.getByText("Payment created (source)")).toBeVisible();
    expect(screen.queryByText("Case opened")).not.toBeInTheDocument();
    expect(
      screen.getByText(
        "Run an investigation to connect banking queue records, ECA request attempts, source coverage, and operating guidance.",
      ),
    ).toBeVisible();
    await user.click(screen.getByRole("button", { name: /Source coverage/ }));
    expect(screen.getByText("No accounting extract supplied.")).toBeVisible();
    expect(screen.queryByText("Ledger entries")).not.toBeInTheDocument();
  });
});
