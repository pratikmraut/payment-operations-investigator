export interface User {
  id: string;
  name: string;
  role: string;
  tenantId: string;
}
export interface Session {
  user: User;
  csrfToken: string;
}
export interface CaseSummary {
  id: string;
  paymentId: string;
  title: string;
  description: string;
  priority: string;
  status: string;
  amountMinor: number;
  currency: string;
  rail: string;
  merchant?: string;
  domain?: string;
  evidenceVersion?: number;
  evidenceHash?: string;
  createdAt: string;
  updatedAt: string;
  version: number;
  tags: string[];
}
export interface TimelineEvent {
  id: string;
  occurredAt: string;
  type: string;
  source: string;
  status: string;
  summary: string;
  correlationId?: string;
  attributes?: Record<string, unknown>;
}
export interface LedgerEntry {
  id: string;
  type: string;
  amountMinor: number;
  currency: string;
  occurredAt: string;
  reference: string;
}
export interface Webhook {
  id: string;
  providerEventId: string;
  type: string;
  occurredAt: string;
  receivedAt: string;
  processingStatus: string;
  providerPaymentId: string;
}
export interface CaseDetail extends CaseSummary {
  tenantId: string;
  events: TimelineEvent[];
  ledgerEntries: LedgerEntry[];
  webhooks: Webhook[];
  provider: Record<string, unknown> | null;
  obpm?: ObpmSnapshot;
  policyDate: string;
}
export interface ObpmCoverage {
  status: "COMPLETE" | "PARTIAL" | "UNAVAILABLE" | "NOT_REQUESTED";
  scope?: string;
  asOf?: string;
  paginationComplete?: boolean;
  reason?: string;
}
export interface ObpmSnapshot {
  schemaVersion: string;
  dataClassification: "SYNTHETIC";
  snapshotId: string;
  mappingVersion: string;
  extractedAt: string;
  source: {
    deploymentId: string;
    releaseFamily: string;
    exactMaintenanceRelease: string | null;
    hostCode: string;
    branchCode: string;
  };
  payment: {
    sourcePaymentId: string;
    rail: "NEFT";
    direction: "OUTBOUND";
    sourceAmountDecimal: string;
    amountMinor: number;
    currency: "INR";
    activationDate: string;
    createdAt: string;
    nativeTransactionStatus: string | null;
    statusUnavailableReason?: string;
  };
  queueRecords: {
    evidenceId: string;
    sourcePaymentId: string;
    queueReference: string;
    requestAttemptId: string;
    nativeQueueCode: string | null;
    nativeResponseStatus: string | null;
    enteredAt: string | null;
    exitedAt: string | null;
    isCurrentQueueRecord: boolean;
    observedAt: string;
  }[];
  externalRequestAttempts: {
    evidenceId: string;
    requestAttemptId: string;
    sourcePaymentId: string;
    requestType: "ECA";
    requestedAt: string;
    timeoutRecordedAt?: string | null;
    externalSystemFinalOutcome: string | null;
  }[];
  messages: unknown[];
  accountingEntries: unknown[];
  sourceCoverage: Record<
    "queueRecords" | "messages" | "externalCoreResponses" | "accountingEntries",
    ObpmCoverage
  >;
}
export interface ObpmImportReceipt {
  importId: string;
  caseId: string;
  status: "CREATED" | "UPDATED" | "UNCHANGED";
  evidenceVersion: number;
  evidenceHash: string;
  caseVersion: number;
  importedAt?: string;
  sourceSnapshotId?: string;
}
export interface EvidenceVersion {
  evidenceVersion: number;
  evidenceHash: string;
  sourceSnapshotId: string;
  extractedAt: string;
  importedAt: string;
}
export interface Citation {
  id: string;
  documentId: string;
  version: number;
  title: string;
  excerpt: string;
  source: string;
  score?: number;
}
export interface Investigation {
  id: string;
  caseId: string;
  createdAt: string;
  createdBy: string;
  mode: "replay" | "ollama";
  status: string;
  outcome: string;
  summary: string;
  confidence: string;
  findings: {
    id: string;
    text: string;
    evidenceIds: string[];
    citationIds: string[];
  }[];
  missingEvidence: string[];
  citations: Citation[];
  toolCalls: {
    name: string;
    status: string;
    durationMs: number;
    evidenceIds: string[];
  }[];
  proposal: { action: string; reason: string };
  metrics: {
    durationMs: number;
    retrievalMs: number;
    toolCount: number;
    modelCalls: number;
    inputTokens: number;
    outputTokens: number;
    retrievalMode: string;
    model: string;
    synthesisScope?: string;
    assessmentSource?: string;
    findingSource?: string;
    factCatalogVersion?: number;
    factCatalogHash?: string;
    selectedFactIds?: string[];
  };
  warnings: string[];
}
export interface AuditEntry {
  id: string;
  occurredAt: string;
  actor: string;
  action: string;
  detail: unknown;
}
export interface Runbook {
  id: string;
  version: number;
  title: string;
  content: string;
  keywords: string[];
  effectiveFrom: string;
  effectiveTo?: string | null;
  source: string;
  sourceUrl?: string | null;
}
export interface Dashboard {
  openCases: number;
  highPriorityCases: number;
  awaitingReview: number;
  resolvedCases: number;
  totalAmountMinor: number;
  currency: string;
  recentActivity: unknown[];
  mode: string;
}
export interface SystemInfo {
  datasetVersion: string;
  caseCount: number;
  runbookCount: number;
  knowledgeAvailable?: boolean;
  workerStatus: string;
  supportedModes: string[];
  limitations: string[];
}
