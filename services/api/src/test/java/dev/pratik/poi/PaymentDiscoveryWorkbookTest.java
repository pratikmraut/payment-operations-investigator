package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;

class PaymentDiscoveryWorkbookTest {
  static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
  static final String OFFICE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
  static final String PACKAGE = "http://schemas.openxmlformats.org/package/2006/relationships";
  static final List<String> HEADERS = List.of("PIO_REF_TXN_NO", "PIO_ORG_BRN", "PIO_ORG_BANK", "REF_SUBSEQ_NO", "UTR_REF_NO", "DATINITIATION", "NUMAMOUNT_4038", "CURRENCY", "DIRECTION");
  static final String REFERENCE = "00123456789012345678901234";
  static String text(String cell, String value) { return "<c r=\"" + cell + "\" t=\"inlineStr\"><is><t>" + value + "</t></is></c>"; }
  static String numeric(String cell, String value, int style) { return "<c r=\"" + cell + "\" s=\"" + style + "\"><v>" + value + "</v></c>"; }
  static String header(int offset) {
    StringBuilder cells = new StringBuilder();
    for (int i = 0; i < HEADERS.size(); i++) cells.append(text("" + (char)('A' + offset + i) + "1", HEADERS.get(i)));
    return "<row r=\"1\">" + cells + "</row>";
  }
  static String data(int row) {
    return "<row r=\"" + row + "\">" + text("A" + row, REFERENCE) + numeric("B" + row, "1352", 0)
        + numeric("C" + row, "760", 0) + numeric("D" + row, "1.0", 0) + text("E" + row, "DEMO-UTR-001")
        + numeric("F" + row, "61.5", 1) + numeric("G" + row, "1.234500E+3", 0)
        + text("H" + row, "INR") + text("I" + row, "OUTBOUND") + "</row>";
  }
  static String worksheet(String rows) { return "<worksheet xmlns=\"" + MAIN + "\"><sheetData>" + rows + "</sheetData></worksheet>"; }
  static Map<String, String> parts(String rows) {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/></Types>");
    map.put("_rels/.rels", "<Relationships xmlns=\"" + PACKAGE + "\"><Relationship Id=\"workbook\" Type=\"" + OFFICE + "/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
    map.put("xl/workbook.xml", "<workbook xmlns=\"" + MAIN + "\" xmlns:r=\"" + OFFICE + "\"><workbookPr date1904=\"0\"/><sheets><sheet name=\"Payments\" sheetId=\"1\" r:id=\"data\"/></sheets></workbook>");
    map.put("xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"" + PACKAGE + "\"><Relationship Id=\"data\" Type=\"" + OFFICE + "/worksheet\" Target=\"worksheets/data.xml\"/><Relationship Id=\"styles\" Type=\"" + OFFICE + "/styles\" Target=\"styles.xml\"/></Relationships>");
    map.put("xl/styles.xml", "<styleSheet xmlns=\"" + MAIN + "\"><cellXfs><xf numFmtId=\"0\"/><xf numFmtId=\"22\"/></cellXfs></styleSheet>");
    map.put("xl/worksheets/data.xml", worksheet(rows));
    return map;
  }
  static byte[] zip(Map<String, String> parts) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      for (var part : parts.entrySet()) {
        zip.putNextEntry(new ZipEntry(part.getKey())); zip.write(part.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
      }
    }
    return output.toByteArray();
  }
  void rejected(Map<String, String> parts, int status) throws Exception {
    byte[] bytes = zip(parts);
    assertThatThrownBy(() -> PaymentDiscoveryWorkbook.read(bytes)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(status));
  }
  @Test void preservesLongTextIdsAndExactNumericMoneyAndReadsNativeDates() throws Exception {
    var rows = PaymentDiscoveryWorkbook.read(zip(parts(header(0) + data(2))));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("PIO_REF_TXN_NO", REFERENCE).containsEntry("PIO_ORG_BRN", "1352")
        .containsEntry("REF_SUBSEQ_NO", "1").containsEntry("NUMAMOUNT_4038", "1234.500")
        .containsEntry("DATINITIATION", "1900-03-01T12:00:00").containsEntry("DIRECTION", "OUTBOUND");
  }
  @Test void date1904SystemAndIsoTextAreExplicitAndLeapBugIsRejected() throws Exception {
    var map = parts(header(0) + data(2).replace("61.5", "0"));
    map.compute("xl/workbook.xml", (k, v) -> v.replace("date1904=\"0\"", "date1904=\"1\""));
    assertThat(PaymentDiscoveryWorkbook.read(zip(map)).get(0).get("DATINITIATION")).isEqualTo("1904-01-01T00:00:00");
    map = parts(header(0) + data(2).replace(numeric("F2", "61.5", 1), text("F2", "2026-09-14T12:30:01")));
    assertThat(PaymentDiscoveryWorkbook.read(zip(map)).get(0).get("DATINITIATION")).isEqualTo("2026-09-14T12:30:01");
    rejected(parts(header(0) + data(2).replace("61.5", "60.5")), 422);
  }
  @Test void ambiguousDatesAndUnformattedNumericDatesAreRejected() throws Exception {
    rejected(parts(header(0) + data(2).replace(numeric("F2", "61.5", 1), text("F2", "09/10/2026"))), 422);
    rejected(parts(header(0) + data(2).replace(numeric("F2", "61.5", 1), numeric("F2", "46100", 0))), 422);
    rejected(parts(header(0) + data(2).replace(numeric("F2", "61.5", 1), text("F2", "2026-09-14"))), 422);
  }
  @Test void numericReferencesAndUtrCannotSilentlyLoseDigits() throws Exception {
    for (String column : List.of("A2", "E2")) {
      String original = column.equals("A2") ? REFERENCE : "DEMO-UTR-001";
      byte[] bytes = zip(parts(header(0) + data(2).replace(text(column, original), numeric(column, "12345678901234500000000000", 0))));
      assertThatThrownBy(() -> PaymentDiscoveryWorkbook.read(bytes)).isInstanceOfSatisfying(ApiException.class, e -> {
        assertThat(e.status).isEqualTo(422); assertThat(e.getMessage()).contains("Text").doesNotContain("12345678901234500000000000");
      });
    }
  }
  @Test void rejectsUnknownDuplicateAndMissingHeaders() throws Exception {
    rejected(parts(header(0).replace("CURRENCY", "CUSTOMER_NAME") + data(2)), 422);
    rejected(parts(header(0).replace("UTR_REF_NO", "PIO_REF_TXN_NO") + data(2)), 422);
    rejected(parts(header(0).replace("UTR_REF_NO", "") + data(2)), 422);
  }
  @Test void rejectsFormulasEvenWhenCachedValueLooksValid() throws Exception {
    rejected(parts(header(0) + data(2).replace("<v>1.234500E+3</v>", "<f>1+1</f><v>2</v>")), 422);
  }
  @Test void followsSheetRelationshipsAndSupportsSharedStringsAndRichText() throws Exception {
    var map = parts(header(0) + data(2).replace(text("A2", REFERENCE), "<c r=\"A2\" t=\"s\"><v>0</v></c>"));
    map.put("xl/sharedStrings.xml", "<sst xmlns=\"" + MAIN + "\"><si><r><t>00</t></r><r><t>123456789012345678901234</t></r><rPh sb=\"0\" eb=\"2\"><t>phonetic annotation is not the identifier</t></rPh></si></sst>");
    map.compute("xl/_rels/workbook.xml.rels", (k, v) -> v.replace("</Relationships>", "<Relationship Id=\"strings\" Type=\"" + OFFICE + "/sharedStrings\" Target=\"sharedStrings.xml\"/></Relationships>"));
    assertThat(PaymentDiscoveryWorkbook.read(zip(map)).get(0).get("PIO_REF_TXN_NO")).isEqualTo(REFERENCE);
  }
  @Test void supportsOneLeadingOrdinalAndEmptyRowsAndIgnoresNotesContent() throws Exception {
    String cells = data(2);
    for (char column = 'I'; column >= 'A'; column--) cells = cells.replace("r=\"" + column + "2\"", "r=\"" + (char)(column + 1) + "2\"");
    cells = cells.replace("<row r=\"2\">", "<row r=\"2\">" + numeric("A2", "1", 0));
    var map = parts(header(1) + cells + "<row r=\"3\"><c r=\"B3\"/></row>");
    map.compute("xl/workbook.xml", (k, v) -> v.replace("<sheets>", "<sheets><sheet name=\"Notes\" sheetId=\"2\" r:id=\"notes\"/>"));
    map.compute("xl/_rels/workbook.xml.rels", (k, v) -> v.replace("</Relationships>", "<Relationship Id=\"notes\" Type=\"" + OFFICE + "/worksheet\" Target=\"worksheets/notes.xml\"/></Relationships>"));
    map.put("xl/worksheets/notes.xml", worksheet("<row r=\"1\"><c r=\"A1\"><f>1+1</f><v>2</v></c></row>"));
    assertThat(PaymentDiscoveryWorkbook.read(zip(map))).hasSize(1);
  }
  @Test void noDataOrCorruptArchiveIsActionable() throws Exception {
    rejected(parts(header(0)), 422);
    assertThatThrownBy(() -> PaymentDiscoveryWorkbook.read(new byte[]{1, 2, 3})).isInstanceOf(ApiException.class);
    byte[] valid = zip(parts(header(0) + data(2)));
    assertThatThrownBy(() -> PaymentDiscoveryWorkbook.read(Arrays.copyOf(valid, valid.length - 8))).isInstanceOf(ApiException.class);
  }
  @Test void archivesCannotTraverseDeclareEntitiesLinkExternallyOrContainMacros() throws Exception {
    var traversal = parts(header(0) + data(2)); traversal.put("../outside.xml", "x"); rejected(traversal, 422);
    var entities = parts(header(0) + data(2)); entities.compute("xl/workbook.xml", (k, v) -> "<!DOCTYPE workbook [<!ENTITY secret SYSTEM 'file:///not-read'>]>" + v); rejected(entities, 422);
    var external = parts(header(0) + data(2)); external.compute("xl/_rels/workbook.xml.rels", (k, v) -> v.replace("</Relationships>", "<Relationship Id=\"external\" Type=\"" + OFFICE + "/hyperlink\" Target=\"https://example.invalid\" TargetMode=\"External\"/></Relationships>")); rejected(external, 422);
    var macros = parts(header(0) + data(2)); macros.put("xl/vbaProject.bin", "not-executed"); rejected(macros, 422);
  }
  @Test void compressedInputExpandedArchiveAndRowCountsAreBounded() throws Exception {
    assertThatThrownBy(() -> PaymentDiscoveryWorkbook.read(new byte[PaymentDiscoveryWorkbook.MAX_BYTES + 1]))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(413));
    var expanded = parts(header(0) + data(2)); expanded.put("padding.txt", "x".repeat(20 * 1024 * 1024)); rejected(expanded, 413);
    StringBuilder rows = new StringBuilder(header(0)); for (int row = 2; row <= 2002; row++) rows.append(data(row));
    rejected(parts(rows.toString()), 413);
  }
  @Test void blankUtrIsPreservedAndFractionalScopeIdentifiersAreRejected() throws Exception {
    var map = parts(header(0) + data(2).replace(text("E2", "DEMO-UTR-001"), "<c r=\"E2\"/>"));
    assertThat(PaymentDiscoveryWorkbook.read(zip(map)).get(0)).containsEntry("UTR_REF_NO", "");
    rejected(parts(header(0) + data(2).replace(numeric("B2", "1352", 0), numeric("B2", "1352.5", 0))), 422);
  }
  @Test void duplicateCellAddressesAndMultipleDataSheetsAreRejected() throws Exception {
    rejected(parts(header(0) + data(2).replace("</row>", text("A2", "duplicate") + "</row>")), 422);
    var map = parts(header(0) + data(2));
    map.compute("xl/workbook.xml", (k, v) -> v.replace("</sheets>", "<sheet name=\"Other data\" sheetId=\"2\" r:id=\"second\"/></sheets>"));
    map.compute("xl/_rels/workbook.xml.rels", (k, v) -> v.replace("</Relationships>", "<Relationship Id=\"second\" Type=\"" + OFFICE + "/worksheet\" Target=\"worksheets/second.xml\"/></Relationships>"));
    map.put("xl/worksheets/second.xml", worksheet(header(0) + data(2))); rejected(map, 422);
  }
}
