import { describe, expect, it } from "vitest";
import { experimentalSessionCookie } from "./session-cookie";

describe("experimental session cookie isolation", () => {
  it("renames the legacy cookie without changing its value or security attributes", () => {
    expect(
      experimentalSessionCookie(
        "POI_SESSION=opaque-session-value; Path=/; Secure; HttpOnly; SameSite=Strict",
      ),
    ).toBe(
      "POI_GPU_SESSION=opaque-session-value; Path=/; Secure; HttpOnly; SameSite=Strict",
    );
  });

  it("expires only the experimental cookie when shared logout clears the legacy name", () => {
    expect(
      experimentalSessionCookie(
        "POI_SESSION=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; HttpOnly; SameSite=Strict",
      ),
    ).toBe(
      "POI_GPU_SESSION=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; HttpOnly; SameSite=Strict",
    );
  });

  it.each([
    "POI_GPU_SESSION=already-isolated; Path=/; HttpOnly",
    "OTHER=POI_SESSION=part-of-value; Path=/",
    "POI_SESSION_EXTRA=another-cookie; Path=/",
    "poi_session=case-sensitive-name; Path=/",
  ])(
    "preserves unrelated or already isolated Set-Cookie headers: %s",
    (header) => {
      expect(experimentalSessionCookie(header)).toBe(header);
    },
  );
});
