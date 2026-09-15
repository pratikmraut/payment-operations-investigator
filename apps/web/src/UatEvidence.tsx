import { useEffect, useRef, useState, type FormEvent } from "react";
import {
  ArrowUpRight,
  BookOpen,
  Clock3,
  Database,
  FileText,
  LoaderCircle,
  MessageSquare,
  RefreshCw,
  Send,
  Sparkles,
  TriangleAlert,
} from "lucide-react";
import { api, ApiError, human } from "./api";
import type { User } from "./types";

type UatDocument = {
  id: string;
  kind: "evidence" | "knowledge";
  title: string;
  content: string;
  source: { file: string; sheet?: string; range?: string; locator?: string };
};
type UatSnapshot = {
  snapshotId: string;
  classification: "UAT";
  title: string;
  paymentReference: string | null;
  utr: string | null;
  amount: string | null;
  currency: string | null;
  evidenceHash: string;
  coverage: { name: string; rowCount: number; completion: string }[];
  warnings: string[];
};
type UatBundle = UatSnapshot & { documents: UatDocument[] };
type UatAnswer = {
  answerId: string;
  question: string;
  snapshotId: string;
  evidenceHash: string;
  answer: string;
  answerComposition?: "joined-model-claims";
  claims: { text: string; evidenceIds: string[] }[];
  unknowns: string[];
  nextChecks: string[];
  citations: UatDocument[];
  model: {
    provider: "ollama";
    name: string;
    actualCalls: number;
    promptTokens: number | null;
    completionTokens: number | null;
    durationMs: number;
  };
  generatedAt: string;
  mode: "model-generated";
  retrieval: { method: string; documentIds: string[] };
};

const failureGuidance: Record<string, string> = {
  UAT_MODEL_TIMEOUT:
    "Your question is still here. Wait briefly, then submit it once more. If it times out again, check the local model service.",
  GATEWAY_TIMEOUT:
    "No completed answer was received. Your question is still here. Wait briefly before retrying; the local model may still be processing the earlier request.",
  UAT_MODEL_BUSY:
    "Another model request is still running. Wait for it to finish, then submit this question again.",
  UAT_MODEL_UNAVAILABLE:
    "Check that the local model service is running, then retry this question.",
  INVALID_UAT_ANSWER:
    "The generated response failed validation. You can retry this question. If the error repeats, use the request ID to inspect the local model logs.",
};
function Failure({ error }: { error: Error }) {
  const guidance =
    error instanceof ApiError ? failureGuidance[error.code] : undefined;
  return (
    <div className="notice danger" role="alert">
      <TriangleAlert size={18} />
      <div>
        <strong>{error.message}</strong>
        {guidance && <p className="uat-failure-guidance">{guidance}</p>}
        {error instanceof ApiError && error.requestId && (
          <small>Request {error.requestId}</small>
        )}
      </div>
    </div>
  );
}
const isStrings = (value: unknown): value is string[] =>
  Array.isArray(value) && value.every((item) => typeof item === "string");
const known = (value: string | null | undefined) =>
  value == null || value === "" ? "Not supplied" : value;
