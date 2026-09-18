package dev.pratik.poi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Rebuildable list positions and activity metadata. Original source bodies remain authoritative. */
@Service
@DependsOnDatabaseInitialization
public class CaseHistoryIndex {
  private static final int BATCH = 100;
  private final ObjectMapper mapper;
  private final JdbcTemplate db;
  private final TransactionTemplate tx;

  public CaseHistoryIndex(ObjectMapper mapper, JdbcTemplate db, TransactionTemplate tx) {
    this.mapper=mapper; this.db=db; this.tx=tx;
  }

  /** Startup repair only; public history reads never write or read all source payloads. */
  @PostConstruct public void rebuild() {
    String after="";
    while (true) {
      var cases=db.queryForList("SELECT id,tenant_id FROM fcr_payment_case WHERE id>? ORDER BY id LIMIT ?",after,BATCH);
      if(cases.isEmpty())return;
      for(var row:cases) {
        String tenant=(String)row.get("tenant_id"), id=(String)row.get("id");
        tx.executeWithoutResult(status -> rebuildCase(tenant,id));
      }
      after=(String)cases.get(cases.size()-1).get("id");
    }
  }

  private void rebuildCase(String tenant,String id) {
    var originals=db.queryForList("SELECT body FROM fcr_payment_case WHERE tenant_id=? AND id=? FOR UPDATE",String.class,tenant,id);
    db.update("DELETE FROM fcr_case_history_item WHERE tenant_id=? AND case_id=?",tenant,id);
    if(originals.isEmpty() || CaseLifecycleState.load(db,tenant,id).state().equals("DELETED"))return;
    recordCase(mapper,db,tenant,parse(mapper,originals.get(0)));
    rebuildRows(tenant,id,"fcr_case_evidence","summary",value -> recordEvidence(mapper,db,tenant,value));
    rebuildRows(tenant,id,"fcr_case_investigation","summary",value -> recordJob(mapper,db,tenant,value));
    rebuildRows(tenant,id,"fcr_case_management_event","body",value -> recordManagement(mapper,db,tenant,value));
    rebuildRows(tenant,id,"fcr_case_lifecycle_event","body",value -> recordLifecycle(mapper,db,tenant,value));
  }

  private void rebuildRows(String tenant,String id,String table,String column,java.util.function.Consumer<ObjectNode> save) {
    String after="";
    while(true) {
      // table and column are internal constants, never request input.
      var batch=db.queryForList("SELECT id,"+column+" AS stored_value FROM "+table+" WHERE tenant_id=? AND case_id=? AND id>? ORDER BY id LIMIT ?",tenant,id,after,BATCH);
      if(batch.isEmpty())return;
      for(var row:batch) {
        ObjectNode value=parse(mapper,(String)row.get("stored_value"));
        if(!id.equals(value.path("caseId").asText()) || !row.get("id").equals(value.path("id").asText()))throw storage();
        save.accept(value);
      }
      after=(String)batch.get(batch.size()-1).get("id");
    }
  }

