import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { CaseReport } from "./CaseReport";
import { setCsrfToken } from "./api";

const workbench = {
  caseId: "PC-1",
  latestEvidenceId: "EV-2",
  evidence: [
    {
      id: "EV-2",
      caseId: "PC-1",
      version: 2,
      evidenceHash: "b".repeat(64),
      sourceKind: "API",
    },
    {
      id: "EV-1",
      caseId: "PC-1",
      version: 1,
      evidenceHash: "a".repeat(64),
      sourceKind: "JSON",
    },
  ],
  investigations: [
    {
      id: "JOB-2",
      caseId: "PC-1",
      evidenceId: "EV-2",
      evidenceVersion: 2,
      status: "COMPLETED",
      question: "Review latest observations",
    },
    {
      id: "JOB-1",
      caseId: "PC-1",
      evidenceId: "EV-1",
      evidenceVersion: 1,
      status: "COMPLETED",
      question: "Review earlier observations",
    },
    {
      id: "JOB-3",
      caseId: "PC-1",
      evidenceId: "EV-2",
      evidenceVersion: 2,
      status: "RUNNING",
      question: "Pending question",
    },
  ],
};
type Scope = {
  evidenceId: string | null;
  investigationIds: string[];
  includeEvidenceRows: boolean;
  reportMode?: "SUMMARY" | "DETAILED";
};
function preview(scope: Scope) {
  return {
    schemaVersion: "payment-case-report-v1",
    reportId: "RPT-1",
    reportHash: "f".repeat(64),
    generatedAt: "2026-09-14T10:00:00Z",
    generatedBy: { id: "viewer", name: "Viewer", role: "VIEWER" },
    case: { id: "PC-1", reference: "00012345678901234567890" },
    scope,
    evidence: workbench.evidence.find((e) => e.id === scope.evidenceId) ?? null,
    investigations: workbench.investigations.filter((j) =>
      scope.investigationIds.includes(j.id),
    ),
    review: { status: "PENDING", conclusion: null },
    warnings: ["Each answer retains its own saved sources."],
  };
}
const json = (value: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(value), { status }));
function mock(
  options: {
    work?: unknown;
    generate?: (scope: Scope, request: RequestInit) => Promise<Response>;
    pdf?: () => Promise<Response>;
  } = {},
) {
  const fetcher = vi.fn((url: string, request: RequestInit) => {
    if (url.endsWith("/workbench")) return json(options.work ?? workbench);
    if (url.endsWith("/report-preview")) {
      const scope = JSON.parse(request.body as string);
      return options.generate?.(scope, request) ?? json(preview(scope));
    }
    if (url.endsWith("/report.pdf"))
      return (
        options.pdf?.() ??
        Promise.resolve(
          new Response("%PDF-1.4 fixture", {
            headers: { "Content-Type": "application/pdf" },
          }),
        )
      );
    throw new Error(url);
  });
  vi.stubGlobal("fetch", fetcher);
  return fetcher;
}
async function page() {
  render(<CaseReport caseId="PC-1" onClose={vi.fn()} />);
  await screen.findByLabelText("Report evidence version");
}
const checked = (question: string) =>
  screen.getByRole("checkbox", { name: new RegExp(question) });
const requests = (fetcher: ReturnType<typeof mock>, suffix: string) =>
  fetcher.mock.calls.filter(([url]) => url.endsWith(suffix));
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  setCsrfToken(null);
});

