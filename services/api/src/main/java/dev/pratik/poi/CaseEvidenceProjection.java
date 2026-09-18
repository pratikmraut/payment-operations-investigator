package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Projects an already authorized immutable case snapshot into cited source documents. */
@Component
public final class CaseEvidenceProjection {
  private static final String SCHEMA = "fcr-case-evidence-v1";
  private static final List<String> PAYMENT_STATUS = List.of("CODSTATUS", "ACCTSTATUS", "MSGSTATUS", "NUMRETRY", "N10_STATUS", "REASONCODE_6346");
  private static final List<String> HOST_STATUS = List.of("TXN_STAT", "ACCT_STAT", "MSG_STAT", "NTWK_ACCT_STAT", "CONTG_ACCT_STAT", "CT_PARTY_ACCT_STAT", "COD_REPLY", "COD_EXT", "COD_REJECT", "SFMS_REJ_CODE");
  private final ObjectMapper mapper;
  private final CaseGuidanceSource guidanceSource;
  private final CaseKnowledgeService knowledge;
  private final CaseStatusKnowledge statusKnowledge;

  public CaseEvidenceProjection(ObjectMapper mapper, String guidanceFile) {
    this(mapper, guidanceFile, new CaseStatusKnowledge(mapper, "", null));
  }

  public CaseEvidenceProjection(ObjectMapper mapper, String guidanceFile, CaseStatusKnowledge statusKnowledge) {
    this(mapper, new CaseGuidanceSource(mapper, guidanceFile), statusKnowledge, null);
  }

  @Autowired
  public CaseEvidenceProjection(ObjectMapper mapper, CaseGuidanceSource guidanceSource,
      CaseStatusKnowledge statusKnowledge, CaseKnowledgeService knowledge) {
    this.mapper = mapper;
    this.guidanceSource = guidanceSource;
    this.statusKnowledge = statusKnowledge;
    this.knowledge = knowledge;
  }

  public ObjectNode project(Actor actor, ObjectNode caseItem, ObjectNode snapshot) {
    return project(actor, caseItem, snapshot, null);
  }

  public ObjectNode project(Actor actor, ObjectNode caseItem, ObjectNode snapshot, String question) {
    return projectInternal(actor, caseItem, snapshot, question, false);
  }

  /** Input capacity and current index only: no embedding, model or bank call. */
  public ObjectNode readiness(Actor actor, ObjectNode caseItem, ObjectNode snapshot, String question) {
    ObjectNode projected = projectInternal(actor, caseItem, snapshot, question, true);
    ObjectNode result = mapper.createObjectNode().put("ready", !projected.path("knowledgeIndexStatus").asText().matches("MISSING|STALE"))
        .put("modelAvailabilityChecked", false).put("modelContextChecked", false);
    for (String field : List.of("selection", "knowledgeVersion", "knowledgeIndexStatus", "knowledgeSelection", "warnings"))
      if (projected.has(field)) result.set(field, projected.get(field).deepCopy());
    result.put("meaning", "Local evidence and knowledge checks only. Exact configured model context and model availability are checked for an explicitly submitted question.");
    return result;
  }

