import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "./api";

export type HistoryPageMeta = {
  total: number;
  limit: number;
  nextCursor: string | null;
};
export type HistoryPage<T> = HistoryPageMeta & { caseId: string; items: T[] };

export function historyMeta(
  value: unknown,
  count: number,
): HistoryPageMeta | undefined {
  if (value === undefined) return undefined; // Retained pre-pagination snapshots.
  const meta = value as HistoryPageMeta;
  if (
    !meta ||
    !Number.isSafeInteger(meta.total) ||
    meta.total < count ||
    !Number.isSafeInteger(meta.limit) ||
    meta.limit < 1 ||
    meta.limit > 25 ||
    count > meta.limit ||
    !(
      meta.nextCursor === null ||
      (typeof meta.nextCursor === "string" && !!meta.nextCursor)
    ) ||
    (meta.nextCursor !== null && (count === 0 || meta.total <= count))
  )
    throw new Error(
      "The saved history page could not be read. Refresh the history to continue.",
    );
  return meta;
}

export function validateHistoryPage<T extends { id: string }>(
  value: HistoryPage<T>,
  caseId: string,
  validate: (item: T) => void,
) {
  if (
    !value ||
    value.caseId !== caseId ||
    !Array.isArray(value.items) ||
    new Set(value.items.map((item) => item?.id)).size !== value.items.length
  )
    throw new Error(
      "The saved history response is unreadable or belongs to another case.",
    );
  historyMeta(value, value.items.length);
  value.items.forEach(validate);
  if (value.nextCursor !== null && value.nextCursor !== value.items.at(-1)?.id)
    throw new Error(
      "The saved history cursor does not match its records. Refresh the history.",
    );
}

export function mergeHistory<T extends { id: string }>(
  previous: T[],
  incoming: T[],
): T[] {
  const updates = new Map(incoming.map((item) => [item.id, item]));
  const seen = new Set(previous.map((item) => item.id));
  return [
    ...previous.map((item) => updates.get(item.id) ?? item),
    ...incoming.filter((item) => !seen.has(item.id)),
  ];
}

export function HistoryMore<T extends { id: string }>({
  caseId,
  path,
  page,
  loaded,
  label,
  disabled = false,
  validate,
  onPage,
  onRefresh,
}: {
  caseId: string;
  path: string;
  page?: HistoryPageMeta;
  loaded: number;
  label: string;
  disabled?: boolean;
  validate: (item: T) => void;
  onPage: (value: HistoryPage<T>) => void;
  onRefresh: () => void;
}) {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<Error | null>(null);
  const operation = useRef<AbortController | null>(null);
  useEffect(() => {
    setError(null);
    setBusy(false);
    return () => {
      operation.current?.abort();
      operation.current = null;
    };
  }, [path, caseId]);
  async function more() {
    if (!page?.nextCursor || disabled || operation.current) return;
    const controller = new AbortController();
    operation.current = controller;
    setBusy(true);
    setError(null);
    const timer = window.setTimeout(() => controller.abort(), 30000);
    try {
      const value = await api<HistoryPage<T>>(
        `${path}${path.includes("?") ? "&" : "?"}limit=${page.limit}&cursor=${encodeURIComponent(page.nextCursor)}`,
        { signal: controller.signal },
      );
      validateHistoryPage(value, caseId, validate);
      if (
        value.nextCursor === page.nextCursor ||
        value.items.some((item) => item.id === page.nextCursor)
      )
        throw new Error(
          "The history position did not advance. Refresh the history before continuing.",
        );
      if (!controller.signal.aborted) onPage(value);
    } catch (failure) {
      if (operation.current === controller)
        setError(
          controller.signal.aborted
            ? new Error(
                "Loading history timed out. Retry or refresh the history.",
              )
            : (failure as Error),
        );
    } finally {
      window.clearTimeout(timer);
      if (operation.current === controller) {
        operation.current = null;
        setBusy(false);
      }
    }
  }
  if (!page) return null;
  return (
    <div className="case-history-controls">
      <p className="payment-help">
        {Math.min(loaded, page.total)} of {page.total} {label} loaded
      </p>
      {page.nextCursor && (
        <button
          type="button"
          className="secondary"
          disabled={disabled || busy}
          onClick={() => void more()}
        >
          {busy ? "Loading history…" : `Load more ${label}`}
        </button>
      )}
      {error && (
        <div className="notice danger" role="alert">
          <span>{error.message}</span>
          {error instanceof ApiError && error.requestId && (
            <small>Request {error.requestId}</small>
          )}
          <button
            type="button"
            className="secondary"
            disabled={disabled || busy}
            onClick={() => {
              setError(null);
              onRefresh();
            }}
          >
            Refresh history
          </button>
        </div>
      )}
    </div>
  );
}
