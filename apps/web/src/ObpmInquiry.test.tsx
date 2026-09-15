import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ObpmInquiry } from "./ObpmInquiry";
import { setCsrfToken } from "./api";
import type { User } from "./types";

const analyst: User = {
  id: "analyst",
  name: "Demo analyst",
  role: "ANALYST",
  tenantId: "northstar",
};
const configuration = {
  enabled: true,
  mode: "SYNTHETIC_MOCK",
  referenceType: "PAYMENT_REFERENCE",
  examples: [
    { reference: "MOCK-NEFT-1001", label: "Current ECA timeout" },
    {
      reference: "MOCK-NEFT-1002",
      label: "Historical timeout, current pending",
    },
  ],
};
const receipt = {
  importId: "IMPORT-MOCK-1",
  caseId: "OBPM-MOCK-1",
  status: "CREATED",
  evidenceVersion: 1,
  evidenceHash: "hash-one",
  caseVersion: 1,
};
const reply = (data: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(data), { status }));
afterEach(() => {
  vi.unstubAllGlobals();
  setCsrfToken(null);
});

function mockApi(
  options: {
    enabled?: boolean;
    status?: number;
    body?: unknown;
    hold?: boolean;
  } = {},
) {
  let resolvePost: (response: Response) => void = () => {};
  const fetcher = vi.fn((url: string, request: RequestInit) => {
    if (url === "/api/obpm/inquiry")
      return reply({ ...configuration, enabled: options.enabled ?? true });
    if (url === "/api/obpm/inquiries" && request.method === "POST") {
      if (options.hold)
        return new Promise<Response>((resolve) => {
          resolvePost = resolve;
        });
      return reply(options.body ?? receipt, options.status ?? 200);
    }
    return Promise.reject(new Error(`Unexpected request: ${url}`));
  });
  vi.stubGlobal("fetch", fetcher);
  return {
    fetcher,
    finish: () =>
      resolvePost(new Response(JSON.stringify(receipt), { status: 200 })),
  };
}
async function chooseExample() {
  const user = userEvent.setup();
  await screen.findByRole("option", {
    name: /MOCK-NEFT-1001 · Current ECA timeout/,
  });
  await user.selectOptions(
    screen.getByLabelText("Mock inquiry example"),
    "MOCK-NEFT-1001",
  );
  return user;
}

