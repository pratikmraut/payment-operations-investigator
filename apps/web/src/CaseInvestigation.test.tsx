import { afterEach, describe, expect, it, vi } from "vitest";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { CaseInvestigation } from "./CaseInvestigation";
import { setCsrfToken } from "./api";
import { confirmUnsavedChanges } from "./unsavedChanges";

const caseId = "CASE-INVESTIGATION-FIXTURE-A";
const evidenceId = (version: number) =>
  `EVIDENCE-INVESTIGATION-FIXTURE-${version}`;
function evidence(version = 1, owner = caseId) {
  return {
    id: evidenceId(version),
    version,
    caseId: owner,
    evidenceHash: `original-hash-${version}`,
    sourceKind: "MANUAL",
    createdAt: `2026-09-14T10:00:0${version}Z`,
    warnings: [],
  };
}
function document(version = 1) {
  return {
    id: "ORIGINAL-RECORD",
    kind: "evidence",
    title: `Original fixture source version ${version}`,
    content: `Original fixture source text from version ${version}; raw status ${version === 1 ? "00" : "09"}.`,
    source: {
      file: "original-fixture.json",
      sheet: null,
      range: null,
      locator: `sections.PAYMENT.rows[${version - 1}]`,
    },
  };
}
function context(version = 1) {
  return {
    evidenceId: evidenceId(version),
    evidenceVersion: version,
    evidenceHash: `original-hash-${version}`,
    guidanceHash: "original-guidance-hash",
    nonEmptyRows: 1,
    documents: [document(version)],
    warnings: ["Original fixture: source-local timezone is unconfirmed."],
    timeline: [
      {
        id: "original-timeline-row",
        label: "PAYMENT exported row 1",
        timestamp: null,
        group: "PAYMENT",
        rowIndex: 1,
        documentId: "ORIGINAL-RECORD",
        fields: {
          AMOUNT: "12345678901234567890.0009",
          NATIVE_STATUS: "00",
          SOURCE_DATE: "",
        },
      },
    ],
  };
}
type Status = "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED";
function job(
  id = "ORIGINAL-JOB-1",
  status: Status = "COMPLETED",
  version = 1,
  question = "Explain the original fixture records.",
) {
  return {
    id,
    caseId,
    evidenceId: evidenceId(version),
    evidenceVersion: version,
    evidenceHash: `original-hash-${version}`,
    question,
    status,
    createdAt: "2026-09-14T11:00:01Z",
    createdBy: "Original fixture analyst",
  };
}
function detail(value: ReturnType<typeof job>) {
  const doc = document(value.evidenceVersion);
  const claim = `Generated fixture interpretation for evidence version ${value.evidenceVersion}.`;
  return {
    ...value,
    documents: [doc],
    ...(value.status === "FAILED"
      ? {
          error: {
            code: "ORIGINAL_MODEL_FAILURE",
            message: "The original fixture model could not complete this job.",
          },
        }
      : {}),
    ...(value.status === "COMPLETED"
      ? {
          answer: {
            answerId: `ANSWER-${value.id}`,
            snapshotId: value.evidenceId,
            evidenceHash: value.evidenceHash,
            question: value.question,
            answer: claim,
            answerComposition: "joined-model-claims",
            mode: "model-generated",
            claims: [{ text: claim, evidenceIds: [doc.id] }],
            unknowns: ["The final outcome is absent from this fixture."],
            nextChecks: ["Obtain the missing fixture outcome record."],
            citations: [doc],
            generatedAt: "2026-09-14T11:00:05Z",
            model: {
              provider: "ollama",
              name: "original-local-model",
              actualCalls: 1,
              durationMs: 1234,
              promptTokens: null,
              completionTokens: 32,
            },
            retrieval: {
              method: "original-fixture-retrieval",
              documentIds: [doc.id],
            },
          },
        }
      : {}),
  };
}
function ragDetail(value: ReturnType<typeof detail>) {
  const row = {
    ...document(value.evidenceVersion),
    id: "PAYMENT-ROW-1",
    content: JSON.stringify({
      REFTXNNUMBER: "00012345678901234567890",
      NUMAMOUNT_4038: "12345678901234567890.0009",
    }),
  };
  const claims = [
    {
      text: "The supplied row records REFTXNNUMBER=00012345678901234567890 and NUMAMOUNT_4038=12345678901234567890.0009.",
      evidenceIds: [row.id],
    },
    {
      text: "The supplied row does not establish beneficiary credit.",
      evidenceIds: [row.id],
    },
  ];
  return {
    ...value,
    documents: [row],
    answer: {
      ...value.answer!,
      answer: claims.map((claim) => claim.text).join("\n\n"),
      claims,
      citations: [row],
      retrieval: { method: "bm25-ranked-all-supplied", documentIds: [row.id] },
      rag: {
        pipeline: "case-evidence-rag-v1",
        promptHash: "a".repeat(64),
        checks: [
          "source-membership",
          "literal-field-quotations",
          "required-unknowns-and-next-checks",
        ],
        claimSupports: [
          {
            claimIndex: 0,
            claimType: "observation",
            fields: [
              {
                documentId: row.id,
                field: "REFTXNNUMBER",
                value: "00012345678901234567890",
              },
              {
                documentId: row.id,
                field: "NUMAMOUNT_4038",
                value: "12345678901234567890.0009",
              },
            ],
          },
          { claimIndex: 1, claimType: "limitation", fields: [] },
        ],
      },
    },
  };
}
type Options = {
  caseStatus?: string;
  lifecycleState?: "ACTIVE" | "ARCHIVED";
  jobs?: ReturnType<typeof job>[];
  versions?: number[];
  contextStatus?: number;
  contextTransform?: (value: ReturnType<typeof context>) => unknown;
  detailTransform?: (value: ReturnType<typeof detail>) => unknown;
  resultStatus?: Status;
  runningFirst?: boolean;
  postFailures?: number;
  postFailureStatus?: number;
  pollFailure?: boolean;
  holdDetail?: boolean;
  holdPost?: boolean;
};
const reply = (data: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(data), { status }));
function mockApi(options: Options = {}) {
  let jobs = [...(options.jobs ?? [])];
  const reads = new Map<string, number>();
  let attempts = 0;
  let heldDetail: (() => void) | undefined;
  let heldPost: (() => void) | undefined;
  const fetcher = vi.fn((url: string, request: RequestInit) => {
    const match =
      /^\/api\/payment-cases\/(CASE-INVESTIGATION-FIXTURE-[AB])\/(.*)$/.exec(
        url,
      );
    if (!match) throw new Error(`Unexpected test endpoint ${url}`);
    const [, owner, suffix] = match;
    if (suffix === "workbench") {
      const versions = options.versions ?? [1];
      return reply({
        caseId: owner,
        lifecycleState: options.lifecycleState,
        status: options.caseStatus,
        evidence: versions.map((version) => evidence(version, owner)),
        latestEvidenceId: versions.length ? evidenceId(versions[0]) : null,
        investigations: owner === caseId ? jobs : [],
        audit: [
          {
            id: "original-audit-opened",
            action: "CASE_OPENED",
            occurredAt: "2026-09-14T10:00:00Z",
            actor: "Original fixture analyst",
            detail: "Original case record opened for inspection.",
          },
        ],
      });
    }
    if (suffix.endsWith("/context")) {
      if (options.contextStatus)
        return reply(
          {
            code: "ORIGINAL_CONTEXT_INVALID",
            message: "The original fixture exceeds the evidence context limit.",
            requestId: "original-context-request",
          },
          options.contextStatus,
        );
      const version = suffix.includes(evidenceId(2)) ? 2 : 1;
      return reply(
        options.contextTransform
          ? options.contextTransform(context(version))
          : context(version),
      );
    }
    if (suffix === "investigations" && request.method === "POST") {
      if (attempts++ < (options.postFailures ?? 0))
        return reply(
          {
            code: "ORIGINAL_CONNECTION_FAILURE",
            message: "The original fixture request could not be confirmed.",
            requestId: "original-post-request",
          },
          options.postFailureStatus ?? 503,
        );
      const body = JSON.parse(request.body as string);
      const version = body.evidenceId === evidenceId(2) ? 2 : 1;
      const saved = job(
        `ORIGINAL-JOB-${jobs.length + 1}`,
        "QUEUED",
        version,
        body.question,
      );
      jobs = [saved, ...jobs];
      if (options.holdPost)
        return new Promise<Response>((resolve) => {
          heldPost = () =>
            resolve(new Response(JSON.stringify(saved), { status: 202 }));
        });
      return reply(saved, 202);
    }
    if (suffix.startsWith("investigations/")) {
      const id = suffix.slice("investigations/".length);
      const found = jobs.find((item) => item.id === id);
      if (!found) throw new Error(`Unknown fixture job ${id}`);
      const count = (reads.get(id) ?? 0) + 1;
      reads.set(id, count);
      if (options.pollFailure && count === 1)
        return reply(
          {
            code: "ORIGINAL_STATUS_ERROR",
            message: "The fixture status service is unavailable.",
            requestId: "original-poll-request",
          },
          503,
        );
      const next = {
        ...found,
        status:
          found.status === "COMPLETED" || found.status === "FAILED"
            ? found.status
            : options.runningFirst && count === 1
              ? ("RUNNING" as const)
              : (options.resultStatus ?? "COMPLETED"),
      };
      jobs = jobs.map((item) => (item.id === id ? next : item));
      const body = options.detailTransform
        ? options.detailTransform(detail(next))
        : detail(next);
      if (options.holdDetail)
        return new Promise<Response>((resolve) => {
          heldDetail = () => resolve(new Response(JSON.stringify(body)));
        });
      return reply(body);
    }
    throw new Error(`Unexpected test endpoint ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
  return {
    fetcher,
    finishDetail: () => heldDetail?.(),
    finishPost: () => heldPost?.(),
  };
}
const posts = (fetcher: ReturnType<typeof mockApi>["fetcher"]) =>
  fetcher.mock.calls.filter(([, request]) => request.method === "POST");
async function openPage(canWrite = true) {
  const view = render(
    <CaseInvestigation caseId={caseId} canWrite={canWrite} />,
  );
  await screen.findByLabelText("Your case question");
  await waitFor(() =>
    expect(
      screen.queryByText("Loading selected evidence context…"),
    ).not.toBeInTheDocument(),
  );
  return view;
}
function ask(question = "What does the original fixture establish?") {
  fireEvent.change(screen.getByLabelText("Your case question"), {
    target: { value: question },
  });
  fireEvent.click(screen.getByRole("button", { name: "Run investigation" }));
}
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
  setCsrfToken(null);
});

describe("case investigation workbench", () => {
  it("keeps a failed question dirty, then clears the warning once the request is saved", async () => {
    mockApi({ postFailures: 1 });
    setCsrfToken("fixture-csrf");
    await openPage();
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    fireEvent.change(screen.getByLabelText("Your case question"), {
      target: { value: "Review the supplied source records." },
    });
    expect(confirmUnsavedChanges()).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Run investigation" }));
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Run investigation" }),
      ).toBeEnabled(),
    );
    expect(confirmUnsavedChanges()).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Run investigation" }));
    await screen.findByRole("region", { name: "Selected investigation" });
    expect(confirmUnsavedChanges()).toBe(true);
    expect(confirm).toHaveBeenCalledTimes(2);
  });
  it("restores a case-bound question draft without submitting it and marks it unsaved", async () => {
    const { fetcher } = mockApi();
    await openPage();
    fireEvent.change(screen.getByLabelText("Restore text draft"), {
      target: {
        files: [
          new File(
            [
              JSON.stringify({
                draftVersion: "case-text-draft-v1",
                scope: `${caseId}:investigation-question`,
                value: "Which evidence is still needed?",
              }),
            ],
            "question-draft.json",
            { type: "application/json" },
          ),
        ],
      },
    });
    await waitFor(() =>
      expect(screen.getByLabelText("Your case question")).toHaveValue(
        "Which evidence is still needed?",
      ),
    );
    expect(posts(fetcher)).toHaveLength(0);
    vi.spyOn(window, "confirm").mockReturnValue(false);
    expect(confirmUnsavedChanges()).toBe(false);
  });
  it("disables new questions for a resolved case returned by the workbench", async () => {
    const { fetcher } = mockApi({ caseStatus: "RESOLVED" });
    await openPage();
    expect(screen.getByText(/This case is resolved. Reopen it/)).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "Run investigation" }),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText("Your case question")).toHaveAttribute(
      "readonly",
    );
    expect(posts(fetcher)).toHaveLength(0);
  });
  it("honors archived state returned by a refreshed workbench and keeps saved answers readable", async () => {
    mockApi({ lifecycleState: "ARCHIVED", jobs: [job()] });
    render(<CaseInvestigation caseId={caseId} canWrite />);
    await screen.findByText(/This case is archived. Saved investigations/);
    expect(
      screen.queryByRole("button", { name: "Run investigation" }),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText("Your case question")).toHaveAttribute(
      "readonly",
    );
    expect(
      await screen.findByText(
        "Generated fixture interpretation for evidence version 1.",
      ),
    ).toBeVisible();
  });
  it("shows saved server total in history and the Q&A result separately from model generation", async () => {
    const saved = {
      ...job(),
      requestedAt: "2026-09-14T11:00:00Z",
      timing: {
        preparationMs: 1000,
        queueMs: 2000,
        processingMs: 51000,
        totalMs: 54000,
        totalBasis: "request-received",
      },
    };
    mockApi({ jobs: [saved] });
    render(<CaseInvestigation caseId={caseId} canWrite presentation="qa" />);
    await screen.findByText(
      "Generated fixture interpretation for evidence version 1.",
    );
    const result = screen.getByRole("region", {
      name: "Selected investigation",
    });
    expect(within(result).getByText(/Server total:/)).toHaveTextContent(
      "00:54",
    );
    expect(
      within(result).getByText(
        "Preparation 00:01 · Queue 00:02 · Processing 00:51",
      ),
    ).toBeVisible();
    expect(within(result).getByText("Model generation: 1.2s")).toBeVisible();
    expect(
      screen.getByRole("button", {
        name: /Explain the original fixture records.*ORIGINAL-JOB-1/,
      }),
    ).toHaveTextContent("Server total: 00:54");
    expect(screen.queryByRole("timer")).not.toBeInTheDocument();
  });

  it("starts timing before POST returns, preserves it while selecting history, and stops on the terminal poll", async () => {
    const options: Options = {
      jobs: [job("ORIGINAL-HISTORY")],
      holdPost: true,
      runningFirst: true,
    };
    const { fetcher, finishPost, finishDetail } = mockApi(options);
    await openPage();
    await screen.findByText(
      "Generated fixture interpretation for evidence version 1.",
    );
    vi.useFakeTimers({
      toFake: ["setTimeout", "clearTimeout", "setInterval", "clearInterval"],
    });
    let now = 0;
    vi.spyOn(performance, "now").mockImplementation(() => now);
    options.holdDetail = true;
    ask("Time this explicit investigation.");
    expect(screen.getByRole("timer")).toHaveTextContent("00:00");
    expect(screen.getByText("Preparing and saving request…")).toBeVisible();
    const callsBeforeTick = fetcher.mock.calls.length;
    await act(async () => {
      now = 14000;
      vi.advanceTimersByTime(14000);
    });
    expect(screen.getByRole("timer")).toHaveTextContent("00:14");
    expect(fetcher.mock.calls.length).toBe(callsBeforeTick);
    await act(async () => {
      finishPost();
    });
    expect(screen.getByText("Queued · waiting to start")).toBeVisible();
    fireEvent.click(
      screen.getByRole("button", {
        name: /Explain the original fixture records.*ORIGINAL-HISTORY/,
      }),
    );
    expect(
      screen.getByRole("region", { name: "Selected investigation" }),
    ).toHaveTextContent("Explain the original fixture records.");
    const queuedCalls = fetcher.mock.calls.length;
    await act(async () => {
      now = 19000;
      vi.advanceTimersByTime(1000);
    });
    expect(screen.getByRole("timer")).toHaveTextContent("00:19");
    expect(fetcher.mock.calls.length).toBe(queuedCalls);
    await act(async () => {
      finishDetail();
    });
    expect(screen.getByText("Running investigation…")).toBeVisible();
    await act(async () => {
      now = 21000;
      vi.advanceTimersByTime(2000);
    });
    await act(async () => {
      now = 25500;
      finishDetail();
    });
    expect(screen.getByText("Completed · status received")).toBeVisible();
    expect(screen.getByRole("timer")).toHaveTextContent("00:25");
    const terminalCalls = fetcher.mock.calls.length;
    await act(async () => {
      now = 80000;
      vi.advanceTimersByTime(10000);
    });
    expect(screen.getByRole("timer")).toHaveTextContent("00:25");
    expect(fetcher.mock.calls.length).toBe(terminalCalls);
    expect(posts(fetcher)).toHaveLength(1);
  });

  it.each(["FAILED", "unknown"] as const)(
    "handles %s polling results without claiming a successful answer",
    async (status) => {
      const options: Options = {
        holdPost: true,
        holdDetail: true,
        resultStatus: "FAILED",
        ...(status === "unknown"
          ? {
              detailTransform: (value: ReturnType<typeof detail>) => ({
                ...value,
                status: "UNRECOGNIZED",
              }),
            }
          : {}),
      };
      const { finishPost, finishDetail, fetcher } = mockApi(options);
      await openPage();
      vi.useFakeTimers({
        toFake: ["setTimeout", "clearTimeout", "setInterval", "clearInterval"],
      });
      let now = 0;
      vi.spyOn(performance, "now").mockImplementation(() => now);
      ask();
      await act(async () => {
        now = 5000;
        finishPost();
      });
      await act(async () => {
        now = 10000;
        finishDetail();
      });
      if (status === "FAILED") {
        expect(screen.getByText("Failed · no completed answer")).toBeVisible();
        expect(screen.getByRole("timer")).toHaveTextContent("00:10");
      } else
        expect(
          screen.getByText("Status unavailable · still waiting"),
        ).toBeVisible();
      const calls = fetcher.mock.calls.length;
      await act(async () => {
        now = 20000;
        vi.advanceTimersByTime(10000);
      });
      expect(screen.getByRole("timer")).toHaveTextContent(
        status === "FAILED" ? "00:10" : "00:20",
      );
      expect(
        screen.queryByText("Completed · status received"),
      ).not.toBeInTheDocument();
      expect(fetcher.mock.calls.length).toBe(calls);
    },
  );

  it.each([422, 503])(
    "treats a POST %s as a rejected request or an unconfirmed submission",
    async (status) => {
      mockApi({ postFailures: 1, postFailureStatus: status });
      await openPage();
      vi.useFakeTimers({
        toFake: ["setTimeout", "clearTimeout", "setInterval", "clearInterval"],
      });
      let now = 0;
      vi.spyOn(performance, "now").mockImplementation(() => now);
      await act(async () => {
        ask();
      });
      expect(
        screen.getByText(
          status === 422
            ? "Request rejected"
            : "Status unavailable · still waiting",
        ),
      ).toBeVisible();
      await act(async () => {
        now = 5000;
        vi.advanceTimersByTime(5000);
      });
      expect(screen.getByRole("timer")).toHaveTextContent(
        status === 422 ? "00:00" : "00:05",
      );
    },
  );

  it("drops the local timer and aborts a preparing request when the case changes", async () => {
    const { fetcher } = mockApi({ holdPost: true });
    const view = await openPage();
    vi.useFakeTimers({
      toFake: ["setTimeout", "clearTimeout", "setInterval", "clearInterval"],
    });
    ask();
    expect(screen.getByRole("timer")).toBeVisible();
    const signal = posts(fetcher)[0][1].signal!;
    await act(async () => {
      view.rerender(
        <CaseInvestigation caseId="CASE-INVESTIGATION-FIXTURE-B" canWrite />,
      );
    });
    expect(signal.aborted).toBe(true);
    expect(screen.queryByRole("timer")).not.toBeInTheDocument();
    expect(screen.queryByText("Browser elapsed")).not.toBeInTheDocument();
  });

  it("offers a case-specific PDF export from the Evidence Q&A workbench without running a question", async () => {
    const { fetcher } = mockApi();
    render(
      <CaseInvestigation caseId={caseId} presentation="qa" canWrite={false} />,
    );
    const link = await screen.findByRole("link", { name: "Export PDF" });
    expect(link).toHaveAttribute("href", `/payment-cases/${caseId}?report=1`);
    expect(posts(fetcher)).toHaveLength(0);
  });
  it("shows raw source rows, exact strings and recorded audit without automatically running a model", async () => {
    const { fetcher } = mockApi();
    await openPage();
    expect(await screen.findByText("PAYMENT exported row 1")).toBeVisible();
    expect(screen.getByText("Timestamp not supplied")).toBeVisible();
    fireEvent.click(screen.getByText("Raw fields"));
    expect(screen.getByText("12345678901234567890.0009")).toBeVisible();
    expect(screen.getByText("00")).toBeVisible();
    expect(screen.getByText("(blank)")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "ORIGINAL-RECORD" }));
    expect(screen.getByText(document().content)).toBeVisible();
    fireEvent.click(screen.getByRole("tab", { name: "Audit trail" }));
    expect(
      screen.getByText("Original case record opened for inspection."),
    ).toBeVisible();
    expect(
      screen.getByText("2026-09-14T10:00:00Z · Original fixture analyst"),
    ).toBeVisible();
    expect(posts(fetcher)).toHaveLength(0);
  });

  it("submits a version-bound question once, polls running jobs and displays model claims with preserved citations", async () => {
    const { fetcher } = mockApi({ runningFirst: true });
    setCsrfToken("original-case-csrf");
    await openPage();
    ask("What can these fixture records establish?");
    await screen.findByRole("button", { name: "Investigation in progress…" });
    expect(
      screen.getByLabelText("Investigation evidence version"),
    ).toBeDisabled();
    fireEvent.submit(
      screen.getByLabelText("Your case question").closest("form")!,
    );
    expect(posts(fetcher)).toHaveLength(1);
    expect(JSON.parse(posts(fetcher)[0][1].body as string)).toEqual({
      question: "What can these fixture records establish?",
      evidenceId: evidenceId(1),
      evidenceHash: "original-hash-1",
    });
    expect((posts(fetcher)[0][1].headers as Headers).get("X-CSRF-Token")).toBe(
      "original-case-csrf",
    );
    const result = await screen.findByRole("region", {
      name: "Selected investigation",
    });
    await waitFor(
      () =>
        expect(
          within(result).getByText(
            "Generated fixture interpretation for evidence version 1.",
          ),
        ).toBeVisible(),
      { timeout: 4000 },
    );
    expect(
      within(result).getAllByText(
        "Generated fixture interpretation for evidence version 1.",
      ),
    ).toHaveLength(1);
    const headings = within(result)
      .getAllByRole("heading")
      .map((node) => node.textContent);
    expect(headings.indexOf("Model-generated answer")).toBeLessThan(
      headings.indexOf("What this evidence cannot establish"),
    );
    expect(
      within(result).getByRole("region", {
        name: "What this evidence cannot establish",
      }),
    ).toHaveTextContent("The final outcome is absent from this fixture.");
    expect(within(result).getByText("1 actual model call")).toBeVisible();
    expect(
      within(result).getByText("Not reported input / 32 output tokens"),
    ).toBeVisible();
    fireEvent.click(
      within(result).getByRole("button", { name: "ORIGINAL-RECORD" }),
    );
    expect(within(result).getByText(document().content)).toBeVisible();
    expect(screen.getByRole("note")).toHaveTextContent(
      "Source membership checks do not verify factual correctness",
    );
  });

  it("resumes a queued job after reload without submitting another question", async () => {
    const saved = job("ORIGINAL-PENDING", "QUEUED");
    const { fetcher } = mockApi({ jobs: [saved] });
    await openPage();
    await screen.findByText(
      "Generated fixture interpretation for evidence version 1.",
    );
    expect(posts(fetcher)).toHaveLength(0);
    expect(
      fetcher.mock.calls.some(([url]) =>
        url.endsWith("/investigations/ORIGINAL-PENDING"),
      ),
    ).toBe(true);
  });

  it("allows a viewer to read saved answers and sources without a run action", async () => {
    const { fetcher } = mockApi({ jobs: [job()] });
    await openPage(false);
    await screen.findByText(
      "Generated fixture interpretation for evidence version 1.",
    );
    expect(screen.getByLabelText("Your case question")).toHaveAttribute(
      "readonly",
    );
    expect(
      screen.queryByRole("button", { name: "Run investigation" }),
    ).not.toBeInTheDocument();
    fireEvent.submit(
      screen.getByLabelText("Your case question").closest("form")!,
    );
    expect(posts(fetcher)).toHaveLength(0);
  });

  it("creates separate jobs for successive explicit questions while retaining prior answers", async () => {
    const { fetcher } = mockApi();
    await openPage();
    ask("First original fixture question.");
    await screen.findByText(
      "Generated fixture interpretation for evidence version 1.",
    );
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Run investigation" }),
      ).toBeEnabled(),
    );
    ask("Second original fixture question.");
    await waitFor(() => expect(posts(fetcher)).toHaveLength(2));
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Run investigation" }),
      ).toBeEnabled(),
    );
    expect(
      (posts(fetcher)[0][1].headers as Headers).get("Idempotency-Key"),
    ).not.toBe(
      (posts(fetcher)[1][1].headers as Headers).get("Idempotency-Key"),
    );
    const first = screen.getByRole("button", {
      name: /First original fixture question\./,
    });
    const second = screen.getByRole("button", {
      name: /Second original fixture question\./,
    });
    expect(first).toBeVisible();
    expect(second).toBeVisible();
    fireEvent.click(first);
    expect(
      within(
        screen.getByRole("region", { name: "Selected investigation" }),
      ).getByText("First original fixture question."),
    ).toBeVisible();
  });

  it("keeps failed jobs visible without manufacturing an answer and permits a new explicit run", async () => {
    const { fetcher } = mockApi({ resultStatus: "FAILED" });
    await openPage();
    ask();
    await screen.findByText(
      "The original fixture model could not complete this job.",
    );
    expect(
      screen.queryByText(/Generated fixture interpretation/),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText("Your case question")).toHaveValue(
      "What does the original fixture establish?",
    );
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Run investigation" }),
      ).toBeEnabled(),
    );
    ask();
    await waitFor(() => expect(posts(fetcher)).toHaveLength(2));
    expect(
      (posts(fetcher)[0][1].headers as Headers).get("Idempotency-Key"),
    ).not.toBe(
      (posts(fetcher)[1][1].headers as Headers).get("Idempotency-Key"),
    );
  });

  it("retains an uncertain submission key for an unchanged retry", async () => {
    const { fetcher } = mockApi({ postFailures: 1 });
    await openPage();
    ask();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "original-post-request",
    );
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Run investigation" }),
      ).toBeEnabled(),
    );
    ask();
    await screen.findByText(
      "Generated fixture interpretation for evidence version 1.",
    );
    expect(
      (posts(fetcher)[0][1].headers as Headers).get("Idempotency-Key"),
    ).toBe((posts(fetcher)[1][1].headers as Headers).get("Idempotency-Key"));
  });

  it("allows bounded knowledge selection before submission and retains its key after timeout", async () => {
    const { fetcher, finishPost } = mockApi({ holdPost: true });
    const view = await openPage();
    vi.useFakeTimers();
    ask();
    const signal = posts(fetcher)[0][1].signal!;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30000);
    });
    expect(signal.aborted).toBe(false);
    expect(
      screen.getByRole("button", { name: "Saving request…" }),
    ).toBeDisabled();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(135000);
      finishPost();
    });
    expect(signal.aborted).toBe(true);
    expect(screen.getByRole("alert")).toHaveTextContent("165 seconds");
    expect(screen.getByLabelText("Your case question")).toHaveValue(
      "What does the original fixture establish?",
    );
    ask();
    expect(posts(fetcher)).toHaveLength(2);
    expect(
      (posts(fetcher)[0][1].headers as Headers).get("Idempotency-Key"),
    ).toBe((posts(fetcher)[1][1].headers as Headers).get("Idempotency-Key"));
    view.unmount();
    await act(async () => {
      finishPost();
    });
  });

  it("keeps read requests on their existing 30-second deadline", async () => {
    const timeout = vi.spyOn(window, "setTimeout");
    const { fetcher } = mockApi();
    await openPage();
    expect(timeout.mock.calls.some(([, delay]) => delay === 30000)).toBe(true);
    expect(timeout.mock.calls.some(([, delay]) => delay === 165000)).toBe(
      false,
    );
    expect(posts(fetcher)).toHaveLength(0);
  });

  it("blocks empty evidence and actionable context failures without model requests", async () => {
    const { fetcher } = mockApi({ versions: [] });
    const view = await openPage();
    expect(screen.getByText("Attach evidence to start")).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Run investigation" }),
    ).toBeDisabled();
    expect(posts(fetcher)).toHaveLength(0);
    view.unmount();
    const next = mockApi({ contextStatus: 422 });
    await openPage();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "original-context-request",
    );
    expect(
      screen.getByText(/This version cannot currently be investigated/),
    ).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Run investigation" }),
    ).toBeDisabled();
    expect(posts(next.fetcher)).toHaveLength(0);
  });

  it.each(["zero rows", "hash"])(
    "cannot run against %s context",
    async (kind) => {
      const { fetcher } = mockApi({
        contextTransform: (value) =>
          kind === "hash"
            ? { ...value, evidenceHash: "unrelated-hash" }
            : { ...value, nonEmptyRows: 0 },
      });
      await openPage();
      expect(
        screen.getByRole("button", { name: "Run investigation" }),
      ).toBeDisabled();
      expect(posts(fetcher)).toHaveLength(0);
      if (kind === "hash")
        expect(await screen.findByRole("alert")).toHaveTextContent(
          "does not match this evidence version",
        );
      else
        expect(
          await screen.findByText(/This version has no source rows/),
        ).toBeVisible();
    },
  );

  it("shows older answer provenance and opens its frozen citation despite a newer selected context", async () => {
    mockApi({ versions: [2, 1], jobs: [job()] });
    await openPage();
    const result = await screen.findByRole("region", {
      name: "Selected investigation",
    });
    await within(result).findByText(
      "Generated fixture interpretation for evidence version 1.",
    );
    expect(
      within(result).getByText(/used an earlier evidence version/),
    ).toBeVisible();
    fireEvent.click(screen.getByRole("tab", { name: "Evidence" }));
    expect(screen.getByText("Original fixture source version 2")).toBeVisible();
    fireEvent.click(
      within(result).getByRole("button", { name: "ORIGINAL-RECORD" }),
    );
    expect(within(result).getByText(document(1).content)).toBeVisible();
    expect(
      within(result).queryByText(document(2).content),
    ).not.toBeInTheDocument();
    expect(
      within(result).queryByText("Checked source fields"),
    ).not.toBeInTheDocument();
    fireEvent.click(within(result).getByText("Saved answer provenance"));
    expect(
      within(result).queryByText(/Prompt fingerprint/),
    ).not.toBeInTheDocument();
  });

  it("shows checked explicit field quotations and saved prompt provenance without writes", async () => {
    const { fetcher } = mockApi({
      versions: [2, 1],
      jobs: [job()],
      detailTransform: ragDetail,
    });
    await openPage(false);
    const summary = await screen.findByText("Checked source fields");
    const fields = summary.closest("details")!;
    expect(fields).not.toHaveAttribute("open");
    expect(screen.getAllByText("Checked source fields")).toHaveLength(1);
    expect(
      screen.getByText(
        "The supplied row records REFTXNNUMBER=00012345678901234567890 and NUMAMOUNT_4038=12345678901234567890.0009.",
      ),
    ).toBeVisible();
    fireEvent.click(summary);
    expect(within(fields).getByText("00012345678901234567890")).toBeVisible();
    expect(within(fields).getByText("12345678901234567890.0009")).toBeVisible();
    expect(within(fields).getByText("NUMAMOUNT_4038")).toBeVisible();
    expect(
      within(fields).getByText(
        "Explicit field quotations checked against saved rows; other wording and interpretations still require review.",
      ),
    ).toBeVisible();
    fireEvent.click(
      within(fields).getAllByRole("button", { name: "PAYMENT-ROW-1" })[0],
    );
    expect(
      screen.getByText(ragDetail(detail(job())).documents[0].content),
    ).toBeVisible();
    fireEvent.click(screen.getByText("Saved answer provenance"));
    expect(screen.getByText("case-evidence-rag-v1")).toBeVisible();
    expect(screen.getByText("a".repeat(64))).toBeVisible();
    expect(
      screen.getByText(
        "Validation: source membership and response structure; human factual review required.",
      ),
    ).toBeVisible();
    expect(screen.getByText("Experimental model answers")).toBeVisible();
    expect(posts(fetcher)).toHaveLength(0);
  });

  it("allows source-cited natural prose and guidance-only citations without a checked-field label", async () => {
    const { fetcher } = mockApi({
      jobs: [job()],
      detailTransform: (value) => {
        const saved = ragDetail(value);
        const guidance = {
          ...document(),
          id: "FIELD-GUIDANCE",
          kind: "knowledge",
          content:
            "A source amount alone does not establish the payment outcome.",
        };
        saved.documents.push(guidance);
        saved.answer.citations.push(guidance);
        saved.answer.retrieval.documentIds.push(guidance.id);
        saved.answer.claims[0].text =
          "The supplied payment reference is 00012345678901234567890 and its source amount is 12345678901234567890.0009.";
        saved.answer.claims[1] = {
          text: "The supplied guidance says an amount alone does not establish the outcome.",
          evidenceIds: [guidance.id],
        };
        saved.answer.answer = saved.answer.claims
          .map((claim) => claim.text)
          .join("\n\n");
        saved.answer.rag.claimSupports = saved.answer.claims.map(
          (_claim, claimIndex) => ({
            claimIndex,
            claimType: "source-cited",
            fields: [],
          }),
        );
        return saved;
      },
    });
    await openPage(false);
    expect(
      await screen.findByText(
        "The supplied guidance says an amount alone does not establish the outcome.",
      ),
    ).toBeVisible();
    expect(
      screen.getByText(
        "The supplied payment reference is 00012345678901234567890 and its source amount is 12345678901234567890.0009.",
      ),
    ).toBeVisible();
    expect(screen.queryByText("Checked source fields")).not.toBeInTheDocument();
    expect(
      screen.queryByText(/Explicit field quotations checked/),
    ).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "FIELD-GUIDANCE" }));
    expect(
      screen.getByText(
        "A source amount alone does not establish the payment outcome.",
      ),
    ).toBeVisible();
    expect(posts(fetcher)).toHaveLength(0);
  });

  it.each([
    "null receipt",
    "wrong pipeline",
    "bad prompt hash",
    "missing check",
    "wrong claim index",
    "invalid claim type",
    "missing supports",
    "duplicate field",
    "unknown field",
    "changed value",
    "unquoted value",
    "uncited row",
    "duplicate source keys",
    "numeric source value",
    "interpretation without guidance",
    "missing unknowns",
  ])("rejects optional RAG metadata with %s", async (kind) => {
    mockApi({
      jobs: [job()],
      detailTransform: (value) => {
        const saved = ragDetail(value);
        const rag = saved.answer.rag;
        const support = rag.claimSupports[0];
        if (kind === "null receipt")
          return { ...saved, answer: { ...saved.answer, rag: null } };
        if (kind === "wrong pipeline") rag.pipeline = "unrecognized-pipeline";
        if (kind === "bad prompt hash") rag.promptHash = "not-a-fingerprint";
        if (kind === "missing check") rag.checks.pop();
        if (kind === "wrong claim index") support.claimIndex = 1;
        if (kind === "invalid claim type") support.claimType = "verified";
        if (kind === "missing supports") support.fields = [];
        if (kind === "duplicate field")
          support.fields.push({ ...support.fields[0] });
        if (kind === "unknown field")
          support.fields[0].field = "UNKNOWN_NATIVE_FIELD";
        if (kind === "changed value")
          support.fields[0].value = "12345678901234567890";
        if (kind === "unquoted value") {
          saved.answer.claims[0].text =
            "The supplied payment reference is absent and its source amount is 12345678901234567890.0009.";
          saved.answer.answer = saved.answer.claims
            .map((claim) => claim.text)
            .join("\n\n");
        }
        if (kind === "uncited row") support.fields[0].documentId = "HOST-ROW-1";
        if (kind === "duplicate source keys")
          saved.documents[0].content =
            '{"REFTXNNUMBER":"wrong","REFTXNNUMBER":"00012345678901234567890","NUMAMOUNT_4038":"12345678901234567890.0009"}';
        if (kind === "numeric source value")
          saved.documents[0].content =
            '{"REFTXNNUMBER":12345678901234567890,"NUMAMOUNT_4038":"12345678901234567890.0009"}';
        if (kind === "interpretation without guidance")
          support.claimType = "interpretation";
        if (kind === "missing unknowns") saved.answer.unknowns = [];
        return saved;
      },
    });
    await openPage();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "checked field metadata does not match",
    );
    expect(screen.queryByText("Checked source fields")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "Model-generated answer" }),
    ).not.toBeInTheDocument();
  });

  it("refreshes newly attached versions without losing the question or silently replacing a selected immutable version", async () => {
    const options: Options = { versions: [] };
    const { fetcher } = mockApi(options);
    const view = await openPage();
    fireEvent.change(screen.getByLabelText("Your case question"), {
      target: { value: "Keep my original draft question." },
    });
    options.versions = [1];
    view.rerender(
      <CaseInvestigation caseId={caseId} canWrite evidenceRevision={1} />,
    );
    await waitFor(() =>
      expect(
        screen.getByLabelText("Investigation evidence version"),
      ).toHaveValue(evidenceId(1)),
    );
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Run investigation" }),
      ).toBeEnabled(),
    );
    options.versions = [2, 1];
    view.rerender(
      <CaseInvestigation caseId={caseId} canWrite evidenceRevision={2} />,
    );
    await screen.findByRole("option", { name: /Version 2.*Latest/ });
    expect(screen.getByLabelText("Investigation evidence version")).toHaveValue(
      evidenceId(1),
    );
    expect(
      screen.getByText(/You selected an earlier evidence version/),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Your case question")).toHaveValue(
      "Keep my original draft question.",
    );
    expect(posts(fetcher)).toHaveLength(0);
  });

  it.each(["citation", "answer hash", "question", "model call"])(
    "rejects a completed answer with invalid %s",
    async (kind) => {
      mockApi({
        jobs: [job()],
        detailTransform: (value) => ({
          ...value,
          answer: {
            ...value.answer!,
            ...(kind === "citation"
              ? {
                  citations: [
                    {
                      ...document(),
                      content: "Unrelated text under a reused source ID.",
                    },
                  ],
                }
              : kind === "answer hash"
                ? { evidenceHash: "wrong-hash" }
                : kind === "question"
                  ? { question: "A different question." }
                  : { model: { ...value.answer!.model, actualCalls: 0 } }),
          },
        }),
      });
      await openPage();
      await screen.findByRole("alert");
      expect(
        screen.queryByText(
          "Generated fixture interpretation for evidence version 1.",
        ),
      ).not.toBeInTheDocument();
    },
  );

  it("pauses polling visibly after a transport error and resumes without another POST", async () => {
    const { fetcher } = mockApi({
      jobs: [job("ORIGINAL-STATUS-JOB", "RUNNING")],
      pollFailure: true,
    });
    await openPage();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "original-poll-request",
    );
    fireEvent.click(screen.getByRole("button", { name: "Refresh job status" }));
    await screen.findByText(
      "Generated fixture interpretation for evidence version 1.",
    );
    expect(posts(fetcher)).toHaveLength(0);
  });

  it("aborts outstanding job reads on case change and does not display their late result", async () => {
    const { fetcher, finishDetail } = mockApi({
      jobs: [job("ORIGINAL-HELD-JOB", "RUNNING")],
      holdDetail: true,
    });
    const view = await openPage();
    const read = fetcher.mock.calls.find(([url]) =>
      url.endsWith("/investigations/ORIGINAL-HELD-JOB"),
    )!;
    view.rerender(
      <CaseInvestigation caseId="CASE-INVESTIGATION-FIXTURE-B" canWrite />,
    );
    await screen.findByLabelText("Your case question");
    expect(read[1].signal!.aborted).toBe(true);
    await act(async () => {
      finishDetail();
    });
    expect(
      screen.queryByText(
        "Generated fixture interpretation for evidence version 1.",
      ),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("region", { name: "Selected investigation" }),
    ).not.toBeInTheDocument();
  });

  it("uses the exact linked Q&A version and saves a question through the existing case endpoint", async () => {
    const { fetcher } = mockApi({ versions: [2, 1] });
    const changed = vi.fn();
    render(
      <CaseInvestigation
        caseId={caseId}
        canWrite
        presentation="qa"
        requestedEvidenceId={evidenceId(1)}
        onEvidenceChange={changed}
      />,
    );
    expect(
      await screen.findByRole("heading", {
        name: "Questions and saved answers",
      }),
    ).toBeVisible();
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Run investigation" }),
      ).toBeEnabled(),
    );
    expect(screen.getByLabelText("Investigation evidence version")).toHaveValue(
      evidenceId(1),
    );
    expect(
      screen.getByRole("tab", { name: "Investigations 0" }),
    ).toHaveAttribute("aria-selected", "true");
    expect(
      fetcher.mock.calls.some(([url]) =>
        url.includes(`${evidenceId(2)}/context`),
      ),
    ).toBe(false);
    expect(posts(fetcher)).toHaveLength(0);
    ask();
    await screen.findByText(
      "Generated fixture interpretation for evidence version 1.",
    );
    const body = JSON.parse(posts(fetcher)[0][1].body as string);
    expect(body.evidenceId).toBe(evidenceId(1));
    expect(body.evidenceHash).toBe("original-hash-1");
    expect(posts(fetcher)[0][0]).toBe(
      `/api/payment-cases/${caseId}/investigations`,
    );
  });

  it("restores a versionless Q&A link to latest on Back and keeps draft text", async () => {
    const { fetcher } = mockApi({ versions: [2, 1] });
    const changed = vi.fn();
    const view = render(
      <CaseInvestigation
        caseId={caseId}
        canWrite
        presentation="qa"
        onEvidenceChange={changed}
      />,
    );
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Run investigation" }),
      ).toBeEnabled(),
    );
    fireEvent.change(screen.getByLabelText("Your case question"), {
      target: { value: "Retain my draft while choosing a version." },
    });
    fireEvent.change(screen.getByLabelText("Investigation evidence version"), {
      target: { value: evidenceId(1) },
    });
    expect(changed).toHaveBeenCalledWith(evidenceId(1));
    view.rerender(
      <CaseInvestigation
        caseId={caseId}
        canWrite
        presentation="qa"
        onEvidenceChange={changed}
        requestedEvidenceId={evidenceId(1)}
      />,
    );
    await waitFor(() =>
      expect(
        screen.getByLabelText("Investigation evidence version"),
      ).toHaveValue(evidenceId(1)),
    );
    view.rerender(
      <CaseInvestigation
        caseId={caseId}
        canWrite
        presentation="qa"
        onEvidenceChange={changed}
      />,
    );
    expect(screen.getByLabelText("Investigation evidence version")).toHaveValue(
      evidenceId(2),
    );
    expect(screen.getByLabelText("Your case question")).toHaveValue(
      "Retain my draft while choosing a version.",
    );
    expect(posts(fetcher)).toHaveLength(0);
  });

  it("keeps the initial Q&A version and draft when newer evidence arrives during a refresh", async () => {
    const options: Options = { versions: [1] };
    const { fetcher } = mockApi(options);
    const changed = vi.fn();
    const view = render(
      <CaseInvestigation
        caseId={caseId}
        canWrite
        presentation="qa"
        onEvidenceChange={changed}
      />,
    );
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Run investigation" }),
      ).toBeEnabled(),
    );
    fireEvent.change(screen.getByLabelText("Your case question"), {
      target: { value: "Question about the original version." },
    });
    options.versions = [2, 1];
    view.rerender(
      <CaseInvestigation
        caseId={caseId}
        canWrite
        presentation="qa"
        onEvidenceChange={changed}
        evidenceRevision={1}
      />,
    );
    await screen.findByRole("option", { name: /Version 2.*Latest/ });
    expect(screen.getByLabelText("Investigation evidence version")).toHaveValue(
      evidenceId(1),
    );
    expect(screen.getByLabelText("Your case question")).toHaveValue(
      "Question about the original version.",
    );
    expect(changed).not.toHaveBeenCalled();
    expect(posts(fetcher)).toHaveLength(0);
  });

  it("rejects an unavailable linked version without falling back or reusing old context", async () => {
    const { fetcher } = mockApi({ versions: [2, 1] });
    const view = render(
      <CaseInvestigation
        caseId={caseId}
        canWrite
        presentation="qa"
        requestedEvidenceId={evidenceId(1)}
      />,
    );
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Run investigation" }),
      ).toBeEnabled(),
    );
    view.rerender(
      <CaseInvestigation
        caseId={caseId}
        canWrite
        presentation="qa"
        requestedEvidenceId="UNRELATED-EVIDENCE"
      />,
    );
    expect(
      screen.getByRole("button", { name: "Run investigation" }),
    ).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "requested evidence version is unavailable for this case",
    );
    expect(
      fetcher.mock.calls.some(([url]) =>
        url.includes("UNRELATED-EVIDENCE/context"),
      ),
    ).toBe(false);
    expect(
      fetcher.mock.calls.some(([url]) =>
        url.includes(`${evidenceId(2)}/context`),
      ),
    ).toBe(false);
    expect(posts(fetcher)).toHaveLength(0);
  });

  it("guides Q&A cases without evidence to collection and makes no model request", async () => {
    const { fetcher } = mockApi({ versions: [] });
    render(<CaseInvestigation caseId={caseId} canWrite presentation="qa" />);
    expect(
      await screen.findByText("Attach evidence before asking a question"),
    ).toBeVisible();
    expect(
      screen.getByRole("link", { name: "Collect case evidence" }),
    ).toHaveAttribute("href", `/payment-cases/${caseId}`);
    expect(
      screen.getByRole("button", { name: "Run investigation" }),
    ).toBeDisabled();
    expect(posts(fetcher)).toHaveLength(0);
  });

  it("guards repeated clicks while a submission is unresolved and aborts on unmount", async () => {
    const { fetcher, finishPost } = mockApi({ holdPost: true });
    const view = await openPage();
    ask();
    fireEvent.submit(
      screen.getByLabelText("Your case question").closest("form")!,
    );
    expect(posts(fetcher)).toHaveLength(1);
    expect(
      screen.getByRole("button", { name: "Saving request…" }),
    ).toBeDisabled();
    const signal = posts(fetcher)[0][1].signal!;
    view.unmount();
    expect(signal.aborted).toBe(true);
    await act(async () => {
      finishPost();
    });
    expect(
      fetcher.mock.calls.some(([url]) =>
        /\/investigations\/ORIGINAL-JOB-/.test(url),
      ),
    ).toBe(false);
  });
});
