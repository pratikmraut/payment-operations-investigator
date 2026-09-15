package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;

/** Explicit PO02 wire mapping. It records observations, not cursor-completion or payment outcomes. */
final class FlexcubeEvidenceAdapter {
  static final String LEGACY_VERSION = "flexcube-neft-evidence-v1";
  static final String VERSION = "flexcube-neft-evidence-v2";
  static final Map<String, String> ARRAYS = Map.of(
      "PAYMENT", "neftPaymentEvidenceDetails", "HOST", "neftHostEvidenceDetails",
      "HISTORY", "neftHistoryEvidenceDetails", "STATUS", "neftStatusEvidenceDetails");
  static final Map<String, List<String>> WIRE_COLUMNS = Map.of(
      "PAYMENT", List.of("sourceTable", "queryObservedAt", "scopeRowCount", "refTxnNumber",
          "idUserReference2020", "idRelatedRef2006", "utrRefNo", "idMsgReference2020", "idSequenceNo",
          "numAmount4038", "codCurr", "direction", "datInitiation", "datValue3380", "msgType", "subMsgType",
          "txnType", "codStatus", "acctStatus", "msgStatus", "numRetry", "batchTime3535", "reasonCode6346",
          "n10MsgId", "n10IdSequenceNo", "n10TxnId", "n10Status", "n10DateTime", "n10RecvSentDateTime"),
      "HOST", List.of("sourceTable", "queryObservedAt", "scopeRowCount", "refTxnNo", "refSubseqNo",
          "refNetworkNo", "refUsrNo", "codOrgBrn", "codOrgBank", "codNetworkId", "codPaymentTxn", "payDir",
          "codChnlId", "amtTxnTcy", "codTxnCcy", "datTxn", "datInitiation", "datPost", "datValue",
          "datActivation", "datDispatch", "datNtwkValue", "datAuthtime", "datSecondAuthtime", "txnStat",
          "acctStat", "msgStat", "ntwkAcctStat", "contgAcctStat", "ctPartyAcctStat", "flgPostCutoff",
          "codReply", "codExt", "codReject", "sfmsRejCode", "refTxnNoRev"),
      "HISTORY", List.of("sourceTable", "queryObservedAt", "scopeRowCount", "refTxnNo", "codOrgBrn",
          "codOrgBank", "datTxn", "datPost", "datValue", "acctStat", "msgStat", "txnStat", "ntwkAcctStat",
          "contgAcctStat", "codChnlId"),
      "STATUS", List.of("sourceTable", "queryObservedAt", "scopeRowCount", "codStatus", "msgStatus",
          "acctStatus", "neftCodStatus"));
  // Scope and query metadata must be supplied. Nullable STATUS tuple cells use the existing tuple check.
  static final Map<String, Set<String>> REQUIRED_COLUMNS = Map.of(
      "PAYMENT", Set.of("sourceTable", "queryObservedAt", "scopeRowCount", "refTxnNumber"),
      "HOST", Set.of("sourceTable", "queryObservedAt", "scopeRowCount", "refTxnNo", "codOrgBrn", "codOrgBank"),
      "HISTORY", Set.of("sourceTable", "queryObservedAt", "scopeRowCount", "refTxnNo", "codOrgBrn", "codOrgBank"),
      "STATUS", Set.of("sourceTable", "queryObservedAt", "scopeRowCount"));
  record Adapted(ObjectNode payload, ObjectNode upstream) {}

  private final ObjectMapper mapper;
  FlexcubeEvidenceAdapter(ObjectMapper mapper) { this.mapper = mapper; }

  Adapted adapt(ObjectNode response, ObjectNode request, String timezone, Instant receivedAt) {
    return adapt(response, request, timezone, receivedAt, VERSION);
  }

