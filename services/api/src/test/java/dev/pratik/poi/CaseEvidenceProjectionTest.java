package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaseEvidenceProjectionTest {
  final ObjectMapper mapper = new ObjectMapper();
  final Actor analyst = new Actor("fixture-analyst", "Fixture analyst", "ANALYST", "northstar");
  final Actor other = new Actor("fixture-other", "Other fixture", "ANALYST", "silverline");
  @TempDir Path temporary;
  ObjectNode item() {
    return mapper.createObjectNode().put("id", "FCR-FIXTURE-CASE")
        .put("reference", "0001234567890123456789012345").put("orgBank", "099").put("orgBranch", "0100")
        .put("amount", "12345678901234567890.007").put("utr", "FIXTURE-UTR")
        .put("currency", "INR").put("initiatedAt", "2026-01-02T10:00:00")
        .put("reason", "Operator asks about this source observation.");
  }
  ObjectNode snapshot() {
    ObjectNode result = mapper.createObjectNode().put("id", "EVD-FIXTURE-1").put("caseId", item().path("id").asText())
        .put("version", 1).put("sourceKind", "EXCEL");
    result.putArray("warnings").add("Source query completion is unverified.");
    ObjectNode payload = result.putObject("payload").put("schemaVersion", "fcr-case-evidence-v1").put("sourceTimezone", "UNKNOWN");
    payload.putObject("payment").put("reference", item().path("reference").asText()).put("orgBank", "099").put("orgBranch", "0100");
    ObjectNode sections = payload.putObject("sections"), coverage = result.putObject("coverage");
    for (String group : CaseEvidenceSchema.COLUMNS.keySet()) {
      sections.putObject(group).put("note", "").putArray("rows");
      coverage.putObject(group).put("rowCount", 0).put("completion", "UNVERIFIED");
    }
    addRow(result, "PAYMENT", "2026-01-02T10:00:00");
    addRow(result, "HOST", "2026-01-02T10:10:00");
    addRow(result, "HISTORY", "2026-01-02T10:05:00");
    return result;
  }
  ObjectNode addRow(ObjectNode snapshot, String group, String timestamp) {
    ArrayNode rows = (ArrayNode) snapshot.path("payload").path("sections").path(group).path("rows");
    ObjectNode row = rows.addObject();
    CaseEvidenceSchema.COLUMNS.get(group).forEach(column -> row.put(column, ""));
    row.put("SOURCE_TABLE", CaseEvidenceSchema.SOURCE_TABLES.get(group));
    if (group.equals("PAYMENT")) row.put("REFTXNNUMBER", item().path("reference").asText())
        .put("NUMAMOUNT_4038", "12345678901234567890.007").put("DATINITIATION", timestamp).put("CODSTATUS", "991");
    else if (Set.of("HOST", "HISTORY").contains(group)) row.put("REF_TXN_NO", item().path("reference").asText())
        .put("COD_ORG_BANK", "099").put("COD_ORG_BRN", "0100").put("DAT_TXN", timestamp).put("TXN_STAT", "991");
    ((ObjectNode) snapshot.path("coverage").path(group)).put("rowCount", rows.size());
    return row;
  }
  JsonNode document(ObjectNode result, String id) {
    for (JsonNode document : result.path("documents")) if (id.equals(document.path("id").asText())) return document;
    throw new AssertionError("Missing document " + id);
  }
  ObjectNode project(ObjectNode snapshot) { return new CaseEvidenceProjection(mapper, "").project(analyst, item(), snapshot); }
  ObjectNode guide(String tenant, String content) {
    ObjectNode value = mapper.createObjectNode().put("schemaVersion", "fcr-case-guidance-v1")
        .put("tenantId", tenant).put("evidenceSchema", "fcr-case-evidence-v1");
    ObjectNode document = value.putArray("documents").addObject().put("id", "TENANT-GUIDE")
        .put("kind", "knowledge").put("title", "Original tenant guide").put("content", content);
    document.putObject("source").put("file", "original-fixture-guide.md").put("locator", "Fixture source paragraph");
    return value;
  }
  Path writeGuide(ObjectNode guide) throws Exception {
    Path file = temporary.resolve("guidance.json"); Files.write(file, mapper.writeValueAsBytes(guide)); return file;
  }
  void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable run, int status) {
    assertThatThrownBy(run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(status));
  }

  @Test void rowDocumentsContainExactNativeJsonAndStableCitableLocatorsWithoutMutation() throws Exception {
    ObjectNode input = snapshot(), original = input.deepCopy();
    var result = project(input);
    assertThat(result.path("documents")).hasSize(11);
    JsonNode sourceRow = input.path("payload").path("sections").path("PAYMENT").path("rows").get(0);
    JsonNode doc = document(result, "PAYMENT-ROW-1");
    assertThat(mapper.readTree(doc.path("content").asText())).isEqualTo(sourceRow);
    assertThat(doc.path("source").path("file").asText()).isEqualTo("case-evidence/EVD-FIXTURE-1.json");
    assertThat(doc.path("source").path("locator").asText()).contains("PAYMENT.rows[0]", "source row 1");
    assertThat(mapper.readTree(document(result, "CASE-CONTEXT").path("content").asText()).path("savedWarnings"))
        .isEqualTo(input.path("warnings"));
    assertThat(input).isEqualTo(original);
    UatService.documentMap(result.path("documents"), new ApiException(422, "TEST", "Invalid test documents"));
    assertThat(result.path("guidanceHash").asText()).matches("[a-f0-9]{64}");
  }
  @Test void unknownStatusCodesStayRawAndGenericGuidanceDoesNotInventTheirMapping() {
    var result = project(snapshot());
    JsonNode event = result.path("timeline").get(0);
    assertThat(event.path("fields").path("CODSTATUS").asText()).isEqualTo("991");
    assertThat(event.path("label").asText()).isEqualTo("PAYMENT row 1 · DATINITIATION observation");
    assertThat(document(result, "GUIDE-RAW-STATUS").path("content").asText()).contains("no verified meaning", "Do not invent labels").doesNotContain("991");
  }
  @Test void validSourceLocalTimestampsSortObservationsAndRetainOriginalRowIds() {
    var result = project(snapshot());
    assertThat(result.path("timeline").findValuesAsText("documentId")).containsExactly("PAYMENT-ROW-1", "HISTORY-ROW-1", "HOST-ROW-1");
    assertThat(result.path("timeline").get(1).path("timestamp").asText()).isEqualTo("2026-01-02T10:05:00");
    assertThat(result.path("timeline").get(1).path("rowIndex").asInt()).isEqualTo(1);
    assertThat(result.path("warnings").toString()).contains("Source timezone is UNKNOWN", "not an event chronology").isNotEmpty();
  }
  @Test void missingInvalidOrOffsetTimestampsPreserveExportOrderAndRawText() {
    for (String timestamp : List.of("", "2026-02-30T12:00:00", "2026-01-02T10:10:00Z", "01/02/2026 10:10 AM")) {
      ObjectNode input = snapshot();
      ((ObjectNode) input.path("payload").path("sections").path("HOST").path("rows").get(0)).put("DAT_TXN", timestamp);
      var result = project(input);
      assertThat(result.path("timeline").findValuesAsText("documentId")).containsExactly("PAYMENT-ROW-1", "HOST-ROW-1", "HISTORY-ROW-1");
      JsonNode displayed = result.path("timeline").get(1).path("timestamp");
      if (timestamp.isEmpty()) assertThat(displayed.isNull()).isTrue(); else assertThat(displayed.asText()).isEqualTo(timestamp);
      assertThat(result.path("warnings").toString()).contains("retains group and exported-row order");
    }
  }
  @Test void tiedObservationsRetainExportOrderWithoutClaimingAnEventSequence() {
    ObjectNode input = snapshot(); addRow(input, "HISTORY", "2026-01-02T10:05:00");
    var result = project(input);
    assertThat(result.path("timeline").findValuesAsText("documentId")).containsExactly("PAYMENT-ROW-1", "HISTORY-ROW-1", "HISTORY-ROW-2", "HOST-ROW-1");
    assertThat(result.path("warnings").toString()).contains("timestamps are tied");
  }
  @Test void emptyGroupCoverageDescribesSuppliedCountsAndDoesNotInventRows() throws Exception {
    var result = project(snapshot());
    var coverage = mapper.readTree(document(result, "STATUS-COVERAGE").path("content").asText());
    assertThat(coverage.path("suppliedRowCount").asInt()).isZero();
    assertThat(coverage.path("savedCoverage").path("completion").asText()).isEqualTo("UNVERIFIED");
    assertThat(coverage.path("meaning").asText()).contains("zero count alone does not prove");
    assertThat(result.path("timeline").findValuesAsText("group")).doesNotContain("STATUS");
    assertThat(result.path("documents").findValuesAsText("id")).doesNotContain("STATUS-ROW-1");
  }
  @Test void privateGuidanceIsTenantScopedCopiedAndChangesTheCanonicalGuidanceHash() throws Exception {
    Path file = writeGuide(guide("northstar", "Original fixture guidance for this tenant."));
    var projection = new CaseEvidenceProjection(mapper, file.toString());
    var first = projection.project(analyst, item(), snapshot());
    assertThat(document(first, "TENANT-GUIDE").path("content").asText()).contains("this tenant");
    var otherResult = projection.project(other, item(), snapshot());
    assertThat(otherResult.path("documents").findValuesAsText("id")).doesNotContain("TENANT-GUIDE");
    assertThat(otherResult.path("guidanceHash")).isEqualTo(project(snapshot()).path("guidanceHash"));
    writeGuide(guide("northstar", "Changed original fixture guidance."));
    var second = projection.project(analyst, item(), snapshot());
    assertThat(second.path("guidanceHash")).isNotEqualTo(first.path("guidanceHash"));
    assertThat(document(first, "TENANT-GUIDE").path("content").asText()).isEqualTo("Original fixture guidance for this tenant.");
  }
  @Test void guidanceRejectsEvidenceRowsWrongEnvelopeAndDuplicateJsonKeys() throws Exception {
    ObjectNode guide = guide("northstar", "Original fixture guidance.");
    ((ObjectNode) guide.path("documents").get(0)).put("kind", "evidence");
    Path file = writeGuide(guide); var projection = new CaseEvidenceProjection(mapper, file.toString());
    rejected(() -> projection.project(analyst, item(), snapshot()), 503);
    guide = guide("northstar", "Original fixture guidance."); guide.put("unexpected", "field"); writeGuide(guide);
    rejected(() -> projection.project(analyst, item(), snapshot()), 503);
    String duplicate = mapper.writeValueAsString(guide("northstar", "Original fixture guidance.")).replace("\"tenantId\":\"northstar\"", "\"tenantId\":\"northstar\",\"tenantId\":\"silverline\"");
    Files.writeString(file, duplicate, StandardCharsets.UTF_8);
    rejected(() -> projection.project(analyst, item(), snapshot()), 503);
  }
  @Test void knowledgeCannotReuseEvidenceCitationIdsAndDocumentBoundRejectsWithoutTruncation() throws Exception {
    ObjectNode guide = guide("northstar", "Original fixture guidance."); ((ObjectNode) guide.path("documents").get(0)).put("id", "PAYMENT-ROW-1");
    Path file = writeGuide(guide);
    rejected(() -> new CaseEvidenceProjection(mapper, file.toString()).project(analyst, item(), snapshot()), 503);
    ObjectNode input = snapshot();
    for (int index = 0; index < 90; index++) addRow(input, "STATUS", "");
    ObjectNode before = input.deepCopy();
    assertThatThrownBy(() -> project(input)).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.status).isEqualTo(422); assertThat(e.getMessage()).contains("100 source documents", "Narrow", "no source rows were truncated");
    });
    assertThat(input).isEqualTo(before);
  }
  @Test void totalSourceCharactersAndPrivateFileSizeAreBounded() throws Exception {
    Path file = writeGuide(guide("northstar", "x".repeat(49000)));
    rejected(() -> new CaseEvidenceProjection(mapper, file.toString()).project(analyst, item(), snapshot()), 422);
    Files.writeString(file, "x".repeat(256 * 1024 + 1), StandardCharsets.UTF_8);
    rejected(() -> new CaseEvidenceProjection(mapper, file.toString()).project(analyst, item(), snapshot()), 503);
  }
  @Test void wrongCaseOrPaymentIdentityIsRejectedBeforeProjection() {
    ObjectNode wrongCase = snapshot(); wrongCase.put("caseId", "DIFFERENT"); rejected(() -> project(wrongCase), 422);
    ObjectNode mismatch = snapshot(); ((ObjectNode) mismatch.path("payload").path("payment")).put("orgBank", "999");
    rejected(() -> project(mismatch), 422);
  }
}
