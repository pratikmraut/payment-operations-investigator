import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CaseKnowledgePage } from "./CaseKnowledgePage";
import type { User } from "./types";

const user: User = {
  id: "analyst",
  name: "Analyst",
  role: "ANALYST",
  tenantId: "northstar",
};
const digest = "a".repeat(64);
const buildLibrary = (count = 21) => ({
  schemaVersion: "case-knowledge-library-v1",
  tenantId: "northstar",
  evidenceSchema: "fcr-case-evidence-v1",
  version: digest,
  embedding: {
    enabled: true,
    status: "CURRENT",
    model: "qwen3-embedding:0.6b",
    digest,
    dimensions: 1024,
    indexedAt: "2026-09-15T10:00:00Z",
    indexedDocuments: count,
    totalDocuments: count,
  },
  items: Array.from({ length: count }, (_, position) => ({
    id: `GUIDE-${position + 1}`,
    kind: "knowledge",
    title: `Guidance ${position + 1}`,
    content:
      position === 20
        ? "DISPATCH_IN_PROGRESS is specific to MSGSTATUS 11; this does not confirm beneficiary credit."
        : `Source limitation number ${position + 1}.`,
    source: {
      file: position === 20 ? "Enumeration.xlsx" : "reviewed-guidance",
      sheet: "PMT",
      range: `H${position + 1}:I${position + 1}`,
      locator: "User supplied reference; deployment unverified.",
    },
    version: (position + 1).toString(16).padStart(64, "0"),
    category:
      position === 20 ? "STATUS" : position === 0 ? "SCOPE" : "GUIDANCE",
    selection:
      position === 20
        ? "EXACT_OR_SEMANTIC"
        : position === 0
          ? "ALWAYS"
          : "SEMANTIC",
    embeddingStatus: "CURRENT",
  })),
  warnings: ["The reference does not establish the installed bank release."],
});
const reply = (body: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(body), { status }));

