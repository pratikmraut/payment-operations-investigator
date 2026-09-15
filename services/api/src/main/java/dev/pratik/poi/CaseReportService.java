package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Freezes authorized case records for a reproducible report. No model or bank calls. */
@Service
public class CaseReportService {
  static final int REQUEST_LIMIT = 8192, MAX_JOBS = 20, MAX_REPORT_BYTES = 8 * 1024 * 1024;
  private final ObjectMapper mapper;
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final PaymentDiscoveryService cases;
  private final CaseEvidenceService evidence;
  private final CaseInvestigationService investigations;
  private final CaseManagementService management;
  private final CaseReportPdf pdf;
  private final Semaphore renderSlots = new Semaphore(2);

  public CaseReportService(ObjectMapper mapper, JdbcTemplate db, TransactionTemplate tx,
      PaymentDiscoveryService cases, CaseEvidenceService evidence, CaseInvestigationService investigations,
      CaseManagementService management, CaseReportPdf pdf) {
    this.mapper = mapper; this.db = db; this.tx = tx; this.cases = cases; this.evidence = evidence;
    this.investigations = investigations; this.management = management; this.pdf = pdf;
  }

  public ObjectNode preview(Actor actor, String caseId, byte[] bytes, String key) {
    cases.caseDetail(actor, caseId);
    ObjectNode request = parse(bytes);
    exactKeys(request, Set.of("evidenceId", "investigationIds", "includeEvidenceRows"), Set.of("reportMode"));
    if (key == null || !key.matches("[A-Za-z0-9._:-]{8,200}"))
      throw new ApiException(400, "CASE_REPORT_KEY_REQUIRED", "An Idempotency-Key of 8-200 safe characters is required.");
    JsonNode selectedEvidence = request.get("evidenceId");
    String evidenceId = selectedEvidence.isNull() ? null : identifier(selectedEvidence);
    if (!request.path("includeEvidenceRows").isBoolean()) throw invalid("Choose whether to include the raw evidence appendix.");
    List<String> ids = identifiers(request.path("investigationIds"));
    boolean includeRows = request.path("includeEvidenceRows").booleanValue();
    // Keep omitted mode absent from the frozen scope and request hash for legacy replay compatibility.
    String reportMode = "DETAILED";
    if (request.has("reportMode")) {
      JsonNode mode = request.get("reportMode");
      if (!mode.isTextual() || !Set.of("SUMMARY", "DETAILED").contains(mode.textValue()))
        throw invalid("Choose SUMMARY or DETAILED for the report format.");
      reportMode = mode.textValue();
    }
    boolean summary = reportMode.equals("SUMMARY");
    if (summary && includeRows)
      throw invalid("Summary reports omit raw evidence rows. Choose DETAILED to include the evidence appendix.");
    if (summary && ids.size() > 2)
      throw invalid("Select at most 2 investigations for a summary, or choose DETAILED to include more.");
    if (evidenceId == null && (includeRows || !ids.isEmpty()))
      throw invalid("Select a saved evidence version before including its rows or investigation answers.");
    String requestHash = UatService.canonicalHash(request);
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        return tx.execute(status -> {
          // Share the lifecycle lock: no report snapshot can be inserted after case deletion.
          cases.lockCase(actor,caseId);
          // The same scoped case is checked even on an idempotent replay.
          ObjectNode item = cases.caseDetail(actor, caseId);
          List<Map<String,Object>> previous = db.queryForList(
              "SELECT request_hash,report_hash,body FROM fcr_case_report WHERE tenant_id=? AND actor_id=? AND case_id=? AND idempotency_key=?",
              actor.tenantId(), actor.id(), caseId, key);
          if (!previous.isEmpty()) {
            if (!requestHash.equals(previous.get(0).get("request_hash")))
              throw new ApiException(409, "CASE_REPORT_KEY_CONFLICT", "This report key was used with different selections. Generate a new preview.");
            return verified(previous.get(0), caseId);
          }
          ObjectNode bundle = mapper.createObjectNode().put("schemaVersion", "payment-case-report-v1")
              .put("reportId", "RPT-" + UUID.randomUUID()).put("generatedAt", Instant.now().toString());
          bundle.putObject("generatedBy").put("id", actor.id()).put("name", actor.name()).put("role", actor.role());
          ObjectNode managed = management.detail(actor, caseId);
          item = cases.caseDetail(actor, caseId);
          // Use the same observed management version in the case header and management section.
          item.set("owner", managed.path("owner").deepCopy());
          item.set("priority", managed.path("priority").deepCopy());
          item.set("managementVersion", managed.path("version").deepCopy());
          bundle.set("case", item.deepCopy());
          ObjectNode reportManagement = managed.deepCopy();
          reportManagement.remove(List.of("assignees", "reviewerConclusions"));
          // The dedicated review section is bound to this selection. Activity remains a concise case history.
          for (JsonNode event : reportManagement.path("audit")) if (event instanceof ObjectNode object) object.remove("data");
          bundle.set("management", reportManagement);
          ObjectNode snapshot = evidenceId == null ? null : evidence.detail(actor, caseId, evidenceId);
          if (snapshot != null) verifyEvidence(item, snapshot);
          if (snapshot == null) bundle.putNull("evidence");
          else {
            ObjectNode selected = snapshot.deepCopy();
            selected.set("sourceTimezone", snapshot.path("payload").path("sourceTimezone").deepCopy());
            if (!includeRows) selected.remove("payload");
            bundle.set("evidence", selected);
          }
          ArrayNode jobs = bundle.putArray("investigations");
          for (String id : ids) {
            ObjectNode job = investigations.detail(actor, caseId, id).deepCopy();
            // A saved answer already preserves its full cited documents. Do not substitute current rows.
            job.remove("documents");
            jobs.add(job);
          }
          ArrayNode activity = bundle.putArray("caseActivity");
          activity.add(activity(caseId + "-opened", "CASE_OPENED", item.path("createdAt").asText(),
              item.path("createdBy").asText(), "Case opened: " + item.path("reason").asText()));
          for(String raw:db.queryForList("SELECT body FROM fcr_case_lifecycle_event WHERE tenant_id=? AND case_id=? ORDER BY version",String.class,actor.tenantId(),caseId)) {
            ObjectNode entry=UatService.parseObject(mapper,raw.getBytes(StandardCharsets.UTF_8),invalid("Stored lifecycle activity is unavailable."));
            activity.add(activity(entry.path("id").asText(),"CASE_"+entry.path("action").asText(),entry.path("occurredAt").asText(),entry.path("actor").asText(),entry.path("reason").asText()));
          }
          if (snapshot != null) activity.add(activity(evidenceId, "EVIDENCE_ATTACHED", snapshot.path("createdAt").asText(),
              snapshot.path("createdBy").asText(), "Evidence version " + snapshot.path("version").asInt() + " attached."));
          // Derive activity from the same frozen jobs; a later completion must not contradict their report status.
          for (JsonNode job : jobs) {
            String id = job.path("id").asText();
            activity.add(activity(id + "-requested", "INVESTIGATION_REQUESTED", job.path("createdAt").asText(),
                job.path("createdBy").asText(), job.path("question").asText()));
            if (job.hasNonNull("startedAt")) activity.add(activity(id + "-started", "INVESTIGATION_STARTED", job.path("startedAt").asText(), "system", "Investigation processing started."));
            if (job.hasNonNull("finishedAt")) activity.add(activity(id + "-finished", "INVESTIGATION_" + job.path("status").asText(), job.path("finishedAt").asText(), "system",
                job.path("status").asText().equals("COMPLETED") ? "Saved model answer; factual review remains required." : job.path("error").path("message").asText()));
          }
          ObjectNode review = bundle.putObject("review").put("status", "PENDING");
          review.putNull("conclusion");
          if (snapshot != null) {
            JsonNode match = matchingConclusion(managed.path("reviewerConclusions"), snapshot, ids, jobs);
            if (match != null) { review.put("status", "RECORDED"); review.set("conclusion", match.deepCopy()); }
          }
          bundle.set("scope", request.deepCopy());
          ArrayNode warnings = bundle.putArray("warnings");
          warnings.add("This report preserves saved observations and model-written answers. It does not establish a payment outcome or execute a payment.");
          warnings.add("Each answer retains its own evidence version and original cited sources; model claims require factual review.");
          if (snapshot == null) warnings.add("No evidence version was selected. This is a case summary without evidence or model answers.");
          if (summary) warnings.add("The summary PDF omits full cited-source bodies and detailed provenance. They remain preserved in the frozen report snapshot; choose DETAILED to include them in a PDF.");
          else if (!includeRows) warnings.add("The raw selected-evidence appendix is omitted. Complete documents cited by selected answers remain included.");
          if (review.path("status").asText().equals("PENDING"))
            warnings.add("No recorded reviewer conclusion matches this exact evidence version and question selection.");
          for (JsonNode job : jobs) {
            if (!job.path("evidenceId").asText().equals(evidenceId)) {
              warnings.add("An included investigation used another evidence version. Its source version is identified alongside its answer.");
              break;
            }
          }
          if (jobs.isEmpty()) warnings.add("No investigations were selected.");
          if (jobs.findValuesAsText("status").stream().anyMatch(s -> !s.equals("COMPLETED")))
            warnings.add("Some selected investigations have no completed answer. Their saved status is included without a substitute answer.");
          // Do not freeze a knowingly mixed management version after an overlapping edit.
          if (management.detail(actor, caseId).path("version").asLong() != managed.path("version").asLong())
            throw new ApiException(409, "CASE_REPORT_CHANGED", "Case management changed while preparing the report. Refresh and generate it again.");
          cases.caseDetail(actor, caseId);
          String reportHash = UatService.canonicalHash(bundle);
          bundle.put("reportHash", reportHash);
          String serialized = bundle.toString();
          if (serialized.getBytes(StandardCharsets.UTF_8).length > MAX_REPORT_BYTES) throw tooLarge();
          db.update("INSERT INTO fcr_case_report(id,tenant_id,case_id,created_at,actor_id,idempotency_key,request_hash,report_hash,body) VALUES(?,?,?,?,?,?,?,?,?)",
              bundle.path("reportId").asText(), actor.tenantId(), caseId, bundle.path("generatedAt").asText(),
              actor.id(), key, requestHash, reportHash, serialized);
          return bundle;
        });
      } catch (DataIntegrityViolationException concurrent) {
        if (attempt == 1) throw new ApiException(409, "CASE_REPORT_CONFLICT", "Another report command is completing. Retry with the same report key.");
      }
    }
    throw new IllegalStateException();
  }

  public ObjectNode frozen(Actor actor, String caseId, byte[] bytes) {
    cases.caseDetail(actor, caseId);
    ObjectNode request = parse(bytes); exactKeys(request, Set.of("reportId", "reportHash"));
    String reportId = identifier(request.path("reportId"));
    String hash = request.path("reportHash").asText("");
    if (!request.path("reportHash").isTextual() || !hash.matches("[a-f0-9]{64}"))
      throw invalid("Generate a report preview with a valid fingerprint before downloading.");
    List<Map<String,Object>> rows = db.queryForList(
        "SELECT report_hash,body FROM fcr_case_report WHERE id=? AND tenant_id=? AND case_id=?",
        reportId, actor.tenantId(), caseId);
    if (rows.isEmpty()) throw ApiException.notFound();
    if (!hash.equals(rows.get(0).get("report_hash")))
      throw new ApiException(409, "CASE_REPORT_CHANGED", "The report fingerprint does not match the saved preview. Generate a new preview.");
    return verified(rows.get(0), caseId);
  }

  public byte[] render(ObjectNode frozen) {
    if (!renderSlots.tryAcquire())
      throw new ApiException(429, "CASE_REPORT_BUSY", "Two reports are being prepared. Wait briefly and retry the same download.");
    try { return pdf.render(frozen); }
    finally { renderSlots.release(); }
  }

  private ObjectNode verified(Map<String,Object> row, String caseId) {
    try {
      ObjectNode bundle = UatService.parseObject(mapper, ((String) row.get("body")).getBytes(StandardCharsets.UTF_8),
          new ApiException(503, "CASE_REPORT_INVALID", "The saved report could not be verified."));
      String expected = (String) row.get("report_hash");
      ObjectNode hashed = bundle.deepCopy(); hashed.remove("reportHash");
      if (!expected.equals(bundle.path("reportHash").asText()) || !expected.equals(UatService.canonicalHash(hashed))
          || !caseId.equals(bundle.path("case").path("id").asText())
          || !"payment-case-report-v1".equals(bundle.path("schemaVersion").asText()))
        throw new IllegalArgumentException();
      return bundle;
    } catch (RuntimeException failure) {
      throw new ApiException(503, "CASE_REPORT_INVALID", "The saved report could not be verified. Generate a new preview.");
    }
  }

  static JsonNode matchingConclusion(JsonNode conclusions, ObjectNode snapshot, List<String> ids, ArrayNode jobs) {
    if (ids.isEmpty()) return null;
    Set<String> selected = new HashSet<>(ids);
    for (JsonNode job : jobs) if (!job.path("status").asText().equals("COMPLETED")) return null;
    List<JsonNode> matches = new ArrayList<>();
    for (JsonNode conclusion : conclusions) {
      Set<String> reviewed = new HashSet<>(); conclusion.path("investigationIds").forEach(id -> reviewed.add(id.asText()));
      if (conclusion.path("status").asText().equals("RECORDED")
          && conclusion.path("evidenceId").equals(snapshot.path("id"))
          && conclusion.path("evidenceHash").equals(snapshot.path("evidenceHash")) && selected.equals(reviewed)) matches.add(conclusion);
    }
    return matches.stream().max(Comparator.comparing(node -> Instant.parse(node.path("createdAt").asText()))).orElse(null);
  }

  private static void verifyEvidence(ObjectNode item, ObjectNode snapshot) {
    boolean identity = snapshot.path("caseId").equals(item.path("id"));
    for (String field : List.of("reference", "orgBank", "orgBranch"))
      identity &= snapshot.path("payload").path("payment").path(field).equals(item.path(field));
    if (!identity || !CaseEvidenceService.fingerprint(snapshot).equals(snapshot.path("evidenceHash").asText()))
      throw new ApiException(503, "CASE_REPORT_EVIDENCE_INVALID", "The selected evidence could not be verified. Inspect its saved version before exporting.");
  }
  private ObjectNode activity(String id, String action, String time, String actor, String detail) {
    return mapper.createObjectNode().put("id", id).put("action", action).put("occurredAt", time).put("actor", actor).put("detail", detail);
  }

  private ObjectNode parse(byte[] bytes) {
    if (bytes.length > REQUEST_LIMIT) throw new ApiException(413, "CASE_REPORT_REQUEST_TOO_LARGE", "Report selection is limited to 8 KiB.");
    return UatService.parseObject(mapper, bytes, invalid("Supply one valid report selection object."));
  }
  private static void exactKeys(ObjectNode request, Set<String> keys) {
    exactKeys(request, keys, Set.of());
  }
  private static void exactKeys(ObjectNode request, Set<String> required, Set<String> optional) {
    Set<String> found = new HashSet<>(); request.fieldNames().forEachRemaining(found::add);
    Set<String> allowed = new HashSet<>(required); allowed.addAll(optional);
    if (!found.containsAll(required) || !allowed.containsAll(found))
      throw invalid("The report request contains missing or unsupported fields.");
  }
  private static String identifier(JsonNode value) {
    if (!value.isTextual() || !value.textValue().matches("[A-Za-z0-9._:-]{1,100}"))
      throw invalid("Choose saved case evidence and investigation identifiers.");
    return value.textValue();
  }
  private static List<String> identifiers(JsonNode value) {
    if (!value.isArray() || value.size() > MAX_JOBS) throw invalid("Select at most 20 investigations per report.");
    List<String> result = new ArrayList<>();
    for (JsonNode id : value) {
      String identifier = identifier(id);
      if (result.contains(identifier)) throw invalid("An investigation can appear only once in the report selection.");
      result.add(identifier);
    }
    return result;
  }
  static ApiException invalid(String message) { return new ApiException(422, "INVALID_CASE_REPORT", message); }
  static ApiException tooLarge() { return new ApiException(413, "CASE_REPORT_TOO_LARGE", "The report is too large. Select fewer investigations or omit the raw evidence appendix."); }
}
