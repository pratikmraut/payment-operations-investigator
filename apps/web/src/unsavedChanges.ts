import { useLayoutEffect, useRef } from "react";

const drafts = new Map<symbol, string>();

/** Only memory is used. Payment data is never persisted by this guard. */
export function useUnsavedChanges(dirty: boolean, label: string) {
  const id = useRef(Symbol("draft"));
  useLayoutEffect(() => {
    const key = id.current;
    if (dirty) drafts.set(key, label);
    else drafts.delete(key);
    const beforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      // Browsers supply their own message for reload, tab close and external links.
      event.returnValue = "";
    };
    if (dirty) window.addEventListener("beforeunload", beforeUnload);
    return () => {
      drafts.delete(key);
      window.removeEventListener("beforeunload", beforeUnload);
    };
  }, [dirty, label]);
}

export function confirmUnsavedChanges(): boolean {
  if (!drafts.size) return true;
  const labels = [...new Set(drafts.values())].join(", ");
  return window.confirm(
    `Leave with unsaved changes?\n\n${labels}\n\nThese changes have not been submitted. Stay to save them or download a draft before leaving.`,
  );
}

function draftPage(path: string) {
  const url = new URL(path, window.location.origin);
  const pathname = url.hash.startsWith("#/")
    ? url.hash.slice(1).split("?")[0]
    : url.pathname;
  // Report dialogs and selecting another version in the same Q&A workbench do
  // not unmount its inputs, so their URLs may change without discarding drafts.
  const questionCase = /^(\/evidences\/questions\/[^/]+)(?:\/[^/]+)?$/.exec(
    pathname,
  );
  return questionCase?.[1] ?? pathname;
}

export function confirmNavigation(destination: string): boolean {
  return (
    draftPage(destination) === draftPage(locationUrl()) ||
    confirmUnsavedChanges()
  );
}

const INDEX = "__poiNavigationIndex";
type Entry = { url: string; index: number; state: Record<string, unknown> };
let accepted: Entry | null = null;
let restoring = false;
const locationUrl = () =>
  `${location.pathname}${location.search}${location.hash}`;
const objectState = (value: unknown): Record<string, unknown> =>
  value && typeof value === "object" ? { ...value } : {};

/** Called only after a navigation has been accepted, or for auth/canonical redirects. */
export function writeNavigationHistory(
  kind: "push" | "replace",
  path: string,
  state: unknown = window.history.state,
) {
  const previous = Number(window.history.state?.[INDEX]);
  const index =
    (accepted?.index ?? (Number.isFinite(previous) ? previous : 0)) +
    (kind === "push" ? 1 : 0);
  const nextState = { ...objectState(state), [INDEX]: index };
  window.history[kind === "push" ? "pushState" : "replaceState"](
    nextState,
    "",
    path,
  );
  if (accepted) accepted = { url: locationUrl(), index, state: nextState };
}

/** Capture history events before the app router can unmount a dirty form. */
export function installNavigationProtection() {
  const index = Number(window.history.state?.[INDEX]);
  const state = {
    ...objectState(window.history.state),
    [INDEX]: Number.isFinite(index) ? index : 0,
  };
  window.history.replaceState(state, "", locationUrl());
  accepted = { url: locationUrl(), index: state[INDEX], state };
  restoring = false;
  const onHistory = (event: Event) => {
    if (!accepted) return;
    if (restoring) {
      event.stopImmediatePropagation();
      // A rejected Back/Forward returns to the original history entry, without
      // pushing duplicate entries or remounting any input component.
      if (locationUrl() === accepted.url) restoring = false;
      return;
    }
    if (locationUrl() === accepted.url) return;
    const nextIndex = Number(window.history.state?.[INDEX]);
    if (
      draftPage(locationUrl()) !== draftPage(accepted.url) &&
      !confirmUnsavedChanges()
    ) {
      event.stopImmediatePropagation();
      const delta = accepted.index - nextIndex;
      if (Number.isFinite(nextIndex) && delta !== 0) {
        restoring = true;
        window.history.go(delta);
      } else {
        // Compatibility for untagged same-document hash bookmarks.
        window.history.replaceState(accepted.state, "", accepted.url);
      }
      return;
    }
    const nextState = {
      ...objectState(window.history.state),
      [INDEX]: Number.isFinite(nextIndex) ? nextIndex : accepted.index + 1,
    };
    window.history.replaceState(nextState, "", locationUrl());
    accepted = {
      url: locationUrl(),
      index: nextState[INDEX],
      state: nextState,
    };
  };
  window.addEventListener("popstate", onHistory, true);
  window.addEventListener("hashchange", onHistory, true);
  return () => {
    window.removeEventListener("popstate", onHistory, true);
    window.removeEventListener("hashchange", onHistory, true);
    accepted = null;
    restoring = false;
  };
}
