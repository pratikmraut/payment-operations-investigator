import { useEffect, useMemo, useState } from "react";
import {
  ArrowUpRight,
  FileText,
  LoaderCircle,
  MessageSquare,
  RefreshCw,
  TriangleAlert,
} from "lucide-react";
import { api, ApiError } from "./api";
import { CaseInvestigation } from "./CaseInvestigation";
import { PaymentWorkflow } from "./PaymentWorkflow";
import { navigateLink, navigateTo, replaceDestination } from "./routing";
import { paymentCaseNumber, paymentCasePath } from "./paymentCaseIdentity";
import type { User } from "./types";
import {
  CASE_LIFECYCLE_CHANGED,
  type CaseLifecycleState,
} from "./CaseLifecycle";

type SavedCase = {
  id: string;
  caseNumber?: string;
  lifecycleState?: CaseLifecycleState;
  reference: string;
  utr: string | null;
  orgBank: string;
  orgBranch: string;
  amount: string;
  currency: string | null;
  reason: string;
  evidenceStatus: string;
};
type Props = { user: User; caseId?: string; evidenceId?: string };
const record = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === "object" && !Array.isArray(value);

function validateCases(value: unknown): SavedCase[] {
  if (
    !record(value) ||
    !Array.isArray(value.items) ||
    !value.items.every(
      (item) =>
        record(item) &&
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
        (item.caseNumber === undefined ||
          typeof item.caseNumber === "string") &&
        [item.utr, item.currency].every(
          (field) => field === null || typeof field === "string",
        ),
    ) ||
    new Set(value.items.map((item) => item.id)).size !== value.items.length
  ) {
    throw new Error(
      "The saved payment list could not be read. Refresh cases to try again.",
    );
  }
  return value.items as SavedCase[];
}
const qaPath = (caseId: string, evidenceId?: string) =>
  `/evidences/questions/${encodeURIComponent(caseId)}${evidenceId ? `/${encodeURIComponent(evidenceId)}` : ""}`;

