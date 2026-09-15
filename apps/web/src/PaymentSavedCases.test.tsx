import { afterEach, describe, expect, it, vi } from "vitest";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PaymentCasesPage, type PaymentCase } from "./PaymentDiscovery";
import type { User } from "./types";

const user: User = {
  id: "fixture-queue-analyst",
  name: "Fixture queue analyst",
  role: "ANALYST",
  tenantId: "fixture-queue-tenant",
};

const fixtures: PaymentCase[] = Array.from({ length: 23 }, (_, index) => {
  const suffix = String(index + 1).padStart(2, "0");
  return {
    id: `CASE-FIXTURE-${suffix}`,
    candidateId: `candidate-fixture-${suffix}`,
    reference: `REFERENCE-ORIGINAL-${suffix}`,
    utr: index === 22 ? null : `UTR-FIXTURE-${suffix}`,
    orgBranch: "BRANCH-NOT-SEARCHABLE",
    orgBank: "BANK-NOT-SEARCHABLE",
    hostSubsequences: ["0"],
    initiatedAt: "2026-09-14T09:00:00",
    amount: "987654321.125",
    currency: null,
    sourceKind: "MOCK",
    dataClassification: "SYNTHETIC",
    reason:
      index === 22 ? "Investigate pending handoff." : "Review supplied record.",
    status: "OPEN",
    priority: "MEDIUM",
    createdAt: "2026-09-14T10:00:00Z",
    updatedAt: "2026-09-14T10:00:00Z",
    createdBy: user.id,
    evidenceStatus: "DISCOVERY_ONLY",
  };
});

function mockSavedCases(initialRecords = fixtures) {
  let records = initialRecords;
  const fetcher = vi.fn((url: string, request: RequestInit) => {
    if (request.method && request.method !== "GET")
      throw new Error("Saved queue tests must not issue mutations.");
    let payload: unknown;
    if (
      url === "/api/payment-cases" ||
      url.startsWith("/api/payment-cases?lifecycle=")
    ) {
      const state =
        new URL(url, "http://localhost").searchParams.get("lifecycle") ??
        "ACTIVE";
      const matching = records.filter(
        (item) =>
          state === "ALL" || (item.lifecycleState ?? "ACTIVE") === state,
      );
      payload = { items: matching, total: matching.length };
    } else if (url === "/api/payment-cases/dashboard") {
      payload = {
        openCases: records.length,
        highPriorityCases: 0,
        awaitingReview: 0,
        resolvedCases: 0,
      };
    } else if (url === "/api/payment-discovery/config") {
      payload = {
        mode: "MOCK",
        today: "2026-09-14",
        timezone: "Asia/Kolkata",
        maxRecords: 200,
        scopes: [{ orgBranch: "0100", orgBank: "099", label: "Fixture scope" }],
        directLookupScope: "Exact lookup in the selected fixture scope.",
      };
    } else {
      throw new Error(`Unexpected saved queue test endpoint: ${url}`);
    }
    return Promise.resolve(new Response(JSON.stringify(payload)));
  });
  vi.stubGlobal("fetch", fetcher);
  return {
    fetcher,
    replaceRecords: (next: PaymentCase[]) => {
      records = next;
    },
  };
}

function savedRegion() {
  return within(screen.getByRole("region", { name: "Saved payment cases" }));
}

function shownCaseIds() {
  return savedRegion()
    .queryAllByRole("link", { name: /^Open CASE-FIXTURE-/ })
    .map((link) => link.getAttribute("aria-label")!.slice("Open ".length));
}

function search(value: string) {
  fireEvent.change(
    screen.getByRole("searchbox", { name: "Search saved payment cases" }),
    {
      target: { value },
    },
  );
}

