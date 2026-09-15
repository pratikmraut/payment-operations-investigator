import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { EvidenceQuestions } from "./EvidenceQuestions";
import { navigateTo } from "./routing";

vi.mock("./routing", () => ({
  navigateTo: vi.fn(),
  replaceDestination: vi.fn(),
  navigateLink: (event: { preventDefault: () => void }) =>
    event.preventDefault(),
}));
vi.mock("./CaseInvestigation", () => ({
  CaseInvestigation: (props: {
    caseId: string;
    requestedEvidenceId?: string;
    canWrite: boolean;
    onEvidenceChange: (id: string) => void;
  }) => (
    <div
      data-testid="shared-workbench"
      data-case={props.caseId}
      data-version={props.requestedEvidenceId}
      data-writable={String(props.canWrite)}
    >
      <button onClick={() => props.onEvidenceChange("EVIDENCE-OLDER")}>
        Select fixture version
      </button>
    </div>
  ),
}));
const user = {
  id: "operator",
  name: "Original analyst",
  role: "ANALYST",
  tenantId: "original",
};
const saved = (
  id = "ORIGINAL-CASE-A",
  reference = "00012345678901234567890",
) => ({
  id,
  reference,
  utr: "ORIGINAL-UTR-A",
  orgBank: "099",
  orgBranch: "0012",
  amount: "5234.4000",
  currency: null,
  reason: "Review duplicate debit concern",
  evidenceStatus: "EVIDENCE_ATTACHED",
});
function mockCases(items: unknown[], status = 200) {
  const fetcher = vi.fn((_url: string, _request?: RequestInit) =>
    Promise.resolve(
      new Response(
        JSON.stringify(
          status === 200
            ? { items }
            : {
                message: "Cases temporarily unavailable",
                requestId: "fixture-request",
              },
        ),
        { status },
      ),
    ),
  );
  vi.stubGlobal("fetch", fetcher);
  return fetcher;
}
afterEach(() => {
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});
describe("Evidence questions case selection", () => {
  it("retains archived deep links and displays their saved workbench read-only", async () => {
    const item = { ...saved(), lifecycleState: "ARCHIVED" };
    mockCases([item]);
    render(<EvidenceQuestions user={user} caseId={item.id} />);
    await screen.findByLabelText("Payment case");
    expect(screen.getByTestId("shared-workbench")).toHaveAttribute(
      "data-writable",
      "false",
    );
    expect(screen.getByText(/This case is archived/)).toBeVisible();
    expect(
      screen.getByRole("link", { name: /Open archived case/ }),
    ).toHaveAttribute("href", `/payment-cases/${item.id}`);
  });
  it("selects and searches a case number while passing its canonical id to the shared investigation", async () => {
    const numbered = { ...saved(), caseNumber: "2026091600001" };
    mockCases([numbered, saved("OTHER-CASE", "OTHER-REFERENCE")]);
    render(
      <EvidenceQuestions
        user={user}
        caseId={numbered.caseNumber}
        evidenceId="EVIDENCE-EXACT"
      />,
    );
    expect(await screen.findByLabelText("Payment case")).toHaveValue(
      numbered.caseNumber,
    );
    expect(
      screen.getByRole("option", { name: /Case 2026091600001/ }),
    ).toBeInTheDocument();
    expect(screen.getByTestId("shared-workbench")).toHaveAttribute(
      "data-case",
      numbered.id,
    );
    fireEvent.change(screen.getByLabelText("Search saved cases"), {
      target: { value: numbered.caseNumber },
    });
    expect(screen.getByText(/1 matching case\./)).toBeVisible();
    expect(
      screen.getByRole("link", { name: /Open case \/ collect evidence/ }),
    ).toHaveAttribute("href", `/payment-cases/${numbered.caseNumber}`);
    fireEvent.click(screen.getByText("Select fixture version"));
    expect(navigateTo).toHaveBeenCalledWith(
      `/evidences/questions/${numbered.caseNumber}/EVIDENCE-OLDER`,
    );
  });
  it("waits for an explicit case selection and only reads saved cases", async () => {
    const fetcher = mockCases([saved()]);
    render(<EvidenceQuestions user={user} />);
    const picker = await screen.findByLabelText("Payment case");
    expect(picker).toHaveValue("");
    expect(screen.queryByTestId("shared-workbench")).not.toBeInTheDocument();
    fireEvent.change(picker, { target: { value: saved().id } });
    expect(navigateTo).toHaveBeenCalledWith(
      "/evidences/questions/ORIGINAL-CASE-A",
    );
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0][0]).toBe("/api/payment-cases?lifecycle=ALL");
  });
  it("searches reference, UTR, case ID and reason without changing an active case", async () => {
    mockCases([
      saved(),
      {
        ...saved("ORIGINAL-CASE-B", "OTHER-REF"),
        utr: null,
        reason: "Investigate fee",
      },
    ]);
    render(
      <EvidenceQuestions
        user={user}
        caseId={saved().id}
        evidenceId="EVIDENCE-EXACT"
      />,
    );
    const search = await screen.findByLabelText("Search saved cases");
    fireEvent.click(
      screen.getByText("Payment details and investigation reason"),
    );
    expect(screen.getByText("5234.4000 · Currency not supplied")).toBeVisible();
    for (const term of [
      "000123",
      "original-utr",
      "original-case-a",
      "duplicate concern",
    ]) {
      fireEvent.change(search, { target: { value: term } });
      expect(screen.getByText(/1 matching case\./)).toBeVisible();
    }
    fireEvent.change(search, { target: { value: "fee" } });
    expect(screen.getByLabelText("Payment case")).toHaveValue(saved().id);
    expect(screen.getByTestId("shared-workbench")).toHaveAttribute(
      "data-version",
      "EVIDENCE-EXACT",
    );
    expect(navigateTo).not.toHaveBeenCalled();
    fireEvent.change(search, { target: { value: "absent-word" } });
    expect(screen.getByText(/No cases match this search/)).toBeVisible();
    expect(screen.getByTestId("shared-workbench")).toHaveAttribute(
      "data-case",
      saved().id,
    );
  });
  it("binds the shared workbench to the route case and version and updates version links", async () => {
    mockCases([saved()]);
    const view = render(
      <EvidenceQuestions
        user={user}
        caseId={saved().id}
        evidenceId="EVIDENCE-EXACT"
      />,
    );
    const workbench = await screen.findByTestId("shared-workbench");
    expect(workbench).toHaveAttribute("data-version", "EVIDENCE-EXACT");
    fireEvent.click(
      screen.getByRole("button", { name: "Select fixture version" }),
    );
    expect(navigateTo).toHaveBeenCalledWith(
      "/evidences/questions/ORIGINAL-CASE-A/EVIDENCE-OLDER",
    );
    view.rerender(
      <EvidenceQuestions
        user={user}
        caseId={saved().id}
        evidenceId="EVIDENCE-OLDER"
      />,
    );
    expect(workbench).toHaveAttribute("data-version", "EVIDENCE-OLDER");
  });
  it("never mounts another case when a requested identity is unavailable", async () => {
    mockCases([saved()]);
    render(<EvidenceQuestions user={user} caseId="UNAUTHORIZED" />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "unavailable in your authorized workspace",
    );
    expect(screen.queryByTestId("shared-workbench")).not.toBeInTheDocument();
    expect(navigateTo).not.toHaveBeenCalled();
  });
  it("lets viewers inspect the same workbench without writer access", async () => {
    mockCases([saved()]);
    render(
      <EvidenceQuestions
        user={{ ...user, role: "VIEWER" }}
        caseId={saved().id}
      />,
    );
    expect(await screen.findByTestId("shared-workbench")).toHaveAttribute(
      "data-writable",
      "false",
    );
  });
  it("guides an empty workspace back to payment discovery", async () => {
    mockCases([]);
    render(<EvidenceQuestions user={user} />);
    expect(
      await screen.findByRole("heading", {
        name: "Create a payment case first",
      }),
    ).toBeVisible();
    expect(screen.getByRole("link", { name: "Find payment" })).toHaveAttribute(
      "data-case-queue-mode",
      "payment",
    );
  });
  it("shows retryable list failures and rejects malformed case identities", async () => {
    mockCases([], 503);
    render(<EvidenceQuestions user={user} caseId={saved().id} />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Cases temporarily unavailable",
    );
    mockCases([{ ...saved(), reference: 123 }]);
    fireEvent.click(screen.getByRole("button", { name: "Refresh cases" }));
    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("could not be read"),
    );
    mockCases([saved()]);
    fireEvent.click(screen.getByRole("button", { name: "Refresh cases" }));
    expect(await screen.findByTestId("shared-workbench")).toHaveAttribute(
      "data-case",
      saved().id,
    );
  });
});
