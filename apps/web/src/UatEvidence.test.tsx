import { afterEach, describe, expect, it, vi } from "vitest";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "./App";
import { UatEvidencePage } from "./UatEvidence";
import { setCsrfToken } from "./api";
import type { User } from "./types";

const analyst: User = {
  id: "fixture-user",
  name: "Fixture analyst",
  role: "ANALYST",
  tenantId: "fixture-tenant",
};
const document = {
  id: "fixture-evidence-A",
  kind: "evidence",
  title: "Fixture source record",
  content:
    "Original test fixture: the submitted source contains an unresolved exception.",
  source: {
    file: "fixture-export.xlsx",
    sheet: "FixtureRecords",
    range: "A2:C2",
  },
};
function snapshot(id = "fixture-A") {
  return {
    snapshotId: id,
    classification: "UAT",
    title: `Original test export ${id}`,
    paymentReference: `fixture-reference-${id}`,
    utr: null,
    amount: "123456789012345678901234567890.123456",
    currency: "INR",
    evidenceHash: `fixture-hash-${id}`,
    coverage: [{ name: "Fixture source", rowCount: 1, completion: "PARTIAL" }],
    warnings: ["This test export does not contain settlement evidence."],
    documents: [document],
  };
}
function response(question: string, id = "fixture-A") {
  const generatedText = `Generated fixture wording for the submitted question: ${question}`;
  return {
    answerId: `fixture-answer-${id}`,
    question,
    snapshotId: id,
    evidenceHash: `fixture-hash-${id}`,
    answer: generatedText,
    answerComposition: "joined-model-claims" as const,
    claims: [
      {
        text: generatedText,
        evidenceIds: [document.id],
      },
    ],
    unknowns: ["Final outcome is not supplied."],
    nextChecks: ["Obtain the missing outcome evidence."],
    citations: [document],
    model: {
      provider: "ollama",
      name: "fixture-local-model",
      actualCalls: 1,
      promptTokens: 170,
      completionTokens: 43,
      durationMs: 2400,
    },
    generatedAt: "2026-09-13T09:00:00Z",
    mode: "model-generated",
    retrieval: { method: "fixture-retrieval", documentIds: [document.id] },
  };
}
const reply = (data: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(data), { status }));
type MockOptions = {
  enabled?: boolean;
  empty?: boolean;
  status?: number;
  errorCode?: string;
  errorMessage?: string;
  history?: unknown[];
  transform?: (value: ReturnType<typeof response>) => unknown;
  hold?: boolean;
};
function mockApi(options: MockOptions = {}) {
  let pendingResult:
    { resolve: (value: Response) => void; body: unknown } | undefined;
  const fetcher = vi.fn((url: string, request: RequestInit) => {
    if (url === "/api/auth/me")
      return reply({ user: analyst, csrfToken: "fixture-session-csrf" });
    if (url === "/api/uat/snapshots")
      return reply({
        enabled: options.enabled ?? true,
        items: options.empty ? [] : [snapshot(), snapshot("fixture-B")],
      });
    for (const id of ["fixture-A", "fixture-B"]) {
      if (url === `/api/uat/snapshots/${id}`) return reply(snapshot(id));
      if (url === `/api/uat/snapshots/${id}/questions`) {
        if (request.method !== "POST")
          return reply({ items: options.history ?? [] });
        if (options.status)
          return reply(
            {
              code: options.errorCode ?? "UAT_MODEL_UNAVAILABLE",
              message:
                options.errorMessage ??
                "The local model did not complete the request. No fallback answer was used.",
              requestId: "fixture-request-failure",
            },
            options.status,
          );
        const data = JSON.parse(request.body as string);
        const body = options.transform
          ? options.transform(response(data.question, id))
          : response(data.question, id);
        if (options.hold)
          return new Promise<Response>((resolve) => {
            pendingResult = { resolve, body };
          });
        return reply(body);
      }
    }
    return Promise.reject(new Error(`Unexpected request: ${url}`));
  });
  vi.stubGlobal("fetch", fetcher);
  return {
    fetcher,
    finish: () =>
      pendingResult?.resolve(
        new Response(JSON.stringify(pendingResult.body), { status: 200 }),
      ),
  };
}
async function openPage(user = analyst) {
  const result = render(<UatEvidencePage user={user} />);
  await screen.findByLabelText("Your question");
  return result;
}
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  setCsrfToken(null);
  window.history.replaceState({}, "", "/");
});