beforeEach(() => {
  window.history.replaceState(null, "", "/knowledge");
  vi.spyOn(window, "scrollTo").mockImplementation(() => undefined);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("case knowledge library", () => {
  it("accepts absent source coordinates represented as null without inventing a locator", async () => {
    const value = buildLibrary(1);
    vi.stubGlobal(
      "fetch",
      vi.fn(() =>
        reply({
          ...value,
          items: value.items.map((item) => ({
            ...item,
            source: {
              file: item.source.file,
              sheet: null,
              range: null,
              locator: null,
            },
          })),
        }),
      ),
    );
    render(<CaseKnowledgePage user={user} />);
    await screen.findByRole("article");
    await userEvent.click(screen.getByText("Read guidance and source"));
    expect(screen.getByText("Source file")).toBeVisible();
    expect(screen.queryByText("Sheet")).not.toBeInTheDocument();
    expect(screen.queryByText("Cells")).not.toBeInTheDocument();
    expect(screen.queryByText("Source details")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
  it("lists current case guidance ten at a time, with read-only flow navigation", async () => {
    const fetcher = vi.fn((_url: string, _options?: RequestInit) =>
      reply(buildLibrary()),
    );
    vi.stubGlobal("fetch", fetcher);
    render(<CaseKnowledgePage user={user} />);
    await screen.findByText("Showing 1–10 of 21 matching documents");
    expect(screen.getAllByRole("article")).toHaveLength(10);
    expect(
      screen.queryByRole("heading", { name: "Guidance 11" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
    const operator = userEvent.setup();
    await operator.click(screen.getByRole("button", { name: "Next" }));
    expect(
      screen.getByText("Showing 11–20 of 21 matching documents"),
    ).toBeInTheDocument();
    await operator.click(screen.getByRole("button", { name: "Next" }));
    expect(screen.getAllByRole("article")).toHaveLength(1);
    expect(screen.getByText("Page 3 of 3 · 10 per page")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
    expect(
      screen.getByRole("link", { name: "Find a payment" }),
    ).toHaveAttribute("href", "/cases");
    expect(
      screen.getByRole("link", { name: "Open Evidence Q&A" }),
    ).toHaveAttribute("href", "/evidences/questions");
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher).toHaveBeenCalledWith(
      "/api/case-knowledge",
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );
    expect(fetcher.mock.calls[0][1]).not.toHaveProperty("method");
    expect(
      screen.getByText(/earlier answers retain their saved sources/),
    ).toBeInTheDocument();
  });

  it("searches all pages using every word across full content and source, and exposes exact citations", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => reply(buildLibrary())),
    );
    render(<CaseKnowledgePage user={user} />);
    await screen.findByText("Showing 1–10 of 21 matching documents");
    const operator = userEvent.setup();
    await operator.click(screen.getByRole("button", { name: "Next" }));
    await operator.type(
      screen.getByRole("textbox", { name: "Search guidance" }),
      "Enumeration MSGSTATUS",
    );
    expect(
      screen.getByText("Showing 1–1 of 1 matching documents"),
    ).toBeInTheDocument();
    expect(screen.getByText("Page 1 of 1 · 10 per page")).toBeInTheDocument();
    const entry = screen.getByRole("article");
    await operator.click(within(entry).getByText("Read guidance and source"));
    expect(
      within(entry).getByText(/DISPATCH_IN_PROGRESS is specific/),
    ).toBeVisible();
    expect(within(entry).getByText("H21:I21")).toBeVisible();
    expect(
      within(entry).getByText(
        "User supplied reference; deployment unverified.",
      ),
    ).toBeVisible();
    expect(within(entry).getByText("15".padStart(64, "0"))).toBeVisible();
    expect(
      within(entry).getByText(
        "Selected by exact field/code or question relevance",
      ),
    ).toBeVisible();
    await operator.type(
      screen.getByRole("textbox", { name: "Search guidance" }),
      " absent",
    );
    expect(screen.queryByRole("article")).not.toBeInTheDocument();
    await operator.click(screen.getByRole("button", { name: "Clear filters" }));
    expect(screen.getAllByRole("article")).toHaveLength(10);
  });

  it("shows stale coverage honestly and combines category and embedding filters", async () => {
    const value = buildLibrary();
    value.embedding.status = "STALE";
    value.embedding.indexedDocuments = 20;
    value.items[20].embeddingStatus = "STALE";
    vi.stubGlobal(
      "fetch",
      vi.fn(() => reply(value)),
    );
    render(<CaseKnowledgePage user={user} />);
    await screen.findByText(/Guidance has changed/);
    expect(
      screen.getByText(/20 \/ 21 documents have current embeddings/),
    ).toBeInTheDocument();
    const operator = userEvent.setup();
    await operator.selectOptions(
      screen.getByRole("combobox", { name: "Knowledge category" }),
      "STATUS",
    );
    await operator.selectOptions(
      screen.getByRole("combobox", { name: "Embedding coverage" }),
      "STALE",
    );
    expect(
      screen.getByRole("heading", { name: "Guidance 21" }),
    ).toBeInTheDocument();
    expect(screen.getAllByRole("article")).toHaveLength(1);
    await operator.selectOptions(
      screen.getByRole("combobox", { name: "Embedding coverage" }),
      "CURRENT",
    );
    expect(screen.queryByRole("article")).not.toBeInTheDocument();
  });

  it.each(["MISSING", "DISABLED"])(
    "allows inspecting current guidance when the index is %s",
    async (state) => {
      const value = buildLibrary(1);
      value.embedding = {
        ...value.embedding,
        status: state,
        enabled: state !== "DISABLED",
        indexedDocuments: 0,
      };
      value.items[0].embeddingStatus = state;
      vi.stubGlobal(
        "fetch",
        vi.fn(() => reply(value)),
      );
      render(<CaseKnowledgePage user={user} />);
      await screen.findByText("Showing 1–1 of 1 matching documents");
      expect(
        screen.getByText(/0 \/ 1 documents have current embeddings/),
      ).toBeInTheDocument();
      expect(screen.getByRole("article")).toBeInTheDocument();
      expect(
        screen.queryByText("Current embeddings", { selector: "span" }),
      ).not.toBeInTheDocument();
    },
  );

  it("does not display stale content after a failed refresh and supports retry", async () => {
    const fetcher = vi
      .fn()
      .mockImplementationOnce(() => reply(buildLibrary(1)))
      .mockImplementationOnce(() =>
        reply(
          { code: "INDEX_UNAVAILABLE", message: "Knowledge is unavailable." },
          503,
        ),
      )
      .mockImplementation(() => reply(buildLibrary(2)));
    vi.stubGlobal("fetch", fetcher);
    render(<CaseKnowledgePage user={user} />);
    await screen.findByRole("article");
    const operator = userEvent.setup();
    await operator.click(screen.getByRole("button", { name: "Refresh" }));
    await screen.findByRole("alert");
    expect(screen.queryByRole("article")).not.toBeInTheDocument();
    expect(screen.queryByText("Current embeddings")).not.toBeInTheDocument();
    await operator.click(screen.getByRole("button", { name: "Try again" }));
    await screen.findByText("Showing 1–2 of 2 matching documents");
    expect(fetcher).toHaveBeenCalledTimes(3);
  });

  it.each(["wrong tenant", "duplicate document", "false current coverage"])(
    "rejects %s instead of showing misleading guidance",
    async (scenario) => {
      const value = buildLibrary(2);
      if (scenario === "wrong tenant") value.tenantId = "silverline";
      if (scenario === "duplicate document")
        value.items[1].id = value.items[0].id;
      if (scenario === "false current coverage")
        value.embedding.indexedDocuments = 1;
      vi.stubGlobal(
        "fetch",
        vi.fn(() => reply(value)),
      );
      render(<CaseKnowledgePage user={user} />);
      expect(await screen.findByRole("alert")).toHaveTextContent(
        "could not be verified",
      );
      expect(screen.queryByRole("article")).not.toBeInTheDocument();
    },
  );

  it("aborts an old workspace load and never displays its late response", async () => {
    let resolveFirst!: (response: Response) => void;
    const fetcher = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            resolveFirst = resolve;
          }),
      )
      .mockImplementation(() =>
        reply({ ...buildLibrary(1), tenantId: "silverline" }),
      );
    vi.stubGlobal("fetch", fetcher);
    const view = render(<CaseKnowledgePage user={user} />);
    view.rerender(
      <CaseKnowledgePage user={{ ...user, tenantId: "silverline" }} />,
    );
    await screen.findByText("1 documents · Workspace silverline");
    await act(async () =>
      resolveFirst(new Response(JSON.stringify(buildLibrary(21)))),
    );
    expect(screen.queryByText(/Workspace northstar/)).not.toBeInTheDocument();
    expect(screen.getAllByRole("article")).toHaveLength(1);
    expect(fetcher.mock.calls[0][1].signal.aborted).toBe(true);
  });

  it("displays document content as text and exposes the full source version without external requests", async () => {
    const value = buildLibrary(1);
    value.items[0].content = '<script>alert("untrusted")</script>';
    const fetcher = vi.fn(() => reply(value));
    vi.stubGlobal("fetch", fetcher);
    render(<CaseKnowledgePage user={user} />);
    await screen.findByRole("article");
    const operator = userEvent.setup();
    await operator.click(screen.getByText("Read guidance and source"));
    expect(
      screen.getByText('<script>alert("untrusted")</script>'),
    ).toBeVisible();
    await operator.click(screen.getByText("Index and source version"));
    expect(screen.getByText("qwen3-embedding:0.6b")).toBeVisible();
    expect(screen.getAllByText(digest)).toHaveLength(2);
    expect(document.querySelector("script")).toBeNull();
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
  });
});
