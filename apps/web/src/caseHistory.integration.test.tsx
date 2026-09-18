import { afterEach, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { CaseInvestigation } from "./CaseInvestigation";
import { CaseReport } from "./CaseReport";
import { CaseManagement } from "./CaseManagement";

const caseId = "CASE-PAGED-SYNTHETIC";
const base = `/api/payment-cases/${caseId}`;
const response = (value: unknown) =>
  Promise.resolve(new Response(JSON.stringify(value)));
const evidence = (version: number) => ({
  id: `EV-${version}`,
  caseId,
  version,
  evidenceHash: `hash-${version}`,
  sourceKind: "MANUAL",
  createdAt: "2026-01-02T00:00:00Z",
  createdBy: "source-author",
  warnings: [],
});
const job = (id: string, status = "COMPLETED", version = 2) => ({
  id,
  caseId,
  evidenceId: `EV-${version}`,
  evidenceVersion: version,
  evidenceHash: `hash-${version}`,
  question: `Question ${id}`,
  status,
  createdAt: "2026-01-03T00:00:00Z",
  createdBy: "question-author",
});
const activity = (id: string) => ({
  id,
  action: "CASE_OPENED",
  occurredAt: "2026-01-01T00:00:00Z",
  actor: "source-author",
  detail: `Activity ${id}`,
});
const meta = (total: number, nextCursor: string | null = null) => ({
  total,
  limit: 10,
  nextCursor,
});
const page = <T,>(
  items: T[],
  total = items.length,
  nextCursor: string | null = null,
) => ({ caseId, items, ...meta(total, nextCursor) });
const workbench = () => ({
  caseId,
  latestEvidenceId: "EV-2",
  evidence: [evidence(2)],
  investigations: [],
  audit: [activity("A2")],
  evidencePage: meta(2, "EV-2"),
  investigationPage: meta(0),
  activityPage: meta(2, "A2"),
  activeInvestigations: [],
  activeInvestigationPage: { total: 0, limit: 25, nextCursor: null },
});
const context = (version: number) => ({
  evidenceId: `EV-${version}`,
  evidenceVersion: version,
  evidenceHash: `hash-${version}`,
  guidanceHash: "guidance",
  nonEmptyRows: 1,
  documents: [],
  warnings: [],
  timeline: [],
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

it("pins an older linked evidence version and preserves its question while manually paging and refreshing", async () => {
  const fetcher = vi.fn((url: string) => {
    if (url === `${base}/workbench`) return response(workbench());
    if (url === `${base}/evidence/EV-1/summary`) return response(evidence(1));
    if (url === `${base}/evidence/EV-1/context`) return response(context(1));
    if (url === `${base}/evidence?limit=10&cursor=EV-2`)
      return response(page([evidence(1)], 2));
    if (url === `${base}/activity?limit=10&cursor=A2`)
      return response(page([activity("A1")], 2));
    throw new Error(url);
  });
  vi.stubGlobal("fetch", fetcher);
  render(
    <CaseInvestigation caseId={caseId} canWrite requestedEvidenceId="EV-1" />,
  );
  await waitFor(() =>
    expect(screen.getByLabelText("Investigation evidence version")).toHaveValue(
      "EV-1",
    ),
  );
  await screen.findByText("1 source rows");
  fireEvent.change(screen.getByLabelText("Your case question"), {
    target: { value: "Keep this investigation draft" },
  });
  fireEvent.click(
    screen.getByRole("button", { name: "Load more evidence versions" }),
  );
  await screen.findByText("2 of 2 evidence versions loaded");
  expect(screen.getAllByRole("option", { name: /Version 1/ })).toHaveLength(1);
  fireEvent.click(screen.getByRole("tab", { name: /Audit trail/ }));
  fireEvent.click(
    screen.getByRole("button", { name: "Load more activity records" }),
  );
  await screen.findByText("Activity A1");
  fireEvent.click(screen.getByRole("button", { name: "Refresh workbench" }));
  await waitFor(() =>
    expect(
      fetcher.mock.calls.filter(([url]) => url === `${base}/workbench`),
    ).toHaveLength(2),
  );
  expect(screen.getByLabelText("Investigation evidence version")).toHaveValue(
    "EV-1",
  );
  expect(screen.getByLabelText("Your case question")).toHaveValue(
    "Keep this investigation draft",
  );
});

it("polls an active job outside the first history page", async () => {
  const active = job("OLD-ACTIVE", "RUNNING");
  const fetcher = vi.fn((url: string) => {
    if (url === `${base}/workbench`)
      return response({
        ...workbench(),
        activeInvestigations: [active],
        activeInvestigationPage: { total: 1, limit: 25, nextCursor: null },
      });
    if (url === `${base}/evidence/EV-2/context`) return response(context(2));
    if (url === `${base}/investigations/OLD-ACTIVE`)
      return response({ ...active, documents: [] });
    throw new Error(url);
  });
  vi.stubGlobal("fetch", fetcher);
  render(<CaseInvestigation caseId={caseId} canWrite />);
  await waitFor(() =>
    expect(
      fetcher.mock.calls.some(([url]) => url.endsWith("/OLD-ACTIVE")),
    ).toBe(true),
  );
  expect(
    screen.getByRole("button", { name: "Investigation in progress…" }),
  ).toBeDisabled();
});

it("reports select completed questions from the chosen evidence, including jobs outside the general first page", async () => {
  const old = job("OLDER-COMPLETED"),
    older = job("OLDEST-COMPLETED");
  const fetcher = vi.fn((url: string) => {
    if (url === `${base}/workbench`)
      return response({
        ...workbench(),
        evidencePage: meta(1),
        investigationPage: meta(1),
        investigations: [job("RECENT-FAILED", "FAILED")],
      });
    if (url.includes("/reports?"))
      return response({
        schemaVersion: "payment-case-report-history-v1",
        caseId,
        items: [],
        nextCursor: null,
      });
    if (url === `${base}/investigations?status=COMPLETED&evidenceId=EV-2`)
      return response(page([old], 2, old.id));
    if (
      url ===
      `${base}/investigations?status=COMPLETED&evidenceId=EV-2&limit=10&cursor=OLDER-COMPLETED`
    )
      return response(page([older], 2));
    throw new Error(url);
  });
  vi.stubGlobal("fetch", fetcher);
  render(<CaseReport caseId={caseId} onClose={vi.fn()} />);
  await waitFor(() =>
    expect(
      screen.getByRole("checkbox", { name: /Question OLDER-COMPLETED/ }),
    ).toBeChecked(),
  );
  expect(
    screen.queryByRole("checkbox", { name: /RECENT-FAILED/ }),
  ).not.toBeInTheDocument();
  fireEvent.click(
    screen.getByRole("button", { name: "Load more report questions" }),
  );
  await screen.findByRole("checkbox", { name: /Question OLDEST-COMPLETED/ });
  expect(
    screen.getByRole("checkbox", { name: /Question OLDER-COMPLETED/ }),
  ).toBeChecked();
  expect(
    screen.getByRole("checkbox", { name: /Question OLDEST-COMPLETED/ }),
  ).not.toBeChecked();
  expect(
    fetcher.mock.calls.some(([url]) => url.includes("/report-preview")),
  ).toBe(false);
});

const reviewer = {
  id: "reviewer",
  name: "Reviewer",
  role: "REVIEWER",
  tenantId: "northstar",
};
const management = () => ({
  caseId,
  version: 1,
  owner: null,
  priority: "MEDIUM",
  assignees: [],
  notes: [],
  evidenceRequests: [],
  reviewerConclusions: [],
  audit: [],
});

it("reviewer question paging preserves the written conclusion and selected question", async () => {
  const first = job("REVIEW-1"),
    next = job("REVIEW-0");
  const fetcher = vi.fn((url: string) => {
    if (url === `${base}/management`) return response(management());
    if (url === `${base}/workbench`)
      return response({
        ...workbench(),
        investigationPage: meta(1),
        investigations: [job("OTHER-VERSION", "COMPLETED", 1)],
      });
    if (url === `${base}/investigations?status=COMPLETED&evidenceId=EV-2`)
      return response(page([first], 2, first.id));
    if (
      url ===
      `${base}/investigations?status=COMPLETED&evidenceId=EV-2&limit=10&cursor=REVIEW-1`
    )
      return response(page([next], 2));
    throw new Error(url);
  });
  vi.stubGlobal("fetch", fetcher);
  render(
    <CaseManagement caseId={caseId} createdBy="case-author" user={reviewer} />,
  );
  await screen.findByText("Unassigned", { selector: "dd" });
  fireEvent.click(screen.getByRole("tab", { name: "Reviewer conclusion" }));
  fireEvent.click(
    await screen.findByRole("checkbox", { name: /Question REVIEW-1/ }),
  );
  fireEvent.change(
    screen.getByLabelText("Reviewer conclusion", { selector: "textarea" }),
    { target: { value: "Keep this reviewer draft." } },
  );
  fireEvent.click(
    screen.getByRole("button", { name: "Load more review questions" }),
  );
  await screen.findByRole("checkbox", { name: /Question REVIEW-0/ });
  expect(
    screen.getByRole("checkbox", { name: /Question REVIEW-1/ }),
  ).toBeChecked();
  expect(
    screen.getByLabelText("Reviewer conclusion", { selector: "textarea" }),
  ).toHaveValue("Keep this reviewer draft.");
});

it("loads the exact older investigation authors before allowing a reviewed resolution", async () => {
  const prior = job("PRIOR-QUESTION");
  let resolve!: (value: Response) => void;
  const pending = new Promise<Response>((done) => {
    resolve = done;
  });
  const fetcher = vi.fn((url: string) => {
    if (url === `${base}/management`)
      return response({
        ...management(),
        status: "AWAITING_REVIEW",
        allowedTransitions: ["RESOLVED"],
        reviewerConclusions: [
          {
            id: "CONCLUSION",
            status: "RECORDED",
            conclusion: "Reviewed sources",
            evidenceId: "EV-2",
            evidenceHash: "hash-2",
            evidenceVersion: 2,
            investigationIds: [prior.id],
            createdAt: "2026-01-03T00:00:00Z",
            createdBy: "independent-reviewer",
            createdByName: "Independent reviewer",
          },
        ],
      });
    if (url === `${base}/workbench`) return response(workbench());
    if (url === `${base}/investigations/${prior.id}/summary`) return pending;
    throw new Error(url);
  });
  vi.stubGlobal("fetch", fetcher);
  render(
    <CaseManagement caseId={caseId} createdBy="case-author" user={reviewer} />,
  );
  await screen.findByText("Unassigned", { selector: "dd" });
  fireEvent.change(screen.getByLabelText("Next investigation status"), {
    target: { value: "RESOLVED" },
  });
  fireEvent.change(
    screen.getByLabelText("Reviewer conclusion for latest evidence"),
    { target: { value: "CONCLUSION" } },
  );
  fireEvent.change(screen.getByLabelText("Reason for status change"), {
    target: { value: "Complete review" },
  });
  expect(
    screen.getByRole("button", { name: "Save investigation status" }),
  ).toBeDisabled();
  resolve(new Response(JSON.stringify(prior)));
  await waitFor(() =>
    expect(
      screen.getByRole("button", { name: "Save investigation status" }),
    ).not.toBeDisabled(),
  );
});
