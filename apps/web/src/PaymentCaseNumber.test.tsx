import { afterEach, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { PaymentCaseDetail } from "./PaymentDiscovery";

const internalId = `FCR-${"a".repeat(64)}`;
const caseNumber = "2026091600001";
const item = {
  id: internalId,
  caseNumber,
  reference: "ORIGINAL-PAYMENT-REFERENCE",
  utr: null,
  orgBank: "099",
  orgBranch: "0012",
  hostSubsequences: [null],
  initiatedAt: "2026-09-16T10:00:00",
  amount: "1.0007",
  currency: null,
  sourceKind: "EXCEL",
  dataClassification: "PRIVATE_UAT",
  reason: "Original number fixture",
  status: "OPEN",
  priority: "MEDIUM",
  createdAt: "2026-09-16T04:30:00Z",
  updatedAt: "2026-09-16T04:30:00Z",
  createdBy: "fixture-operator",
  evidenceStatus: "DISCOVERY_ONLY",
};
vi.mock("./CaseEvidence", () => ({
  CaseEvidence: ({ caseId }: { caseId: string }) => (
    <div data-testid="evidence" data-case={caseId} />
  ),
}));
vi.mock("./CaseInvestigation", () => ({
  CaseInvestigation: ({
    caseId,
    caseNumber,
  }: {
    caseId: string;
    caseNumber?: string;
  }) => (
    <div
      data-testid="investigation"
      data-case={caseId}
      data-number={caseNumber}
    />
  ),
}));
vi.mock("./CaseManagement", () => ({
  CaseManagement: ({ caseId }: { caseId: string }) => (
    <div data-testid="management" data-case={caseId} />
  ),
}));
vi.mock("./CaseReport", () => ({
  CaseReport: ({ caseId }: { caseId: string }) => (
    <div data-testid="report" data-case={caseId} />
  ),
}));

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

it("resolves a case number then uses the canonical internal id for evidence, questions, management and reports", async () => {
  const fetcher = vi.fn(() =>
    Promise.resolve(new Response(JSON.stringify(item))),
  );
  vi.stubGlobal("fetch", fetcher);
  window.history.replaceState(
    null,
    "",
    `/payment-cases/${caseNumber}?report=1`,
  );
  render(
    <PaymentCaseDetail
      caseId={caseNumber}
      onBack={vi.fn()}
      canWrite
      reportOpen
    />,
  );
  expect(
    await screen.findByText(`PRIVATE PAYMENT CASE · ${caseNumber}`),
  ).toBeVisible();
  expect(fetcher.mock.calls).toHaveLength(1);
  for (const component of [
    "evidence",
    "investigation",
    "management",
    "report",
  ]) {
    expect(screen.getByTestId(component)).toHaveAttribute(
      "data-case",
      internalId,
    );
  }
  expect(screen.getByTestId("investigation")).toHaveAttribute(
    "data-number",
    caseNumber,
  );
  expect(screen.queryByText(internalId)).not.toBeInTheDocument();
  expect(screen.getByText(item.reference)).toBeVisible();
});

it("updates an old bookmarked case URL to the friendly number without dropping its report selection or adding history", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(() => Promise.resolve(new Response(JSON.stringify(item)))),
  );
  window.history.replaceState(
    null,
    "",
    `/payment-cases/${internalId}?report=1`,
  );
  const historyLength = window.history.length;
  render(<PaymentCaseDetail caseId={internalId} onBack={vi.fn()} reportOpen />);
  await waitFor(() =>
    expect(window.location.pathname).toBe(`/payment-cases/${caseNumber}`),
  );
  expect(window.location.search).toBe("?report=1");
  expect(window.history.length).toBe(historyLength);
  expect(screen.getByTestId("report")).toHaveAttribute("data-case", internalId);
});
