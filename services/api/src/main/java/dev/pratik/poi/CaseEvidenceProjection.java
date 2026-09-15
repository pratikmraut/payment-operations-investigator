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
    CaseEvidenceService.verifyUpstream(mapper, snapshot);
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

    // Validate the complete raw evidence before any optional embedding call.
    UatService.documentMap(documents, tooLarge());
    ArrayNode guidance;
    if (knowledge != null && knowledge.enabled()) {
      guidance = knowledge.select(actor, payload, question, warnings, documents);
    } else {
      guidance = guidanceSource.documents(actor, warnings);
      ArrayNode provisional = documents.deepCopy().addAll(guidance);
      UatService.documentMap(provisional, tooLarge());
      guidance.addAll(statusKnowledge.select(actor, payload, question, warnings));
    }
    documents.addAll(guidance);
    UatService.documentMap(documents, tooLarge());
    ObjectNode result = mapper.createObjectNode();
    result.set("documents", documents);
    ArrayNode projectedTimeline = result.putArray("timeline"); timeline.forEach(row -> projectedTimeline.add(row.event));
    result.set("warnings", mapper.valueToTree(warnings));
    result.put("guidanceHash", UatService.canonicalHash(guidance));
    return result;
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
      if (!payload.path("sections").path(group).path("rows").isArray() || !snapshot.path("coverage").path(group).isObject()) throw invalid();
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
  private static ApiException tooLarge() { return new ApiException(422, "CASE_INVESTIGATION_EVIDENCE_LIMIT", "Evidence and guidance must fit 100 source documents and 50,000 source characters with valid unique citations. Narrow the selected evidence or guidance; no source rows were truncated."); }
}
