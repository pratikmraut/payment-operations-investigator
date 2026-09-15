package dev.pratik.poi;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable, version-bound questions using the existing local model. Never changes payment state. */
@Service
public class CaseInvestigationService implements AutoCloseable {
  private final ObjectMapper mapper;
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final PaymentDiscoveryService cases;
  private final CaseEvidenceService evidence;
  private final CaseEvidenceProjection projection;
  private final UatWorkerClient worker;
  private final ExecutorService executor;
  private final Clock clock;
  private final Semaphore slots=new Semaphore(5);
  private volatile boolean accepting;

  @Autowired
  public CaseInvestigationService(ObjectMapper mapper,JdbcTemplate db,TransactionTemplate tx,
      PaymentDiscoveryService cases,CaseEvidenceService evidence,CaseEvidenceProjection projection,UatWorkerClient worker) {
    this(mapper,db,tx,cases,evidence,projection,worker,new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(5), runnable->{Thread thread=new Thread(runnable,"case-investigation-worker");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy()));
    accepting=false;
  }
  CaseInvestigationService(ObjectMapper mapper,JdbcTemplate db,TransactionTemplate tx,
      PaymentDiscoveryService cases,CaseEvidenceService evidence,CaseEvidenceProjection projection,UatWorkerClient worker,ExecutorService executor) {
    this(mapper,db,tx,cases,evidence,projection,worker,executor,Clock.systemUTC());
  }
  CaseInvestigationService(ObjectMapper mapper,JdbcTemplate db,TransactionTemplate tx,
      PaymentDiscoveryService cases,CaseEvidenceService evidence,CaseEvidenceProjection projection,UatWorkerClient worker,ExecutorService executor,Clock clock) {
    this.mapper=mapper;this.db=db;this.tx=tx;this.cases=cases;this.evidence=evidence;
    this.projection=projection;this.worker=worker;this.executor=executor;this.clock=clock;
    accepting=true; // Controlled executor constructor is used outside the managed application lifecycle.
  }

  public ObjectNode context(Actor actor,String caseId,String evidenceId) {
    ObjectNode item=cases.caseDetail(actor,caseId),snapshot=evidence.detail(actor,caseId,evidenceId);
    ObjectNode result=projection.project(actor,item,snapshot);
    result.put("evidenceId",evidenceId).put("evidenceVersion",snapshot.path("version").asInt())
        .put("evidenceHash",snapshot.path("evidenceHash").asText()).put("nonEmptyRows",rowCount(snapshot));
    return result;
  }

