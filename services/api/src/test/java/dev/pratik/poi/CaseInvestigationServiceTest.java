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
  final Actor other = new Actor("other", "Other", "ANALYST", "silverline");
  JdbcTemplate db; TransactionTemplate tx;
  PaymentDiscoveryService cases; CaseEvidenceService evidence; CaseEvidenceProjection projection;
  UatWorkerClient worker; CaseInvestigationService service; ManualExecutor executor;
  ObjectNode item, snapshot; String caseId;
  @TempDir Path temporary;

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
    doAnswer(call -> answer(call.getArgument(0))).when(worker).answerCase(any());
    executor = new ManualExecutor(); service = make(executor);
  }
  @AfterEach void close() { service.close(); }
  CaseInvestigationService make(ExecutorService queue) { return new CaseInvestigationService(mapper, db, tx, cases, evidence, projection, worker, queue); }
  AtomicReference<Instant> controlledTime() {
    AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-09-15T10:00:00Z"));
    Clock clock = mock(Clock.class); when(clock.instant()).thenAnswer(call -> time.get());
    service.close(); executor = new ManualExecutor();
    service = new CaseInvestigationService(mapper, db, tx, cases, evidence, projection, worker, executor, clock);
    return time;
  }
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

  @Test void queueIsDurableAndReloadsWithoutASecondDispatch() throws Exception {
    ObjectNode expectedInput = service.context(viewer, caseId, snapshot.path("id").asText());
    ObjectNode job = start("durable-question");
    assertThat(job.path("status").asText()).isEqualTo("QUEUED");
    assertThat(job.has("input")).isFalse(); assertThat(job.has("answer")).isFalse();
    assertThat(jobs()).isEqualTo(1); assertThat(executor.pending()).isEqualTo(1); verifyNoInteractions(worker);
    var workbench = service.workbench(viewer, caseId);
    assertThat(workbench.path("latestEvidenceId")).isEqualTo(snapshot.path("id"));
    assertThat(workbench.path("investigations").get(0)).isEqualTo(job);
    assertThat(workbench.path("audit").toString()).contains("CASE_OPENED", "EVIDENCE_ATTACHED", "INVESTIGATION_REQUESTED");
    assertThat(detail(job).path("documents")).isEqualTo(expectedInput.path("documents"));
    ObjectNode saved = stored(job);
    assertThat(saved.path("inputHash").asText()).isEqualTo(UatService.canonicalHash(saved.path("input")));
    assertThat(saved.path("input").path("documents")).isEqualTo(expectedInput.path("documents"));
    service.workbench(analyst, caseId); service.detail(viewer, caseId, job.path("id").asText());
    assertThat(executor.pending()).isEqualTo(1); verifyNoInteractions(worker);
  }

  @Test void elapsedTimeIncludesPreparationQueueAndProcessingAndStaysFrozenOnReplay() throws Exception {
    projection = spy(projection); AtomicReference<Instant> time = controlledTime(); Instant requested = time.get();
    doAnswer(call -> { ObjectNode projected = (ObjectNode)call.callRealMethod();
      time.set(time.get().plusMillis(2500)); return projected; }).when(projection).project(any(), any(), any(), anyString());
    ObjectNode queued = start("timed-investigation");
    assertThat(queued.path("requestedAt").asText()).isEqualTo(requested.toString());
    assertThat(queued.path("createdAt").asText()).isEqualTo(requested.plusMillis(2500).toString());
    assertThat(queued.path("timing")).isEqualTo(timing(2500L, null, null, null, "request-received"));
    assertThat(stored(queued).path("timing").toString()).isEqualTo(queued.path("timing").toString());
    time.set(requested.plusMillis(5750));
    doAnswer(call -> {
      assertThat(detail(queued).path("timing")).isEqualTo(timing(2500L, 3250L, null, null, "request-received"));
      assertThat(stored(queued).path("timing").toString()).isEqualTo(detail(queued).path("timing").toString());
      time.set(time.get().plusMillis(8500)); return answer(call.getArgument(0));
    }).when(worker).answerCase(any());
    executor.runNext(); ObjectNode completed = detail(queued), persisted = stored(queued);
    assertThat(completed.path("timing")).isEqualTo(timing(2500L, 3250L, 8500L, 14250L, "request-received"));
    assertThat(completed.path("answer").path("model").path("durationMs").asLong()).isEqualTo(17L);
    assertThat(persisted.path("timing").toString()).isEqualTo(completed.path("timing").toString());
    ObjectNode savedSummary = (ObjectNode)mapper.readTree(db.queryForObject(
        "SELECT summary FROM fcr_case_investigation WHERE id=?", String.class, queued.path("id").asText()));
    assertThat(savedSummary.path("timing").toString()).isEqualTo(completed.path("timing").toString());
    time.set(time.get().plusSeconds(86400));
    assertThat(start("timed-investigation").toString()).isEqualTo(savedSummary.toString());
    assertThat(service.workbench(viewer, caseId).path("investigations").get(0).toString()).isEqualTo(savedSummary.toString());
    assertThat(detail(queued)).isEqualTo(completed);
    assertThat(stored(queued)).isEqualTo(persisted);
    verify(worker, times(1)).answerCase(any());
  }

  @Test void legacyTimingIsDerivedFromJobCreationWithoutRewritingStoredRecords() throws Exception {
    ObjectNode job = start("legacy-timing-question"); executor.runNext(); ObjectNode legacy = stored(job);
    legacy.remove(List.of("requestedAt", "timing"));
    legacy.put("createdAt", "2026-09-15T10:00:00Z").put("startedAt", "2026-09-15T10:00:03Z")
        .put("finishedAt", "2026-09-15T10:00:12Z"); replaceStored(legacy);
    Map<String, Object> before = db.queryForMap("SELECT summary,body FROM fcr_case_investigation WHERE id=?", job.path("id").asText());
    ObjectNode expected = timing(null, 3000L, 9000L, 12000L, "job-created");
    for(JsonNode result : List.of(detail(job), start("legacy-timing-question"),
        service.workbench(viewer, caseId).path("investigations").get(0))) {
      assertThat(result.has("requestedAt")).isFalse(); assertThat(result.path("timing")).isEqualTo(expected);
    }
    assertThat(db.queryForMap("SELECT summary,body FROM fcr_case_investigation WHERE id=?", job.path("id").asText())).isEqualTo(before);
    assertThat(detail(job).path("answer")).isEqualTo(legacy.path("answer"));
    verify(worker, times(1)).answerCase(any());
  }

  @Test void invalidMissingReversedAndOverflowingTimestampRangesStayUnavailable() throws Exception {
    ObjectNode job = start("unavailable-timing"); executor.runNext(); ObjectNode completed = stored(job);
    for(String value : List.of("not-a-timestamp", "2026-09-15T10:00:04Z", "+1000000000-12-31T23:59:59.999999999Z")) {
      ObjectNode changed = completed.deepCopy();
      changed.put("requestedAt", value).put("createdAt", "2026-09-15T10:00:01Z")
          .put("startedAt", value).put("finishedAt", "2026-09-15T10:00:03Z"); replaceStored(changed);
      JsonNode timing = detail(job).path("timing");
      assertThat(timing.path("preparationMs").isNull()).isTrue();
      assertThat(timing.path("processingMs").isNull()).isTrue();
      assertThat(timing.path("totalMs").isNull()).isTrue();
    }
    ObjectNode changed = completed.deepCopy();
    changed.put("requestedAt", "-1000000000-01-01T00:00:00Z").put("createdAt", "2026-09-15T10:00:01Z")
        .put("startedAt", "2026-09-15T10:00:00Z").put("finishedAt", "2026-09-15T10:00:03Z"); replaceStored(changed);
    assertThat(detail(job).path("timing")).isEqualTo(timing(null, null, 3000L, null, "request-received"));
    changed.putNull("requestedAt").remove("startedAt"); changed.remove("finishedAt"); replaceStored(changed);
    assertThat(detail(job).path("timing")).isEqualTo(timing(null, null, null, null, "job-created"));
  }
  @Test void statusRetrievalIsQuestionOnlyAndFreezesKnowledgeAcrossCatalogChangesAndReplay() throws Exception {
    Path file=temporary.resolve("status-catalog.json");
    Files.write(file,mapper.writeValueAsBytes(CaseStatusKnowledgeTest.catalog()));
    projection=new CaseEvidenceProjection(mapper,"",new CaseStatusKnowledge(mapper,file.toString(),worker));
    service.close(); executor=new ManualExecutor();service=make(executor);
    when(worker.searchCaseKnowledge(any())).thenReturn(CaseStatusKnowledgeTest.searchResponse("FIXTURE-CODSTATUS-991"));
    var context=service.context(viewer,caseId,snapshot.path("id").asText());
    assertThat(context.path("documents").findValuesAsText("id")).contains("FIXTURE-OVERVIEW").doesNotContain("FCR-ENUM-RETRIEVAL");
    verify(worker,never()).searchCaseKnowledge(any());
    ObjectNode job=start("frozen-status-question"), frozen=stored(job);
    assertThat(frozen.path("input").path("documents").findValuesAsText("id"))
        .contains("FIXTURE-CODSTATUS-991","FCR-ENUM-RETRIEVAL");
    assertThat(frozen.path("inputHash").asText()).isEqualTo(UatService.canonicalHash(frozen.path("input")));
    assertThat(frozen.path("guidanceHash")).isNotEqualTo(context.path("guidanceHash"));
    Files.writeString(file,"invalid newer catalog");
    assertThat(start("frozen-status-question").path("id")).isEqualTo(job.path("id"));
    assertThat(detail(job).path("documents")).isEqualTo(frozen.path("input").path("documents"));
    verify(worker,times(1)).searchCaseKnowledge(any());
    verify(worker,never()).answerCase(any());
  }

  @Test void statusSearchRespectsAuthorizationQueueCapacityAndReleasesFailedReservations() throws Exception {
    Path file=temporary.resolve("status-catalog.json");
    Files.write(file,mapper.writeValueAsBytes(CaseStatusKnowledgeTest.catalog()));
    projection=new CaseEvidenceProjection(mapper,"",new CaseStatusKnowledge(mapper,file.toString(),worker));
    service.close();executor=new ManualExecutor();service=make(executor);
    status(403,()->service.start(viewer,caseId,bytes(question()),"viewer-status-question"));
    status(404,()->service.start(other,caseId,bytes(question()),"other-status-question"));
    verifyNoInteractions(worker);
    when(worker.searchCaseKnowledge(any())).thenThrow(new ApiException(503,"FIXTURE_UNAVAILABLE","Synthetic failure"));
    for(int i=0;i<6;i++) {String key="failed-status-search-"+i;status(503,()->start(key));}
    assertThat(jobs()).isZero();
    doReturn(CaseStatusKnowledgeTest.searchResponse()).when(worker).searchCaseKnowledge(any());
    for(int i=0;i<5;i++)start("queued-status-search-"+i);
    verify(worker,times(11)).searchCaseKnowledge(any());
    status(429,()->start("full-status-queue"));
    verify(worker,times(11)).searchCaseKnowledge(any());
    assertThat(jobs()).isEqualTo(5);
  }

  @Test void successfulResultUsesFrozenVersionAndNeverRewritesLaterEvidenceOrCase() throws Exception {
    ObjectNode job = start("frozen-version"); ObjectNode frozenInput = (ObjectNode) stored(job).path("input");
    ObjectNode newer = evidence.submit(analyst, caseId, bytes(payload("RAW-TEST-B")), "JSON", "newer-evidence");
    ObjectNode caseBefore = cases.caseDetail(analyst, caseId); AtomicReference<ObjectNode> captured = new AtomicReference<>();
    doAnswer(call -> { ObjectNode input = call.getArgument(0); captured.set(input.deepCopy());
      assertThat(detail(job).path("status").asText()).isEqualTo("RUNNING"); return answer(input); }).when(worker).answerCase(any());
    executor.runNext(); ObjectNode completed = detail(job);
    assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
    assertThat(captured.get()).isEqualTo(frozenInput);
    assertThat(completed.path("evidenceId")).isEqualTo(snapshot.path("id"));
    assertThat(completed.path("answer").path("snapshotId")).isEqualTo(snapshot.path("id"));
    assertThat(completed.path("answer").path("answerComposition").asText()).isEqualTo("joined-model-claims");
    assertThat(completed.path("documents").toString()).contains("RAW-TEST-A").doesNotContain("RAW-TEST-B");
    assertThat(evidence.detail(analyst, caseId, snapshot.path("id").asText())).isEqualTo(snapshot);
    assertThat(evidence.detail(analyst, caseId, newer.path("id").asText())).isEqualTo(newer);
    assertThat(cases.caseDetail(analyst, caseId)).isEqualTo(caseBefore);
    assertThat(service.workbench(analyst, caseId).path("latestEvidenceId")).isEqualTo(newer.path("id"));
    assertThat(start("frozen-version").path("status").asText()).isEqualTo("COMPLETED");
    assertThat(executor.pending()).isZero(); verify(worker, times(1)).answerCase(any());
  }

  @Test void idempotencyReplaysPendingAndRejectsChangedQuestionOrVersion() {
    AtomicReference<Instant> time = controlledTime();
    ObjectNode original = start("replay-question");
    time.set(time.get().plusSeconds(60));
    assertThat(start("replay-question")).isEqualTo(original);
    status(409, () -> service.start(analyst, caseId, bytes(question(snapshot, "A changed question")), "replay-question"));
    ObjectNode newer = evidence.submit(analyst, caseId, bytes(payload("RAW-TEST-B")), "JSON", "changed-evidence");
    status(409, () -> service.start(analyst, caseId, bytes(question(newer, question().path("question").asText())), "replay-question"));
    assertThat(jobs()).isEqualTo(1); assertThat(executor.pending()).isEqualTo(1); verifyNoInteractions(worker);
  }

  @Test void newCaseJobsRequireRagAndNeverUseTheOriginalExportWorker() throws Exception {
    doAnswer(call -> {ObjectNode legacy=answer(call.getArgument(0));legacy.remove("rag");return legacy;}).when(worker).answerCase(any());
    ObjectNode job=start("missing-case-rag");
    assertThat(stored(job).path("answerContract").asText()).isEqualTo(CaseAnswerValidator.PIPELINE);
    executor.runNext();
    assertThat(detail(job).path("status").asText()).isEqualTo("FAILED");
    assertThat(detail(job).has("answer")).isFalse();
    verify(worker).answerCase(any()); verify(worker,never()).answer(any());
  }

  @Test void completedOldAnswersRemainReadableWithoutNewMetadataOrNewInference() throws Exception {
    ObjectNode job=start("read-original-answer");executor.runNext();
    ObjectNode old=stored(job);old.remove("answerContract");((ObjectNode)old.path("answer")).remove("rag");replaceStored(old);
    ObjectNode before=old.path("answer").deepCopy();
    assertThat(detail(job).path("answer")).isEqualTo(before);
    assertThat(start("read-original-answer").path("status").asText()).isEqualTo("COMPLETED");
    verify(worker,times(1)).answerCase(any());verify(worker,never()).answer(any());
  }

  @Test void removingRagFromANewSavedAnswerCannotDowngradeItsValidation() throws Exception {
    ObjectNode job=start("tampered-case-rag");executor.runNext();
    ObjectNode body=stored(job);((ObjectNode)body.path("answer")).remove("rag");replaceStored(body);
    status(503,()->detail(job));verify(worker,times(1)).answerCase(any());
  }

  @Test void concurrentSameCommandPersistsAndSchedulesOnlyOneJob() throws Exception {
    ExecutorService callers = Executors.newFixedThreadPool(2); CountDownLatch go = new CountDownLatch(1);
    try {
      Callable<ObjectNode> call = () -> { go.await(); return start("concurrent-question"); };
      Future<ObjectNode> first = callers.submit(call), second = callers.submit(call); go.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS).path("id")).isEqualTo(second.get(5, TimeUnit.SECONDS).path("id"));
      assertThat(jobs()).isEqualTo(1); assertThat(executor.pending()).isEqualTo(1); verifyNoInteractions(worker);
    } finally { callers.shutdownNow(); }
  }

  @Test void writerTenantAndSavedVersionOwnershipChecksRunBeforeDispatch() {
    status(403, () -> service.start(viewer, caseId, bytes(question()), "viewer-question"));
    status(404, () -> service.start(other, caseId, bytes(question()), "other-question"));
    status(404, () -> service.workbench(other, caseId));
    status(404, () -> service.context(other, caseId, snapshot.path("id").asText()));
    status(404, () -> service.context(analyst, caseId, "EVD-missing"));
    ObjectNode wrong = question(); wrong.put("evidenceId", "EVD-missing");
    status(404, () -> service.start(analyst, caseId, bytes(wrong), "missing-evidence"));
    ObjectNode stale = question(); stale.put("evidenceHash", "0".repeat(64));
    status(409, () -> service.start(analyst, caseId, bytes(stale), "stale-evidence"));
    assertThat(jobs()).isZero(); verifyNoInteractions(worker);
    ObjectNode job = start("authorized-question");
    status(404, () -> service.detail(other, caseId, job.path("id").asText()));
    status(404, () -> service.detail(analyst, caseId, "CIN-missing"));
  }

  @Test void strictQuestionBoundsAndEmptyEvidenceRejectWithoutJobs() {
    for (String body : List.of("{}", "[]", "null", "{\"question\":\"x\",\"question\":\"y\"}", question() + " {}"))
      status(422, () -> service.start(analyst, caseId, body.getBytes(StandardCharsets.UTF_8), "invalid-question"));
    for (Consumer<ObjectNode> edit : List.<Consumer<ObjectNode>>of(q -> q.put("question", "x".repeat(2001)),
        q -> q.put("question", " "), q -> q.put("question", 10), q -> q.put("question", "bad\0text"),
        q -> q.put("documents", "client-selected"), q -> q.put("evidenceHash", "not-a-hash"))) {
      ObjectNode input = question(); edit.accept(input);
      status(422, () -> service.start(analyst, caseId, bytes(input), "invalid-question"));
    }
    for (String key : Arrays.asList(null, "short", "has spaces in retry key"))
      status(400, () -> service.start(analyst, caseId, bytes(question()), key));
    status(413, () -> service.start(analyst, caseId, new byte[16385], "oversized-question"));
    ObjectNode empty = evidence.submit(analyst, caseId, bytes(evidence.template(item)), "JSON", "empty-evidence");
    status(422, () -> service.start(analyst, caseId, bytes(question(empty, "Is the outcome known?")), "empty-question"));
    assertThat(jobs()).isZero(); verifyNoInteractions(worker);
  }

  @Test void queueLimitIsBoundedAndCapacityReturnsAfterCompletion() {
    for(int i=0;i<5;i++) start("queued-question-" + i);
    status(429, () -> start("queue-overflow")); assertThat(jobs()).isEqualTo(5);
    executor.runNext(); assertThat(start("after-one-finished").path("status").asText()).isEqualTo("QUEUED");
    assertThat(jobs()).isEqualTo(6); assertThat(executor.pending()).isEqualTo(5);
  }

  @Test void failedSchedulingPersistsFailureAndDoesNotInvokeModel() {
    controlledTime();
    executor.shutdown(); ObjectNode failed = start("rejected-executor");
    assertThat(failed.path("status").asText()).isEqualTo("FAILED");
    assertThat(failed.path("error").path("code").asText()).isEqualTo("CASE_INVESTIGATION_UNAVAILABLE");
    assertThat(failed.path("timing")).isEqualTo(timing(0L, 0L, null, 0L, "request-received"));
    assertThat(detail(failed).has("answer")).isFalse();
    assertThat(start("rejected-executor")).isEqualTo(failed); verifyNoInteractions(worker);
  }

  @Test void providerTimeoutBusyAndRuntimeFailuresPersistSanitizedErrorsWithoutFallbackOrRetry() {
    AtomicReference<Instant> time = controlledTime();
    int index = 0;
    for (RuntimeException failure : List.of(new ApiException(504, "UAT_MODEL_TIMEOUT", "PRIVATE provider detail"),
        new ApiException(503, "UAT_MODEL_BUSY", "PRIVATE provider detail"), new IllegalStateException("PRIVATE provider detail"))) {
      doAnswer(call -> { time.set(time.get().plusSeconds(4)); throw failure; }).when(worker).answerCase(any());
      String key = "failed-question-" + index++;
      ObjectNode job = start(key); time.set(time.get().plusSeconds(2)); executor.runNext(); ObjectNode failed = detail(job);
      assertThat(failed.path("status").asText()).isEqualTo("FAILED"); assertThat(failed.has("answer")).isFalse();
      assertThat(failed.path("timing")).isEqualTo(timing(0L, 2000L, 4000L, 6000L, "request-received"));
      assertThat(failed.path("error").toString()).doesNotContain("PRIVATE");
      assertThat(start(key).path("status").asText()).isEqualTo("FAILED");
      assertThat(executor.pending()).isZero();
    }
    verify(worker, times(3)).answerCase(any()); assertThat(jobs()).isEqualTo(3);
  }

  @Test void malformedCitationsProvenanceAndUncitedSummaryCannotBecomeSavedAnswers() {
    int index = 0;
    for (Consumer<ObjectNode> edit : List.<Consumer<ObjectNode>>of(a -> a.put("snapshotId", "wrong-snapshot"),
        a -> ((ObjectNode)a.path("claims").get(0)).putArray("evidenceIds").add("invented"),
        a -> ((ObjectNode)a.path("citations").get(0)).put("content", "Changed source"),
        a -> ((ObjectNode)a.path("model")).put("actualCalls", 0),
        a -> a.put("answer", a.path("answer").asText() + " Uncited additional conclusion."))) {
      doAnswer(call -> { ObjectNode response = answer(call.getArgument(0)); edit.accept(response); return response; }).when(worker).answerCase(any());
      ObjectNode job = start("invalid-answer-" + index++); executor.runNext(); ObjectNode failed = detail(job);
      assertThat(failed.path("status").asText()).isEqualTo("FAILED"); assertThat(failed.has("answer")).isFalse();
      assertThat(failed.path("error").path("code").asText()).isEqualTo("INVALID_UAT_ANSWER");
    }
    assertThat(evidence.detail(analyst, caseId, snapshot.path("id").asText())).isEqualTo(snapshot);
  }

  @Test void restartMarksBothUnfinishedStatesFailedAndLeavesCompletedAnswerUntouched() throws Exception {
    AtomicReference<Instant> time = controlledTime();
    ObjectNode completed = start("before-restart-done"); executor.runNext(); ObjectNode original = detail(completed);
    ObjectNode queued = start("before-restart-queued"), running = start("before-restart-running");
    time.set(time.get().plusSeconds(2));
    ObjectNode body = stored(running); body.put("status", "RUNNING").put("startedAt", time.get().toString()); replaceStored(body);
    time.set(time.get().plusSeconds(5));
    service.recoverInterrupted(); service.recoverInterrupted();
    for(ObjectNode job : List.of(queued, running)) {
      ObjectNode failed = detail(job); assertThat(failed.path("status").asText()).isEqualTo("FAILED");
      assertThat(failed.path("error").path("code").asText()).isEqualTo("CASE_INVESTIGATION_INTERRUPTED");
      assertThat(failed.has("answer")).isFalse();
    }
    assertThat(detail(queued).path("timing")).isEqualTo(timing(0L, 7000L, null, 7000L, "request-received"));
    assertThat(detail(running).path("timing")).isEqualTo(timing(0L, 2000L, 5000L, 7000L, "request-received"));
    executor.runNext(); executor.runNext();
    assertThat(detail(completed)).isEqualTo(original); verify(worker, times(1)).answerCase(any());
  }

  @Test void storageInputTamperingIsRejectedBeforeModelAndOnDetailRead() throws Exception {
    ObjectNode job = start("tampered-input"); ObjectNode body = stored(job);
    ((ObjectNode)body.path("input")).put("question", "Changed without updating its hash"); replaceStored(body);
    status(503, () -> detail(job)); executor.runNext();
    assertThat(stored(job).path("status").asText()).isEqualTo("FAILED");
    assertThat(stored(job).has("answer")).isFalse(); verifyNoInteractions(worker);
  }

  @Test void accessIsRecheckedBeforeDispatchAndBeforeSavingAWorkerAnswer() {
    AtomicReference<Instant> time = controlledTime();
    ObjectNode denied = start("access-revoked-before");
    doThrow(ApiException.notFound()).when(cases).caseDetail(analyst, caseId);
    time.set(time.get().plusSeconds(3));
    executor.runNext(); verifyNoInteractions(worker);
    doCallRealMethod().when(cases).caseDetail(analyst, caseId);
    assertThat(detail(denied).path("status").asText()).isEqualTo("FAILED");
    assertThat(detail(denied).path("timing")).isEqualTo(timing(0L, 3000L, null, 3000L, "request-received"));
    doAnswer(call -> { ObjectNode response = answer(call.getArgument(0));
      doThrow(ApiException.notFound()).when(cases).caseDetail(analyst, caseId); return response; }).when(worker).answerCase(any());
    ObjectNode deniedAfter = start("access-revoked-after"); executor.runNext();
    doCallRealMethod().when(cases).caseDetail(analyst, caseId);
    assertThat(detail(deniedAfter).path("status").asText()).isEqualTo("FAILED");
    assertThat(detail(deniedAfter).has("answer")).isFalse(); verify(worker, times(1)).answerCase(any());
  }

  @Test void completedStoredAnswerCannotBeChangedOrGivenAnAdditionalSummary() throws Exception {
    ObjectNode job = start("completed-tamper"); executor.runNext(); ObjectNode body = stored(job);
    ((ObjectNode)body.path("answer")).put("answer", "Unsupported replacement summary"); replaceStored(body);
    status(503, () -> detail(job)); service.recoverInterrupted();
    verify(worker, times(1)).answerCase(any());
  }

  @Test void startupRecoveryGatesManagedExecutorUntilReadyAndShutdownClosesAdmission() throws Exception {
    CountDownLatch invoked = new CountDownLatch(1), release = new CountDownLatch(1);
    doAnswer(call -> { invoked.countDown();
      if(!release.await(5, TimeUnit.SECONDS))throw new IllegalStateException("Original test release timed out");
      return answer(call.getArgument(0)); }).when(worker).answerCase(any());
    CaseInvestigationService managed = new CaseInvestigationService(mapper, db, tx, cases, evidence, projection, worker);
    try {
      status(503, () -> managed.start(analyst, caseId, bytes(question()), "managed-question"));
      assertThat(jobs()).isZero(); verifyNoInteractions(worker);
      managed.recoverInterrupted();
      assertThat(managed.start(analyst, caseId, bytes(question()), "managed-question").path("status").asText()).isEqualTo("QUEUED");
      assertThat(invoked.await(5, TimeUnit.SECONDS)).isTrue();
      managed.close(); release.countDown();
      status(503, () -> managed.start(analyst, caseId, bytes(question()), "managed-after-stop"));
    } finally { release.countDown(); managed.close(); }
  }

  @Test void changedSelectedEvidenceIsRejectedBeforeInferenceAndOnAnswerRead() throws Exception {
    ObjectNode job = start("changed-saved-evidence");
    ObjectNode changed = snapshot.deepCopy();
    ((ObjectNode)changed.path("payload").path("sections").path("PAYMENT").path("rows").get(0)).put("N10_STATUS", "UNSEALED-CHANGE");
    db.update("UPDATE fcr_case_evidence SET body=? WHERE id=?", changed.toString(), snapshot.path("id").asText());
    status(409, () -> detail(job)); executor.runNext();
    assertThat(stored(job).path("status").asText()).isEqualTo("FAILED");
    assertThat(stored(job).path("error").path("code").asText()).isEqualTo("CASE_INVESTIGATION_EVIDENCE_CHANGED");
    assertThat(stored(job).has("answer")).isFalse(); verifyNoInteractions(worker);
  }

  @Test void evidenceMutationDuringInferenceCannotPersistAnAnswerForTheOldFingerprint() throws Exception {
    doAnswer(call -> {
      ObjectNode response = answer(call.getArgument(0)), changed = snapshot.deepCopy();
      changed.put("version", 99);
      db.update("UPDATE fcr_case_evidence SET body=? WHERE id=?", changed.toString(), snapshot.path("id").asText());
      return response;
    }).when(worker).answerCase(any());
    ObjectNode job = start("mid-inference-evidence-change"); executor.runNext();
    assertThat(stored(job).path("status").asText()).isEqualTo("FAILED");
    assertThat(stored(job).has("answer")).isFalse(); verify(worker, times(1)).answerCase(any());
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