function validSnapshot(value: UatSnapshot) {
  return (
    value?.classification === "UAT" &&
    typeof value.snapshotId === "string" &&
    !!value.snapshotId &&
    typeof value.title === "string" &&
    typeof value.evidenceHash === "string" &&
    !!value.evidenceHash &&
    [value.paymentReference, value.utr, value.amount, value.currency].every(
      (field) => field == null || typeof field === "string",
    ) &&
    Array.isArray(value.coverage) &&
    value.coverage.every(
      (row) =>
        typeof row?.name === "string" &&
        Number.isSafeInteger(row.rowCount) &&
        row.rowCount >= 0 &&
        typeof row.completion === "string",
    ) &&
    isStrings(value.warnings)
  );
}
function validDocument(value: UatDocument) {
  return (
    typeof value?.id === "string" &&
    !!value.id &&
    ["evidence", "knowledge"].includes(value.kind) &&
    typeof value.title === "string" &&
    typeof value.content === "string" &&
    typeof value.source?.file === "string"
  );
}
function validateAnswer(
  value: UatAnswer,
  bundle: UatBundle | null,
  question: string,
) {
  const available = new Set(bundle?.documents.map((doc) => doc.id));
  if (
    !value ||
    (bundle &&
      (value.snapshotId !== bundle.snapshotId ||
        value.evidenceHash !== bundle.evidenceHash)) ||
    value.question !== question ||
    value.mode !== "model-generated"
  )
    throw new Error(
      "The answer does not match the selected evidence and question. Refresh the export before trying again.",
    );
  if (
    typeof value.answerId !== "string" ||
    typeof value.answer !== "string" ||
    !value.answer.trim() ||
    typeof value.snapshotId !== "string" ||
    typeof value.evidenceHash !== "string" ||
    typeof value.question !== "string" ||
    !Array.isArray(value.citations) ||
    !value.citations.every(
      (doc) => validDocument(doc) && (!bundle || available.has(doc.id)),
    ) ||
    !isStrings(value.unknowns) ||
    !isStrings(value.nextChecks) ||
    !Array.isArray(value.claims) ||
    !value.claims.every(
      (claim) =>
        typeof claim?.text === "string" && isStrings(claim.evidenceIds),
    ) ||
    typeof value.generatedAt !== "string" ||
    typeof value.retrieval?.method !== "string" ||
    !isStrings(value.retrieval.documentIds) ||
    (bundle && !value.retrieval.documentIds.every((id) => available.has(id)))
  )
    throw new Error(
      "The API returned an invalid answer or source reference. No answer has been accepted.",
    );
  const cited = new Set(value.citations.map((doc) => doc.id));
  if (
    value.answerComposition !== undefined &&
    (value.answerComposition !== "joined-model-claims" ||
      value.claims.length === 0 ||
      value.answer !== value.claims.map((claim) => claim.text).join("\n\n") ||
      value.claims.some(
        (claim) => !claim.text.trim() || claim.evidenceIds.length === 0,
      ))
  )
    throw new Error(
      "The answer composition does not match its cited model claims. No answer has been accepted.",
    );
  if (
    !value.claims.every((claim) =>
      claim.evidenceIds.every((id) => cited.has(id)),
    )
  )
    throw new Error(
      "The answer contains a claim with an unavailable source reference. No answer has been accepted.",
    );
  if (
    value.model?.provider !== "ollama" ||
    typeof value.model.name !== "string" ||
    !value.model.name ||
    !Number.isSafeInteger(value.model.actualCalls) ||
    value.model.actualCalls < 1 ||
    ![value.model.promptTokens, value.model.completionTokens].every(
      (count) => count === null || (Number.isSafeInteger(count) && count >= 0),
    ) ||
    !Number.isFinite(value.model.durationMs) ||
    value.model.durationMs < 0
  )
    throw new Error(
      "The API did not confirm a completed model call. No generated answer is available.",
    );
}

