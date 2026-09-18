import { afterEach, describe, expect, it, vi } from "vitest";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import {
  CaseReadiness,
  SourceSelectionCoverage,
  sourceSelection,
  type SourceSelection,
} from "./CaseReadiness";
import { setCsrfToken } from "./api";

const evidence = {
  id: "EVD-SYNTHETIC",
  evidenceHash: "a".repeat(64),
  version: 3,
};
const question = "Explain the recorded payment observations.";
const selection: SourceSelection = {
  schemaVersion: "case-evidence-selection-v1",
  method: "question-ranked-whole-rows-v1",
  mode: "SELECTED",
  totalRows: 3,
  selectedRows: 2,
  omittedRows: 1,
  selectedRowIds: ["PAYMENT-ROW-1", "HOST-ROW-2"],
  groups: {
    PAYMENT: { suppliedRows: 1, selectedRows: 1, omittedRows: 0 },
    HOST: { suppliedRows: 2, selectedRows: 1, omittedRows: 1 },
    HISTORY: { suppliedRows: 0, selectedRows: 0, omittedRows: 0 },
    STATUS: { suppliedRows: 0, selectedRows: 0, omittedRows: 0 },
  },
  meaning: "Original synthetic coverage; omitted records may remain relevant.",
};
const response = () => ({
  caseId: "CASE-SYNTHETIC",
  evidenceId: evidence.id,
  evidenceHash: evidence.evidenceHash,
  evidenceVersion: evidence.version,
  ready: true,
  selection,
  knowledgeVersion: "b".repeat(64),
  knowledgeIndexStatus: "CURRENT",
  modelAvailabilityChecked: false,
  modelContextChecked: false,
  warnings: ["Source completion is unverified."],
});
const reply = (value: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(value), { status }));
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  setCsrfToken(null);
});

describe("Case readiness", () => {
  it("runs only after the explicit action and binds the exact question and evidence", async () => {
    const fetcher = vi.fn(() => reply(response()));
    vi.stubGlobal("fetch", fetcher);
    setCsrfToken("synthetic-csrf");
    render(
      <CaseReadiness
        caseId="CASE-SYNTHETIC"
        evidence={evidence}
        question={question}
      />,
    );
    expect(fetcher).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Check readiness" }));
    await screen.findByText(/Ready to prepare this question/);
    expect(fetcher).toHaveBeenCalledTimes(1);
    const [url, init] = fetcher.mock.calls[0] as unknown as [
      string,
      RequestInit,
    ];
    expect(url).toBe(
      "/api/payment-cases/CASE-SYNTHETIC/investigations/readiness",
    );
    expect(init.method).toBe("POST");
    expect(new Headers(init.headers).get("X-CSRF-Token")).toBe(
      "synthetic-csrf",
    );
    expect(JSON.parse(init.body as string)).toEqual({
      question,
      evidenceId: evidence.id,
      evidenceHash: evidence.evidenceHash,
    });
    expect(screen.getByText("2 of 3 source rows selected")).toBeVisible();
    expect(
      screen.getByText(/outside this question's context/),
    ).toHaveTextContent("relevant or conflicting facts");
    expect(screen.getByText(/final prompt budget/)).toBeVisible();
  });

  it("shows missing knowledge honestly and never invokes investigation endpoints", async () => {
    const fetcher = vi.fn(() =>
      reply({ ...response(), ready: false, knowledgeIndexStatus: "MISSING" }),
    );
    vi.stubGlobal("fetch", fetcher);
    render(
      <CaseReadiness
        caseId="CASE-SYNTHETIC"
        evidence={evidence}
        question={question}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "Check readiness" }));
    await screen.findByText(/Knowledge embeddings need refreshing/);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it("rejects another case or version response", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => reply({ ...response(), evidenceHash: "c".repeat(64) })),
    );
    render(
      <CaseReadiness
        caseId="CASE-SYNTHETIC"
        evidence={evidence}
        question={question}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "Check readiness" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "does not match this case",
    );
    expect(
      screen.queryByText("2 of 3 source rows selected"),
    ).not.toBeInTheDocument();
  });

  it("discards a late response after the user changes the question", async () => {
    let resolve!: (value: Response) => void;
    const fetcher = vi.fn(
      () =>
        new Promise<Response>((done) => {
          resolve = done;
        }),
    );
    vi.stubGlobal("fetch", fetcher);
    const view = render(
      <CaseReadiness
        caseId="CASE-SYNTHETIC"
        evidence={evidence}
        question={question}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "Check readiness" }));
    expect(
      screen.getByRole("button", { name: "Checking readiness…" }),
    ).toBeDisabled();
    view.rerender(
      <CaseReadiness
        caseId="CASE-SYNTHETIC"
        evidence={evidence}
        question="A different question"
      />,
    );
    await act(async () => resolve(new Response(JSON.stringify(response()))));
    expect(
      screen.queryByText(/Ready to prepare this question/),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Check readiness" }),
    ).toBeEnabled();
  });

  it("requires a nonblank question and saved evidence and supports safe API-error retry", async () => {
    const fetcher = vi
      .fn()
      .mockImplementationOnce(() =>
        reply(
          {
            code: "CASE_INVESTIGATION_EVIDENCE_LIMIT",
            message: "Required sources exceed capacity.",
            requestId: "synthetic-request",
          },
          422,
        ),
      )
      .mockImplementationOnce(() => reply(response()));
    vi.stubGlobal("fetch", fetcher);
    const view = render(
      <CaseReadiness caseId="CASE-SYNTHETIC" evidence={null} question="" />,
    );
    expect(
      screen.getByRole("button", { name: "Check readiness" }),
    ).toBeDisabled();
    view.rerender(
      <CaseReadiness
        caseId="CASE-SYNTHETIC"
        evidence={evidence}
        question={question}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "Check readiness" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Required sources exceed capacity",
    );
    fireEvent.click(screen.getByRole("button", { name: "Check readiness" }));
    await waitFor(() =>
      expect(screen.queryByRole("alert")).not.toBeInTheDocument(),
    );
    await screen.findByText(/Ready to prepare this question/);
  });
});

describe("Frozen source coverage", () => {
  it("leaves historical jobs with no selection metadata unchanged", () => {
    const view = render(<SourceSelectionCoverage />);
    expect(view.container).toBeEmptyDOMElement();
  });
  it.each([
    { ...selection, mode: ["SELECTED"] },
    { ...selection, omittedRows: 5 },
    { ...selection, selectedRowIds: ["PAYMENT-ROW-1", "HOST-ROW-999"] },
    { ...selection, selectedRowIds: ["PAYMENT-ROW-1", "PAYMENT-ROW-1"] },
    {
      ...selection,
      groups: {
        ...selection.groups,
        HOST: { suppliedRows: 2, selectedRows: 1, omittedRows: 0 },
      },
    },
  ])("rejects inconsistent or malformed selection metadata", (value) => {
    expect(sourceSelection(value)).toBeNull();
    render(<SourceSelectionCoverage selection={value} />);
    expect(screen.getByRole("alert")).toHaveTextContent(
      "could not be verified",
    );
  });
});
