import { useEffect, useRef, useState, type FormEvent } from "react";
import { ArrowRight, Download, LoaderCircle, RefreshCw } from "lucide-react";
import { api, ApiError, human } from "./api";
import type { ObpmImportReceipt, User } from "./types";
import { navigateLink } from "./routing";

type InquiryConfiguration = {
  enabled: boolean;
  mode: "SYNTHETIC_MOCK";
  referenceType: "PAYMENT_REFERENCE";
  examples: { reference: string; label: string }[];
};

export function ObpmInquiry({
  user,
  disabled = false,
  onImported,
  onBusyChange,
}: {
  user: User;
  disabled?: boolean;
  onImported: () => void;
  onBusyChange?: (busy: boolean) => void;
}) {
  const [configuration, setConfiguration] =
    useState<InquiryConfiguration | null>(null);
  const [configError, setConfigError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  const [reference, setReference] = useState("");
  const [result, setResult] = useState<ObpmImportReceipt | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [pending, setPending] = useState(false);
  const request = useRef<AbortController | null>(null);
  const canImport = ["ANALYST", "REVIEWER"].includes(user.role.toUpperCase());

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setConfigError(null);
    setConfiguration(null);
    api<InquiryConfiguration>("/obpm/inquiry", { signal: controller.signal })
      .then((value) => {
        if (controller.signal.aborted) return;
        if (
          typeof value?.enabled !== "boolean" ||
          value.mode !== "SYNTHETIC_MOCK" ||
          value.referenceType !== "PAYMENT_REFERENCE" ||
          !Array.isArray(value.examples) ||
          value.examples.some(
            (example) =>
              typeof example?.reference !== "string" ||
              typeof example?.label !== "string",
          )
        )
          throw new Error(
            "The inquiry configuration is unreadable. Refresh the configuration.",
          );
        setConfiguration(value);
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setConfigError(failure);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [revision]);
  useEffect(() => () => request.current?.abort(), []);

  function changeReference(value: string) {
    setReference(value);
    setResult(null);
    setError(null);
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!canImport || disabled || request.current || !configuration?.enabled)
      return;
    setError(null);
    setResult(null);
    const paymentReference = reference.trim();
    if (!paymentReference) {
      setError(
        new Error("Enter a payment reference or choose a mock example."),
      );
      return;
    }
    const controller = new AbortController();
    request.current = controller;
    setPending(true);
    onBusyChange?.(true);
    try {
      const receipt = await api<ObpmImportReceipt>("/obpm/inquiries", {
        method: "POST",
        body: JSON.stringify({ paymentReference }),
        signal: controller.signal,
      });
      if (controller.signal.aborted) return;
      if (
        !receipt?.caseId ||
        !receipt.importId ||
        !["CREATED", "UPDATED", "UNCHANGED"].includes(receipt.status) ||
        !Number.isSafeInteger(receipt.evidenceVersion) ||
        receipt.evidenceVersion < 1
      )
        throw new Error(
          "The API returned an unreadable import receipt. Refresh recent imports before retrying.",
        );
      setResult(receipt);
      onImported();
    } catch (failure) {
      if (!controller.signal.aborted) setError(failure as Error);
    } finally {
      if (!controller.signal.aborted) {
        setPending(false);
        onBusyChange?.(false);
      }
      if (request.current === controller) request.current = null;
    }
  }

  return (
    <section className="obpm-inquiry" aria-labelledby="obpm-inquiry-title">
      <div className="section-title">
        <h3 id="obpm-inquiry-title">
          <Download size={16} /> Fetch from mock inquiry API
        </h3>
        <span className="badge">Original dummy data</span>
      </div>
      <p className="muted">
        Fetch one synthetic NEFT payment from the local mock service and import
        its evidence. This demo covers ECA queue and request evidence. No Oracle
        connection is configured.
      </p>
      {loading && (
        <p className="muted" role="status">
          Loading inquiry configuration…
        </p>
      )}
      {configError && (
        <div className="notice danger" role="alert">
          <div>
            <strong>{configError.message}</strong>
            {configError instanceof ApiError && configError.requestId && (
              <small>Request {configError.requestId}</small>
            )}
          </div>
        </div>
      )}
      {!loading && (
        <div className="obpm-inquiry-configuration">
          <p className="muted">
            {configuration?.enabled
              ? "Mock connector configured. Service availability is checked when you fetch."
              : configuration
                ? "The mock inquiry connector is disabled in this deployment. Sample snapshot import remains available below."
                : "Inquiry configuration is unavailable."}
          </p>
          <button
            type="button"
            className="text-button"
            disabled={disabled || pending}
            onClick={() => setRevision((value) => value + 1)}
          >
            <RefreshCw size={14} /> Refresh configuration
          </button>
        </div>
      )}
      <form onSubmit={submit}>
        <label htmlFor="obpm-inquiry-example">Mock inquiry example</label>
        <select
          id="obpm-inquiry-example"
          disabled={
            !canImport || disabled || pending || !configuration?.enabled
          }
          value={
            configuration?.examples.some(
              (example) => example.reference === reference,
            )
              ? reference
              : ""
          }
          onChange={(event) => changeReference(event.target.value)}
        >
          <option value="">
            Choose an example or enter a payment reference
          </option>
          {configuration?.examples.map((example) => (
            <option key={example.reference} value={example.reference}>
              {example.reference} · {example.label}
            </option>
          ))}
        </select>
        <label htmlFor="obpm-inquiry-reference">Payment reference</label>
        <input
          id="obpm-inquiry-reference"
          className="mono"
          value={reference}
          maxLength={100}
          placeholder="MOCK-NEFT-1001"
          autoComplete="off"
          spellCheck={false}
          disabled={disabled || pending || !configuration?.enabled}
          readOnly={!canImport}
          onChange={(event) => changeReference(event.target.value)}
        />
        {error && (
          <>
            <div className="notice danger" role="alert">
              <div>
                <strong>{error.message}</strong>
                {error instanceof ApiError && error.requestId && (
                  <small>Request {error.requestId}</small>
                )}
              </div>
            </div>
            <p className="muted">
              No completed import receipt was received. After a connection
              error, refresh recent imports before retrying.
            </p>
          </>
        )}
        {result && (
          <div className="notice success" role="status">
            <div>
              <strong>
                {human(result.status)} · {result.caseId} · Evidence v
                {result.evidenceVersion}
              </strong>
              <small>
                {result.status === "UNCHANGED"
                  ? "This snapshot was already imported. No evidence version was added."
                  : result.status === "UPDATED"
                    ? "New evidence was saved and the case reopened."
                    : "The fetched synthetic case is ready in the queue."}
              </small>
              <a
                className="text-button"
                href={`/cases/${encodeURIComponent(result.caseId)}`}
                onClick={navigateLink}
              >
                Open fetched case <ArrowRight size={14} />
              </a>
            </div>
          </div>
        )}
        {canImport ? (
          <button
            className="primary"
            type="submit"
            disabled={disabled || pending || !configuration?.enabled}
          >
            {pending ? (
              <LoaderCircle size={16} className="spin" />
            ) : (
              <Download size={16} />
            )}
            {pending ? "Fetching and importing…" : "Fetch and import"}
          </button>
        ) : (
          <p className="notice neutral">
            An analyst or reviewer can fetch and import evidence. Your role is
            read-only.
          </p>
        )}
        {pending && (
          <p className="muted" role="status">
            Waiting for the import receipt. Open the case after confirmation to
            run an investigation.
          </p>
        )}
      </form>
    </section>
  );
}
