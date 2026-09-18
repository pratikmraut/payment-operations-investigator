import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { navigateLink, navigateTo, replaceDestination } from "./routing";
import {
  confirmUnsavedChanges,
  installNavigationProtection,
  useUnsavedChanges,
} from "./unsavedChanges";

let stop: (() => void) | undefined;
afterEach(() => {
  cleanup();
  stop?.();
  stop = undefined;
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/");
});
function Draft() {
  const [value, setValue] = useState("");
  const [saved, setSaved] = useState("");
  useUnsavedChanges(value !== saved, "Case note");
  return (
    <>
      <input
        aria-label="Draft"
        value={value}
        onChange={(event) => setValue(event.target.value)}
      />
      <button onClick={() => setSaved(value)}>Save</button>
      <a href="/knowledge" onClick={navigateLink}>
        Knowledge
      </a>
    </>
  );
}
function edit() {
  fireEvent.change(screen.getByLabelText("Draft"), {
    target: { value: "Check the source records" },
  });
}
describe("unsaved input protection", () => {
  it("blocks links, programmatic navigation and replace navigation without losing input", () => {
    window.history.replaceState(null, "", "/cases");
    stop = installNavigationProtection();
    render(<Draft />);
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    edit();
    fireEvent.click(screen.getByRole("link", { name: "Knowledge" }));
    navigateTo("/system");
    replaceDestination("/evidences");
    expect(window.location.pathname).toBe("/cases");
    expect(screen.getByLabelText("Draft")).toHaveValue(
      "Check the source records",
    );
    expect(confirm).toHaveBeenCalledTimes(3);
    expect(confirm.mock.calls[0][0]).toContain("Case note");
    confirm.mockReturnValue(true);
    navigateTo("/system");
    expect(window.location.pathname).toBe("/system");
    expect(confirm).toHaveBeenCalledTimes(4); // accepted navigation does not prompt twice
  });
  it("warns on reload only while dirty and stops after save, undo or unmount", () => {
    const { unmount } = render(<Draft />);
    const beforeUnload = () => {
      const event = new Event("beforeunload", { cancelable: true });
      window.dispatchEvent(event);
      return event.defaultPrevented;
    };
    expect(beforeUnload()).toBe(false);
    edit();
    expect(beforeUnload()).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(beforeUnload()).toBe(false);
    fireEvent.change(screen.getByLabelText("Draft"), {
      target: { value: "More edits" },
    });
    expect(beforeUnload()).toBe(true);
    fireEvent.change(screen.getByLabelText("Draft"), {
      target: { value: "Check the source records" },
    });
    expect(beforeUnload()).toBe(false);
    edit();
    unmount();
    expect(confirmUnsavedChanges()).toBe(true);
  });
  it("restores a canceled browser Back without notifying the router, then allows Back and Forward", async () => {
    window.history.replaceState({ caseQueueMode: "payment" }, "", "/cases");
    stop = installNavigationProtection();
    navigateTo("/payment-cases/CASE-A");
    const route = vi.fn();
    window.addEventListener("popstate", route);
    render(<Draft />);
    edit();
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    act(() => window.history.back());
    await waitFor(() => expect(confirm).toHaveBeenCalledTimes(1));
    await waitFor(() =>
      expect(window.location.pathname).toBe("/payment-cases/CASE-A"),
    );
    expect(route).not.toHaveBeenCalled();
    expect(screen.getByLabelText("Draft")).toHaveValue(
      "Check the source records",
    );
    confirm.mockReturnValue(true);
    act(() => window.history.back());
    await waitFor(() => expect(window.location.pathname).toBe("/cases"));
    expect(window.history.state.caseQueueMode).toBe("payment");
    expect(route).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    act(() => window.history.forward());
    await waitFor(() =>
      expect(window.location.pathname).toBe("/payment-cases/CASE-A"),
    );
    expect(confirm).toHaveBeenCalledTimes(2);
    window.removeEventListener("popstate", route);
  });
  it("does not warn for the current route or opening an internal link in another tab", () => {
    window.history.replaceState(null, "", "/cases");
    stop = installNavigationProtection();
    render(<Draft />);
    edit();
    const confirm = vi.spyOn(window, "confirm");
    navigateTo("/cases");
    navigateLink({
      currentTarget: screen.getByRole("link", { name: "Knowledge" }),
      button: 0,
      ctrlKey: true,
      preventDefault: vi.fn(),
    } as unknown as Parameters<typeof navigateLink>[0]);
    expect(confirm).not.toHaveBeenCalled();
  });
  it("keeps same-case report and Q&A version navigation usable while retaining the dirty guard", () => {
    window.history.replaceState(null, "", "/payment-cases/CASE-A");
    stop = installNavigationProtection();
    render(<Draft />);
    edit();
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    navigateTo("/payment-cases/CASE-A?report=1");
    expect(window.location.search).toBe("?report=1");
    expect(confirm).not.toHaveBeenCalled();
    navigateTo("/knowledge");
    expect(confirm).toHaveBeenCalledOnce();
    expect(window.location.pathname).toBe("/payment-cases/CASE-A");
  });
});
