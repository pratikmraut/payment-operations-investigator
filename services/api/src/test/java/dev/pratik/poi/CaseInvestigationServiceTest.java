package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Original synthetic evidence and a mocked model; never reads UAT exports or calls inference. */
class CaseInvestigationServiceTest {
  final ObjectMapper mapper = new ObjectMapper();
  final Actor analyst = new Actor("analyst", "Analyst", "ANALYST", "northstar");
  final Actor viewer = new Actor("viewer", "Viewer", "VIEWER", "northstar");
  final Actor reviewer = new Actor("reviewer", "Reviewer", "REVIEWER", "northstar");
  final Actor other = new Actor("other", "Other", "ANALYST", "silverline");
  JdbcTemplate db; TransactionTemplate tx;
  PaymentDiscoveryService cases; CaseEvidenceService evidence; CaseEvidenceProjection projection;
  UatWorkerClient worker; CaseInvestigationService service; ManualExecutor executor;
  ObjectNode item, snapshot; String caseId;
  @TempDir Path temporary;
  AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-09-15T10:00:00Z"));
  Clock clock=mock(Clock.class);
  Map<String,ObjectNode> receipts=new LinkedHashMap<>(), envelopes=new LinkedHashMap<>();
  List<String> generated=new ArrayList<>();boolean autoComplete=true,cancelAcknowledged=true;
  Consumer<ObjectNode> mutateAnswer=a->{};