describe("local mock inquiry", () => {
  it("fetches only after an explicit action, uses session CSRF, and opens the confirmed case", async () => {
    const { fetcher, finish } = mockApi({ hold: true });
    const imported = vi.fn();
    const busy = vi.fn();
    setCsrfToken("csrf-inquiry");
    render(
      <ObpmInquiry user={analyst} onImported={imported} onBusyChange={busy} />,
    );
    const user = await chooseExample();
    expect(
      fetcher.mock.calls.filter(([, options]) => options.method === "POST"),
    ).toHaveLength(0);
    expect(
      screen.getByText(/Service availability is checked when you fetch/),
    ).toBeVisible();
    expect(
      screen.getByText(/No Oracle connection is configured/),
    ).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Fetch and import" }));
    expect(
      screen.getByRole("button", { name: "Fetching and importing…" }),
    ).toBeDisabled();
    expect(
      screen.queryByRole("link", { name: "Open fetched case" }),
    ).not.toBeInTheDocument();
    expect(imported).not.toHaveBeenCalled();
    const post = fetcher.mock.calls.find(
      ([, options]) => options.method === "POST",
    )!;
    expect(post[0]).toBe("/api/obpm/inquiries");
    expect(JSON.parse(post[1].body as string)).toEqual({
      paymentReference: "MOCK-NEFT-1001",
    });
    expect((post[1].headers as Headers).get("X-CSRF-Token")).toBe(
      "csrf-inquiry",
    );
    expect(post[1].credentials).toBe("same-origin");
    finish();
    await waitFor(() => expect(imported).toHaveBeenCalledOnce());
    expect(
      screen.getByText("Created · OBPM-MOCK-1 · Evidence v1"),
    ).toBeVisible();
    expect(
      screen.getByRole("link", { name: "Open fetched case" }),
    ).toHaveAttribute("href", "/cases/OBPM-MOCK-1");
    expect(busy.mock.calls).toEqual([[true], [false]]);
    await user.clear(screen.getByLabelText("Payment reference"));
    expect(
      screen.queryByRole("link", { name: "Open fetched case" }),
    ).not.toBeInTheDocument();
  });

  it("keeps a disabled deployment visible without sending an inquiry", async () => {
    const { fetcher } = mockApi({ enabled: false });
    render(<ObpmInquiry user={analyst} onImported={vi.fn()} />);
    await screen.findByText(/mock inquiry connector is disabled/);
    expect(
      screen.getByRole("button", { name: "Fetch and import" }),
    ).toBeDisabled();
    expect(screen.getByLabelText("Payment reference")).toBeDisabled();
    expect(
      fetcher.mock.calls.every(([, options]) => options.method !== "POST"),
    ).toBe(true);
  });

  it.each([404, 502, 503, 504])(
    "shows an API %s failure and its request ID without claiming an import",
    async (status) => {
      mockApi({
        status,
        body: {
          code: "INQUIRY_FAILED",
          message: "Mock inquiry could not complete.",
          requestId: `REQ-${status}`,
        },
      });
      const imported = vi.fn();
      render(<ObpmInquiry user={analyst} onImported={imported} />);
      const user = await chooseExample();
      await user.click(
        screen.getByRole("button", { name: "Fetch and import" }),
      );
      expect(await screen.findByRole("alert")).toHaveTextContent(
        `REQ-${status}`,
      );
      expect(screen.getByRole("alert")).toHaveTextContent(
        "Mock inquiry could not complete.",
      );
      expect(
        screen.queryByRole("link", { name: "Open fetched case" }),
      ).not.toBeInTheDocument();
      expect(imported).not.toHaveBeenCalled();
      await user.selectOptions(
        screen.getByLabelText("Mock inquiry example"),
        "MOCK-NEFT-1002",
      );
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    },
  );

  it("rejects a blank payment reference locally and does not accept a malformed receipt as success", async () => {
    const { fetcher } = mockApi({ body: { status: "CREATED" } });
    const imported = vi.fn();
    render(<ObpmInquiry user={analyst} onImported={imported} />);
    const user = userEvent.setup();
    await screen.findByRole("option", {
      name: /MOCK-NEFT-1001 · Current ECA timeout/,
    });
    await user.type(screen.getByLabelText("Payment reference"), "   ");
    await user.click(screen.getByRole("button", { name: "Fetch and import" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Enter a payment reference",
    );
    expect(
      fetcher.mock.calls.every(([, options]) => options.method !== "POST"),
    ).toBe(true);
    await user.type(
      screen.getByLabelText("Payment reference"),
      "MOCK-NEFT-1001",
    );
    await user.click(screen.getByRole("button", { name: "Fetch and import" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "unreadable import receipt",
    );
    expect(imported).not.toHaveBeenCalled();
  });

  it("withholds inquiry writes from a viewer including programmatic form submission", async () => {
    const { fetcher } = mockApi();
    render(
      <ObpmInquiry
        user={{ ...analyst, role: "VIEWER" }}
        onImported={vi.fn()}
      />,
    );
    await screen.findByRole("option", {
      name: /MOCK-NEFT-1001 · Current ECA timeout/,
    });
    expect(
      screen.queryByRole("button", { name: "Fetch and import" }),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText("Payment reference")).toHaveAttribute(
      "readonly",
    );
    fireEvent.submit(
      screen.getByLabelText("Payment reference").closest("form")!,
    );
    expect(
      fetcher.mock.calls.every(([, options]) => options.method !== "POST"),
    ).toBe(true);
  });

  it("aborts an unfinished inquiry when the view unmounts and suppresses late success", async () => {
    const { fetcher, finish } = mockApi({ hold: true });
    const imported = vi.fn();
    const view = render(<ObpmInquiry user={analyst} onImported={imported} />);
    const user = await chooseExample();
    await user.click(screen.getByRole("button", { name: "Fetch and import" }));
    const signal = fetcher.mock.calls.find(
      ([, options]) => options.method === "POST",
    )![1].signal!;
    view.unmount();
    expect(signal.aborted).toBe(true);
    finish();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(imported).not.toHaveBeenCalled();
  });
});
