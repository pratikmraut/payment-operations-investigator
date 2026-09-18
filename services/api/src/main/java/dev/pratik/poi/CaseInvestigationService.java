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
  private final CaseJobCoordinator coordinator;
  private final java.util.concurrent.atomic.AtomicBoolean scheduled=new java.util.concurrent.atomic.AtomicBoolean();
  private volatile CaseJobCoordinator.Claim currentClaim;
  private ScheduledExecutorService scheduler;
  private boolean managed;
  private volatile boolean accepting;

  @Autowired
  public CaseInvestigationService(ObjectMapper mapper,JdbcTemplate db,TransactionTemplate tx,
      PaymentDiscoveryService cases,CaseEvidenceService evidence,CaseEvidenceProjection projection,UatWorkerClient worker) {
    this(mapper,db,tx,cases,evidence,projection,worker,Executors.newSingleThreadExecutor(runnable->{Thread thread=new Thread(runnable,"case-investigation-dispatch");thread.setDaemon(true);return thread;}));
    managed=true;
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
    coordinator=new CaseJobCoordinator(db,tx,clock);
    accepting=true; // Explicit executor constructors use the same dispatcher with a caller-controlled clock and scheduling.
  }

  public ObjectNode context(Actor actor,String caseId,String evidenceId) {
    ObjectNode item=cases.caseDetail(actor,caseId),snapshot=evidence.detail(actor,caseId,evidenceId);
    ObjectNode result=projection.project(actor,item,snapshot);
    result.put("evidenceId",evidenceId).put("evidenceVersion",snapshot.path("version").asInt())
        .put("evidenceHash",snapshot.path("evidenceHash").asText()).put("nonEmptyRows",rowCount(snapshot));
    return result;
  }

  public ObjectNode workbench(Actor actor,String caseId) {
    return new CaseHistoryService(mapper,db,tx,cases).workbench(actor,caseId);
  }

  public ObjectNode detail(Actor actor,String caseId,String id) {
    cases.caseDetail(actor,caseId);ObjectNode job=load(actor.tenantId(),caseId,id);
    verifyStored(job);verifyBinding(actor,caseId,job);ObjectNode result=summary(job);
    if(job.has("input"))result.set("documents",job.path("input").path("documents").deepCopy());else result.putArray("documents");
    if(job.has("answer"))result.set("answer",job.get("answer").deepCopy());
    return result;
  }

  public ObjectNode readiness(Actor actor,String caseId,byte[] bytes) {
    ObjectNode request=questionRequest(bytes),item=cases.caseDetail(actor,caseId);
    ObjectNode snapshot=questionEvidence(actor,caseId,request);
    ObjectNode result=projection.readiness(actor,item,snapshot,request.path("question").asText());
    result.put("caseId",caseId).put("evidenceId",snapshot.path("id").asText()).put("evidenceVersion",snapshot.path("version").asInt())
        .put("evidenceHash",snapshot.path("evidenceHash").asText());
    return result;
  }

  public ObjectNode start(Actor actor,String caseId,byte[] bytes,String key) {
    Instant requestedAt=clock.instant();actor.requireWriter();ObjectNode item=cases.requireActive(actor,caseId);
    if(!accepting)throw new ApiException(503,"CASE_INVESTIGATION_STARTING","The investigation service is starting or stopping. Wait briefly and retry.");
    if(key==null || !key.matches("[A-Za-z0-9._:-]{8,200}"))throw new ApiException(400,"CASE_QUESTION_KEY_REQUIRED","An Idempotency-Key of 8–200 safe characters is required.");
    ObjectNode request=questionRequest(bytes);String requestHash=UatService.canonicalHash(request);
    ObjectNode replay=replay(actor,caseId,key,requestHash);if(replay!=null)return replay;
    ObjectNode snapshot=questionEvidence(actor,caseId,request);
    ObjectNode ready=projection.readiness(actor,item,snapshot,request.path("question").asText());
    if(!ready.path("ready").asBoolean())throw new ApiException(409,"CASE_KNOWLEDGE_NOT_READY","Current knowledge needs matching embeddings before this question can be queued.");
    ObjectNode saved=tx.execute(status->{
      coordinator.lock();lockCase(actor,caseId);cases.requireActive(actor,caseId);
      ObjectNode previous=replay(actor,caseId,key,requestHash);if(previous!=null)return previous;
      coordinator.checkCapacity(actor.tenantId(),actor.id());
      Instant now=clock.instant();
      ObjectNode job=mapper.createObjectNode().put("id","CIN-"+UUID.randomUUID()).put("caseId",caseId)
          .put("evidenceId",snapshot.path("id").asText()).put("evidenceVersion",snapshot.path("version").asInt()).put("evidenceHash",snapshot.path("evidenceHash").asText())
          .put("question",request.path("question").asText()).put("status","QUEUED").put("phase","QUEUED").put("queueVersion","case-job-v1")
          .put("requestedAt",requestedAt.toString()).put("createdAt",now.toString()).put("createdBy",actor.id())
          .put("answerContract",CaseAnswerValidator.PIPELINE).put("knowledgeVersion",ready.path("knowledgeVersion").asText())
          .put("cancellationRequested",false).put("workerSubmissionStarted",false);
      job.set("warnings",ready.path("warnings").deepCopy());if(ready.has("selection"))job.set("selection",ready.path("selection").deepCopy());if(ready.has("knowledgeSelection"))job.set("knowledgeSelection",ready.path("knowledgeSelection").deepCopy());setTiming(job);
      db.update("INSERT INTO fcr_case_investigation(id,tenant_id,case_id,evidence_id,created_at,actor_id,idempotency_key,request_hash,status,summary,body) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
          job.path("id").asText(),actor.tenantId(),caseId,job.path("evidenceId").asText(),job.path("createdAt").asText(),actor.id(),key,requestHash,"QUEUED",summary(job).toString(),job.toString());
      coordinator.add(actor.tenantId(),caseId,job.path("id").asText(),actor.id(),now.getEpochSecond(),now.getNano());
      CaseHistoryIndex.recordJob(mapper,db,actor.tenantId(),summary(job));return summary(job);
    });
    signal();return saved;
  }

  public ObjectNode cancel(Actor actor,String caseId,String id,byte[] bytes) {
    actor.requireWriter();cases.caseDetail(actor,caseId);
    if(bytes.length>512 || !UatService.parseObject(mapper,bytes,invalid("Supply an empty cancellation object.")).isEmpty())throw invalid("Supply an empty cancellation object.");
    ObjectNode result=tx.execute(status->{
      coordinator.lock();lockCase(actor,caseId);ObjectNode job=lockedJob(actor.tenantId(),caseId,id);
      if(!actor.id().equals(job.path("createdBy").asText())&&!actor.role().equals("REVIEWER"))throw new ApiException(403,"CASE_CANCEL_FORBIDDEN","Only the question's author or a reviewer can cancel it.");
      if(!pending(job))return summary(job);
      if(!job.path("cancellationRequested").asBoolean())job.put("cancellationRequested",true).put("cancellationRequestedAt",clock.instant().toString()).put("cancellationRequestedBy",actor.id());
      if(!job.path("workerSubmissionStarted").asBoolean()) {
        terminal(job,"CANCELLED","CASE_INVESTIGATION_CANCELLED","The question was cancelled before model generation started.");
        db.update("UPDATE fcr_case_dispatcher SET job_id=NULL,lease_owner=NULL,lease_until=0,fence=fence+1 WHERE id=1 AND job_id=?",id);
      } else job.put("phase","CANCELLING");
      db.update("UPDATE fcr_case_job_queue SET eligible_at=0 WHERE job_id=?",id);
      update(actor.tenantId(),caseId,job);return summary(job);
    });
    signal();return result;
  }

  private ObjectNode questionRequest(byte[] bytes) {
    if(bytes.length>16384)throw new ApiException(413,"CASE_QUESTION_TOO_LARGE","Question requests are limited to 16 KiB.");
    ObjectNode request=UatService.parseObject(mapper,bytes,invalid("Supply question, evidenceId and evidenceHash only."));
    Set<String> keys=new HashSet<>();request.fieldNames().forEachRemaining(keys::add);
    if(!keys.equals(Set.of("question","evidenceId","evidenceHash")))throw invalid("Supply question, evidenceId and evidenceHash only.");
    text(request,"question",2000);text(request,"evidenceId",100);String hash=text(request,"evidenceHash",64);
    if(!hash.matches("[a-f0-9]{64}"))throw invalid("Choose a saved evidence version with a valid fingerprint.");return request;
  }
  private ObjectNode questionEvidence(Actor actor,String caseId,ObjectNode request) {
    ObjectNode snapshot=evidence.detail(actor,caseId,request.path("evidenceId").asText());
    if(!snapshot.path("evidenceHash").equals(request.path("evidenceHash")))throw new ApiException(409,"CASE_EVIDENCE_CHANGED","The selected evidence fingerprint differs. Refresh and select its saved version again.");
    if(rowCount(snapshot)==0)throw invalid("Attach at least one payment evidence row before running an investigation. Empty exports alone cannot be investigated.");return snapshot;
  }
  private void signal() {
    if(!accepting || !scheduled.compareAndSet(false,true))return;
    try {executor.execute(()->{try{dispatchOnce();}finally{scheduled.set(false);}});}
    catch(RejectedExecutionException stopping){scheduled.set(false);} // Persisted jobs survive a stopped executor.
  }
  /** Same deterministic dispatcher in managed runtime and isolated tests; no synchronous model shortcut. */
  void dispatchOnce() {
    if(!accepting)return;
    cleanupOne();CaseJobCoordinator.Claim claim=coordinator.claim();if(claim==null)return;currentClaim=claim;
    try {process(claim);}catch(ApiException failure){handleFailure(claim,failure);}
    catch(RuntimeException failure){handleFailure(claim,new ApiException(503,"CASE_INVESTIGATION_FAILED","The saved question could not be processed."));}
    finally {currentClaim=null;}
  }
  private void process(CaseJobCoordinator.Claim claim) {
    Actor actor=Actor.knownActors().stream().filter(a->a.tenantId().equals(claim.tenant())&&a.id().equals(claim.actorId())).findFirst().orElseThrow(ApiException::notFound);
    ObjectNode job=load(claim.tenant(),claim.caseId(),claim.jobId());if(!pending(job))return;
    if(job.path("cancellationRequested").asBoolean() && job.path("workerSubmissionStarted").asBoolean()) {cancelWorker(claim,job);return;}
    actor.requireWriter();ObjectNode item=cases.caseDetail(actor,claim.caseId());verifyBinding(actor,claim.caseId(),job);verifyStored(job);
    if(!job.has("input")) {
      job=change(claim,current->{current.put("status","RUNNING").put("phase","PREPARING");if(!current.hasNonNull("startedAt"))current.put("startedAt",clock.instant().toString());current.put("preparationStartedAt",clock.instant().toString());});
      if(job==null)return;
      ObjectNode snapshot=evidence.detail(actor,claim.caseId(),job.path("evidenceId").asText());
      ObjectNode ready=projection.readiness(actor,item,snapshot,job.path("question").asText());
      if(!ready.path("ready").asBoolean() || !ready.path("knowledgeVersion").equals(job.path("knowledgeVersion")))
        throw new ApiException(409,"CASE_KNOWLEDGE_CHANGED","Knowledge changed after this question was queued. Review the current guidance and submit a new question.");
      ObjectNode selected=projection.project(actor,item,snapshot,job.path("question").asText());
      if(!selected.path("knowledgeVersion").equals(job.path("knowledgeVersion")))throw new ApiException(409,"CASE_KNOWLEDGE_CHANGED","Knowledge changed while preparing this question. Review the guidance and submit a new question.");
      ObjectNode input=mapper.createObjectNode().put("question",job.path("question").asText()).put("snapshotId",job.path("evidenceId").asText()).put("evidenceHash",job.path("evidenceHash").asText());
      input.set("documents",selected.path("documents").deepCopy());
      job=change(claim,current->{current.set("input",input);current.put("inputHash",UatService.canonicalHash(input)).put("guidanceHash",selected.path("guidanceHash").asText())
          .put("preparedAt",clock.instant().toString()).put("phase","PREFLIGHT");current.set("warnings",selected.path("warnings").deepCopy());if(selected.has("selection"))current.set("selection",selected.path("selection").deepCopy());if(selected.has("knowledgeSelection"))current.set("knowledgeSelection",selected.path("knowledgeSelection").deepCopy());});
      if(job==null)return;
    }
    verifyStored(job);ObjectNode input=(ObjectNode)job.path("input");
    if(!job.has("contextReadiness")) {
      ObjectNode preflight=worker.preflightCase(input.deepCopy());
      if(preflight==null || !preflight.path("schemaVersion").asText().equals("case-context-readiness-v1") || !preflight.path("ready").isBoolean())throw UatService.invalidWorker();
      if(!preflight.path("ready").asBoolean())throw new ApiException(422,"CASE_CONTEXT_LIMIT","This question's exact serialized evidence exceeds the configured model context. Ask a narrower question or select fewer relevant source rows.");
      job=change(claim,current->current.set("contextReadiness",preflight.deepCopy()));if(job==null)return;
    }
    // Persist uncertainty before crossing the process boundary. A retry uses this exact frozen identity.
    if(!job.path("workerSubmissionStarted").asBoolean()) {
      job=change(claim,current->current.put("status","RUNNING").put("phase","SUBMITTING").put("workerSubmissionStarted",true));if(job==null)return;
    }
    if(job.path("cancellationRequested").asBoolean()){cancelWorker(claim,job);return;}
    ObjectNode identity=workerIdentity(claim,job),receipt;
    try {receipt=worker.caseJobStatus(identity);}
    catch(ApiException missing) {
      if(!missing.code.equals("CASE_WORKER_JOB_NOT_FOUND"))throw missing;
      ObjectNode envelope=identity.deepCopy();envelope.set("input",input.deepCopy());receipt=worker.submitCaseJob(envelope);
    }
    acceptReceipt(claim,actor,job,receipt);
  }
  private void cancelWorker(CaseJobCoordinator.Claim claim,ObjectNode job) {
    verifyStored(job);ObjectNode receipt;
    try {receipt=worker.cancelCaseJob(workerIdentity(claim,job));}
    catch(ApiException missing) {if(!missing.code.equals("CASE_WORKER_JOB_NOT_FOUND"))throw missing;
      throw new ApiException(503,"CASE_WORKER_CANCEL_UNCONFIRMED","The model worker has not acknowledged this cancellation yet.");}
    Actor actor=Actor.knownActors().stream().filter(a->a.tenantId().equals(claim.tenant())&&a.id().equals(claim.actorId())).findFirst().orElse(null);
    acceptReceipt(claim,actor,job,receipt);
  }
  private ObjectNode workerIdentity(CaseJobCoordinator.Claim claim,ObjectNode job) {
    return mapper.createObjectNode().put("tenantId",claim.tenant()).put("caseId",claim.caseId()).put("jobId",claim.jobId()).put("inputHash",job.path("inputHash").asText());
  }
  private void acceptReceipt(CaseJobCoordinator.Claim claim,Actor actor,ObjectNode original,ObjectNode receipt) {
    ObjectNode identity=workerIdentity(claim,original);
    if(receipt==null)throw UatService.invalidWorker();
    for(String field:List.of("tenantId","caseId","jobId","inputHash"))if(!identity.path(field).equals(receipt.path(field)))throw UatService.invalidWorker();
    String status=receipt.path("status").asText();
    if(!Set.of("QUEUED","RUNNING","COMPLETED","FAILED","CANCELLED").contains(status))throw UatService.invalidWorker();
    if(status.equals("QUEUED")||status.equals("RUNNING")) {
      change(claim,current->current.put("status","RUNNING").put("phase",current.path("cancellationRequested").asBoolean()?"CANCELLING":"GENERATING").put("workerStatus",status));return;
    }
    if(status.equals("COMPLETED")) {
      if(!(receipt.get("answer") instanceof ObjectNode answer))throw UatService.invalidWorker();
      // A cancellation racing a completed worker receipt discards its answer explicitly.
      ObjectNode latest=load(claim.tenant(),claim.caseId(),claim.jobId());
      if(latest.path("cancellationRequested").asBoolean()){finish(claim,"CANCELLED","CASE_INVESTIGATION_CANCELLED","Cancellation completed. The model answer was not attached to this question.",null);return;}
      if(actor==null)throw ApiException.notFound();cases.caseDetail(actor,claim.caseId());verifyBinding(actor,claim.caseId(),original);
      CaseAnswerValidator.validate(answer,(ObjectNode)original.path("input"));finish(claim,"COMPLETED",null,null,answer);return;
    }
    if(status.equals("CANCELLED"))finish(claim,"CANCELLED","CASE_INVESTIGATION_CANCELLED","The model worker acknowledged cancellation. No answer was attached.",null);
    else {
      String workerCode=receipt.path("error").path("code").asText();
      String code=Set.of("CASE_WORKER_INTERRUPTED","UAT_MODEL_TIMEOUT","INVALID_UAT_ANSWER","UAT_MODEL_BUSY").contains(workerCode)?workerCode:"CASE_INVESTIGATION_FAILED";
      finish(claim,"FAILED",code,code.equals("CASE_WORKER_INTERRUPTED")?"The model worker restarted during generation. This question was not generated again automatically; submit a new question when ready.":"The model worker could not produce a valid cited answer. No fallback answer was attached.",null);
    }
  }
  private ObjectNode change(CaseJobCoordinator.Claim claim,java.util.function.Consumer<ObjectNode> edit) {
    return coordinator.fenced(claim,()->{ObjectNode job=lockedJob(claim.tenant(),claim.caseId(),claim.jobId());if(!pending(job))return null;edit.accept(job);update(claim.tenant(),claim.caseId(),job);return job;});
  }
  private void finish(CaseJobCoordinator.Claim claim,String status,String code,String message,ObjectNode answer) {
    coordinator.fenced(claim,()->{ObjectNode job=lockedJob(claim.tenant(),claim.caseId(),claim.jobId());if(!pending(job))return null;
      if(job.has("abortReason")){statusFromAbort(job);}
      else if(status.equals("COMPLETED") && job.path("cancellationRequested").asBoolean()) {terminal(job,"CANCELLED","CASE_INVESTIGATION_CANCELLED","Cancellation completed. The model answer was not attached to this question.");}
      else {terminal(job,status,code,message);if(answer!=null)job.set("answer",answer.deepCopy());}
      update(claim.tenant(),claim.caseId(),job);coordinator.done(claim);return true;});
  }
  private void statusFromAbort(ObjectNode job){terminal(job,"FAILED",job.path("abortReason").path("code").asText(),job.path("abortReason").path("message").asText());}
  private void terminal(ObjectNode job,String status,String code,String message) {
    job.put("status",status).put("phase",status).put("finishedAt",clock.instant().toString());job.remove("waitingReason");
    if(code!=null)job.putObject("error").put("code",code).put("message",message);
  }
  private void handleFailure(CaseJobCoordinator.Claim claim,ApiException failure) {
    ObjectNode job;
    try{job=load(claim.tenant(),claim.caseId(),claim.jobId());}catch(ApiException gone){return;}
    if(!pending(job))return;
    boolean transientFailure=Set.of("UAT_MODEL_UNAVAILABLE","UAT_MODEL_TIMEOUT","UAT_MODEL_BUSY","CASE_WORKER_QUEUE_FULL","CASE_WORKER_CANCEL_UNCONFIRMED").contains(failure.code);
    if(transientFailure) {
      coordinator.fenced(claim,()->{ObjectNode current=lockedJob(claim.tenant(),claim.caseId(),claim.jobId());if(!pending(current))return null;
        current.put("phase",current.path("cancellationRequested").asBoolean()?"CANCELLING":"WAITING");
        current.putObject("waitingReason").put("code",failure.code).put("message","Waiting for the local model worker. The saved question will resume automatically with the same request identity.");
        update(claim.tenant(),claim.caseId(),current);coordinator.later(claim,5000);return true;});return;
    }
    String code=Set.of("CASE_KNOWLEDGE_CHANGED","CASE_CONTEXT_LIMIT","CASE_INVESTIGATION_EVIDENCE_CHANGED","INVALID_UAT_ANSWER","CASE_INVESTIGATION_STORAGE").contains(failure.code)?failure.code:"CASE_INVESTIGATION_FAILED";
    String message=code.equals("CASE_KNOWLEDGE_CHANGED")?"Knowledge changed after the question was queued. Refresh guidance and submit a new question.":code.equals("CASE_CONTEXT_LIMIT")?"The selected evidence exceeds the configured model context. Ask a narrower question before trying again.":"The saved question or model response could not be verified. No fallback answer was attached.";
    // A worker may still be active after an access/binding problem: await its cancellation acknowledgement.
    if(job.path("workerSubmissionStarted").asBoolean() && (failure.status==403||failure.status==404||failure.code.equals("CASE_INVESTIGATION_EVIDENCE_CHANGED"))) {
      change(claim,current->{current.put("cancellationRequested",true).put("phase","CANCELLING");current.putObject("abortReason").put("code",code).put("message",message);});return;
    }
    finish(claim,"FAILED",code,message,null);
  }
  private static boolean pending(ObjectNode job){return Set.of("QUEUED","RUNNING").contains(job.path("status").asText());}

  @EventListener(ApplicationReadyEvent.class)
  public void recoverInterrupted() {
    accepting=false;
    // New durable jobs retain phase and frozen inputs. Old synchronous jobs have no worker receipt and cannot safely be replayed.
    String after="";
    while(true) {
      var rows=db.queryForList("SELECT tenant_id,case_id,id FROM fcr_case_investigation WHERE status IN ('QUEUED','RUNNING') AND id>? ORDER BY id LIMIT 100",after);
      if(rows.isEmpty())break;
      for(var row:rows)tx.executeWithoutResult(s->{
        coordinator.lock();String tenant=(String)row.get("tenant_id"),caseId=(String)row.get("case_id"),id=(String)row.get("id");ObjectNode job=lockedJob(tenant,caseId,id);
        if(!pending(job))return;
        if(job.path("queueVersion").asText().equals("case-job-v1")) {
          if(db.queryForObject("SELECT COUNT(*) FROM fcr_case_job_queue WHERE job_id=?",Integer.class,id)==0){Instant at=Instant.parse(job.path("createdAt").asText());coordinator.add(tenant,caseId,id,job.path("createdBy").asText(),at.getEpochSecond(),at.getNano());}
        } else {terminal(job,"FAILED","CASE_INVESTIGATION_INTERRUPTED","This older synchronous investigation was interrupted. No automatic model retry was made; submit a new question when ready.");update(tenant,caseId,job);}
      });
      after=(String)rows.get(rows.size()-1).get("id");
    }
    accepting=true;
    if(managed && scheduler==null) {
      scheduler=Executors.newScheduledThreadPool(2,r->{Thread t=new Thread(r,"case-investigation-watch");t.setDaemon(true);return t;});
      scheduler.scheduleWithFixedDelay(()->{try{signal();}catch(RuntimeException unavailable){/* Saved jobs remain queued. */}},0,1,TimeUnit.SECONDS);
      scheduler.scheduleWithFixedDelay(()->{try{var claim=currentClaim;if(claim!=null)coordinator.heartbeat(claim);}catch(RuntimeException unavailable){/* Fencing rejects stale writes. */}},5,5,TimeUnit.SECONDS);
    }
  }
  private void cleanupOne() {
    var pending=db.queryForList("SELECT tenant_id,case_id FROM fcr_case_worker_cleanup WHERE eligible_at<=? ORDER BY requested_at,tenant_id,case_id LIMIT 1",clock.millis());
    if(pending.isEmpty())return;var value=pending.get(0);String tenant=(String)value.get("tenant_id"),caseId=(String)value.get("case_id");
    try {ObjectNode ack=worker.forgetCaseJobs(mapper.createObjectNode().put("tenantId",tenant).put("caseId",caseId));
      if(ack==null || !ack.path("tenantId").asText().equals(tenant) || !ack.path("caseId").asText().equals(caseId) || !ack.path("forgotten").isBoolean() || !ack.path("forgotten").asBoolean())throw UatService.invalidWorker();
      db.update("DELETE FROM fcr_case_worker_cleanup WHERE tenant_id=? AND case_id=?",tenant,caseId);}
    catch(RuntimeException unavailable){db.update("UPDATE fcr_case_worker_cleanup SET attempts=attempts+1,eligible_at=? WHERE tenant_id=? AND case_id=?",clock.millis()+30_000,tenant,caseId);}
  }

  private ObjectNode replay(Actor actor,String caseId,String key,String requestHash) {
    var rows=db.queryForList("SELECT request_hash,summary FROM fcr_case_investigation WHERE tenant_id=? AND actor_id=? AND case_id=? AND idempotency_key=?",actor.tenantId(),actor.id(),caseId,key);
    if(rows.isEmpty())return null;
    if(!requestHash.equals(rows.get(0).get("request_hash")))throw new ApiException(409,"CASE_QUESTION_KEY_CONFLICT","This retry key belongs to a different question or evidence version. Start a new run.");
    return summary(stored((String)rows.get(0).get("summary")));
  }
  private void lockCase(Actor actor,String caseId){if(db.query("SELECT id FROM fcr_payment_case WHERE tenant_id=? AND id=? FOR UPDATE",(rs,n)->rs.getString(1),actor.tenantId(),caseId).isEmpty())throw ApiException.notFound();cases.caseDetail(actor,caseId);}
  private ObjectNode lockedJob(String tenant,String caseId,String id){
    if(db.queryForList("SELECT id FROM fcr_payment_case WHERE tenant_id=? AND id=? FOR UPDATE",String.class,tenant,caseId).isEmpty())throw ApiException.notFound();
    return queryJob(tenant,caseId,id,true);
  }
  private ObjectNode load(String tenant,String caseId,String id){return queryJob(tenant,caseId,id,false);}
  private ObjectNode queryJob(String tenant,String caseId,String id,boolean lock){var rows=db.query("SELECT body FROM fcr_case_investigation WHERE tenant_id=? AND case_id=? AND id=?"+(lock?" FOR UPDATE":""),(rs,n)->stored(rs.getString(1)),tenant,caseId,id);if(rows.isEmpty())throw ApiException.notFound();ObjectNode job=rows.get(0);if(!job.path("id").asText().equals(id) || !job.path("caseId").asText().equals(caseId))throw storageFailure();return job;}
  private void update(String tenant,String caseId,ObjectNode job){setTiming(job);db.update("UPDATE fcr_case_investigation SET status=?,summary=?,body=? WHERE tenant_id=? AND case_id=? AND id=?",job.path("status").asText(),summary(job).toString(),job.toString(),tenant,caseId,job.path("id").asText());CaseHistoryIndex.recordJob(mapper,db,tenant,summary(job));}
  private void verifyStored(ObjectNode job){
    if(job.path("queueVersion").asText().equals("case-job-v1")) {
      var rows=db.queryForList("SELECT case_id,evidence_id,actor_id,request_hash FROM fcr_case_investigation WHERE id=?",job.path("id").asText());
      if(rows.size()!=1)throw storageFailure();var row=rows.get(0);
      ObjectNode admitted=mapper.createObjectNode();for(String field:List.of("question","evidenceId","evidenceHash"))admitted.set(field,job.path(field).deepCopy());
      if(!job.path("caseId").asText().equals(row.get("case_id")) || !job.path("evidenceId").asText().equals(row.get("evidence_id"))
          || !job.path("createdBy").asText().equals(row.get("actor_id")) || !UatService.canonicalHash(admitted).equals(row.get("request_hash")))throw storageFailure();
    }
    if(job.path("queueVersion").asText().equals("case-job-v1") && !job.has("input")) {
      if(job.has("answer") || job.path("workerSubmissionStarted").asBoolean() || !job.path("knowledgeVersion").asText().matches("[a-f0-9]{64}"))throw storageFailure();
      return;
    }
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
  private ObjectNode summary(ObjectNode job){return historySummary(mapper,job);}
  static ObjectNode historySummary(ObjectMapper mapper,ObjectNode job){ObjectNode result=job.deepCopy();result.remove(List.of("input","answer"));setTiming(mapper,result);return result;}
  private void setTiming(ObjectNode job) {setTiming(mapper,job);}
  private static void setTiming(ObjectMapper mapper,ObjectNode job) {
    boolean requestMeasured=job.hasNonNull("requestedAt");
    boolean terminal=Set.of("COMPLETED","FAILED","CANCELLED").contains(job.path("status").asText());
    JsonNode finished=terminal?job.get("finishedAt"):null;
    ObjectNode timing=mapper.createObjectNode().put("totalBasis",requestMeasured?"request-received":"job-created");
    timing.put("preparationMs",elapsed(job.get("requestedAt"),job.get("createdAt")));
    timing.put("queueMs",elapsed(job.get("createdAt"),job.hasNonNull("startedAt")?job.get("startedAt"):finished));
    timing.put("processingMs",elapsed(job.get("startedAt"),finished));
    timing.put("totalMs",elapsed(job.get(requestMeasured?"requestedAt":"createdAt"),finished));
    job.set("timing",timing);
    if(job.path("queueVersion").asText().equals("case-job-v1")) {
      ObjectNode phases=mapper.createObjectNode();
      phases.put("admissionMs",elapsed(job.get("requestedAt"),job.get("createdAt")));
      phases.put("preparationMs",elapsed(job.get("preparationStartedAt"),job.hasNonNull("preparedAt")?job.get("preparedAt"):finished));
      phases.put("workerWaitAndGenerationMs",elapsed(job.get("preparedAt"),finished));
      job.set("phaseTiming",phases);
    }
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
  @Override @PreDestroy public void close(){
    accepting=false;if(scheduler!=null)scheduler.shutdownNow();executor.shutdownNow();coordinator.close();
  }
}
