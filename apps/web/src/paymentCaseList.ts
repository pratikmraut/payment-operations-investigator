import { useEffect, useState } from "react";
import { api } from "./api";

export const CASE_SORTS = {
  CREATED_DESC: "Newest cases first",
  CREATED_ASC: "Oldest cases first",
  UPDATED_DESC: "Recently updated first",
  CASE_NUMBER_ASC: "Case number: ascending",
  CASE_NUMBER_DESC: "Case number: descending",
  PRIORITY_DESC: "Highest priority first",
} as const;
export type CaseSort = keyof typeof CASE_SORTS;
export type CaseWork =
  | "ALL"
  | "MINE"
  | "OPEN"
  | "INVESTIGATING"
  | "AWAITING_EVIDENCE"
  | "AWAITING_REVIEW"
  | "RESOLVED";
export type CaseListQuery = {
  lifecycle?: "ACTIVE" | "ARCHIVED" | "ALL";
  search?: string;
  work?: CaseWork;
  page?: number;
  pageSize?: number;
  sort?: CaseSort;
  bank?: string;
  branch?: string;
  reference?: string;
};
export type CasePage<T> = {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
  sort: CaseSort;
  search: string;
  work: CaseWork;
  lifecycle: "ACTIVE" | "ARCHIVED" | "ALL";
};
export const isRecord = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === "object" && !Array.isArray(value);

export type SavedPaymentCase = {
  id: string;
  caseNumber?: string;
  lifecycleState?: "ACTIVE" | "ARCHIVED";
  reference: string;
  utr: string | null;
  orgBank: string;
  orgBranch: string;
  amount: string;
  currency: string | null;
  evidenceCurrency?: unknown;
  reason: string;
  evidenceStatus: string;
};
export function isSavedPaymentCase(item: unknown): item is SavedPaymentCase {
  return (
    isRecord(item) &&
    [
      "id",
      "reference",
      "orgBank",
      "orgBranch",
      "amount",
      "reason",
      "evidenceStatus",
    ].every((key) => typeof item[key] === "string") &&
    !!item.id &&
    (item.caseNumber === undefined || typeof item.caseNumber === "string") &&
    (item.lifecycleState === undefined ||
      ["ACTIVE", "ARCHIVED"].includes(String(item.lifecycleState))) &&
    [item.utr, item.currency].every(
      (field) => field === null || typeof field === "string",
    )
  );
}
export function caseListPath(query: CaseListQuery = {}) {
  const parameters = new URLSearchParams({
    lifecycle: query.lifecycle ?? "ACTIVE",
    search: (query.search ?? "").trim(),
    work: query.work ?? "ALL",
    page: String(query.page ?? 1),
    pageSize: String(query.pageSize ?? 10),
    sort: query.sort ?? "CREATED_DESC",
  });
  for (const key of ["bank", "branch", "reference"] as const)
    if (query[key] !== undefined) parameters.set(key, query[key]!);
  return `/payment-cases?${parameters}`;
}
export function validateCasePage<T extends { id: string }>(
  value: unknown,
  validItem: (item: unknown) => item is T,
): CasePage<T> {
  if (
    !isRecord(value) ||
    !Array.isArray(value.items) ||
    !value.items.every(validItem) ||
    new Set(value.items.map((item) => item.id)).size !== value.items.length ||
    !["total", "page", "pageSize", "totalPages"].every((key) =>
      Number.isSafeInteger(value[key]),
    ) ||
    (value.total as number) < 0 ||
    (value.pageSize as number) < 1 ||
    (value.pageSize as number) > 50 ||
    value.totalPages !==
      Math.max(
        1,
        Math.ceil((value.total as number) / (value.pageSize as number)),
      ) ||
    (value.page as number) < 1 ||
    (value.page as number) > (value.totalPages as number) ||
    value.items.length !==
      Math.min(
        value.pageSize as number,
        Math.max(
          0,
          (value.total as number) -
            ((value.page as number) - 1) * (value.pageSize as number),
        ),
      ) ||
    typeof value.sort !== "string" ||
    !Object.hasOwn(CASE_SORTS, value.sort) ||
    typeof value.search !== "string" ||
    value.search.length > 200 ||
    ![
      "ALL",
      "MINE",
      "OPEN",
      "INVESTIGATING",
      "AWAITING_EVIDENCE",
      "AWAITING_REVIEW",
      "RESOLVED",
    ].includes(String(value.work)) ||
    !["ACTIVE", "ARCHIVED", "ALL"].includes(String(value.lifecycle))
  )
    throw new Error(
      "The saved payment list could not be read. Refresh cases to try again.",
    );
  return value as CasePage<T>;
}
export function useCaseSearch(search: string) {
  const value = search.trim();
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), 250);
    return () => window.clearTimeout(timer);
  }, [value]);
  return { debounced, pending: value !== debounced };
}

// Validators are module-level functions so a render does not restart a request.
export function useCaseRead<T>(
  path: string | null,
  validate: (value: unknown) => T,
  revision = 0,
  enabled = true,
  preserve = false,
) {
  const [state, setState] = useState<{
    path: string | null;
    data: T | null;
    loading: boolean;
    error: Error | null;
  }>({ path, data: null, loading: !!path, error: null });
  useEffect(() => {
    if (!path || !enabled) return;
    const controller = new AbortController();
    setState((current) => ({
      path,
      data: preserve && current.path === path ? current.data : null,
      loading: true,
      error: null,
    }));
    const timer = window.setTimeout(() => {
      controller.abort();
      setState((current) => ({
        ...current,
        loading: false,
        error: new Error(
          "Loading payment cases took longer than 30 seconds. Refresh cases to retry.",
        ),
      }));
    }, 30000);
    api<unknown>(path, { signal: controller.signal })
      .then((value) => {
        if (!controller.signal.aborted)
          setState({
            path,
            data: validate(value),
            loading: false,
            error: null,
          });
      })
      .catch((error: Error) => {
        if (!controller.signal.aborted)
          setState((current) => ({ ...current, loading: false, error }));
      })
      .finally(() => window.clearTimeout(timer));
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [path, revision, enabled, preserve, validate]);
  return state.path === path && enabled
    ? state
    : { path, data: null, loading: !!path, error: null };
}
