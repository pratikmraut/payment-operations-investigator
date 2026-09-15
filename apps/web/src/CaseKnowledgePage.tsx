import { useEffect, useMemo, useState } from "react";
import {
  ArrowRight,
  BookOpen,
  ChevronLeft,
  ChevronRight,
  FileText,
  LoaderCircle,
  RefreshCw,
  Search,
  TriangleAlert,
} from "lucide-react";
import { api, ApiError, date } from "./api";
import { navigateLink } from "./routing";
import type { User } from "./types";

type IndexStatus = "CURRENT" | "STALE" | "MISSING" | "DISABLED";
type Category = "SCOPE" | "GUIDANCE" | "STATUS";
type Selection = "ALWAYS" | "EXACT_OR_SEMANTIC" | "SEMANTIC";
type KnowledgeItem = {
  id: string;
  kind: "knowledge";
  title: string;
  content: string;
  source: {
    file: string;
    sheet?: string | null;
    range?: string | null;
    locator?: string | null;
  };
  version: string;
  category: Category;
  selection: Selection;
  embeddingStatus: IndexStatus;
};
type KnowledgeLibrary = {
  schemaVersion: "case-knowledge-library-v1";
  tenantId: string;
  evidenceSchema: string;
  version: string;
  embedding: {
    enabled: boolean;
    status: IndexStatus;
    model: string | null;
    digest: string | null;
    dimensions: number | null;
    indexedAt: string | null;
    indexedDocuments: number;
    totalDocuments: number;
  };
  items: KnowledgeItem[];
  warnings: string[];
};

const PAGE_SIZE = 10;
const STATUS: Record<IndexStatus, string> = {
  CURRENT: "Current embeddings",
  STALE: "Embeddings need updating",
  MISSING: "Embeddings missing",
  DISABLED: "Embedding index disabled",
};
const CATEGORIES: Record<Category, string> = {
  SCOPE: "Scope & interpretation",
  GUIDANCE: "Investigation guidance",
  STATUS: "Status definitions",
};
const SELECTION: Record<Selection, string> = {
  ALWAYS: "Included in every applicable question",
  EXACT_OR_SEMANTIC: "Selected by exact field/code or question relevance",
  SEMANTIC: "Selected by question relevance",
};
const record = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === "object" && !Array.isArray(value);
const hash = (value: unknown) =>
  typeof value === "string" && /^[a-f0-9]{64}$/.test(value);
const status = (value: unknown): value is IndexStatus =>
  typeof value === "string" && Object.hasOwn(STATUS, value);

function readLibrary(value: unknown, tenantId: string): KnowledgeLibrary {
  const invalid = () => {
    throw new Error(
      "The current knowledge library could not be verified. Refresh to try again.",
    );
  };
  if (
    !record(value) ||
    value.schemaVersion !== "case-knowledge-library-v1" ||
    value.tenantId !== tenantId ||
    typeof value.evidenceSchema !== "string" ||
    !value.evidenceSchema ||
    !hash(value.version) ||
    !Array.isArray(value.items) ||
    !Array.isArray(value.warnings) ||
    !value.warnings.every((warning) => typeof warning === "string") ||
    !record(value.embedding)
  )
    return invalid();
  if (
    !value.items.every(
      (item) =>
        record(item) &&
        item.kind === "knowledge" &&
        ["id", "title", "content"].every(
          (key) => typeof item[key] === "string" && !!item[key],
        ) &&
        hash(item.version) &&
        status(item.embeddingStatus) &&
        typeof item.category === "string" &&
        Object.hasOwn(CATEGORIES, item.category) &&
        typeof item.selection === "string" &&
        Object.hasOwn(SELECTION, item.selection) &&
        record(item.source) &&
        typeof item.source.file === "string" &&
        !!item.source.file &&
        Object.entries(item.source).every(
          ([key, field]) =>
            ["file", "sheet", "range", "locator"].includes(key) &&
            (typeof field === "string" || (key !== "file" && field === null)),
        ),
    ) ||
    new Set(value.items.map((item) => item.id)).size !== value.items.length
  )
    return invalid();
  const index = value.embedding;
  if (
    typeof index.enabled !== "boolean" ||
    !status(index.status) ||
    ![index.model, index.digest, index.indexedAt].every(
      (field) => field === null || typeof field === "string",
    ) ||
    !(
      index.dimensions === null ||
      (Number.isSafeInteger(index.dimensions) &&
        (index.dimensions as number) > 0)
    ) ||
    !Number.isSafeInteger(index.indexedDocuments) ||
    (index.indexedDocuments as number) < 0 ||
    index.totalDocuments !== value.items.length ||
    (index.indexedDocuments as number) > value.items.length ||
    (index.status === "CURRENT" &&
      (!index.enabled ||
        !index.model ||
        !index.digest ||
        !index.dimensions ||
        !index.indexedAt ||
        index.indexedDocuments !== value.items.length ||
        value.items.some((item) => item.embeddingStatus !== "CURRENT")))
  )
    return invalid();
  return value as KnowledgeLibrary;
}

