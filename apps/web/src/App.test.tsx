import { afterEach, describe, expect, it, vi } from "vitest";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App, { InvestigationPanel, Login, ReviewDecision } from "./App";
import { setCsrfToken } from "./api";
import type { CaseDetail, Investigation, User } from "./types";
const reviewer: User = {
  id: "reviewer",
  name: "Demo Reviewer",
  role: "REVIEWER",
  tenantId: "northstar",
};
const investigation: Investigation = {
  id: "INV-1",
  caseId: "CASE-1",
  createdAt: "2026-09-11T09:00:00Z",
  createdBy: "analyst",
  mode: "replay",
  status: "AWAITING_REVIEW",
  outcome: "TIMEOUT_AFTER_SUCCESS",
  summary: "Provider success is supported.",
  confidence: "HIGH",
  findings: [],
  missingEvidence: [],
  citations: [],
  toolCalls: [],
  proposal: {
    action: "RESOLVE_CASE",
    reason: "Payment success is independently evidenced.",
  },
  metrics: {
    durationMs: 20,
    retrievalMs: 1,
    toolCount: 4,
    modelCalls: 0,
    inputTokens: 0,
    outputTokens: 0,
    retrievalMode: "lexical",
    model: "deterministic-replay",
  },
  warnings: [],
};
const item = { id: "CASE-1", version: 4, status: "AWAITING_REVIEW" };
afterEach(() => {
  vi.unstubAllGlobals();
  setCsrfToken(null);
  window.history.replaceState({}, "", "/");
});
describe("review authorization and decision semantics", () => {
  it.each(["ANALYST", "VIEWER"])(
    "does not expose decision controls to %s",
    (role) => {
      render(
        <ReviewDecision
          user={{ ...reviewer, role }}
          item={item}
          investigation={investigation}
          isLatest
          onDecision={vi.fn()}
        />,
      );
      expect(
        screen.queryByRole("button", { name: "Approve proposal" }),
      ).not.toBeInTheDocument();
      expect(
        screen.getByText("A reviewer must approve or reject this proposal."),
      ).toBeVisible();
    },
  );
  it("prevents a reviewer deciding their own investigation", () => {
    render(
      <ReviewDecision
        user={reviewer}
        item={item}
        investigation={{ ...investigation, createdBy: "reviewer" }}
        isLatest
        onDecision={vi.fn()}
      />,
    );
    expect(
      screen.getByText(/You cannot review your own investigation/),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "Approve proposal" }),
    ).not.toBeInTheDocument();
  });
  it("requires a note and submits the version with a stable retry key after an uncertain failure", async () => {
    const user = userEvent.setup();
    const onDecision = vi.fn();
    const fetcher = vi
      .fn()
      .mockRejectedValueOnce(new TypeError("Network dropped"))
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ id: "DEC-1", caseStatus: "RESOLVED", version: 5 }),
          { status: 200 },
        ),
      );
    vi.stubGlobal("fetch", fetcher);
    setCsrfToken("csrf-1");
    render(
      <ReviewDecision
        user={reviewer}
        item={item}
        investigation={investigation}
        isLatest
        onDecision={onDecision}
      />,
    );
    const approve = screen.getByRole("button", { name: "Approve proposal" });
    expect(approve).toBeDisabled();
    await user.type(
      screen.getByLabelText(/Review note/),
      "Checked the provider reference and ledger evidence.",
    );
    await user.click(approve);
    await screen.findByRole("alert");
    expect(onDecision).not.toHaveBeenCalled();
    await user.click(approve);
    await waitFor(() => expect(onDecision).toHaveBeenCalledOnce());
    expect(fetcher.mock.calls[0][1].headers.get("Idempotency-Key")).toBe(
      fetcher.mock.calls[1][1].headers.get("Idempotency-Key"),
    );
    expect(JSON.parse(fetcher.mock.calls[1][1].body)).toMatchObject({
      expectedVersion: 4,
      investigationId: "INV-1",
      decision: "APPROVE",
    });
    expect(screen.getByText("Decision recorded: Approve")).toBeVisible();
  });
  it("surfaces a stale-version conflict and does not mark a decision successful", async () => {
    const user = userEvent.setup();
    const onDecision = vi.fn();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: "STALE_VERSION",
            message: "Case changed. Refresh before reviewing.",
            requestId: "REQ-4",
          }),
          { status: 409 },
        ),
      ),
    );
    render(
      <ReviewDecision
        user={reviewer}
        item={item}
        investigation={investigation}
        isLatest
        onDecision={onDecision}
      />,
    );
    await user.type(screen.getByLabelText(/Review note/), "Reviewed evidence.");
    await user.click(screen.getByRole("button", { name: "Approve proposal" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Case changed. Refresh before reviewing.",
    );
    expect(onDecision).not.toHaveBeenCalled();
    expect(screen.queryByText(/Decision recorded/)).not.toBeInTheDocument();
  });
});
describe("demo login", () => {
  it("shows an authentication failure without opening the workspace", async () => {
    const user = userEvent.setup();
    const onLogin = vi.fn();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: "INVALID_CREDENTIALS",
            message: "Invalid username or password.",
          }),
          { status: 401 },
        ),
      ),
    );
    render(<Login onLogin={onLogin} />);
    await user.click(screen.getByRole("button", { name: /Open workspace/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Invalid username or password.",
    );
    expect(onLogin).not.toHaveBeenCalled();
  });
});

