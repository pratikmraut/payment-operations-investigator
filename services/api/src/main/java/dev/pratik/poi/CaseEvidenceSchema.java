package dev.pratik.poi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Native inquiry column interface. No payment values or source implementation are embedded. */
public final class CaseEvidenceSchema {
  public static final Map<String, List<String>> COLUMNS;
  public static final Map<String, String> FUNCTIONS;
  public static final Map<String, String> SOURCE_TABLES;
  public static final int MAX_ROWS = 500;
  public static final int MAX_CELL_LENGTH = 4000;
  private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
  private static final String OFFICE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
  private static final String PACKAGE = "http://schemas.openxmlformats.org/package/2006/relationships";

  static {
    var columns = new LinkedHashMap<String, List<String>>();
    columns.put("PAYMENT", List.of("SOURCE_TABLE", "QUERY_OBSERVED_AT", "SCOPE_ROW_COUNT", "REFTXNNUMBER",
        "IDUSERREFERENCE_2020", "IDRELATEDREF_2006", "UTR_REF_NO", "IDMSGREFERENCE_2020", "IDSEQUENCENO",
        "NUMAMOUNT_4038", "CODCURR", "DIRECTION", "DATINITIATION", "DATVALUE_3380", "MSGTYPE", "SUBMSGTYPE",
        "TXNTYPE", "CODSTATUS", "ACCTSTATUS", "MSGSTATUS", "NUMRETRY", "BATCH_TIME_3535", "REASONCODE_6346",
        "N10_MSGID", "N10_IDSEQUENCENO", "N10_TXNID", "N10_STATUS", "N10_DATETIME", "N10_RECV_SENT_DATETIME"));
    columns.put("HOST", List.of("SOURCE_TABLE", "QUERY_OBSERVED_AT", "SCOPE_ROW_COUNT", "REF_TXN_NO",
        "REF_SUBSEQ_NO", "REF_NETWORK_NO", "REF_USR_NO", "COD_ORG_BRN", "COD_ORG_BANK", "COD_NETWORK_ID",
        "COD_PAYMENT_TXN", "PAY_DIR", "COD_CHNL_ID", "AMT_TXN_TCY", "COD_TXN_CCY", "DAT_TXN", "DAT_INITIATION",
        "DAT_POST", "DAT_VALUE", "DAT_ACTIVATION", "DAT_DISPATCH", "DAT_NTWK_VALUE", "DAT_AUTHTIME",
        "DAT_SECOND_AUTHTIME", "TXN_STAT", "ACCT_STAT", "MSG_STAT", "NTWK_ACCT_STAT", "CONTG_ACCT_STAT",
        "CT_PARTY_ACCT_STAT", "FLG_POST_CUTOFF", "COD_REPLY", "COD_EXT", "COD_REJECT", "SFMS_REJ_CODE", "REF_TXN_NO_REV"));
    columns.put("HISTORY", List.of("SOURCE_TABLE", "QUERY_OBSERVED_AT", "SCOPE_ROW_COUNT", "REF_TXN_NO",
        "COD_ORG_BRN", "COD_ORG_BANK", "DAT_TXN", "DAT_POST", "DAT_VALUE", "ACCT_STAT", "MSG_STAT", "TXN_STAT",
        "NTWK_ACCT_STAT", "CONTG_ACCT_STAT", "COD_CHNL_ID"));
    columns.put("STATUS", List.of("SOURCE_TABLE", "QUERY_OBSERVED_AT", "SCOPE_ROW_COUNT", "CODSTATUS",
        "MSGSTATUS", "ACCTSTATUS", "NEFTCODSTATUS"));
    COLUMNS = Collections.unmodifiableMap(columns);
    var functions = new LinkedHashMap<String, String>();
    columns.keySet().forEach(group -> functions.put(group, "AP_BA_NEFT_" + group + "_INQ"));
    FUNCTIONS = Collections.unmodifiableMap(functions);
    var tables = new LinkedHashMap<String, String>();
    tables.put("PAYMENT", "PM_NEFT_TXN_LOG"); tables.put("HOST", "PM_TXN_LOG");
    tables.put("HISTORY", "PM_TXN_LOG_HIST"); tables.put("STATUS", "NEFTTXNCODSTATUS");
    SOURCE_TABLES = Collections.unmodifiableMap(tables);
  }
  private CaseEvidenceSchema() { }

