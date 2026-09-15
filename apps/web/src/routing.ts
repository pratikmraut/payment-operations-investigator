import type { MouseEvent } from "react";

export type Route = {
  page: "cases" | "payment-cases" | "knowledge" | "system" | "evidences";
  caseId?: string;
  evidenceId?: string;
  view?: "questions" | "exports";
  reportOpen?: true;
};

function canonicalPath(path: string): string {
  const segments = path.replace(/\/+$/, "").split("/").slice(1);
  const [page, id] = segments;
  if (page === "evidences" && id === "exports" && segments.length === 2) {
    return "/evidences/exports";
  }
  if (
    page === "evidences" &&
    id === "questions" &&
    segments.length >= 2 &&
    segments.length <= 4
  ) {
    try {
      const identifiers = segments.slice(2);
      if (identifiers.some((value) => !value)) return "/evidences/questions";
      return [
        "/evidences/questions",
        ...identifiers.map((value) =>
          encodeURIComponent(decodeURIComponent(value)),
        ),
      ].join("/");
    } catch {
      return "/evidences/questions";
    }
  }
  if (segments.length === 1) {
    if (page === "uat-evidence" || page === "evidences") return "/evidences";
    if (page === "knowledge" || page === "system") return `/${page}`;
  }
  if (
    (page === "cases" || page === "payment-cases") &&
    id &&
    segments.length === 2
  ) {
    try {
      return `/${page}/${encodeURIComponent(decodeURIComponent(id))}`;
    } catch {
      return "/cases";
    }
  }
  return "/cases";
}

export function workspaceDestination(): string {
  // Accept old bookmarks, but all rendered links and subsequent URLs use paths.
  const path = window.location.hash.startsWith("#/")
    ? window.location.hash.slice(1)
    : `${window.location.pathname}${window.location.search}`;
  return canonicalDestination(path);
}

function canonicalDestination(input: string) {
  const [path, query = ""] = input.split("?", 2);
  const destination = canonicalPath(path);
  return /^\/payment-cases\/[^/]+$/.test(destination) &&
    new URLSearchParams(query).get("report") === "1"
    ? `${destination}?report=1`
    : destination;
}

export function currentRoute(): Route {
  const [, page, id, questionCaseId, evidenceId] = workspaceDestination()
    .split("?")[0]
    .split("/");
  const questions = page === "evidences" && id === "questions";
  return {
    ...(workspaceDestination().endsWith("?report=1")
      ? { reportOpen: true as const }
      : {}),
    page: page as Route["page"],
    caseId:
      (page === "cases" || page === "payment-cases") && id
        ? decodeURIComponent(id)
        : questions && questionCaseId
          ? decodeURIComponent(questionCaseId)
          : undefined,
    evidenceId:
      questions && evidenceId ? decodeURIComponent(evidenceId) : undefined,
    view: questions
      ? "questions"
      : page === "evidences" && id === "exports"
        ? "exports"
        : undefined,
  };
}

export function canonicalizeWorkspace() {
  const destination = workspaceDestination();
  if (
    `${window.location.pathname}${window.location.search}${window.location.hash}` !==
    destination
  ) {
    window.history.replaceState(null, "", destination);
  }
}

type NavigationState = { caseQueueMode: "payment" | "demo" };

export function navigateTo(path: string, state: NavigationState | null = null) {
  const destination = canonicalDestination(path);
  if (
    `${window.location.pathname}${window.location.search}${window.location.hash}` !==
    destination
  ) {
    window.history.pushState(state, "", destination);
  } else if (state) {
    window.history.replaceState(state, "", destination);
  }
  window.dispatchEvent(new PopStateEvent("popstate"));
}

export function replaceDestination(path: string) {
  const destination = canonicalDestination(path);
  if (`${window.location.pathname}${window.location.search}` !== destination) {
    window.history.replaceState(window.history.state, "", destination);
    window.dispatchEvent(new PopStateEvent("popstate"));
  }
}

export function navigateLink(event: MouseEvent<HTMLAnchorElement>) {
  const anchor = event.currentTarget;
  if (
    event.defaultPrevented ||
    event.button !== 0 ||
    event.metaKey ||
    event.ctrlKey ||
    event.shiftKey ||
    event.altKey ||
    anchor.hasAttribute("download") ||
    (anchor.target && anchor.target !== "_self")
  )
    return;
  const destination = new URL(anchor.href);
  if (destination.origin !== window.location.origin) return;
  event.preventDefault();
  navigateTo(
    `${destination.pathname}${destination.search}`,
    anchor.dataset.caseQueueMode === "payment"
      ? { caseQueueMode: "payment" }
      : null,
  );
}

export const navigateQueue = navigateLink;