async function ready() {
  render(<PaymentCasesPage user={user} onOpen={vi.fn()} />);
  await savedRegion().findByText("Showing 1–10 of 23 cases");
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("saved payment case search and pagination", () => {
  it("switches active, archived and all cases without losing the search", async () => {
    const archived = { ...fixtures[1], lifecycleState: "ARCHIVED" as const };
    const { fetcher } = mockSavedCases([fixtures[0], archived]);
    render(<PaymentCasesPage user={user} onOpen={vi.fn()} />);
    await savedRegion().findByText(fixtures[0].reference);
    expect(
      savedRegion().queryByText(archived.reference),
    ).not.toBeInTheDocument();
    search("supplied");
    fireEvent.change(screen.getByLabelText("Case visibility"), {
      target: { value: "ARCHIVED" },
    });
    await savedRegion().findByText(archived.reference);
    expect(savedRegion().getByText("Archived")).toBeVisible();
    expect(
      screen.getByRole("searchbox", { name: "Search saved payment cases" }),
    ).toHaveValue("supplied");
    expect(
      fetcher.mock.calls.some(
        ([url]) => url === "/api/payment-cases?lifecycle=ARCHIVED",
      ),
    ).toBe(true);
    fireEvent.change(screen.getByLabelText("Case visibility"), {
      target: { value: "ALL" },
    });
    await savedRegion().findByText(fixtures[0].reference);
    expect(savedRegion().getByText(archived.reference)).toBeVisible();
  });
  it("searches the date-based case number and uses it in case and report links while retaining the payment reference", async () => {
    const numbered = { ...fixtures[0], caseNumber: "2026091600001" };
    mockSavedCases([numbered, fixtures[1]]);
    render(<PaymentCasesPage user={user} onOpen={vi.fn()} />);
    await savedRegion().findByText(numbered.caseNumber);
    search(numbered.caseNumber);
    expect(
      savedRegion().getByRole("link", { name: `Open ${numbered.caseNumber}` }),
    ).toHaveAttribute("href", `/payment-cases/${numbered.caseNumber}`);
    expect(
      savedRegion().getByRole("link", {
        name: `Export PDF for ${numbered.reference}`,
      }),
    ).toHaveAttribute("href", `/payment-cases/${numbered.caseNumber}?report=1`);
    expect(savedRegion().getByText(numbered.reference)).toBeVisible();
    expect(savedRegion().queryByText(numbered.id)).not.toBeInTheDocument();
    expect(
      savedRegion().getByText("Showing 1–1 of 1 matching cases"),
    ).toBeVisible();
  });
  it("presents 23 cases in pages of 10, 10 and 3 with no missing or duplicate records", async () => {
    mockSavedCases();
    await ready();
    const navigation = within(
      screen.getByRole("navigation", { name: "Saved cases pagination" }),
    );
    expect(
      navigation.getByRole("button", { name: "Previous page" }),
    ).toBeDisabled();
    const firstPage = shownCaseIds();
    expect(firstPage).toEqual(fixtures.slice(0, 10).map((item) => item.id));

    await userEvent.click(
      navigation.getByRole("button", { name: "Next page" }),
    );
    expect(savedRegion().getByText("Showing 11–20 of 23 cases")).toBeVisible();
    const secondPage = shownCaseIds();
    expect(secondPage).toEqual(fixtures.slice(10, 20).map((item) => item.id));

    await userEvent.click(
      navigation.getByRole("button", { name: "Go to page 3" }),
    );
    expect(savedRegion().getByText("Showing 21–23 of 23 cases")).toBeVisible();
    const thirdPage = shownCaseIds();
    expect(thirdPage).toEqual(fixtures.slice(20).map((item) => item.id));
    expect(
      navigation.getByRole("button", { name: "Next page" }),
    ).toBeDisabled();
    const allIds = [...firstPage, ...secondPage, ...thirdPage];
    expect(allIds).toHaveLength(23);
    expect(new Set(allIds).size).toBe(23);

    await userEvent.click(
      navigation.getByRole("button", { name: "Previous page" }),
    );
    expect(shownCaseIds()).toEqual(secondPage);
  });

  it.each([
    ["reference-original-18", "CASE-FIXTURE-18"],
    ["case-fixture-16", "CASE-FIXTURE-16"],
    ["utr-fixture-21", "CASE-FIXTURE-21"],
    ["PENDING handoff", "CASE-FIXTURE-23"],
    [
      "  REFERENCE-ORIGINAL-21   utr-fixture-21\t supplied  ",
      "CASE-FIXTURE-21",
    ],
  ])(
    "finds %s across all saved records without requiring its page to be open",
    async (query, expectedId) => {
      const { fetcher } = mockSavedCases();
      await ready();
      const requestsBeforeSearch = fetcher.mock.calls.length;
      search(query);
      expect(shownCaseIds()).toEqual([expectedId]);
      expect(savedRegion().getByText("23 cases")).toBeVisible();
      expect(fetcher.mock.calls).toHaveLength(requestsBeforeSearch);
    },
  );

  it("requires every query term and excludes bank, branch and amount from matching", async () => {
    mockSavedCases();
    await ready();
    for (const query of [
      "REFERENCE-ORIGINAL-21 utr-fixture-22",
      "BANK-NOT-SEARCHABLE",
      "BRANCH-NOT-SEARCHABLE",
      "987654321.125",
      "does-not-exist",
    ]) {
      search(query);
      expect(
        savedRegion().getByRole("heading", { name: "No matching cases" }),
      ).toBeVisible();
      expect(shownCaseIds()).toEqual([]);
      expect(savedRegion().getByText("23 cases")).toBeVisible();
    }
    await userEvent.click(
      savedRegion().getByRole("button", { name: "Clear search" }),
    );
    expect(
      screen.getByRole("searchbox", { name: "Search saved payment cases" }),
    ).toHaveValue("");
    expect(shownCaseIds()).toEqual(
      fixtures.slice(0, 10).map((item) => item.id),
    );
    expect(savedRegion().getByText("Showing 1–10 of 23 cases")).toBeVisible();
  });

  it("resets to the first page when the query changes or is cleared", async () => {
    mockSavedCases();
    await ready();
    await userEvent.click(screen.getByRole("button", { name: "Go to page 3" }));
    search("review");
    expect(
      savedRegion().getByText("Showing 1–10 of 22 matching cases"),
    ).toBeVisible();
    expect(shownCaseIds()).toEqual(
      fixtures.slice(0, 10).map((item) => item.id),
    );

    await userEvent.click(screen.getByRole("button", { name: "Go to page 2" }));
    await userEvent.click(
      savedRegion().getByRole("button", { name: "Clear search" }),
    );
    expect(savedRegion().getByText("Showing 1–10 of 23 cases")).toBeVisible();
    expect(shownCaseIds()).toEqual(
      fixtures.slice(0, 10).map((item) => item.id),
    );
  });

  it("returns to a valid page when refresh reduces the saved list below the current page", async () => {
    const { replaceRecords } = mockSavedCases();
    await ready();
    await userEvent.click(screen.getByRole("button", { name: "Go to page 3" }));
    replaceRecords(fixtures.slice(0, 5));
    await userEvent.click(
      screen.getByRole("button", { name: "Refresh queue" }),
    );
    await waitFor(() => {
      expect(savedRegion().getByText("Showing 1–5 of 5 cases")).toBeVisible();
      expect(shownCaseIds()).toEqual(
        fixtures.slice(0, 5).map((item) => item.id),
      );
    });
    expect(savedRegion().getByText("5 cases")).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "Go to page 3" }),
    ).not.toBeInTheDocument();
  });
});
