import { afterEach, describe, expect, it } from "vitest";
import {
  canonicalizeWorkspace,
  currentRoute,
  navigateTo,
  workspaceDestination,
} from "./routing";

afterEach(() => window.history.replaceState(null, "", "/"));

describe("case Evidence Q&A routes", () => {
  it("preserves a case-specific report request through clean navigation and canonicalization", () => {
    navigateTo("/payment-cases/CASE%2FA?report=1&ignored=value");
    expect(workspaceDestination()).toBe("/payment-cases/CASE%2FA?report=1");
    expect(currentRoute()).toMatchObject({
      page: "payment-cases",
      caseId: "CASE/A",
      reportOpen: true,
    });
    canonicalizeWorkspace();
    expect(window.location.search).toBe("?report=1");
    expect(window.location.hash).toBe("");
    navigateTo("/payment-cases/CASE%2FA");
    expect(currentRoute().reportOpen).toBeUndefined();
  });
  it.each([
    "/cases?report=1",
    "/evidences?report=1",
    "/payment-cases/CASE-A?report=wrong",
  ])("does not open a report from unsupported query %s", (path) => {
    navigateTo(path);
    expect(currentRoute().reportOpen).toBeUndefined();
    expect(window.location.search).toBe("");
  });
  it.each([
    ["/evidences/questions", undefined, undefined],
    ["/evidences/questions/CASE-A", "CASE-A", undefined],
    ["/evidences/questions/CASE-A/EVD-2", "CASE-A", "EVD-2"],
    [
      "/evidences/questions/CASE%2FA/EVD%20VERSION%202",
      "CASE/A",
      "EVD VERSION 2",
    ],
    ["/evidences/questions/CASE%252F/EVD%3F%23", "CASE%2F", "EVD?#"],
  ])(
    "round trips %s without merging identifiers into the path",
    (path, caseId, evidenceId) => {
      navigateTo(path!);
      expect(workspaceDestination()).toBe(path);
      expect(currentRoute()).toEqual({
        page: "evidences",
        view: "questions",
        caseId,
        evidenceId,
      });
    },
  );

  it.each([
    "/evidences/questions/BAD%",
    "/evidences/questions/CASE-A/BAD%",
    "/evidences/questions//EVD-2",
  ])("rejects malformed route %s before a case can be selected", (path) => {
    navigateTo(path);
    expect(workspaceDestination()).toBe("/evidences/questions");
    expect(currentRoute().caseId).toBeUndefined();
    expect(currentRoute().evidenceId).toBeUndefined();
  });

  it("canonicalizes legacy hash bookmarks and trailing slashes for the selected evidence version", () => {
    window.history.replaceState(
      null,
      "",
      "/#/evidences/questions/CASE-A/EVD-2/",
    );
    canonicalizeWorkspace();
    expect(window.location.pathname).toBe("/evidences/questions/CASE-A/EVD-2");
    expect(window.location.hash).toBe("");
    expect(currentRoute()).toEqual({
      page: "evidences",
      view: "questions",
      caseId: "CASE-A",
      evidenceId: "EVD-2",
    });
  });

  it.each(["/evidences", "/evidences/exports"])(
    "never treats %s as a selected payment case",
    (path) => {
      navigateTo(path);
      expect(currentRoute().caseId).toBeUndefined();
      expect(currentRoute().evidenceId).toBeUndefined();
      expect(currentRoute().view).toBe(
        path.endsWith("exports") ? "exports" : undefined,
      );
    },
  );
});