export function CaseKnowledgePage({ user }: { user: User }) {
  return (
    <KnowledgeView
      key={`${user.tenantId}:${user.id}:${user.role}`}
      tenantId={user.tenantId}
    />
  );
}

function KnowledgeView({ tenantId }: { tenantId: string }) {
  const [library, setLibrary] = useState<KnowledgeLibrary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const [revision, setRevision] = useState(0);
  const [search, setSearch] = useState("");
  const [category, setCategory] = useState<Category | "ALL">("ALL");
  const [indexStatus, setIndexStatus] = useState<IndexStatus | "ALL">("ALL");
  const [page, setPage] = useState(1);

  useEffect(() => {
    const controller = new AbortController();
    let timedOut = false;
    const timer = window.setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, 30000);
    setLoading(true);
    setError(null);
    setLibrary(null);
    api<unknown>("/case-knowledge", { signal: controller.signal })
      .then((value) => {
        if (!controller.signal.aborted)
          setLibrary(readLibrary(value, tenantId));
      })
      .catch((failure) => {
        if (!controller.signal.aborted || timedOut) {
          setError(
            timedOut
              ? new Error(
                  "Loading knowledge took longer than 30 seconds. Refresh to retry.",
                )
              : (failure as Error),
          );
        }
      })
      .finally(() => {
        window.clearTimeout(timer);
        if (!controller.signal.aborted || timedOut) setLoading(false);
      });
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [revision, tenantId]);

  const filtered = useMemo(() => {
    const words = search.trim().toLowerCase().split(/\s+/).filter(Boolean);
    return (library?.items ?? []).filter((item) => {
      const text = [
        item.id,
        item.title,
        item.content,
        ...Object.values(item.source),
      ]
        .join(" ")
        .toLowerCase();
      return (
        (category === "ALL" || item.category === category) &&
        (indexStatus === "ALL" || item.embeddingStatus === indexStatus) &&
        words.every((word) => text.includes(word))
      );
    });
  }, [library, search, category, indexStatus]);
  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const currentPage = Math.min(page, totalPages);
  const start = (currentPage - 1) * PAGE_SIZE;
  const visible = filtered.slice(start, start + PAGE_SIZE);
  const index = library?.embedding;

  return (
    <div className="case-knowledge-page">
      <div className="page-heading">
        <div>
          <div className="eyebrow">VERSIONED OPERATING GUIDANCE</div>
          <h1>Knowledge library</h1>
          <p>
            Explore the current guidance available to payment-case
            investigations and check its embedding coverage.
          </p>
        </div>
        <button
          className="secondary"
          onClick={() => setRevision((value) => value + 1)}
        >
          <RefreshCw size={16} /> Refresh
        </button>
      </div>
      <div className="knowledge-intro">
        <BookOpen size={22} aria-hidden="true" />
        <div>
          <strong>Guidance for your payment investigation</strong>
          <p>
            Find a payment, save its evidence, then ask a question. The
            investigation selects applicable guidance from this library. Its
            citations show the exact documents used; earlier answers retain
            their saved sources.
          </p>
          <div className="case-knowledge-links">
            <a href="/cases" onClick={navigateLink}>
              Find a payment <ArrowRight size={14} />
            </a>
            <a href="/evidences/questions" onClick={navigateLink}>
              Open Evidence Q&amp;A <ArrowRight size={14} />
            </a>
          </div>
        </div>
      </div>
      {loading && (
        <div className="loading" role="status">
          <LoaderCircle className="spin" size={21} />
          <span>Loading knowledge library…</span>
        </div>
      )}
      {error && (
        <div className="notice danger" role="alert">
          <TriangleAlert size={18} />
          <div>
            <strong>{error.message}</strong>
            {error instanceof ApiError && error.requestId && (
              <small>Request {error.requestId}</small>
            )}
          </div>
          <button
            className="text-button"
            onClick={() => setRevision((value) => value + 1)}
          >
            Try again
          </button>
        </div>
      )}
      {library && index && !loading && !error && (
        <>
          <section
            className="panel case-knowledge-index"
            aria-labelledby="knowledge-index-title"
          >
            <div className="case-knowledge-index-heading">
              <div>
                <h2 id="knowledge-index-title">Current knowledge</h2>
                <p>
                  {library.items.length} documents · Workspace{" "}
                  {library.tenantId}
                </p>
              </div>
              <span
                className={`version-badge knowledge-index-${index.status.toLowerCase()}`}
              >
                {STATUS[index.status]}
              </span>
            </div>
            <p>
              {index.indexedDocuments} / {index.totalDocuments} documents have
              current embeddings. Coverage describes saved vectors matching the
              current guidance; it does not check whether the local model is
              running.
            </p>
            <progress
              className="knowledge-coverage-meter"
              aria-label="Documents with current embeddings"
              value={index.indexedDocuments}
              max={Math.max(1, index.totalDocuments)}
            />
            {index.status === "STALE" && (
              <p className="case-knowledge-index-action">
                Guidance has changed. Its embedding index needs rebuilding
                before it can be used for indexed investigations.
              </p>
            )}
            {index.status === "MISSING" && (
              <p className="case-knowledge-index-action">
                Create the configured embedding index before using indexed
                investigations.
              </p>
            )}
            {index.status === "DISABLED" && (
              <p className="case-knowledge-index-action">
                The complete knowledge embedding index is not enabled. The
                guidance below is still available for inspection.
              </p>
            )}
            <details className="case-knowledge-index-details">
              <summary>Index and source version</summary>
              <dl>
                <dt>Library version</dt>
                <dd>
                  <code>{library.version}</code>
                </dd>
                <dt>Evidence schema</dt>
                <dd>{library.evidenceSchema}</dd>
                <dt>Embedding model</dt>
                <dd>{index.model ?? "Not configured"}</dd>
                <dt>Dimensions</dt>
                <dd>{index.dimensions ?? "Not recorded"}</dd>
                <dt>Indexed</dt>
                <dd>
                  {index.indexedAt ? (
                    <time dateTime={index.indexedAt}>
                      {date(index.indexedAt)}
                    </time>
                  ) : (
                    "Not recorded"
                  )}
                </dd>
                <dt>Model fingerprint</dt>
                <dd>
                  <code>{index.digest ?? "Not recorded"}</code>
                </dd>
              </dl>
            </details>
          </section>
          {library.warnings.length > 0 && (
            <div className="notice warning case-knowledge-warnings" role="note">
              <TriangleAlert size={18} />
              <div>
                <strong>Source applicability</strong>
                <ul>
                  {library.warnings.map((warning, position) => (
                    <li key={position}>{warning}</li>
                  ))}
                </ul>
              </div>
            </div>
          )}
          <div
            className="case-knowledge-filters"
            role="search"
            aria-label="Filter knowledge library"
          >
            <label className="case-knowledge-search">
              Search guidance
              <span className="search-field">
                <Search size={17} />
                <input
                  aria-label="Search guidance"
                  placeholder="Search fields, status codes, guidance or sources…"
                  value={search}
                  onChange={(event) => {
                    setSearch(event.target.value);
                    setPage(1);
                  }}
                />
              </span>
            </label>
            <label>
              Knowledge category
              <select
                value={category}
                onChange={(event) => {
                  setCategory(event.target.value as Category | "ALL");
                  setPage(1);
                }}
              >
                <option value="ALL">All categories</option>
                {Object.entries(CATEGORIES).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Embedding coverage
              <select
                value={indexStatus}
                onChange={(event) => {
                  setIndexStatus(event.target.value as IndexStatus | "ALL");
                  setPage(1);
                }}
              >
                <option value="ALL">All embedding states</option>
                {Object.entries(STATUS).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <p className="case-knowledge-results" role="status">
            {filtered.length
              ? `Showing ${start + 1}–${Math.min(start + PAGE_SIZE, filtered.length)} of ${filtered.length} matching documents`
              : "No matching guidance"}
          </p>
          <div className="case-knowledge-list">
            {visible.map((item) => (
              <article
                className="panel case-knowledge-item"
                key={`${item.id}:${item.version}`}
              >
                <div className="case-knowledge-item-heading">
                  <div>
                    <span className="eyebrow">{CATEGORIES[item.category]}</span>
                    <h2>
                      <FileText size={17} aria-hidden="true" />
                      {item.title}
                    </h2>
                  </div>
                  <span
                    className={`version-badge knowledge-index-${item.embeddingStatus.toLowerCase()}`}
                  >
                    {STATUS[item.embeddingStatus]}
                  </span>
                </div>
                <div className="runbook-meta">
                  <code>{item.id}</code>
                  <span>Version {item.version.slice(0, 12)}</span>
                  <span>{SELECTION[item.selection]}</span>
                </div>
                <p className="case-knowledge-source">
                  {item.source.file}
                  {item.source.sheet ? ` · ${item.source.sheet}` : ""}
                  {item.source.range ? `!${item.source.range}` : ""}
                </p>
                <details className="case-knowledge-detail">
                  <summary>Read guidance and source</summary>
                  <div className="case-knowledge-content">{item.content}</div>
                  <dl>
                    <dt>Source file</dt>
                    <dd>{item.source.file}</dd>
                    {item.source.sheet && (
                      <>
                        <dt>Sheet</dt>
                        <dd>{item.source.sheet}</dd>
                      </>
                    )}
                    {item.source.range && (
                      <>
                        <dt>Cells</dt>
                        <dd>{item.source.range}</dd>
                      </>
                    )}
                    {item.source.locator && (
                      <>
                        <dt>Source details</dt>
                        <dd>{item.source.locator}</dd>
                      </>
                    )}
                    <dt>Content version</dt>
                    <dd>
                      <code>{item.version}</code>
                    </dd>
                  </dl>
                </details>
              </article>
            ))}
          </div>
          {!filtered.length && (
            <div className="empty">
              <BookOpen size={29} />
              <h3>No matching guidance</h3>
              <p>Try another word or clear the filters.</p>
              <button
                className="secondary"
                onClick={() => {
                  setSearch("");
                  setCategory("ALL");
                  setIndexStatus("ALL");
                  setPage(1);
                }}
              >
                Clear filters
              </button>
            </div>
          )}
          {filtered.length > 0 && (
            <nav
              className="case-knowledge-pagination"
              aria-label="Knowledge pages"
            >
              <button
                className="secondary"
                disabled={currentPage <= 1}
                onClick={() => setPage(currentPage - 1)}
              >
                <ChevronLeft size={16} />
                Previous
              </button>
              <span>
                Page {currentPage} of {totalPages} · 10 per page
              </span>
              <button
                className="secondary"
                disabled={currentPage >= totalPages}
                onClick={() => setPage(currentPage + 1)}
              >
                Next
                <ChevronRight size={16} />
              </button>
            </nav>
          )}
          <p className="case-knowledge-footnote">
            This library contains reusable definitions and guidance. Payment
            records, saved model answers and pending training examples are not
            added as knowledge automatically. Reference versions do not
            establish applicability to a deployed bank release.
          </p>
        </>
      )}
    </div>
  );
}