export function UatEvidencePage({ user }: { user: User }) {
  const [catalog, setCatalog] = useState<{
    enabled: boolean;
    items: UatSnapshot[];
  } | null>(null);
  const [selected, setSelected] = useState("");
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setCatalog(null);
    setError(null);
    api<{ enabled: boolean; items: UatSnapshot[] }>("/uat/snapshots", {
      signal: controller.signal,
    })
      .then((result) => {
        if (controller.signal.aborted) return;
        if (
          typeof result?.enabled !== "boolean" ||
          !Array.isArray(result.items) ||
          !result.items.every(validSnapshot)
        )
          throw new Error(
            "The evidence export list is unreadable. Refresh the available exports.",
          );
        setCatalog(result);
        setSelected((current) =>
          result.items.some((item) => item.snapshotId === current)
            ? current
            : result.items[0]?.snapshotId || "",
        );
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setError(failure);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [revision]);
  return (
    <div className="uat-page">
      <div className="page-heading">
        <div>
          <div className="eyebrow">EXPORTED BANK EVIDENCE</div>
          <h1>Evidence Q&A</h1>
          <p>
            Ask a local language model about a selected export. Inspect its
            sources and the evidence it still needs.
          </p>
        </div>
        <button
          className="secondary"
          disabled={loading}
          onClick={() => setRevision((value) => value + 1)}
        >
          <RefreshCw size={16} /> Refresh exports
        </button>
      </div>
      <div
        className="notice danger"
        role="note"
        aria-label="Experimental answer warning"
      >
        <TriangleAlert size={18} aria-hidden="true" />
        <div>
          <strong>
            Experimental answers: live validation found unsupported claims.
          </strong>{" "}
          Verify field values and conclusions against the cited records.
        </div>
      </div>
      {error && <Failure error={error} />}
      {loading && (
        <p className="uat-loading" role="status">
          <LoaderCircle className="spin" size={18} /> Loading available evidence
          exports…
        </p>
      )}
      {!loading && catalog && !catalog.enabled && (
        <div className="notice neutral">
          Evidence access is disabled in this deployment.
        </div>
      )}
      {!loading && catalog?.enabled && !catalog.items.length && (
        <div className="panel uat-empty">
          <Database size={28} />
          <h2>No evidence exports available</h2>
          <p>
            An authorized export snapshot must be staged before you can ask a
            question.
          </p>
        </div>
      )}
      {catalog?.enabled && catalog.items.length > 0 && (
        <>
          <div className="panel uat-selection">
            <label htmlFor="uat-snapshot">Evidence snapshot</label>
            <select
              id="uat-snapshot"
              value={selected}
              onChange={(event) => setSelected(event.target.value)}
            >
              {catalog.items.map((item) => (
                <option key={item.snapshotId} value={item.snapshotId}>
                  {item.title} · {known(item.paymentReference)}
                </option>
              ))}
            </select>
            <span className="badge">Imported evidence</span>
          </div>
          {selected && (
            <UatWorkspace
              key={`${selected}:${revision}`}
              snapshotId={selected}
              user={user}
            />
          )}
        </>
      )}
    </div>
  );
}

function UatWorkspace({
  snapshotId,
  user,
}: {
  snapshotId: string;
  user: User;
}) {
  const [bundle, setBundle] = useState<UatBundle | null>(null);
  const [loadError, setLoadError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(true);
  const [question, setQuestion] = useState("");
  const [answer, setAnswer] = useState<UatAnswer | null>(null);
  const [history, setHistory] = useState<UatAnswer[]>([]);
  const [historyError, setHistoryError] = useState<Error | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [pending, setPending] = useState(false);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [focusedDocument, setFocusedDocument] = useState<string | null>(null);
  const request = useRef<AbortController | null>(null);
  const documentElements = useRef(new Map<string, HTMLDetailsElement>());
  const canAsk = ["ANALYST", "REVIEWER"].includes(user.role.toUpperCase());
  useEffect(() => {
    if (!pending) return;
    const startedAt = performance.now();
    const timer = window.setInterval(() => {
      setElapsedSeconds(Math.floor((performance.now() - startedAt) / 1000));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [pending]);
  useEffect(() => {
    const controller = new AbortController();
    api<UatBundle>(`/uat/snapshots/${encodeURIComponent(snapshotId)}`, {
      signal: controller.signal,
    })
      .then((value) => {
        if (controller.signal.aborted) return;
        if (
          !validSnapshot(value) ||
          value.snapshotId !== snapshotId ||
          !Array.isArray(value.documents) ||
          !value.documents.every(validDocument) ||
          new Set(value.documents.map((doc) => doc.id)).size !==
            value.documents.length
        )
          throw new Error(
            "The selected export is incomplete or unreadable. Refresh the available exports.",
          );
        setBundle(value);
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setLoadError(failure);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => {
      controller.abort();
      request.current?.abort();
    };
  }, [snapshotId]);
  useEffect(() => {
    if (!bundle) return;
    const controller = new AbortController();
    setHistoryLoading(true);
    api<{ items: UatAnswer[] }>(
      `/uat/snapshots/${encodeURIComponent(snapshotId)}/questions`,
      { signal: controller.signal },
    )
      .then((result) => {
        if (controller.signal.aborted) return;
        if (!Array.isArray(result?.items))
          throw new Error("Saved answers could not be read.");
        result.items.forEach((item) => {
          if (item.snapshotId !== snapshotId)
            throw new Error("A saved answer refers to a different snapshot.");
          validateAnswer(
            item,
            item.evidenceHash === bundle.evidenceHash ? bundle : null,
            item.question,
          );
        });
        setHistory((current) =>
          [
            ...new Map(
              [...current, ...result.items].map((item) => [
                item.answerId,
                item,
              ]),
            ).values(),
          ].slice(0, 50),
        );
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setHistoryError(failure);
      })
      .finally(() => {
        if (!controller.signal.aborted) setHistoryLoading(false);
      });
    return () => controller.abort();
  }, [bundle, snapshotId]);
  function revealDocument(id: string) {
    const node =
      documentElements.current.get(`answer:${id}`) ||
      documentElements.current.get(id);
    if (node) {
      node.open = true;
      node.scrollIntoView?.({ behavior: "smooth", block: "center" });
      setFocusedDocument(id);
    }
  }
  function editQuestion(value: string) {
    setQuestion(value);
    setAnswer(null);
    setError(null);
  }
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!canAsk || !bundle || request.current) return;
    const submittedQuestion = question.trim();
    setError(null);
    setAnswer(null);
    if (!submittedQuestion || submittedQuestion.length > 2000) {
      setError(new Error("Enter a question between 1 and 2,000 characters."));
      return;
    }
    const controller = new AbortController();
    request.current = controller;
    setElapsedSeconds(0);
    setPending(true);
    try {
      const result = await api<UatAnswer>(
        `/uat/snapshots/${encodeURIComponent(snapshotId)}/questions`,
        {
          method: "POST",
          body: JSON.stringify({
            question: submittedQuestion,
            evidenceHash: bundle.evidenceHash,
          }),
          signal: controller.signal,
        },
      );
      if (controller.signal.aborted) return;
      validateAnswer(result, bundle, submittedQuestion);
      if (result.answerComposition !== "joined-model-claims")
        throw new Error(
          "The API did not return the current cited-claims answer format. Refresh before trying again.",
        );
      setAnswer(result);
      setHistory((current) =>
        [
          result,
          ...current.filter((item) => item.answerId !== result.answerId),
        ].slice(0, 50),
      );
    } catch (failure) {
      if (!controller.signal.aborted) setError(failure as Error);
    } finally {
      if (!controller.signal.aborted) setPending(false);
      if (request.current === controller) request.current = null;
    }
  }
  if (loading)
    return (
      <p className="uat-loading" role="status">
        <LoaderCircle className="spin" size={18} /> Loading selected evidence…
      </p>
    );
  if (loadError) return <Failure error={loadError} />;
  if (!bundle) return null;
  return (
    <>
      <div className="uat-layout">
        <aside
          className="panel uat-source-panel"
          aria-label="Selected export details"
        >
          <div className="section-title">
            <h2>
              <Database size={18} /> Selected evidence
            </h2>
            <span className="badge">Imported</span>
          </div>
          <h3 className="uat-snapshot-title">{bundle.title}</h3>
          <dl className="uat-facts">
            <div>
              <dt>Payment reference</dt>
              <dd className="mono">{known(bundle.paymentReference)}</dd>
            </div>
            <div>
              <dt>UTR</dt>
              <dd className="mono">{known(bundle.utr)}</dd>
            </div>
            <div>
              <dt>Source amount</dt>
              <dd className="uat-amount">
                {known(bundle.amount)} <span>{known(bundle.currency)}</span>
              </dd>
            </div>
            <div>
              <dt>Snapshot</dt>
              <dd className="mono">{bundle.snapshotId}</dd>
            </div>
          </dl>
          <h3>Export coverage</h3>
          <p className="muted">
            Counts describe supplied rows. Missing rows do not establish that an
            event did not occur.
          </p>
          <ul className="uat-coverage">
            {bundle.coverage.map((row, index) => (
              <li key={`${row.name}:${index}`}>
                <div>
                  <strong>{row.name}</strong>
                  <small>{human(row.completion)}</small>
                </div>
                <span>
                  {row.rowCount} {row.rowCount === 1 ? "row" : "rows"}
                </span>
              </li>
            ))}
          </ul>
          {!bundle.coverage.length && (
            <p className="muted">
              No source coverage declaration was supplied.
            </p>
          )}
          {bundle.warnings.length > 0 && (
            <div className="uat-warnings">
              <h3>
                <TriangleAlert size={15} /> Export limitations
              </h3>
              <ul>
                {bundle.warnings.map((warning, index) => (
                  <li key={index}>{warning}</li>
                ))}
              </ul>
            </div>
          )}
          <details className="uat-hash">
            <summary>Evidence fingerprint</summary>
            <code>{bundle.evidenceHash}</code>
          </details>
        </aside>
        <section
          className="uat-conversation"
          aria-labelledby="uat-question-title"
        >
          <div className="panel uat-question-panel">
            <div className="section-title">
              <h2 id="uat-question-title">
                <MessageSquare size={18} /> Ask about this payment
              </h2>
              <span className="mode-badge ollama">
                <Sparkles size={13} /> Local model
              </span>
            </div>
            <p className="muted">
              Your question is answered from the selected export and retrieved
              guidance. Wording is generated when you submit.
            </p>
            <form onSubmit={submit}>
              <label htmlFor="uat-question">Your question</label>
              <textarea
                id="uat-question"
                value={question}
                maxLength={2000}
                rows={5}
                readOnly={!canAsk}
                disabled={pending}
                placeholder="Ask what the evidence explains, what remains unknown, or what to check next."
                onChange={(event) => editQuestion(event.target.value)}
              />
              <div className="uat-question-footer">
                <small>
                  {question.length.toLocaleString()} / 2,000 characters
                </small>
                {canAsk ? (
                  <button className="primary" type="submit" disabled={pending}>
                    {pending ? (
                      <LoaderCircle className="spin" size={16} />
                    ) : (
                      <Send size={16} />
                    )}
                    {pending ? "Generating answer…" : "Ask local model"}
                  </button>
                ) : (
                  <span className="muted">
                    An analyst or reviewer can ask questions. Your role is
                    read-only.
                  </span>
                )}
              </div>
            </form>
            {pending && (
              <div className="uat-pending">
                <Clock3 size={18} aria-hidden="true" />
                <div>
                  <p role="status" aria-live="polite">
                    Waiting for the local model response. A cold local model can
                    take several minutes to load and read the export. Keep this
                    page open while you wait.
                  </p>
                  <p
                    className="uat-elapsed"
                    role="timer"
                    aria-label="Elapsed waiting time"
                    aria-live="off"
                  >
                    {Math.floor(elapsedSeconds / 60)}:
                    {String(elapsedSeconds % 60).padStart(2, "0")} elapsed
                  </p>
                  <p className="uat-wait-note">
                    Elapsed time is not an estimate of time remaining. A
                    completed answer or an error will appear here.
                  </p>
                </div>
              </div>
            )}
            {error && <Failure error={error} />}
          </div>
          <section
            className="panel uat-history"
            aria-labelledby="uat-history-title"
          >
            <h3 id="uat-history-title">Saved model answers</h3>
            {historyLoading && (
              <p className="muted" role="status">
                Loading saved answers…
              </p>
            )}
            {historyError && <Failure error={historyError} />}
            {!historyLoading && !historyError && !history.length && (
              <p className="muted">No saved answers for this export yet.</p>
            )}
            <div className="uat-history-list">
              {history.map((item) => (
                <button
                  key={item.answerId}
                  className={`uat-history-item${answer?.answerId === item.answerId ? " selected" : ""}`}
                  disabled={pending}
                  onClick={() => {
                    setAnswer(item);
                    setError(null);
                  }}
                >
                  <strong>{item.question}</strong>
                  <span>
                    {item.evidenceHash === bundle.evidenceHash
                      ? "Current evidence"
                      : "Earlier evidence"}{" "}
                    ·{" "}
                    {item.answerComposition === "joined-model-claims"
                      ? "Cited model claims"
                      : "Historical free-prose summary"}{" "}
                    · {item.model.name} · {item.generatedAt}
                  </span>
                </button>
              ))}
            </div>
          </section>
          {answer ? (
            <section
              className="panel uat-answer"
              aria-labelledby="uat-answer-title"
            >
              <div className="section-title">
                <h2 id="uat-answer-title">
                  <Sparkles size={18} /> Model-generated answer
                </h2>
                <span className="badge">Review needed</span>
              </div>
              {answer.evidenceHash !== bundle.evidenceHash && (
                <p className="notice neutral">
                  This saved answer used an earlier evidence hash. Its cited
                  documents are preserved below; it is not an answer about the
                  currently selected evidence version.
                </p>
              )}
              <p className="uat-answer-question">{answer.question}</p>
              <section
                className="uat-answer-unknowns"
                aria-labelledby="uat-answer-unknowns-title"
              >
                <h3 id="uat-answer-unknowns-title">
                  What this evidence cannot establish
                </h3>
                <p className="muted">
                  Uncertainty reported for your question. Review it alongside
                  the cited claims.
                </p>
                {answer.unknowns.length ? (
                  <ul>
                    {answer.unknowns.map((item, index) => (
                      <li key={index}>{item}</li>
                    ))}
                  </ul>
                ) : (
                  <p className="muted">
                    The model did not list an unknown for this question. That
                    does not establish that the evidence is complete.
                  </p>
                )}
              </section>
              {answer.answerComposition === "joined-model-claims" ? (
                <p className="uat-composition-note">
                  The local model wrote these cited claims for your question.
                  The service assembled the answer from that same text.
                </p>
              ) : (
                <div className="uat-historical-summary">
                  <h3>Historical free-prose summary</h3>
                  <p className="uat-composition-note">
                    This saved response has a separate model-written summary and
                    claim list. Review both against the preserved sources.
                  </p>
                  <div className="uat-answer-text">{answer.answer}</div>
                </div>
              )}
              {answer.claims.length > 0 && (
                <div className="uat-claims">
                  <h3>
                    {answer.answerComposition === "joined-model-claims"
                      ? "Model-written claims and cited evidence"
                      : "Historical claims and cited evidence"}
                  </h3>
                  {answer.claims.map((claim, index) => (
                    <article key={index}>
                      <p>{claim.text}</p>
                      <div className="uat-source-links">
                        {claim.evidenceIds.length ? (
                          claim.evidenceIds.map((id) => (
                            <button
                              className="text-button"
                              key={id}
                              onClick={() => revealDocument(id)}
                            >
                              <FileText size={13} />
                              {id}
                              <ArrowUpRight size={12} />
                            </button>
                          ))
                        ) : (
                          <small>No source was attached to this claim.</small>
                        )}
                      </div>
                    </article>
                  ))}
                </div>
              )}
              <div className="uat-answer-lists">
                <div>
                  <h3>Suggested next checks</h3>
                  {answer.nextChecks.length ? (
                    <ul>
                      {answer.nextChecks.map((item, index) => (
                        <li key={index}>{item}</li>
                      ))}
                    </ul>
                  ) : (
                    <p className="muted">No next checks were returned.</p>
                  )}
                </div>
              </div>
              <div className="uat-model-meta">
                <strong>{answer.model.name}</strong>
                <span>
                  {answer.model.actualCalls} actual model{" "}
                  {answer.model.actualCalls === 1 ? "call" : "calls"}
                </span>
                <span>{(answer.model.durationMs / 1000).toFixed(1)}s</span>
                <span>
                  {answer.model.promptTokens ?? "Not reported"} input /{" "}
                  {answer.model.completionTokens ?? "not reported"} output
                  tokens
                </span>
              </div>
              <p className="uat-review-note">
                Source references identify available documents; they do not
                automatically verify the model's interpretation. Check each
                claim before acting. This page does not execute payments.
              </p>
              <details className="uat-answer-provenance">
                <summary>Answer provenance and retrieved sources</summary>
                <dl className="uat-facts">
                  <div>
                    <dt>Answer ID</dt>
                    <dd className="mono">{answer.answerId}</dd>
                  </div>
                  <div>
                    <dt>Generated at</dt>
                    <dd>{answer.generatedAt}</dd>
                  </div>
                  <div>
                    <dt>Retrieval method</dt>
                    <dd>{answer.retrieval.method}</dd>
                  </div>
                  <div>
                    <dt>Answer composition</dt>
                    <dd>
                      {answer.answerComposition === "joined-model-claims"
                        ? "Service joins model-written claims without rewriting"
                        : "Historical model-written free-prose summary"}
                    </dd>
                  </div>
                  <div>
                    <dt>Evidence hash</dt>
                    <dd className="mono">{answer.evidenceHash}</dd>
                  </div>
                </dl>
                <div className="uat-source-links">
                  {answer.retrieval.documentIds.map((id) =>
                    answer.evidenceHash === bundle.evidenceHash ||
                    answer.citations.some((doc) => doc.id === id) ? (
                      <button
                        className="text-button"
                        key={id}
                        onClick={() => revealDocument(id)}
                      >
                        {id}
                        <ArrowUpRight size={12} />
                      </button>
                    ) : (
                      <code key={id}>{id} · earlier retrieval</code>
                    ),
                  )}
                </div>
                <h4>Answer citations</h4>
                {answer.citations.length ? (
                  <ul>
                    {answer.citations.map((citation) => (
                      <li key={citation.id}>
                        <button
                          className="text-button"
                          onClick={() => revealDocument(citation.id)}
                        >
                          {citation.title}
                          <ArrowUpRight size={12} />
                        </button>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p>No source citations were returned.</p>
                )}
              </details>
              <div className="uat-citation-documents">
                <h3>Sources cited by this answer</h3>
                {answer.citations.map((doc) => (
                  <details
                    key={`${answer.answerId}:${doc.id}`}
                    className={`uat-document${focusedDocument === doc.id ? " focused" : ""}`}
                    ref={(node) => {
                      if (node)
                        documentElements.current.set(`answer:${doc.id}`, node);
                      else documentElements.current.delete(`answer:${doc.id}`);
                    }}
                  >
                    <summary>
                      <span>
                        <strong>{doc.title}</strong>
                        <small>
                          {doc.id} ·{" "}
                          {doc.kind === "evidence"
                            ? "Export evidence"
                            : "Knowledge guidance"}
                        </small>
                      </span>
                    </summary>
                    <p className="uat-document-source">
                      <strong>Source:</strong> {doc.source.file}
                      {doc.source.sheet && ` · ${doc.source.sheet}`}
                      {doc.source.range && ` · ${doc.source.range}`}
                      {doc.source.locator && ` · ${doc.source.locator}`}
                    </p>
                    <pre>{doc.content}</pre>
                  </details>
                ))}
              </div>
            </section>
          ) : (
            !pending &&
            !error && (
              <div className="uat-answer-placeholder">
                <BookOpen size={25} />
                <h3>Start with the evidence</h3>
                <p>
                  Ask your own question, then compare the generated explanation
                  with the source records below.
                </p>
              </div>
            )
          )}
        </section>
      </div>
      <section
        className="panel uat-documents"
        aria-labelledby="uat-documents-title"
      >
        <div className="section-title">
          <h2 id="uat-documents-title">
            <FileText size={18} /> Evidence and guidance
          </h2>
          <span className="muted">
            {bundle.documents.length} available documents
          </span>
        </div>
        <p className="muted">
          Expand a document to inspect the exact context made available for
          retrieval. Source locations refer to the staged export or guidance.
        </p>
        {bundle.documents.map((doc) => (
          <details
            key={doc.id}
            className={`uat-document${focusedDocument === doc.id ? " focused" : ""}`}
            ref={(node) => {
              if (node) documentElements.current.set(doc.id, node);
              else documentElements.current.delete(doc.id);
            }}
          >
            <summary>
              <span>
                <strong>{doc.title}</strong>
                <small>
                  {doc.id} ·{" "}
                  {doc.kind === "evidence"
                    ? "Export evidence"
                    : "Knowledge guidance"}
                </small>
              </span>
              <span className="badge">
                {doc.kind === "evidence" ? "Evidence" : "Guidance"}
              </span>
            </summary>
            <p className="uat-document-source">
              <strong>Source:</strong> {doc.source.file}
              {doc.source.sheet && ` · ${doc.source.sheet}`}
              {doc.source.range && ` · ${doc.source.range}`}
              {doc.source.locator && ` · ${doc.source.locator}`}
            </p>
            <pre>{doc.content}</pre>
          </details>
        ))}
        {!bundle.documents.length && (
          <p className="muted">
            No source documents were supplied in this snapshot.
          </p>
        )}
      </section>
    </>
  );
}