describe("UAT evidence model questions", () => {
  it("sends arbitrary questions with the selected hash and CSRF, renders returned wording, and expands the exact citation", async () => {
    const { fetcher, finish } = mockApi({ hold: true });
    setCsrfToken("fixture-question-csrf");
    const user = userEvent.setup();
    await openPage();
    expect(
      screen.getByRole("note", { name: "Experimental answer warning" }),
    ).toHaveTextContent(
      "Experimental answers: live validation found unsupported claims. Verify field values and conclusions against the cited records.",
    );
    expect(
      screen.getByText("123456789012345678901234567890.123456"),
    ).toBeVisible();
    expect(screen.getByText("Partial")).toBeVisible();
    expect(
      screen.getByText(
        "This test export does not contain settlement evidence.",
      ),
    ).toBeVisible();
    const question =
      "Compare this exception with the available source and identify the missing fact.";
    await user.click(screen.getByLabelText("Your question"));
    await user.paste(question);
    await user.click(screen.getByRole("button", { name: "Ask local model" }));
    expect(
      screen.getByRole("button", { name: "Generating answer…" }),
    ).toBeDisabled();
    expect(
      screen.queryByRole("region", { name: "Model-generated answer" }),
    ).not.toBeInTheDocument();
    fireEvent.submit(screen.getByLabelText("Your question").closest("form")!);
    const calls = fetcher.mock.calls.filter(
      ([, request]) => request.method === "POST",
    );
    expect(calls).toHaveLength(1);
    expect(calls[0][0]).toBe("/api/uat/snapshots/fixture-A/questions");
    expect(JSON.parse(calls[0][1].body as string)).toEqual({
      question,
      evidenceHash: "fixture-hash-fixture-A",
    });
    expect((calls[0][1].headers as Headers).get("X-CSRF-Token")).toBe(
      "fixture-question-csrf",
    );
    finish();
    const answer = await screen.findByRole("region", {
      name: "Model-generated answer",
    });
    expect(
      within(answer).getByText(
        `Generated fixture wording for the submitted question: ${question}`,
      ),
    ).toBeVisible();
    expect(within(answer).getByText("1 actual model call")).toBeVisible();
    expect(within(answer).getByText("2.4s")).toBeVisible();
    expect(
      within(answer).getByText("Final outcome is not supplied."),
    ).toBeVisible();
    expect(
      within(answer).getByRole("region", {
        name: "What this evidence cannot establish",
      }),
    ).toHaveTextContent("Final outcome is not supplied.");
    expect(
      within(answer).getByRole("heading", {
        name: "Model-written claims and cited evidence",
      }),
    ).toBeVisible();
    expect(answer.querySelector(".uat-answer-text")).toBeNull();
    expect(
      within(answer).getByText(/do not automatically verify/),
    ).toBeVisible();
    await user.click(
      within(answer).getAllByRole("button", { name: document.id })[0],
    );
    expect(within(answer).getByText(document.content)).toBeVisible();
    expect(
      within(answer).getByText(/fixture-export.xlsx · FixtureRecords · A2:C2/),
    ).toBeVisible();
  });

  it("preserves the question after a provider failure and does not manufacture an answer", async () => {
    mockApi({ status: 503 });
    const user = userEvent.setup();
    await openPage();
    await user.type(
      screen.getByLabelText("Your question"),
      "What can be established from this export?",
    );
    await user.click(screen.getByRole("button", { name: "Ask local model" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "fixture-request-failure",
    );
    expect(screen.getByLabelText("Your question")).toHaveValue(
      "What can be established from this export?",
    );
    expect(
      screen.queryByRole("region", { name: "Model-generated answer" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText(/Generated fixture wording/),
    ).not.toBeInTheDocument();
  });

  it.each([
    [
      504,
      "UAT_MODEL_TIMEOUT",
      "If it times out again, check the local model service.",
    ],
    [
      504,
      "GATEWAY_TIMEOUT",
      "the local model may still be processing the earlier request.",
    ],
    [503, "UAT_MODEL_BUSY", "Another model request is still running."],
    [
      503,
      "UAT_MODEL_UNAVAILABLE",
      "Check that the local model service is running",
    ],
    [502, "INVALID_UAT_ANSWER", "The generated response failed validation."],
  ])(
    "explains %s %s without inventing an answer or discarding the question",
    async (status, errorCode, guidance) => {
      mockApi({ status: Number(status), errorCode: String(errorCode) });
      await openPage();
      const input = screen.getByLabelText("Your question");
      fireEvent.change(input, {
        target: { value: "Explain the available export." },
      });
      fireEvent.submit(input.closest("form")!);
      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent(guidance);
      expect(alert).toHaveTextContent("No fallback answer was used.");
      expect(alert).toHaveTextContent("fixture-request-failure");
      expect(input).toHaveValue("Explain the available export.");
      expect(
        screen.getByRole("button", { name: "Ask local model" }),
      ).toBeEnabled();
      expect(screen.queryByRole("timer")).not.toBeInTheDocument();
      expect(
        screen.queryByRole("region", { name: "Model-generated answer" }),
      ).not.toBeInTheDocument();
    },
  );

  it("shows measured elapsed waiting time, then stops the timer after a completed answer", async () => {
    const { finish } = mockApi({ hold: true });
    await openPage();
    vi.useFakeTimers();
    const input = screen.getByLabelText("Your question");
    fireEvent.change(input, {
      target: { value: "Explain the available export." },
    });
    fireEvent.submit(input.closest("form")!);
    expect(
      screen.getByText(/A cold local model can take several minutes/),
    ).toHaveAttribute("role", "status");
    expect(
      screen.getByRole("timer", { name: "Elapsed waiting time" }),
    ).toHaveTextContent("0:00 elapsed");
    expect(screen.getByRole("timer")).toHaveAttribute("aria-live", "off");
    expect(screen.getByText(/not an estimate of time remaining/)).toBeVisible();
    act(() => vi.advanceTimersByTime(65000));
    expect(screen.getByRole("timer")).toHaveTextContent("1:05 elapsed");
    expect(vi.getTimerCount()).toBe(1);
    await act(async () => {
      finish();
    });
    expect(
      screen.getByRole("region", { name: "Model-generated answer" }),
    ).toBeVisible();
    expect(screen.queryByRole("timer")).not.toBeInTheDocument();
    expect(vi.getTimerCount()).toBe(0);
    fireEvent.submit(input.closest("form")!);
    expect(screen.getByRole("timer")).toHaveTextContent("0:00 elapsed");
    await act(async () => {
      finish();
    });
    expect(vi.getTimerCount()).toBe(0);
  });

  it("clears the waiting timer and aborts the request when the page unmounts", async () => {
    const { fetcher, finish } = mockApi({ hold: true });
    const view = await openPage();
    vi.useFakeTimers();
    const input = screen.getByLabelText("Your question");
    fireEvent.change(input, {
      target: { value: "Explain the available export." },
    });
    fireEvent.submit(input.closest("form")!);
    const signal = fetcher.mock.calls.find(
      ([, request]) => request.method === "POST",
    )![1].signal!;
    act(() => vi.advanceTimersByTime(3000));
    expect(screen.getByRole("timer")).toHaveTextContent("0:03 elapsed");
    view.unmount();
    expect(signal.aborted).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
    await act(async () => {
      finish();
    });
    expect(
      screen.queryByRole("region", { name: "Model-generated answer" }),
    ).not.toBeInTheDocument();
  });

  it("aborts an in-flight question on snapshot switch and suppresses its late answer", async () => {
    const { fetcher, finish } = mockApi({ hold: true });
    const user = userEvent.setup();
    await openPage();
    await user.type(
      screen.getByLabelText("Your question"),
      "Explain the first snapshot.",
    );
    await user.click(screen.getByRole("button", { name: "Ask local model" }));
    const signal = fetcher.mock.calls.find(
      ([, request]) => request.method === "POST",
    )![1].signal!;
    await user.selectOptions(
      screen.getByLabelText("Evidence snapshot"),
      "fixture-B",
    );
    await waitFor(() =>
      expect(screen.getByLabelText("Your question")).toHaveValue(""),
    );
    expect(signal.aborted).toBe(true);
    finish();
    await waitFor(() =>
      expect(
        screen.getByText("fixture-reference-fixture-B", { selector: "dd" }),
      ).toBeVisible(),
    );
    expect(
      screen.queryByRole("region", { name: "Model-generated answer" }),
    ).not.toBeInTheDocument();
  });

  it.each(["hash", "citation", "calls", "composition", "missing composition"])(
    "rejects a response with invalid %s provenance",
    async (kind) => {
      mockApi({
        transform: (value) =>
          kind === "hash"
            ? { ...value, evidenceHash: "unrelated-hash" }
            : kind === "citation"
              ? {
                  ...value,
                  claims: [
                    {
                      text: "Unsupported fixture claim",
                      evidenceIds: ["missing-document"],
                    },
                  ],
                }
              : kind === "calls"
                ? { ...value, model: { ...value.model, actualCalls: 0 } }
                : kind === "composition"
                  ? { ...value, answer: "A separate unsupported summary." }
                  : { ...value, answerComposition: undefined },
      });
      const user = userEvent.setup();
      await openPage();
      await user.type(
        screen.getByLabelText("Your question"),
        "Explain the source.",
      );
      await user.click(screen.getByRole("button", { name: "Ask local model" }));
      await screen.findByRole("alert");
      expect(
        screen.queryByRole("region", { name: "Model-generated answer" }),
      ).not.toBeInTheDocument();
      expect(screen.getByLabelText("Your question")).toHaveValue(
        "Explain the source.",
      );
    },
  );

  it("distinguishes a saved earlier-evidence answer and opens its preserved documents, with unreported tokens left unknown", async () => {
    const saved = response("What did the earlier source show?");
    const earlierDocument = {
      ...document,
      id: "fixture-earlier-document",
      content: "Original test evidence from the earlier snapshot.",
    };
    mockApi({
      history: [
        {
          ...saved,
          answerComposition: undefined,
          evidenceHash: "fixture-hash-earlier",
          citations: [earlierDocument],
          claims: [
            {
              text: "Earlier fixture claim.",
              evidenceIds: [earlierDocument.id],
            },
          ],
          retrieval: {
            method: "fixture-retrieval",
            documentIds: [earlierDocument.id],
          },
          model: { ...saved.model, promptTokens: null, completionTokens: null },
        },
      ],
    });
    const user = userEvent.setup();
    await openPage();
    await user.click(
      await screen.findByRole("button", {
        name: /What did the earlier source show\?/,
      }),
    );
    const answer = screen.getByRole("region", {
      name: "Model-generated answer",
    });
    expect(
      within(answer).getByText(/used an earlier evidence hash/),
    ).toBeVisible();
    expect(
      within(answer).getByRole("heading", {
        name: "Historical free-prose summary",
      }),
    ).toBeVisible();
    expect(within(answer).getByText(saved.answer)).toBeVisible();
    expect(within(answer).getByText("Earlier fixture claim.")).toBeVisible();
    expect(within(answer).getByText("Review needed")).toBeVisible();
    expect(
      within(answer).getByText(
        "Not reported input / not reported output tokens",
      ),
    ).toBeVisible();
    await user.click(
      within(answer).getAllByRole("button", { name: earlierDocument.id })[0],
    );
    expect(within(answer).getByText(earlierDocument.content)).toBeVisible();
    expect(
      within(answer).queryByText(document.content),
    ).not.toBeInTheDocument();
  });

  it("renders joined claims once and keeps an empty unknown list explicitly uncertain", async () => {
    const claims = [
      {
        text: "First distinct generated fixture claim.",
        evidenceIds: [document.id],
      },
      {
        text: "Second distinct generated fixture claim.",
        evidenceIds: [document.id],
      },
    ];
    mockApi({
      history: [
        {
          ...response("Compare the supplied fixture facts."),
          answer: claims.map((claim) => claim.text).join("\n\n"),
          claims,
          unknowns: [],
        },
      ],
    });
    const user = userEvent.setup();
    await openPage();
    await user.click(
      await screen.findByRole("button", {
        name: /Compare the supplied fixture facts\./,
      }),
    );
    const answer = screen.getByRole("region", {
      name: "Model-generated answer",
    });
    for (const claim of claims)
      expect(within(answer).getAllByText(claim.text)).toHaveLength(1);
    expect(answer.querySelector(".uat-answer-text")).toBeNull();
    expect(
      within(answer).getByRole("region", {
        name: "What this evidence cannot establish",
      }),
    ).toHaveTextContent("does not establish that the evidence is complete");
    expect(
      within(answer).getByText(/service assembled the answer/),
    ).toBeVisible();
  });

  it("blocks empty input and viewer requests without preventing evidence reading", async () => {
    const { fetcher } = mockApi();
    const user = userEvent.setup();
    const view = await openPage();
    await user.click(screen.getByRole("button", { name: "Ask local model" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Enter a question between 1 and 2,000 characters.",
    );
    expect(
      fetcher.mock.calls.every(([, request]) => request.method !== "POST"),
    ).toBe(true);
    view.rerender(<UatEvidencePage user={{ ...analyst, role: "VIEWER" }} />);
    expect(
      screen.queryByRole("button", { name: "Ask local model" }),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText("Your question")).toHaveAttribute("readonly");
    fireEvent.submit(screen.getByLabelText("Your question").closest("form")!);
    expect(
      fetcher.mock.calls.every(([, request]) => request.method !== "POST"),
    ).toBe(true);
    expect(screen.getByText("Evidence and guidance")).toBeVisible();
  });

  it.each([false, true])(
    "renders disabled or empty configuration without a question action (enabled=%s)",
    async (enabled) => {
      mockApi({ enabled, empty: true });
      render(<UatEvidencePage user={analyst} />);
      await screen.findByText(
        enabled
          ? "No evidence exports available"
          : "Evidence access is disabled in this deployment.",
      );
      expect(
        screen.queryByRole("button", { name: "Ask local model" }),
      ).not.toBeInTheDocument();
    },
  );

  it("preserves standalone Q&A at /evidences/exports with its original records", async () => {
    mockApi();
    window.history.replaceState({}, "", "/evidences/exports");
    render(<App />);
    await screen.findByLabelText("Your question");
    expect(
      screen.getByRole("link", { name: "Evidence library" }),
    ).toHaveAttribute("href", "/evidences");
    expect(window.location.pathname).toBe("/evidences/exports");
    expect(window.location.hash).toBe("");
    expect(screen.getByText("IMPORTED EVIDENCE")).toBeVisible();
    expect(screen.getByText("Export review")).toBeVisible();
    expect(screen.queryByText("DEMO DATA")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Import synthetic NEFT evidence" }),
    ).not.toBeInTheDocument();
  });
});
