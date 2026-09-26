import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { EvidenceQuestions } from "./EvidenceQuestions";
import { casePageFixture } from "./testCasePage";
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
      <input aria-label="Fixture question draft" defaultValue="" />
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
  const fetcher = vi.fn((url: string, _request?: RequestInit) => {
    const path = new URL(url, "http://fixture.test");
    const item = items.find(
      (value) =>
        !!value &&
        typeof value === "object" &&
        [(value as any).id, (value as any).caseNumber].includes(
          decodeURIComponent(path.pathname.split("/").at(-1)!),
        ),
    );
    const list = path.pathname === "/api/payment-cases";
    return Promise.resolve(
      new Response(
        JSON.stringify(
          status !== 200
            ? {
                message: "Cases temporarily unavailable",
                requestId: "fixture-request",
              }
            : list
              ? casePageFixture(url, items as Record<string, unknown>[])
              : (item ?? { message: "Unavailable", code: "NOT_FOUND" }),
        ),
        { status: status !== 200 ? status : list || item ? 200 : 404 },
      ),
    );
  });
  vi.stubGlobal("fetch", fetcher);
  return fetcher;
}
afterEach(() => {
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});
describe("Evidence questions case selection", () => {
  it("shows evidence currency for the selected case with its source version", async () => {
    const selected = {
      ...saved(),
      evidenceCurrency: {
        currency: "INR",
        evidenceId: "EVD-QA",
        version: 1,
        sourceKind: "JSON",
      },
    };
    mockCases([selected]);
    render(<EvidenceQuestions user={user} caseId={selected.id} />);
    await screen.findByTestId("shared-workbench");
    fireEvent.click(
      screen.getByText("Payment details and investigation reason"),
    );
    expect(screen.getByText("5234.4000 INR")).toBeVisible();
  });
  it("loads a deep-selected case outside the first page and keeps its draft while paging, filtering and list failure", async () => {
    const items = Array.from({ length: 23 }, (_, index) =>
      saved(`CASE-${index + 1}`, `REFERENCE-${index + 1}`),
    );
    const fetcher = mockCases(items);
    render(
      <EvidenceQuestions
        user={user}
        caseId="CASE-23"
        evidenceId="SAVED-VERSION"
      />,
    );
    const workbench = await screen.findByTestId("shared-workbench");
    fireEvent.change(screen.getByLabelText("Fixture question draft"), {
      target: { value: "My unfinished investigation" },
    });
    expect(
      screen.getByRole("option", { name: /REFERENCE-23.*outside this page/ }),
    ).toBeVisible();
    expect(screen.getAllByRole("option")).toHaveLength(12); // prompt, ten results, pinned selection
    expect(
      fetcher.mock.calls.some(([url]) => url === "/api/payment-cases/CASE-23"),
    ).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Next cases" }));
    await screen.findByText("Page 2 of 3 · 10 per page");
    expect(screen.getByTestId("shared-workbench")).toBe(workbench);
    expect(screen.getByLabelText("Fixture question draft")).toHaveValue(
      "My unfinished investigation",
    );
    fireEvent.change(screen.getByLabelText("Search saved cases"), {
      target: { value: "REFERENCE-1" },
    });
    await screen.findByText("Page 1 of 2 · 10 per page");
    expect(screen.getByTestId("shared-workbench")).toBe(workbench);
    expect(screen.getByLabelText("Payment case")).toHaveValue("CASE-23");
    const last = fetcher.mock.calls.at(-1)!;
    expect(last[0]).toContain("search=REFERENCE-1");
    expect(last[0]).toContain("page=1");
    fetcher.mockImplementationOnce(() =>
      Promise.resolve(
        new Response(JSON.stringify({ message: "List unavailable" }), {
          status: 503,
        }),
      ),
    );
    fireEvent.change(screen.getByLabelText("Search saved cases"), {
      target: { value: "failure" },
    });
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "List unavailable",
    );
    expect(screen.getByTestId("shared-workbench")).toBe(workbench);
    expect(screen.getByLabelText("Fixture question draft")).toHaveValue(
      "My unfinished investigation",
    );
    expect(navigateTo).not.toHaveBeenCalled();
  });
  it("retains archived deep links and displays their saved workbench read-only", async () => {
    const item = { ...saved(), lifecycleState: "ARCHIVED" };
    mockCases([item]);
    render(<EvidenceQuestions user={user} caseId={item.id} />);
    await screen.findByTestId("shared-workbench");
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
    await waitFor(() =>
      expect(screen.getByLabelText("Payment case")).toHaveValue(
        numbered.caseNumber,
      ),
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
    expect(await screen.findByText(/1 matching case\./)).toBeVisible();
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
    await screen.findByText(/1 matching case\./);
    const picker = screen.getByLabelText("Payment case");
    expect(picker).toHaveValue("");
    expect(screen.queryByTestId("shared-workbench")).not.toBeInTheDocument();
    fireEvent.change(picker, { target: { value: saved().id } });
    expect(navigateTo).toHaveBeenCalledWith(
      "/evidences/questions/ORIGINAL-CASE-A",
    );
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(
      new URL(fetcher.mock.calls[0][0], "http://fixture.test").searchParams.get(
        "pageSize",
      ),
    ).toBe("10");
    expect(fetcher.mock.calls[0][0]).toContain("lifecycle=ALL");
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
    await screen.findByTestId("shared-workbench");
    const search = screen.getByLabelText("Search saved cases");
    fireEvent.click(
      screen.getByText("Payment details and investigation reason"),
    );
    expect(screen.getByText("5234.4000 INR")).toBeVisible();
    for (const term of [
      "000123",
      "original-utr",
      "original-case-a",
      "duplicate concern",
    ]) {
      fireEvent.change(search, { target: { value: term } });
      expect(await screen.findByText(/1 matching case\./)).toBeVisible();
    }
    fireEvent.change(search, { target: { value: "fee" } });
    expect(screen.getByLabelText("Payment case")).toHaveValue(saved().id);
    expect(screen.getByTestId("shared-workbench")).toHaveAttribute(
      "data-version",
      "EVIDENCE-EXACT",
    );
    expect(navigateTo).not.toHaveBeenCalled();
    fireEvent.change(search, { target: { value: "absent-word" } });
    expect(await screen.findByText(/No cases match this search/)).toBeVisible();
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
    expect((await screen.findAllByRole("alert"))[0]).toHaveTextContent(
      "Cases temporarily unavailable",
    );
    mockCases([{ ...saved(), reference: 123 }]);
    fireEvent.click(screen.getByRole("button", { name: "Refresh cases" }));
    await waitFor(() =>
      expect(screen.getAllByRole("alert")[0]).toHaveTextContent(
        "could not be read",
      ),
    );
    mockCases([saved()]);
    fireEvent.click(screen.getByRole("button", { name: "Refresh cases" }));
    expect(await screen.findByTestId("shared-workbench")).toHaveAttribute(
      "data-case",
      saved().id,
    );
  });
});