  private ObjectNode projectInternal(Actor actor, ObjectNode caseItem, ObjectNode snapshot, String question, boolean previewOnly) {
    CaseEvidenceService.verifyUpstream(mapper, snapshot);
    if (!snapshot.path("evidenceHash").isTextual() || !snapshot.path("evidenceHash").asText().matches("[a-f0-9]{64}")
        || !CaseEvidenceService.fingerprint(snapshot).equals(snapshot.path("evidenceHash").asText()))
      throw new ApiException(503, "EVIDENCE_STORAGE_UNAVAILABLE", "The saved evidence fingerprint could not be verified.");
    if (question != null && (question.isBlank() || question.length() > 2000)) throw invalid();
    ObjectNode payload = validatedPayload(caseItem, snapshot);
    String snapshotId = snapshot.path("id").asText();
    String source = "case-evidence/" + snapshotId + ".json";
    ArrayNode documents = mapper.createArrayNode();
    LinkedHashSet<String> warnings = new LinkedHashSet<>();
    if (snapshot.path("warnings").isArray()) for (JsonNode warning : snapshot.path("warnings"))
      if (warning.isTextual()) warnings.add(warning.textValue());
    ObjectNode context = mapper.createObjectNode().put("caseId", caseItem.path("id").asText())
        .put("snapshotId", snapshotId).put("version", snapshot.path("version").asInt())
        .put("sourceKind", snapshot.path("sourceKind").asText())
        .put("sourceTimezone", payload.path("sourceTimezone").asText());
    context.set("payment", payload.path("payment").deepCopy());
    context.set("savedWarnings", snapshot.path("warnings").isArray() ? snapshot.path("warnings").deepCopy() : mapper.createArrayNode());
    ObjectNode discovery = context.putObject("discoveryObservation");
    for (String key : List.of("reference", "utr", "amount", "currency", "initiatedAt", "reason"))
      if (caseItem.has(key)) discovery.set(key, caseItem.get(key).deepCopy());
    context.put("meaning", "Case reason is an operator-reported question. Discovery values are earlier source observations, not a verified outcome.");
    documents.add(document("CASE-CONTEXT", "evidence", "Selected saved payment and evidence version", context.toString(), source, "Selected case identity and original discovery observation"));

    List<TimedRow> timeline = new ArrayList<>();
    boolean allTimestampsValid = true;
    for (String group : CaseEvidenceSchema.COLUMNS.keySet()) {
      JsonNode section = payload.path("sections").path(group);
      JsonNode rows = section.path("rows");
      ObjectNode coverage = mapper.createObjectNode().put("group", group).put("suppliedRowCount", rows.size())
          .put("sourceKind", snapshot.path("sourceKind").asText())
          .put("note", section.path("note").asText());
      coverage.set("savedCoverage", snapshot.path("coverage").path(group).deepCopy());
      coverage.put("meaning", "Count of rows supplied in this evidence version. A zero count alone does not prove successful query completion or that an event did not occur. SCOPE_ROW_COUNT remains the source row's reported scope count.");
      documents.add(document(group + "-COVERAGE", "evidence", group + " supplied-row coverage", coverage.toString(), source,
          "payload.sections." + group + " and coverage." + group));
      for (int index = 0; index < rows.size(); index++) {
        JsonNode row = rows.get(index);
        if (!row.isObject()) throw invalid();
        String id = group + "-ROW-" + (index + 1);
        ObjectNode sourceRow = FlexcubeEvidenceAdapter.sourceRow(snapshot, group, index + 1, (ObjectNode)row);
        String locator = "payload.sections." + group + ".rows[" + index + "] (source row " + (index + 1) + ")";
        if(snapshot.has("upstream")) locator += "; native column mapping of upstream.rawResponse." + FlexcubeEvidenceAdapter.ARRAYS.get(group)
            + "[" + index + "]; original source nulls retained";
        if(snapshot.path("upstream").has("omittedFields")) locator += "; omitted source fields remain absent";
        documents.add(document(id, "evidence", group + " source row " + (index + 1), sourceRow.toString(), source, locator));
        String timestampField = group.equals("PAYMENT") ? "DATINITIATION" : Set.of("HOST", "HISTORY").contains(group) ? "DAT_TXN" : null;
        if (timestampField == null) continue;
        JsonNode rawTimestamp = sourceRow.path(timestampField);
        String timestamp = rawTimestamp.isTextual() && !rawTimestamp.textValue().isBlank() ? rawTimestamp.textValue() : null;
        LocalDateTime parsed = parseTimestamp(timestamp);
        allTimestampsValid &= parsed != null;
        ObjectNode event = mapper.createObjectNode().put("id", "TIMELINE-" + id)
            .put("label", group + " row " + (index + 1) + " · " + timestampField + " observation")
            .put("group", group).put("rowIndex", index + 1).put("documentId", id);
        event.set("timestamp", timestamp == null ? NullNode.instance : mapper.getNodeFactory().textNode(timestamp));
        ObjectNode fields = event.putObject("fields");
        for (String key : group.equals("PAYMENT") ? PAYMENT_STATUS : HOST_STATUS)
          if (sourceRow.has(key)) fields.set(key, sourceRow.get(key).deepCopy());
        timeline.add(new TimedRow(event, parsed));
      }
    }
    if (allTimestampsValid) timeline.sort(Comparator.comparing(TimedRow::time));
    else warnings.add("At least one PAYMENT DATINITIATION, HOST DAT_TXN or HISTORY DAT_TXN is missing or invalid as a source-local timestamp. Timeline retains group and exported-row order; it is not a chronological event sequence.");
    if (timeline.stream().filter(row -> row.time != null).map(TimedRow::time).distinct().count()
        < timeline.stream().filter(row -> row.time != null).count())
      warnings.add("Some source timestamps are tied; relative event order within a tied timestamp is not established.");
    warnings.add("Timeline entries are raw source observations, not verified operational transitions. Source-local timestamps are not converted between timezones, and ordering across fields with different source semantics is not an event chronology.");
    if (payload.path("sourceTimezone").asText().equals("UNKNOWN"))
      warnings.add("Source timezone is UNKNOWN; the laptop timezone does not establish the source timezone.");
    else warnings.add("The selected source timezone is recorded metadata; timestamp field semantics and cross-system clock agreement still require source verification.");

    // The source browser retains every original row. Model limits apply only
    // to the separately selected question bundle, never the saved snapshot.
    // Saved rows can be larger than one model document. They remain readable;
    // the question selector rejects an oversized required row without clipping.
    if (documents.size() > 5 + CaseEvidenceSchema.MAX_ROWS * 4
        || payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > CaseEvidenceService.MAX_BYTES) throw invalid();
    ArrayNode guidance;
    String knowledgeVersion;
    String indexStatus = "DISABLED";
    int inventoryDocuments;
    ObjectNode selection = null;
    if (knowledge != null && knowledge.enabled()) {
      CaseKnowledgeService.Preview preparation = knowledge.preview(actor, payload, question, warnings);
      knowledgeVersion = preparation.version();
      inventoryDocuments = preparation.totalDocuments();
      indexStatus = preparation.embeddingStatus();
      if (question == null) guidance = preparation.documents();
      else {
        CaseEvidenceSelection.Selection chosen = new CaseEvidenceSelection(mapper).select(documents, preparation.reservation(), question);
        documents = chosen.documents(); selection = chosen.metadata();
        guidance = previewOnly ? preparation.reservation() : knowledge.select(actor, payload, question, warnings, documents);
        if (!previewOnly) {
          String selectedVersion = knowledgeSelectionVersion(guidance);
          if (!knowledgeVersion.equals(selectedVersion)) throw new ApiException(409, "CASE_KNOWLEDGE_CHANGED", "Knowledge changed while preparing the question. Refresh readiness before submitting again.");
        }
      }
    } else {
      ArrayNode allGeneral = guidanceSource.documents(actor, warnings);
      ArrayNode inventory = allGeneral.deepCopy().addAll(statusKnowledge.documents(actor, warnings));
      CaseKnowledgeLimits.inventory(inventory, invalid());
      knowledgeVersion = UatService.canonicalHash(inventory);
      inventoryDocuments = inventory.size();
      ArrayNode general = question == null ? allGeneral : selectGeneral(allGeneral, question);
      guidance = general.deepCopy().addAll(statusKnowledge.selectExact(actor, payload, question, warnings));
      if (question != null) {
        ArrayNode reservation = general.deepCopy().addAll(statusKnowledge.reserve(actor, payload, question, warnings));
        CaseEvidenceSelection.Selection chosen = new CaseEvidenceSelection(mapper).select(documents, reservation, question);
        documents = chosen.documents(); selection = chosen.metadata();
        if (!previewOnly) {
          guidance = general.deepCopy().addAll(statusKnowledge.select(actor, payload, question, warnings));
          ArrayNode currentInventory = guidanceSource.documents(actor, warnings).addAll(statusKnowledge.documents(actor, warnings));
          if (!knowledgeVersion.equals(UatService.canonicalHash(currentInventory)))
            throw new ApiException(409, "CASE_KNOWLEDGE_CHANGED", "Knowledge changed while preparing the question. Refresh readiness before submitting again.");
        }
      }
    }
    documents.addAll(guidance);
    if (question != null) UatService.documentMap(documents, tooLarge());
    if (selection != null && selection.path("omittedRows").asInt() > 0)
      warnings.add("This question uses " + selection.path("selectedRows").asInt() + " of " + selection.path("totalRows").asInt()
          + " supplied rows. Omitted rows remain in the saved evidence and may contain relevant or conflicting facts; this is not a complete-source assessment.");
    ObjectNode result = mapper.createObjectNode();
    result.set("documents", documents);
    if (selection != null) result.set("selection", selection);
    result.put("knowledgeVersion", knowledgeVersion).put("knowledgeIndexStatus", indexStatus);
    int selectedKnowledge = 0;
    for (JsonNode document : guidance) if (!Set.of("CASE-KNOWLEDGE-RETRIEVAL", "FCR-ENUM-RETRIEVAL").contains(document.path("id").asText())) selectedKnowledge++;
    result.putObject("knowledgeSelection").put("totalDocuments", inventoryDocuments)
        .put("selectedDocuments", selectedKnowledge).put("omittedDocuments", Math.max(0, inventoryDocuments-selectedKnowledge))
        .put("selectionBasis", previewOnly && knowledge != null && knowledge.enabled() ? "capacity-reservation" : "selected-original-documents");
    ArrayNode projectedTimeline = result.putArray("timeline"); timeline.forEach(row -> projectedTimeline.add(row.event));
    result.set("warnings", mapper.valueToTree(warnings));
    result.put("guidanceHash", UatService.canonicalHash(guidance));
    return result;
  }