  public ObjectNode workbench(Actor actor,String caseId) {
    ObjectNode item=cases.caseDetail(actor,caseId);
    ArrayNode versions=(ArrayNode)evidence.list(actor,caseId).path("items");
    List<ObjectNode> jobs=db.query("SELECT summary FROM fcr_case_investigation WHERE tenant_id=? AND case_id=? ORDER BY created_at DESC,id DESC",
        (rs,n)->summary(stored(rs.getString(1))),actor.tenantId(),caseId);
    jobs.sort(Comparator.<ObjectNode,Instant>comparing(node->Instant.parse(node.path("createdAt").asText())).reversed());
    ObjectNode result=mapper.createObjectNode().put("caseId",caseId);
    result.set("lifecycleState",item.path("lifecycleState"));result.set("lifecycleVersion",item.path("lifecycleVersion"));
    result.set("evidence",versions);if(versions.isEmpty())result.putNull("latestEvidenceId");else result.put("latestEvidenceId",versions.get(0).path("id").asText());
    result.set("investigations",mapper.valueToTree(jobs));
    List<ObjectNode> audit=new ArrayList<>();
    audit.add(event(caseId+"-opened","CASE_OPENED",item.path("createdAt").asText(),item.path("createdBy").asText(),"Saved payment case opened: "+item.path("reason").asText()));
    for(JsonNode version:versions)audit.add(event(version.path("id").asText(),"EVIDENCE_ATTACHED",version.path("createdAt").asText(),version.path("createdBy").asText(),
        "Evidence version "+version.path("version").asInt()+" saved from "+version.path("sourceKind").asText()+"."));
    for(ObjectNode job:jobs) {
      String id=job.path("id").asText(),actorId=job.path("createdBy").asText();
      audit.add(event(id+"-requested","INVESTIGATION_REQUESTED",job.path("createdAt").asText(),actorId,"Question submitted for evidence version "+job.path("evidenceVersion").asInt()+": "+job.path("question").asText()));
      if(job.hasNonNull("startedAt"))audit.add(event(id+"-started","INVESTIGATION_STARTED",job.path("startedAt").asText(),"system","Investigation worker began processing this question."));
      if(job.hasNonNull("finishedAt"))audit.add(event(id+"-finished","INVESTIGATION_"+job.path("status").asText(),job.path("finishedAt").asText(),"system",
          job.path("status").asText().equals("COMPLETED")?"Model response and cited sources saved; factual review remains required.":job.path("error").path("message").asText()));
    }
    for(ObjectNode entry:db.query("SELECT body FROM fcr_case_lifecycle_event WHERE tenant_id=? AND case_id=? ORDER BY version",(rs,n)->stored(rs.getString(1)),actor.tenantId(),caseId))
      audit.add(event(entry.path("id").asText(),"CASE_"+entry.path("action").asText(),entry.path("occurredAt").asText(),entry.path("actor").asText(),entry.path("reason").asText()));
    audit.sort(Comparator.comparing(node->Instant.parse(node.path("occurredAt").asText())));
    result.set("audit",mapper.valueToTree(audit));return result;
  }

  public ObjectNode detail(Actor actor,String caseId,String id) {
    cases.caseDetail(actor,caseId);ObjectNode job=load(actor.tenantId(),caseId,id);
    verifyStored(job);verifyBinding(actor,caseId,job);ObjectNode result=summary(job);
    result.set("documents",job.path("input").path("documents").deepCopy());
    if(job.has("answer"))result.set("answer",job.get("answer").deepCopy());
    return result;
  }

