package dev.pratik.poi;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;

/** PO01 DTO to the stable, exact-text discovery contract. No inferred payment outcome. */
final class FlexcubeDiscoveryAdapter {
  static final Map<String,String> FIELDS;
  static {
    var fields=new LinkedHashMap<String,String>();
    fields.put("referenceTransactionNumber","PIO_REF_TXN_NO");
    fields.put("originatingBranchCode","PIO_ORG_BRN");
    fields.put("originatingBankCode","PIO_ORG_BANK");
    fields.put("referenceSubsequenceNumber","REF_SUBSEQ_NO");
    fields.put("utrReferenceNumber","UTR_REF_NO");
    fields.put("initiationDateTime","DATINITIATION");
    fields.put("transactionAmount","NUMAMOUNT_4038");
    FIELDS=Collections.unmodifiableMap(fields);
  }
  static PaymentDiscoveryClient.Result parse(ObjectMapper mapper,ObjectNode response,ObjectNode request) {
    JsonNode records=response.path("neftPaymentDiscoveryDetails");
    if (!records.isArray() || records.size()>2000 || !response.path("hasMoreRecords").isBoolean()
        || !response.path("observedAt").isTextual()) throw invalid();
    String observed=response.path("observedAt").textValue();
    try { if (observed.length()>100) throw invalid(); OffsetDateTime.parse(observed); }
    catch (java.time.format.DateTimeParseException error) { throw invalid(); }
    List<Map<String,String>> rows=new ArrayList<>();
    ArrayNode nulls=mapper.createArrayNode(); int index=0;
    for (JsonNode record:records) {
      index++;
      if (!record.isObject() || record.size()!=FIELDS.size()) throw invalid();
      var row=new LinkedHashMap<String,String>();
      for (var field:FIELDS.entrySet()) {
        JsonNode cell=record.get(field.getKey());
        boolean nullable=Set.of("utrReferenceNumber","referenceSubsequenceNumber").contains(field.getKey());
        if (cell==null || !(cell.isTextual() || cell.isNull() && nullable)) throw invalid();
        // Keep an unknown host subsequence distinct from the valid native value "0".
        String value=cell.isNull()?(field.getKey().equals("referenceSubsequenceNumber")?null:""):cell.textValue();
        if (value!=null && value.length()>200) throw invalid();
        row.put(field.getValue(),value);
        if (cell.isNull()) nulls.addObject().put("rowIndex",index).put("field",field.getValue());
      }
      rows.add(row);
    }
    ObjectNode upstream=mapper.createObjectNode().put("schemaVersion","flexcube-neft-discovery-v1").put("receivedAt",Instant.now().toString());
    upstream.set("request",request.deepCopy()); upstream.set("rawResponse",response.deepCopy()); upstream.set("nullFields",nulls);
    return new PaymentDiscoveryClient.Result(rows,response.path("hasMoreRecords").booleanValue(),observed,upstream);
  }
  static ApiException invalid() { return new ApiException(502,"INVALID_DISCOVERY_RESPONSE","FLEXCUBE PO01 response does not match the discovery contract; no records were imported."); }
}