  private ArrayNode selectGeneral(ArrayNode inventory, String question) {
    ArrayNode result = mapper.createArrayNode();
    List<JsonNode> optional = new ArrayList<>();
    for (JsonNode document : inventory) {
      String id = document.path("id").asText();
      if (CaseKnowledgeService.PINNED.contains(id) || (id.equals("FCR-TABLE-REFERENCE-20260915") && CaseKnowledgeService.tableQuestion(question)))
        result.add(document.deepCopy());
      else optional.add(document);
    }
    Set<String> terms = new HashSet<>(List.of(question.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9_]+")));
    optional.sort(Comparator.comparingLong((JsonNode document) -> {
      Set<String> words = new HashSet<>(List.of((document.path("title").asText() + " " + document.path("content").asText()).toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9_]+")));
      return terms.stream().filter(term -> term.length() > 1 && words.contains(term)).count();
    }).reversed().thenComparing(document -> document.path("id").asText()));
    optional.stream().limit(3).forEach(document -> result.add(document.deepCopy()));
    return result;
  }

  private String knowledgeSelectionVersion(ArrayNode documents) {
    for (JsonNode document : documents) if (document.path("id").asText().equals("CASE-KNOWLEDGE-RETRIEVAL")) {
      try { return mapper.readTree(document.path("content").asText()).path("knowledgeVersion").asText(); }
      catch (Exception invalid) { throw invalid(); }
    }
    throw invalid();
  }

  private ObjectNode validatedPayload(ObjectNode item, ObjectNode snapshot) {
    if (!snapshot.path("caseId").asText().equals(item.path("id").asText())
        || !snapshot.path("id").isTextual() || snapshot.path("id").asText().isBlank()
        || !(snapshot.get("payload") instanceof ObjectNode payload)
        || !SCHEMA.equals(payload.path("schemaVersion").asText())) throw invalid();
    for (String field : List.of("reference", "orgBank", "orgBranch"))
      if (!item.path(field).equals(payload.path("payment").path(field))) throw invalid();
    if (!payload.path("sections").isObject() || !snapshot.path("coverage").isObject()) throw invalid();
    for (String group : CaseEvidenceSchema.COLUMNS.keySet())
      if (!payload.path("sections").path(group).path("rows").isArray() || payload.path("sections").path(group).path("rows").size() > CaseEvidenceSchema.MAX_ROWS || !snapshot.path("coverage").path(group).isObject()) throw invalid();
    return payload;
  }

  private ObjectNode document(String id, String kind, String title, String content, String file, String locator) {
    ObjectNode result = mapper.createObjectNode().put("id", id).put("kind", kind).put("title", title).put("content", content);
    result.putObject("source").put("file", file).put("locator", locator);
    return result;
  }
  private static LocalDateTime parseTimestamp(String timestamp) {
    if (timestamp == null || !timestamp.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?")) return null;
    try { return LocalDateTime.parse(timestamp); } catch (DateTimeException invalid) { return null; }
  }
  private record TimedRow(ObjectNode event, LocalDateTime time) { }
  private static ApiException invalid() { return new ApiException(422, "INVALID_CASE_PROJECTION", "The selected evidence version does not match this saved case or its four-group contract."); }
  private static ApiException tooLarge() { return new ApiException(422, "CASE_INVESTIGATION_EVIDENCE_LIMIT", "Selected evidence and guidance must fit 100 source documents and 50,000 source characters with valid unique citations. Narrow the question or source export; original saved rows remain unchanged."); }
}
