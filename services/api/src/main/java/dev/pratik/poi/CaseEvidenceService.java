package dev.pratik.poi;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Case-scoped, immutable four-function evidence. No model or payment execution. */
@Service
public class CaseEvidenceService {
  static final int MAX_BYTES=5*1024*1024, MAX_ROWS=500, MAX_STORED_BYTES=10*1024*1024;
  static final String SCHEMA="fcr-case-evidence-v1";
  private final ObjectMapper mapper;
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final PaymentDiscoveryService cases;
  private final CaseEvidenceClient client;
  public CaseEvidenceService(ObjectMapper mapper,JdbcTemplate db,TransactionTemplate tx,
      PaymentDiscoveryService cases,CaseEvidenceClient client) {
    this.mapper=mapper;this.db=db;this.tx=tx;this.cases=cases;this.client=client;
  }
  ObjectNode config(Actor actor,String caseId) {
    ObjectNode item=cases.caseDetail(actor,caseId);
    ObjectNode result=mapper.createObjectNode().put("schemaVersion",SCHEMA);
    result.set("api",mapper.valueToTree(client.config()));
    result.putObject("limits").put("maxRowsPerSection",MAX_ROWS).put("maxFileBytes",MAX_BYTES);
    var groups=result.putArray("groups");
    CaseEvidenceSchema.COLUMNS.forEach((key,columns)->{
      ObjectNode group=groups.addObject().put("key",key).put("functionName",CaseEvidenceSchema.FUNCTIONS.get(key));
      group.set("columns",mapper.valueToTree(columns));
    });
    result.set("template",template(item));return result;
  }
  ObjectNode template(ObjectNode item) {
    ObjectNode result=mapper.createObjectNode().put("schemaVersion",SCHEMA).put("sourceTimezone","UNKNOWN");
    result.set("payment",identity(item));ObjectNode sections=result.putObject("sections");
    CaseEvidenceSchema.COLUMNS.keySet().forEach(key->sections.putObject(key).put("note","").putArray("rows"));
    return result;
  }
  ObjectNode list(Actor actor,String caseId) {
    cases.caseDetail(actor,caseId);
    ObjectNode result=mapper.createObjectNode();
    result.set("items",mapper.valueToTree(db.query(
        "SELECT summary FROM fcr_case_evidence WHERE tenant_id=? AND case_id=? ORDER BY version DESC",
        (rs,n)->stored(rs.getString(1)),actor.tenantId(),caseId)));
    return result;
  }
  ObjectNode detail(Actor actor,String caseId,String snapshotId) {
    cases.caseDetail(actor,caseId);
    List<ObjectNode> found=db.query("SELECT body FROM fcr_case_evidence WHERE tenant_id=? AND case_id=? AND id=?",
        (rs,n)->stored(rs.getString(1)),actor.tenantId(),caseId,snapshotId);
    if(found.isEmpty())throw ApiException.notFound();ObjectNode snapshot=found.get(0);verifyUpstream(mapper,snapshot);return snapshot;
  }
  byte[] excelTemplate(Actor actor,String caseId,String group) {
    cases.caseDetail(actor,caseId);return CaseEvidenceSchema.template(group);
  }
  ObjectNode submit(Actor actor,String caseId,byte[] bytes,String source,String key) {
    actor.requireWriter();cases.requireActive(actor,caseId);validateKey(key);
    if(!Set.of("MANUAL","JSON").contains(source))throw invalid("Unsupported evidence source.");
    return save(actor,caseId,parse(bytes),source,key,null);
  }
  ObjectNode excel(Actor actor,String caseId,Map<String,byte[]> files,String timezone,String key) {
    actor.requireWriter();ObjectNode item=cases.requireActive(actor,caseId);validateKey(key);
    if(!files.keySet().equals(CaseEvidenceSchema.COLUMNS.keySet()))throw invalid("Supply exactly PAYMENT, HOST, HISTORY and STATUS Excel files.");
    ObjectNode payload=template(item).put("sourceTimezone",timezone);
    for(String group:CaseEvidenceSchema.COLUMNS.keySet())
      ((ObjectNode)payload.path("sections").path(group)).set("rows",mapper.valueToTree(CaseEvidenceSchema.read(group,files.get(group))));
    if(bytes(payload).length>MAX_BYTES)throw tooLarge();
    return save(actor,caseId,payload,"EXCEL",key,null);
  }
  ObjectNode inquire(Actor actor,String caseId,byte[] request,String key) {
    actor.requireWriter();ObjectNode item=cases.requireActive(actor,caseId);validateKey(key);
    if(parse(request).size()!=0)throw invalid("Inquiry uses the saved case identity; no request fields are accepted.");
    String requestHash=hash("BANK_API|"+caseId);
    ObjectNode replay=replay(actor,caseId,key,requestHash);
    if(replay!=null)return replay;
    CaseEvidenceClient.Result received=client.fetchResult(item.path("reference").asText(),item.path("orgBank").asText(),item.path("orgBranch").asText());
    ObjectNode reply=received.response();
    try {
      if(received.upstream()!=null)
        return save(actor,caseId,reply,"BANK_API",key,null,received.upstream());
      fields(reply,Set.of("schemaVersion","payment","sourceTimezone","sections","acquisition"),"Inquiry response");
      JsonNode acquisition=reply.get("acquisition");
      if(acquisition==null || !acquisition.isObject())throw invalid("Missing section acquisition metadata.");
      ObjectNode payload=reply.deepCopy();payload.remove("acquisition");
      return save(actor,caseId,payload,"BANK_API",key,(ObjectNode)acquisition);
    } catch(ApiException failure) {
      if(failure.status==422)throw new ApiException(502,"INVALID_CASE_EVIDENCE_RESPONSE",
          "Inquiry response: "+failure.getMessage()+" No evidence was saved.");
      throw failure;
    }
  }
  private ObjectNode save(Actor actor,String caseId,ObjectNode payload,String source,String key,ObjectNode acquisition) {
    return save(actor,caseId,payload,source,key,acquisition,null);
  }
  private ObjectNode save(Actor actor,String caseId,ObjectNode payload,String source,String key,ObjectNode acquisition,ObjectNode upstream) {
    ObjectNode item=cases.caseDetail(actor,caseId);
    var warnings=new ArrayList<String>();
    ObjectNode normalized=validate(payload,item,warnings);
    ObjectNode coverage=coverage(normalized,acquisition,identity(item));
    if(!source.equals("BANK_API"))warnings.add("Source query completion is unverified for uploaded or manually entered evidence, including empty sections.");
    if(upstream!=null) {
      warnings.add("PO02 returned an inquiry response without per-group fetch-completion or truncation metadata. Source coverage remains UNVERIFIED; local receipt time does not replace source observation times.");
      if(!upstream.path("nullFields").isEmpty())warnings.add("PO02 source nulls are preserved in the original response and null-field provenance. The editable text projection uses empty text; cited source rows retain null values.");
      if(!upstream.path("omittedFields").isEmpty())warnings.add("PO02 omitted some optional source fields. Their absence is preserved in the original response and omitted-field provenance. Blank form cells do not establish source values; cited source rows retain the omissions.");
      warnings.add("PO02 transactionStatus describes inquiry-service processing. A NEFTCODSTATUS label, including SUCCESS, does not by itself confirm payment acceptance, beneficiary credit or settlement.");
    }
    warnings.add("These four result groups do not establish an atomic database snapshot or a final payment outcome.");
    if(normalized.path("sourceTimezone").asText().equals("UNKNOWN"))warnings.add("Source timezone is unspecified; source-local dates have not been converted.");
    String requestHash=source.equals("BANK_API")?hash("BANK_API|"+caseId):hash(source+"|"+canonical(normalized));
    return tx.execute(status->{
      List<String> locked=db.query("SELECT id FROM fcr_payment_case WHERE tenant_id=? AND id=? FOR UPDATE",
          (rs,n)->rs.getString(1),actor.tenantId(),caseId);
      if(locked.isEmpty())throw ApiException.notFound();
      cases.requireActive(actor,caseId);
      ObjectNode replay=replay(actor,caseId,key,requestHash);if(replay!=null)return replay;
      ObjectNode latestCase=cases.caseRecord(actor,caseId);
      Integer max=db.queryForObject("SELECT COALESCE(MAX(version),0) FROM fcr_case_evidence WHERE tenant_id=? AND case_id=?",Integer.class,actor.tenantId(),caseId);
      int version=max+1;String now=Instant.now().toString(),id="EVD-"+UUID.randomUUID();
      ObjectNode snapshot=mapper.createObjectNode().put("id",id).put("caseId",caseId).put("version",version)
          .put("sourceKind",source).put("dataClassification","PRIVATE_EVIDENCE").put("createdAt",now).put("createdBy",actor.id());
      snapshot.set("payload",normalized);snapshot.set("coverage",coverage);snapshot.set("warnings",mapper.valueToTree(warnings));
      if(upstream!=null)snapshot.set("upstream",upstream.deepCopy());
      snapshot.put("evidenceHash",fingerprint(snapshot));
      verifyUpstream(mapper,snapshot);
      if(bytes(snapshot).length>MAX_STORED_BYTES)throw new ApiException(413,"CASE_EVIDENCE_TOO_LARGE","The preserved source response and normalized evidence together exceed 10 MiB. Narrow the source inquiry; no evidence was saved.");
      ObjectNode summary=snapshot.deepCopy();summary.remove(List.of("payload","upstream"));
      db.update("INSERT INTO fcr_case_evidence(id,tenant_id,case_id,version,created_at,summary,body) VALUES(?,?,?,?,?,?,?)",
          id,actor.tenantId(),caseId,version,now,summary.toString(),snapshot.toString());
      db.update("INSERT INTO fcr_case_evidence_command(tenant_id,actor_id,case_id,idempotency_key,request_hash,snapshot_id) VALUES(?,?,?,?,?,?)",
          actor.tenantId(),actor.id(),caseId,key,requestHash,id);
      boolean any=CaseEvidenceSchema.COLUMNS.keySet().stream().anyMatch(group->normalized.path("sections").path(group).path("rows").size()>0);
      latestCase.put("evidenceStatus",any?"EVIDENCE_ATTACHED":"EMPTY_EVIDENCE_ATTACHED").put("updatedAt",now);
      db.update("UPDATE fcr_payment_case SET body=? WHERE tenant_id=? AND id=?",latestCase.toString(),actor.tenantId(),caseId);
      return snapshot;
    });
  }
  private ObjectNode validate(ObjectNode input,ObjectNode item,List<String> warnings) {
    if(input.has("payload"))throw invalid("The JSON contains a saved snapshot envelope. Supply its payload object using this case's JSON template; do not change its payment identity to fit another case.");
    fields(input,Set.of("schemaVersion","payment","sourceTimezone","sections"),"Evidence JSON");
    if(!SCHEMA.equals(text(input,"schemaVersion",100)))throw invalid("schemaVersion: use fcr-case-evidence-v1 from the case JSON template.");
    if(!(input.get("payment") instanceof ObjectNode paymentIdentity))throw invalid("payment: supply the reference, orgBank and orgBranch object from this case's template.");
    fields(paymentIdentity,Set.of("reference","orgBank","orgBranch"),"payment");
    ObjectNode expectedIdentity=identity(item);
    for(String key:List.of("reference","orgBank","orgBranch")) {
      String supplied=text(paymentIdentity,key,key.equals("reference")?200:10,"payment."+key);
      if(!supplied.equals(expectedIdentity.path(key).asText()))throw invalid("payment."+key+": does not match the selected saved case. Open the matching payment case or use evidence exported for this case; do not rewrite the identifier.");
    }
    String timezone=text(input,"sourceTimezone",100);
    try {if(!timezone.equals("UNKNOWN"))ZoneId.of(timezone);}catch(DateTimeException failure){throw invalid("Use UNKNOWN or a valid source timezone such as Asia/Kolkata.");}
    if(!(input.get("sections") instanceof ObjectNode sections))throw invalid("Four evidence sections are required.");
    fields(sections,CaseEvidenceSchema.COLUMNS.keySet(),"sections");
    ObjectNode output=template(item).put("sourceTimezone",timezone);
    for(String group:CaseEvidenceSchema.COLUMNS.keySet()) {
      if(!(sections.get(group) instanceof ObjectNode section))throw invalid(group+": expected a rows/note section.");
      fields(section,Set.of("rows","note"),group);String note=text(section,"note",4000,group+".note");
      if(!(section.get("rows") instanceof ArrayNode rows))throw invalid(group+".rows: use a JSON array, or [] when no rows were supplied.");
      if(rows.size()>MAX_ROWS)throw invalid(group+".rows: at most 500 source rows are supported.");
      ObjectNode target=(ObjectNode)output.path("sections").path(group);target.put("note",note);ArrayNode copied=target.putArray("rows");
      Set<String> headers=new LinkedHashSet<>(CaseEvidenceSchema.COLUMNS.get(group));
      for(int i=0;i<rows.size();i++) {
        if(!(rows.get(i) instanceof ObjectNode row))throw invalid(group+" row "+(i+1)+": expected named columns.");
        String where=group+" row "+(i+1);
        fields(row,headers,where);ObjectNode normalized=copied.addObject();
        for(String column:headers) normalized.put(column,text(row,column,4000,where+"."+column));
        validateRow(group,normalized,item,i+1);
        String count=normalized.path("SCOPE_ROW_COUNT").asText();
        if(!count.isEmpty() && (!count.matches("[0-9]{1,10}") || new BigDecimal(count).compareTo(BigDecimal.valueOf(rows.size()))!=0))
          throw invalid(where+".SCOPE_ROW_COUNT: must match the supplied source rows when populated. Re-export the full scoped result; do not change its count merely to bypass validation.");
      }
      if(rows.isEmpty())warnings.add(group+": no rows supplied; this alone does not prove the source query completed.");
    }
    ArrayNode payment=(ArrayNode)output.path("sections").path("PAYMENT").path("rows");
    if(payment.size()>1)warnings.add("PAYMENT has multiple matching rows; no row has been selected as the authoritative current state.");
    for(JsonNode row:payment) {
      String amount=row.path("NUMAMOUNT_4038").asText();
      if(!amount.isEmpty() && new BigDecimal(amount).compareTo(new BigDecimal(item.path("amount").asText()))!=0)
        addOnce(warnings,"PAYMENT amount differs from the saved discovery amount; both observations are preserved.");
      if(!Objects.equals(row.path("UTR_REF_NO").asText(),item.path("utr").asText("")))
        addOnce(warnings,"PAYMENT UTR differs from the saved discovery UTR; verify the source observations.");
    }
    int statusRow=0;
    for(JsonNode row:output.path("sections").path("STATUS").path("rows")) {
      statusRow++;
      boolean matched=false;
      for(JsonNode p:payment)if(List.of("CODSTATUS","MSGSTATUS","ACCTSTATUS").stream().allMatch(k->p.path(k).equals(row.path(k))))matched=true;
      if(!matched)throw invalid("STATUS row "+statusRow+": CODSTATUS/MSGSTATUS/ACCTSTATUS must match one supplied PAYMENT tuple. Verify that both exports belong to the same scoped inquiry.");
    }
    if(bytes(output).length>MAX_BYTES)throw tooLarge();return output;
  }
  private void validateRow(String group,ObjectNode row,ObjectNode item,int index) {
    String where=group+" row "+index+": ";
    if(group.equals("PAYMENT") && !row.path("REFTXNNUMBER").asText().equals(item.path("reference").asText()))throw invalid(where+"REFTXNNUMBER differs from this case; use the matching payment's source export.");
    if(Set.of("HOST","HISTORY").contains(group)) {
      for(var field:Map.of("REF_TXN_NO","reference","COD_ORG_BRN","orgBranch","COD_ORG_BANK","orgBank").entrySet())
        if(!row.path(field.getKey()).asText().equals(item.path(field.getValue()).asText()))
          throw invalid(where+field.getKey()+" differs from this case; use the matching scoped source export.");
    }
    String source=row.path("SOURCE_TABLE").asText();
    if(!source.isEmpty() && !source.equals(CaseEvidenceSchema.SOURCE_TABLES.get(group)))throw invalid(where+"unexpected SOURCE_TABLE.");
    String amount=row.path(group.equals("PAYMENT")?"NUMAMOUNT_4038":"AMT_TXN_TCY").asText("");
    if(!amount.isEmpty())try {if(amount.length()>100 || !amount.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]{1,3})?"))throw new NumberFormatException();new BigDecimal(amount);}catch(NumberFormatException failure){throw invalid(where+"amount must be exact decimal text without separators.");}
  }
  private ObjectNode coverage(ObjectNode payload,ObjectNode acquisition,ObjectNode identity) {
    ObjectNode coverage=mapper.createObjectNode();
    if(acquisition!=null)fields(acquisition,CaseEvidenceSchema.COLUMNS.keySet(),"acquisition");
    for(String group:CaseEvidenceSchema.COLUMNS.keySet()) {
      int count=payload.path("sections").path(group).path("rows").size();
      ObjectNode entry=coverage.putObject(group).put("rowCount",count).put("completion",acquisition==null?"UNVERIFIED":"COMPLETE");
      if(acquisition==null)continue;
      String context="acquisition."+group;
      if(!(acquisition.get(group) instanceof ObjectNode meta))throw invalid(context+": expected API acquisition metadata.");
      fields(meta,Set.of("reference","orgBank","orgBranch","returnCode","fetchCompleted","observedAt","rowCount","hasMore"),context);
      for(String key:List.of("reference","orgBank","orgBranch"))if(!identity.get(key).equals(meta.get(key)))throw invalid(context+"."+key+": does not match the saved case.");
      if(!"0".equals(text(meta,"returnCode",20)) || !meta.path("fetchCompleted").isBoolean() || !meta.path("fetchCompleted").asBoolean()
          || !meta.path("hasMore").isBoolean() || meta.path("hasMore").asBoolean() || !meta.path("rowCount").isIntegralNumber()
          || !meta.path("rowCount").canConvertToInt() || meta.path("rowCount").intValue()!=count)throw invalid(context+": API must report successful, fully fetched, untruncated results with an exact rowCount.");
      String observed=text(meta,"observedAt",100,context+".observedAt");try{OffsetDateTime.parse(observed);}catch(DateTimeException failure){throw invalid(context+".observedAt: supply an ISO observation time with a timezone offset.");}
      entry.set("acquisition",meta.deepCopy());
    }return coverage;
  }
  private ObjectNode replay(Actor actor,String caseId,String key,String requestHash) {
    List<Map<String,Object>> rows=db.queryForList("SELECT request_hash,snapshot_id FROM fcr_case_evidence_command WHERE tenant_id=? AND actor_id=? AND case_id=? AND idempotency_key=?",
        actor.tenantId(),actor.id(),caseId,key);
    if(rows.isEmpty())return null;
    if(!requestHash.equals(rows.get(0).get("request_hash")))throw new ApiException(409,"EVIDENCE_IDEMPOTENCY_CONFLICT","This retry key belongs to different evidence. Use a new save attempt for changed data.");
    return detail(actor,caseId,(String)rows.get(0).get("snapshot_id"));
  }
  private ObjectNode identity(ObjectNode item){return mapper.createObjectNode().put("reference",item.path("reference").asText()).put("orgBank",item.path("orgBank").asText()).put("orgBranch",item.path("orgBranch").asText());}
  static String fingerprint(ObjectNode snapshot) {
    ObjectNode digest=snapshot.objectNode();
    for(String field:List.of("sourceKind","payload","coverage"))digest.set(field,snapshot.path(field));
    if(snapshot.has("upstream"))digest.set("upstream",snapshot.get("upstream"));
    return UatService.canonicalHash(digest);
  }
  static void verifyUpstream(ObjectMapper mapper,ObjectNode snapshot) {
    if(!snapshot.has("upstream"))return;
    if(!fingerprint(snapshot).equals(snapshot.path("evidenceHash").asText()))
      throw new ApiException(503,"EVIDENCE_STORAGE_UNAVAILABLE","The saved PO02 evidence fingerprint could not be verified.");
    FlexcubeEvidenceAdapter.verify(mapper,snapshot);
  }
  private ObjectNode parse(byte[] data){if(data.length>MAX_BYTES)throw tooLarge();return UatService.parseObject(mapper,data,invalid("Supply a valid JSON object without duplicate keys or trailing content."));}
  private ObjectNode stored(String value){try{return (ObjectNode)mapper.readTree(value);}catch(Exception e){throw new ApiException(503,"EVIDENCE_STORAGE_UNAVAILABLE","Saved evidence could not be read.");}}
  private byte[] bytes(JsonNode value){return value.toString().getBytes(StandardCharsets.UTF_8);}
  private static void fields(ObjectNode value,Set<String> expected,String context){
    Set<String> actual=new HashSet<>();value.fieldNames().forEachRemaining(actual::add);
    TreeSet<String> missing=new TreeSet<>(expected);missing.removeAll(actual);
    if(!missing.isEmpty())throw invalid(context+": missing template field(s): "+String.join(", ",missing)+". Preserve all standard headers; use empty text for a blank source cell.");
    if(!expected.containsAll(actual))throw invalid(context+": unsupported field(s). Use only fields from the downloaded case template.");
  }
  private static String text(ObjectNode value,String key,int max){return text(value,key,max,key);}
  private static String text(ObjectNode value,String key,int max,String path){JsonNode field=value.get(key);if(field==null || !field.isTextual())throw invalid(path+": use quoted JSON text, including numeric source values; no automatic numeric conversion is performed.");if(field.textValue().length()>max || field.textValue().indexOf('\0')>=0)throw invalid(path+": use text of at most "+max+" characters without a NUL character.");return field.textValue();}
  private static void validateKey(String key){if(key==null || !key.matches("[A-Za-z0-9._:-]{8,200}"))throw new ApiException(400,"EVIDENCE_IDEMPOTENCY_REQUIRED","An Idempotency-Key of 8–200 safe characters is required.");}
  private static void addOnce(List<String> list,String value){if(!list.contains(value))list.add(value);}
  private String canonical(JsonNode value){if(value.isObject()){TreeMap<String,JsonNode> sorted=new TreeMap<>();value.fields().forEachRemaining(e->sorted.put(e.getKey(),e.getValue()));ObjectNode node=mapper.createObjectNode();sorted.forEach((k,v)->node.set(k,canonicalNode(v)));return node.toString();}return canonicalNode(value).toString();}
  private JsonNode canonicalNode(JsonNode value){if(value.isObject()){ObjectNode node=mapper.createObjectNode();TreeSet<String> keys=new TreeSet<>();value.fieldNames().forEachRemaining(keys::add);keys.forEach(k->node.set(k,canonicalNode(value.get(k))));return node;}if(value.isArray()){ArrayNode node=mapper.createArrayNode();value.forEach(v->node.add(canonicalNode(v)));return node;}return value;}
  private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
  static ApiException invalid(String message){return new ApiException(422,"INVALID_CASE_EVIDENCE",message);}
  static ApiException tooLarge(){return new ApiException(413,"CASE_EVIDENCE_TOO_LARGE","Evidence JSON must be no larger than 5 MiB.");}
}
