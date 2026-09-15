import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { CaseLifecycle, type Lifecycle } from "./CaseLifecycle";
import { navigateTo } from "./routing";
import { setCsrfToken } from "./api";
vi.mock("./routing", () => ({ navigateTo: vi.fn() }));
const user = {
  id: "analyst",
  name: "Analyst",
  role: "ANALYST",
  tenantId: "fixture",
};
const active = (): Lifecycle => ({
  caseId: "CASE-LIFECYCLE",
  caseNumber: "2026091600001",
  state: "ACTIVE",
  version: 0,
  canArchive: true,
  canRestore: false,
  canDelete: false,
  activeInvestigationCount: 0,
  audit: [],
});
const archived = (): Lifecycle => ({
  ...active(),
  state: "ARCHIVED",
  version: 1,
  canArchive: false,
  canRestore: true,
  canDelete: true,
});
const response = (body: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(body), { status }));
function mock(
  initial = active(),
  post: (url: string, options: RequestInit) => Promise<Response> = () =>
    response(archived()),
) {
  const fetcher = vi.fn((url: string, options: RequestInit) =>
    options.method === "POST" ? post(url, options) : response(initial),
  );
  vi.stubGlobal("fetch", fetcher);
  return fetcher;
}
async function page(role = "ANALYST", onChanged = vi.fn()) {
  render(
    <CaseLifecycle
      caseId="CASE-LIFECYCLE"
      user={{ ...user, role }}
      onChanged={onChanged}
    />,
  );
  await screen.findByText("2026091600001", { selector: "strong" });
  return onChanged;
}
const reason = (value = "Duplicate test case created during validation") =>
  fireEvent.change(screen.getByLabelText(/^Reason for/), { target: { value } });
const posts = (fetcher: ReturnType<typeof mock>) =>
  fetcher.mock.calls.filter(([, init]) => init.method === "POST");