  public ObjectNode start(Actor actor,String caseId,byte[] bytes,String key) {
    Instant requestedAt=clock.instant();
    actor.requireWriter();ObjectNode item=cases.requireActive(actor,caseId);
    if(!accepting)throw new ApiException(503,"CASE_INVESTIGATION_STARTING","The investigation service is starting or stopping. Wait briefly and retry.");
    if(bytes.length>16384)throw new ApiException(413,"CASE_QUESTION_TOO_LARGE","Question requests are limited to 16 KiB.");
    if(key==null || !key.matches("[A-Za-z0-9._:-]{8,200}"))throw new ApiException(400,"CASE_QUESTION_KEY_REQUIRED","An Idempotency-Key of 8–200 safe characters is required.");
    ObjectNode request=UatService.parseObject(mapper,bytes,invalid("Supply question, evidenceId and evidenceHash only."));
    Set<String> keys=new HashSet<>();request.fieldNames().forEachRemaining(keys::add);
    if(!keys.equals(Set.of("question","evidenceId","evidenceHash")))throw invalid("Supply question, evidenceId and evidenceHash only.");
    String question=text(request,"question",2000),evidenceId=text(request,"evidenceId",100),expectedHash=text(request,"evidenceHash",64);
    if(!expectedHash.matches("[a-f0-9]{64}"))throw invalid("Choose a saved evidence version with a valid fingerprint.");
    String requestHash=UatService.canonicalHash(request);
    ObjectNode replay=replay(actor,caseId,key,requestHash);if(replay!=null)return replay;
    ObjectNode snapshot=evidence.detail(actor,caseId,evidenceId);
    if(!snapshot.path("evidenceHash").asText().equals(expectedHash))throw new ApiException(409,"CASE_EVIDENCE_CHANGED","The selected evidence fingerprint differs. Refresh and select its saved version again.");
    if(rowCount(snapshot)==0)throw invalid("Attach at least one payment evidence row before running an investigation. Empty exports alone cannot be investigated.");
    if(!slots.tryAcquire())throw new ApiException(429,"CASE_INVESTIGATION_BUSY","The local investigation queue is full. Wait for a running question to finish.");
    boolean[] created={false};ObjectNode saved;
    try {
      ObjectNode projected=projection.project(actor,item,snapshot,question);
      ObjectNode input=mapper.createObjectNode().put("question",question).put("snapshotId",evidenceId).put("evidenceHash",expectedHash);
      input.set("documents",projected.path("documents").deepCopy());
      saved=tx.execute(status->{
        lockCase(actor,caseId);cases.requireActive(actor,caseId);ObjectNode found=replay(actor,caseId,key,requestHash);if(found!=null)return found;
        ObjectNode job=mapper.createObjectNode().put("id","CIN-"+UUID.randomUUID()).put("caseId",caseId)
            .put("evidenceId",evidenceId).put("evidenceVersion",snapshot.path("version").asInt()).put("evidenceHash",expectedHash)
            .put("question",question).put("status","QUEUED").put("requestedAt",requestedAt.toString())
            .put("createdAt",clock.instant().toString()).put("createdBy",actor.id())
            .put("answerContract",CaseAnswerValidator.PIPELINE)
            .put("guidanceHash",projected.path("guidanceHash").asText()).put("inputHash",UatService.canonicalHash(input));
        job.set("warnings",projected.path("warnings").deepCopy());job.set("input",input.deepCopy());
        setTiming(job);
        db.update("INSERT INTO fcr_case_investigation(id,tenant_id,case_id,evidence_id,created_at,actor_id,idempotency_key,request_hash,status,summary,body) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            job.path("id").asText(),actor.tenantId(),caseId,evidenceId,job.path("createdAt").asText(),actor.id(),key,requestHash,"QUEUED",summary(job).toString(),job.toString());
        created[0]=true;return summary(job);
      });
    }catch(RuntimeException failure){slots.release();throw failure;}
    if(!created[0]){slots.release();return saved;}
    String id=saved.path("id").asText();
    try {executor.execute(()->run(actor,caseId,id));}
    catch(RejectedExecutionException failure){slots.release();fail(actor.tenantId(),caseId,id,"CASE_INVESTIGATION_UNAVAILABLE","The local investigation worker could not start. Submit a new run when it is available.");return summary(load(actor.tenantId(),caseId,id));}
    return saved;
  }

  private void run(Actor actor,String caseId,String id) {
    try {
      cases.caseDetail(actor,caseId);
      ObjectNode running=tx.execute(status->{
        ObjectNode job=lockedJob(actor.tenantId(),caseId,id);
        if(!job.path("status").asText().equals("QUEUED"))return null;
        job.put("status","RUNNING").put("startedAt",clock.instant().toString());update(actor.tenantId(),caseId,job);return job;
      });
      if(running==null)return;verifyStored(running);verifyBinding(actor,caseId,running);
      ObjectNode input=(ObjectNode)running.path("input");
      ObjectNode answer=worker.answerCase(input.deepCopy());CaseAnswerValidator.validate(answer,input);
      cases.caseDetail(actor,caseId);verifyBinding(actor,caseId,running);
      tx.executeWithoutResult(status->{
        ObjectNode job=lockedJob(actor.tenantId(),caseId,id);
        if(!job.path("status").asText().equals("RUNNING"))return;
        job.put("status","COMPLETED").put("finishedAt",clock.instant().toString());job.set("answer",answer.deepCopy());
        update(actor.tenantId(),caseId,job);
      });
    }catch(ApiException failure){
      // Only fixed application errors are stored. Never store provider/source-bearing exception text.
      String message=failure.status==504?"The local model exceeded the response wait. No answer was substituted. Check the model before starting a new run."
          :failure.status==403 || failure.status==404?"Case access changed before the investigation completed. No answer was saved."
          :failure.code.equals("UAT_MODEL_BUSY")?"The local model is answering another request. Start a new run after it finishes."
          :"The local model could not return a valid cited answer. No fallback answer was used. You can start a new run.";
      fail(actor.tenantId(),caseId,id,failure.code,message);
    }catch(RuntimeException failure){fail(actor.tenantId(),caseId,id,"CASE_INVESTIGATION_FAILED","The investigation did not complete. No fallback answer was used.");}
    finally{slots.release();}
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recoverInterrupted() {
    accepting=false;
    for(Map<String,Object> row:db.queryForList("SELECT tenant_id,case_id,id FROM fcr_case_investigation WHERE status IN ('QUEUED','RUNNING')"))
      fail((String)row.get("tenant_id"),(String)row.get("case_id"),(String)row.get("id"),"CASE_INVESTIGATION_INTERRUPTED","The application restarted before this investigation completed. Start a new run; no automatic model retry was made.");
    accepting=true;
  }

  private void fail(String tenant,String caseId,String id,String code,String message) {
    tx.executeWithoutResult(status->{
      ObjectNode job=lockedJob(tenant,caseId,id);
      if(!Set.of("QUEUED","RUNNING").contains(job.path("status").asText()))return;
      job.put("status","FAILED").put("finishedAt",clock.instant().toString());job.putObject("error").put("code",code).put("message",message);update(tenant,caseId,job);
    });
  }
  private ObjectNode replay(Actor actor,String caseId,String key,String requestHash) {
    var rows=db.queryForList("SELECT request_hash,summary FROM fcr_case_investigation WHERE tenant_id=? AND actor_id=? AND case_id=? AND idempotency_key=?",actor.tenantId(),actor.id(),caseId,key);
    if(rows.isEmpty())return null;
    if(!requestHash.equals(rows.get(0).get("request_hash")))throw new ApiException(409,"CASE_QUESTION_KEY_CONFLICT","This retry key belongs to a different question or evidence version. Start a new run.");
    return summary(stored((String)rows.get(0).get("summary")));
  }
  private void lockCase(Actor actor,String caseId){if(db.query("SELECT id FROM fcr_payment_case WHERE tenant_id=? AND id=? FOR UPDATE",(rs,n)->rs.getString(1),actor.tenantId(),caseId).isEmpty())throw ApiException.notFound();cases.caseDetail(actor,caseId);}
  private ObjectNode lockedJob(String tenant,String caseId,String id){return queryJob(tenant,caseId,id,true);}
  private ObjectNode load(String tenant,String caseId,String id){return queryJob(tenant,caseId,id,false);}
  private ObjectNode queryJob(String tenant,String caseId,String id,boolean lock){var rows=db.query("SELECT body FROM fcr_case_investigation WHERE tenant_id=? AND case_id=? AND id=?"+(lock?" FOR UPDATE":""),(rs,n)->stored(rs.getString(1)),tenant,caseId,id);if(rows.isEmpty())throw ApiException.notFound();return rows.get(0);}
  private void update(String tenant,String caseId,ObjectNode job){setTiming(job);db.update("UPDATE fcr_case_investigation SET status=?,summary=?,body=? WHERE tenant_id=? AND case_id=? AND id=?",job.path("status").asText(),summary(job).toString(),job.toString(),tenant,caseId,job.path("id").asText());}
  private void verifyStored(ObjectNode job){
    if(!(job.get("input") instanceof ObjectNode input) || !UatService.canonicalHash(input).equals(job.path("inputHash").asText())
        || !input.path("snapshotId").equals(job.path("evidenceId")) || !input.path("evidenceHash").equals(job.path("evidenceHash"))
        || !input.path("question").equals(job.path("question")))throw storageFailure();
    try{UatService.documentMap(input.path("documents"),storageFailure());
      if(job.path("status").asText().equals("COMPLETED") && !(job.get("answer") instanceof ObjectNode))throw storageFailure();
      if(job.has("answer")) {
        ObjectNode answer=(ObjectNode)job.path("answer");
        if(job.has("answerContract") && !CaseAnswerValidator.PIPELINE.equals(job.path("answerContract").asText()))throw storageFailure();
        if(job.has("answerContract") || answer.has("rag"))CaseAnswerValidator.validate(answer,input);
        else UatService.validateAnswer(answer,input);
      }
    }catch(ApiException failure){throw storageFailure();}
  }
  private void verifyBinding(Actor actor,String caseId,ObjectNode job){
    ObjectNode snapshot=evidence.detail(actor,caseId,job.path("evidenceId").asText());
    if(!snapshot.path("evidenceHash").equals(job.path("evidenceHash"))
        || snapshot.path("version").asInt()!=job.path("evidenceVersion").asInt()
        || !CaseEvidenceService.fingerprint(snapshot).equals(job.path("evidenceHash").asText()))
      throw new ApiException(409,"CASE_INVESTIGATION_EVIDENCE_CHANGED","The saved evidence version failed its fingerprint check. No investigation answer can be accepted.");
  }
  private ObjectNode summary(ObjectNode job){ObjectNode result=job.deepCopy();result.remove(List.of("input","answer"));setTiming(result);return result;}
  private void setTiming(ObjectNode job) {
    boolean requestMeasured=job.hasNonNull("requestedAt");
    boolean terminal=Set.of("COMPLETED","FAILED").contains(job.path("status").asText());
    JsonNode finished=terminal?job.get("finishedAt"):null;
    ObjectNode timing=mapper.createObjectNode().put("totalBasis",requestMeasured?"request-received":"job-created");
    timing.put("preparationMs",elapsed(job.get("requestedAt"),job.get("createdAt")));
    timing.put("queueMs",elapsed(job.get("createdAt"),job.hasNonNull("startedAt")?job.get("startedAt"):finished));
    timing.put("processingMs",elapsed(job.get("startedAt"),finished));
    timing.put("totalMs",elapsed(job.get(requestMeasured?"requestedAt":"createdAt"),finished));
    job.set("timing",timing);
  }
  private static Long elapsed(JsonNode from,JsonNode to) {
    if(from==null || to==null || !from.isTextual() || !to.isTextual())return null;
    try {
      Instant start=Instant.parse(from.textValue()),end=Instant.parse(to.textValue());
      return end.isBefore(start)?null:Duration.between(start,end).toMillis();
    }catch(DateTimeException | ArithmeticException unavailable){return null;}
  }
  private ObjectNode stored(String body){try{return (ObjectNode)mapper.readTree(body);}catch(Exception failure){throw storageFailure();}}
  private int rowCount(ObjectNode snapshot){int count=0;for(String group:CaseEvidenceSchema.COLUMNS.keySet())count+=snapshot.path("payload").path("sections").path(group).path("rows").size();return count;}
  private ObjectNode event(String id,String action,String at,String actor,String detail){return mapper.createObjectNode().put("id",id).put("action",action).put("occurredAt",at).put("actor",actor).put("detail",detail);}
  private static String text(ObjectNode node,String key,int limit){JsonNode value=node.get(key);if(value==null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length()>limit || value.textValue().indexOf('\0')>=0)throw invalid(key+": supply nonblank text of at most "+limit+" characters.");return value.textValue();}
  private static ApiException invalid(String message){return new ApiException(422,"INVALID_CASE_QUESTION",message);}
  private static ApiException storageFailure(){return new ApiException(503,"CASE_INVESTIGATION_STORAGE","The saved investigation could not be verified.");}
  @Override @PreDestroy public void close(){accepting=false;executor.shutdownNow();}
}