  @BeforeEach void setup() {
    var source = new DriverManagerDataSource("jdbc:h2:mem:case-investigation-" + UUID.randomUUID()
        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
    db = new JdbcTemplate(source); tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    var discovery = new PaymentDiscoveryClient(mapper, "MOCK", false, "", "TEST-UAT", 2,
        (u,b,t) -> { throw new AssertionError("No bank call is allowed in an investigation test"); });
    cases = spy(new PaymentDiscoveryService(mapper, db, tx, discovery, "northstar:1352:760,silverline:2468:760"));
    var found = cases.search(analyst, bytes(mapper.createObjectNode().put("orgBank", "760").put("orgBranch", "1352")
        .put("inquiryDate", "2026-09-14").put("recordCount", 20)));
    var created = cases.createCase(analyst, bytes(mapper.createObjectNode().put("candidateId", found.path("items").get(0).path("candidateId").asText())
        .put("reason", "Original synthetic question context")), "question-case-setup");
    item = (ObjectNode) created.path("item"); caseId = item.path("id").asText();
    evidence = new CaseEvidenceService(mapper, db, tx, cases, new CaseEvidenceClient(mapper, false, "", 15, "",
        (u,b,h,t) -> { throw new AssertionError("No bank evidence call is allowed"); }));
    snapshot = evidence.submit(analyst, caseId, bytes(payload("RAW-TEST-A")), "JSON", "original-evidence");
    projection = new CaseEvidenceProjection(mapper, ""); worker = mock(UatWorkerClient.class);
    when(clock.instant()).thenAnswer(call->now.get());when(clock.millis()).thenAnswer(call->now.get().toEpochMilli());
    when(worker.preflightCase(any())).thenReturn(mapper.createObjectNode().put("schemaVersion","case-context-readiness-v1").put("ready",true));
    doAnswer(call->{ObjectNode id=call.getArgument(0);ObjectNode receipt=receipts.get(id.path("jobId").asText());if(receipt==null)throw new ApiException(404,"CASE_WORKER_JOB_NOT_FOUND","No receipt");return receipt.deepCopy();}).when(worker).caseJobStatus(any());
    doAnswer(call->submit(call.getArgument(0))).when(worker).submitCaseJob(any());
    doAnswer(call->{ObjectNode id=call.getArgument(0);ObjectNode receipt=receipts.computeIfAbsent(id.path("jobId").asText(),key->id.deepCopy().put("status","CANCELLED"));
      receipt.put("cancellationRequested",true);if(cancelAcknowledged){receipt.put("status","CANCELLED");receipt.remove("answer");}return receipt.deepCopy();}).when(worker).cancelCaseJob(any());
    doAnswer(call->{ObjectNode scope=call.getArgument(0);return scope.deepCopy().put("forgotten",true);}).when(worker).forgetCaseJobs(any());
    executor = new ManualExecutor(); service = make(executor);
  }
  @AfterEach void close() { service.close(); }
  CaseInvestigationService make(ExecutorService queue) { return new CaseInvestigationService(mapper, db, tx, cases, evidence, projection, worker, queue, clock); }
  ObjectNode submit(ObjectNode envelope) {
    String id=envelope.path("jobId").asText();
    if(!receipts.containsKey(id)) {
      generated.add(id);envelopes.put(id,envelope.deepCopy());ObjectNode receipt=envelope.deepCopy();receipt.remove("input");
      receipt.put("status",autoComplete?"COMPLETED":"RUNNING").put("cancellationRequested",false);
      if(autoComplete){ObjectNode result=answer((ObjectNode)envelope.path("input"));mutateAnswer.accept(result);receipt.set("answer",result);}
      receipts.put(id,receipt);
    }
    return receipts.get(id).deepCopy();
  }
  void complete(ObjectNode job) {String id=job.path("id").asText();ObjectNode receipt=receipts.get(id);receipt.put("status","COMPLETED");receipt.set("answer",answer((ObjectNode)envelopes.get(id).path("input")));}
  void tick(){if(executor.pending()>0)executor.runNext();else service.dispatchOnce();}
  void advance(long millis){now.set(now.get().plusMillis(millis));}
  void restart(){service.close();executor=new ManualExecutor();service=make(executor);service.recoverInterrupted();}
  ObjectNode cancel(ObjectNode job){return service.cancel(analyst,caseId,job.path("id").asText(),bytes(mapper.createObjectNode()));}
  ObjectNode timing(Long preparation, Long queue, Long processing, Long total, String basis) {
    return mapper.createObjectNode().put("totalBasis", basis).put("preparationMs", preparation)
        .put("queueMs", queue).put("processingMs", processing).put("totalMs", total);
  }
  byte[] bytes(JsonNode value) { return value.toString().getBytes(StandardCharsets.UTF_8); }
  ObjectNode payload(String rawStatus) {
    ObjectNode payload = evidence.template(item);
    ObjectNode row = ((ArrayNode) payload.path("sections").path("PAYMENT").path("rows")).addObject();
    CaseEvidenceSchema.COLUMNS.get("PAYMENT").forEach(key -> row.put(key, ""));
    row.put("REFTXNNUMBER", item.path("reference").asText()).put("SOURCE_TABLE", CaseEvidenceSchema.SOURCE_TABLES.get("PAYMENT"))
        .put("SCOPE_ROW_COUNT", "1").put("QUERY_OBSERVED_AT", "2026-09-14T01:02:03+05:30")
        .put("DATINITIATION", "2026-09-14T01:01:01").put("NUMAMOUNT_4038", "123.007").put("N10_STATUS", rawStatus);
    return payload;
  }
  ObjectNode question() { return question(snapshot, "What does the supplied observation establish?"); }
  ObjectNode question(ObjectNode version, String text) { return mapper.createObjectNode().put("question", text)
      .put("evidenceId", version.path("id").asText()).put("evidenceHash", version.path("evidenceHash").asText()); }
  ObjectNode start(String key) { return service.start(analyst, caseId, bytes(question()), key); }
  ObjectNode detail(ObjectNode job) { return service.detail(analyst, caseId, job.path("id").asText()); }
  int jobs() { return db.queryForObject("SELECT COUNT(*) FROM fcr_case_investigation", Integer.class); }
  void status(int expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status).isEqualTo(expected));
  }
  ObjectNode stored(ObjectNode job) throws Exception { return (ObjectNode) mapper.readTree(db.queryForObject(
      "SELECT body FROM fcr_case_investigation WHERE id=?", String.class, job.path("id").asText())); }
  void replaceStored(ObjectNode job) {
    ObjectNode summary = job.deepCopy(); summary.remove(List.of("input", "answer"));
    db.update("UPDATE fcr_case_investigation SET status=?,summary=?,body=? WHERE id=?",
        job.path("status").asText(), summary.toString(), job.toString(), job.path("id").asText());
  }
  ObjectNode answer(ObjectNode input) {
    String documentId = input.path("documents").get(0).path("id").asText();
    ObjectNode response = mapper.createObjectNode().put("answerId", "ORIGINAL-TEST-" + UUID.randomUUID())
        .put("question", input.path("question").asText()).put("snapshotId", input.path("snapshotId").asText())
        .put("evidenceHash", input.path("evidenceHash").asText()).put("answer", "This original test observation leaves the outcome unknown.")
        .put("answerComposition", "joined-model-claims").put("mode", "model-generated")
        .put("validation", "structure-and-source-membership-only").put("generatedAt", Instant.now().toString());
    response.putArray("claims").addObject().put("text", response.path("answer").asText()).putArray("evidenceIds").add(documentId);
    response.putArray("unknowns").add("The outcome is not established in this synthetic test.");
    response.putArray("nextChecks").add("Review the original source observation.");
    response.putArray("citations").add(input.path("documents").get(0).deepCopy());
    response.putObject("retrieval").put("method", "test-supplied-documents").putArray("documentIds").add(documentId);
    response.putObject("model").put("provider", "ollama").put("name", "mocked-contract-test")
        .put("actualCalls", 1).put("promptTokens", 123).put("completionTokens", 45).put("durationMs", 17);
    ObjectNode rag=response.putObject("rag").put("pipeline",CaseAnswerValidator.PIPELINE).put("promptHash","b".repeat(64));
    rag.set("checks",mapper.valueToTree(CaseAnswerValidator.CHECKS));
    rag.putArray("claimSupports").addObject().put("claimIndex",0).put("claimType","limitation").putArray("fields");
    return response;
  }

  @Test void admissionIsDurableBeforeRetrievalAndReadsNeverDispatch() throws Exception {
    projection=spy(projection);restart();ObjectNode job=start("durable-question");
    assertThat(job.path("status").asText()).isEqualTo("QUEUED");assertThat(jobs()).isEqualTo(1);
    assertThat(stored(job).has("input")).isFalse();assertThat(detail(job).path("documents")).isEmpty();
    assertThat(stored(job).path("knowledgeVersion").asText()).hasSize(64);
    var workbench=service.workbench(viewer,caseId);
    assertThat(workbench.path("investigations").get(0).toString()).isEqualTo(job.toString());
    assertThat(workbench.path("audit").toString()).contains("INVESTIGATION_REQUESTED");
    verify(projection,never()).project(any(),any(),any(),anyString());verifyNoInteractions(worker);
    tick();assertThat(detail(job).path("status").asText()).isEqualTo("COMPLETED");
    verify(projection,times(1)).project(any(),any(),any(),anyString());verify(worker,never()).answerCase(any());
    assertThat(stored(job).path("inputHash").asText()).isEqualTo(UatService.canonicalHash(stored(job).path("input")));
  }
  @Test void readinessIsLocalReadOnlyAndPinsEvidenceAndKnowledge() {
    ObjectNode ready=service.readiness(viewer,caseId,bytes(question()));
    assertThat(ready.path("ready").asBoolean()).isTrue();assertThat(ready.path("caseId").asText()).isEqualTo(caseId);
    assertThat(ready.path("evidenceHash")).isEqualTo(snapshot.path("evidenceHash"));assertThat(jobs()).isZero();
    verifyNoInteractions(worker);
  }
  @Test void endToEndTimingSeparatesAdmissionQueueRetrievalAndWorkerAndFreezes() throws Exception {
    projection=spy(projection);restart();
    doAnswer(call->{ObjectNode result=(ObjectNode)call.callRealMethod();advance(2500);return result;}).when(projection).project(any(),any(),any(),anyString());
    doAnswer(call->{advance(8500);return submit(call.getArgument(0));}).when(worker).submitCaseJob(any());
    ObjectNode job=start("timing-durable-question");advance(3250);tick();ObjectNode result=detail(job),body=stored(job);
    assertThat(result.path("timing")).isEqualTo(timing(0L,3250L,11000L,14250L,"request-received"));
    assertThat(result.path("phaseTiming").path("preparationMs").asLong()).isEqualTo(2500);
    assertThat(result.path("phaseTiming").path("workerWaitAndGenerationMs").asLong()).isEqualTo(8500);
    assertThat(result.path("answer").path("model").path("durationMs").asLong()).isEqualTo(17);
    advance(86400000);assertThat(detail(job)).isEqualTo(result);assertThat(stored(job)).isEqualTo(body);
    assertThat(start("timing-durable-question").path("timing")).isEqualTo(result.path("timing"));
  }
  @Test void originalAnswerAndSourcesRemainBoundToOlderEvidence() throws Exception {
    ObjectNode job=start("original-version-question");tick();ObjectNode before=stored(job);
    ObjectNode newer=evidence.submit(analyst,caseId,bytes(payload("RAW-TEST-B")),"JSON","newer-version-question");
    assertThat(detail(job).path("evidenceId")).isEqualTo(snapshot.path("id"));
    assertThat(detail(job).path("answer").path("snapshotId")).isEqualTo(snapshot.path("id"));
    assertThat(service.workbench(viewer,caseId).path("latestEvidenceId")).isEqualTo(newer.path("id"));
    assertThat(stored(job)).isEqualTo(before);assertThat(evidence.detail(analyst,caseId,snapshot.path("id").asText())).isEqualTo(snapshot);
  }
  @Test void idempotentRetryAndConflictingCommandDoNotQueueTwice() {
    ObjectNode job=start("same-request-key");assertThat(start("same-request-key").path("id")).isEqualTo(job.path("id"));
    status(409,()->service.start(analyst,caseId,bytes(question(snapshot,"A different question")),"same-request-key"));
    assertThat(jobs()).isEqualTo(1);tick();assertThat(start("same-request-key").path("status").asText()).isEqualTo("COMPLETED");
    assertThat(generated).hasSize(1);
  }
  @Test void concurrentSameCommandPersistsOnlyOneDurableJob() throws Exception {
    ExecutorService callers=Executors.newFixedThreadPool(2);CountDownLatch go=new CountDownLatch(1);
    try{Callable<ObjectNode> call=()->{go.await();return start("concurrent-question");};
      Future<ObjectNode> first=callers.submit(call),second=callers.submit(call);go.countDown();
      assertThat(first.get(5,TimeUnit.SECONDS).path("id")).isEqualTo(second.get(5,TimeUnit.SECONDS).path("id"));
      assertThat(jobs()).isEqualTo(1);assertThat(executor.pending()).isEqualTo(1);verifyNoInteractions(worker);
    }finally{callers.shutdownNow();}
  }
  @Test void preflightOutageRetainsPreparedInputAndResumesWithoutPreparingTwice() throws Exception {
    projection=spy(projection);restart();when(worker.preflightCase(any())).thenThrow(new ApiException(503,"UAT_MODEL_UNAVAILABLE","PRIVATE"))
        .thenReturn(mapper.createObjectNode().put("schemaVersion","case-context-readiness-v1").put("ready",true));
    ObjectNode job=start("preflight-unavailable");tick();ObjectNode frozen=stored(job);
    assertThat(detail(job).path("phase").asText()).isEqualTo("WAITING");assertThat(detail(job).toString()).doesNotContain("PRIVATE");
    tick();verify(worker,times(1)).preflightCase(any());advance(5000);tick();
    assertThat(detail(job).path("status").asText()).isEqualTo("COMPLETED");assertThat(stored(job).path("input")).isEqualTo(frozen.path("input"));
    verify(projection,times(1)).project(any(),any(),any(),anyString());assertThat(generated).hasSize(1);
  }
  @Test void uncertainSubmitAndApiRestartReuseCompletedWorkerReceiptWithoutDuplicateGeneration() throws Exception {
    doAnswer(call->{submit(call.getArgument(0));throw new ApiException(504,"UAT_MODEL_TIMEOUT","PRIVATE late response");}).when(worker).submitCaseJob(any());
    ObjectNode job=start("uncertain-submit-question");tick();ObjectNode frozen=stored(job);
    assertThat(detail(job).path("phase").asText()).isEqualTo("WAITING");restart();advance(5000);tick();
    assertThat(detail(job).path("status").asText()).isEqualTo("COMPLETED");assertThat(stored(job).path("input")).isEqualTo(frozen.path("input"));
    assertThat(generated).hasSize(1);verify(worker,times(1)).submitCaseJob(any());
  }
  @Test void stoppedExecutorLeavesQueuedJobRecoverableAfterRestart() {
    executor.shutdown();ObjectNode job=start("stopped-executor-question");
    assertThat(detail(job).path("status").asText()).isEqualTo("QUEUED");verifyNoInteractions(worker);
    restart();tick();assertThat(detail(job).path("status").asText()).isEqualTo("COMPLETED");
  }
  @Test void queuedCancellationIsImmediateIdempotentAndNeverCallsWorker() {
    ObjectNode job=start("cancel-queued-question");ObjectNode cancelled=cancel(job);
    assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");assertThat(cancelled.hasNonNull("finishedAt")).isTrue();
    assertThat(cancel(job)).isEqualTo(cancelled);tick();verifyNoInteractions(worker);
    assertThat(service.workbench(viewer,caseId).path("activeInvestigations")).isEmpty();
    assertThat(service.workbench(viewer,caseId).path("audit").toString()).contains("INVESTIGATION_CANCELLATION_REQUESTED","INVESTIGATION_CANCELLED");
  }
  @Test void runningCancellationStaysActiveUntilAcknowledgedAndDiscardsCompletedAnswer() {
    autoComplete=false;cancelAcknowledged=false;ObjectNode job=start("cancel-running-question");tick();
    assertThat(cancel(job).path("phase").asText()).isEqualTo("CANCELLING");tick();
    assertThat(detail(job).path("status").asText()).isEqualTo("RUNNING");assertThat(service.workbench(viewer,caseId).path("activeInvestigations")).hasSize(1);
    complete(job);doAnswer(call->receipts.get(job.path("id").asText()).deepCopy()).when(worker).cancelCaseJob(any());tick();
    assertThat(detail(job).path("status").asText()).isEqualTo("CANCELLED");assertThat(detail(job).has("answer")).isFalse();
  }
  @Test void cancellationRacingFinalValidationCannotAttachCompletedAnswer() {
    autoComplete=false;ObjectNode job=start("cancel-finish-race");tick();complete(job);
    java.util.concurrent.atomic.AtomicInteger readNumber=new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(call->{if(readNumber.incrementAndGet()==3)cancel(job);return call.callRealMethod();}).when(cases).caseDetail(argThat(a->a!=null&&a.id().equals("analyst")),eq(caseId));
    tick();doCallRealMethod().when(cases).caseDetail(any(),eq(caseId));
    assertThat(detail(job).path("status").asText()).isEqualTo("CANCELLED");assertThat(detail(job).has("answer")).isFalse();
  }
  @Test void missingCancellationReceiptIsUncertainAndCannotReleaseActiveGuard() {
    autoComplete=false;ObjectNode job=start("cancel-missing-worker-receipt");tick();cancel(job);
    doThrow(new ApiException(404,"CASE_WORKER_JOB_NOT_FOUND","Old worker no receipt")).when(worker).cancelCaseJob(any());tick();
    assertThat(detail(job).path("status").asText()).isEqualTo("RUNNING");assertThat(detail(job).path("phase").asText()).isEqualTo("CANCELLING");
    assertThat(detail(job).path("waitingReason").path("code").asText()).isEqualTo("CASE_WORKER_CANCEL_UNCONFIRMED");
  }
  @Test void cancellationRequiresAuthorOrReviewerAndScopedWriter() {
    ObjectNode job=service.start(reviewer,caseId,bytes(question()),"reviewer-owned-question");
    status(403,()->cancel(job));status(403,()->service.cancel(viewer,caseId,job.path("id").asText(),bytes(mapper.createObjectNode())));
    status(404,()->service.cancel(other,caseId,job.path("id").asText(),bytes(mapper.createObjectNode())));
    assertThat(service.cancel(reviewer,caseId,job.path("id").asText(),bytes(mapper.createObjectNode())).path("status").asText()).isEqualTo("CANCELLED");
    ObjectNode own=start("analyst-owned-question");assertThat(service.cancel(reviewer,caseId,own.path("id").asText(),bytes(mapper.createObjectNode())).path("status").asText()).isEqualTo("CANCELLED");
  }
  @Test void actorTurnsAreFairAndPerActorQueueLimitReturnsCapacity() {
    ObjectNode first=start("fair-analyst-first");advance(1);ObjectNode second=start("fair-analyst-second");advance(1);
    start("fair-analyst-third");start("fair-analyst-fourth");status(429,()->start("fair-overflow-question"));
    ObjectNode review=service.start(reviewer,caseId,bytes(question()),"fair-reviewer-question");
    tick();tick();tick();assertThat(generated).containsExactly(first.path("id").asText(),review.path("id").asText(),second.path("id").asText());
    assertThat(start("fair-capacity-returned").path("status").asText()).isEqualTo("QUEUED");
  }
  @Test void globalCapacityIncludesOtherActorsAndQueuedJobs() throws Exception {
    ObjectNode source=start("global-capacity-original"),body=stored(source);
    for(int i=1;i<32;i++){ObjectNode copy=body.deepCopy().put("id","CIN-CAPACITY-"+i).put("createdBy","fixture-actor-"+i);
      db.update("INSERT INTO fcr_case_investigation(id,tenant_id,case_id,evidence_id,created_at,actor_id,idempotency_key,request_hash,status,summary,body) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
          copy.path("id").asText(),"northstar",caseId,snapshot.path("id").asText(),now.get().toString(),copy.path("createdBy").asText(),"capacity-fixture-"+i,"f".repeat(64),"QUEUED",copy.toString(),copy.toString());}
    status(429,()->service.start(reviewer,caseId,bytes(question()),"global-capacity-rejected"));assertThat(jobs()).isEqualTo(32);
  }
  @Test void leaseHeartbeatAndFencingPreventStaleInstanceWrites() {
    start("leased-question");CaseJobCoordinator first=new CaseJobCoordinator(db,tx,clock),second=new CaseJobCoordinator(db,tx,clock);
    var claim=first.claim();assertThat(claim).isNotNull();assertThat(second.claim()).isNull();advance(20000);
    assertThat(first.heartbeat(claim)).isTrue();advance(20000);assertThat(second.claim()).isNull();advance(10001);
    var successor=second.claim();assertThat(successor.jobId()).isEqualTo(claim.jobId());assertThat(successor.fence()).isGreaterThan(claim.fence());
    assertThat(first.fenced(claim,()->"stale write")).isNull();assertThat(second.fenced(successor,()->"current write")).isEqualTo("current write");
  }
  @Test void changedKnowledgeAfterAdmissionFailsBeforeRetrievalOrWorkerCall() {
    projection=spy(projection);restart();ObjectNode job=start("knowledge-revision-question");
    doAnswer(call->{ObjectNode ready=(ObjectNode)call.callRealMethod();return ready.put("knowledgeVersion","a".repeat(64));}).when(projection).readiness(any(),any(),any(),anyString());
    tick();assertThat(detail(job).path("error").path("code").asText()).isEqualTo("CASE_KNOWLEDGE_CHANGED");
    verify(projection,never()).project(any(),any(),any(),anyString());verifyNoInteractions(worker);
  }
  @Test void exactPreflightContextLimitRejectsWithoutGeneration() {
    when(worker.preflightCase(any())).thenReturn(mapper.createObjectNode().put("schemaVersion","case-context-readiness-v1").put("ready",false));
    ObjectNode job=start("exact-context-rejected");tick();assertThat(detail(job).path("error").path("code").asText()).isEqualTo("CASE_CONTEXT_LIMIT");
    verify(worker,never()).submitCaseJob(any());verify(worker,never()).caseJobStatus(any());
  }
  @Test void queuedQuestionTamperingIsDetectedAgainstAdmissionHashBeforePreparation() throws Exception {
    ObjectNode job=start("tampered-admission-question"),body=stored(job);body.put("question","Tampered before preparation");replaceStored(body);
    status(503,()->detail(job));tick();assertThat(stored(job).path("status").asText()).isEqualTo("FAILED");verifyNoInteractions(worker);
  }
  @Test void frozenInputTamperingCannotBeResumedOrRead() throws Exception {
    autoComplete=false;ObjectNode job=start("tampered-prepared-input");tick();ObjectNode body=stored(job);
    ((ObjectNode)body.path("input")).put("question","Tampered after preparation");replaceStored(body);status(503,()->detail(job));tick();
    assertThat(stored(job).has("answer")).isFalse();assertThat(generated).hasSize(1);
  }
  @Test void changedSavedEvidenceBeforePreparationDoesNotInvokeWorker() throws Exception {
    ObjectNode job=start("changed-evidence-question"),changed=snapshot.deepCopy();changed.put("version",99);
    db.update("UPDATE fcr_case_evidence SET body=? WHERE id=?",changed.toString(),snapshot.path("id").asText());tick();
    assertThat(stored(job).path("error").path("code").asText()).isEqualTo("CASE_INVESTIGATION_EVIDENCE_CHANGED");verifyNoInteractions(worker);
  }
  @Test void changedEvidenceDuringGenerationRequiresCancellationAcknowledgement() throws Exception {
    autoComplete=false;cancelAcknowledged=false;ObjectNode job=start("changed-active-evidence");tick();
    ObjectNode changed=snapshot.deepCopy().put("version",99);db.update("UPDATE fcr_case_evidence SET body=? WHERE id=?",changed.toString(),snapshot.path("id").asText());
    tick();assertThat(stored(job).path("phase").asText()).isEqualTo("CANCELLING");tick();assertThat(stored(job).path("status").asText()).isEqualTo("RUNNING");
    cancelAcknowledged=true;tick();assertThat(stored(job).path("status").asText()).isEqualTo("FAILED");assertThat(stored(job).has("answer")).isFalse();
  }
  @Test void malformedAnswersAndUncitedConclusionsAreNeverSaved() {
    int index=0;for(Consumer<ObjectNode> edit:List.<Consumer<ObjectNode>>of(a->a.put("snapshotId","wrong"),
        a->((ObjectNode)a.path("claims").get(0)).putArray("evidenceIds").add("invented"),
        a->((ObjectNode)a.path("citations").get(0)).put("content","Changed source"),
        a->((ObjectNode)a.path("model")).put("actualCalls",0),a->a.put("answer","Unsupported uncited conclusion"))) {
      mutateAnswer=edit;ObjectNode job=start("malformed-receipt-"+index++);tick();
      assertThat(detail(job).path("status").asText()).isEqualTo("FAILED");assertThat(detail(job).has("answer")).isFalse();
      assertThat(detail(job).path("error").path("code").asText()).isEqualTo("INVALID_UAT_ANSWER");
    }
  }
  @Test void receiptIdentityMismatchCannotBecomeACompletedAnswer() {
    doAnswer(call->{ObjectNode result=submit(call.getArgument(0));return result.put("caseId","different-case");}).when(worker).submitCaseJob(any());
    ObjectNode job=start("receipt-identity-mismatch");tick();assertThat(detail(job).path("status").asText()).isEqualTo("FAILED");assertThat(detail(job).has("answer")).isFalse();
  }
  @Test void oldCompletedAnswersAndTimingStayReadableWithoutRewriting() throws Exception {
    ObjectNode job=start("legacy-readable-question");tick();ObjectNode body=stored(job);
    body.remove(List.of("queueVersion","answerContract","requestedAt","timing","phaseTiming"));((ObjectNode)body.path("answer")).remove("rag");
    body.put("createdAt","2026-09-15T10:00:00Z").put("startedAt","2026-09-15T10:00:03Z").put("finishedAt","2026-09-15T10:00:12Z");replaceStored(body);
    Map<String,Object> before=db.queryForMap("SELECT summary,body FROM fcr_case_investigation WHERE id=?",job.path("id").asText());
    assertThat(detail(job).path("timing")).isEqualTo(timing(null,3000L,9000L,12000L,"job-created"));restart();
    assertThat(detail(job).path("answer")).isEqualTo(body.path("answer"));
    assertThat(db.queryForMap("SELECT summary,body FROM fcr_case_investigation WHERE id=?",job.path("id").asText())).isEqualTo(before);
  }
  @Test void legacyInterruptedJobsFailWithoutRetryButDurableQueuedJobsSurviveRestart() throws Exception {
    autoComplete=false;ObjectNode old=start("legacy-active-question");tick();ObjectNode body=stored(old);body.remove("queueVersion");replaceStored(body);
    ObjectNode durable=start("durable-queued-restart");restart();
    assertThat(detail(old).path("error").path("code").asText()).isEqualTo("CASE_INVESTIGATION_INTERRUPTED");
    assertThat(detail(durable).path("status").asText()).isEqualTo("QUEUED");autoComplete=true;tick();
    assertThat(detail(durable).path("status").asText()).isEqualTo("COMPLETED");assertThat(generated).hasSize(2);
  }
  @Test void cleanupOutboxRetriesUnverifiedAckAndRequiresMatchingTerminalPurgeReceipt() {
    db.update("INSERT INTO fcr_case_worker_cleanup(tenant_id,case_id,requested_at,attempts,eligible_at) VALUES(?,?,?,0,0)","northstar","FCR-DELETED-FIXTURE",now.get().toString());
    doReturn(mapper.createObjectNode(),mapper.createObjectNode().put("tenantId","northstar").put("caseId","FCR-DELETED-FIXTURE").put("forgotten",true)).when(worker).forgetCaseJobs(any());
    tick();assertThat(db.queryForObject("SELECT attempts FROM fcr_case_worker_cleanup",Integer.class)).isEqualTo(1);
    advance(30000);tick();assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_case_worker_cleanup",Integer.class)).isZero();
  }
  @Test void writerTenantAndSavedVersionScopeChecksRunBeforeDispatch() {
    status(403,()->service.start(viewer,caseId,bytes(question()),"viewer-question"));status(404,()->service.start(other,caseId,bytes(question()),"other-question"));
    status(404,()->service.workbench(other,caseId));status(404,()->service.context(other,caseId,snapshot.path("id").asText()));
    status(404,()->service.start(analyst,caseId,bytes(question().put("evidenceId","EVD-missing")),"missing-evidence"));
    status(409,()->service.start(analyst,caseId,bytes(question().put("evidenceHash","0".repeat(64))),"changed-fingerprint"));verifyNoInteractions(worker);
  }
  @Test void strictQuestionBoundsAndEmptyEvidenceRejectWithoutJobs() {
    for(String body:List.of("{}","[]","null","{\"question\":\"x\",\"question\":\"y\"}",question()+" {}"))
      status(422,()->service.start(analyst,caseId,body.getBytes(StandardCharsets.UTF_8),"invalid-question"));
    for(Consumer<ObjectNode> edit:List.<Consumer<ObjectNode>>of(q->q.put("question","x".repeat(2001)),q->q.put("question"," "),q->q.put("question",10),q->q.put("question","bad\0text"),q->q.put("documents","client-selected"),q->q.put("evidenceHash","invalid"))) {
      ObjectNode input=question();edit.accept(input);status(422,()->service.start(analyst,caseId,bytes(input),"invalid-question"));}
    for(String key:Arrays.asList(null,"short","has spaces"))status(400,()->service.start(analyst,caseId,bytes(question()),key));
    status(413,()->service.start(analyst,caseId,new byte[16385],"oversized-question"));
    ObjectNode empty=evidence.submit(analyst,caseId,bytes(evidence.template(item)),"JSON","empty-evidence");
    status(422,()->service.start(analyst,caseId,bytes(question(empty,"Is outcome known?")),"empty-question"));assertThat(jobs()).isZero();verifyNoInteractions(worker);
  }
  @Test void metadataPinFetchDoesNotLoadAnswerAndRejectsForeignScope() {
    ObjectNode job=start("pinned-metadata-question");tick();
    CaseHistoryService history=new CaseHistoryService(mapper,db,tx,cases);
    ObjectNode pin=history.investigationSummary(viewer,caseId,job.path("id").asText());
    assertThat(pin.path("status").asText()).isEqualTo("COMPLETED");assertThat(pin.path("createdBy").asText()).isEqualTo("analyst");
    assertThat(pin.has("answer")).isFalse();assertThat(pin.has("documents")).isFalse();assertThat(pin.has("input")).isFalse();
    status(404,()->history.investigationSummary(other,caseId,job.path("id").asText()));
    status(404,()->history.investigationSummary(viewer,caseId,"CIN-absent"));
  }
  @Test void startupAdmissionGateAndShutdownLeaveSavedDurableJobUntouched() throws Exception {
    CaseInvestigationService managed=new CaseInvestigationService(mapper,db,tx,cases,evidence,projection,worker);
    try {status(503,()->managed.start(analyst,caseId,bytes(question()),"managed-startup-question"));assertThat(jobs()).isZero();}
    finally{managed.close();}
    ObjectNode job=start("shutdown-durable-question");ObjectNode before=stored(job);service.close();
    assertThat(stored(job)).isEqualTo(before);status(503,()->start("closed-service-question"));
  }
  static final class ManualExecutor extends AbstractExecutorService {
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>(); private volatile boolean stopped;
    public void execute(Runnable task) { if(stopped)throw new RejectedExecutionException("Original test executor stopped"); tasks.add(task); }
    int pending() { return tasks.size(); }
    void runNext() { Runnable task = tasks.poll(); assertThat(task).isNotNull(); task.run(); }
    public void shutdown() { stopped = true; }
    public List<Runnable> shutdownNow() { stopped = true; List<Runnable> pending = new ArrayList<>(tasks); tasks.clear(); return pending; }
    public boolean isShutdown() { return stopped; }
    public boolean isTerminated() { return stopped && tasks.isEmpty(); }
    public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
  }
}