afterEach(() => {
  vi.unstubAllGlobals();
  vi.clearAllMocks();
  setCsrfToken(null);
});
describe("private case lifecycle", () => {
  it("requires a reason and sends expected version, CSRF and an idempotency key when archiving", async () => {
    const fetcher = mock();
    setCsrfToken("csrf-lifecycle");
    const changed = await page();
    expect(screen.getByRole("button", { name: "Archive case" })).toBeDisabled();
    reason();
    fireEvent.click(screen.getByRole("button", { name: "Archive case" }));
    await screen.findByText(
      /archived. Its evidence and answers remain available/,
    );
    const [url, options] = posts(fetcher)[0];
    expect(url).toBe("/api/payment-cases/CASE-LIFECYCLE/archive");
    expect(JSON.parse(options.body as string)).toEqual({
      expectedVersion: 0,
      reason: "Duplicate test case created during validation",
    });
    expect((options.headers as Headers).get("Idempotency-Key")).toBeTruthy();
    expect((options.headers as Headers).get("X-CSRF-Token")).toBe(
      "csrf-lifecycle",
    );
    expect(changed).toHaveBeenCalledWith("ARCHIVED");
    expect(screen.getByRole("button", { name: "Restore case" })).toBeVisible();
  });
  it("restores an archived case with its existing number", async () => {
    const fetcher = mock(archived(), () =>
      response({ ...active(), version: 2 }),
    );
    await page();
    reason("Continue evidence review");
    fireEvent.click(screen.getByRole("button", { name: "Restore case" }));
    await screen.findByText("Case 2026091600001 restored to the active queue.");
    expect(posts(fetcher)[0][0]).toMatch(/\/restore$/);
    expect(
      JSON.parse(posts(fetcher)[0][1].body as string).expectedVersion,
    ).toBe(1);
  });
  it("blocks changes while an investigation is queued or running", async () => {
    const fetcher = mock({
      ...active(),
      activeInvestigationCount: 1,
      canArchive: false,
    });
    await page();
    reason();
    expect(screen.getByText(/Wait for them to finish/)).toBeVisible();
    expect(screen.getByRole("button", { name: "Archive case" })).toBeDisabled();
    expect(posts(fetcher)).toHaveLength(0);
  });
  it("does not expose permanent deletion to analysts even if the response advertises it", async () => {
    mock(archived());
    await page();
    expect(
      screen.queryByRole("button", { name: /permanently/i }),
    ).not.toBeInTheDocument();
  });
  it("shows only lifecycle history for viewers", async () => {
    const fetcher = mock();
    await page("VIEWER");
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Archive case" }),
    ).not.toBeInTheDocument();
    expect(posts(fetcher)).toHaveLength(0);
  });
  it("requires the exact case number and a reason for administrator deletion", async () => {
    const fetcher = mock(archived(), () =>
      response({
        caseId: "CASE-LIFECYCLE",
        caseNumber: "2026091600001",
        state: "DELETED",
        version: 2,
      }),
    );
    await page("ADMIN");
    fireEvent.click(screen.getByRole("button", { name: /Delete an unwanted/ }));
    reason();
    const confirm = screen.getByLabelText(/Type case number/);
    fireEvent.change(confirm, { target: { value: "2026091600002" } });
    expect(
      screen.getByRole("button", { name: "Permanently delete case" }),
    ).toBeDisabled();
    fireEvent.change(confirm, { target: { value: "2026091600001" } });
    fireEvent.click(
      screen.getByRole("button", { name: "Permanently delete case" }),
    );
    await waitFor(() => expect(navigateTo).toHaveBeenCalledWith("/cases"));
    expect(posts(fetcher)[0][0]).toMatch(/\/permanent-delete$/);
    expect(JSON.parse(posts(fetcher)[0][1].body as string).confirmation).toBe(
      "2026091600001",
    );
  });
  it("retries an uncertain outcome with the same payload and idempotency key", async () => {
    let count = 0;
    const fetcher = mock(active(), () =>
      ++count === 1
        ? Promise.reject(new TypeError("network"))
        : response(archived()),
    );
    await page();
    reason();
    fireEvent.click(screen.getByRole("button", { name: "Archive case" }));
    fireEvent.click(
      await screen.findByRole("button", { name: "Retry pending action" }),
    );
    await screen.findByText(/archived. Its evidence/);
    const attempts = posts(fetcher);
    expect(attempts).toHaveLength(2);
    expect(attempts[0][1].body).toBe(attempts[1][1].body);
    expect((attempts[0][1].headers as Headers).get("Idempotency-Key")).toBe(
      (attempts[1][1].headers as Headers).get("Idempotency-Key"),
    );
  });
  it("preserves the reason after a state conflict and requires refresh", async () => {
    mock(active(), () =>
      response(
        { code: "CASE_VERSION_CONFLICT", message: "Case version changed" },
        409,
      ),
    );
    await page();
    reason();
    fireEvent.click(screen.getByRole("button", { name: "Archive case" }));
    await screen.findByText(/The case changed. Refresh lifecycle/);
    expect(screen.getByLabelText(/^Reason for/)).toHaveValue(
      "Duplicate test case created during validation",
    );
    expect(screen.getByRole("button", { name: "Archive case" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Refresh lifecycle" }));
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Archive case" }),
      ).toBeEnabled(),
    );
  });
  it("leaves deletion mode when refresh finds that another session restored the case", async () => {
    let current = archived();
    const fetcher = vi.fn((url: string, options: RequestInit) =>
      options.method === "POST" ? response(archived()) : response(current),
    );
    vi.stubGlobal("fetch", fetcher);
    await page("ADMIN");
    fireEvent.click(screen.getByRole("button", { name: /Delete an unwanted/ }));
    reason("No longer needed for investigation");
    fireEvent.change(screen.getByLabelText(/Type case number/), {
      target: { value: "2026091600001" },
    });
    current = { ...active(), version: 2 };
    fireEvent.click(screen.getByRole("button", { name: "Refresh lifecycle" }));
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Archive case" }),
      ).toBeEnabled(),
    );
    expect(screen.queryByLabelText(/Type case number/)).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Permanently delete case" }),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText("Reason for archiving")).toHaveValue(
      "No longer needed for investigation",
    );
    expect(posts(fetcher)).toHaveLength(0);
  });
  it("rejects a lifecycle response belonging to another case", async () => {
    mock({ ...active(), caseId: "UNRELATED" });
    render(<CaseLifecycle caseId="CASE-LIFECYCLE" user={user} />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      /could not be read for this case/,
    );
    expect(
      screen.queryByRole("button", { name: "Archive case" }),
    ).not.toBeInTheDocument();
  });
});
