import { casePageFixture } from "./testCasePage";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "./App";
import { setCsrfToken } from "./api";
import { navigateLink, navigateTo } from "./routing";

vi.mock("./EvidenceQuestions", () => ({
  EvidenceQuestions: ({
    caseId,
    evidenceId,
  }: {
    caseId?: string;
    evidenceId?: string;
  }) => (
    <section>
      <h1>Evidence Q&amp;A</h1>
      <p>Selected case: {caseId ?? "None"}</p>
      <p>Selected evidence: {evidenceId ?? "Latest"}</p>
    </section>
  ),
}));

const session = {
  user: {
    id: "analyst",
    name: "Demo Analyst",
    role: "ANALYST",
    tenantId: "northstar",
  },
  csrfToken: "test-csrf",
};
function mockApi(authenticated = true, logoutStatus = 204) {
  const reply = (body: unknown, status = 200) =>
    Promise.resolve(
      new Response(status === 204 ? null : JSON.stringify(body), { status }),
    );
  const fetcher = vi.fn((url: string) => {
    if (url === "/api/auth/me")
      return reply(authenticated ? session : {}, authenticated ? 200 : 401);
    if (url === "/api/auth/login") {
      authenticated = true;
      return reply(session);
    }
    if (url === "/api/auth/logout")
      return reply({ message: "Sign out failed." }, logoutStatus);
    if (url === "/api/dashboard") return reply({});
    if (url === "/api/health")
      return reply({ status: "UP", service: "Fixture API", mode: "fixture" });
    if (url === "/api/system")
      return reply({ supportedModes: [], limitations: [] });
    if (url.startsWith("/api/evidences?"))
      return reply({
        generatedAt: "2026-09-14T00:00:00Z",
        page: 1,
        pageSize: 10,
        total: 0,
        totalPages: 1,
        summary: {
          caseCount: 0,
          casesWithRows: 0,
          casesWithoutRows: 0,
          evidenceVersions: 0,
        },
        scopes: [],
        items: [],
      });
    if (url === "/api/uat/snapshots")
      return reply({ enabled: true, items: [] });
    if (url === "/api/payment-cases/dashboard")
      return reply({
        openCases: 0,
        highPriorityCases: 0,
        awaitingReview: 0,
        resolvedCases: 0,
      });
    if (url.startsWith("/api/payment-cases?"))
      return reply(casePageFixture(url, []));
    if (url === "/api/payment-discovery/config")
      return reply({
        mode: "DISABLED",
        today: "2026-09-14",
        timezone: "Asia/Kolkata",
        maxRecords: 200,
        scopes: [],
        directLookupScope: "Previously loaded local records",
      });
    if (url === "/api/case-knowledge")
      return reply({
        schemaVersion: "case-knowledge-library-v1",
        tenantId: "northstar",
        evidenceSchema: "fcr-case-evidence-v1",
        version: "a".repeat(64),
        embedding: {
          enabled: false,
          status: "DISABLED",
          model: null,
          digest: null,
          dimensions: null,
          indexedAt: null,
          indexedDocuments: 0,
          totalDocuments: 0,
        },
        items: [],
        warnings: [],
      });
    if (url.startsWith("/api/cases?")) return reply({ items: [], total: 0 });
    if (url.endsWith("/investigations") || url.endsWith("/audit"))
      return reply({ items: [] });
    if (url === "/api/cases/CASE-1")
      return reply({ message: "Case fixture unavailable." }, 404);
    if (url === "/api/payment-cases/PC-ROUTING-FIXTURE")
      return reply({ message: "Saved case fixture unavailable." }, 404);
    throw new Error(`Unexpected request: ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
  return fetcher;
}
beforeEach(() => {
  window.history.replaceState(null, "", "/");
  vi.spyOn(window, "scrollTo").mockImplementation(() => undefined);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  setCsrfToken(null);
  window.history.replaceState(null, "", "/");
});
const currentLocation = () =>
  `${window.location.pathname}${window.location.search}${window.location.hash}`;
async function expectLocation(destination: string) {
  await waitFor(() => expect(currentLocation()).toBe(destination));
}
const pages = [
  {
    destination: "/cases/CASE-1",
    text: "Case fixture unavailable.",
    endpoint: "/api/cases/CASE-1",
    isHeading: false,
  },
  {
    destination: "/payment-cases/PC-ROUTING-FIXTURE",
    text: "Saved case fixture unavailable.",
    endpoint: "/api/payment-cases/PC-ROUTING-FIXTURE",
    isHeading: false,
  },
  {
    destination: "/knowledge",
    text: "Knowledge library",
    endpoint: "/api/case-knowledge",
    isHeading: true,
  },
  {
    destination: "/system",
    text: "System & health",
    endpoint: "/api/system",
    isHeading: true,
  },
  {
    destination: "/evidences",
    text: "Evidence library",
    endpoint: "/api/evidences?",
    isHeading: true,
  },
  {
    destination: "/evidences/exports",
    text: "Evidence Q&A",
    endpoint: "/api/uat/snapshots",
    isHeading: true,
  },
];
async function expectPage(page: (typeof pages)[number]) {
  if (page.isHeading) await screen.findByRole("heading", { name: page.text });
  else await screen.findByText(page.text);
}
describe("clean workspace paths and sign-in routing", () => {
  it("ignores retired demo queue history on initial load, Back and Forward", async () => {
    const fetcher = mockApi();
    window.history.replaceState({ caseQueueMode: "demo" }, "", "/cases");
    render(<App />);
    await screen.findByRole("heading", { name: "Find payment" });
    expect(
      screen.queryByRole("group", { name: "Case queue dataset" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Demo cases" }),
    ).not.toBeInTheDocument();
    await userEvent.click(
      screen.getByRole("link", { name: "Evidence library" }),
    );
    await screen.findByRole("heading", { name: "Evidence library" });
    await userEvent.click(
      screen.getAllByRole("link", { name: "Find payment" })[0],
    );
    await expectLocation("/cases");
    expect(
      screen.getByRole("heading", { name: "Find payment" }),
    ).toBeInTheDocument();
    await act(async () => {
      window.history.back();
    });
    await screen.findByRole("heading", { name: "Evidence library" });
    await act(async () => {
      window.history.back();
    });
    await screen.findByRole("heading", { name: "Find payment" });
    expect(screen.getByText("PRIVATE CASES")).toBeInTheDocument();
    await act(async () => {
      window.history.forward();
    });
    await screen.findByRole("heading", { name: "Evidence library" });
    await act(async () => {
      window.history.forward();
    });
    await screen.findByRole("heading", { name: "Find payment" });
    expect(
      fetcher.mock.calls.some(
        ([url]) => url === "/api/dashboard" || url.startsWith("/api/cases?"),
      ),
    ).toBe(false);
  }, 10_000);
  it("preserves a direct legacy case but returns to the payment queue from its back link", async () => {
    const fetcher = mockApi();
    window.history.replaceState(null, "", "/cases/CASE-1");
    render(<App />);
    await screen.findByText("Case fixture unavailable.");
    await userEvent.click(screen.getByRole("link", { name: "Back to queue" }));
    await screen.findByRole("heading", { name: "Find payment" });
    await expectLocation("/cases");
    expect(
      screen.queryByRole("button", { name: "Demo cases" }),
    ).not.toBeInTheDocument();
    expect(
      fetcher.mock.calls.some(([url]) => url.startsWith("/api/cases?")),
    ).toBe(false);
  });
  it("keeps fresh sign-in at root and opens the workspace at /cases", async () => {
    mockApi(false);
    render(<App />);
    await screen.findByRole("heading", { name: "Sign in to investigate" });
    await expectLocation("/");
    await userEvent.click(
      screen.getByRole("button", { name: "Open workspace" }),
    );
    await screen.findByRole("heading", { name: "Case queue" });
    await expectLocation("/cases");
    expect(screen.getByRole("link", { name: "Case queue" })).toHaveAttribute(
      "href",
      "/cases",
    );
  });
  it.each(["/", "/cases", "/cases/"])(
    "opens and reloads the authenticated queue from %s at canonical /cases",
    async (path) => {
      mockApi();
      window.history.replaceState(null, "", path);
      const view = render(<App />);
      await screen.findByRole("heading", { name: "Case queue" });
      await expectLocation("/cases");
      view.unmount();
      render(<App />);
      await screen.findByRole("heading", { name: "Case queue" });
      await expectLocation("/cases");
    },
  );
  it.each(pages)(
    "opens and reloads $destination without a hash",
    async (page) => {
      const fetcher = mockApi();
      window.history.replaceState(null, "", page.destination);
      const view = render(<App />);
      await expectPage(page);
      await expectLocation(page.destination);
      await waitFor(() =>
        expect(
          fetcher.mock.calls.some(([url]) => url.startsWith(page.endpoint)),
        ).toBe(true),
      );
      view.unmount();
      render(<App />);
      await expectPage(page);
      await expectLocation(page.destination);
      await waitFor(() =>
        expect(
          fetcher.mock.calls.filter(([url]) => url.startsWith(page.endpoint))
            .length,
        ).toBeGreaterThanOrEqual(2),
      );
    },
  );
  it("shows root sign-in for an unauthenticated /cases visit and restores /cases after login", async () => {
    mockApi(false);
    window.history.replaceState(null, "", "/cases");
    render(<App />);
    await screen.findByRole("heading", { name: "Sign in to investigate" });
    await expectLocation("/");
    await userEvent.click(
      screen.getByRole("button", { name: "Open workspace" }),
    );
    await screen.findByRole("heading", { name: "Case queue" });
    await expectLocation("/cases");
  });
  it.each(["#/", "#/cases", "#/cases/", "#/payment-cases", "#/payment-cases/"])(
    "normalizes legacy queue %s to /cases without losing the queue",
    async (hash) => {
      mockApi();
      window.history.replaceState(null, "", `/${hash}`);
      render(<App />);
      await screen.findByRole("heading", { name: "Case queue" });
      await expectLocation("/cases");
    },
  );
  it.each(pages)(
    "preserves the explicit $destination destination through root sign-in",
    async (page) => {
      const { destination, endpoint } = page;
      const fetcher = mockApi(false);
      window.history.replaceState(null, "", destination);
      render(<App />);
      await screen.findByRole("heading", { name: "Sign in to investigate" });
      await expectLocation("/");
      await userEvent.click(
        screen.getByRole("button", { name: "Open workspace" }),
      );
      await expectPage(page);
      await expectLocation(destination);
      await waitFor(() =>
        expect(
          fetcher.mock.calls.some(([url]) => url.startsWith(endpoint)),
        ).toBe(true),
      );
      await userEvent.click(screen.getByRole("link", { name: "Case queue" }));
      await screen.findByRole("heading", { name: "Case queue" });
      await expectLocation("/cases");
    },
  );
  it.each([
    { legacy: "/#/cases/CASE-1", page: pages[0] },
    { legacy: "/#/payment-cases/PC-ROUTING-FIXTURE", page: pages[1] },
    { legacy: "/#/knowledge", page: pages[2] },
    { legacy: "/cases#/knowledge", page: pages[2] },
    { legacy: "/#/system", page: pages[3] },
    { legacy: "/#/uat-evidence", page: pages[4] },
    { legacy: "/#/evidences", page: pages[4] },
    { legacy: "/uat-evidence", page: pages[4] },
    { legacy: "/uat-evidence/", page: pages[4] },
  ])(
    "migrates legacy $legacy to its clean destination",
    async ({ legacy, page }) => {
      mockApi();
      window.history.replaceState(null, "", legacy);
      render(<App />);
      await expectPage(page);
      await expectLocation(page.destination);
    },
  );
  it("retains the migrated legacy evidence destination through sign-in", async () => {
    mockApi(false);
    window.history.replaceState(null, "", "/#/uat-evidence");
    render(<App />);
    await screen.findByRole("heading", { name: "Sign in to investigate" });
    await expectLocation("/");
    await userEvent.click(
      screen.getByRole("button", { name: "Open workspace" }),
    );
    await screen.findByRole("heading", { name: "Evidence library" });
    await expectLocation("/evidences");
  });
  it("migrates a legacy hash change while the app is already open", async () => {
    mockApi();
    window.history.replaceState(null, "", "/cases");
    render(<App />);
    await screen.findByRole("heading", { name: "Case queue" });
    await act(async () => {
      window.location.hash = "#/uat-evidence";
    });
    await screen.findByRole("heading", { name: "Evidence library" });
    await expectLocation("/evidences");
  });
  it.each([
    { label: "Knowledge library", destination: "/knowledge" },
    { label: "System & health", destination: "/system" },
    { label: "Evidence library", destination: "/evidences" },
  ])(
    "navigates the clean $label link from /cases without reloading the app",
    async ({ label, destination }) => {
      const fetcher = mockApi();
      window.history.replaceState(null, "", "/cases");
      render(<App />);
      await screen.findByRole("heading", { name: "Case queue" });
      const link = screen.getByRole("link", { name: label });
      expect(link).toHaveAttribute("href", destination);
      await userEvent.click(link);
      await screen.findByRole("heading", { name: label });
      await expectLocation(destination);
      expect(
        fetcher.mock.calls.filter(([url]) => url === "/api/auth/me"),
      ).toHaveLength(1);
    },
  );
  it("supports /cases navigation and browser Back/Forward without reloading the app", async () => {
    mockApi();
    window.history.replaceState(null, "", "/knowledge");
    render(<App />);
    await screen.findByRole("heading", { name: "Knowledge library" });
    await userEvent.click(screen.getByRole("link", { name: "Case queue" }));
    await screen.findByRole("heading", { name: "Case queue" });
    await expectLocation("/cases");
    await act(async () => {
      window.history.back();
    });
    await screen.findByRole("heading", { name: "Knowledge library" });
    await expectLocation("/knowledge");
    await act(async () => {
      window.history.forward();
    });
    await screen.findByRole("heading", { name: "Case queue" });
    await expectLocation("/cases");
    await userEvent.click(
      screen.getByRole("link", { name: "Evidence library" }),
    );
    await screen.findByRole("heading", { name: "Evidence library" });
    await expectLocation("/evidences");
    await act(async () => {
      window.history.back();
    });
    await screen.findByRole("heading", { name: "Case queue" });
    await expectLocation("/cases");
    await act(async () => {
      window.history.forward();
    });
    await screen.findByRole("heading", { name: "Evidence library" });
    await expectLocation("/evidences");
  });
  it("keeps the hidden export demo directly addressable and preserves browser history", async () => {
    const fetcher = mockApi();
    window.history.replaceState(null, "", "/evidences");
    render(<App />);
    await screen.findByRole("heading", { name: "Evidence library" });
    expect(
      fetcher.mock.calls.some(([url]) => url.startsWith("/api/uat/")),
    ).toBe(false);
    expect(
      screen.queryByRole("link", { name: "Export demo" }),
    ).not.toBeInTheDocument();
    await act(async () => {
      navigateTo("/evidences/exports");
    });
    await screen.findByRole("heading", { name: "Evidence Q&A" });
    await expectLocation("/evidences/exports");
    expect(
      screen.queryByRole("link", { name: "Export demo" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("exports", { selector: ".breadcrumb span" }),
    ).not.toBeInTheDocument();
    await act(async () => {
      window.history.back();
    });
    await screen.findByRole("heading", { name: "Evidence library" });
    await expectLocation("/evidences");
    await act(async () => {
      window.history.forward();
    });
    await screen.findByRole("heading", { name: "Evidence Q&A" });
    await expectLocation("/evidences/exports");
    expect(
      fetcher.mock.calls.filter(([url]) => url === "/api/auth/me"),
    ).toHaveLength(1);
  });
  it.each([
    "/evidences/questions",
    "/evidences/questions/CASE%2FORIGINAL",
    "/evidences/questions/CASE%2FORIGINAL/EVD%20VERSION%202",
  ])(
    "restores case Evidence Q&A destination %s after login and session expiry",
    async (destination) => {
      const fetcher = mockApi(false);
      window.history.replaceState(null, "", destination);
      render(<App />);
      await screen.findByRole("heading", { name: "Sign in to investigate" });
      await expectLocation("/");
      await userEvent.click(
        screen.getByRole("button", { name: "Open workspace" }),
      );
      await screen.findByRole("heading", { name: "Evidence Q&A" });
      await expectLocation(destination);
      expect(
        screen.getByText(
          `Selected case: ${destination.includes("CASE") ? "CASE/ORIGINAL" : "None"}`,
        ),
      ).toBeInTheDocument();
      expect(
        screen.getByText(
          `Selected evidence: ${destination.includes("EVD") ? "EVD VERSION 2" : "Latest"}`,
        ),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("link", { name: "Evidence Q&A" }),
      ).toHaveAttribute("aria-current", "page");
      expect(
        fetcher.mock.calls.some(([url]) => url.startsWith("/api/uat/")),
      ).toBe(false);
      await act(async () => window.dispatchEvent(new Event("session-expired")));
      await screen.findByRole("heading", { name: "Sign in to investigate" });
      await expectLocation("/");
      await userEvent.click(
        screen.getByRole("button", { name: "Open workspace" }),
      );
      await screen.findByRole("heading", { name: "Evidence Q&A" });
      await expectLocation(destination);
    },
  );
  it("restores the selected case and evidence version with Back and Forward across evidence tabs", async () => {
    mockApi();
    const destination =
      "/evidences/questions/CASE%2FORIGINAL/EVD%20VERSION%202";
    window.history.replaceState(null, "", destination);
    render(<App />);
    await screen.findByText("Selected evidence: EVD VERSION 2");
    await userEvent.click(screen.getByRole("link", { name: "Case evidence" }));
    await screen.findByRole("heading", { name: "Evidence library" });
    await expectLocation("/evidences");
    await act(async () => window.history.back());
    await screen.findByText("Selected case: CASE/ORIGINAL");
    await screen.findByText("Selected evidence: EVD VERSION 2");
    await expectLocation(destination);
    await act(async () => window.history.forward());
    await screen.findByRole("heading", { name: "Evidence library" });
    await expectLocation("/evidences");
    await userEvent.click(screen.getByRole("link", { name: "Evidence Q&A" }));
    await screen.findByText("Selected case: None");
    await expectLocation("/evidences/questions");
  });
  it("returns to root after successful logout and does not reopen the old page on login", async () => {
    mockApi();
    window.history.replaceState(null, "", "/knowledge");
    render(<App />);
    await screen.findByRole("heading", { name: "Knowledge library" });
    await userEvent.click(screen.getByRole("button", { name: "Sign out" }));
    await screen.findByRole("heading", { name: "Sign in to investigate" });
    await expectLocation("/");
    await userEvent.click(
      screen.getByRole("button", { name: "Open workspace" }),
    );
    await screen.findByRole("heading", { name: "Case queue" });
    await expectLocation("/cases");
  });
  it("preserves the current page when logout fails", async () => {
    mockApi(true, 500);
    window.history.replaceState(null, "", "/knowledge");
    render(<App />);
    await screen.findByRole("heading", { name: "Knowledge library" });
    await userEvent.click(screen.getByRole("button", { name: "Sign out" }));
    await screen.findByText("Sign out failed.");
    await expectLocation("/knowledge");
    expect(
      screen.queryByRole("heading", { name: "Sign in to investigate" }),
    ).not.toBeInTheDocument();
  });
  it.each([
    { destination: "/cases", heading: "Case queue" },
    { destination: "/knowledge", heading: "Knowledge library" },
    { destination: "/evidences", heading: "Evidence library" },
    { destination: "/evidences/exports", heading: "Evidence Q&A" },
  ])(
    "shows root sign-in after session expiry and restores $destination",
    async ({ destination, heading }) => {
      mockApi();
      window.history.replaceState(null, "", destination);
      render(<App />);
      await screen.findByRole("heading", { name: heading });
      await act(async () => {
        window.dispatchEvent(new Event("session-expired"));
      });
      await screen.findByRole("heading", { name: "Sign in to investigate" });
      await expectLocation("/");
      await userEvent.click(
        screen.getByRole("button", { name: "Open workspace" }),
      );
      await screen.findByRole("heading", { name: heading });
      await expectLocation(destination);
    },
  );
  it.each([
    { name: "Control click", options: { ctrlKey: true }, target: "" },
    { name: "Command click", options: { metaKey: true }, target: "" },
    { name: "Shift click", options: { shiftKey: true }, target: "" },
    { name: "Alt click", options: { altKey: true }, target: "" },
    { name: "Middle click", options: { button: 1 }, target: "" },
    { name: "New-tab target", options: {}, target: "_blank" },
  ])("preserves browser navigation for $name", ({ options, target }) => {
    window.history.replaceState(null, "", "/cases");
    const push = vi.spyOn(window.history, "pushState");
    render(
      <a href="/knowledge" target={target || undefined} onClick={navigateLink}>
        Open fixture knowledge
      </a>,
    );
    let preventedByRouter: boolean | undefined;
    // Observe the router, then suppress jsdom's unsupported full-page navigation.
    document.addEventListener(
      "click",
      (event) => {
        preventedByRouter = event.defaultPrevented;
        event.preventDefault();
      },
      { once: true },
    );
    fireEvent.click(
      screen.getByRole("link", { name: "Open fixture knowledge" }),
      options,
    );
    expect(preventedByRouter).toBe(false);
    expect(push).not.toHaveBeenCalled();
    expect(currentLocation()).toBe("/cases");
  });
});
