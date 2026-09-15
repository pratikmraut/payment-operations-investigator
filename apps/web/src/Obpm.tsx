import {
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type ReactNode,
} from "react";
import {
  ArrowRight,
  Database,
  LoaderCircle,
  RefreshCw,
  Upload,
  X,
} from "lucide-react";
import { api, ApiError, human, money } from "./api";
import { ObpmInquiry } from "./ObpmInquiry";
import { navigateLink } from "./routing";
import type {
  CaseDetail,
  EvidenceVersion,
  ObpmImportReceipt,
  ObpmSnapshot,
  User,
} from "./types";

function Failure({ error }: { error: Error }) {
  return (
    <div className="notice danger" role="alert">
      <div>
        <strong>{error.message}</strong>
        {error instanceof ApiError && error.requestId && (
          <small>Request {error.requestId}</small>
        )}
      </div>
    </div>
  );
}

function useObpmRead<T>(path: string, revision = 0) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    api<T>(path, { signal: controller.signal })
      .then((result) => {
        if (!controller.signal.aborted) setData(result);
      })
      .catch((failure) => {
        if (!controller.signal.aborted) setError(failure);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [path, revision]);
  return { data, error, loading };
}

type Sample = {
  id: string;
  title: string;
  description: string;
  payload: ObpmSnapshot;
};