describe("system health", () => {
  it("distinguishes an unavailable runbook count from a confirmed zero and historical responses", async () => {
    const user = userEvent.setup();
    let knowledgeAvailable: boolean | undefined = false;
    let runbookCount = 0;
    const reply = (value: unknown) =>
      Promise.resolve(new Response(JSON.stringify(value), { status: 200 }));
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string) => {
        if (url === "/api/auth/me")
          return reply({ user: reviewer, csrfToken: "test-csrf" });
        if (url === "/api/health")
          return reply({
            status: "UP",
            service: "test-api",
            mode: "synthetic",
          });
        if (url === "/api/system")
          return reply({
            datasetVersion: "test-dataset",
            caseCount: 48,
            runbookCount,
            ...(knowledgeAvailable === undefined ? {} : { knowledgeAvailable }),
            workerStatus: "UP",
            supportedModes: ["replay", "ollama"],
            limitations: [
              "Replay is deterministic graph execution without LLM inference.",
            ],
          });
        return Promise.reject(new Error(`Unexpected request: ${url}`));
      }),
    );
    vi.spyOn(window, "scrollTo").mockImplementation(() => undefined);
    window.history.replaceState({}, "", "/system");
    render(<App />);
    await screen.findByRole("heading", { name: "System & health" });
    const runbooks = within(screen.getByText("Runbooks").parentElement!);
    expect(await runbooks.findByText("Unavailable")).toBeVisible();
    expect(runbooks.queryByText("0")).not.toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Payment case investigation" }),
    ).toBeVisible();
    expect(screen.getByText("Local model · Ollama")).toBeVisible();
    expect(
      screen.queryByRole("heading", { name: "Investigation modes" }),
    ).not.toBeInTheDocument();
    expect(screen.getByText("SERVICE STATUS")).toBeVisible();
    const demoCases = within(screen.getByText("Demo cases").parentElement!);
    expect(demoCases.getByText("48")).toBeVisible();
    expect(
      screen.getByText(/These counts describe retained test fixtures/),
    ).toBeVisible();
    const legacy = screen.getByText("Legacy test details").closest("details")!;
    expect(legacy).not.toHaveAttribute("open");
    expect(
      within(legacy).getByText(/Replay is deterministic/),
    ).not.toBeVisible();
    expect(
      screen.getByText(
        /Service status does not confirm that a model is loaded/,
      ),
    ).toBeVisible();
    await user.click(screen.getByText("Legacy test details"));
    expect(
      within(legacy).getByText(/Legacy service modes: replay, ollama/),
    ).toBeVisible();
    expect(within(legacy).getByText(/Replay is deterministic/)).toBeVisible();

    knowledgeAvailable = true;
    await user.click(screen.getByRole("button", { name: "Check health" }));
    expect(await runbooks.findByText("0")).toBeVisible();
    expect(runbooks.queryByText("Unavailable")).not.toBeInTheDocument();

    knowledgeAvailable = undefined;
    runbookCount = 15;
    await user.click(screen.getByRole("button", { name: "Check health" }));
    expect(await runbooks.findByText("15")).toBeVisible();
    expect(runbooks.queryByText("Unavailable")).not.toBeInTheDocument();
  });
});

