import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, money, setCsrfToken } from "./api";
afterEach(() => {
  vi.unstubAllGlobals();
  setCsrfToken(null);
});
describe("exact minor-unit currency display", () => {
  it.each([
    [9007199254740982, "₹9,00,71,99,25,47,409.82"],
    [Number.MAX_SAFE_INTEGER, "₹9,00,71,99,25,47,409.91"],
    [-9007199254740982, "-₹9,00,71,99,25,47,409.82"],
    [Number.MIN_SAFE_INTEGER, "-₹9,00,71,99,25,47,409.91"],
  ] as const)(
    "preserves every paise at safe-integer boundary %s",
    (value, expected) => {
      expect(money(value)).toBe(expected);
    },
  );
  it("formats zero, original dataset amounts and Indian digit grouping exactly", () => {
    for (const [value, expected] of [
      [0, "₹0.00"],
      [1, "₹0.01"],
      [65075, "₹650.75"],
      [70140, "₹701.40"],
      [105478, "₹1,054.78"],
      [1519983, "₹15,199.83"],
      [1882469, "₹18,824.69"],
      [123456789, "₹12,34,567.89"],
    ] as const) {
      expect(money(value, "INR")).toBe(expected);
    }
  });
  it("preserves negative signs below one rupee and at the rupee boundary", () => {
    for (const [value, expected] of [
      [-0, "-₹0.00"],
      [-1, "-₹0.01"],
      [-9, "-₹0.09"],
      [-82, "-₹0.82"],
      [-99, "-₹0.99"],
      [-100, "-₹1.00"],
      [-101, "-₹1.01"],
    ] as const) {
      expect(money(value)).toBe(expected);
    }
  });
  it("shows invalid amounts explicitly instead of rounding unsupported inputs", () => {
    for (const value of [
      Number.MAX_SAFE_INTEGER + 1,
      Number.MIN_SAFE_INTEGER - 1,
      0.1,
      -0.1,
      Number.NaN,
      Number.POSITIVE_INFINITY,
      Number.NEGATIVE_INFINITY,
    ]) {
      expect(money(value)).toBe("Invalid amount");
    }
  });
});

describe("session API client", () => {
  it("includes the session cookie policy and CSRF token on mutations", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ id: "DEC-1" }), { status: 200 }),
      );
    vi.stubGlobal("fetch", fetcher);
    setCsrfToken("session-csrf");
    await api("/cases/CASE-1/decisions", {
      method: "POST",
      headers: { "Idempotency-Key": "retry-key" },
      body: JSON.stringify({ decision: "APPROVE" }),
    });
    const [url, options] = fetcher.mock.calls[0];
    expect(url).toBe("/api/cases/CASE-1/decisions");
    expect(options.credentials).toBe("same-origin");
    expect(options.headers.get("X-CSRF-Token")).toBe("session-csrf");
    expect(options.headers.get("Idempotency-Key")).toBe("retry-key");
  });
  it("preserves authorization and request identifiers from backend errors", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: "FORBIDDEN",
            message: "Independent reviewer required.",
            requestId: "REQ-17",
          }),
          { status: 403 },
        ),
      ),
    );
    await expect(
      api("/cases/CASE-1/decisions", { method: "POST" }),
    ).rejects.toMatchObject({
      status: 403,
      code: "FORBIDDEN",
      requestId: "REQ-17",
    });
  });
  it("clears the authenticated UI on an expired protected session", async () => {
    const listener = vi.fn();
    window.addEventListener("session-expired", listener);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ message: "Session expired" }), {
          status: 401,
        }),
      ),
    );
    await expect(api("/cases")).rejects.toBeInstanceOf(ApiError);
    expect(listener).toHaveBeenCalledOnce();
    window.removeEventListener("session-expired", listener);
  });
  it("reports a network failure instead of presenting an empty successful result", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new TypeError("Failed to fetch")),
    );
    await expect(api("/cases")).rejects.toMatchObject({
      status: 0,
      code: "NETWORK_ERROR",
    });
  });
  it("preserves a gateway timeout even when nginx returns an HTML error page", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response("<html>Gateway Timeout</html>", { status: 504 }),
        ),
    );
    await expect(
      api("/cases/CASE-1/investigations", { method: "POST" }),
    ).rejects.toMatchObject({
      status: 504,
      code: "GATEWAY_TIMEOUT",
      message: expect.stringContaining("No completed result was received"),
    });
  });
  it("ignores a late expired-session response from a request abandoned during navigation", async () => {
    const controller = new AbortController();
    const listener = vi.fn();
    let finish!: (response: Response) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn(
        () =>
          new Promise<Response>((resolve) => {
            finish = resolve;
          }),
      ),
    );
    window.addEventListener("session-expired", listener);
    const request = api("/cases/CASE-1/investigations", {
      method: "POST",
      signal: controller.signal,
    });
    controller.abort();
    finish(
      new Response(JSON.stringify({ message: "Session expired" }), {
        status: 401,
      }),
    );
    await expect(request).rejects.toMatchObject({ name: "AbortError" });
    expect(listener).not.toHaveBeenCalled();
    window.removeEventListener("session-expired", listener);
  });
});