export function ObpmImport({
  user,
  onImported,
  onClose,
}: {
  user: User;
  onImported: () => void;
  onClose: () => void;
}) {
  const [revision, setRevision] = useState(0);
  const samples = useObpmRead<{ items: Sample[] }>("/obpm/samples", revision);
  const receipts = useObpmRead<{ items: ObpmImportReceipt[] }>(
    "/obpm/imports",
    revision,
  );
  const [sampleId, setSampleId] = useState("");
  const [payload, setPayload] = useState("");
  const [result, setResult] = useState<ObpmImportReceipt | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [pending, setPending] = useState(false);
  const [inquiryPending, setInquiryPending] = useState(false);
  const busy = pending || inquiryPending;
  const request = useRef<AbortController | null>(null);
  const canImport = ["ANALYST", "REVIEWER"].includes(user.role.toUpperCase());
  useEffect(() => () => request.current?.abort(), []);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!canImport || busy || request.current) return;
    setError(null);
    setResult(null);
    let parsed: unknown;
    try {
      parsed = JSON.parse(payload);
    } catch {
      setError(
        new Error("Snapshot JSON is not valid. Correct it before importing."),
      );
      return;
    }
    if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
      setError(new Error("Provide one synthetic snapshot JSON object."));
      return;
    }
    const controller = new AbortController();
    request.current = controller;
    setPending(true);
    try {
      const receipt = await api<ObpmImportReceipt>("/obpm/imports", {
        method: "POST",
        body: JSON.stringify(parsed),
        signal: controller.signal,
      });
      if (controller.signal.aborted) return;
      setResult(receipt);
      setRevision((value) => value + 1);
      onImported();
    } catch (failure) {
      if (!controller.signal.aborted) setError(failure as Error);
    } finally {
      if (!controller.signal.aborted) setPending(false);
      if (request.current === controller) request.current = null;
    }
  }

  return (
    <section className="panel obpm-import" aria-labelledby="obpm-import-title">
      <div className="section-title">
        <h2 id="obpm-import-title">
          <Upload size={17} />
          Import synthetic NEFT evidence
        </h2>
        <button
          className="icon-button secondary"
          aria-label="Close import section"
          onClick={onClose}
          disabled={busy}
        >
          <X size={17} />
        </button>
      </div>
      <p className="muted">
        Original synthetic snapshots only. Importing adds or refreshes an app
        case; it does not connect to Oracle or execute a payment.
      </p>
      <ObpmInquiry
        user={user}
        disabled={pending}
        onBusyChange={setInquiryPending}
        onImported={() => {
          setResult(null);
          setRevision((value) => value + 1);
          onImported();
        }}
      />
      <div className="obpm-import-grid">
        <form onSubmit={submit}>
          {samples.error && <Failure error={samples.error} />}
          <label htmlFor="obpm-sample">Synthetic sample</label>
          <select
            id="obpm-sample"
            value={sampleId}
            disabled={samples.loading || busy}
            onChange={(event) => {
              const id = event.target.value;
              setSampleId(id);
              const sample = samples.data?.items.find((item) => item.id === id);
              if (sample) setPayload(JSON.stringify(sample.payload, null, 2));
              setResult(null);
              setError(null);
            }}
          >
            <option value="">
              {samples.loading
                ? "Loading samples…"
                : "Choose a sample or paste a snapshot"}
            </option>
            {samples.data?.items.map((sample) => (
              <option key={sample.id} value={sample.id}>
                {sample.title}
              </option>
            ))}
          </select>
          {!samples.loading && samples.data?.items.length === 0 && (
            <p className="muted">No samples are configured.</p>
          )}
          {sampleId && (
            <p className="muted">
              {
                samples.data?.items.find((item) => item.id === sampleId)
                  ?.description
              }
            </p>
          )}
          <label htmlFor="obpm-payload">Snapshot JSON</label>
          <textarea
            id="obpm-payload"
            className="mono"
            rows={10}
            value={payload}
            readOnly={!canImport}
            disabled={busy}
            placeholder="Select an original sample to inspect its snapshot."
            onChange={(event) => {
              setPayload(event.target.value);
              setResult(null);
              setError(null);
            }}
            spellCheck={false}
          />
          <p className="muted">
            A later source cutoff creates a new evidence version and reopens the
            case. Exact duplicates are unchanged. Earlier snapshots are
            rejected.
          </p>
          {error && (
            <>
              <Failure error={error} />
              <p className="muted">
                After an uncertain connection error, refresh recent imports
                before submitting again.
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
                  {result.status === "UPDATED"
                    ? "A new immutable snapshot was saved and the case reopened."
                    : result.status === "UNCHANGED"
                      ? "This snapshot was already imported. No evidence version was added."
                      : "The synthetic case is ready in the queue."}
                </small>
                <a
                  className="text-button"
                  href={`/cases/${encodeURIComponent(result.caseId)}`}
                  onClick={navigateLink}
                >
                  Open imported case <ArrowRight size={14} />
                </a>
              </div>
            </div>
          )}
          {canImport ? (
            <button
              className="primary"
              type="submit"
              disabled={!payload.trim() || busy}
            >
              {pending ? (
                <LoaderCircle size={16} className="spin" />
              ) : (
                <Upload size={16} />
              )}
              {pending ? "Importing snapshot…" : "Import snapshot"}
            </button>
          ) : (
            <p className="notice neutral">
              An analyst or reviewer can import snapshots. Your role is
              read-only.
            </p>
          )}
          {pending && (
            <p className="muted" role="status">
              Waiting for the API receipt. The queue is unchanged until the
              import is confirmed.
            </p>
          )}
        </form>
        <div className="obpm-receipts">
          <div className="section-title">
            <h3>Recent imports</h3>
            <button
              className="text-button"
              disabled={busy || receipts.loading}
              onClick={() => setRevision((value) => value + 1)}
            >
              <RefreshCw size={14} />
              Refresh imports
            </button>
          </div>
          {receipts.error && <Failure error={receipts.error} />}
          {receipts.loading && (
            <p className="muted" role="status">
              Loading import receipts…
            </p>
          )}
          {!receipts.loading &&
            !receipts.error &&
            !receipts.data?.items.length && (
              <p className="muted">No synthetic NEFT imports yet.</p>
            )}
          {receipts.data?.items.map((receipt) => (
            <article key={receipt.importId} className="obpm-receipt">
              <div>
                <a href={`/cases/${encodeURIComponent(receipt.caseId)}`} onClick={navigateLink}>
                  {receipt.caseId}
                </a>
                <span className="badge">{human(receipt.status)}</span>
              </div>
              <p>
                Evidence v{receipt.evidenceVersion} · Case v
                {receipt.caseVersion}
              </p>
              {receipt.importedAt && <small>{receipt.importedAt}</small>}
              <small className="mono">{receipt.importId}</small>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}
function known(value: string | null | undefined) {
  return value == null || value === "" ? "Unknown · not supplied" : value;
}
export function elapsedAt(
  start: string | null | undefined,
  cutoff: string | null | undefined,
) {
  if (!start || !cutoff) return "Unknown · timestamp missing";
  const duration = Date.parse(cutoff) - Date.parse(start);
  if (!Number.isFinite(duration) || duration < 0)
    return "Unknown · timestamps inconsistent";
  const seconds = Math.floor(duration / 1000);
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  return `${days ? `${days}d ` : ""}${hours ? `${hours}h ` : ""}${minutes}m ${seconds % 60}s`;
}

const coverageLabels = {
  queueRecords: "Queue records",
  messages: "Messages",
  externalCoreResponses: "External core responses",
  accountingEntries: "Accounting entries",
};

export function ObpmEvidence({
  item,
  focusedId,
  view,
  revision = 0,
}: {
  item: CaseDetail;
  focusedId: string | null;
  view: "records" | "coverage";
  revision?: number;
}) {
  const snapshot = item.obpm;
  const [viewedAt] = useState(() => new Date().toISOString());
  if (!snapshot)
    return (
      <div className="notice danger" role="alert">
        The banking snapshot is unavailable. Refresh the case before
        investigating.
      </div>
    );
  return (
    <div className="obpm-evidence">
      <div className="section-title">
        <h2>
          <Database size={17} />
          {view === "records" ? "Bank evidence" : "Source coverage"}
        </h2>
        <span className="tiny muted">
          Synthetic · Evidence v{item.evidenceVersion ?? "unknown"}
        </span>
      </div>
      {focusedId && (
        <div className="notice info">
          <span>
            Referenced evidence: <code>{focusedId}</code>. This panel shows the
            current imported snapshot; historical investigations retain their
            original evidence.
          </span>
        </div>
      )}
      {view === "records" ? (
        <>
          <div className="notice neutral">
            A queue timeout records a processing exception. It does not
            establish debit, external-core outcome, beneficiary credit or
            settlement.
          </div>
          <h3>Payment and source identity</h3>
          <dl className="obpm-fields">
            <Field label="Native payment status">
              <code>{known(snapshot.payment.nativeTransactionStatus)}</code>
            </Field>
            <Field label="Status evidence">
              {snapshot.payment.statusUnavailableReason ||
                (snapshot.payment.nativeTransactionStatus == null
                  ? "No status supplied"
                  : "Native source value; separate from case status")}
            </Field>
            <Field label="Payment amount">
              {money(snapshot.payment.amountMinor, snapshot.payment.currency)}
            </Field>
            <Field label="Source decimal (INR)">
              <code>{snapshot.payment.sourceAmountDecimal}</code>
            </Field>
            <Field label="Native payment reference">
              <code>{snapshot.payment.sourcePaymentId}</code>
            </Field>
            <Field label="Direction / rail">
              {snapshot.payment.direction} / {snapshot.payment.rail}
            </Field>
            <Field label="Activation date">
              {snapshot.payment.activationDate}
            </Field>
            <Field label="Source created at (UTC)">
              {snapshot.payment.createdAt}
            </Field>
            <Field label="Deployment">
              <code>{snapshot.source.deploymentId}</code>
            </Field>
            <Field label="Release family / maintenance">
              {snapshot.source.releaseFamily} /{" "}
              {known(snapshot.source.exactMaintenanceRelease)}
            </Field>
            <Field label="Host / branch">
              <code>
                {snapshot.source.hostCode} / {snapshot.source.branchCode}
              </code>
            </Field>
            <Field label="Snapshot extracted at (UTC)">
              {snapshot.extractedAt}
            </Field>
          </dl>
          <div className="evidence-group">
            <h3>
              Queue records <span>{snapshot.queueRecords.length}</span>
            </h3>
            <p className="muted">
              Raw source codes are preserved. Current queue age is measured at
              the extraction cutoff, not at the present time. All timestamps
              below are UTC.
            </p>
            {!snapshot.queueRecords.length && (
              <p className="muted">
                No queue records supplied. Check the declared source coverage.
              </p>
            )}
            {snapshot.queueRecords.map((row) => (
              <article
                key={row.evidenceId}
                className={`obpm-record ${row.evidenceId === focusedId ? "focused" : ""}`}
              >
                <div className="section-title">
                  <h4>
                    <code>{row.evidenceId}</code>
                  </h4>
                  <span className="badge">
                    {row.isCurrentQueueRecord
                      ? "Current at snapshot"
                      : "Historical record"}
                  </span>
                </div>
                <dl className="obpm-fields">
                  <Field label="Queue / response codes">
                    <code>
                      {known(row.nativeQueueCode)} /{" "}
                      {known(row.nativeResponseStatus)}
                    </code>
                  </Field>
                  <Field label="Queue reference">
                    <code>{row.queueReference}</code>
                  </Field>
                  <Field label="Request attempt">
                    <code>{row.requestAttemptId}</code>
                  </Field>
                  <Field label="Payment reference">
                    <code>{row.sourcePaymentId}</code>
                  </Field>
                  <Field label="Entered at">{known(row.enteredAt)}</Field>
                  <Field label="Exited at">{known(row.exitedAt)}</Field>
                  <Field label="Observed at">{row.observedAt}</Field>
                  <Field
                    label={
                      row.isCurrentQueueRecord
                        ? "Queue age at snapshot"
                        : "Recorded queue duration"
                    }
                  >
                    {elapsedAt(
                      row.enteredAt,
                      row.isCurrentQueueRecord
                        ? snapshot.extractedAt
                        : row.exitedAt,
                    )}
                  </Field>
                </dl>
              </article>
            ))}
          </div>
          <div className="evidence-group">
            <h3>
              External request attempts{" "}
              <span>{snapshot.externalRequestAttempts.length}</span>
            </h3>
            {!snapshot.externalRequestAttempts.length && (
              <p className="muted">
                No request attempts supplied. This does not prove no request
                occurred.
              </p>
            )}
            {snapshot.externalRequestAttempts.map((row) => (
              <article
                key={row.evidenceId}
                className={`obpm-record ${row.evidenceId === focusedId ? "focused" : ""}`}
              >
                <h4>
                  <code>{row.evidenceId}</code>
                </h4>
                <dl className="obpm-fields">
                  <Field label="Request type">{row.requestType}</Field>
                  <Field label="Request attempt">
                    <code>{row.requestAttemptId}</code>
                  </Field>
                  <Field label="Payment reference">
                    <code>{row.sourcePaymentId}</code>
                  </Field>
                  <Field label="Requested at (UTC)">{row.requestedAt}</Field>
                  <Field label="Timeout recorded at (UTC)">
                    {known(row.timeoutRecordedAt)}
                  </Field>
                  <Field label="External system final outcome">
                    <code>{known(row.externalSystemFinalOutcome)}</code>
                  </Field>
                </dl>
              </article>
            ))}
          </div>
        </>
      ) : (
        <>
          <dl className="obpm-fields">
            <Field label="Snapshot reference">
              <code>{snapshot.snapshotId}</code>
            </Field>
            <Field label="Mapping version">
              <code>{snapshot.mappingVersion}</code>
            </Field>
            <Field label="Extracted at (UTC)">{snapshot.extractedAt}</Field>
            <Field label="Elapsed since extraction at view load">
              {elapsedAt(snapshot.extractedAt, viewedAt)}
            </Field>
            <Field label="View loaded at (UTC)">{viewedAt}</Field>
            <Field label="Evidence hash">
              <code>{item.evidenceHash || "Not supplied"}</code>
            </Field>
          </dl>
          <p className="notice neutral">
            Complete means complete within the declared source query scope and
            cutoff. Missing accounting or message records are unavailable
            evidence, not zero balances or proof of settlement.
          </p>
          {(Object.keys(coverageLabels) as (keyof typeof coverageLabels)[]).map(
            (key) => {
              const coverage = snapshot.sourceCoverage[key];
              return (
                <article className="obpm-record" key={key}>
                  <div className="section-title">
                    <h3>{coverageLabels[key]}</h3>
                    <span className="badge">
                      {coverage ? human(coverage.status) : "Unknown"}
                    </span>
                  </div>
                  <dl className="obpm-fields">
                    <Field label="Declared query scope">
                      {coverage?.scope || "Not supplied"}
                    </Field>
                    <Field label="Coverage as of (UTC)">
                      {coverage?.asOf || "Not supplied"}
                    </Field>
                    <Field label="All pages included">
                      {coverage?.paginationComplete === true
                        ? "Yes, within this scope"
                        : coverage?.paginationComplete === false
                          ? "No"
                          : "Not established"}
                    </Field>
                    {coverage?.reason && (
                      <Field label="Coverage reason">{coverage.reason}</Field>
                    )}
                  </dl>
                </article>
              );
            },
          )}
          <ObpmVersions
            caseId={item.id}
            currentVersion={item.evidenceVersion}
            revision={revision}
          />
          <details className="event-attributes">
            <summary>Normalized source snapshot</summary>
            <pre>{JSON.stringify(snapshot, null, 2)}</pre>
          </details>
        </>
      )}
    </div>
  );
}

function ObpmVersions({
  caseId,
  currentVersion,
  revision,
}: {
  caseId: string;
  currentVersion?: number;
  revision: number;
}) {
  const versions = useObpmRead<{ items: EvidenceVersion[] }>(
    `/cases/${encodeURIComponent(caseId)}/evidence-versions`,
    revision,
  );
  return (
    <div className="evidence-group">
      <h3>Evidence versions</h3>
      <p className="muted">
        Immutable import summaries. Historical investigations keep their
        original snapshot; refreshing source evidence requires a new
        investigation and review.
      </p>
      {versions.error && <Failure error={versions.error} />}
      {versions.loading && (
        <p role="status" className="muted">
          Loading evidence versions…
        </p>
      )}
      {!versions.loading && !versions.error && !versions.data?.items.length && (
        <p className="muted">No evidence version summaries were returned.</p>
      )}
      {[...(versions.data?.items || [])]
        .sort((a, b) => b.evidenceVersion - a.evidenceVersion)
        .map((version) => (
          <article className="obpm-record" key={version.evidenceVersion}>
            <h4>
              Evidence v{version.evidenceVersion}
              {version.evidenceVersion === currentVersion && " · Current"}
            </h4>
            <dl className="obpm-fields">
              <Field label="Source snapshot">
                <code>{version.sourceSnapshotId}</code>
              </Field>
              <Field label="Extracted at (UTC)">{version.extractedAt}</Field>
              <Field label="Imported at (UTC)">{version.importedAt}</Field>
              <Field label="Evidence hash">
                <code>{version.evidenceHash}</code>
              </Field>
            </dl>
          </article>
        ))}
    </div>
  );
}
