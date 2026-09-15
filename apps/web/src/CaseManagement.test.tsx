import { afterEach, describe, expect, it, vi } from "vitest";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { CaseManagement, type Management } from "./CaseManagement";
import type { Lifecycle } from "./CaseLifecycle";
import { setCsrfToken } from "./api";
const user = {
  id: "reviewer",
  name: "Reviewer",
  role: "REVIEWER",
  tenantId: "northstar",
};
const initial = (): Management => ({
  caseId: "PC-1",
  version: 0,
  owner: null,
  priority: "MEDIUM",
  assignees: [
    { id: "analyst", name: "Analyst", role: "ANALYST" },
    { id: "reviewer", name: "Reviewer", role: "REVIEWER" },
  ],
  notes: [],
  evidenceRequests: [],
  reviewerConclusions: [],
  audit: [],
});
const workbench = {
  caseId: "PC-1",
  latestEvidenceId: "EV-1",
  evidence: [
    { id: "EV-1", caseId: "PC-1", version: 1, evidenceHash: "hash-1" },
  ],
  investigations: [
    {
      id: "JOB-1",
      caseId: "PC-1",
      evidenceId: "EV-1",
      evidenceHash: "hash-1",
      evidenceVersion: 1,
      question: "Check the recorded evidence",
      status: "COMPLETED",
      createdBy: "analyst",
    },
  ],
};
const lifecycle = (): Lifecycle => ({
  caseId: "PC-1",
  caseNumber: "2026091600001",
  state: "ACTIVE",
  version: 0,
  canArchive: true,
  canRestore: false,
  canDelete: false,
  activeInvestigationCount: 0,
  audit: [],
});
const reply = (body: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(body), { status }));
function mock(
  options: {
    post?: (url: string, init: RequestInit) => Promise<Response>;
    data?: () => Management;
    work?: unknown;
    lifecycle?: () => Lifecycle;
  } = {},
) {
  const fetcher = vi.fn((url: string, init: RequestInit) => {
    if (init.method === "POST")
      return options.post?.(url, init) ?? reply({ ...initial(), version: 1 });
    if (url.endsWith("/workbench")) return reply(options.work ?? workbench);
    if (url.endsWith("/management"))
      return reply(options.data?.() ?? initial());
    if (url.endsWith("/lifecycle"))
      return reply(options.lifecycle?.() ?? lifecycle());
    throw new Error(url);
  });
  vi.stubGlobal("fetch", fetcher);
  return fetcher;
}
async function page(actor = user, createdBy = "analyst", changed = vi.fn()) {
  render(
    <CaseManagement
      caseId="PC-1"
      createdBy={createdBy}
      user={actor}
      onChanged={changed}
    />,
  );
  await screen.findByText("Unassigned", { selector: "dd" });
  await waitFor(() =>
    expect(
      screen.queryByText("Loading case management…"),
    ).not.toBeInTheDocument(),
  );
  return changed;
}
function input(label: string, value: string) {
  fireEvent.change(
    screen.getByLabelText(label, { selector: "input,select,textarea" }),
    { target: { value } },
  );
}
function tab(name: string) {
  fireEvent.click(screen.getByRole("tab", { name }));
}
const posts = (fetcher: ReturnType<typeof mock>) =>
  fetcher.mock.calls.filter(([, init]) => init.method === "POST");
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  setCsrfToken(null);
});
describe("payment case management", () => {
  it("preserves the lifecycle draft and refreshes its state when reopening the tab", async () => {
    let current = lifecycle();
    const fetcher = mock({ lifecycle: () => current });
    await page();
    expect(fetcher.mock.calls.some(([url]) => url.endsWith("/lifecycle"))).toBe(
      false,
    );
    tab("Case lifecycle");
    await screen.findByLabelText("Reason for archiving");
    input("Reason for archiving", "Keep this archive draft");
    tab("Notes");
    current = {
      ...lifecycle(),
      version: 1,
      activeInvestigationCount: 1,
      canArchive: false,
    };
    tab("Case lifecycle");
    await screen.findByText(/Wait for them to finish/);
    expect(screen.getByLabelText("Reason for archiving")).toHaveValue(
      "Keep this archive draft",
    );
    expect(screen.getByRole("button", { name: "Archive case" })).toBeDisabled();
    expect(posts(fetcher)).toHaveLength(0);
  });
  it("keeps in-flight lifecycle requests and uncertain retries intact across tab switches", async () => {
    let failArchive!: (error: Error) => void;
    const pendingArchive = new Promise<Response>((_, reject) => {
      failArchive = reject;
    });
    let attempts = 0;
    const fetcher = mock({
      post: () =>
        ++attempts === 1
          ? pendingArchive
          : reply({
              ...lifecycle(),
              state: "ARCHIVED",
              version: 1,
              canArchive: false,
              canRestore: true,
            }),
    });
    await page();
    tab("Case lifecycle");
    await screen.findByLabelText("Reason for archiving");
    input("Reason for archiving", "Preserve the lifecycle request");
    fireEvent.click(screen.getByRole("button", { name: "Archive case" }));
    const submitted = posts(fetcher)[0][1];
    tab("Notes");
    expect(submitted.signal?.aborted).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Refresh management" }));
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Refresh management" }),
      ).toBeEnabled(),
    );
    expect(submitted.signal?.aborted).toBe(false);
    tab("Case lifecycle");
    expect(screen.getByRole("button", { name: "Saving…" })).toBeDisabled();
    tab("Notes");
    await act(async () => {
      failArchive(new Error("Connection interrupted"));
    });
    tab("Case lifecycle");
    const retry = await screen.findByRole("button", {
      name: "Retry pending action",
    });
    expect(screen.getByLabelText("Reason for archiving")).toHaveValue(
      "Preserve the lifecycle request",
    );
    expect(
      fetcher.mock.calls.filter(([url]) => url.endsWith("/lifecycle")),
    ).toHaveLength(1);
    fireEvent.click(retry);
    await screen.findByText(
      /archived. Its evidence and answers remain available/,
    );
    const commands = posts(fetcher);
    expect(commands).toHaveLength(2);
    expect(commands[1][1].body).toBe(submitted.body);
    expect((commands[1][1].headers as Headers).get("Idempotency-Key")).toBe(
      (submitted.headers as Headers).get("Idempotency-Key"),
    );
  });
  it("preserves archived management records while hiding writer and reviewer forms", async () => {
    const fetcher = mock();
    render(
      <CaseManagement
        caseId="PC-1"
        createdBy="analyst"
        user={user}
        lifecycleState="ARCHIVED"
      />,
    );
    await screen.findByText("Unassigned", { selector: "dd" });
    for (const name of [
      "Overview",
      "Notes",
      "Evidence requests",
      "Reviewer conclusion",
    ]) {
      tab(name);
      expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    }
    expect(screen.getByText(/Restore it from Case lifecycle/)).toBeVisible();
    expect(posts(fetcher)).toHaveLength(0);
    expect(screen.getByRole("tab", { name: "Case lifecycle" })).toBeVisible();
  });
  it("reads viewer sections without exposing writer or reviewer actions", async () => {
    const fetcher = mock();
    await page({ ...user, role: "VIEWER" });
    for (const name of [
      "Overview",
      "Notes",
      "Evidence requests",
      "Reviewer conclusion",
      "Activity",
    ]) {
      tab(name);
      expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    }
    expect(posts(fetcher)).toHaveLength(0);
    expect(
      screen.getByText(/Your role can read case management/),
    ).toBeVisible();
  });
  it("saves authorized owner and priority with version, CSRF and a stable command key", async () => {
    setCsrfToken("csrf-fixture");
    const fetcher = mock({
      post: () =>
        reply({
          ...initial(),
          version: 1,
          owner: { id: "analyst", name: "Analyst" },
          priority: "HIGH",
        }),
    });
    const changed = await page();
    input("Case owner", "analyst");
    input("Case priority", "HIGH");
    input(
      "Reason for assignment or priority change",
      "Customer follow-up needed",
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Save owner and priority" }),
    );
    await screen.findByText("Saved to this case.");
    expect(changed).toHaveBeenCalledOnce();
    const [, request] = posts(fetcher)[0];
    expect(JSON.parse(request.body as string)).toEqual({
      expectedVersion: 0,
      ownerId: "analyst",
      priority: "HIGH",
      reason: "Customer follow-up needed",
    });
    expect((request.headers as Headers).get("X-CSRF-Token")).toBe(
      "csrf-fixture",
    );
    expect((request.headers as Headers).get("Idempotency-Key")).toBeTruthy();
    expect(
      screen.getByLabelText("Reason for assignment or priority change"),
    ).toHaveValue("");
  });
  it("preserves the note and uses the exact same idempotency body/key after an uncertain save", async () => {
    let attempts = 0;
    const fetcher = mock({
      post: () =>
        ++attempts === 1
          ? Promise.reject(new Error("disconnect"))
          : reply({ ...initial(), version: 1 }),
    });
    await page();
    tab("Notes");
    input("New case note", "Retain this exact note");
    fireEvent.click(screen.getByRole("button", { name: "Add note" }));
    await screen.findByRole("button", { name: "Retry pending save" });
    expect(screen.getByLabelText("New case note")).toHaveValue(
      "Retain this exact note",
    );
    expect(screen.getByLabelText("New case note")).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Retry pending save" }));
    await screen.findByText("Saved to this case.");
    const calls = posts(fetcher);
    expect(calls[0][1].body).toBe(calls[1][1].body);
    expect((calls[0][1].headers as Headers).get("Idempotency-Key")).toBe(
      (calls[1][1].headers as Headers).get("Idempotency-Key"),
    );
  });
  it("keeps a conflicting draft, requires refresh, then saves against the refreshed version", async () => {
    let version = 0,
      attempts = 0;
    const fetcher = mock({
      data: () => ({ ...initial(), version }),
      post: () =>
        ++attempts === 1
          ? reply(
              {
                code: "CASE_MANAGEMENT_VERSION_CONFLICT",
                message: "Saved case changed",
              },
              409,
            )
          : reply({ ...initial(), version: 2 }),
    });
    await page();
    input("Case priority", "CRITICAL");
    input("Reason for assignment or priority change", "Keep my draft");
    fireEvent.click(
      screen.getByRole("button", { name: "Save owner and priority" }),
    );
    await screen.findByText(/The saved case changed/);
    expect(
      screen.getByRole("button", { name: "Save owner and priority" }),
    ).toBeDisabled();
    version = 1;
    fireEvent.click(screen.getByRole("button", { name: "Refresh management" }));
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Save owner and priority" }),
      ).toBeEnabled(),
    );
    expect(screen.getByLabelText("Case priority")).toHaveValue("CRITICAL");
    expect(
      screen.getByLabelText("Reason for assignment or priority change"),
    ).toHaveValue("Keep my draft");
    fireEvent.click(
      screen.getByRole("button", { name: "Save owner and priority" }),
    );
    await screen.findByText("Saved to this case.");
    expect(
      JSON.parse(posts(fetcher)[1][1].body as string).expectedVersion,
    ).toBe(1);
  });
  it("requires a saved evidence version when fulfilling a request", async () => {
    const request = {
      id: "REQ-1",
      title: "Posted entries",
      detail: "Obtain posted ledger evidence",
      dueDate: null,
      status: "OPEN",
      createdAt: "2026-09-14",
      createdBy: "analyst",
      createdByName: "Analyst",
      updatedAt: "2026-09-14",
      updatedBy: "analyst",
      updatedByName: "Analyst",
      evidenceId: null,
      evidenceVersion: null,
      evidenceHash: null,
      updates: [],
    };
    const fetcher = mock({
      data: () => ({ ...initial(), evidenceRequests: [request] }),
    });
    await page();
    tab("Evidence requests");
    fireEvent.click(
      screen.getByRole("button", { name: "Update request: Posted entries" }),
    );
    input("Request update note", "Attached the supplied records");
    expect(
      screen.getByRole("button", { name: "Save request update" }),
    ).toBeDisabled();
    input("Evidence that fulfils this request", "EV-1");
    fireEvent.click(
      screen.getByRole("button", { name: "Save request update" }),
    );
    await screen.findByText("Saved to this case.");
    expect(JSON.parse(posts(fetcher)[0][1].body as string)).toEqual({
      expectedVersion: 0,
      status: "FULFILLED",
      note: "Attached the supplied records",
      evidenceId: "EV-1",
    });
  });
  it("binds independent reviewer conclusions to exact evidence and selected completed investigations", async () => {
    const fetcher = mock();
    await page();
    tab("Reviewer conclusion");
    fireEvent.click(screen.getByRole("checkbox"));
    input(
      "Reviewer conclusion",
      "The supplied records do not establish the final outcome.",
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Record reviewer conclusion" }),
    );
    await screen.findByText("Saved to this case.");
    expect(JSON.parse(posts(fetcher)[0][1].body as string)).toEqual({
      expectedVersion: 0,
      evidenceId: "EV-1",
      evidenceHash: "hash-1",
      investigationIds: ["JOB-1"],
      conclusion: "The supplied records do not establish the final outcome.",
    });
  });
  it("prevents a reviewer from reviewing a case or investigation they created", async () => {
    const fetcher = mock({
      work: {
        ...workbench,
        investigations: [
          { ...workbench.investigations[0], createdBy: "reviewer" },
        ],
      },
    });
    await page(user, "reviewer");
    tab("Reviewer conclusion");
    expect(screen.getByRole("checkbox")).toBeDisabled();
    input("Reviewer conclusion", "Independent review required");
    expect(
      screen.getByRole("button", { name: "Record reviewer conclusion" }),
    ).toBeDisabled();
    expect(posts(fetcher)).toHaveLength(0);
  });
  it("renders note text as text", async () => {
    mock({
      data: () => ({
        ...initial(),
        notes: [
          {
            id: "N1",
            text: '<img src=x onerror="alert(1)">',
            createdAt: "2026-09-14",
            createdBy: "analyst",
            createdByName: "Analyst",
          },
        ],
      }),
    });
    await page();
    tab("Notes");
    expect(screen.getByText('<img src=x onerror="alert(1)">')).toBeVisible();
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
  });
  it("rejects a management response for another case", async () => {
    mock({ data: () => ({ ...initial(), caseId: "OTHER-CASE" }) });
    render(<CaseManagement caseId="PC-1" createdBy="analyst" user={user} />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "belongs to another case",
    );
    expect(screen.queryByText("Unassigned")).not.toBeInTheDocument();
  });
});
