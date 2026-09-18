package dev.pratik.poi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded public history metadata. No source payloads, model requests or bank inquiries. */
@Service
public class CaseHistoryService {
  static final int DEFAULT_LIMIT=10, MAX_LIMIT=25;
  private final ObjectMapper mapper;
  private final JdbcTemplate db;
  private final PaymentDiscoveryService cases;
  private final TransactionTemplate reads;
  public CaseHistoryService(ObjectMapper mapper,JdbcTemplate db,TransactionTemplate tx,PaymentDiscoveryService cases) {
    this.mapper=mapper;this.db=db;this.cases=cases;
    reads=new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager()));
    reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }
  record Query(int limit,String cursor,String evidenceId,String status) {
    static Query parse(Map<String,String[]> params,boolean jobs) {
      Set<String> allowed=jobs?Set.of("limit","cursor","evidenceId","status"):Set.of("limit","cursor");
      if(!allowed.containsAll(params.keySet()) || params.values().stream().anyMatch(v -> v==null || v.length!=1))throw invalid();
      int limit=DEFAULT_LIMIT;
      try {if(params.containsKey("limit")){String value=params.get("limit")[0];if(!value.matches("[0-9]{1,2}"))throw invalid();limit=Integer.parseInt(value);}}catch(NumberFormatException bad){throw invalid();}
      String cursor=one(params,"cursor"),evidence=one(params,"evidenceId"),status=one(params,"status");
      if(limit<1 || limit>MAX_LIMIT || cursor!=null&&!identifier(cursor) || evidence!=null&&!identifier(evidence)
          || status!=null&&!Set.of("QUEUED","RUNNING","COMPLETED","FAILED","CANCELLED","ACTIVE").contains(status))throw invalid();
      return new Query(limit,cursor,evidence,status);
    }
    private static String one(Map<String,String[]> values,String key){return values.containsKey(key)?values.get(key)[0]:null;}
  }
  public ObjectNode evidence(Actor actor,String caseId,Map<String,String[]> params) {
    Query query=Query.parse(params,false);return snapshot(actor,caseId,()->page(actor,caseId,"EVIDENCE",query));
  }
  public ObjectNode investigations(Actor actor,String caseId,Map<String,String[]> params) {
    Query query=Query.parse(params,true);return snapshot(actor,caseId,()->page(actor,caseId,"INVESTIGATION",query));
  }
  public ObjectNode activity(Actor actor,String caseId,Map<String,String[]> params) {
    Query query=Query.parse(params,false);return snapshot(actor,caseId,()->page(actor,caseId,"ACTIVITY",query));
  }
  public ObjectNode evidenceSummary(Actor actor,String caseId,String id) {
    if(!identifier(id))throw invalid();
    return snapshot(actor,caseId,()->{
      var values=db.queryForList("SELECT summary FROM fcr_case_evidence WHERE tenant_id=? AND case_id=? AND id=?",String.class,actor.tenantId(),caseId,id);
      if(values.isEmpty())throw ApiException.notFound();return bound(values.get(0),caseId,id,false);
    });
  }
  public ObjectNode investigationSummary(Actor actor,String caseId,String id) {
    if(!identifier(id))throw invalid();
    return snapshot(actor,caseId,()->{
      var values=db.queryForList("SELECT summary FROM fcr_case_investigation WHERE tenant_id=? AND case_id=? AND id=?",String.class,actor.tenantId(),caseId,id);
      if(values.isEmpty())throw ApiException.notFound();return bound(values.get(0),caseId,id,true);
    });
  }
  public ObjectNode workbench(Actor actor,String caseId) {
    return snapshot(actor,caseId,()->{
      ObjectNode item=cases.caseDetail(actor,caseId);
      Query first=new Query(DEFAULT_LIMIT,null,null,null);
      ObjectNode evidence=page(actor,caseId,"EVIDENCE",first), jobs=page(actor,caseId,"INVESTIGATION",first),activity=page(actor,caseId,"ACTIVITY",first);
      ObjectNode active=page(actor,caseId,"INVESTIGATION",new Query(MAX_LIMIT,null,null,"ACTIVE"));
      ObjectNode result=mapper.createObjectNode().put("caseId",caseId);
      for(String field:List.of("status","lifecycleState","lifecycleVersion"))result.set(field,item.path(field));
      result.set("evidence",evidence.path("items"));result.set("investigations",jobs.path("items"));result.set("audit",activity.path("items"));
      result.set("evidencePage",metadata(evidence));result.set("investigationPage",metadata(jobs));result.set("activityPage",metadata(activity));
      result.set("activeInvestigations",active.path("items"));result.set("activeInvestigationPage",metadata(active));
      if(evidence.path("items").isEmpty())result.putNull("latestEvidenceId");else result.put("latestEvidenceId",evidence.path("items").get(0).path("id").asText());
      return result;
    });
  }
  static ObjectNode metadata(ObjectNode page) {ObjectNode copy=page.deepCopy();copy.remove(List.of("items","caseId"));return copy;}

  private ObjectNode page(Actor actor,String caseId,String kind,Query query) {
    String from="fcr_case_history_item h",column="h.body";
    if(kind.equals("EVIDENCE")){from+=" JOIN fcr_case_evidence origin ON origin.tenant_id=h.tenant_id AND origin.case_id=h.case_id AND origin.id=h.item_id";column="origin.summary";}
    if(kind.equals("INVESTIGATION")){from+=" JOIN fcr_case_investigation origin ON origin.tenant_id=h.tenant_id AND origin.case_id=h.case_id AND origin.id=h.item_id";column="origin.summary";}
    String where="h.tenant_id=? AND h.case_id=? AND h.kind=?";
    List<Object> args=new ArrayList<>(List.of(actor.tenantId(),caseId,kind));
    if(query.evidenceId()!=null) {
      if(db.queryForObject("SELECT COUNT(*) FROM fcr_case_evidence WHERE tenant_id=? AND case_id=? AND id=?",Integer.class,actor.tenantId(),caseId,query.evidenceId())==0)throw ApiException.notFound();
      where+=" AND origin.evidence_id=?";args.add(query.evidenceId());
    }
    if(query.status()!=null) {
      if(query.status().equals("ACTIVE"))where+=" AND origin.status IN ('QUEUED','RUNNING')";
      else {where+=" AND origin.status=?";args.add(query.status());}
    }
    long total=db.queryForObject("SELECT COUNT(*) FROM "+from+" WHERE "+where,Long.class,args.toArray());
    if(query.cursor()!=null) {
      List<Object> anchorArgs=new ArrayList<>(args);anchorArgs.add(query.cursor());
      var anchors=db.queryForList("SELECT h.sort_seconds,h.sort_nanos FROM "+from+" WHERE "+where+" AND h.item_id=?",anchorArgs.toArray());
      if(anchors.isEmpty())throw new ApiException(409,"CASE_HISTORY_CURSOR_CHANGED","This history position no longer belongs to the selected case and filters. Refresh the history to start again.");
      var anchor=anchors.get(0);long seconds=((Number)anchor.get("sort_seconds")).longValue();int nanos=((Number)anchor.get("sort_nanos")).intValue();
      where+=" AND (h.sort_seconds<? OR (h.sort_seconds=? AND h.sort_nanos<?) OR (h.sort_seconds=? AND h.sort_nanos=? AND h.item_id<?))";
      Collections.addAll(args,seconds,seconds,nanos,seconds,nanos,query.cursor());
    }
    args.add(query.limit()+1);
    var rows=db.queryForList("SELECT h.item_id,"+column+" AS stored_value FROM "+from+" WHERE "+where+" ORDER BY h.sort_seconds DESC,h.sort_nanos DESC,h.item_id DESC LIMIT ?",args.toArray());
    ObjectNode result=mapper.createObjectNode().put("caseId",caseId).put("total",total).put("limit",query.limit());ArrayNode items=result.putArray("items");
    for(var row:rows.subList(0,Math.min(rows.size(),query.limit()))) {
      String id=(String)row.get("item_id");ObjectNode value=kind.equals("ACTIVITY")?CaseHistoryIndex.parse(mapper,(String)row.get("stored_value")):bound((String)row.get("stored_value"),caseId,id,kind.equals("INVESTIGATION"));
      if(!id.equals(value.path("id").asText()))throw CaseHistoryIndex.storage();items.add(value);
    }
    if(rows.size()>query.limit())result.put("nextCursor",(String)rows.get(query.limit()-1).get("item_id"));else result.putNull("nextCursor");
    return result;
  }
  private ObjectNode bound(String raw,String caseId,String id,boolean job) {
    ObjectNode value=CaseHistoryIndex.parse(mapper,raw);
    if(!id.equals(value.path("id").asText()) || !caseId.equals(value.path("caseId").asText()) || value.has("payload") || value.has("upstream") || value.has("input") || value.has("answer"))throw CaseHistoryIndex.storage();
    return job?CaseInvestigationService.historySummary(mapper,value):value;
  }
  private <T> T snapshot(Actor actor,String caseId,Supplier<T> work) {
    for(int attempt=0;attempt<3;attempt++)try{return reads.execute(status->{cases.caseRecord(actor,caseId);T value=work.get();cases.caseRecord(actor,caseId);return value;});}
    catch(CannotSerializeTransactionException conflict){if(attempt==2)throw new ApiException(503,"CASE_HISTORY_BUSY","Case history changed while this page was being read. Refresh to try again.");}
    throw new IllegalStateException("Unreachable history retry");
  }
  private static boolean identifier(String value){return value!=null&&value.matches("[A-Za-z0-9._:-]{1,200}");}
  private static ApiException invalid(){return new ApiException(400,"INVALID_CASE_HISTORY_QUERY","Use one limit from 1 to 25, an optional saved cursor, and supported investigation filters.");}
}