  private Adapted adapt(ObjectNode response, ObjectNode request, String timezone, Instant receivedAt, String version) {
    boolean legacy = LEGACY_VERSION.equals(version);
    Set<String> keys = new HashSet<>(ARRAYS.values()); keys.addAll(Set.of("postingDate", "transactionStatus"));
    exact(response, keys, "response");
    if (!response.path("postingDate").isTextual() || !response.path("transactionStatus").isObject())
      throw invalid("The PO02 response must include its posting date and transaction status.");
    JsonNode args = request.path("args1");
    if (!args.path("referenceTransactionNumber").isTextual()
        || !args.path("originatingBankCode").isIntegralNumber() || !args.path("originatingBranchCode").isIntegralNumber())
      throw invalid("The PO02 request identity is invalid.");
    ObjectNode payload = mapper.createObjectNode().put("schemaVersion", CaseEvidenceService.SCHEMA)
        .put("sourceTimezone", timezone);
    payload.putObject("payment").put("reference", args.path("referenceTransactionNumber").textValue())
        .put("orgBank", args.path("originatingBankCode").asText())
        .put("orgBranch", args.path("originatingBranchCode").asText());
    ObjectNode sections = payload.putObject("sections");
    ObjectNode upstream = mapper.createObjectNode().put("schemaVersion", version).put("receivedAt", receivedAt.toString());
    upstream.set("request", request.deepCopy()); upstream.set("rawResponse", response.deepCopy());
    ArrayNode nullFields = upstream.putArray("nullFields");
    ArrayNode omittedFields = legacy ? null : upstream.putArray("omittedFields");
    for (String group : CaseEvidenceSchema.COLUMNS.keySet()) {
      JsonNode input = response.path(ARRAYS.get(group));
      if (!input.isArray() || input.size() > CaseEvidenceService.MAX_ROWS)
        throw invalid(group + ": PO02 must return an explicit array of at most 500 rows; missing or null arrays are not empty results.");
      ObjectNode section = sections.putObject(group).put("note", "PO02 inquiry response. Cursor completion and truncation metadata were not supplied.");
      ArrayNode output = section.putArray("rows");
      List<String> wire = WIRE_COLUMNS.get(group), nativeColumns = CaseEvidenceSchema.COLUMNS.get(group);
      for (int index = 0; index < input.size(); index++) {
        if (!(input.get(index) instanceof ObjectNode row)) throw invalid(group + ": expected a PO02 evidence row object.");
        if (legacy) exact(row, new HashSet<>(wire), group + " row " + (index + 1));
        else supportedRow(row, group, index + 1);
        ObjectNode converted = output.addObject();
        for (int column = 0; column < wire.size(); column++) {
          JsonNode value = row.get(wire.get(column)); String name = nativeColumns.get(column);
          if (value == null) {
            // Missing DTO properties are unknown, independently of explicitly null source cells.
            converted.put(name, "");
            omittedFields.addObject().put("group", group).put("rowIndex", index + 1).put("field", name);
          } else if (value.isNull()) {
            converted.put(name, "");
            nullFields.addObject().put("group", group).put("rowIndex", index + 1).put("field", name);
          } else if (value.isTextual() && value.textValue().length() <= CaseEvidenceSchema.MAX_CELL_LENGTH
              && value.textValue().indexOf('\0') < 0) converted.put(name, value.textValue());
          else throw invalid(group + " row " + (index + 1) + "." + wire.get(column)
              + ": expected source text or null. Numeric values are not converted implicitly.");
        }
      }
    }
    return new Adapted(payload, upstream);
  }

  /** Validate the reversible normalization before any preserved upstream data is used. */
  static void verify(ObjectMapper mapper, ObjectNode snapshot) {
    if (!snapshot.has("upstream")) return;
    try {
      if (!(snapshot.get("upstream") instanceof ObjectNode upstream)
          || !snapshot.path("sourceKind").asText().equals("BANK_API")) throw new IllegalArgumentException();
      String version = upstream.path("schemaVersion").asText();
      if (!Set.of(LEGACY_VERSION, VERSION).contains(version)
          || !(upstream.get("request") instanceof ObjectNode request)
          || !(upstream.get("rawResponse") instanceof ObjectNode response)) throw new IllegalArgumentException();
      Set<String> fields = new HashSet<>(Set.of("schemaVersion", "receivedAt", "request", "rawResponse", "nullFields"));
      if (VERSION.equals(version)) fields.add("omittedFields");
      exact(upstream, fields, "saved PO02 provenance");
      Adapted expected = new FlexcubeEvidenceAdapter(mapper).adapt(response, request,
          snapshot.path("payload").path("sourceTimezone").asText(), Instant.parse(upstream.path("receivedAt").asText()), version);
      if (!expected.payload().equals(snapshot.path("payload")) || !expected.upstream().equals(upstream))
        throw new IllegalArgumentException();
    } catch (RuntimeException failure) {
      throw new ApiException(503, "EVIDENCE_STORAGE_UNAVAILABLE", "The saved PO02 source response or its normalization could not be verified.");
    }
  }

  /** Return native names with original explicit nulls and omitted properties preserved for cited evidence. */
  static ObjectNode sourceRow(ObjectNode snapshot, String group, int rowIndex, ObjectNode normalized) {
    ObjectNode original = normalized.deepCopy();
    for (JsonNode field : snapshot.path("upstream").path("nullFields"))
      if (group.equals(field.path("group").asText()) && rowIndex == field.path("rowIndex").asInt())
        original.putNull(field.path("field").asText());
    for (JsonNode field : snapshot.path("upstream").path("omittedFields"))
      if (group.equals(field.path("group").asText()) && rowIndex == field.path("rowIndex").asInt())
        original.remove(field.path("field").asText());
    return original;
  }

  private static void supportedRow(ObjectNode row, String group, int index) {
    Set<String> actual = new HashSet<>(); row.fieldNames().forEachRemaining(actual::add);
    if (!new HashSet<>(WIRE_COLUMNS.get(group)).containsAll(actual))
      throw invalid(group + " row " + index + ": unsupported PO02 fields; no evidence was saved.");
    for (String field : REQUIRED_COLUMNS.get(group))
      if (!row.path(field).isTextual() || row.path(field).textValue().isBlank())
        throw invalid(group + " row " + index + "." + field + ": required PO02 scope, identity and query metadata must be source text.");
  }

  private static void exact(ObjectNode node, Set<String> expected, String context) {
    Set<String> actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) throw invalid(context + ": missing or unsupported PO02 fields; no evidence was saved.");
  }
  static ApiException invalid(String message) { return new ApiException(502, "INVALID_CASE_EVIDENCE_RESPONSE", message); }
}