  public static List<String> columns(String group) {
    List<String> result = group == null ? null : COLUMNS.get(group);
    if (result == null) throw new ApiException(422, "INVALID_EVIDENCE_GROUP", "Select PAYMENT, HOST, HISTORY or STATUS.");
    return result;
  }

  public static List<Map<String, String>> read(String group, byte[] bytes) {
    List<String> expected = columns(group);
    try { return PaymentDiscoveryWorkbook.readColumns(bytes, expected); }
    catch (ApiException failure) {
      throw new ApiException(failure.status, failure.code, group + " Excel file: " + failure.getMessage());
    }
  }

  /** Empty, exact-header XLSX; the caller fills source values without numeric conversion. */
  public static byte[] template(String group) {
    List<String> columns = columns(group);
    Map<String, String> parts = new LinkedHashMap<>();
    parts.put("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
        + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
        + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
        + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
        + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>");
    parts.put("_rels/.rels", "<Relationships xmlns=\"" + PACKAGE + "\"><Relationship Id=\"workbook\" Type=\"" + OFFICE
        + "/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
    parts.put("xl/workbook.xml", "<workbook xmlns=\"" + MAIN + "\" xmlns:r=\"" + OFFICE
        + "\"><sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"data\"/></sheets></workbook>");
    parts.put("xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"" + PACKAGE + "\">"
        + "<Relationship Id=\"data\" Type=\"" + OFFICE + "/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
        + "<Relationship Id=\"styles\" Type=\"" + OFFICE + "/styles\" Target=\"styles.xml\"/></Relationships>");
    parts.put("xl/styles.xml", "<styleSheet xmlns=\"" + MAIN + "\">"
        + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
        + "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font></fonts>"
        + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
        + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF17365D\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
        + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
        + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
        + "<cellXfs count=\"2\"><xf numFmtId=\"49\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
        + "<xf numFmtId=\"49\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyAlignment=\"1\">"
        + "<alignment vertical=\"center\" wrapText=\"1\"/></xf></cellXfs>"
        + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
    String last = columnName(columns.size());
    StringBuilder sheet = new StringBuilder("<worksheet xmlns=\"").append(MAIN).append("\"><dimension ref=\"A1:")
        .append(last).append("1\"/><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
        .append("<sheetFormatPr defaultRowHeight=\"18\"/><cols><col min=\"1\" max=\"").append(columns.size())
        .append("\" width=\"32\" customWidth=\"1\" style=\"0\"/></cols><sheetData><row r=\"1\" ht=\"34\" customHeight=\"1\">");
    for (int index = 0; index < columns.size(); index++) sheet.append("<c r=\"").append(columnName(index + 1))
        .append("1\" t=\"inlineStr\" s=\"1\"><is><t>").append(columns.get(index)).append("</t></is></c>");
    sheet.append("</row></sheetData><autoFilter ref=\"A1:").append(last).append("1\"/></worksheet>");
    parts.put("xl/worksheets/sheet1.xml", sheet.toString());
    try {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (ZipOutputStream archive = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
        for (var part : parts.entrySet()) {
          ZipEntry entry = new ZipEntry(part.getKey()); entry.setTime(0L);
          archive.putNextEntry(entry); archive.write(part.getValue().getBytes(StandardCharsets.UTF_8)); archive.closeEntry();
        }
      }
      return output.toByteArray();
    } catch (IOException ex) { throw new IllegalStateException("Could not create evidence template.", ex); }
  }

  static String columnName(int number) {
    StringBuilder result = new StringBuilder();
    while (number > 0) { number--; result.insert(0, (char) ('A' + number % 26)); number /= 26; }
    return result.toString();
  }
}
