import { useEffect, useRef, useState, type ChangeEvent } from "react";
import {
  Braces,
  Database,
  Download,
  FileSpreadsheet,
  FileText,
  LoaderCircle,
  Plus,
  RefreshCw,
  Save,
  Trash2,
  TriangleAlert,
} from "lucide-react";
import { api, ApiError, human } from "./api";
import { navigateLink } from "./routing";
import { paymentCaseNumber, paymentCasePath } from "./paymentCaseIdentity";
import { caseListPath, validateCasePage } from "./paymentCaseList";
import { parseEvidenceJson } from "./evidenceJson";
import {
  HistoryMore,
  historyMeta,
  mergeHistory,
  validateHistoryPage,
  type HistoryPage,
  type HistoryPageMeta,
} from "./caseHistory";
import { useUnsavedChanges } from "./unsavedChanges";
import {
  EvidenceProvenance,
  isSourceNull,
  isSourceOmitted,
  validateEvidenceUpstream,
  type EvidenceUpstream,
} from "./EvidenceProvenance";

const GROUPS = ["PAYMENT", "HOST", "HISTORY", "STATUS"] as const;
type Group = (typeof GROUPS)[number];
type Row = Record<string, string>;
type Payload = {
  schemaVersion: "fcr-case-evidence-v1";
  payment: { reference: string; orgBank: string; orgBranch: string };
  sourceTimezone: string;
  sections: Record<Group, { rows: Row[]; note: string }>;
};
type Config = {
  schemaVersion: Payload["schemaVersion"];
  api: { enabled: boolean; mode: "DISABLED" | "BANK_API" };
  limits: { maxRowsPerSection: number; maxFileBytes: number };
  groups: { key: Group; functionName: string; columns: string[] }[];
  template: Payload;
};
type Summary = {
  id: string;
  version: number;
  caseId: string;
  sourceKind: string;
  createdAt: string;
  createdBy: string;
  evidenceHash: string;
  warnings: string[];
  coverage: Record<
    Group,
    { rowCount: number; completion: "UNVERIFIED" | "COMPLETE" }
  >;
};
type Snapshot = Summary & { payload: Payload; upstream?: EvidenceUpstream };
type Props = {
  caseId: string;
  canWrite: boolean;
  onSaved?: () => void;
  archived?: boolean;
  resolved?: boolean;
};
type Mode = "inquiry" | "excel" | "manual";

const record = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === "object" && !Array.isArray(value);
function keys(value: Record<string, unknown>, expected: readonly string[]) {
  return (
    Object.keys(value).length === expected.length &&
    expected.every((key) => Object.hasOwn(value, key))
  );
}
const strings = (value: unknown): value is string[] =>
  Array.isArray(value) && value.every((item) => typeof item === "string");
const bounded = (value: unknown): value is string =>
  typeof value === "string" && value.length <= 4000;

