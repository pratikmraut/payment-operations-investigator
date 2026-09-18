import { useEffect, useRef, useState, type FormEvent } from "react";
import {
  ArrowUpRight,
  FileText,
  Download,
  History,
  LoaderCircle,
  MessageSquare,
  RefreshCw,
  Send,
  Sparkles,
  TriangleAlert,
} from "lucide-react";
import { api, ApiError, human } from "./api";
import { navigateLink } from "./routing";
import { parseEvidenceJson } from "./evidenceJson";
import { useUnsavedChanges } from "./unsavedChanges";
import { DraftTextControls } from "./DraftTextControls";
import type { User } from "./types";
import "./CaseInvestigationQueue.css";
import { CaseReadiness, SourceSelectionCoverage } from "./CaseReadiness";
import {
  HistoryMore,
  historyMeta,
  mergeHistory,
  type HistoryPageMeta,
} from "./caseHistory";
import {
  InvestigationTimer,
  SavedInvestigationTiming,
} from "./InvestigationTimer";
import {
  observeBrowserRun,
  validTiming,
  type BrowserRun,
  type InvestigationTiming,
} from "./investigationTiming";

type Document = {
  id: string;
  kind: "evidence" | "knowledge";
  title: string;
  content: string;
  source: {
    file: string;
    sheet?: string | null;
    range?: string | null;
    locator?: string | null;
  };
};
type Evidence = {
  id: string;
  version: number;
  caseId: string;
  evidenceHash: string;
  sourceKind: string;
  createdAt: string;
  warnings: string[];
};
type Context = {
  evidenceId: string;
  evidenceVersion: number;
  evidenceHash: string;
  guidanceHash: string;
  nonEmptyRows: number;
  documents: Document[];
  warnings: string[];
  timeline: {
    id: string;
    label: string;
    timestamp: string | null;
    group: string;
    rowIndex: number;
    documentId: string;
    fields: Record<string, string>;
  }[];
};
type Job = {
  id: string;
  caseId: string;
  evidenceId: string;
  evidenceVersion: number;
  evidenceHash: string;
  question: string;
  status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED";
  createdAt: string;
  createdBy: string;
  requestedAt?: string | null;
  timing?: InvestigationTiming;
  startedAt?: string;
  finishedAt?: string;
  error?: { code: string; message: string };
  selection?: unknown;
  queueVersion?: string;
  phase?: string;
  cancellationRequested?: boolean;
  waitingReason?: { code: string; message: string };
};
type RagReceipt = {
  pipeline: "case-evidence-rag-v1";
  promptHash: string;
  checks: string[];
  claimSupports: {
    claimIndex: number;
    claimType: "observation" | "interpretation" | "limitation" | "source-cited";
    fields: { documentId: string; field: string; value: string }[];
  }[];
};
type Answer = {
  answerId: string;
  snapshotId: string;
  evidenceHash: string;
  question: string;
  answer: string;
  answerComposition: "joined-model-claims";
  mode: "model-generated";
  claims: { text: string; evidenceIds: string[] }[];
  unknowns: string[];
  nextChecks: string[];
  citations: Document[];
  generatedAt: string;
  model: {
    provider: "ollama";
    name: string;
    actualCalls: number;
    durationMs: number;
    promptTokens: number | null;
    completionTokens: number | null;
  };
  retrieval: { method: string; documentIds: string[] };
  rag?: RagReceipt;
};
type JobDetail = Job & { documents: Document[]; answer?: Answer };
function frozenSelection(job: JobDetail | null): unknown {
  if (!job) return undefined;
  if (job.selection !== undefined) return job.selection;
  const receipt = job.documents.find(
    (document) => document.id === "CASE-EVIDENCE-SELECTION",
  );
  if (!receipt) return undefined;
  try {
    return parseEvidenceJson(receipt.content);
  } catch {
    return receipt.content;
  }
}
type Workbench = {
  caseId: string;
  status?: string;
  lifecycleState?: "ACTIVE" | "ARCHIVED";
  lifecycleVersion?: number;
  evidence: Evidence[];
  latestEvidenceId: string | null;
  investigations: Job[];
  evidencePage?: HistoryPageMeta;
  investigationPage?: HistoryPageMeta;
  activityPage?: HistoryPageMeta;
  activeInvestigations?: Job[];
  activeInvestigationPage?: HistoryPageMeta;
  audit: {
    id: string;
    action: string;
    occurredAt: string;
    actor: string;
    detail: string;
  }[];
};
type Props = {
  caseId: string;
  caseNumber?: string;
  canWrite: boolean;
  user?: Pick<User, "id" | "role">;
  archived?: boolean;
  resolved?: boolean;
  evidenceRevision?: number;
  presentation?: "case" | "qa";
  requestedEvidenceId?: string;
  onEvidenceChange?: (evidenceId: string) => void;
};
type Tab = "timeline" | "evidence" | "investigations" | "audit";
const suggestedQuestions = [
  {
    label: "Summarize this payment",
    question:
      "Summarize what the attached records show about this payment. Cite the source rows and distinguish recorded facts from unknown outcomes.",
  },
  {
    label: "Did OBPM accept the payment?",
    question:
      "Do the attached records establish that OBPM accepted this payment? Cite the supporting evidence and explain what confirmation is still missing.",
  },
  {
    label: "Was the beneficiary credited?",
    question:
      "Do the attached records confirm beneficiary credit? Cite the supporting evidence and explain what is still missing.",
  },
  {
    label: "What do the status codes mean?",
    question:
      "Explain the recorded payment, accounting and message status codes using the supplied field-specific guidance. Cite each source row and mapping, and identify any unmapped codes or unverified deployment assumptions.",
  },
  {
    label: "What changed in the payment history?",
    question:
      "What differences appear between the attached history rows and current payment or host records? Cite the rows, preserve source timestamps and explain any limits on establishing event order.",
  },
  {
    label: "What should I check next?",
    question:
      "Which additional evidence is needed to investigate this payment? Explain the gaps in the attached records and suggest documented read-only checks, citing the supplied guidance where available.",
  },
] as const;
const pending = (job: Job) =>
  job.status === "QUEUED" || job.status === "RUNNING";
const phaseLabels: Record<string, string> = {
  QUEUED: "Queued · waiting for a worker",
  PREPARING: "Preparing evidence and guidance",
  PREFLIGHT: "Checking model context",
  SUBMITTING: "Submitting to the local model",
  GENERATING: "Generating an answer",
  WAITING: "Waiting for the local model worker",
  CANCELLING: "Cancellation requested · waiting for acknowledgement",
  COMPLETED: "Answer saved",
  FAILED: "Investigation failed",
  CANCELLED: "Investigation cancelled",
};
function jobPhase(job: Job) {
  return job.cancellationRequested && pending(job)
    ? phaseLabels.CANCELLING
    : job.phase
      ? phaseLabels[job.phase]
      : undefined;
}
const string = (value: unknown): value is string => typeof value === "string";
const strings = (value: unknown): value is string[] =>
  Array.isArray(value) && value.every(string);
const record = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === "object" && !Array.isArray(value);
const canonical = (value: unknown): string =>
  JSON.stringify(value, (_key, entry) =>
    record(entry)
      ? Object.fromEntries(
          Object.keys(entry)
            .sort()
            .map((key) => [key, entry[key]]),
        )
      : entry,
  );
