import { describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { EvidenceWorkspace } from "./EvidenceWorkspace";
import type { User } from "./types";

vi.mock("./EvidenceLibrary", () => ({
  EvidenceLibraryPage: () => <h1>Saved evidence fixture</h1>,
}));
vi.mock("./UatEvidence", () => ({
  UatEvidencePage: () => <h1>Preserved export fixture</h1>,
}));
vi.mock("./EvidenceQuestions", () => ({
  EvidenceQuestions: ({
    user,
    caseId,
    evidenceId,
  }: {
    user: User;
    caseId?: string;
    evidenceId?: string;
  }) => (
    <section>
      <h1>Case questions fixture</h1>
      <p>
        {user.id} / {caseId ?? "Select case"} / {evidenceId ?? "Select version"}
      </p>
    </section>
  ),
}));

const user: User = {
  id: "original-analyst",
  name: "Original analyst",
  role: "ANALYST",
  tenantId: "original-tenant",
};

describe("evidence workspace tabs", () => {
  it.each([
    [undefined, "Case evidence", "Saved evidence fixture"],
    ["questions", "Evidence Q&A", "Case questions fixture"],
    ["exports", undefined, "Preserved export fixture"],
  ] as const)("renders only the %s workspace", (view, tab, heading) => {
    render(<EvidenceWorkspace user={user} view={view} />);
    const navigation = within(
      screen.getByRole("navigation", { name: "Evidence workspace" }),
    );
    expect(navigation.getAllByRole("link")).toHaveLength(2);
    expect(
      navigation.getByRole("link", { name: "Case evidence" }),
    ).toHaveAttribute("href", "/evidences");
    expect(
      navigation.getByRole("link", { name: "Evidence Q&A" }),
    ).toHaveAttribute("href", "/evidences/questions");
    expect(
      navigation.queryByRole("link", { name: "Export demo" }),
    ).not.toBeInTheDocument();
    if (tab)
      expect(navigation.getByRole("link", { name: tab })).toHaveAttribute(
        "aria-current",
        "page",
      );
    expect(
      navigation
        .getAllByRole("link")
        .filter((link) => link.hasAttribute("aria-current")),
    ).toHaveLength(tab ? 1 : 0);
    expect(screen.getAllByRole("heading")).toHaveLength(1);
    expect(screen.getByRole("heading", { name: heading })).toBeInTheDocument();
  });

  it("forwards the authorized user and explicit route selection to case questions", () => {
    render(
      <EvidenceWorkspace
        user={user}
        view="questions"
        caseId="CASE/ORIGINAL"
        evidenceId="VERSION 2"
      />,
    );
    expect(
      screen.getByText("original-analyst / CASE/ORIGINAL / VERSION 2"),
    ).toBeInTheDocument();
    expect(
      screen.queryByText("Preserved export fixture"),
    ).not.toBeInTheDocument();
  });
});