  static void recordCase(ObjectMapper mapper,JdbcTemplate db,String tenant,ObjectNode item) {
    String caseId=item.path("id").asText();
    activity(mapper,db,tenant,caseId,caseId+"-opened","CASE_OPENED",item.path("createdAt").asText(),item.path("createdBy").asText(),
        "Saved payment case opened: "+item.path("reason").asText(),null,null);
  }
  static void recordEvidence(ObjectMapper mapper,JdbcTemplate db,String tenant,ObjectNode summary) {
    String id=summary.path("id").asText(),caseId=summary.path("caseId").asText();
    int version=summary.path("version").asInt();if(version<1)throw storage();
    position(db,tenant,caseId,"EVIDENCE",id,version,0,null);
    activity(mapper,db,tenant,caseId,id,"EVIDENCE_ATTACHED",summary.path("createdAt").asText(),summary.path("createdBy").asText(),
        "Evidence version "+version+" saved from "+summary.path("sourceKind").asText()+".",null,null);
  }
  static void recordJob(ObjectMapper mapper,JdbcTemplate db,String tenant,ObjectNode summary) {
    String id=summary.path("id").asText(),caseId=summary.path("caseId").asText();
    Instant created=instant(summary.path("createdAt").asText());
    position(db,tenant,caseId,"INVESTIGATION",id,created.getEpochSecond(),created.getNano(),null);
    activity(mapper,db,tenant,caseId,id+"-requested","INVESTIGATION_REQUESTED",summary.path("createdAt").asText(),summary.path("createdBy").asText(),
        "Question submitted for evidence version "+summary.path("evidenceVersion").asInt()+": "+summary.path("question").asText(),null,null);
    if(summary.hasNonNull("startedAt"))activity(mapper,db,tenant,caseId,id+"-started","INVESTIGATION_STARTED",summary.path("startedAt").asText(),"system",
        "Investigation worker began processing this question.",null,null);
    if(summary.hasNonNull("cancellationRequestedAt"))activity(mapper,db,tenant,caseId,id+"-cancel-requested","INVESTIGATION_CANCELLATION_REQUESTED",summary.path("cancellationRequestedAt").asText(),summary.path("cancellationRequestedBy").asText(),"Cancellation requested for the saved question.",null,null);
    if(summary.hasNonNull("finishedAt"))activity(mapper,db,tenant,caseId,id+"-finished","INVESTIGATION_"+summary.path("status").asText(),summary.path("finishedAt").asText(),"system",
        summary.path("status").asText().equals("COMPLETED")?"Model response and cited sources saved; factual review remains required.":summary.path("error").path("message").asText(summary.path("status").asText().equals("CANCELLED")?"The saved question was cancelled; no answer was attached.":"The saved question did not complete."),null,null);
  }
  static void recordManagement(ObjectMapper mapper,JdbcTemplate db,String tenant,ObjectNode event) {
    activity(mapper,db,tenant,event.path("caseId").asText(),event.path("id").asText(),event.path("action").asText(),event.path("occurredAt").asText(),
        event.path("actor").asText(),event.path("detail").asText(),event.path("actorName").asText(),event.path("version").asLong());
  }
  static void recordLifecycle(ObjectMapper mapper,JdbcTemplate db,String tenant,ObjectNode event) {
    activity(mapper,db,tenant,event.path("caseId").asText(),event.path("id").asText(),"CASE_"+event.path("action").asText(),event.path("occurredAt").asText(),
        event.path("actor").asText(),event.path("reason").asText(),event.path("actorName").asText(),event.path("version").asLong());
  }
  private static void activity(ObjectMapper mapper,JdbcTemplate db,String tenant,String caseId,String id,String action,String at,String actor,String detail,String name,Long version) {
    Instant instant=instant(at);
    ObjectNode value=mapper.createObjectNode().put("id",id).put("action",action).put("occurredAt",at).put("actor",actor).put("detail",detail);
    if(name!=null)value.put("actorName",name);if(version!=null)value.put("version",version);
    position(db,tenant,caseId,"ACTIVITY",id,instant.getEpochSecond(),instant.getNano(),value.toString());
  }
  private static void position(JdbcTemplate db,String tenant,String caseId,String kind,String id,long seconds,int nanos,String body) {
    if(db.update("UPDATE fcr_case_history_item SET sort_seconds=?,sort_nanos=?,body=? WHERE tenant_id=? AND case_id=? AND kind=? AND item_id=?",
        seconds,nanos,body,tenant,caseId,kind,id)==0)
      db.update("INSERT INTO fcr_case_history_item(tenant_id,case_id,kind,item_id,sort_seconds,sort_nanos,body) VALUES(?,?,?,?,?,?,?)",tenant,caseId,kind,id,seconds,nanos,body);
  }
  static ObjectNode parse(ObjectMapper mapper,String body) {
    try {var value=mapper.readTree(body);if(value instanceof ObjectNode object)return object;}catch(Exception ignored) { }
    throw storage();
  }
  private static Instant instant(String value){try{return Instant.parse(value);}catch(Exception invalid){throw storage();}}
  static ApiException storage(){return new ApiException(503,"CASE_HISTORY_STORAGE","The saved history metadata could not be verified. Refresh or ask an administrator to repair its index.");}
}