describe("investigation accessibility and truthful pending state", () => {
  const detail: CaseDetail = {
    ...item,
    tenantId: "northstar",
    paymentId: "PAY-1",
    title: "Request timed out",
    description: "Synthetic timeout case.",
    priority: "HIGH",
    amountMinor: 10000,
    currency: "INR",
    rail: "SIMULATED_TRANSFER",
    merchant: "Demo Merchant",
    createdAt: "2026-09-11T09:00:00Z",
    updatedAt: "2026-09-11T09:00:00Z",
    tags: [],
    events: [],
    ledgerEntries: [],
    webhooks: [],
    provider: {},
    policyDate: "2026-09-11",
  };
  const cited: Investigation = {
    ...investigation,
    findings: [
      {
        id: "F-1",
        text: "The recorded payment succeeded.",
        evidenceIds: ["EVT-1"],
        citationIds: ["RB-TIMEOUT:v1"],
      },
    ],
    citations: [
      {
        id: "RB-TIMEOUT:v1",
        documentId: "RB-TIMEOUT",
        version: 1,
        title: "Transport timeout guidance",
        excerpt: "A transport timeout does not establish a payment failure.",
        source: "Original simulated operating policy",
      },
    ],
  };
  function renderSaved(result: Investigation) {
    return render(
      <InvestigationPanel
        item={detail}
        user={reviewer}
        investigation={result}
        isLatest
        onCompleted={vi.fn()}
        onDecision={vi.fn()}
        onEvidence={vi.fn()}
        loading={false}
        historyError={null}
      />,
    );
  }
  it("distinguishes model-selected facts from service wording and preserves recorded usage", async () => {
    const user = userEvent.setup();
    renderSaved({
      ...cited,
      mode: "ollama",
      metrics: {
        ...cited.metrics,
        model: "local-test-model",
        modelCalls: 2,
        inputTokens: 123,
        outputTokens: 8,
        synthesisScope: "fact-selection",
        findingSource: "service-rendered-facts",
        assessmentSource: "deterministic-evidence-rules",
        factCatalogVersion: 1,
        factCatalogHash: "a".repeat(64),
        selectedFactIds: ["FACT-1"],
      },
    });
    expect(screen.getAllByText("AI-selected evidence").length).toBeGreaterThan(
      0,
    );
    expect(
      screen.getByText(
        "The model selected facts. Their wording and evidence links are supplied by the service.",
      ),
    ).toBeVisible();
    expect(
      screen.getByText(
        "Summary and proposed action use validated evidence rules.",
      ),
    ).toBeVisible();
    expect(screen.getByText(cited.summary)).toBeVisible();
    expect(screen.getByText(cited.findings[0].text)).toBeVisible();
    expect(screen.getByRole("button", { name: "EVT-1" })).toBeVisible();
    expect(screen.getByRole("button", { name: "RB-TIMEOUT:v1" })).toBeVisible();
    expect(
      screen.queryByText("AI evidence explanation"),
    ).not.toBeInTheDocument();
    expect(screen.queryByText(/Decision recorded/)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Tool trace" }));
    expect(
      within(screen.getByText("Model calls").parentElement!).getByText("2"),
    ).toBeVisible();
    expect(screen.getByText("123 / 8")).toBeVisible();
  });
  it("does not infer wording or assessment authorship from fact selection alone", () => {
    renderSaved({
      ...cited,
      mode: "ollama",
      metrics: {
        ...cited.metrics,
        modelCalls: 2,
        synthesisScope: "fact-selection",
      },
    });
    expect(screen.getAllByText("AI-selected evidence").length).toBeGreaterThan(
      0,
    );
    expect(screen.getByText(cited.findings[0].text)).toBeVisible();
    expect(
      screen.queryByText(
        "The model selected facts. Their wording and evidence links are supplied by the service.",
      ),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText(
        "Summary and proposed action use validated evidence rules.",
      ),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("AI evidence explanation"),
    ).not.toBeInTheDocument();
  });
  it("distinguishes a model finding from rule-derived assessment and preserves recorded usage", async () => {
    const user = userEvent.setup();
    renderSaved({
      ...cited,
      mode: "ollama",
      metrics: {
        ...cited.metrics,
        model: "local-test-model",
        modelCalls: 2,
        inputTokens: 111,
        outputTokens: 17,
        synthesisScope: "finding-only",
        assessmentSource: "deterministic-evidence-rules",
      },
    });
    expect(
      screen.getAllByText("AI evidence explanation").length,
    ).toBeGreaterThan(0);
    expect(
      screen.getByText(
        "Summary and proposed action use validated evidence rules.",
      ),
    ).toBeVisible();
    expect(screen.getByText(cited.summary)).toBeVisible();
    expect(screen.getByText(cited.findings[0].text)).toBeVisible();
    expect(screen.getByRole("button", { name: "RB-TIMEOUT:v1" })).toBeVisible();
    expect(screen.queryByText(/Decision recorded/)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Tool trace" }));
    expect(
      within(screen.getByText("Model calls").parentElement!).getByText("2"),
    ).toBeVisible();
    expect(screen.getByText("111 / 17")).toBeVisible();
  });
  it("labels planner-only Ollama results without inventing a model explanation", async () => {
    const user = userEvent.setup();
    renderSaved({
      ...cited,
      mode: "ollama",
      outcome: "INSUFFICIENT_EVIDENCE",
      confidence: "INSUFFICIENT",
      summary: "Provider payout evidence is unavailable.",
      findings: [],
      missingEvidence: ["Obtain the provider payout record."],
      proposal: {
        action: "REQUEST_EVIDENCE",
        reason: "Payout evidence is missing.",
      },
      metrics: {
        ...cited.metrics,
        model: "local-test-model",
        modelCalls: 1,
        inputTokens: 57,
        outputTokens: 9,
        synthesisScope: "skipped-insufficient-evidence",
        assessmentSource: "deterministic-evidence-rules",
      },
    });
    expect(screen.getAllByText("Ollama tool planning").length).toBeGreaterThan(
      0,
    );
    expect(
      screen.getByText(
        "No model explanation was generated because the evidence was insufficient.",
      ),
    ).toBeVisible();
    expect(
      screen.getByText("Obtain the provider payout record."),
    ).toBeVisible();
    expect(
      screen.queryByText("AI evidence explanation"),
    ).not.toBeInTheDocument();
    expect(screen.queryByText(cited.findings[0].text)).not.toBeInTheDocument();
    expect(screen.queryByText(/Decision recorded/)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Tool trace" }));
    expect(
      within(screen.getByText("Model calls").parentElement!).getByText("1"),
    ).toBeVisible();
    expect(screen.getByText("57 / 9")).toBeVisible();
  });
  it.each([undefined, "future-synthesis-scope"])(
    "preserves historical Ollama provenance for unrecognized scope %s",
    (synthesisScope) => {
      renderSaved({
        ...cited,
        mode: "ollama",
        metrics: { ...cited.metrics, modelCalls: 2, synthesisScope },
      });
      expect(screen.getAllByText("Ollama inference").length).toBeGreaterThan(0);
      expect(screen.getByText(cited.summary)).toBeVisible();
      expect(screen.getByText(cited.findings[0].text)).toBeVisible();
      expect(
        screen.queryByText(
          "Summary and proposed action use validated evidence rules.",
        ),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByText("AI evidence explanation"),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByText("Ollama tool planning"),
      ).not.toBeInTheDocument();
    },
  );
  it("keeps new replay results explicit about zero model inference", async () => {
    const user = userEvent.setup();
    renderSaved({
      ...cited,
      metrics: {
        ...cited.metrics,
        synthesisScope: "deterministic-replay",
        assessmentSource: "deterministic-evidence-rules",
      },
    });
    expect(
      screen.getAllByText("Replay · no language model").length,
    ).toBeGreaterThan(0);
    expect(
      screen.queryByText("AI evidence explanation"),
    ).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Tool trace" }));
    expect(
      within(screen.getByText("Model calls").parentElement!).getByText("0"),
    ).toBeVisible();
  });
  it("does not infer assessment authorship from synthesis scope alone", () => {
    renderSaved({
      ...cited,
      mode: "ollama",
      metrics: {
        ...cited.metrics,
        modelCalls: 2,
        synthesisScope: "finding-only",
      },
    });
    expect(
      screen.getAllByText("AI evidence explanation").length,
    ).toBeGreaterThan(0);
    expect(
      screen.queryByText(
        "Summary and proposed action use validated evidence rules.",
      ),
    ).not.toBeInTheDocument();
  });
  it("focuses the citation dialog, traps tab, closes on Escape, and restores source-button focus", async () => {
    const user = userEvent.setup();
    render(
      <InvestigationPanel
        item={detail}
        user={reviewer}
        investigation={cited}
        isLatest
        onCompleted={vi.fn()}
        onDecision={vi.fn()}
        onEvidence={vi.fn()}
        loading={false}
        historyError={null}
      />,
    );
    const source = screen.getByRole("button", { name: "RB-TIMEOUT:v1" });
    await user.click(source);
    expect(
      screen.getByRole("dialog", { name: "Source citation" }),
    ).toBeVisible();
    const close = screen.getByRole("button", { name: "Close citation" });
    expect(close).toHaveFocus();
    await user.tab();
    expect(close).toHaveFocus();
    await user.tab({ shift: true });
    expect(close).toHaveFocus();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(source).toHaveFocus();
  });
  it("shows only a pending service request before the investigation returns", async () => {
    const user = userEvent.setup();
    const completed = vi.fn();
    let finish!: (response: Response) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(
        () =>
          new Promise<Response>((resolve) => {
            finish = resolve;
          }),
      ),
    );
    render(
      <InvestigationPanel
        item={detail}
        user={reviewer}
        isLatest
        onCompleted={completed}
        onDecision={vi.fn()}
        onEvidence={vi.fn()}
        loading={false}
        historyError={null}
      />,
    );
    await user.click(screen.getByRole("button", { name: "Run investigation" }));
    expect(screen.getByRole("status")).toHaveTextContent(
      "Waiting for the investigation service",
    );
    expect(
      screen.queryByText("Provider success is supported."),
    ).not.toBeInTheDocument();
    expect(completed).not.toHaveBeenCalled();
    finish(new Response(JSON.stringify(investigation), { status: 200 }));
    await waitFor(() => expect(completed).toHaveBeenCalledWith("INV-1"));
    expect(
      screen.queryByText("Waiting for the investigation service"),
    ).not.toBeInTheDocument();
  });
  it("keeps a late investigation response isolated from the next case and reloads saved history on return", async () => {
    const user = userEvent.setup();
    const nextCase = {
      ...detail,
      id: "CASE-2",
      title: "Second payment needs evidence",
    };
    let finish!: (response: Response) => void;
    let saved = false;
    let pendingSignal: AbortSignal | undefined;
    const reply = (data: unknown) =>
      Promise.resolve(new Response(JSON.stringify(data), { status: 200 }));
    const fetcher = vi.fn((url: string, options: RequestInit) => {
      if (url === "/api/auth/me")
        return reply({ user: reviewer, csrfToken: "csrf" });
      if (
        url === "/api/cases/CASE-1/investigations" &&
        options.method === "POST"
      ) {
        pendingSignal = options.signal as AbortSignal;
        return new Promise<Response>((resolve) => {
          finish = resolve;
        });
      }
      if (url === "/api/cases/CASE-1") return reply(detail);
      if (url === "/api/cases/CASE-2") return reply(nextCase);
      if (url === "/api/cases/CASE-1/investigations")
        return reply({ items: saved ? [cited] : [] });
      if (url.endsWith("/investigations") || url.endsWith("/audit"))
        return reply({ items: [] });
      return Promise.reject(new Error(`Unexpected request: ${url}`));
    });
    vi.stubGlobal("fetch", fetcher);
    vi.spyOn(window, "scrollTo").mockImplementation(() => undefined);
    window.history.replaceState({}, "", "/cases/CASE-1");
    render(<App />);
    await screen.findByRole("heading", { name: detail.title });
    await user.click(screen.getByRole("button", { name: "Run investigation" }));
    expect(screen.getByRole("status")).toHaveTextContent(
      "server work may continue",
    );
    await act(async () => {
      window.history.replaceState({}, "", "/cases/CASE-2");
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    await screen.findByRole("heading", { name: nextCase.title });
    expect(pendingSignal?.aborted).toBe(true);
    const callsAfterNavigation = fetcher.mock.calls.length;
    await act(async () => {
      saved = true;
      finish(new Response(JSON.stringify(cited), { status: 200 }));
    });
    expect(fetcher).toHaveBeenCalledTimes(callsAfterNavigation);
    expect(screen.queryByText(cited.summary)).not.toBeInTheDocument();
    expect(
      screen.queryByText("Waiting for the investigation service"),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: nextCase.title })).toBeVisible();
    await act(async () => {
      window.history.replaceState({}, "", "/cases/CASE-1");
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    expect(await screen.findByText(cited.summary)).toBeVisible();
    expect(screen.getByRole("heading", { name: detail.title })).toBeVisible();
  });
  it("reports an uncertain gateway timeout without inventing a result or recommending an immediate rerun", async () => {
    const user = userEvent.setup();
    const completed = vi.fn();
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response("<html>Gateway timeout</html>", { status: 504 }),
        ),
    );
    render(
      <InvestigationPanel
        item={detail}
        user={reviewer}
        isLatest
        onCompleted={completed}
        onDecision={vi.fn()}
        onEvidence={vi.fn()}
        loading={false}
        historyError={null}
      />,
    );
    await user.click(screen.getByRole("button", { name: "Run investigation" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "No completed result was received",
    );
    expect(
      screen.getByText(
        /Refresh saved investigations before starting another run/,
      ),
    ).toBeVisible();
    expect(
      screen.queryByText("Waiting for the investigation service"),
    ).not.toBeInTheDocument();
    expect(screen.queryByText(investigation.summary)).not.toBeInTheDocument();
    expect(completed).not.toHaveBeenCalled();
  });
  it("rejects a completed response for another case", async () => {
    const user = userEvent.setup();
    const completed = vi.fn();
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(
            JSON.stringify({ ...investigation, caseId: "CASE-OTHER" }),
            { status: 200 },
          ),
        ),
    );
    render(
      <InvestigationPanel
        item={detail}
        user={reviewer}
        isLatest
        onCompleted={completed}
        onDecision={vi.fn()}
        onEvidence={vi.fn()}
        loading={false}
        historyError={null}
      />,
    );
    await user.click(screen.getByRole("button", { name: "Run investigation" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "result for a different case",
    );
    expect(completed).not.toHaveBeenCalled();
  });
  it("labels earlier saved findings while another investigation is pending", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn(() => new Promise<Response>(() => {})),
    );
    render(
      <InvestigationPanel
        item={detail}
        user={reviewer}
        investigation={cited}
        isLatest
        onCompleted={vi.fn()}
        onDecision={vi.fn()}
        onEvidence={vi.fn()}
        loading={false}
        historyError={null}
      />,
    );
    await user.click(
      screen.getByRole("button", { name: "Run another investigation" }),
    );
    expect(
      screen.getByText(
        "Previously saved investigation · the new request is still pending",
      ),
    ).toBeVisible();
    expect(screen.getByText(cited.summary)).toBeVisible();
  });
});