export function EvidenceQuestions(props: Props) {
  return (
    <QuestionsView
      key={`${props.user.tenantId}:${props.user.id}:${props.user.role}`}
      {...props}
    />
  );
}
function QuestionsView({ user, caseId, evidenceId }: Props) {
  const [cases, setCases] = useState<SavedCase[] | null>(null);
  const [search, setSearch] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const [revision, setRevision] = useState(0);
  const canWrite = ["ANALYST", "REVIEWER"].includes(user.role.toUpperCase());
  useEffect(() => {
    const changed = () => setRevision((value) => value + 1);
    window.addEventListener(CASE_LIFECYCLE_CHANGED, changed);
    return () => window.removeEventListener(CASE_LIFECYCLE_CHANGED, changed);
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    let timedOut = false;
    const timer = window.setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, 30000);
    setLoading(true);
    setError(null);
    api<unknown>("/payment-cases?lifecycle=ALL", { signal: controller.signal })
      .then((value) => {
        if (!controller.signal.aborted) setCases(validateCases(value));
      })
      .catch((failure) => {
        if (!controller.signal.aborted || timedOut)
          setError(
            timedOut
              ? new Error(
                  "Loading payment cases took longer than 30 seconds. Refresh cases to retry.",
                )
              : (failure as Error),
          );
      })
      .finally(() => {
        window.clearTimeout(timer);
        if (!controller.signal.aborted || timedOut) setLoading(false);
      });
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [revision]);
  const selected = caseId
    ? cases?.find((item) => item.id === caseId || item.caseNumber === caseId)
    : undefined;
  useEffect(() => {
    if (
      selected?.caseNumber &&
      caseId !== selected.caseNumber &&
      window.location.pathname === qaPath(caseId!, evidenceId)
    ) {
      replaceDestination(qaPath(selected.caseNumber, evidenceId));
    }
  }, [selected?.caseNumber, caseId, evidenceId]);
  const matches = useMemo(() => {
    const words = search.trim().toLowerCase().split(/\s+/).filter(Boolean);
    return (cases ?? []).filter((item) => {
      const text = [
        item.id,
        item.caseNumber ?? "",
        item.reference,
        item.utr ?? "",
        item.reason,
      ]
        .join(" ")
        .toLowerCase();
      return words.every((word) => text.includes(word));
    });
  }, [cases, search]);
  const label = (item: SavedCase) =>
    `${item.caseNumber ? `Case ${item.caseNumber} · ` : ""}${item.reference} · ${item.utr || "UTR not supplied"} · Bank ${item.orgBank} / Branch ${item.orgBranch}${item.lifecycleState === "ARCHIVED" ? " · Archived" : ""}`;
  return (
    <div className="evidence-questions-page">
      <div className="page-heading">
        <div>
          <div className="eyebrow">QUESTIONS OVER SAVED PAYMENT EVIDENCE</div>
          <h1>Evidence Q&amp;A</h1>
          <p>
            Select a payment case and evidence version, ask a question, and
            review answers with their preserved sources.
          </p>
        </div>
        <button
          className="secondary"
          disabled={loading}
          onClick={() => setRevision((value) => value + 1)}
        >
          <RefreshCw size={15} /> Refresh cases
        </button>
      </div>
      <PaymentWorkflow current="investigation" />
      {!selected && (
        <ol
          className="evidence-questions-guide"
          aria-label="How to use Evidence Q&A"
        >
          <li>
            <strong>1. Select your case</strong>
            <span>
              Use its case number, payment reference, UTR or investigation
              reason.
            </span>
          </li>
          <li>
            <strong>2. Choose saved evidence</strong>
            <span>
              Select the version whose records you want to investigate.
            </span>
          </li>
          <li>
            <strong>3. Ask and review</strong>
            <span>
              Run a question, then inspect the answer, citations and gaps.
            </span>
          </li>
        </ol>
      )}
      <section
        className="panel evidence-questions-picker"
        aria-labelledby="evidence-questions-picker-title"
      >
        <div className="section-title">
          <h2 id="evidence-questions-picker-title">
            <FileText size={18} /> Select payment case
          </h2>
        </div>
        <p className="muted">
          These are your authorized saved cases. Evidence and questions are
          shared with each case's investigation workbench.
        </p>
        {loading && (
          <p role="status">
            <LoaderCircle size={15} className="spin" /> Loading payment cases…
          </p>
        )}
        {error && (
          <div className="notice danger" role="alert">
            <TriangleAlert size={17} />
            <div>
              <strong>{error.message}</strong>
              {error instanceof ApiError && error.requestId && (
                <small>Request {error.requestId}</small>
              )}
            </div>
          </div>
        )}
        {cases && !error && (
          <>
            <div
              className="evidence-questions-fields"
              role="search"
              aria-label="Find a case for Evidence Q&A"
            >
              <div>
                <label htmlFor="evidence-questions-search">
                  Search saved cases
                </label>
                <input
                  id="evidence-questions-search"
                  value={search}
                  maxLength={200}
                  onChange={(event) => setSearch(event.target.value)}
                  placeholder="Case number, payment reference, UTR or investigation reason"
                />
              </div>
              <div>
                <label htmlFor="evidence-questions-case">Payment case</label>
                <select
                  id="evidence-questions-case"
                  value={selected ? paymentCaseNumber(selected) : ""}
                  onChange={(event) =>
                    navigateTo(
                      event.target.value
                        ? qaPath(event.target.value)
                        : "/evidences/questions",
                    )
                  }
                >
                  <option value="">Choose a payment case</option>
                  {selected &&
                    !matches.some((item) => item.id === selected.id) && (
                      <option value={paymentCaseNumber(selected)}>
                        {label(selected)} · Current selection outside search
                      </option>
                    )}
                  {matches.map((item) => (
                    <option key={item.id} value={paymentCaseNumber(item)}>
                      {label(item)}
                    </option>
                  ))}
                </select>
                <small className="muted">
                  {matches.length} matching{" "}
                  {matches.length === 1 ? "case" : "cases"}. Searching does not
                  change the selected payment.
                </small>
              </div>
            </div>
            {cases.length > 0 && matches.length === 0 && (
              <p role="status">
                No cases match this search. Try another reference or word.
              </p>
            )}
            {caseId && !selected && (
              <div className="notice danger" role="alert">
                <TriangleAlert size={17} />
                <p>
                  This case is unavailable in your authorized workspace. Choose
                  an available case above.
                </p>
              </div>
            )}
            {selected && (
              <>
                {selected.lifecycleState === "ARCHIVED" && (
                  <p className="notice neutral">
                    This case is archived. Review its saved evidence and answers
                    here, or open the case and restore it to ask a new question.
                  </p>
                )}
                <details
                  key={selected.id}
                  className="evidence-questions-details"
                >
                  <summary>Payment details and investigation reason</summary>
                  <dl className="evidence-questions-payment">
                    <div>
                      <dt>Case number</dt>
                      <dd>{paymentCaseNumber(selected)}</dd>
                    </div>
                    <div>
                      <dt>Payment reference</dt>
                      <dd>{selected.reference}</dd>
                    </div>
                    <div>
                      <dt>UTR</dt>
                      <dd>{selected.utr || "Not supplied"}</dd>
                    </div>
                    <div>
                      <dt>Bank / branch</dt>
                      <dd>
                        {selected.orgBank} / {selected.orgBranch}
                      </dd>
                    </div>
                    <div>
                      <dt>Source amount</dt>
                      <dd>
                        {selected.amount}
                        {selected.currency
                          ? ` ${selected.currency}`
                          : " · Currency not supplied"}
                      </dd>
                    </div>
                  </dl>
                  <p className="evidence-questions-reason">
                    <strong>Investigation reason</strong>
                    {selected.reason}
                  </p>
                </details>
                <div className="evidence-questions-actions">
                  <a
                    className="secondary"
                    href={paymentCasePath(selected)}
                    onClick={navigateLink}
                  >
                    {selected.lifecycleState === "ARCHIVED"
                      ? "Open archived case"
                      : "Open case / collect evidence"}{" "}
                    <ArrowUpRight size={14} />
                  </a>
                  <small className="muted">
                    Each submitted question is saved to this case with the exact
                    evidence version used.
                  </small>
                </div>
              </>
            )}
          </>
        )}
      </section>
      {!error && cases && !selected && (
        <section className="panel evidence-questions-empty">
          <MessageSquare size={28} />
          <h2>
            {cases.length
              ? "Choose a case to begin"
              : "Create a payment case first"}
          </h2>
          <p>
            {cases.length
              ? "Select a payment above to see its evidence versions, saved questions and answers."
              : "Find the payment, open a case and attach its inquiry results, Excel files or JSON evidence."}
          </p>
          <a
            className="secondary"
            href="/cases"
            data-case-queue-mode="payment"
            onClick={navigateLink}
          >
            Find payment <ArrowUpRight size={14} />
          </a>
        </section>
      )}
      {!error && selected && (
        <CaseInvestigation
          caseId={selected.id}
          caseNumber={selected.caseNumber}
          canWrite={
            canWrite && !loading && selected.lifecycleState !== "ARCHIVED"
          }
          archived={selected.lifecycleState === "ARCHIVED"}
          presentation="qa"
          requestedEvidenceId={evidenceId}
          onEvidenceChange={(id) =>
            navigateTo(qaPath(paymentCaseNumber(selected), id))
          }
        />
      )}
    </div>
  );
}
