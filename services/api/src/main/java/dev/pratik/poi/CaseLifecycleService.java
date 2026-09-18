package dev.pratik.poi;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit human lifecycle commands. Tombstones reserve numbers without retaining case payloads. */
@Service
public class CaseLifecycleService {
  static final int MAX_BYTES=8192;
  private final ObjectMapper mapper;
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final PaymentDiscoveryService cases;
  private final CaseNumberService numbers;
  public CaseLifecycleService(ObjectMapper mapper,JdbcTemplate db,TransactionTemplate tx,PaymentDiscoveryService cases,CaseNumberService numbers) {
    this.mapper=mapper;this.db=db;this.tx=tx;this.cases=cases;this.numbers=numbers;
  }
  public ObjectNode detail(Actor actor,String idOrNumber) {
    String id=numbers.resolve(actor.tenantId(),idOrNumber);
    return tx.execute(status->{lock(actor,id);cases.caseRecord(actor,id);return view(actor,id);});
  }
  public ObjectNode command(Actor actor,String idOrNumber,String action,byte[] bytes,String key) {
    if(action.equals("DELETED"))actor.requireAdministrator();else actor.requireLifecycleManager();
    if(key==null || !key.matches("[A-Za-z0-9._:-]{8,200}"))throw new ApiException(400,"CASE_LIFECYCLE_KEY_REQUIRED","Use an Idempotency-Key of 8–200 safe characters.");
    if(bytes.length>MAX_BYTES)throw new ApiException(413,"CASE_LIFECYCLE_TOO_LARGE","Lifecycle requests are limited to 8 KiB.");
    ObjectNode input=UatService.parseObject(mapper,bytes,invalid("Supply a lifecycle command object."));
    Set<String> required=action.equals("DELETED")?Set.of("expectedVersion","reason","confirmation"):Set.of("expectedVersion","reason");
    Set<String> keys=new HashSet<>();input.fieldNames().forEachRemaining(keys::add);
    if(!keys.equals(required))throw invalid("Supply expectedVersion and reason; permanent deletion also requires the exact case number in confirmation.");
    JsonNode version=input.get("expectedVersion");
    if(!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue()<0 || version.longValue()>=JsonSupport.MAX_SAFE_INTEGER)throw invalid("expectedVersion must be a nonnegative safe integer.");
    JsonNode reasonNode=input.get("reason");
    if(!reasonNode.isTextual() || reasonNode.textValue().isBlank() || reasonNode.textValue().length()>2000 || reasonNode.textValue().chars().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))throw invalid("Enter a reason of 1–2,000 characters.");
    String reason=reasonNode.textValue().strip(),id=numbers.resolve(actor.tenantId(),idOrNumber);
    String hash=UatService.canonicalHash(mapper.createObjectNode().put("action",action).set("input",input));
    return tx.execute(status->{
      ObjectNode original=lock(actor,id);
      String number=numbers.find(actor.tenantId(),id);
      var previous=db.queryForList("SELECT request_hash,body FROM fcr_case_lifecycle_command WHERE tenant_id=? AND case_id=? AND actor_id=? AND idempotency_key=?",actor.tenantId(),id,actor.id(),key);
      if(!previous.isEmpty()) {
        if(!hash.equals(previous.get(0).get("request_hash")))throw new ApiException(409,"CASE_LIFECYCLE_KEY_CONFLICT","This retry key belongs to a different lifecycle command.");
        // Never expose a stale archived/active receipt after permanent removal.
        if(CaseLifecycleState.load(db,actor.tenantId(),id).state().equals("DELETED") && !action.equals("DELETED"))throw ApiException.notFound();
        return action.equals("DELETED")?stored((String)previous.get(0).get("body")):view(actor,id);
      }
      var state=CaseLifecycleState.load(db,actor.tenantId(),id);
      if(state.state().equals("DELETED"))throw ApiException.notFound();
      if(state.version()!=version.longValue())throw new ApiException(409,"CASE_LIFECYCLE_VERSION_CONFLICT","Case lifecycle changed. Refresh and review its current state before trying again.");
      String requiredState=action.equals("ARCHIVED")?"ACTIVE":"ARCHIVED";
      if(!state.state().equals(requiredState))throw new ApiException(409,"CASE_LIFECYCLE_STATE_CONFLICT",action.equals("DELETED")?"Archive the case before permanently deleting it.":"The case is already in the requested state. Refresh before continuing.");
      int active=activeJobs(actor,id);
      if(active>0)throw new ApiException(409,"CASE_INVESTIGATION_ACTIVE","Wait for queued or running investigations to finish before changing the case lifecycle.");
      if(action.equals("DELETED") && (number==null || !input.path("confirmation").isTextual() || !number.equals(input.path("confirmation").textValue())))throw invalid("Type the exact case number to confirm permanent deletion.");
      long next=state.version()+1;String now=Instant.now().toString();
      if(state.version()==0)db.update("INSERT INTO fcr_case_lifecycle(tenant_id,case_id,state,version,updated_at) VALUES(?,?,?,?,?)",actor.tenantId(),id,action,next,now);
      else db.update("UPDATE fcr_case_lifecycle SET state=?,version=?,updated_at=? WHERE tenant_id=? AND case_id=?",action,next,now,actor.tenantId(),id);
      ObjectNode event=mapper.createObjectNode().put("id","LCE-"+UUID.randomUUID()).put("caseId",id).put("caseNumber",number).put("version",next)
          .put("action",action.equals("ACTIVE")?"RESTORED":action.equals("DELETED")?"PERMANENTLY_DELETED":"ARCHIVED")
          .put("reason",reason).put("occurredAt",now).put("actor",actor.id()).put("actorName",actor.name());
      db.update("INSERT INTO fcr_case_lifecycle_event(id,tenant_id,case_id,version,occurred_at,body) VALUES(?,?,?,?,?,?)",event.path("id").asText(),actor.tenantId(),id,next,now,event.toString());
      if(action.equals("DELETED"))purge(actor,id,original);
      else CaseHistoryIndex.recordLifecycle(mapper,db,actor.tenantId(),event);
      cases.refreshSearch(actor.tenantId(),id);
      ObjectNode result=action.equals("DELETED")?mapper.createObjectNode().put("caseId",id).put("caseNumber",number).put("state","DELETED").put("version",next):view(actor,id);
      db.update("INSERT INTO fcr_case_lifecycle_command(tenant_id,case_id,actor_id,idempotency_key,request_hash,body) VALUES(?,?,?,?,?,?)",actor.tenantId(),id,actor.id(),key,hash,result.toString());
      return result;
    });
  }
  private ObjectNode lock(Actor actor,String id) {
    if(db.query("SELECT id FROM fcr_payment_case WHERE tenant_id=? AND id=? FOR UPDATE",(rs,n)->rs.getString(1),actor.tenantId(),id).isEmpty())throw ApiException.notFound();
    return cases.lifecycleRecord(actor,id);
  }
  private int activeJobs(Actor actor,String id) {return db.queryForObject("SELECT COUNT(*) FROM fcr_case_investigation WHERE tenant_id=? AND case_id=? AND status IN ('QUEUED','RUNNING')",Integer.class,actor.tenantId(),id);}
  private ObjectNode view(Actor actor,String id) {
    var state=CaseLifecycleState.load(db,actor.tenantId(),id);int active=activeJobs(actor,id);
    boolean manager=Set.of("ANALYST","REVIEWER","ADMIN").contains(actor.role());
    ObjectNode result=mapper.createObjectNode().put("caseId",id).put("caseNumber",numbers.find(actor.tenantId(),id)).put("state",state.state()).put("version",state.version())
        .put("canArchive",manager&&active==0&&state.state().equals("ACTIVE")).put("canRestore",manager&&active==0&&state.state().equals("ARCHIVED"))
        .put("canDelete",actor.role().equals("ADMIN")&&active==0&&state.state().equals("ARCHIVED")).put("activeInvestigationCount",active);
    var audit=db.query("SELECT body FROM fcr_case_lifecycle_event WHERE tenant_id=? AND case_id=? ORDER BY version DESC LIMIT ?",(rs,n)->stored(rs.getString(1)),actor.tenantId(),id,CaseHistoryService.DEFAULT_LIMIT);
    result.set("audit",mapper.valueToTree(audit));
    long total=db.queryForObject("SELECT COUNT(*) FROM fcr_case_lifecycle_event WHERE tenant_id=? AND case_id=?",Long.class,actor.tenantId(),id);
    ObjectNode page=result.putObject("auditPage").put("total",total).put("limit",CaseHistoryService.DEFAULT_LIMIT);
    if(total>audit.size())page.put("nextCursor",audit.get(audit.size()-1).path("id").asText());else page.putNull("nextCursor");
    return result;
  }
  private void purge(Actor actor,String id,ObjectNode original) {
    // The API originals are removed transactionally; a durable outbox retires terminal worker copies after commit.
    db.update("INSERT INTO fcr_case_worker_cleanup(tenant_id,case_id,requested_at,attempts,eligible_at) VALUES(?,?,?,0,0)",actor.tenantId(),id,Instant.now().toString());
    for(String table:List.of("fcr_case_history_item","fcr_case_evidence_command","fcr_case_investigation","fcr_case_report","fcr_case_management_command","fcr_case_management_event","fcr_case_management","fcr_case_evidence"))
      db.update("DELETE FROM "+table+" WHERE tenant_id=? AND case_id=?",actor.tenantId(),id);
    // Keep idempotency reservations, but remove the original case payload from old receipts.
    for(var row:db.queryForList("SELECT actor_id,idempotency_key,body FROM fcr_case_command WHERE tenant_id=?",actor.tenantId())) {
      if(stored((String)row.get("body")).path("caseId").asText().equals(id))db.update("UPDATE fcr_case_command SET body=? WHERE tenant_id=? AND actor_id=? AND idempotency_key=?",
          mapper.createObjectNode().put("caseId",id).put("status","DELETED").toString(),actor.tenantId(),row.get("actor_id"),row.get("idempotency_key"));
    }
    ObjectNode tombstone=mapper.createObjectNode().put("id",id).put("orgBank",original.path("orgBank").asText()).put("orgBranch",original.path("orgBranch").asText());
    // Preserve the FK target and number allocation. Release identity for an explicitly created fresh case.
    db.update("UPDATE fcr_payment_case SET identity_hash=?,body=? WHERE tenant_id=? AND id=?",PaymentDiscoveryService.hash("deleted:"+id),tombstone.toString(),actor.tenantId(),id);
  }
  private ObjectNode stored(String value) {try{return (ObjectNode)mapper.readTree(value);}catch(Exception e){throw new ApiException(503,"CASE_LIFECYCLE_STORAGE_UNAVAILABLE","Stored lifecycle data could not be read.");}}
  private static ApiException invalid(String message){return new ApiException(422,"INVALID_CASE_LIFECYCLE",message);}
}
