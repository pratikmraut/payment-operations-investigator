import { afterEach, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { PaymentCaseDetail } from "./PaymentDiscovery";

vi.mock("./CaseEvidence", () => ({
  CaseEvidence: ({
    onSaved,
    canWrite,
  }: {
    onSaved: () => void;
    canWrite: boolean;
  }) => (
    <div data-testid="evidence-intake" data-writable={String(canWrite)}>
      <input aria-label="Original intake draft" defaultValue="" />
      <button onClick={onSaved}>Complete original evidence save</button>
    </div>
  ),
}));
vi.mock("./CaseInvestigation", () => ({
  CaseInvestigation: ({
    evidenceRevision,
    canWrite,
  }: {
    evidenceRevision: number;
    canWrite: boolean;
  }) => (
    <div data-testid="investigation" data-writable={String(canWrite)}>
      Workbench refresh revision {evidenceRevision}
    </div>
  ),
}));
vi.mock("./CaseManagement", () => ({ CaseManagement: () => null }));
afterEach(() => {
  vi.unstubAllGlobals();
});

it("refreshes the authoritative case evidence availability and timestamp after saving an empty version", async () => {
  let evidenceSaved = false;
  const fetcher = vi.fn(() =>
    Promise.resolve(
      new Response(
        JSON.stringify({
          id: "CASE-ORIGINAL-REFRESH",
          reference: "ORIGINAL-PAYMENT-REFRESH",
          utr: null,
          orgBank: "009",
          orgBranch: "0012",
          hostSubsequences: [],
          initiatedAt: "2026-09-01T10:15:00",
          amount: "0.0007",
          currency: null,
          sourceKind: "EXCEL",
          dataClassification: "PRIVATE_UAT",
          reason: "Original synthetic refresh fixture.",
          status: "OPEN",
          priority: "MEDIUM",
          createdAt: "2026-09-14T10:00:00Z",
          createdBy: "Original analyst",
          updatedAt: evidenceSaved
            ? "2026-09-14T11:00:00Z"
            : "2026-09-14T10:00:00Z",
          evidenceStatus: evidenceSaved
            ? "EMPTY_EVIDENCE_ATTACHED"
            : "DISCOVERY_ONLY",
        }),
      ),
    ),
  );
  vi.stubGlobal("fetch", fetcher);
  render(
    <PaymentCaseDetail
      caseId="CASE-ORIGINAL-REFRESH"
      onBack={vi.fn()}
      canWrite
    />,
  );
  await screen.findByText("Discovery Only");
  fireEvent.change(screen.getByLabelText("Original intake draft"), {
    target: { value: "Retain the user's local draft." },
  });
  evidenceSaved = true;
  fireEvent.click(
    screen.getByRole("button", { name: "Complete original evidence save" }),
  );
  expect(screen.getByText("Workbench refresh revision 1")).toBeInTheDocument();
  await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
  expect(
    await screen.findByText("Empty Evidence Attached"),
  ).toBeInTheDocument();
  expect(screen.getByText("2026-09-14T11:00:00Z")).toBeInTheDocument();
  expect(screen.getByLabelText("Original intake draft")).toHaveValue(
    "Retain the user's local draft.",
  );
});

it("preserves the workbench and intake while reporting a header refresh failure, then retries without another save", async () => {
  const item = {
    id: "CASE-ORIGINAL-REFRESH",
    reference: "ORIGINAL-PAYMENT-REFRESH",
    utr: null,
    orgBank: "009",
    orgBranch: "0012",
    hostSubsequences: [],
    initiatedAt: "",
    amount: "0.0007",
    currency: null,
    sourceKind: "EXCEL",
    dataClassification: "PRIVATE_UAT",
    reason: "Original refresh failure fixture.",
    status: "OPEN",
    priority: "MEDIUM",
    createdAt: "2026-09-14T10:00:00Z",
    updatedAt: "2026-09-14T10:00:00Z",
    createdBy: "Original analyst",
    evidenceStatus: "DISCOVERY_ONLY",
  };
  let calls = 0;
  const fetcher = vi.fn(() => {
    calls++;
    return Promise.resolve(
      new Response(
        JSON.stringify(
          calls === 2
            ? {
                message: "The original fixture header refresh failed.",
                requestId: "original-refresh-request",
              }
            : {
                ...item,
                ...(calls > 2
                  ? {
                      evidenceStatus: "EVIDENCE_ATTACHED",
                      updatedAt: "2026-09-14T11:00:00Z",
                    }
                  : {}),
              },
        ),
        { status: calls === 2 ? 503 : 200 },
      ),
    );
  });
  vi.stubGlobal("fetch", fetcher);
  render(<PaymentCaseDetail caseId={item.id} onBack={vi.fn()} canWrite />);
  await screen.findByText("Discovery Only");
  fireEvent.change(screen.getByLabelText("Original intake draft"), {
    target: { value: "Keep this draft on refresh error." },
  });
  fireEvent.click(
    screen.getByRole("button", { name: "Complete original evidence save" }),
  );
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "original-refresh-request",
  );
  expect(screen.getByText("Workbench refresh revision 1")).toBeInTheDocument();
  expect(screen.getByText("Discovery Only")).toBeInTheDocument();
  expect(screen.getByLabelText("Original intake draft")).toHaveValue(
    "Keep this draft on refresh error.",
  );
  fireEvent.click(screen.getByRole("button", { name: "Try again" }));
  await screen.findByText("Evidence Attached");
  expect(screen.getByText("Workbench refresh revision 1")).toBeInTheDocument();
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  expect(fetcher).toHaveBeenCalledTimes(3);
});

it("keeps archived evidence and investigations readable without allowing new writes", async () => {
  const item = {
    id: "ARCHIVED-CASE",
    caseNumber: "2026091600001",
    lifecycleState: "ARCHIVED",
    reference: "FIXTURE-PAYMENT",
    utr: null,
    orgBank: "099",
    orgBranch: "0012",
    hostSubsequences: [],
    initiatedAt: "",
    amount: "100",
    currency: null,
    sourceKind: "EXCEL",
    dataClassification: "PRIVATE_UAT",
    reason: "Review fixture",
    status: "OPEN",
    priority: "MEDIUM",
    createdAt: "2026-09-16T00:00:00Z",
    updatedAt: "2026-09-16T00:00:00Z",
    createdBy: "fixture",
    evidenceStatus: "EVIDENCE_ATTACHED",
  };
  vi.stubGlobal(
    "fetch",
    vi.fn(() => Promise.resolve(new Response(JSON.stringify(item)))),
  );
  render(<PaymentCaseDetail caseId={item.id} onBack={vi.fn()} canWrite />);
  await screen.findByText(/This case is archived/);
  expect(screen.getByTestId("evidence-intake")).toHaveAttribute(
    "data-writable",
    "false",
  );
  expect(screen.getByTestId("investigation")).toHaveAttribute(
    "data-writable",
    "false",
  );
  expect(screen.getByRole("button", { name: "Export PDF" })).toBeEnabled();
});
