import type { Investigation, User } from "./types";
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public requestId?: string,
  ) {
    super(message);
  }
}
let csrfToken: string | null = null;
export function setCsrfToken(value: string | null) {
  csrfToken = value;
}
export async function api<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  return apiResponse<T>(path, options, false);
}
export async function apiBlob(
  path: string,
  options: RequestInit = {},
): Promise<Blob> {
  return apiResponse<Blob>(path, options, true);
}
async function apiResponse<T>(
  path: string,
  options: RequestInit,
  blob: boolean,
): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set("Accept", blob ? "application/pdf" : "application/json");
  if (options.body && !(options.body instanceof FormData))
    headers.set("Content-Type", "application/json");
  if (
    options.method &&
    !["GET", "HEAD"].includes(options.method.toUpperCase()) &&
    csrfToken
  )
    headers.set("X-CSRF-Token", csrfToken);
  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      ...options,
      headers,
      credentials: "same-origin",
    });
  } catch (error) {
    options.signal?.throwIfAborted();
    if (error instanceof DOMException && error.name === "AbortError")
      throw error;
    throw new ApiError(
      0,
      "NETWORK_ERROR",
      "Could not reach the API. Check the local services and connection.",
    );
  }
  let payload: unknown;
  try {
    if (response.ok && blob) {
      const contentType = response.headers.get("Content-Type") ?? "";
      if (!contentType.toLowerCase().startsWith("application/pdf"))
        throw new Error("Unexpected report type");
      const file = await response.blob();
      if (!file.size || file.size > 20 * 1024 * 1024)
        throw new Error("Invalid report size");
      payload = file;
    } else
      payload = response.status === 204 ? undefined : await response.json();
  } catch {
    options.signal?.throwIfAborted();
    if (response.ok)
      throw new ApiError(
        response.status,
        "INVALID_RESPONSE",
        "The API returned an unreadable response. Check the service.",
      );
  }
  options.signal?.throwIfAborted();
  if (!response.ok) {
    const detail = payload as {
      code?: string;
      message?: string;
      requestId?: string;
    };
    if (
      response.status === 401 &&
      path !== "/auth/login" &&
      path !== "/auth/me"
    )
      window.dispatchEvent(new Event("session-expired"));
    throw new ApiError(
      response.status,
      detail?.code ||
        (response.status === 504 ? "GATEWAY_TIMEOUT" : "REQUEST_FAILED"),
      detail?.message ||
        (response.status === 504
          ? "The gateway timed out waiting for the API. No completed result was received."
          : `Request failed (${response.status}).`),
      detail?.requestId,
    );
  }
  return payload as T;
}
export function canReview(user: User, investigation: Investigation) {
  return (
    user.role.toUpperCase() === "REVIEWER" &&
    user.id !== investigation.createdBy &&
    investigation.status === "AWAITING_REVIEW"
  );
}
export function money(value: number, currency = "INR") {
  if (!Number.isSafeInteger(value)) return "Invalid amount";
  const minor = BigInt(value);
  const negative = minor < 0n || Object.is(value, -0);
  const magnitude = minor < 0n ? -minor : minor;
  const whole = magnitude / 100n;
  const fraction = (magnitude % 100n).toString().padStart(2, "0");
  // BigInt keeps whole rupees exact; numeric -0 preserves the sub-rupee minus sign.
  const signedWhole = negative ? (whole === 0n ? -0 : -whole) : whole;
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
    .formatToParts(signedWhole)
    .map((part) => (part.type === "fraction" ? fraction : part.value))
    .join("");
}
export function human(value: string | undefined) {
  return (value || "")
    .replaceAll("_", " ")
    .toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase());
}
export function date(value: string) {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime())
    ? value
    : parsed.toLocaleString("en-IN", {
        day: "2-digit",
        month: "short",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
      });
}
