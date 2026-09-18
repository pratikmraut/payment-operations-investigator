import { useEffect, useMemo, useState } from "react";
import {
  ArrowUpRight,
  FileText,
  LoaderCircle,
  MessageSquare,
  RefreshCw,
  TriangleAlert,
} from "lucide-react";
import { ApiError } from "./api";
import { CaseInvestigation } from "./CaseInvestigation";
import { PaymentAmount } from "./PaymentAmount";
import { PaymentWorkflow } from "./PaymentWorkflow";
import { navigateLink, navigateTo, replaceDestination } from "./routing";
import { paymentCaseNumber, paymentCasePath } from "./paymentCaseIdentity";
import {
  caseListPath,
  isSavedPaymentCase,
  validateCasePage,
  useCaseRead,
  useCaseSearch,
  type SavedPaymentCase,
} from "./paymentCaseList";
import type { User } from "./types";
import { CASE_LIFECYCLE_CHANGED } from "./CaseLifecycle";

type SavedCase = SavedPaymentCase;
type Props = { user: User; caseId?: string; evidenceId?: string };
const validateCases = (value: unknown) =>
  validateCasePage(value, isSavedPaymentCase);
function validateSelected(value: unknown): SavedCase {
  if (!isSavedPaymentCase(value))
    throw new Error("This case could not be read. Refresh cases to try again.");
  return value;
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
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [revision, setRevision] = useState(0);
  const { debounced, pending } = useCaseSearch(search);
  const records = useCaseRead(
    caseListPath({ lifecycle: "ALL", search: debounced, page }),
    validateCases,
    revision,
    !pending,
  );
  const validateRequested = useMemo(
    () => (value: unknown) => {
      const result = validateSelected(value);
      if (result.id !== caseId && result.caseNumber !== caseId)
        throw new Error(
          "The selected case response did not match the requested case. Refresh cases to retry.",
        );
      return result;
    },
    [caseId],
  );
  const detail = useCaseRead(
    caseId ? `/payment-cases/${encodeURIComponent(caseId)}` : null,
    validateRequested,
    revision,
    true,
    true,
  );
  const { loading, error } = records;
  const matches = records.data?.items ?? [];
  const selected = detail.data;
  const currentPage = records.data?.page ?? page;
  const totalPages = records.data?.totalPages ?? 1;
  const canWrite = ["ANALYST", "REVIEWER"].includes(user.role.toUpperCase());
  useEffect(() => {
    const changed = () => setRevision((value) => value + 1);
    window.addEventListener(CASE_LIFECYCLE_CHANGED, changed);
    return () => window.removeEventListener(CASE_LIFECYCLE_CHANGED, changed);
  }, []);
  useEffect(() => {
    if (
      selected?.caseNumber &&
      caseId !== selected.caseNumber &&
      window.location.pathname === qaPath(caseId!, evidenceId)
    ) {
      replaceDestination(qaPath(selected.caseNumber, evidenceId));
    }
  }, [selected?.caseNumber, caseId, evidenceId]);
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
          disabled={loading || detail.loading}
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
        {
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
                  onChange={(event) => {
                    setSearch(event.target.value);
                    setPage(1);
                  }}
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
                        {label(selected)} · Current selection outside this page
                      </option>
                    )}
                  {matches.map((item) => (
                    <option key={item.id} value={paymentCaseNumber(item)}>
                      {label(item)}
                    </option>
                  ))}
                </select>
                <small className="muted">
                  {records.data
                    ? `${records.data.total} matching ${records.data.total === 1 ? "case" : "cases"}. `
                    : ""}
                  Searching and paging do not change the selected payment.
                </small>
              </div>
            </div>
            {records.data && (
              <nav
                className="payment-saved-pagination"
                aria-label="Payment case picker pagination"
              >
                <span>
                  Page {currentPage} of {totalPages} · 10 per page
                </span>
                <div className="payment-page-buttons">
                  <button
                    className="secondary"
                    disabled={loading || currentPage <= 1}
                    onClick={() => setPage(currentPage - 1)}
                  >
                    Previous cases
                  </button>
                  <button
                    className="secondary"
                    disabled={loading || currentPage >= totalPages}
                    onClick={() => setPage(currentPage + 1)}
                  >
                    Next cases
                  </button>
                </div>
              </nav>
            )}
            {records.data?.total === 0 && search.trim() && (
              <p role="status">
                No cases match this search. Try another reference or word.
              </p>
            )}
            {caseId && detail.error && (
              <div className="notice danger" role="alert">
                <TriangleAlert size={17} />
                <p>
                  {detail.error instanceof ApiError &&
                  [403, 404].includes(detail.error.status)
                    ? "This case is unavailable in your authorized workspace. Choose an available case above."
                    : detail.error.message}
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
                        <PaymentAmount item={selected} />
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
        }
      </section>
      {!error && records.data && !caseId && !search.trim() && (
        <section className="panel evidence-questions-empty">
          <MessageSquare size={28} />
          <h2>
            {records.data.total
              ? "Choose a case to begin"
              : "Create a payment case first"}
          </h2>
          <p>
            {records.data.total
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
      {caseId && detail.loading && !selected && (
        <p role="status">Loading selected payment case…</p>
      )}
      {selected && (
        <CaseInvestigation
          caseId={selected.id}
          user={user}
          caseNumber={selected.caseNumber}
          canWrite={
            canWrite &&
            !detail.loading &&
            !detail.error &&
            selected.lifecycleState !== "ARCHIVED"
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