function documents(value: unknown): value is Document[] {
  return (
    Array.isArray(value) &&
    value.every(
      (doc) =>
        doc &&
        string(doc.id) &&
        !!doc.id &&
        ["evidence", "knowledge"].includes(doc.kind) &&
        string(doc.title) &&
        string(doc.content) &&
        string(doc.source?.file) &&
        [doc.source.sheet, doc.source.range, doc.source.locator].every(
          (item) => item == null || string(item),
        ),
    ) &&
    new Set(value.map((doc) => doc.id)).size === value.length
  );
}
function validateJob(job: Job, caseId: string) {
  if (
    !job ||
    job.caseId !== caseId ||
    ![
      job.id,
      job.evidenceId,
      job.evidenceHash,
      job.question,
      job.createdAt,
      job.createdBy,
    ].every((value) => string(value) && !!value) ||
    !Number.isSafeInteger(job.evidenceVersion) ||
    job.evidenceVersion < 1 ||
    !["QUEUED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"].includes(
      job.status,
    ) ||
    [job.startedAt, job.finishedAt].some(
      (value) => value !== undefined && !string(value),
    ) ||
    (job.requestedAt != null && !string(job.requestedAt)) ||
    (job.timing !== undefined && !validTiming(job.timing)) ||
    (job.queueVersion !== undefined && !string(job.queueVersion)) ||
    (job.phase !== undefined &&
      (!string(job.phase) || !Object.hasOwn(phaseLabels, job.phase))) ||
    (job.cancellationRequested !== undefined &&
      typeof job.cancellationRequested !== "boolean") ||
    (job.waitingReason !== undefined &&
      (!record(job.waitingReason) ||
        !string(job.waitingReason.code) ||
        !string(job.waitingReason.message))) ||
    (job.error !== undefined &&
      (!string(job.error.code) || !string(job.error.message)))
  )
    throw new Error(
      "The investigation response is unreadable or belongs to a different case.",
    );
}
function validateWorkbench(value: Workbench, caseId: string) {
  if (
    !value ||
    value.caseId !== caseId ||
    !Array.isArray(value.evidence) ||
    !Array.isArray(value.investigations) ||
    !Array.isArray(value.audit) ||
    !value.evidence.every(
      (item) =>
        item?.caseId === caseId &&
        [item.id, item.evidenceHash, item.sourceKind, item.createdAt].every(
          string,
        ) &&
        Number.isSafeInteger(item.version) &&
        item.version > 0 &&
        strings(item.warnings),
    ) ||
    !(
      value.latestEvidenceId === null ||
      value.evidence.some((item) => item.id === value.latestEvidenceId)
    ) ||
    !value.audit.every(
      (item) =>
        item &&
        [item.id, item.action, item.occurredAt, item.actor, item.detail].every(
          string,
        ),
    )
  )
    throw new Error(
      "The case workbench could not be read. Refresh its saved records.",
    );
  value.investigations.forEach((job) => validateJob(job, caseId));
  historyMeta(value.evidencePage, value.evidence.length);
  historyMeta(value.investigationPage, value.investigations.length);
  historyMeta(value.activityPage, value.audit.length);
  if (value.activeInvestigations !== undefined) {
    if (!Array.isArray(value.activeInvestigations))
      throw new Error("Active investigations could not be read.");
    value.activeInvestigations.forEach((job) => {
      validateJob(job, caseId);
      if (!pending(job))
        throw new Error("Active investigation status is unreadable.");
    });
    historyMeta(
      value.activeInvestigationPage,
      value.activeInvestigations.length,
    );
  }
}
function validateEvidence(item: Evidence, caseId: string) {
  if (
    !item ||
    item.caseId !== caseId ||
    ![item.id, item.evidenceHash, item.sourceKind, item.createdAt].every(
      string,
    ) ||
    !Number.isSafeInteger(item.version) ||
    item.version < 1 ||
    !strings(item.warnings)
  )
    throw new Error(
      "The selected evidence summary is unreadable or belongs to another case.",
    );
}
function validateActivity(item: Workbench["audit"][number]) {
  if (
    !item ||
    ![item.id, item.action, item.occurredAt, item.actor, item.detail].every(
      string,
    )
  )
    throw new Error("The case activity record is unreadable.");
}
function validateContext(value: Context, evidence: Evidence) {
  if (
    !value ||
    value.evidenceId !== evidence.id ||
    value.evidenceVersion !== evidence.version ||
    value.evidenceHash !== evidence.evidenceHash ||
    !string(value.guidanceHash) ||
    !Number.isSafeInteger(value.nonEmptyRows) ||
    value.nonEmptyRows < 0 ||
    !documents(value.documents) ||
    !strings(value.warnings) ||
    !Array.isArray(value.timeline)
  )
    throw new Error(
      "The investigation context does not match this evidence version. Refresh before running an investigation.",
    );
  const ids = new Set(value.documents.map((doc) => doc.id));
  if (
    !value.timeline.every(
      (row) =>
        row &&
        [row.id, row.label, row.group, row.documentId].every(string) &&
        ids.has(row.documentId) &&
        (row.timestamp === null || string(row.timestamp)) &&
        Number.isSafeInteger(row.rowIndex) &&
        record(row.fields) &&
        Object.values(row.fields).every(string),
    )
  )
    throw new Error(
      "The exported timeline contains an invalid row or source link. Select another version or correct its evidence.",
    );
}
const ragChecks = [
  "source-membership",
  "literal-field-quotations",
  "required-unknowns-and-next-checks",
];
function hasKeys(
  value: unknown,
  keys: string[],
): value is Record<string, unknown> {
  return (
    record(value) &&
    Object.keys(value).length === keys.length &&
    keys.every((key) => Object.hasOwn(value, key))
  );
}
function validRagReceipt(
  value: unknown,
  answer: Answer,
  supplied: Map<string, Document>,
): value is RagReceipt {
  if (
    !hasKeys(value, ["pipeline", "promptHash", "checks", "claimSupports"]) ||
    value.pipeline !== "case-evidence-rag-v1" ||
    !string(value.promptHash) ||
    !/^[a-f0-9]{64}$/.test(value.promptHash) ||
    !strings(value.checks) ||
    canonical(value.checks) !== canonical(ragChecks) ||
    !Array.isArray(value.claimSupports) ||
    value.claimSupports.length !== answer.claims.length ||
    answer.claims.length > 4 ||
    ![answer.unknowns, answer.nextChecks].every(
      (items) =>
        items.length >= 1 &&
        items.length <= 3 &&
        items.every((item) => !!item.trim()),
    )
  )
    return false;
  return value.claimSupports.every((support, index) => {
    if (
      !hasKeys(support, ["claimIndex", "claimType", "fields"]) ||
      support.claimIndex !== index ||
      !string(support.claimType) ||
      !["observation", "interpretation", "limitation", "source-cited"].includes(
        support.claimType,
      ) ||
      !Array.isArray(support.fields) ||
      support.fields.length > 6 ||
      (!["limitation", "source-cited"].includes(support.claimType) &&
        !support.fields.length)
    )
      return false;
    const claim = answer.claims[index];
    if (
      (support.claimType !== "source-cited" &&
        !claim.evidenceIds.some(
          (id) => supplied.get(id)?.kind === "evidence",
        )) ||
      (support.claimType === "interpretation" &&
        !claim.evidenceIds.some((id) => supplied.get(id)?.kind === "knowledge"))
    )
      return false;
    const seen = new Set<string>();
    return support.fields.every((field) => {
      if (
        !hasKeys(field, ["documentId", "field", "value"]) ||
        !string(field.documentId) ||
        !/^(PAYMENT|HOST|HISTORY|STATUS)-ROW-[1-9][0-9]*$/.test(
          field.documentId,
        ) ||
        !string(field.field) ||
        !field.field ||
        field.field.length > 200 ||
        !string(field.value) ||
        field.value.length > 5000 ||
        !claim.evidenceIds.includes(field.documentId)
      )
        return false;
      const doc = supplied.get(field.documentId);
      const key = canonical([field.documentId, field.field]);
      if (!doc || doc.kind !== "evidence" || seen.has(key)) return false;
      seen.add(key);
      try {
        const row = parseEvidenceJson(doc.content);
        return (
          record(row) &&
          Object.hasOwn(row, field.field) &&
          row[field.field] === field.value &&
          (!field.value.trim() || claim.text.includes(field.value))
        );
      } catch {
        return false;
      }
    });
  });
}
function validateDetail(value: JobDetail, caseId: string, expected?: Job) {
  validateJob(value, caseId);
  if (
    expected &&
    ["id", "evidenceId", "evidenceHash", "evidenceVersion", "question"].some(
      (key) => value[key as keyof Job] !== expected[key as keyof Job],
    )
  )
    throw new Error(
      "The investigation changed its saved question or evidence identity. No answer has been accepted.",
    );
  if (!documents(value.documents))
    throw new Error(
      "The investigation's preserved source documents are unreadable.",
    );
  if (value.status !== "COMPLETED") return;
  const answer = value.answer;
  if (
    !answer ||
    answer.snapshotId !== value.evidenceId ||
    answer.evidenceHash !== value.evidenceHash ||
    answer.question !== value.question ||
    answer.mode !== "model-generated" ||
    answer.answerComposition !== "joined-model-claims" ||
    !string(answer.answerId) ||
    !string(answer.generatedAt) ||
    !documents(answer.citations) ||
    !Array.isArray(answer.claims) ||
    !answer.claims.length ||
    !answer.claims.every(
      (claim) =>
        string(claim?.text) &&
        !!claim.text.trim() &&
        strings(claim.evidenceIds) &&
        claim.evidenceIds.length > 0,
    ) ||
    !strings(answer.unknowns) ||
    !strings(answer.nextChecks) ||
    answer.answer !== answer.claims.map((claim) => claim.text).join("\n\n")
  )
    throw new Error(
      "The completed model answer does not match the saved evidence and cited-claims format. No answer has been accepted.",
    );
  const supplied = new Map(value.documents.map((doc) => [doc.id, doc]));
  const cited = new Set(answer.citations.map((doc) => doc.id));
  if (
    !answer.citations.every(
      (doc) =>
        supplied.has(doc.id) &&
        canonical(doc) === canonical(supplied.get(doc.id)),
    ) ||
    !answer.claims.every((claim) =>
      claim.evidenceIds.every((id) => cited.has(id)),
    ) ||
    !string(answer.retrieval?.method) ||
    !strings(answer.retrieval.documentIds) ||
    !answer.retrieval.documentIds.every((id) => supplied.has(id))
  )
    throw new Error(
      "A model citation does not match the investigation's preserved sources. No answer has been accepted.",
    );
  if (
    answer.model?.provider !== "ollama" ||
    !string(answer.model.name) ||
    !answer.model.name ||
    !Number.isSafeInteger(answer.model.actualCalls) ||
    answer.model.actualCalls < 1 ||
    !Number.isFinite(answer.model.durationMs) ||
    answer.model.durationMs < 0 ||
    ![answer.model.promptTokens, answer.model.completionTokens].every(
      (count) => count === null || (Number.isSafeInteger(count) && count >= 0),
    )
  )
    throw new Error(
      "The answer does not confirm a completed local model call. No answer has been accepted.",
    );
  if (
    answer.rag !== undefined &&
    !validRagReceipt(answer.rag, answer, supplied)
  )
    throw new Error(
      "The answer's checked field metadata does not match its claims and preserved source rows. No answer has been accepted.",
    );
}
async function request<T>(
  path: string,
  signal: AbortSignal,
  options: RequestInit = {},
  timeoutMilliseconds = 30000,
) {
  const controller = new AbortController();
  let timedOut = false;
  const abort = () => controller.abort();
  if (signal.aborted) controller.abort();
  else signal.addEventListener("abort", abort, { once: true });
  const timer = window.setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMilliseconds);
  try {
    return await api<T>(path, { ...options, signal: controller.signal });
  } catch (failure) {
    if (timedOut)
      throw new ApiError(
        504,
        "CASE_STATUS_TIMEOUT",
        `The API did not respond within ${timeoutMilliseconds / 1000} seconds. The saved job may still be running; refresh its status.`,
      );
    throw failure;
  } finally {
    window.clearTimeout(timer);
    signal.removeEventListener("abort", abort);
  }
}
function Failure({ error }: { error: Error }) {
  return (
    <div className="notice danger" role="alert">
      <TriangleAlert size={17} />
      <div>
        <strong>{error.message}</strong>
        {error instanceof ApiError && error.requestId && (
          <small>Request {error.requestId}</small>
        )}
      </div>
    </div>
  );
}
function Doc({
  doc,
  setRef,
}: {
  doc: Document;
  setRef?: (node: HTMLDetailsElement | null) => void;
}) {
  return (
    <details className="case-investigation-document" ref={setRef}>
      <summary>
        <strong>{doc.title}</strong>
        <small>
          {doc.id} ·{" "}
          {doc.kind === "evidence" ? "Export evidence" : "Source guidance"}
        </small>
      </summary>
      <p>
        <strong>Source:</strong> {doc.source.file}
        {doc.source.sheet && ` · ${doc.source.sheet}`}
        {doc.source.range && ` · ${doc.source.range}`}
        {doc.source.locator && ` · ${doc.source.locator}`}
      </p>
      <pre>{doc.content}</pre>
    </details>
  );
}
function mergeJobs(previous: Job[], incoming: Job[]) {
  incoming = incoming.map((value) => {
    const {
      documents: _documents,
      answer: _answer,
      ...summary
    } = value as JobDetail;
    return summary;
  });
  const prior = new Map(previous.map((job) => [job.id, job]));
  const merged = incoming.map((job) =>
    prior.has(job.id) && !pending(prior.get(job.id)!) && pending(job)
      ? prior.get(job.id)!
      : prior.get(job.id)?.cancellationRequested && pending(job)
        ? { ...job, cancellationRequested: true, phase: "CANCELLING" }
        : job,
  );
  return [
    ...merged,
    ...previous.filter((job) => !incoming.some((item) => item.id === job.id)),
  ].sort((a, b) => {
    const aTime = Date.parse(a.createdAt),
      bTime = Date.parse(b.createdAt);
    if (!Number.isFinite(aTime) || !Number.isFinite(bTime))
      return b.createdAt.localeCompare(a.createdAt);
    if (aTime !== bTime) return bTime - aTime;
    // Java Instant retains nanoseconds; Date.parse rounds these down to milliseconds.
    const remainder = (value: string) =>
      Number(
        (value.match(/\.(\d{1,9})(?:Z|[+-]\d{2}:\d{2})$/)?.[1] ?? "")
          .padEnd(9, "0")
          .slice(3),
      );
    return (
      remainder(b.createdAt) - remainder(a.createdAt) ||
      b.id.localeCompare(a.id)
    );
  });
}
export function CaseInvestigation(props: Props) {
  return <WorkbenchView key={props.caseId} {...props} />;
}