describe("frozen payment case reports", () => {
  it("shows the frozen case number while validating the internal case identity", async () => {
    mock({
      generate: (scope) => {
        const value = preview(scope);
        return json({
          ...value,
          case: { ...value.case, caseNumber: "2026091600001" },
        });
      },
    });
    await page();
    fireEvent.click(screen.getByRole("button", { name: "Preview report" }));
    expect(await screen.findByText("2026091600001")).toBeVisible();
    expect(screen.getByText("00012345678901234567890")).toBeVisible();
  });
  it("defaults to only the latest completed question and limits summary to two selections", async () => {
    const more = {
      ...workbench.investigations[0],
      id: "JOB-4",
      question: "Second latest question",
    };
    mock({
      work: {
        ...workbench,
        investigations: [
          workbench.investigations[0],
          more,
          ...workbench.investigations.slice(1),
        ],
      },
    });
    await page();
    expect(screen.getAllByRole("checkbox", { checked: true })).toHaveLength(1);
    expect(checked("Second latest question")).not.toBeChecked();
    fireEvent.click(checked("Second latest question"));
    expect(checked("Review earlier")).toBeDisabled();
    fireEvent.click(screen.getByRole("radio", { name: /Detailed report/ }));
    expect(checked("Review earlier")).toBeEnabled();
    fireEvent.click(checked("Review earlier"));
    fireEvent.click(checked("Include all raw rows"));
    fireEvent.click(screen.getByRole("radio", { name: /Case summary/ }));
    expect(screen.getAllByRole("checkbox", { checked: true })).toHaveLength(1);
    expect(checked("Review latest")).toBeChecked();
    expect(
      screen.queryByRole("checkbox", { name: /Include all raw rows/ }),
    ).not.toBeInTheDocument();
  });
  it("defaults to latest evidence and completed matching jobs without generating on load", async () => {
    const fetcher = mock();
    await page();
    expect(screen.getByLabelText("Report evidence version")).toHaveValue(
      "EV-2",
    );
    expect(checked("Review latest")).toBeChecked();
    expect(checked("Review earlier")).not.toBeChecked();
    expect(checked("Pending question")).not.toBeChecked();
    expect(checked("Pending question")).toHaveAccessibleName(
      /Saved status only; no completed answer/,
    );
    expect(
      screen.queryByRole("checkbox", { name: /Include all raw rows/ }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("radio", { name: /Case summary/ })).toBeChecked();
    expect(requests(fetcher, "/report-preview")).toHaveLength(0);
    expect(requests(fetcher, "/report.pdf")).toHaveLength(0);
  });
  it("labels older-version answers and keeps their selection alongside the report evidence", async () => {
    const fetcher = mock();
    await page();
    expect(checked("Review earlier")).toHaveAccessibleName(
      /Different version; its own saved source references/,
    );
    fireEvent.click(checked("Review earlier"));
    fireEvent.click(screen.getByRole("button", { name: "Preview report" }));
    await screen.findByRole("heading", { name: "Saved report preview" });
    expect(
      JSON.parse(requests(fetcher, "/report-preview")[0][1].body as string),
    ).toEqual({
      evidenceId: "EV-2",
      investigationIds: ["JOB-2", "JOB-1"],
      includeEvidenceRows: false,
      reportMode: "SUMMARY",
    });
    expect(
      screen.getByText(/Review earlier observations · Evidence version 1/),
    ).toHaveTextContent("different version; its own saved sources");
    expect(screen.getByText("Pending for this report scope")).toBeVisible();
  });
  it("allows explicit inclusion of pending investigation status without implying a completed answer", async () => {
    mock();
    await page();
    fireEvent.click(checked("Pending question"));
    fireEvent.click(screen.getByRole("button", { name: "Preview report" }));
    await screen.findByRole("heading", { name: "Saved report preview" });
    expect(
      screen.getByText(/Pending question · Evidence version 2/),
    ).toHaveTextContent("Running: saved status only; no completed answer");
  });
  it("downloads precisely the saved preview hash with PDF Accept, CSRF, and no new preview", async () => {
    setCsrfToken("csrf-report");
    const fetcher = mock();
    await page();
    const create = vi.fn(() => "blob:test-report"),
      revoke = vi.fn();
    vi.stubGlobal(
      "URL",
      Object.assign(URL, { createObjectURL: create, revokeObjectURL: revoke }),
    );
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    fireEvent.click(screen.getByRole("button", { name: "Preview report" }));
    await screen.findByRole("button", { name: "Download PDF" });
    const generate = requests(fetcher, "/report-preview")[0][1];
    expect((generate.headers as Headers).get("Idempotency-Key")).toBeTruthy();
    expect((generate.headers as Headers).get("X-CSRF-Token")).toBe(
      "csrf-report",
    );
    fireEvent.click(screen.getByRole("button", { name: "Download PDF" }));
    await waitFor(() => expect(create).toHaveBeenCalledOnce());
    const download = requests(fetcher, "/report.pdf")[0][1];
    expect(JSON.parse(download.body as string)).toEqual({
      reportId: "RPT-1",
      reportHash: "f".repeat(64),
    });
    expect((download.headers as Headers).get("Accept")).toBe("application/pdf");
    expect((download.headers as Headers).get("X-CSRF-Token")).toBe(
      "csrf-report",
    );
    expect(download.credentials).toBe("same-origin");
    expect(requests(fetcher, "/report-preview")).toHaveLength(1);
  });
  it("retries an uncertain preview with unchanged scope and idempotency key", async () => {
    let calls = 0;
    const fetcher = mock({
      generate: (scope) =>
        ++calls === 1
          ? Promise.reject(new Error("network"))
          : json(preview(scope)),
    });
    await page();
    fireEvent.click(screen.getByRole("button", { name: "Preview report" }));
    await screen.findByRole("button", { name: "Retry same report preview" });
    expect(screen.getByLabelText("Report evidence version")).toBeDisabled();
    fireEvent.click(
      screen.getByRole("button", { name: "Retry same report preview" }),
    );
    await screen.findByRole("heading", { name: "Saved report preview" });
    const [first, second] = requests(fetcher, "/report-preview").map(
      ([, r]) => r,
    );
    expect(first.body).toBe(second.body);
    expect((first.headers as Headers).get("Idempotency-Key")).toBe(
      (second.headers as Headers).get("Idempotency-Key"),
    );
  });
  it("clears preview when selection changes and excludes jobs/rows from case-only scope", async () => {
    const fetcher = mock();
    await page();
    fireEvent.click(screen.getByRole("radio", { name: /Detailed report/ }));
    fireEvent.click(checked("Include all raw rows"));
    fireEvent.click(screen.getByRole("button", { name: "Preview report" }));
    await screen.findByRole("button", { name: "Download PDF" });
    fireEvent.change(screen.getByLabelText("Report evidence version"), {
      target: { value: "" },
    });
    expect(
      screen.queryByRole("button", { name: "Download PDF" }),
    ).not.toBeInTheDocument();
    expect(checked("Review latest")).toBeDisabled();
    expect(checked("Review latest")).not.toBeChecked();
    expect(checked("Include all raw rows")).toBeDisabled();
    expect(checked("Include all raw rows")).not.toBeChecked();
    fireEvent.click(screen.getByRole("button", { name: "Preview report" }));
    await screen.findByText("Case summary only");
    expect(
      JSON.parse(requests(fetcher, "/report-preview")[1][1].body as string),
    ).toEqual({
      evidenceId: null,
      investigationIds: [],
      includeEvidenceRows: false,
      reportMode: "DETAILED",
    });
  });
  it("exports a summary when there is no saved evidence", async () => {
    const fetcher = mock({
      work: {
        caseId: "PC-1",
        latestEvidenceId: null,
        evidence: [],
        investigations: [],
      },
    });
    await page();
    fireEvent.click(screen.getByRole("button", { name: "Preview report" }));
    await screen.findByText("Case summary only");
    expect(
      JSON.parse(requests(fetcher, "/report-preview")[0][1].body as string)
        .evidenceId,
    ).toBeNull();
  });
  it.each([
    "foreign case",
    "different scope",
    "duplicate jobs",
    "malformed evidence",
    "false pending conclusion",
    "different report mode",
  ])("rejects a %s preview", async (kind) => {
    mock({
      generate: (scope) => {
        const result = preview(scope) as any;
        if (kind === "foreign case") result.case.id = "OTHER";
        if (kind === "different report mode")
          result.scope = { ...scope, reportMode: "DETAILED" };
        if (kind === "different scope")
          result.scope = { ...scope, evidenceId: "EV-1" };
        if (kind === "duplicate jobs")
          result.investigations = [
            ...result.investigations,
            ...result.investigations,
          ];
        if (kind === "malformed evidence")
          result.evidence = { ...result.evidence, version: { bad: 1 } };
        if (kind === "false pending conclusion")
          result.review.conclusion = { conclusion: "Not matched" };
        return json(result);
      },
    });
    await page();
    fireEvent.click(screen.getByRole("button", { name: "Preview report" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "does not match",
    );
    expect(
      screen.queryByRole("button", { name: "Download PDF" }),
    ).not.toBeInTheDocument();
  });
  it("rejects duplicate or foreign workbench options before a report can be created", async () => {
    const fetcher = mock({
      work: {
        ...workbench,
        evidence: [{ ...workbench.evidence[0], caseId: "OTHER" }],
      },
    });
    render(<CaseReport caseId="PC-1" onClose={vi.fn()} />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "could not be read",
    );
    expect(requests(fetcher, "/report-preview")).toHaveLength(0);
  });
  it("does not download HTML masquerading as a successful PDF", async () => {
    mock({
      pdf: () =>
        Promise.resolve(
          new Response("<html>error</html>", {
            headers: { "Content-Type": "text/html" },
          }),
        ),
    });
    await page();
    fireEvent.click(screen.getByRole("button", { name: "Preview report" }));
    await screen.findByRole("button", { name: "Download PDF" });
    fireEvent.click(screen.getByRole("button", { name: "Download PDF" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "unreadable response",
    );
  });
  it("shows JSON PDF errors and propagates expired sessions", async () => {
    const expired = vi.fn();
    window.addEventListener("session-expired", expired);
    try {
      mock({
        pdf: () =>
          json(
            {
              code: "UNAUTHENTICATED",
              message: "Sign in again",
              requestId: "trace-1",
            },
            401,
          ),
      });
      await page();
      fireEvent.click(screen.getByRole("button", { name: "Preview report" }));
      await screen.findByRole("button", { name: "Download PDF" });
      fireEvent.click(screen.getByRole("button", { name: "Download PDF" }));
      expect(await screen.findByRole("alert")).toHaveTextContent(
        "Sign in again",
      );
      expect(expired).toHaveBeenCalledOnce();
    } finally {
      window.removeEventListener("session-expired", expired);
    }
  });
});
