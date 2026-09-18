package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Optional display provenance. Discovery values and saved evidence remain authoritative and unchanged. */
final class EvidenceCurrencyProjection {
  private static final Set<String> KEYS=Set.of("currency","evidenceId","version","sourceKind");
  private static final Set<String> SOURCES=Set.of("BANK_API","EXCEL","JSON","MANUAL");
  private EvidenceCurrencyProjection() { }

  static boolean missingCurrency(JsonNode item) { return item.path("currency").asText("").isBlank(); }

  static ObjectNode derive(ObjectMapper mapper,ObjectNode item,ObjectNode summary,String raw) {
    if(!missingCurrency(item) || summary==null || raw==null || raw.length()>CaseEvidenceService.MAX_STORED_BYTES)return null;
    try {
      byte[] bytes=raw.getBytes(StandardCharsets.UTF_8);
      if(bytes.length>CaseEvidenceService.MAX_STORED_BYTES)return null;
      ObjectNode snapshot=UatService.parseObject(mapper,bytes,invalid());
      ObjectNode preservedSummary=snapshot.deepCopy();preservedSummary.remove(List.of("payload","upstream"));
      if(!preservedSummary.equals(summary) || !item.path("id").equals(snapshot.path("caseId"))
          || !snapshot.path("id").isTextual() || snapshot.path("id").asText().isBlank()
          || !snapshot.path("version").isIntegralNumber() || !snapshot.path("version").canConvertToInt() || snapshot.path("version").asInt()<1
          || !SOURCES.contains(snapshot.path("sourceKind").asText())
          || !CaseEvidenceService.fingerprint(snapshot).equals(snapshot.path("evidenceHash").asText()))return null;
      CaseEvidenceService.verifyUpstream(mapper,snapshot);
      JsonNode payload=snapshot.path("payload"),identity=payload.path("payment");
      if(!CaseEvidenceService.SCHEMA.equals(payload.path("schemaVersion").asText()))return null;
      for(String field:List.of("reference","orgBank","orgBranch"))
        if(!item.path(field).isTextual() || !item.path(field).equals(identity.path(field)))return null;
      JsonNode rows=payload.path("sections").path("PAYMENT").path("rows");
      if(!rows.isArray() || rows.isEmpty() || rows.size()>CaseEvidenceService.MAX_ROWS
          || summary.path("coverage").path("PAYMENT").path("rowCount").asInt(-1)!=rows.size())return null;
      BigDecimal amount=decimal(item.path("amount"));String currency=null;
      for(JsonNode row:rows) {
        JsonNode supplied=row.path("CODCURR");
        String table=row.path("SOURCE_TABLE").asText("");
        if(!row.isObject() || !item.path("reference").equals(row.path("REFTXNNUMBER"))
            || (!table.isEmpty() && !table.equals(CaseEvidenceSchema.SOURCE_TABLES.get("PAYMENT")))
            || !supplied.isTextual() || !supplied.textValue().matches("[A-Z]{3}")
            || decimal(row.path("NUMAMOUNT_4038")).compareTo(amount)!=0)return null;
        if(currency!=null && !currency.equals(supplied.textValue()))return null;
        currency=supplied.textValue();
      }
      return mapper.createObjectNode().put("currency",currency).put("evidenceId",snapshot.path("id").asText())
          .put("version",snapshot.path("version").asInt()).put("sourceKind",snapshot.path("sourceKind").asText());
    } catch(RuntimeException invalid) { return null; }
  }

  static void attach(ObjectMapper mapper,ObjectNode item,String raw,String evidenceId,int version,String sourceKind) {
    item.remove("evidenceCurrency");
    if(!missingCurrency(item) || raw==null || raw.length()>1024)return;
    try {
      ObjectNode hint=UatService.parseObject(mapper,raw.getBytes(StandardCharsets.UTF_8),invalid());
      Set<String> keys=new HashSet<>();hint.fieldNames().forEachRemaining(keys::add);
      if(!keys.equals(KEYS) || !hint.path("currency").isTextual() || !hint.path("currency").asText().matches("[A-Z]{3}")
          || !hint.path("evidenceId").isTextual() || evidenceId==null || !evidenceId.equals(hint.path("evidenceId").asText())
          || !hint.path("version").isIntegralNumber() || !hint.path("version").canConvertToInt()
          || version<1 || version!=hint.path("version").intValue()
          || !SOURCES.contains(Objects.toString(sourceKind,"")) || !sourceKind.equals(hint.path("sourceKind").asText()))return;
      item.set("evidenceCurrency",hint);
    } catch(RuntimeException invalid) { /* A damaged optional projection must never invent a currency. */ }
  }

  private static BigDecimal decimal(JsonNode value) {
    if(!value.isTextual() || value.textValue().length()>100
        || !value.textValue().matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]{1,3})?"))throw invalid();
    return new BigDecimal(value.textValue());
  }
  private static ApiException invalid() { return new ApiException(503,"EVIDENCE_CURRENCY_UNAVAILABLE","Evidence currency could not be verified."); }
}
