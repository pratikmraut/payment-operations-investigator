package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import static dev.pratik.poi.PaymentDiscoveryWorkbookTest.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class CaseEvidenceWorkbookTest {
  static String evidenceHeader(String group, int offset) {
    StringBuilder row = new StringBuilder("<row r=\"1\">");
    var columns = CaseEvidenceSchema.COLUMNS.get(group);
    for (int index = 0; index < columns.size(); index++)
      row.append(text(CaseEvidenceSchema.columnName(index + offset + 1) + "1", columns.get(index)));
    return row.append("</row>").toString();
  }
  static String evidenceRow(String group, int number, int offset) {
    StringBuilder row = new StringBuilder("<row r=\"").append(number).append("\">");
    if (offset == 1) row.append(numeric("A" + number, Integer.toString(number - 1), 0));
    var columns = CaseEvidenceSchema.COLUMNS.get(group);
    for (int index = 0; index < columns.size(); index++) {
      String column = columns.get(index);
      String value = column.equals("SOURCE_TABLE") ? CaseEvidenceSchema.SOURCE_TABLES.get(group)
          : column.equals("REF_TXN_NO") || column.equals("REFTXNNUMBER") ? REFERENCE : "fixture-" + index;
      row.append(text(CaseEvidenceSchema.columnName(index + offset + 1) + number, value));
    }
    return row.append("</row>").toString();
  }
  static void reject(String group, Map<String, String> parts, int status) throws Exception {
    byte[] bytes = zip(parts);
    assertThatThrownBy(() -> CaseEvidenceSchema.read(group, bytes)).isInstanceOfSatisfying(ApiException.class,
        failure -> assertThat(failure.status).isEqualTo(status));
  }
  static Map<String, String> unpack(byte[] bytes) throws Exception {
    Map<String, String> result = new LinkedHashMap<>();
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      java.util.zip.ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null)
        result.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
    }
    return result;
  }
  @Test void schemaHasOrderedFourGroupsAndExactCountsAndSourceContracts() {
    assertThat(CaseEvidenceSchema.COLUMNS.keySet()).containsExactly("PAYMENT", "HOST", "HISTORY", "STATUS");
    assertThat(CaseEvidenceSchema.COLUMNS.values().stream().map(List::size).toList()).containsExactly(29, 36, 15, 7);
    assertThat(CaseEvidenceSchema.COLUMNS.get("PAYMENT")).startsWith("SOURCE_TABLE", "QUERY_OBSERVED_AT", "SCOPE_ROW_COUNT", "REFTXNNUMBER");
    assertThat(CaseEvidenceSchema.COLUMNS.get("HOST")).contains("REF_SUBSEQ_NO").endsWith("REF_TXN_NO_REV");
    assertThat(CaseEvidenceSchema.COLUMNS.get("HISTORY")).doesNotContain("REF_SUBSEQ_NO").endsWith("COD_CHNL_ID");
    assertThat(CaseEvidenceSchema.COLUMNS.get("STATUS")).containsExactly("SOURCE_TABLE", "QUERY_OBSERVED_AT", "SCOPE_ROW_COUNT", "CODSTATUS", "MSGSTATUS", "ACCTSTATUS", "NEFTCODSTATUS");
    assertThat(CaseEvidenceSchema.FUNCTIONS.get("HISTORY")).isEqualTo("AP_BA_NEFT_HISTORY_INQ");
    assertThat(CaseEvidenceSchema.SOURCE_TABLES.get("HISTORY")).isEqualTo("PM_TXN_LOG_HIST");
    assertThatThrownBy(() -> CaseEvidenceSchema.COLUMNS.put("EXTRA", List.of())).isInstanceOf(UnsupportedOperationException.class);
  }
  @Test void generatedTemplatesAreHeaderOnlyAndEveryGroupRoundTripsWithoutARecord() throws Exception {
    for (String group : CaseEvidenceSchema.COLUMNS.keySet()) {
      byte[] template = CaseEvidenceSchema.template(group);
      assertThat(CaseEvidenceSchema.read(group, template)).isEmpty();
      var archive = unpack(template);
      assertThat(archive.get("xl/styles.xml")).contains("numFmtId=\"49\"", "<b/>");
      assertThat(archive.get("xl/worksheets/sheet1.xml")).contains("state=\"frozen\"", "style=\"0\"");
      for (String column : CaseEvidenceSchema.COLUMNS.get(group))
        assertThat(archive.get("xl/worksheets/sheet1.xml")).contains("<t>" + column + "</t>");
    }
  }
  @Test void fullWideHostRowsAndEveryOtherGroupPreserveExactStrings() throws Exception {
    for (String group : CaseEvidenceSchema.COLUMNS.keySet()) {
      var result = CaseEvidenceSchema.read(group, zip(parts(evidenceHeader(group, 0) + evidenceRow(group, 2, 0))));
      assertThat(result).hasSize(1);
      assertThat(result.get(0).keySet()).containsExactlyElementsOf(CaseEvidenceSchema.COLUMNS.get(group));
      assertThat(result.get(0).get("SOURCE_TABLE")).isEqualTo(CaseEvidenceSchema.SOURCE_TABLES.get(group));
      if (!group.equals("STATUS")) assertThat(result.get(0).get(group.equals("PAYMENT") ? "REFTXNNUMBER" : "REF_TXN_NO")).isEqualTo(REFERENCE);
    }
  }
  @Test void rejectsNumericValuesInAnySourceColumnIncludingNumericStatusAndAmount() throws Exception {
    String group = "PAYMENT";
    for (String column : List.of("D2", "J2", "R2")) {
      String data = evidenceRow(group, 2, 0).replaceAll("<c r=\"" + column + "\"[^>]*>.*?</c>", numeric(column, "12345678901234567890123", 0));
      reject(group, parts(evidenceHeader(group, 0) + data), 422);
    }
  }
  @Test void rejectsFormulaErrorBooleanAndTypedDateEvenIfCachedOrBlank() throws Exception {
    for (String cell : List.of("<c r=\"D2\"><f>1+1</f><v>2</v></c>", "<c r=\"D2\" t=\"e\"><v>#N/A</v></c>",
        "<c r=\"D2\" t=\"b\"><v>1</v></c>", "<c r=\"D2\" t=\"d\"><v>2026-01-01T00:00:00</v></c>", "<c r=\"D2\" t=\"e\"/>")) {
      String data = evidenceRow("PAYMENT", 2, 0).replace(text("D2", REFERENCE), cell);
      reject("PAYMENT", parts(evidenceHeader("PAYMENT", 0) + data), 422);
    }
  }
  @Test void acceptsOptionalNumericOrTextDisplayOrdinalButRejectsInvalidOrdinal() throws Exception {
    var rows = parts(evidenceHeader("HOST", 1) + evidenceRow("HOST", 2, 1));
    assertThat(CaseEvidenceSchema.read("HOST", zip(rows))).hasSize(1);
    String source = evidenceHeader("HOST", 1).replace("<row r=\"1\">", "<row r=\"1\">" + text("A1", "ROWNUM"))
        + evidenceRow("HOST", 2, 1).replace(numeric("A2", "1", 0), text("A2", "1"));
    assertThat(CaseEvidenceSchema.read("HOST", zip(parts(source)))).hasSize(1);
    reject("HOST", parts(source.replace(text("A2", "1"), text("A2", "1.5"))), 422);
  }
  @Test void wrongGroupMissingDuplicateReorderedOrExtraHeadersAreRejected() throws Exception {
    String header = evidenceHeader("STATUS", 0);
    for (String changed : List.of(header.replace("NEFTCODSTATUS", "UNKNOWN"), header.replace("NEFTCODSTATUS", ""),
        header.replace("NEFTCODSTATUS", "CODSTATUS"), header.replace("CODSTATUS</t>", "ACCTSTATUS</t>"),
        header.replace("</row>", text("H1", "EXTRA") + "</row>"))) reject("STATUS", parts(changed), 422);
    reject("HOST", parts(header), 422);
    assertThatThrownBy(() -> CaseEvidenceSchema.template("UNKNOWN")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(422));
  }
  @Test void blankMissingCellsRemainEmptyTextAndNoDataIsFabricated() throws Exception {
    String data = "<row r=\"2\">" + text("A2", "PM_NEFT_TXN_LOG") + text("D2", REFERENCE) + "<c r=\"G2\"/></row>";
    var result = CaseEvidenceSchema.read("PAYMENT", zip(parts(evidenceHeader("PAYMENT", 0) + data)));
    assertThat(result.get(0)).containsEntry("UTR_REF_NO", "").containsEntry("NUMAMOUNT_4038", "");
    assertThat(CaseEvidenceSchema.read("STATUS", zip(parts(evidenceHeader("STATUS", 0) + "<row r=\"2\"><c r=\"A2\"/></row>")))).isEmpty();
  }
  @Test void populatedCellsOutsideDeclaredHeadersCannotBeSilentlyDropped() throws Exception {
    String data = evidenceRow("STATUS", 2, 0).replace("</row>", text("H2", "extra-value") + "</row>");
    reject("STATUS", parts(evidenceHeader("STATUS", 0) + data), 422);
    String header = evidenceHeader("STATUS", 1);
    String shifted = evidenceRow("STATUS", 2, 1).replace(numeric("A2", "1", 0), text("A2", "not-an-ordinal"));
    reject("STATUS", parts(header + shifted), 422);
  }
  @Test void supportsBlankExtraWorksheetsButRejectsAdditionalPopulatedSheets() throws Exception {
    var map = parts(evidenceHeader("STATUS", 0));
    map.compute("xl/workbook.xml", (k, v) -> v.replace("</sheets>", "<sheet name=\"Sheet2\" sheetId=\"2\" r:id=\"second\"/></sheets>"));
    map.compute("xl/_rels/workbook.xml.rels", (k, v) -> v.replace("</Relationships>", "<Relationship Id=\"second\" Type=\"" + OFFICE + "/worksheet\" Target=\"worksheets/second.xml\"/></Relationships>"));
    map.put("xl/worksheets/second.xml", worksheet(""));
    assertThat(CaseEvidenceSchema.read("STATUS", zip(map))).isEmpty();
    map.put("xl/worksheets/second.xml", worksheet(evidenceHeader("STATUS", 0)));
    reject("STATUS", map, 422);
  }
  @Test void permits4000CharacterTextButRejectsLargerValuesAndMoreThan500Rows() throws Exception {
    String row = evidenceRow("PAYMENT", 2, 0).replace(text("D2", REFERENCE), text("D2", "x".repeat(4000)));
    assertThat(CaseEvidenceSchema.read("PAYMENT", zip(parts(evidenceHeader("PAYMENT", 0) + row))).get(0).get("REFTXNNUMBER")).hasSize(4000);
    reject("PAYMENT", parts(evidenceHeader("PAYMENT", 0) + row.replace("x".repeat(4000), "x".repeat(4001))), 413);
    StringBuilder rows = new StringBuilder(evidenceHeader("STATUS", 0));
    for (int number = 2; number <= 501; number++) rows.append(evidenceRow("STATUS", number, 0));
    assertThat(CaseEvidenceSchema.read("STATUS", zip(parts(rows.toString())))).hasSize(500);
    rows.append(evidenceRow("STATUS", 502, 0)); reject("STATUS", parts(rows.toString()), 413);
  }
  @Test void sharedStringsFollowTheSameEvidenceLengthAndExactTextRules() throws Exception {
    var map = parts(evidenceHeader("PAYMENT", 0) + evidenceRow("PAYMENT", 2, 0).replace(text("D2", REFERENCE), "<c r=\"D2\" t=\"s\"><v>0</v></c>"));
    map.put("xl/sharedStrings.xml", "<sst xmlns=\"" + MAIN + "\"><si><t>" + REFERENCE + "</t></si></sst>");
    map.compute("xl/_rels/workbook.xml.rels", (k, v) -> v.replace("</Relationships>", "<Relationship Id=\"strings\" Type=\"" + OFFICE + "/sharedStrings\" Target=\"sharedStrings.xml\"/></Relationships>"));
    assertThat(CaseEvidenceSchema.read("PAYMENT", zip(map)).get(0).get("REFTXNNUMBER")).isEqualTo(REFERENCE);
    map.put("xl/sharedStrings.xml", "<sst xmlns=\"" + MAIN + "\"><si><t>" + "x".repeat(4001) + "</t></si></sst>");
    reject("PAYMENT", map, 413);
  }
  @Test void displayOrdinalsWithoutEvidenceDoNotConsumeThe500SourceRowAllowance() throws Exception {
    StringBuilder rows = new StringBuilder(evidenceHeader("STATUS", 1));
    for(int number = 2; number <= 501; number++)rows.append(evidenceRow("STATUS", number, 1));
    rows.append("<row r=\"502\">").append(numeric("A502", "501", 0)).append("</row>");
    assertThat(CaseEvidenceSchema.read("STATUS", zip(parts(rows.toString())))).hasSize(500);
    rows.append(evidenceRow("STATUS", 503, 1));
    reject("STATUS", parts(rows.toString()), 413);
  }
  @Test void fourFileErrorsIdentifyTheSelectedGroupAndStillRejectMalformedDisplayOrdinals() throws Exception {
    byte[] wrongGroup = CaseEvidenceSchema.template("STATUS");
    assertThatThrownBy(() -> CaseEvidenceSchema.read("HOST", wrongGroup))
        .isInstanceOfSatisfying(ApiException.class, failure -> {
          assertThat(failure.status).isEqualTo(422); assertThat(failure.getMessage()).startsWith("HOST Excel file:").contains("header");
        });
    String rows = evidenceHeader("STATUS", 1) + "<row r=\"2\">" + text("A2", "PRIVATE-INVALID-ORDINAL") + "</row>";
    byte[] invalid = zip(parts(rows));
    assertThatThrownBy(() -> CaseEvidenceSchema.read("STATUS", invalid)).satisfies(failure ->
        assertThat(failure.getMessage()).startsWith("STATUS Excel file:").contains("display ordinal").doesNotContain("PRIVATE-INVALID-ORDINAL"));
  }
  @Test void evidenceEntryPointRetainsArchiveXmlAndExternalLinkProtections() throws Exception {
    var traversal = parts(evidenceHeader("STATUS", 0)); traversal.put("../outside.xml", "x"); reject("STATUS", traversal, 422);
    var entities = parts(evidenceHeader("STATUS", 0)); entities.compute("xl/workbook.xml", (k, v) -> "<!DOCTYPE workbook [<!ENTITY secret SYSTEM 'file:///not-read'>]>" + v); reject("STATUS", entities, 422);
    var external = parts(evidenceHeader("STATUS", 0)); external.compute("xl/_rels/workbook.xml.rels", (k, v) -> v.replace("</Relationships>", "<Relationship Id=\"external\" Type=\"" + OFFICE + "/hyperlink\" Target=\"https://example.invalid\" TargetMode=\"External\"/></Relationships>")); reject("STATUS", external, 422);
    var macros = parts(evidenceHeader("STATUS", 0)); macros.put("xl/vbaProject.bin", "not-executed"); reject("STATUS", macros, 422);
    assertThatThrownBy(() -> CaseEvidenceSchema.read("STATUS", new byte[PaymentDiscoveryWorkbook.MAX_BYTES + 1])).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(413));
  }
}