class EvidenceIdentityMismatch extends Error {
  constructor(
    readonly supplied: Payload["payment"],
    readonly expected: Payload["payment"],
  ) {
    super(
      "This file belongs to a different payment case. No evidence was saved and your existing form has been preserved.",
    );
  }
}
function exactFields(
  value: Record<string, unknown>,
  expected: readonly string[],
  location: string,
) {
  const missing = expected.filter((key) => !Object.hasOwn(value, key));
  const extra = Object.keys(value).filter((key) => !expected.includes(key));
  if (missing.length || extra.length)
    throw new Error(
      `${location}: ${[missing.length ? `missing fields: ${missing.join(", ")}` : "", extra.length ? `unexpected fields: ${extra.map((key) => key.slice(0, 100)).join(", ")}` : ""].filter(Boolean).join("; ")}. Use the current case's JSON template; keep empty values as quoted text.`,
    );
}
function validatePayload(
  value: unknown,
  config: Config,
  draft = false,
): asserts value is Payload {
  if (!record(value))
    throw new Error(
      "The JSON root must be an evidence object. Use the downloaded JSON template.",
    );
  if (Object.hasOwn(value, "payload") && !Object.hasOwn(value, "sections"))
    throw new Error(
      "This is a saved snapshot wrapper. Upload the evidence object inside its payload field, using the downloaded template.",
    );
  exactFields(
    value,
    ["schemaVersion", "payment", "sourceTimezone", "sections"],
    "Evidence",
  );
  if (value.schemaVersion !== config.schemaVersion)
    throw new Error(
      `schemaVersion must be "${config.schemaVersion}". Download a current JSON template.`,
    );
  if (!record(value.payment))
    throw new Error(
      "payment must contain reference, orgBank and orgBranch as text.",
    );
  exactFields(value.payment, ["reference", "orgBank", "orgBranch"], "payment");
  for (const key of ["reference", "orgBank", "orgBranch"] as const) {
    const field = value.payment[key];
    if (typeof field !== "string" || !field || field.length > 4000)
      throw new Error(
        `payment.${key} must be non-empty quoted text. Do not convert identifiers to JSON numbers.`,
      );
  }
  if (
    !bounded(value.sourceTimezone) ||
    (!draft && !value.sourceTimezone.trim()) ||
    value.sourceTimezone.length > 100
  )
    throw new Error(
      "sourceTimezone must be text of 1–100 characters. Use UNKNOWN if it is unconfirmed.",
    );
  if (!record(value.sections))
    throw new Error(
      "sections must contain the four evidence groups: PAYMENT, HOST, HISTORY and STATUS.",
    );
  exactFields(value.sections, GROUPS, "sections");
  for (const group of config.groups) {
    const section = value.sections[group.key];
    const location = `sections.${group.key}`;
    if (!record(section))
      throw new Error(`${location} must contain a rows array and a text note.`);
    exactFields(section, ["rows", "note"], location);
    if (!bounded(section.note))
      throw new Error(
        `${location}.note must be quoted text of at most 4,000 characters. Use "" when no note was supplied.`,
      );
    if (!Array.isArray(section.rows))
      throw new Error(
        `${location}.rows must be an array. Use [] when no rows were supplied.`,
      );
    if (section.rows.length > config.limits.maxRowsPerSection)
      throw new Error(
        `${location}.rows exceeds the limit of ${config.limits.maxRowsPerSection} rows.`,
      );
    section.rows.forEach((row: unknown, index: number) => {
      const at = `${group.key} row ${index + 1}`;
      if (!record(row))
        throw new Error(
          `${at} must be an object with the configured column names.`,
        );
      exactFields(row, group.columns, at);
      for (const column of group.columns) {
        if (!bounded(row[column]))
          throw new Error(
            `${at}.${column} must be quoted text of at most 4,000 characters. Keep numbers as exact strings and use "" for blank values.`,
          );
      }
    });
  }
  const supplied = value.payment as Payload["payment"];
  if (
    !(["reference", "orgBank", "orgBranch"] as const).every(
      (key) =>
        supplied[key as keyof typeof supplied] ===
        config.template.payment[key as keyof typeof supplied],
    )
  )
    throw new EvidenceIdentityMismatch(supplied, config.template.payment);
}
function validateConfig(value: Config) {
  if (
    value?.schemaVersion !== "fcr-case-evidence-v1" ||
    typeof value.api?.enabled !== "boolean" ||
    !["DISABLED", "BANK_API"].includes(value.api.mode) ||
    !Number.isSafeInteger(value.limits?.maxRowsPerSection) ||
    value.limits.maxRowsPerSection < 1 ||
    value.limits.maxRowsPerSection > 500 ||
    !Number.isSafeInteger(value.limits?.maxFileBytes) ||
    value.limits.maxFileBytes < 1 ||
    value.limits.maxFileBytes > 5242880 ||
    !Array.isArray(value.groups) ||
    value.groups.length !== 4 ||
    new Set(value.groups.map((group) => group.key)).size !== 4 ||
    !value.groups.every(
      (group) =>
        GROUPS.includes(group.key) &&
        typeof group.functionName === "string" &&
        strings(group.columns) &&
        group.columns.length > 0 &&
        group.columns.length <= 250 &&
        new Set(group.columns).size === group.columns.length &&
        group.columns.every((column) => !!column && column.length <= 200),
    ) ||
    !value.template?.payment ||
    !Object.values(value.template.payment).every(
      (field) => typeof field === "string" && !!field,
    )
  )
    throw new Error(
      "The evidence configuration is unreadable. Refresh before entering evidence.",
    );
  validatePayload(value.template, value);
}
function validateSummary(value: Summary, caseId: string) {
  if (
    !value ||
    value.caseId !== caseId ||
    !Number.isSafeInteger(value.version) ||
    value.version < 1 ||
    ![
      value.id,
      value.sourceKind,
      value.createdAt,
      value.createdBy,
      value.evidenceHash,
    ].every((field) => typeof field === "string" && !!field) ||
    !strings(value.warnings) ||
    !record(value.coverage) ||
    !GROUPS.every(
      (key) =>
        Number.isSafeInteger(value.coverage[key]?.rowCount) &&
        value.coverage[key].rowCount >= 0 &&
        ["UNVERIFIED", "COMPLETE"].includes(value.coverage[key].completion),
    )
  )
    throw new Error(
      "The saved evidence response does not match this case or is unreadable.",
    );
}
function validateSnapshot(value: Snapshot, caseId: string, config: Config) {
  validateSummary(value, caseId);
  validatePayload(value.payload, config);
  if (
    GROUPS.some(
      (key) =>
        value.payload.sections[key].rows.length !==
        value.coverage[key].rowCount,
    )
  )
    throw new Error("The saved evidence row counts do not match its payload.");
  validateEvidenceUpstream(value.upstream, value.payload.sections);
}
function blankRow(config: Config, key: Group): Row {
  const payment = config.template.payment;
  const identity: Row = {
    REFTXNNUMBER: payment.reference,
    REF_TXN_NO: payment.reference,
    COD_ORG_BANK: payment.orgBank,
    COD_ORG_BRN: payment.orgBranch,
  };
  return Object.fromEntries(
    config.groups
      .find((group) => group.key === key)!
      .columns.map((column) => [column, identity[column] ?? ""]),
  );
}
function Failure({ error }: { error: Error }) {
  return (
    <div className="notice danger" role="alert">
      <TriangleAlert size={18} />
      <div>
        <strong>{error.message}</strong>
        {error instanceof ApiError && error.requestId && (
          <small>Request {error.requestId}</small>
        )}
      </div>
    </div>
  );
}
async function readEvidence<T>(
  path: string,
  { signal }: { signal: AbortSignal },
) {
  const controller = new AbortController();
  const abort = () => controller.abort();
  if (signal.aborted) controller.abort();
  else signal.addEventListener("abort", abort, { once: true });
  let timedOut = false;
  const timer = window.setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, 30000);
  try {
    return await api<T>(path, { signal: controller.signal });
  } catch (failure) {
    if (timedOut)
      throw new Error(
        "Reading saved evidence took longer than 30 seconds. Retry the request; your form has been preserved.",
      );
    throw failure;
  } finally {
    window.clearTimeout(timer);
    signal.removeEventListener("abort", abort);
  }
}
function ImportProblem({
  error,
  filename,
  caseId,
}: {
  error: Error;
  filename: string;
  caseId: string;
}) {
  const mismatch = error instanceof EvidenceIdentityMismatch ? error : null;
  const [matches, setMatches] = useState<{ id: string; caseNumber?: string }[]>(
    [],
  );
  const [loading, setLoading] = useState(false);
  const [lookupFailed, setLookupFailed] = useState(false);
  const [truncated, setTruncated] = useState(false);
  useEffect(() => {
    setMatches([]);
    setLookupFailed(false);
    setTruncated(false);
    if (!mismatch) {
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    readEvidence<unknown>(
      caseListPath({
        lifecycle: "ACTIVE",
        bank: mismatch.supplied.orgBank,
        branch: mismatch.supplied.orgBranch,
        reference: mismatch.supplied.reference,
        page: 1,
        pageSize: 10,
      }),
      { signal: controller.signal },
    )
      .then((result) => {
        if (controller.signal.aborted) return;
        const found = validateCasePage(
          result,
          (
            item,
          ): item is {
            id: string;
            caseNumber?: string;
            reference: string;
            orgBank: string;
            orgBranch: string;
          } =>
            record(item) &&
            ["id", "reference", "orgBank", "orgBranch"].every(
              (key) => typeof item[key] === "string",
            ) &&
            !!item.id &&
            (item.caseNumber === undefined ||
              typeof item.caseNumber === "string"),
        );
        setTruncated(found.total > found.items.length);
        setMatches(
          found.items.filter(
            (item) =>
              record(item) &&
              typeof item.id === "string" &&
              (item.caseNumber === undefined ||
                typeof item.caseNumber === "string") &&
              item.id !== caseId &&
              (["reference", "orgBank", "orgBranch"] as const).every(
                (key) =>
                  item[key] ===
                  mismatch.supplied[key as keyof Payload["payment"]],
              ),
          ) as { id: string; caseNumber?: string }[],
        );
      })
      .catch(() => {
        if (!controller.signal.aborted) setLookupFailed(true);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [mismatch, caseId]);
  return (
    <div className="case-evidence-import-problem">
      <p>
        <strong>Could not load {filename}</strong>
      </p>
      <Failure error={error} />
      {mismatch && (
        <>
          <div className="case-evidence-import-identities">
            <div>
              <strong>Payment in the file</strong>
              <p>{mismatch.supplied.reference}</p>
              <small>
                Bank {mismatch.supplied.orgBank} · Branch{" "}
                {mismatch.supplied.orgBranch}
              </small>
            </div>
            <div>
              <strong>Currently open case</strong>
              <p>{mismatch.expected.reference}</p>
              <small>
                Bank {mismatch.expected.orgBank} · Branch{" "}
                {mismatch.expected.orgBranch}
              </small>
            </div>
          </div>
          {loading ? (
            <p role="status">Finding the matching case in your workspace…</p>
          ) : matches.length ? (
            <>
              <p>
                Open the matching case, then upload this file again. Its payment
                identifiers must stay unchanged.
              </p>
              {matches.map((item) => (
                <a
                  key={item.id}
                  className="secondary case-evidence-match-link"
                  href={paymentCasePath(item)}
                  onClick={navigateLink}
                >
                  {matches.length === 1
                    ? "Open matching payment case"
                    : `Open matching case ${paymentCaseNumber(item)}`}
                </a>
              ))}
            </>
          ) : (
            <p>
              {lookupFailed
                ? "The matching case lookup is unavailable."
                : truncated
                  ? "More matching cases are available in Case queue."
                  : "No active saved case matching all three identifiers is available in your workspace."}{" "}
              Use Case queue to find this payment within your authorized bank
              and branch.
            </p>
          )}
          {truncated && matches.length > 0 && (
            <p>
              Showing the first {matches.length} matching cases. More matches
              are available in Case queue.
            </p>
          )}
          {!loading && (
            <a
              className="text-button"
              href="/cases"
              data-case-queue-mode="payment"
              onClick={navigateLink}
            >
              Open Case queue
            </a>
          )}
        </>
      )}
    </div>
  );
}
function readFile(file: File, signal: AbortSignal) {
  return new Promise<ArrayBuffer>((resolve, reject) => {
    const reader = new FileReader();
    const abort = () => reader.abort();
    reader.onload = () => {
      signal.removeEventListener("abort", abort);
      resolve(reader.result as ArrayBuffer);
    };
    reader.onerror = () => {
      signal.removeEventListener("abort", abort);
      reject(
        new Error("The selected file could not be read. Choose it again."),
      );
    };
    reader.onabort = () => {
      signal.removeEventListener("abort", abort);
      reject(new DOMException("File reading was cancelled.", "AbortError"));
    };
    if (signal.aborted) {
      reject(new DOMException("File reading was cancelled.", "AbortError"));
      return;
    }
    signal.addEventListener("abort", abort, { once: true });
    reader.readAsArrayBuffer(file);
  });
}
function saveDownload(contents: string, filename: string) {
  const url = URL.createObjectURL(
    new Blob([contents], { type: "application/json" }),
  );
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

export function CaseEvidence(props: Props) {
  return <EvidenceWorkspace key={props.caseId} {...props} />;
}

function EvidenceWorkspace({
  caseId,
  canWrite,
  onSaved,
  archived = false,
  resolved = false,
}: Props) {
  const base = `/payment-cases/${encodeURIComponent(caseId)}/evidence`;
  const writable = canWrite && !archived && !resolved;
  const [config, setConfig] = useState<Config | null>(null);
  const [payload, setPayload] = useState<Payload | null>(null);
  const [savedPayload, setSavedPayload] = useState("");
  const [history, setHistory] = useState<Summary[]>([]);
  const [historyPage, setHistoryPage] = useState<HistoryPageMeta>();
  const [selectedId, setSelectedId] = useState("");
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [mode, setMode] = useState<Mode>("inquiry");
  const [group, setGroup] = useState<Group>("PAYMENT");
  const [rowIndex, setRowIndex] = useState(0);
  const [viewGroup, setViewGroup] = useState<Group>("PAYMENT");
  const [viewRow, setViewRow] = useState(0);
  const [jsonImported, setJsonImported] = useState(false);
  const [jsonName, setJsonName] = useState("");
  const [files, setFiles] = useState<Partial<Record<Group, File>>>({});
  const [excelTimezone, setExcelTimezone] = useState("UNKNOWN");
  const [savedExcel, setSavedExcel] = useState<{
    files: Partial<Record<Group, File>>;
    timezone: string;
  }>({ files: {}, timezone: "UNKNOWN" });
  const [error, setError] = useState<Error | null>(null);
  const [importError, setImportError] = useState<Error | null>(null);
  const [attemptedFile, setAttemptedFile] = useState("");
  const [historyError, setHistoryError] = useState<Error | null>(null);
  const [versionError, setVersionError] = useState<Error | null>(null);
  const [initialError, setInitialError] = useState<Error | null>(null);
  const [busy, setBusy] = useState<"reading" | "saving" | null>(null);
  const [loading, setLoading] = useState(true);
  const [versionLoading, setVersionLoading] = useState(false);
  const [historyRefreshing, setHistoryRefreshing] = useState(false);
  const [savedNotice, setSavedNotice] = useState("");
  const [revision, setRevision] = useState(0);
  const [versionRevision, setVersionRevision] = useState(0);
  const operation = useRef<AbortController | null>(null);
  const detailRequest = useRef<AbortController | null>(null);
  const historyRequest = useRef<AbortController | null>(null);
  const currentSnapshot = useRef<Snapshot | null>(null);
  const idempotency = useRef<{ signature: string; key: string } | null>(null);
  const manualDirty = !!payload && JSON.stringify(payload) !== savedPayload;
  const excelDirty =
    excelTimezone !== savedExcel.timezone ||
    GROUPS.some((key) => files[key] !== savedExcel.files[key]);
  useUnsavedChanges(manualDirty, "Manual / JSON evidence");
  useUnsavedChanges(excelDirty, "Excel evidence selection");
  useEffect(() => {
    if (writable) return;
    operation.current?.abort();
    operation.current = null;
    setBusy(null);
  }, [writable]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setInitialError(null);
    Promise.all([
      readEvidence<Config>(`${base}/config`, { signal: controller.signal }),
      readEvidence<HistoryPage<Summary>>(base, { signal: controller.signal }),
    ])
      .then(([nextConfig, result]) => {
        if (controller.signal.aborted) return;
        validateConfig(nextConfig);
        if (!Array.isArray(result?.items))
          throw new Error("The evidence version list is unreadable.");
        result.items.forEach((item) => validateSummary(item, caseId));
        if (result.total !== undefined)
          validateHistoryPage(result, caseId, (item) =>
            validateSummary(item, caseId),
          );
        setConfig(nextConfig);
        setPayload(structuredClone(nextConfig.template));
        setSavedPayload(JSON.stringify(nextConfig.template));
        setHistory(result.items);
        setHistoryPage(
          historyMeta(
            result.total === undefined ? undefined : result,
            result.items.length,
          ),
        );
        setSelectedId(result.items[0]?.id ?? "");
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setInitialError(failure);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => {
      controller.abort();
      operation.current?.abort();
      detailRequest.current?.abort();
      historyRequest.current?.abort();
      historyRequest.current = null;
    };
  }, [base, caseId, revision]);
  useEffect(() => {
    detailRequest.current?.abort();
    setVersionError(null);
    setViewGroup("PAYMENT");
    setViewRow(0);
    if (!selectedId || !config) {
      setSnapshot(null);
      setVersionLoading(false);
      return;
    }
    if (currentSnapshot.current?.id === selectedId) {
      setSnapshot(currentSnapshot.current);
      setVersionLoading(false);
      return;
    }
    const controller = new AbortController();
    detailRequest.current = controller;
    setSnapshot(null);
    setVersionLoading(true);
    readEvidence<Snapshot>(`${base}/${encodeURIComponent(selectedId)}`, {
      signal: controller.signal,
    })
      .then((value) => {
        if (controller.signal.aborted) return;
        validateSnapshot(value, caseId, config);
        if (value.id !== selectedId)
          throw new Error(
            "The response refers to a different evidence version.",
          );
        currentSnapshot.current = value;
        setSnapshot(value);
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setVersionError(failure);
      })
      .finally(() => {
        if (!controller.signal.aborted) setVersionLoading(false);
      });
    return () => controller.abort();
  }, [base, caseId, config, selectedId, versionRevision]);

  async function refreshHistory(signal?: AbortSignal) {
    if (signal?.aborted) return;
    historyRequest.current?.abort();
    const controller = new AbortController();
    historyRequest.current = controller;
    const abort = () => controller.abort();
    signal?.addEventListener("abort", abort, { once: true });
    setHistoryRefreshing(true);
    try {
      const refreshed = await readEvidence<HistoryPage<Summary>>(base, {
        signal: controller.signal,
      });
      if (!Array.isArray(refreshed?.items))
        throw new Error("The saved version list could not be refreshed.");
      refreshed.items.forEach((item) => validateSummary(item, caseId));
      if (refreshed.total !== undefined)
        validateHistoryPage(refreshed, caseId, (item) =>
          validateSummary(item, caseId),
        );
      if (!controller.signal.aborted) {
        setHistory((current) =>
          mergeHistory(
            refreshed.items,
            current.filter((item) => item.id === selectedId),
          ),
        );
        setHistoryPage(
          historyMeta(
            refreshed.total === undefined ? undefined : refreshed,
            refreshed.items.length,
          ),
        );
        setHistoryError(null);
      }
    } catch (failure) {
      if (!controller.signal.aborted) setHistoryError(failure as Error);
    } finally {
      signal?.removeEventListener("abort", abort);
      if (historyRequest.current === controller) {
        historyRequest.current = null;
        setHistoryRefreshing(false);
      }
    }
  }

  function edited(next: Payload) {
    setPayload(next);
    setJsonImported(false);
    setImportError(null);
    setError(null);
    setSavedNotice("");
  }
  function setRows(rows: Row[]) {
    if (!payload) return;
    edited({
      ...payload,
      sections: {
        ...payload.sections,
        [group]: { ...payload.sections[group], rows },
      },
    });
  }
  async function importJson(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file || !writable || !config || operation.current) return;
    setError(null);
    setImportError(null);
    setAttemptedFile(file.name);
    setSavedNotice("");
    if (
      !file.name.toLowerCase().endsWith(".json") ||
      file.size > config.limits.maxFileBytes
    ) {
      setImportError(new Error("Choose a JSON file no larger than 5 MiB."));
      return;
    }
    const controller = new AbortController();
    operation.current = controller;
    setBusy("reading");
    try {
      const bytes = await readFile(file, controller.signal);
      const uploaded: unknown = parseEvidenceJson(
        new TextDecoder("utf-8", { fatal: true }).decode(bytes),
      );
      const draft =
        record(uploaded) && uploaded.draftVersion === "case-evidence-draft-v1";
      if (draft) {
        exactFields(uploaded, ["draftVersion", "caseId", "payload"], "Draft");
        if (uploaded.caseId !== caseId)
          throw new Error(
            "This draft belongs to a different case. Your existing form has been preserved.",
          );
      }
      const value: unknown = draft ? uploaded.payload : uploaded;
      validatePayload(value, config, draft);
      if (controller.signal.aborted) return;
      if (
        manualDirty &&
        JSON.stringify(value) !== JSON.stringify(payload) &&
        !window.confirm(
          "Replace the unsaved evidence form with this file? Cancel to keep your current draft.",
        )
      )
        return;
      setPayload(value);
      setJsonImported(!draft);
      setJsonName(file.name);
      setGroup("PAYMENT");
      setRowIndex(0);
    } catch (failure) {
      if (!controller.signal.aborted)
        setImportError(
          failure instanceof SyntaxError
            ? new Error(
                "The file is not valid JSON. The existing form has been preserved.",
              )
            : failure instanceof TypeError
              ? new Error(
                  "The file is not readable UTF-8 JSON. Save it as UTF-8 and upload it again; your form has been preserved.",
                )
              : (failure as Error),
        );
    } finally {
      if (!controller.signal.aborted) setBusy(null);
      if (operation.current === controller) operation.current = null;
    }
  }
  async function submit() {
    if (!writable || !config || !payload || operation.current) return;
    historyRequest.current?.abort();
    historyRequest.current = null;
    setHistoryRefreshing(false);
    setError(null);
    setSavedNotice("");
    const controller = new AbortController();
    operation.current = controller;
    setBusy("saving");
    try {
      let route: string;
      let body: string | FormData;
      let signature: string;
      if (mode === "inquiry") {
        if (!config.api.enabled || config.api.mode !== "BANK_API")
          throw new Error("The inquiry API is disabled pending deployment.");
        route = "inquiry";
        body = "{}";
        signature = "inquiry:{}";
      } else if (mode === "excel") {
        if (!excelTimezone.trim() || excelTimezone.length > 100)
          throw new Error(
            "Enter the source timezone, or UNKNOWN when it is unconfirmed.",
          );
        if (GROUPS.some((key) => !files[key]))
          throw new Error("Choose all four Excel files before saving.");
        const form = new FormData();
        const hashes: string[] = [];
        for (const key of GROUPS) {
          const file = files[key]!;
          if (
            !file.name.toLowerCase().endsWith(".xlsx") ||
            file.size > config.limits.maxFileBytes
          )
            throw new Error(
              `${key} must be an .xlsx file no larger than 5 MiB.`,
            );
          const bytes = await readFile(file, controller.signal);
          const digest = await crypto.subtle.digest("SHA-256", bytes);
          hashes.push(
            `${key}:${file.name}:${Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("")}`,
          );
          form.append(key, file);
        }
        form.append("sourceTimezone", excelTimezone);
        route = "excel";
        body = form;
        signature = JSON.stringify(["excel", excelTimezone, hashes]);
      } else {
        validatePayload(payload, config);
        route = jsonImported ? "json" : "manual";
        body = JSON.stringify(payload);
        if (
          new TextEncoder().encode(body).byteLength > config.limits.maxFileBytes
        )
          throw new Error(
            "The evidence JSON is larger than 5 MiB. Reduce the supplied rows or field content.",
          );
        signature = `${route}:${body}`;
      }
      if (controller.signal.aborted) return;
      if (idempotency.current?.signature !== signature)
        idempotency.current = { signature, key: crypto.randomUUID() };
      const result = await api<Snapshot>(`${base}/${route}`, {
        method: "POST",
        body,
        headers: { "Idempotency-Key": idempotency.current.key },
        signal: controller.signal,
      });
      if (controller.signal.aborted) return;
      validateSnapshot(result, caseId, config);
      detailRequest.current?.abort();
      setVersionError(null);
      setVersionLoading(false);
      currentSnapshot.current = result;
      setSnapshot(result);
      setSelectedId(result.id);
      const { payload: _payload, ...summary } = result;
      setHistory((current) => [
        summary,
        ...current.filter((item) => item.id !== result.id),
      ]);
      setSavedNotice(
        `Evidence version ${result.version} saved. Previous versions remain unchanged.`,
      );
      idempotency.current = null;
      if (mode === "manual") setSavedPayload(JSON.stringify(payload));
      if (mode === "excel")
        setSavedExcel({ files: { ...files }, timezone: excelTimezone });
      onSaved?.();
      await refreshHistory(controller.signal);
    } catch (failure) {
      if (!controller.signal.aborted) setError(failure as Error);
    } finally {
      if (!controller.signal.aborted) setBusy(null);
      if (operation.current === controller) operation.current = null;
    }
  }
  function downloadJson() {
    if (!config) return;
    const value = structuredClone(config.template);
    for (const key of GROUPS)
      value.sections[key].rows = [blankRow(config, key)];
    saveDownload(JSON.stringify(value, null, 2), `case-evidence-template.json`);
  }
  function downloadDraft() {
    if (!payload || !config) return;
    const content = JSON.stringify(
      { draftVersion: "case-evidence-draft-v1", caseId, payload },
      null,
      2,
    );
    if (
      new TextEncoder().encode(content).byteLength > config.limits.maxFileBytes
    ) {
      setError(
        new Error(
          "This draft exceeds the 5 MiB upload limit. Reduce the field content before downloading a recoverable draft.",
        ),
      );
      return;
    }
    saveDownload(content, "case-evidence-draft.json");
  }
  if (loading)
    return (
      <section className="panel case-evidence">
        <p role="status">
          <LoaderCircle className="spin" size={16} /> Loading case evidence…
        </p>
      </section>
    );
  if (initialError)
    return (
      <section className="panel case-evidence">
        <Failure error={initialError} />
        <button
          className="secondary"
          onClick={() => setRevision((value) => value + 1)}
        >
          <RefreshCw size={15} /> Retry evidence setup
        </button>
      </section>
    );
  if (!config || !payload) return null;
  const selectedGroup = config.groups.find((item) => item.key === group)!;
  const rows = payload.sections[group].rows;
  const activeRow = rows[rowIndex];
  const savedRows = snapshot?.payload.sections[viewGroup].rows ?? [];
  return (
    <section
      className="panel case-evidence"
      aria-labelledby="case-evidence-title"
    >
      <div className="section-title">
        <h2 id="case-evidence-title">
          <FileText size={18} /> Case evidence
        </h2>
        <span className="badge">Versioned records</span>
      </div>
      <p className="muted">
        Collect the four inquiry result groups for this payment. Saving creates
        an immutable evidence version; it does not run an AI analysis or execute
        a payment.
      </p>
      <dl className="case-evidence-identity">
        <div>
          <dt>Payment reference</dt>
          <dd>{payload.payment.reference}</dd>
        </div>
        <div>
          <dt>Bank</dt>
          <dd>{payload.payment.orgBank}</dd>
        </div>
        <div>
          <dt>Branch</dt>
          <dd>{payload.payment.orgBranch}</dd>
        </div>
      </dl>
      <div
        className="case-evidence-tabs"
        role="tablist"
        aria-label="Evidence input method"
      >
        {(
          [
            ["inquiry", "Inquiry API", Database],
            ["excel", "Four Excel files", FileSpreadsheet],
            ["manual", "Manual + JSON", Braces],
          ] as const
        ).map(([key, label, Icon]) => (
          <button
            key={key}
            id={`case-evidence-tab-${key}`}
            role="tab"
            aria-selected={mode === key}
            aria-controls={`case-evidence-panel-${key}`}
            disabled={!writable || !!busy}
            onClick={() => {
              setMode(key);
              setError(null);
              setSavedNotice("");
            }}
          >
            <Icon size={16} aria-hidden="true" />
            {label}
          </button>
        ))}
      </div>
      {!writable ? (
        <p className="notice neutral">
          {archived
            ? "This case is archived. Saved evidence remains available below. Restore the case before adding a version."
            : resolved
              ? "This case is resolved. Reopen it in Case management before adding evidence. Saved versions remain available below."
              : "Your role can read saved evidence. An analyst or reviewer can add a version."}
        </p>
      ) : (
        <div
          role="tabpanel"
          id={`case-evidence-panel-${mode}`}
          aria-labelledby={`case-evidence-tab-${mode}`}
        >
          <fieldset disabled={!!busy} className="case-evidence-inputs">
            {mode === "inquiry" && (
              <div className="case-evidence-method">
                <h3>Fetch the four inquiry groups</h3>
                <p>
                  The configured inquiry API uses this case's payment reference,
                  bank and branch.
                </p>
                {config.api.enabled && config.api.mode === "BANK_API" ? (
                  <p className="notice neutral">
                    A successful request saves the returned evidence as a new
                    version. Review the source coverage and warnings.
                  </p>
                ) : (
                  <p className="notice neutral">
                    The inquiry API is disabled pending deployment. Use Excel or
                    manual evidence until the bank endpoint is configured.
                  </p>
                )}
              </div>
            )}
            {mode === "excel" && (
              <div className="case-evidence-method">
                <h3>Upload the four inquiry files</h3>
                <p>
                  Upload one .xlsx file per inquiry group, up to 5 MiB each.
                  Keep native headers and text values; an empty result needs its
                  header row. Source query completion remains subject to
                  validation.
                </p>
                <div className="case-evidence-files">
                  {config.groups.map((item) => (
                    <div key={item.key} data-file-selected={!!files[item.key]}>
                      <label htmlFor={`case-evidence-file-${item.key}`}>
                        {item.key} Excel file <small>{item.functionName}</small>
                      </label>
                      <input
                        id={`case-evidence-file-${item.key}`}
                        type="file"
                        accept=".xlsx"
                        onChange={(event) => {
                          const file = event.target.files?.[0];
                          setError(null);
                          setSavedNotice("");
                          if (file && file.size > config.limits.maxFileBytes) {
                            event.target.value = "";
                            setFiles((current) => ({
                              ...current,
                              [item.key]: undefined,
                            }));
                            setError(
                              new Error(
                                `${item.key} must be no larger than 5 MiB.`,
                              ),
                            );
                            return;
                          }
                          setFiles((current) => ({
                            ...current,
                            [item.key]: file,
                          }));
                        }}
                      />
                      <a
                        href={`/api${base}/template/${item.key}.xlsx`}
                        download
                      >
                        <Download size={13} /> Download {item.key} headers
                      </a>
                      {files[item.key] && (
                        <small>{files[item.key]!.name}</small>
                      )}
                    </div>
                  ))}
                </div>
                <label htmlFor="case-evidence-excel-timezone">
                  Excel source timezone
                </label>
                <input
                  id="case-evidence-excel-timezone"
                  value={excelTimezone}
                  maxLength={100}
                  onChange={(event) => {
                    setExcelTimezone(event.target.value);
                    setError(null);
                    setSavedNotice("");
                  }}
                />
                <p className="muted">
                  Use UNKNOWN until the source timezone is confirmed. The laptop
                  timezone does not establish the source timezone.
                </p>
              </div>
            )}
            {mode === "manual" && (
              <div className="case-evidence-method">
                <h3>Enter records or start from a JSON file</h3>
                <div className="case-evidence-json-tools">
                  <button
                    type="button"
                    className="secondary"
                    onClick={downloadJson}
                  >
                    <Download size={15} /> Download JSON template
                  </button>
                  <button
                    type="button"
                    className="secondary"
                    onClick={downloadDraft}
                  >
                    <Download size={15} /> Download form draft
                  </button>
                  <div>
                    <label htmlFor="case-evidence-json">
                      Upload JSON to fill the form
                    </label>
                    <input
                      id="case-evidence-json"
                      type="file"
                      accept=".json,application/json"
                      onChange={importJson}
                    />
                  </div>
                </div>
                {importError && (
                  <ImportProblem
                    error={importError}
                    filename={attemptedFile}
                    caseId={caseId}
                  />
                )}
                <p className="muted">
                  The template includes one blank row per group with this case's
                  identity filled where applicable. Remove unused rows. JSON
                  upload fills the form and requires an explicit save.
                </p>
                <p className="muted">
                  {manualDirty ? "Unsaved form changes. " : ""}Download form
                  draft keeps your current entries in a file on this device.
                  Upload that draft here to continue; it does not save evidence
                  to the case. Draft files contain the entered payment data.
                </p>
                {jsonName && (
                  <p className="case-evidence-json-note">
                    {jsonImported
                      ? `Loaded ${jsonName}. Review the rows below, then save. Unchanged values retain JSON upload provenance.`
                      : `Form edited after loading ${jsonName}. Saving records manual provenance.`}
                  </p>
                )}
                <label htmlFor="case-evidence-timezone">Source timezone</label>
                <input
                  id="case-evidence-timezone"
                  value={payload.sourceTimezone}
                  maxLength={100}
                  onChange={(event) =>
                    edited({ ...payload, sourceTimezone: event.target.value })
                  }
                />
                <p className="muted">
                  Keep UNKNOWN when unconfirmed. All field values remain text,
                  including identifiers, amounts, status codes and source dates.
                </p>
                <div className="case-evidence-row-tools">
                  <div>
                    <label htmlFor="case-evidence-group">Evidence group</label>
                    <select
                      id="case-evidence-group"
                      value={group}
                      onChange={(event) => {
                        setGroup(event.target.value as Group);
                        setRowIndex(0);
                      }}
                    >
                      {config.groups.map((item) => (
                        <option key={item.key} value={item.key}>
                          {item.key} · {payload.sections[item.key].rows.length}{" "}
                          {payload.sections[item.key].rows.length === 1
                            ? "row"
                            : "rows"}
                        </option>
                      ))}
                    </select>
                  </div>
                  <button
                    type="button"
                    className="secondary"
                    disabled={rows.length >= config.limits.maxRowsPerSection}
                    onClick={() => {
                      setRows([...rows, blankRow(config, group)]);
                      setRowIndex(rows.length);
                    }}
                  >
                    <Plus size={15} /> Add {group} row
                  </button>
                </div>
                <p className="muted">
                  {selectedGroup.functionName} · {rows.length} /{" "}
                  {config.limits.maxRowsPerSection} rows
                </p>
                {activeRow ? (
                  <>
                    <div className="case-evidence-row-tools">
                      <div>
                        <label htmlFor="case-evidence-row">Row to edit</label>
                        <select
                          id="case-evidence-row"
                          value={rowIndex}
                          onChange={(event) =>
                            setRowIndex(Number(event.target.value))
                          }
                        >
                          {rows.map((_, index) => (
                            <option key={index} value={index}>
                              Row {index + 1}
                            </option>
                          ))}
                        </select>
                      </div>
                      <button
                        type="button"
                        className="secondary"
                        onClick={() => {
                          setRows(
                            rows.filter((_, index) => index !== rowIndex),
                          );
                          setRowIndex(Math.max(0, rowIndex - 1));
                        }}
                      >
                        <Trash2 size={15} /> Remove selected row
                      </button>
                    </div>
                    <div className="case-evidence-fields">
                      {selectedGroup.columns.map((column) => (
                        <div key={column}>
                          <label htmlFor={`case-evidence-cell-${column}`}>
                            {column}
                          </label>
                          <input
                            id={`case-evidence-cell-${column}`}
                            aria-label={`${group} row ${rowIndex + 1} ${column}`}
                            value={activeRow[column]}
                            maxLength={4000}
                            onChange={(event) =>
                              setRows(
                                rows.map((row, index) =>
                                  index === rowIndex
                                    ? { ...row, [column]: event.target.value }
                                    : row,
                                ),
                              )
                            }
                          />
                        </div>
                      ))}
                    </div>
                  </>
                ) : (
                  <p className="case-evidence-empty">
                    No {group} rows entered. Add a row only when you have a
                    source record.
                  </p>
                )}
                <label htmlFor="case-evidence-note">{group} source note</label>
                <textarea
                  id="case-evidence-note"
                  rows={3}
                  maxLength={4000}
                  value={payload.sections[group].note}
                  onChange={(event) =>
                    edited({
                      ...payload,
                      sections: {
                        ...payload.sections,
                        [group]: {
                          ...payload.sections[group],
                          note: event.target.value,
                        },
                      },
                    })
                  }
                />
              </div>
            )}
          </fieldset>
          {error && <Failure error={error} />}
          <div className="case-evidence-save">
            <button
              className="primary"
              disabled={
                !!busy ||
                (mode === "inquiry" &&
                  (!config.api.enabled || config.api.mode !== "BANK_API"))
              }
              onClick={submit}
            >
              {busy ? (
                <LoaderCircle size={16} className="spin" />
              ) : (
                <Save size={16} />
              )}
              {busy === "reading"
                ? "Reading JSON…"
                : busy === "saving"
                  ? "Saving evidence…"
                  : mode === "inquiry"
                    ? "Fetch and save evidence"
                    : "Save new evidence version"}
            </button>
            <small>
              {mode === "manual"
                ? jsonImported
                  ? "Source: JSON upload"
                  : "Source: manual entry"
                : mode === "excel"
                  ? "Source: four Excel files"
                  : "Source: bank inquiry API"}
            </small>
          </div>
        </div>
      )}
      {savedNotice && (
        <p className="notice success" role="status">
          {savedNotice}
        </p>
      )}
      <section
        className="case-evidence-history"
        aria-labelledby="case-evidence-history-title"
      >
        <h3 id="case-evidence-history-title">Saved evidence versions</h3>
        <p className="muted">
          Select a version to inspect its original values, source coverage and
          warnings.
        </p>
        {history.length ? (
          <>
            <label htmlFor="case-evidence-version">Evidence version</label>
            <select
              id="case-evidence-version"
              value={selectedId}
              disabled={!!busy}
              onChange={(event) => setSelectedId(event.target.value)}
            >
              {history.map((item) => (
                <option key={item.id} value={item.id}>
                  Version {item.version} · {human(item.sourceKind)} ·{" "}
                  {item.createdAt}
                </option>
              ))}
            </select>
          </>
        ) : (
          <p>No evidence versions have been saved for this case.</p>
        )}
        {historyError && (
          <>
            <Failure error={historyError} />
            <button
              className="secondary"
              disabled={!!busy || historyRefreshing}
              onClick={() => void refreshHistory()}
            >
              <RefreshCw size={15} /> Retry saved versions
            </button>
          </>
        )}
        <HistoryMore<Summary>
          caseId={caseId}
          path={base}
          page={historyPage}
          loaded={history.length}
          label="evidence versions"
          disabled={!!busy || historyRefreshing}
          validate={(item) => validateSummary(item, caseId)}
          onPage={(page) => {
            setHistory((current) => mergeHistory(current, page.items));
            setHistoryPage(page);
          }}
          onRefresh={() => void refreshHistory()}
        />
        {historyRefreshing && (
          <p role="status">
            <LoaderCircle size={16} className="spin" /> Refreshing saved
            versions…
          </p>
        )}
        {versionError && (
          <>
            <Failure error={versionError} />
            <button
              className="secondary"
              disabled={!!busy || versionLoading}
              onClick={() => setVersionRevision((value) => value + 1)}
            >
              <RefreshCw size={15} /> Retry saved version
            </button>
          </>
        )}
        {versionLoading && (
          <p role="status">
            <LoaderCircle size={16} className="spin" /> Loading selected
            version…
          </p>
        )}
        {snapshot && snapshot.id === selectedId && !versionLoading && (
          <div className="case-evidence-snapshot">
            <dl className="case-evidence-identity">
              <div>
                <dt>Source</dt>
                <dd>{human(snapshot.sourceKind)}</dd>
              </div>
              <div>
                <dt>Saved by</dt>
                <dd>{snapshot.createdBy}</dd>
              </div>
              <div>
                <dt>Source timezone</dt>
                <dd>{snapshot.payload.sourceTimezone}</dd>
              </div>
            </dl>
            <div className="case-evidence-coverage">
              {GROUPS.map((key) => (
                <div key={key}>
                  <strong>{key}</strong>
                  <span>
                    {snapshot.coverage[key].rowCount}{" "}
                    {snapshot.coverage[key].rowCount === 1 ? "row" : "rows"}
                  </span>
                  <small>
                    {snapshot.coverage[key].completion === "COMPLETE"
                      ? "Query fetch recorded"
                      : "Query fetch unverified"}
                  </small>
                </div>
              ))}
            </div>
            {snapshot.warnings.length > 0 && (
              <div className="notice neutral">
                <TriangleAlert size={16} />
                <div>
                  <strong>Evidence limitations</strong>
                  <ul>
                    {snapshot.warnings.map((warning, index) => (
                      <li key={index}>{warning}</li>
                    ))}
                  </ul>
                </div>
              </div>
            )}
            <EvidenceProvenance upstream={snapshot.upstream} />
            <label htmlFor="case-evidence-saved-group">
              Saved evidence group
            </label>
            <select
              id="case-evidence-saved-group"
              value={viewGroup}
              onChange={(event) => {
                setViewGroup(event.target.value as Group);
                setViewRow(0);
              }}
            >
              {GROUPS.map((key) => (
                <option key={key} value={key}>
                  {key} · {snapshot.payload.sections[key].rows.length}{" "}
                  {snapshot.payload.sections[key].rows.length === 1
                    ? "row"
                    : "rows"}
                </option>
              ))}
            </select>
            {savedRows.length ? (
              <>
                <label htmlFor="case-evidence-saved-row">Saved row</label>
                <select
                  id="case-evidence-saved-row"
                  value={viewRow}
                  onChange={(event) => setViewRow(Number(event.target.value))}
                >
                  {savedRows.map((_, index) => (
                    <option key={index} value={index}>
                      Row {index + 1}
                    </option>
                  ))}
                </select>
                <dl className="case-evidence-values">
                  {config.groups
                    .find((item) => item.key === viewGroup)!
                    .columns.map((column) => (
                      <div key={column}>
                        <dt>{column}</dt>
                        <dd>
                          {isSourceOmitted(
                            snapshot.upstream,
                            viewGroup,
                            viewRow,
                            column,
                          ) ? (
                            <span className="muted">Not supplied</span>
                          ) : isSourceNull(
                              snapshot.upstream,
                              viewGroup,
                              viewRow,
                              column,
                            ) ? (
                            <span className="muted">(source null)</span>
                          ) : savedRows[viewRow]?.[column] === "" ? (
                            <span className="muted">(blank)</span>
                          ) : (
                            savedRows[viewRow]?.[column]
                          )}
                        </dd>
                      </div>
                    ))}
                </dl>
              </>
            ) : (
              <p>
                No {viewGroup} rows in this saved version. This alone does not
                establish that no event occurred.
              </p>
            )}
            <p>
              <strong>Source note:</strong>{" "}
              {snapshot.payload.sections[viewGroup].note || "No note supplied."}
            </p>
            <details>
              <summary>Immutable evidence fingerprint</summary>
              <code className="case-evidence-hash">
                {snapshot.evidenceHash}
              </code>
            </details>
          </div>
        )}
      </section>
    </section>
  );
}
