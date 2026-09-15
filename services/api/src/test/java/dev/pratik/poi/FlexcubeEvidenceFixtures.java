package dev.pratik.poi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Original synthetic PO02 examples; no bank records or upstream implementation source. */
final class FlexcubeEvidenceFixtures {
  static ObjectNode request(ObjectMapper mapper, String reference, String bank, String branch) {
    ObjectNode request = mapper.createObjectNode();
    request.putObject("args0").put("bankCode", Integer.parseInt(bank)).put("transactionBranch", branch)
        .put("externalReferenceNo", "PO02-ORIGINAL-TEST").put("postingDateText", "20260914")
        .put("channel", "API").put("serviceCode", "PO02").put("userId", "TESTUSER");
    request.putObject("args1").put("referenceTransactionNumber", reference)
        .put("originatingBankCode", Integer.parseInt(bank)).put("originatingBranchCode", Integer.parseInt(branch));
    return request;
  }

  static ObjectNode response(ObjectMapper mapper, ObjectNode request) {
    ObjectNode response = mapper.createObjectNode().put("postingDate", "20260914");
    ObjectNode status = response.putObject("transactionStatus").put("FCYHangeHandlingApplied", false)
        .put("errorCode", "0").put("externalReferenceNo", request.path("args0").path("externalReferenceNo").asText())
        .put("isOverriden", false).put("isServiceChargeApplied", false).put("replyCode", 0).put("spReturnValue", 0);
    status.putObject("extendedReply");
    for (String group : CaseEvidenceSchema.COLUMNS.keySet()) {
      ObjectNode row = response.putArray(FlexcubeEvidenceAdapter.ARRAYS.get(group)).addObject();
      FlexcubeEvidenceAdapter.WIRE_COLUMNS.get(group).forEach(key -> row.put(key, ""));
      row.put("sourceTable", CaseEvidenceSchema.SOURCE_TABLES.get(group)).put("scopeRowCount", "1")
          .put("queryObservedAt", "2026-09-14T10:20:30+05:30");
      if (group.equals("PAYMENT")) row.put("refTxnNumber", request.path("args1").path("referenceTransactionNumber").asText())
          .put("numAmount4038", "123.007").put("codCurr", "INR").put("codStatus", "2").put("acctStatus", "3")
          .put("msgStatus", "11").put("datInitiation", "2026-09-14T09:00:00").put("idSequenceNo", "00000012345678901234567890")
          .putNull("idRelatedRef2006");
      else if (group.equals("STATUS")) row.put("codStatus", "2").put("acctStatus", "3").put("msgStatus", "11")
          .put("neftCodStatus", "SUCCESS");
      else {
        row.put("refTxnNo", request.path("args1").path("referenceTransactionNumber").asText())
            .put("codOrgBank", request.path("args1").path("originatingBankCode").asText())
            .put("codOrgBrn", request.path("args1").path("originatingBranchCode").asText())
            .put("datTxn", "2026-09-14T09:00:00").put("txnStat", "2").put("acctStat", "3").put("msgStat", "11");
        if(group.equals("HOST"))row.put("amtTxnTcy", "123.007").put("refSubseqNo", "0").putNull("codExt");
      }
    }
    return response;
  }

  /** Original synthetic transitions with nullable DTO properties omitted by serialization. */
  static ObjectNode sparseResponse(ObjectMapper mapper, ObjectNode request) {
    ObjectNode response = response(mapper, request);
    ((ObjectNode) response.path("neftPaymentEvidenceDetails").get(0)).remove(java.util.List.of(
        "idRelatedRef2006", "idMsgReference2020", "idSequenceNo", "batchTime3535", "reasonCode6346",
        "n10MsgId", "n10IdSequenceNo", "n10TxnId", "n10DateTime", "n10RecvSentDateTime"));
    ((ObjectNode) response.path("neftHostEvidenceDetails").get(0)).remove(java.util.List.of(
        "datDispatch", "datAuthtime", "datSecondAuthtime", "codExt", "codReject", "sfmsRejCode", "refTxnNoRev"));
    ObjectNode history = (ObjectNode) response.path("neftHistoryEvidenceDetails").get(0);
    history.put("scopeRowCount", "3");
    ((com.fasterxml.jackson.databind.node.ArrayNode) response.path("neftHistoryEvidenceDetails"))
        .add(history.deepCopy().put("datTxn", "2026-09-14T09:00:03"))
        .add(history.deepCopy().put("datTxn", "2026-09-14T09:00:06"));
    history.remove(java.util.List.of("acctStat", "msgStat", "txnStat", "ntwkAcctStat", "contgAcctStat"));
    response.putArray("neftStatusEvidenceDetails");
    return response;
  }
  private FlexcubeEvidenceFixtures() {}
}
