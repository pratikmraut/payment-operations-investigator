package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InvestigationService {
  private final CaseStore store;
  private final WorkerClient worker;
  private final JsonSupport json;
  private final TransactionTemplate transactions;
  private static final Set<String> ACTIONS = Set.of("RESOLVE_CASE", "ESCALATE", "REQUEST_EVIDENCE");
  private static final Set<String> OUTCOMES =
      Set.of(
          "TIMEOUT_AFTER_SUCCESS",
          "DUPLICATE_WEBHOOK",
          "OUT_OF_ORDER_WEBHOOK",
          "MISSING_REFUND",
          "INSUFFICIENT_EVIDENCE",
          "OBPM_ECA_TIMEOUT",
          "PROVIDER_FAILURE");

  public InvestigationService(
      CaseStore store, WorkerClient worker, JsonSupport json, TransactionTemplate transactions) {
    this.store = store;
    this.worker = worker;
    this.json = json;
    this.transactions = transactions;
  }

  public ObjectNode investigate(String caseId, Actor actor, String question, String mode) {
    actor.requireWriter();
    if (!Set.of("replay", "ollama").contains(mode))
      throw new ApiException(400, "INVALID_MODE", "Choose replay or ollama.");
    ObjectNode snapshot = store.caseDetail(caseId, actor.tenantId());
    long originalVersion = snapshot.path("version").longValue();
    String id = "INV-" + UUID.randomUUID();
    ObjectNode request = json.object();
    request.put("investigationId", id);
    request.set("case", snapshot.deepCopy());
    request.put("question", question);
    request.put("mode", mode);
    request.put("actorId", actor.id());
    ObjectNode result;
    try {
      result = worker.investigate(request);
      validateResult(result, snapshot, mode, id);
    } catch (ApiException failure) {
      transactions.executeWithoutResult(
          tx ->
              store.audit(
                  caseId,
                  actor.tenantId(),
                  actor.id(),
                  "INVESTIGATION_FAILED",
                  failure.code + "; requested mode " + mode + "; no fallback."));
      throw failure;
    }
    result.retain(
        "id",
        "caseId",
        "createdAt",
        "createdBy",
        "mode",
        "status",
        "outcome",
        "summary",
        "confidence",
        "findings",
        "missingEvidence",
        "citations",
        "toolCalls",
        "proposal",
        "metrics",
        "warnings");
    result.put("id", id);
    result.put("caseId", caseId);
    result.put("createdAt", Instant.now().toString());
    result.put("createdBy", actor.id());
    result.put("mode", mode);
    result.put("status", "AWAITING_REVIEW");
    result.put("caseVersion", originalVersion + 1);
    result.put("snapshotHash", hash(snapshot.toString()));
    result.set("caseSnapshot", snapshot.deepCopy());
    // These are provenance facts enforced by Java, even when the worker response omitted a warning.
    if (mode.equals("replay")) {
      ((ObjectNode) result.path("metrics"))
          .put("modelCalls", 0)
          .put("model", "deterministic-replay")
          .put("inputTokens", 0)
          .put("outputTokens", 0);
      ((com.fasterxml.jackson.databind.node.ArrayNode) result.path("warnings"))
          .add("Deterministic replay mode; no LLM inference.");
    }
    return transactions.execute(
        tx -> {
          ObjectNode locked = store.lockCase(caseId, actor.tenantId());
          if (locked.path("version").longValue() != originalVersion)
            throw new ApiException(
                409,
                "VERSION_CONFLICT",
                "Case changed while the worker was running. Start a fresh investigation.");
          store.insertInvestigation(result, actor.tenantId(), originalVersion + 1);
          store.setStatus(caseId, actor.tenantId(), "AWAITING_REVIEW", originalVersion + 1);
          store.audit(
              caseId,
              actor.tenantId(),
              actor.id(),
              "INVESTIGATION_CREATED",
              id + "; " + mode + "; proposal " + result.path("proposal").path("action").asText());
          return result;
        });
  }

  private void validateResult(
      ObjectNode result, ObjectNode snapshot, String mode, String investigationId) {
    if (!investigationId.equals(result.path("id").asText())
        || !snapshot.path("id").asText().equals(result.path("caseId").asText())
        || !mode.equals(result.path("mode").asText())
        || !OUTCOMES.contains(result.path("outcome").asText())
        || !ACTIONS.contains(result.path("proposal").path("action").asText())
        || !Set.of("HIGH", "MEDIUM", "INSUFFICIENT").contains(result.path("confidence").asText())
        || !result.path("summary").isTextual()
        || result.path("summary").asText().isBlank()
        || !result.path("metrics").isObject()) invalidWorker();
    for (String field :
        List.of("findings", "missingEvidence", "citations", "toolCalls", "warnings"))
      if (!result.path(field).isArray()) invalidWorker();
    Set<String> evidence = new HashSet<>();
    boolean obpm = snapshot.path("domain").asText().equals("OBPM_NEFT");
    if (obpm) {
      if (!Set.of("OBPM_ECA_TIMEOUT", "INSUFFICIENT_EVIDENCE").contains(result.path("outcome").asText())
          || result.path("proposal").path("action").asText().equals("RESOLVE_CASE")) invalidWorker();
      for (String group : List.of("queueRecords", "externalRequestAttempts"))
        for (JsonNode record : snapshot.path("obpm").path(group)) evidence.add(record.path("evidenceId").asText());
    } else if (result.path("outcome").asText().equals("OBPM_ECA_TIMEOUT")) invalidWorker();
    for (String field : List.of("events", "ledgerEntries", "webhooks"))
      for (JsonNode entry : snapshot.path(field)) {
        evidence.add(entry.path("id").asText());
        if (entry.hasNonNull("providerEventId"))
          evidence.add(entry.path("providerEventId").asText());
      }
    evidence.add(snapshot.path("paymentId").asText());
    if (snapshot.path("provider").hasNonNull("paymentId"))
      evidence.add(snapshot.path("provider").path("paymentId").asText());
    // Worker may expose the authorized provider snapshot under this stable synthetic evidence ID.
    if (!obpm) evidence.add("PROVIDER-" + snapshot.path("paymentId").asText());
    if (snapshot.path("provider").hasNonNull("paymentId"))
      evidence.add("PROVIDER:" + snapshot.path("provider").path("paymentId").asText());
    Set<String> citations = new HashSet<>();
    for (JsonNode citation : result.path("citations")) {
      if (!citation.path("id").isTextual()
          || !citation.path("documentId").isTextual()
          || !citation.path("version").isIntegralNumber()) invalidWorker();
      citations.add(citation.path("id").asText());
    }
    for (JsonNode finding : result.path("findings")) {
      if (!finding.path("text").isTextual()
          || !finding.path("evidenceIds").isArray()
          || !finding.path("citationIds").isArray()) invalidWorker();
      for (JsonNode id : finding.path("evidenceIds"))
        if (!evidence.contains(id.asText())) invalidWorker();
      for (JsonNode id : finding.path("citationIds"))
        if (!citations.contains(id.asText())) invalidWorker();
    }
    if (result.path("proposal").path("action").asText().equals("RESOLVE_CASE")) {
      JsonNode reconciliation = snapshot.path("reconciliation");
      JsonNode discrepancy = reconciliation.path("discrepancyMinor");
      // Only Java's authorized snapshot can establish that the ledger reconciles.
      // Null/unknown values must not become zero through JsonNode's numeric defaults.
      if (!reconciliation.path("calculatedBy").asText().equals("java-api")
          || !reconciliation.path("validMoney").isBoolean()
          || !reconciliation.path("validMoney").booleanValue()
          || !reconciliation.path("providerAvailable").isBoolean()
          || !reconciliation.path("providerAvailable").booleanValue()
          || !reconciliation.path("providerPayoutMinor").isIntegralNumber()
          || !discrepancy.isIntegralNumber()
          || !discrepancy.canConvertToLong()
          || discrepancy.longValue() != 0) {
        invalidWorker();
      }
      boolean supportedFinding = false;
      for (JsonNode finding : result.path("findings")) {
        if (!finding.path("evidenceIds").isEmpty() && !finding.path("citationIds").isEmpty()) {
          supportedFinding = true;
        }
      }
      if (!supportedFinding
          || result.path("outcome").asText().equals("INSUFFICIENT_EVIDENCE")
          || result.path("confidence").asText().equals("INSUFFICIENT")) {
        invalidWorker();
      }
    }
    if (mode.equals("ollama") && result.path("metrics").path("modelCalls").asLong() < 1)
      invalidWorker();
  }

  private static void invalidWorker() {
    throw new ApiException(
        503,
        "INVALID_WORKER_RESPONSE",
        "Worker output failed evidence or schema checks. No result was stored.");
  }

  public ObjectNode decide(
      String caseId,
      Actor actor,
      String investigationId,
      String decision,
      String note,
      long expectedVersion,
      String key) {
    actor.requireReviewer();
    if (key == null || !key.matches("[A-Za-z0-9._:-]{8,200}"))
      throw new ApiException(
          400,
          "IDEMPOTENCY_KEY_REQUIRED",
          "Use an Idempotency-Key of 8-200 letters, numbers, dots, colons, underscores or hyphens.");
    if (!Set.of("APPROVE", "REJECT").contains(decision))
      throw new ApiException(400, "INVALID_DECISION", "Decision must be APPROVE or REJECT.");
    String requestHash =
        hash(
            json.value(
                    new TreeMap<>(
                        Map.of(
                            "caseId",
                            caseId,
                            "investigationId",
                            investigationId,
                            "decision",
                            decision,
                            "note",
                            note,
                            "expectedVersion",
                            expectedVersion)))
                .toString());
    return transactions.execute(
        tx -> {
          ObjectNode current = store.lockCase(caseId, actor.tenantId());
          Optional<ObjectNode> replay =
              store.replay(actor.tenantId(), actor.id(), key, requestHash);
          if (replay.isPresent()) return replay.get();
          ObjectNode investigation = store.investigation(investigationId, actor.tenantId());
          if (!investigation.path("caseId").asText().equals(caseId)) throw ApiException.notFound();
          if (investigation.path("createdBy").asText().equals(actor.id()))
            throw new ApiException(
                403,
                "MAKER_CHECKER_REQUIRED",
                "The investigation creator cannot review their own proposal.");
          if (current.path("version").longValue() != expectedVersion
              || store.investigationVersion(investigationId, actor.tenantId()) != expectedVersion)
            throw new ApiException(
                409,
                "VERSION_CONFLICT",
                "This proposal is stale. Refresh and investigate the current case.");
          if (store.hasDecision(investigationId))
            throw new ApiException(
                409, "ALREADY_REVIEWED", "This investigation already has a decision.");
          String action = investigation.path("proposal").path("action").asText();
          if (!ACTIONS.contains(action) || (current.path("domain").asText().equals("OBPM_NEFT") && action.equals("RESOLVE_CASE")))
            throw new ApiException(422, "INVALID_PROPOSAL", "Stored proposal is not permitted.");
          String status =
              decision.equals("REJECT")
                  ? "OPEN"
                  : switch (action) {
                    case "RESOLVE_CASE" -> "RESOLVED";
                    case "ESCALATE" -> "ESCALATED";
                    default -> "NEEDS_EVIDENCE";
                  };
          ObjectNode response =
              json.value(
                  Map.of(
                      "id",
                      "DEC-" + UUID.randomUUID(),
                      "caseId",
                      caseId,
                      "investigationId",
                      investigationId,
                      "decision",
                      decision,
                      "action",
                      action,
                      "caseStatus",
                      status,
                      "version",
                      expectedVersion + 1,
                      "replayed",
                      false));
          response
              .put("reviewedBy", actor.id())
              .put("createdAt", Instant.now().toString())
              .put("note", note);
          store.insertDecision(response, actor, key, requestHash);
          store.setStatus(caseId, actor.tenantId(), status, expectedVersion + 1);
          store.audit(
              caseId,
              actor.tenantId(),
              actor.id(),
              "REVIEW_" + decision,
              "Investigation " + investigationId + "; proposed " + action + "; " + note);
          return response;
        });
  }

  static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