function WorkbenchView({
  caseId,
  caseNumber,
  canWrite: roleCanWrite,
  user,
  archived = false,
  resolved = false,
  evidenceRevision = 0,
  presentation = "case",
  requestedEvidenceId,
  onEvidenceChange,
}: Props) {
  const base = `/payment-cases/${encodeURIComponent(caseId)}`;
  const casePath = `/payment-cases/${encodeURIComponent(caseNumber || caseId)}`;
  const [workbench, setWorkbench] = useState<Workbench | null>(null);
  const lifecycleProp = useRef(archived);
  const isArchived =
    lifecycleProp.current !== archived
      ? archived
      : workbench?.lifecycleState
        ? workbench.lifecycleState === "ARCHIVED"
        : archived;
  useEffect(() => {
    if (lifecycleProp.current === archived) return;
    lifecycleProp.current = archived;
    setWorkbench((current) =>
      current
        ? { ...current, lifecycleState: archived ? "ARCHIVED" : "ACTIVE" }
        : current,
    );
  }, [archived]);
  const resolvedProp = useRef(resolved);
  const isResolved =
    resolvedProp.current !== resolved
      ? resolved
      : workbench?.status
        ? workbench.status === "RESOLVED"
        : resolved;
  useEffect(() => {
    if (resolvedProp.current === resolved) return;
    resolvedProp.current = resolved;
    setWorkbench((current) =>
      current
        ? { ...current, status: resolved ? "RESOLVED" : "OPEN" }
        : current,
    );
  }, [resolved]);
  const canWrite = roleCanWrite && !isArchived && !isResolved;
  const [jobs, setJobs] = useState<Job[]>([]);
  const [localEvidence, setSelectedEvidence] = useState("");
  const selectedEvidence = requestedEvidenceId ?? localEvidence;
  const [loadedContext, setContext] = useState<Context | null>(null);
  const [selectedJob, setSelectedJob] = useState("");
  const [detail, setDetail] = useState<JobDetail | null>(null);
  const [tab, setTab] = useState<Tab>(
    presentation === "qa" ? "investigations" : "timeline",
  );
  const [question, setQuestion] = useState("");
  const [submittedQuestion, setSubmittedQuestion] = useState("");
  useUnsavedChanges(
    !!question.trim() && question !== submittedQuestion,
    "Investigation question",
  );
  const [submitting, setSubmitting] = useState(false);
  const [browserRun, setBrowserRun] = useState<BrowserRun | null>(null);
  const [loading, setLoading] = useState(true);
  const [contextLoading, setContextLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [loadError, setLoadError] = useState<Error | null>(null);
  const [contextError, setContextError] = useState<Error | null>(null);
  const [detailError, setDetailError] = useState<Error | null>(null);
  const [submitError, setSubmitError] = useState<Error | null>(null);
  const [pollError, setPollError] = useState<Error | null>(null);
  const [revision, setRevision] = useState(0);
  const [contextRevision, setContextRevision] = useState(0);
  const [pollRevision, setPollRevision] = useState(0);
  const [detailRevision, setDetailRevision] = useState(0);
  const [focusedDoc, setFocusedDoc] = useState("");
  const [pinLoading, setPinLoading] = useState(false);
  const [pinError, setPinError] = useState<Error | null>(null);
  const [cancelling, setCancelling] = useState<string | null>(null);
  const [cancelError, setCancelError] = useState<{
    jobId: string;
    error: Error;
  } | null>(null);
  const cancelRequest = useRef<AbortController | null>(null);
  const submitRequest = useRef<AbortController | null>(null);
  const idempotency = useRef<{ signature: string; key: string } | null>(null);
  const cachedDetails = useRef(new Map<string, JobDetail>());
  const selectedJobRef = useRef(selectedJob);
  selectedJobRef.current = selectedJob;
  const jobsRef = useRef(jobs);
  jobsRef.current = jobs;
  const sourceRefs = useRef(new Map<string, HTMLDetailsElement>());
  const answerRefs = useRef(new Map<string, HTMLDetailsElement>());
  const evidence = workbench?.evidence.find(
    (item) => item.id === selectedEvidence,
  );
  const context =
    loadedContext?.evidenceId === evidence?.id &&
    loadedContext?.evidenceHash === evidence?.evidenceHash
      ? loadedContext
      : null;
  const unavailableVersion =
    !!selectedEvidence && !!workbench && !evidence && !pinLoading;
  const currentJob = jobs.find((job) => job.id === selectedJob);
  const pendingIds = jobs
    .filter(pending)
    .map((job) => job.id)
    .sort()
    .join("|");
  const active = !!pendingIds || submitting;
  function observeTiming(values: Job[]) {
    const now = performance.now();
    setBrowserRun((current) => {
      const job = values.find((value) => value.id === current?.jobId);
      return current && job ? observeBrowserRun(current, job, now) : current;
    });
  }
  useEffect(
    () => () => {
      submitRequest.current?.abort();
      cancelRequest.current?.abort();
    },
    [],
  );
  function canCancel(job: Job) {
    const role = user?.role.toUpperCase();
    return (
      job.queueVersion === "case-job-v1" &&
      pending(job) &&
      (role === "REVIEWER" ||
        (role === "ANALYST" && job.createdBy === user?.id))
    );
  }
  async function cancel(job: Job) {
    if (!canCancel(job) || cancelRequest.current || job.cancellationRequested)
      return;
    const controller = new AbortController();
    cancelRequest.current = controller;
    setCancelling(job.id);
    setCancelError(null);
    try {
      const value = await request<Job>(
        `${base}/investigations/${encodeURIComponent(job.id)}/cancel`,
        controller.signal,
        { method: "POST", body: "{}" },
      );
      if (controller.signal.aborted) return;
      validateJob(value, caseId);
      if (
        [
          "id",
          "evidenceId",
          "evidenceHash",
          "evidenceVersion",
          "question",
          "createdBy",
        ].some((key) => value[key as keyof Job] !== job[key as keyof Job])
      )
        throw new Error(
          "The cancellation response does not match this saved question. Refresh its status before retrying.",
        );
      if (pending(value) && !value.cancellationRequested)
        throw new Error(
          "Cancellation has not been acknowledged. The investigation may still be running.",
        );
      observeTiming([value]);
      setJobs((current) => mergeJobs(current, [value]));
      // Status replies carry summaries. Fetch the preserved body through the normal detail/poll path.
      cachedDetails.current.delete(value.id);
      setPollRevision((current) => current + 1);
      if (!pending(value)) setRevision((current) => current + 1);
    } catch (failure) {
      if (!controller.signal.aborted)
        setCancelError({ jobId: job.id, error: failure as Error });
    } finally {
      if (!controller.signal.aborted) setCancelling(null);
      if (cancelRequest.current === controller) cancelRequest.current = null;
    }
  }
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setLoadError(null);
    request<Workbench>(`${base}/workbench`, controller.signal)
      .then((value) => {
        if (controller.signal.aborted) return;
        validateWorkbench(value, caseId);
        const returnedJobs = mergeJobs(
          value.investigations,
          value.activeInvestigations ?? [],
        );
        observeTiming(returnedJobs);
        setWorkbench((current) => ({
          ...value,
          evidence: mergeHistory(
            value.evidence,
            current?.evidence.filter((item) => item.id === selectedEvidence) ??
              [],
          ),
        }));
        setJobs((current) => mergeJobs(current, returnedJobs));
        setSelectedEvidence(
          (current) => current || value.latestEvidenceId || "",
        );
        setSelectedJob(
          (current) =>
            current ||
            returnedJobs.find(pending)?.id ||
            value.investigations[0]?.id ||
            "",
        );
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setLoadError(failure);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [base, caseId, evidenceRevision, revision]);
  useEffect(() => {
    const controller = new AbortController();
    setPinError(null);
    if (!selectedEvidence || !workbench?.evidencePage || evidence) {
      setPinLoading(false);
      return () => controller.abort();
    }
    setPinLoading(true);
    request<Evidence>(
      `${base}/evidence/${encodeURIComponent(selectedEvidence)}/summary`,
      controller.signal,
    )
      .then((value) => {
        validateEvidence(value, caseId);
        if (value.id !== selectedEvidence)
          throw new Error(
            "The selected evidence response changed its identity.",
          );
        if (!controller.signal.aborted)
          setWorkbench((current) =>
            current
              ? {
                  ...current,
                  evidence: mergeHistory(current.evidence, [value]),
                }
              : current,
          );
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setPinError(failure);
      })
      .finally(() => {
        if (!controller.signal.aborted) setPinLoading(false);
      });
    return () => controller.abort();
  }, [base, caseId, selectedEvidence, !!workbench?.evidencePage, evidence?.id]);
  useEffect(() => {
    const controller = new AbortController();
    setContext(null);
    setContextError(null);
    setFocusedDoc("");
    if (!evidence) {
      setContextLoading(false);
      return () => controller.abort();
    }
    setContextLoading(true);
    request<Context>(
      `${base}/evidence/${encodeURIComponent(evidence.id)}/context`,
      controller.signal,
    )
      .then((value) => {
        if (controller.signal.aborted) return;
        validateContext(value, evidence);
        setContext(value);
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setContextError(failure);
      })
      .finally(() => {
        if (!controller.signal.aborted) setContextLoading(false);
      });
    return () => controller.abort();
  }, [
    base,
    selectedEvidence,
    evidence?.evidenceHash,
    evidenceRevision,
    contextRevision,
  ]);
  function remember(value: JobDetail) {
    cachedDetails.current.delete(value.id);
    cachedDetails.current.set(value.id, value);
    if (cachedDetails.current.size > 3)
      cachedDetails.current.delete(cachedDetails.current.keys().next().value!);
  }
  useEffect(() => {
    const controller = new AbortController();
    setDetail(null);
    setDetailError(null);
    setDetailLoading(false);
    if (!currentJob) return () => controller.abort();
    const cached = cachedDetails.current.get(currentJob.id);
    if (cached && !pending(cached)) {
      setDetail(cached);
      return () => controller.abort();
    }
    if (pending(currentJob)) {
      setDetailLoading(true);
      return () => controller.abort();
    }
    setDetailLoading(true);
    request<JobDetail>(
      `${base}/investigations/${encodeURIComponent(currentJob.id)}`,
      controller.signal,
    )
      .then((value) => {
        if (controller.signal.aborted) return;
        validateDetail(value, caseId, currentJob);
        remember(value);
        setDetail(value);
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setDetailError(failure);
      })
      .finally(() => {
        if (!controller.signal.aborted) setDetailLoading(false);
      });
    return () => controller.abort();
  }, [base, caseId, selectedJob, currentJob?.status, detailRevision]);
  useEffect(() => {
    if (!pendingIds) {
      setPollError(null);
      return;
    }
    const controller = new AbortController();
    let timer: number | undefined;
    const started = Date.now();
    setPollError(null);
    async function poll() {
      if (controller.signal.aborted) return;
      try {
        if (Date.now() - started >= 30 * 60 * 1000)
          throw new Error(
            "Automatic status updates paused after 30 minutes. The job may still run. Refresh status to continue following it.",
          );
        const values = await Promise.all(
          pendingIds.split("|").map(async (id) => {
            const value = await request<JobDetail>(
              `${base}/investigations/${encodeURIComponent(id)}`,
              controller.signal,
            );
            validateDetail(
              value,
              caseId,
              jobsRef.current.find((job) => job.id === id),
            );
            return value;
          }),
        );
        if (controller.signal.aborted) return;
        observeTiming(values);
        for (const value of values) {
          remember(value);
          if (selectedJobRef.current === value.id) {
            setDetail(value);
            setDetailLoading(false);
          }
        }
        setJobs((current) => mergeJobs(current, values));
        if (values.some((value) => !pending(value)))
          setRevision((value) => value + 1);
        if (values.some(pending)) timer = window.setTimeout(poll, 2000);
      } catch (failure) {
        if (!controller.signal.aborted) {
          setPollError(failure as Error);
          setDetailLoading(false);
        }
      }
    }
    void poll();
    return () => {
      controller.abort();
      window.clearTimeout(timer);
    };
  }, [base, caseId, pendingIds, pollRevision]);
  useEffect(() => {
    if (tab !== "evidence" || !focusedDoc) return;
    const node = sourceRefs.current.get(focusedDoc);
    if (node) {
      node.open = true;
      node.scrollIntoView?.({ behavior: "smooth", block: "center" });
    }
  }, [tab, focusedDoc, context]);
  async function run(event: FormEvent) {
    event.preventDefault();
    if (
      !canWrite ||
      !context ||
      !evidence ||
      contextLoading ||
      active ||
      submitRequest.current ||
      context.nonEmptyRows === 0
    )
      return;
    const submitted = question.trim();
    setSubmitError(null);
    if (!submitted || submitted.length > 2000) {
      setSubmitError(
        new Error("Enter a question between 1 and 2,000 characters."),
      );
      return;
    }
    const controller = new AbortController();
    submitRequest.current = controller;
    setBrowserRun({
      startedAt: performance.now(),
      question: submitted,
      phase: "preparing",
    });
    setSubmitting(true);
    const body = JSON.stringify({
      question: submitted,
      evidenceId: context.evidenceId,
      evidenceHash: context.evidenceHash,
    });
    if (idempotency.current?.signature !== body)
      idempotency.current = { signature: body, key: crypto.randomUUID() };
    try {
      const value = await request<Job>(
        `${base}/investigations`,
        controller.signal,
        {
          method: "POST",
          body,
          headers: { "Idempotency-Key": idempotency.current.key },
        },
        165000,
      );
      if (controller.signal.aborted) return;
      validateJob(value, caseId);
      if (
        value.question !== submitted ||
        value.evidenceId !== context.evidenceId ||
        value.evidenceHash !== context.evidenceHash ||
        value.evidenceVersion !== context.evidenceVersion
      )
        throw new Error(
          "The saved investigation does not match the submitted question and evidence. Refresh its status before retrying.",
        );
      idempotency.current = null;
      setSubmittedQuestion(question);
      const observedAt = performance.now();
      setBrowserRun((current) =>
        current ? observeBrowserRun(current, value, observedAt) : null,
      );
      setJobs((current) => mergeJobs(current, [value]));
      setSelectedJob(value.id);
      setTab("investigations");
      setRevision((value) => value + 1);
    } catch (failure) {
      if (!controller.signal.aborted) {
        setSubmitError(failure as Error);
        const rejected =
          failure instanceof ApiError &&
          [400, 401, 403, 404, 409, 413, 422, 429].includes(failure.status);
        const observedAt = performance.now();
        setBrowserRun((current) =>
          current
            ? {
                ...current,
                phase: rejected ? "rejected" : "unknown",
                ...(rejected ? { stoppedAt: observedAt } : {}),
              }
            : null,
        );
      }
    } finally {
      if (!controller.signal.aborted) setSubmitting(false);
      if (submitRequest.current === controller) submitRequest.current = null;
    }
  }
  const rendered = detail?.id === selectedJob ? detail : null;
  const answer = rendered?.status === "COMPLETED" ? rendered.answer : undefined;
  const sourceLink = (id: string) => (
    <button
      type="button"
      className="text-button"
      key={id}
      onClick={() => {
        const node = answerRefs.current.get(`${rendered!.id}:${id}`);
        if (node) {
          node.open = true;
          node.scrollIntoView?.({ behavior: "smooth", block: "center" });
        }
      }}
    >
      <FileText size={12} /> {id} <ArrowUpRight size={12} />
    </button>
  );
  return (
    <section
      className="case-investigation"
      aria-labelledby="case-investigation-title"
    >
      <div className="page-heading">
        <div>
          <div className="eyebrow">
            {presentation === "qa"
              ? "SAVED CASE INVESTIGATIONS"
              : "CASE WORKBENCH"}
          </div>
          <h2 id="case-investigation-title">
            {presentation === "qa"
              ? "Questions and saved answers"
              : "Investigate the evidence"}
          </h2>
          <p>
            Explore source records, ask a question and retain the answer with
            the exact evidence version used.
          </p>
        </div>
        <div className="heading-actions">
          <a
            className="primary"
            href={`${casePath}?report=1`}
            onClick={navigateLink}
          >
            <Download size={15} aria-hidden="true" /> Export PDF
          </a>
          <button
            className="secondary"
            disabled={loading || submitting}
            onClick={() => {
              setRevision((value) => value + 1);
              setPollRevision((value) => value + 1);
            }}
          >
            <RefreshCw size={15} /> Refresh workbench
          </button>
        </div>
      </div>
      {loadError && <Failure error={loadError} />}
      {!workbench && loading && (
        <p role="status">
          <LoaderCircle className="spin" size={16} /> Loading saved workbench…
        </p>
      )}
      {workbench && (
        <>
          {workbench.activeInvestigationPage?.nextCursor && (
            <div
              className="notice neutral case-investigation-more-active"
              role="note"
            >
              <p>
                More active investigations are available. Open the investigation
                list to load and follow them.
              </p>
              <button
                type="button"
                className="secondary"
                onClick={() => setTab("investigations")}
              >
                View active investigations
              </button>
            </div>
          )}
          <div className="panel case-investigation-version">
            <div>
              <label htmlFor="case-investigation-evidence">
                Investigation evidence version
              </label>
              <select
                id="case-investigation-evidence"
                value={selectedEvidence}
                disabled={active || !workbench.evidence.length}
                onChange={(event) => {
                  if (!onEvidenceChange)
                    setSelectedEvidence(event.target.value);
                  onEvidenceChange?.(event.target.value);
                  setSubmitError(null);
                }}
              >
                {unavailableVersion && (
                  <option value={selectedEvidence}>
                    Requested evidence version unavailable
                  </option>
                )}
                {workbench.evidence.length ? (
                  workbench.evidence.map((item) => (
                    <option key={item.id} value={item.id}>
                      Version {item.version} · {human(item.sourceKind)} ·{" "}
                      {item.createdAt}
                      {item.id === workbench.latestEvidenceId
                        ? " · Latest"
                        : ""}
                    </option>
                  ))
                ) : (
                  <option value="">No saved evidence</option>
                )}
              </select>
              <HistoryMore<Evidence>
                caseId={caseId}
                path={`${base}/evidence`}
                page={workbench.evidencePage}
                loaded={workbench.evidence.length}
                label="evidence versions"
                disabled={active || loading}
                validate={(item) => validateEvidence(item, caseId)}
                onPage={(page) =>
                  setWorkbench((current) =>
                    current
                      ? {
                          ...current,
                          evidence: mergeHistory(current.evidence, page.items),
                          evidencePage: page,
                        }
                      : current,
                  )
                }
                onRefresh={() => setRevision((current) => current + 1)}
              />
              {pinLoading && (
                <p role="status">Loading selected evidence version…</p>
              )}
              {pinError && <Failure error={pinError} />}
            </div>
            <span className="badge">
              {context
                ? `${context.nonEmptyRows} source rows`
                : "Saved evidence"}
            </span>
          </div>
          {unavailableVersion && (
            <p className="notice danger" role="alert">
              The requested evidence version is unavailable for this case.
              Select an available version before asking a question.
            </p>
          )}
          {presentation === "qa" && !workbench.evidence.length && (
            <div className="notice neutral">
              <FileText size={17} />
              <div>
                <strong>Attach evidence before asking a question</strong>
                <p>
                  Open the case and save its Inquiry API results, four Excel
                  files or manual / JSON records.
                </p>
                <a href={casePath} onClick={navigateLink}>
                  Collect case evidence <ArrowUpRight size={13} />
                </a>
              </div>
            </div>
          )}
          {selectedEvidence &&
            evidence &&
            selectedEvidence !== workbench.latestEvidenceId && (
              <p className="notice neutral">
                You selected an earlier evidence version. A new investigation
                will use that version explicitly.
              </p>
            )}
          <div className="case-investigation-layout">
            <section
              className="panel case-investigation-records"
              aria-label="Evidence workbench"
            >
              <div
                className="case-investigation-tabs"
                role="tablist"
                aria-label="Workbench views"
              >
                {(
                  [
                    ["timeline", "Timeline"],
                    ["evidence", "Evidence"],
                    ["investigations", "Investigations"],
                    ["audit", "Audit trail"],
                  ] as const
                ).map(([key, label]) => (
                  <button
                    key={key}
                    role="tab"
                    id={`case-investigation-tab-${key}`}
                    aria-selected={tab === key}
                    aria-controls={`case-investigation-panel-${key}`}
                    onClick={() => setTab(key)}
                  >
                    {label}
                    {key === "investigations" && <span>{jobs.length}</span>}
                  </button>
                ))}
              </div>
              <div
                role="tabpanel"
                id={`case-investigation-panel-${tab}`}
                aria-labelledby={`case-investigation-tab-${tab}`}
              >
                {(tab === "timeline" || tab === "evidence") && (
                  <>
                    {contextLoading && (
                      <p role="status">
                        <LoaderCircle className="spin" size={16} /> Loading
                        selected evidence context…
                      </p>
                    )}
                    {contextError && (
                      <>
                        <Failure error={contextError} />
                        <p className="muted">
                          This version cannot currently be investigated. Check
                          the attached rows and source limits, or select another
                          version.
                        </p>
                        <button
                          className="secondary"
                          onClick={() =>
                            setContextRevision((value) => value + 1)
                          }
                        >
                          Retry evidence context
                        </button>
                      </>
                    )}
                    {!selectedEvidence && (
                      <div className="case-investigation-empty">
                        <FileText size={26} />
                        <h3>Attach evidence to start</h3>
                        <p>
                          Use the evidence section above to save inquiry
                          results, Excel files or manual records.
                        </p>
                      </div>
                    )}
                    {context?.warnings.length ? (
                      <div className="notice neutral">
                        <TriangleAlert size={16} />
                        <div>
                          <strong>Source limitations</strong>
                          <ul>
                            {context.warnings.map((warning, index) => (
                              <li key={index}>{warning}</li>
                            ))}
                          </ul>
                        </div>
                      </div>
                    ) : null}
                  </>
                )}
                {tab === "timeline" && context && (
                  <>
                    <h3>Exported record timeline</h3>
                    <p className="muted">
                      These entries describe source records and raw fields. They
                      do not establish a complete lifecycle or prove an
                      operational event.
                    </p>
                    {context.timeline.length ? (
                      <ol className="case-investigation-timeline">
                        {context.timeline.map((row) => (
                          <li key={row.id}>
                            <div className="case-investigation-timeline-dot" />
                            <div>
                              <div className="case-investigation-record-heading">
                                <strong>{row.label}</strong>
                                <span>{row.group}</span>
                              </div>
                              <time>
                                {row.timestamp ?? "Timestamp not supplied"}
                              </time>
                              <button
                                className="text-button"
                                onClick={() => {
                                  setTab("evidence");
                                  setFocusedDoc(row.documentId);
                                }}
                              >
                                <FileText size={12} /> {row.documentId}
                                <ArrowUpRight size={12} />
                              </button>
                              <details>
                                <summary>Raw fields</summary>
                                <dl className="case-investigation-values">
                                  {Object.entries(row.fields).map(
                                    ([key, value]) => (
                                      <div key={key}>
                                        <dt>{key}</dt>
                                        <dd>
                                          {value === "" ? (
                                            <span className="muted">
                                              (blank)
                                            </span>
                                          ) : (
                                            value
                                          )}
                                        </dd>
                                      </div>
                                    ),
                                  )}
                                </dl>
                              </details>
                            </div>
                          </li>
                        ))}
                      </ol>
                    ) : (
                      <p>
                        No dated or undated row entries were returned for this
                        version.
                      </p>
                    )}
                  </>
                )}
                {tab === "evidence" && context && (
                  <>
                    <h3>Evidence and source guidance</h3>
                    <p className="muted">
                      Exact documents made available for this selected version.
                      Answer citations preserve their own job's documents.
                    </p>
                    {context.documents.map((doc) => (
                      <Doc
                        key={`${context.evidenceId}:${doc.id}`}
                        doc={doc}
                        setRef={(node) => {
                          if (node) sourceRefs.current.set(doc.id, node);
                          else sourceRefs.current.delete(doc.id);
                        }}
                      />
                    ))}
                    <details className="case-investigation-provenance">
                      <summary>Context fingerprints</summary>
                      <p>
                        Evidence: <code>{context.evidenceHash}</code>
                      </p>
                      <p>
                        Guidance: <code>{context.guidanceHash}</code>
                      </p>
                    </details>
                  </>
                )}
                {tab === "investigations" && (
                  <>
                    <h3>Saved investigations</h3>
                    <p className="muted">
                      Every submitted question is a separate saved job. Select
                      one to inspect its result and preserved sources.
                      {presentation === "qa" &&
                        " Questions from all evidence versions of this case appear here; each answer retains its own version."}
                    </p>
                    {jobs.length ? (
                      <div className="case-investigation-jobs">
                        {jobs.map((job) => (
                          <button
                            key={job.id}
                            className={selectedJob === job.id ? "selected" : ""}
                            aria-pressed={selectedJob === job.id}
                            onClick={() => setSelectedJob(job.id)}
                          >
                            <span className="case-investigation-job-top">
                              <strong>{job.question}</strong>
                              <span
                                className={`case-investigation-status ${job.status.toLowerCase()}`}
                              >
                                {human(job.status)}
                              </span>
                            </span>
                            <small>
                              Evidence version {job.evidenceVersion} ·{" "}
                              {job.createdAt}
                            </small>
                            <small>
                              {job.createdBy} · {job.id}
                            </small>
                            {jobPhase(job) && <small>{jobPhase(job)}</small>}
                            <SavedInvestigationTiming job={job} />
                          </button>
                        ))}
                      </div>
                    ) : (
                      <p>
                        No investigations have been requested for this case.
                      </p>
                    )}
                    <HistoryMore<Job>
                      caseId={caseId}
                      path={`${base}/investigations`}
                      page={workbench.investigationPage}
                      loaded={jobs.length}
                      label="investigations"
                      disabled={loading}
                      validate={(item) => validateJob(item, caseId)}
                      onPage={(page) => {
                        setJobs((current) => mergeJobs(current, page.items));
                        setWorkbench((current) =>
                          current
                            ? { ...current, investigationPage: page }
                            : current,
                        );
                      }}
                      onRefresh={() => setRevision((current) => current + 1)}
                    />
                    <HistoryMore<Job>
                      caseId={caseId}
                      path={`${base}/investigations?status=ACTIVE`}
                      page={
                        workbench.activeInvestigationPage?.nextCursor
                          ? workbench.activeInvestigationPage
                          : undefined
                      }
                      loaded={jobs.filter(pending).length}
                      label="active investigations"
                      disabled={loading}
                      validate={(item) => {
                        validateJob(item, caseId);
                        if (!pending(item))
                          throw new Error(
                            "Active investigation status is unreadable.",
                          );
                      }}
                      onPage={(page) => {
                        setJobs((current) => mergeJobs(current, page.items));
                        setWorkbench((current) =>
                          current
                            ? { ...current, activeInvestigationPage: page }
                            : current,
                        );
                      }}
                      onRefresh={() => setRevision((current) => current + 1)}
                    />
                  </>
                )}
                {tab === "audit" && (
                  <>
                    <h3>Recorded case activity</h3>
                    <p className="muted">
                      Actions and timestamps returned by the case audit record.
                    </p>
                    {workbench.audit.length ? (
                      <ol className="case-investigation-audit">
                        {workbench.audit.map((item) => (
                          <li key={item.id}>
                            <History size={15} />
                            <div>
                              <strong>{human(item.action)}</strong>
                              <p>{item.detail}</p>
                              <small>
                                {item.occurredAt} · {item.actor}
                              </small>
                            </div>
                          </li>
                        ))}
                      </ol>
                    ) : (
                      <p>No audit records were returned.</p>
                    )}
                    <HistoryMore<Workbench["audit"][number]>
                      caseId={caseId}
                      path={`${base}/activity`}
                      page={workbench.activityPage}
                      loaded={workbench.audit.length}
                      label="activity records"
                      disabled={loading}
                      validate={validateActivity}
                      onPage={(page) =>
                        setWorkbench((current) =>
                          current
                            ? {
                                ...current,
                                audit: mergeHistory(current.audit, page.items),
                                activityPage: page,
                              }
                            : current,
                        )
                      }
                      onRefresh={() => setRevision((current) => current + 1)}
                    />
                  </>
                )}
              </div>
            </section>
            <aside
              className="panel case-investigation-question"
              aria-labelledby="case-investigation-question-title"
            >
              <div className="section-title">
                <h3 id="case-investigation-question-title">
                  <MessageSquare size={18} /> Investigation question
                </h3>
                <span className="mode-badge ollama">
                  <Sparkles size={12} /> Local model
                </span>
              </div>
              <p className="muted">
                The model reads the selected version and its source guidance. No
                question runs automatically.
              </p>
              <form onSubmit={run}>
                <div className="case-investigation-suggestions">
                  <label htmlFor="case-investigation-suggestion">
                    Suggested questions
                  </label>
                  <select
                    id="case-investigation-suggestion"
                    value={
                      suggestedQuestions.find(
                        (item) => item.question === question,
                      )?.question ?? ""
                    }
                    disabled={!canWrite || active}
                    onChange={(event) => {
                      setQuestion(event.target.value);
                      setSubmitError(null);
                    }}
                    aria-describedby="case-investigation-suggestion-help"
                  >
                    <option value="" disabled>
                      Choose a question or write your own
                    </option>
                    {suggestedQuestions.map((item) => (
                      <option key={item.label} value={item.question}>
                        {item.label}
                      </option>
                    ))}
                  </select>
                  <p id="case-investigation-suggestion-help" className="muted">
                    Select a suggestion to fill the question below. You can edit
                    it before running the investigation.
                  </p>
                </div>
                <label htmlFor="case-investigation-question">
                  Your case question
                </label>
                <textarea
                  id="case-investigation-question"
                  rows={5}
                  maxLength={2000}
                  value={question}
                  readOnly={!canWrite}
                  disabled={active}
                  onChange={(event) => {
                    setQuestion(event.target.value);
                    setSubmitError(null);
                  }}
                  placeholder="Ask what the recorded fields explain and which evidence is still needed."
                />
                {canWrite && (
                  <DraftTextControls
                    scope={`${caseId}:investigation-question`}
                    value={question}
                    onRestore={(value) => {
                      setQuestion(value);
                      setSubmitError(null);
                    }}
                    maxLength={2000}
                    disabled={active}
                  />
                )}
                {canWrite && (
                  <CaseReadiness
                    caseId={caseId}
                    evidence={evidence}
                    question={question}
                    disabled={
                      active ||
                      contextLoading ||
                      !context ||
                      context.nonEmptyRows === 0
                    }
                  />
                )}
                <div className="case-investigation-submit">
                  <small>{question.length.toLocaleString()} / 2,000</small>
                  {canWrite ? (
                    <button
                      type="submit"
                      className="primary"
                      disabled={
                        active ||
                        !context ||
                        contextLoading ||
                        context.nonEmptyRows === 0
                      }
                    >
                      {active ? (
                        <LoaderCircle className="spin" size={15} />
                      ) : (
                        <Send size={15} />
                      )}
                      {submitting
                        ? "Saving request…"
                        : pendingIds
                          ? "Investigation in progress…"
                          : "Run investigation"}
                    </button>
                  ) : (
                    <p className="muted">
                      {isArchived
                        ? "This case is archived. Saved investigations remain available. Restore it to submit a new question."
                        : isResolved
                          ? "This case is resolved. Reopen it in Case management before submitting a new question."
                          : "Your role can read saved investigations. An analyst or reviewer can submit questions."}
                    </p>
                  )}
                </div>
              </form>
              <InvestigationTimer
                run={
                  pendingIds && browserRun?.stoppedAt !== undefined
                    ? null
                    : browserRun
                }
                pendingJob={jobs.find(pending)}
                statusUnavailable={!!pollError}
              />
              {context && context.nonEmptyRows === 0 && (
                <p className="notice neutral">
                  This version has no source rows. Attach source records before
                  running an investigation.
                </p>
              )}
              {contextError && tab !== "timeline" && tab !== "evidence" && (
                <>
                  <Failure error={contextError} />
                  <button
                    className="secondary"
                    onClick={() => setContextRevision((value) => value + 1)}
                  >
                    Retry evidence context
                  </button>
                </>
              )}
              {submitError && <Failure error={submitError} />}
              {pollError && (
                <>
                  <Failure error={pollError} />
                  <button
                    className="secondary"
                    onClick={() => setPollRevision((value) => value + 1)}
                  >
                    Refresh job status
                  </button>
                </>
              )}
              <div
                className="notice neutral case-investigation-quality"
                role="note"
              >
                <TriangleAlert size={16} />
                <div>
                  <strong>Experimental model answers</strong>
                  <p>
                    Live validation has found unsupported claims. Source
                    membership checks do not verify factual correctness. Check
                    field values and conclusions against the cited records.
                  </p>
                </div>
              </div>
              {currentJob && (
                <section
                  className="case-investigation-result"
                  aria-labelledby="case-investigation-result-title"
                >
                  <h3 id="case-investigation-result-title">
                    Selected investigation
                  </h3>
                  <p className="case-investigation-result-question">
                    {currentJob.question}
                  </p>
                  <div className="case-investigation-result-meta">
                    <span
                      className={`case-investigation-status ${currentJob.status.toLowerCase()}`}
                    >
                      {human(currentJob.status)}
                    </span>
                    <span>Evidence version {currentJob.evidenceVersion}</span>
                  </div>
                  <SavedInvestigationTiming
                    job={rendered ?? currentJob}
                    breakdown
                  />
                  {jobPhase(currentJob) && (
                    <p className="case-investigation-phase" role="status">
                      {jobPhase(currentJob)}
                    </p>
                  )}
                  {pending(currentJob) && currentJob.waitingReason && (
                    <p className="notice neutral">
                      {currentJob.waitingReason.message}
                    </p>
                  )}
                  {canCancel(currentJob) && (
                    <div className="case-investigation-cancel">
                      <button
                        type="button"
                        className="secondary"
                        disabled={
                          !!cancelling || currentJob.cancellationRequested
                        }
                        onClick={() => void cancel(currentJob)}
                      >
                        {cancelling === currentJob.id
                          ? "Requesting cancellation…"
                          : currentJob.cancellationRequested
                            ? "Cancellation requested"
                            : cancelError?.jobId === currentJob.id
                              ? "Retry cancellation"
                              : "Cancel investigation"}
                      </button>
                      <small>
                        {currentJob.cancellationRequested
                          ? "Status updates continue until the worker acknowledges cancellation. No completed answer will be attached to a cancelled question."
                          : "Cancel this saved question. Its question and evidence history stay available."}
                      </small>
                    </div>
                  )}
                  {pending(currentJob) &&
                    cancelError?.jobId === currentJob.id && (
                      <div className="case-investigation-cancel-error">
                        <Failure error={cancelError.error} />
                        <p>
                          Cancellation is unconfirmed. Refresh status or retry
                          cancellation for this same job.
                        </p>
                        <button
                          type="button"
                          className="secondary"
                          onClick={() => setPollRevision((value) => value + 1)}
                        >
                          Refresh cancellation status
                        </button>
                      </div>
                    )}
                  {currentJob.status === "CANCELLED" && (
                    <p className="notice neutral">
                      This investigation was cancelled. Its question and saved
                      evidence remain available; no completed answer is
                      attached.
                    </p>
                  )}
                  <SourceSelectionCoverage
                    selection={
                      rendered
                        ? frozenSelection(rendered)
                        : currentJob.selection
                    }
                  />
                  {currentJob.evidenceId !== workbench.latestEvidenceId && (
                    <p className="notice neutral">
                      This investigation used an earlier evidence version. Its
                      result does not describe the latest attached evidence.
                    </p>
                  )}
                  {currentJob.evidenceId !== selectedEvidence && (
                    <p className="muted">
                      Its answer uses evidence version{" "}
                      {currentJob.evidenceVersion}; the left panel currently
                      shows a different version. The answer's own cited
                      documents remain below.
                    </p>
                  )}
                  {detailLoading && !pending(currentJob) && (
                    <p role="status">Loading saved answer…</p>
                  )}
                  {detailError && (
                    <>
                      <Failure error={detailError} />
                      <button
                        className="secondary"
                        onClick={() => {
                          cachedDetails.current.delete(currentJob.id);
                          setDetailRevision((value) => value + 1);
                        }}
                      >
                        Retry saved answer
                      </button>
                    </>
                  )}
                  {currentJob.status === "FAILED" && (
                    <div className="notice danger" role="alert">
                      <TriangleAlert size={16} />
                      <div>
                        <strong>
                          {currentJob.error?.message ??
                            rendered?.error?.message ??
                            "The investigation failed without a completed model answer."}
                        </strong>
                        <small>
                          {currentJob.error?.code ?? rendered?.error?.code}
                        </small>
                        <p>
                          No answer is available for this job. A new explicit
                          run creates a separate job.
                        </p>
                      </div>
                    </div>
                  )}
                  {answer && rendered && (
                    <>
                      <h4>Model-generated answer</h4>
                      <p className="muted">
                        Generated for this question from the selected evidence
                        version.
                      </p>
                      <div className="case-investigation-claims">
                        {answer.claims.map((claim, index) => (
                          <article key={index}>
                            <p>{claim.text}</p>
                            <div>{claim.evidenceIds.map(sourceLink)}</div>
                            {!!answer.rag?.claimSupports[index].fields
                              .length && (
                              <details className="case-investigation-provenance">
                                <summary>Checked source fields</summary>
                                <p>
                                  Explicit field quotations checked against
                                  saved rows; other wording and interpretations
                                  still require review.
                                </p>
                                {answer.rag.claimSupports[index].fields.map(
                                  (field) => (
                                    <p
                                      key={`${field.documentId}:${field.field}`}
                                    >
                                      <code>{field.field}</code>:{" "}
                                      <code>
                                        {field.value === ""
                                          ? "(blank)"
                                          : field.value}
                                      </code>{" "}
                                      {sourceLink(field.documentId)}
                                    </p>
                                  ),
                                )}
                              </details>
                            )}
                          </article>
                        ))}
                      </div>
                      <section
                        className="case-investigation-unknowns"
                        aria-labelledby="case-investigation-unknowns-title"
                      >
                        <h4 id="case-investigation-unknowns-title">
                          What this evidence cannot establish
                        </h4>
                        {answer.unknowns.length ? (
                          <ul>
                            {answer.unknowns.map((item, index) => (
                              <li key={index}>{item}</li>
                            ))}
                          </ul>
                        ) : (
                          <p>
                            The model listed no unknowns. This does not
                            establish that the evidence is complete.
                          </p>
                        )}
                      </section>
                      <h4>Suggested next checks</h4>
                      {answer.nextChecks.length ? (
                        <ul className="case-investigation-next-checks">
                          {answer.nextChecks.map((item, index) => (
                            <li key={index}>{item}</li>
                          ))}
                        </ul>
                      ) : (
                        <p className="muted">No next checks were returned.</p>
                      )}
                      <div className="case-investigation-model-meta">
                        <strong>{answer.model.name}</strong>
                        <span>
                          {answer.model.actualCalls} actual model{" "}
                          {answer.model.actualCalls === 1 ? "call" : "calls"}
                        </span>
                        <span>
                          Model generation:{" "}
                          {(answer.model.durationMs / 1000).toFixed(1)}s
                        </span>
                        <small>
                          {answer.model.promptTokens ?? "Not reported"} input /{" "}
                          {answer.model.completionTokens ?? "not reported"}{" "}
                          output tokens
                        </small>
                      </div>
                      <h4>Sources cited by this answer</h4>
                      {answer.citations.map((doc) => (
                        <Doc
                          key={`${rendered.id}:${doc.id}`}
                          doc={doc}
                          setRef={(node) => {
                            if (node)
                              answerRefs.current.set(
                                `${rendered.id}:${doc.id}`,
                                node,
                              );
                            else
                              answerRefs.current.delete(
                                `${rendered.id}:${doc.id}`,
                              );
                          }}
                        />
                      ))}
                      <details className="case-investigation-provenance">
                        <summary>Saved answer provenance</summary>
                        <p>
                          Job: <code>{rendered.id}</code>
                        </p>
                        <p>
                          Answer: <code>{answer.answerId}</code>
                        </p>
                        <p>Generated: {answer.generatedAt}</p>
                        <p>
                          Evidence: <code>{answer.evidenceHash}</code>
                        </p>
                        <p>Retrieval: {answer.retrieval.method}</p>
                        {answer.rag && (
                          <>
                            <p>
                              Pipeline: <code>{answer.rag.pipeline}</code>
                            </p>
                            <p>
                              Prompt fingerprint:{" "}
                              <code>{answer.rag.promptHash}</code>
                            </p>
                          </>
                        )}
                        <p>
                          Validation: source membership and response structure;
                          human factual review required.
                        </p>
                      </details>
                    </>
                  )}
                </section>
              )}
            </aside>
          </div>
        </>
      )}
    </section>
  );
}
