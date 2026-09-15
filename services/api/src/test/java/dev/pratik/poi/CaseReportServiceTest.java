package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class CaseReportServiceTest {
  final ObjectMapper mapper = new ObjectMapper();
  final Actor analyst = new Actor("analyst", "Analyst", "ANALYST", "northstar");
  final Actor viewer = new Actor("viewer", "Viewer", "VIEWER", "northstar");
  final Actor other = new Actor("other", "Other", "ANALYST", "silverline");
  final String caseId = "FCR-REPORT-TEST";
  JdbcTemplate db;
  PaymentDiscoveryService cases;
  CaseEvidenceService evidence;
  CaseInvestigationService investigations;
  CaseManagementService management;
  CaseReportPdf pdf;
  CaseReportService service;
  ObjectNode caseItem, snapshot, managed, job;

  @BeforeEach void setup() {
    var source = new DriverManagerDataSource("jdbc:h2:mem:case-report-" + UUID.randomUUID()
        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
    db = new JdbcTemplate(source);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    cases = mock(PaymentDiscoveryService.class); evidence = mock(CaseEvidenceService.class);
    investigations = mock(CaseInvestigationService.class); management = mock(CaseManagementService.class);
    pdf = mock(CaseReportPdf.class);
    service = new CaseReportService(mapper, db, tx, cases, evidence, investigations, management, pdf);
    caseItem = mapper.createObjectNode().put("id", caseId).put("reference", "000012345678901234567890")
        .put("amount", "123.007").put("orgBank", "099").put("orgBranch", "0100")
        .put("status", "OPEN").put("priority", "MEDIUM").put("createdBy", "analyst");
    when(cases.caseDetail(any(), anyString())).thenAnswer(call -> {
      Actor actor = call.getArgument(0); String id = call.getArgument(1);
      if (!actor.tenantId().equals("northstar") || !id.equals(caseId)) throw ApiException.notFound();
      return caseItem.deepCopy();
    });
    snapshot = mapper.createObjectNode().put("id", "EVD-REPORT-1").put("version", 1)
        .put("caseId", caseId).put("evidenceHash", "a".repeat(64)).put("sourceKind", "JSON");
    snapshot.putObject("coverage").putObject("PAYMENT").put("rowCount", 1).put("completion", "UNVERIFIED");
    snapshot.putArray("warnings").add("Source completion is unverified.");
    snapshot.putObject("payload").put("sourceTimezone", "UNKNOWN").putObject("sections")
        .putObject("PAYMENT").putArray("rows").addObject().put("NUMAMOUNT_4038", "123.007");
    snapshot.withObject("payload").putObject("payment").put("reference", caseItem.path("reference").asText())
        .put("orgBank", "099").put("orgBranch", "0100");
    ObjectNode digest = mapper.createObjectNode();
    for (String field : List.of("sourceKind", "payload", "coverage")) digest.set(field, snapshot.path(field));
    snapshot.put("evidenceHash", UatService.canonicalHash(digest));
    when(evidence.detail(any(), eq(caseId), anyString())).thenAnswer(call -> {
      if (!call.getArgument(2).equals(snapshot.path("id").asText())) throw ApiException.notFound();
      return snapshot.deepCopy();
    });
    managed = mapper.createObjectNode().put("caseId", caseId).put("version", 0).put("priority", "MEDIUM");
    managed.putNull("owner");
    for (String key : List.of("notes", "audit", "evidenceRequests", "reviewerConclusions", "assignees")) managed.putArray(key);
    when(management.detail(any(), eq(caseId))).thenAnswer(call -> managed.deepCopy());
    job = mapper.createObjectNode().put("id", "CIN-REPORT-1").put("caseId", caseId).put("status", "COMPLETED")
        .put("evidenceId", "EVD-REPORT-1").put("evidenceVersion", 1).put("evidenceHash", "a".repeat(64))
        .put("question", "What does the amount show?").put("createdBy", "analyst");
    job.set("evidenceHash", snapshot.path("evidenceHash"));
    job.putArray("documents").addObject().put("id", "RETRIEVED-NOT-CITED").put("content", "Not part of cited-source appendix");
    ObjectNode answer = job.putObject("answer").put("answer", "The recorded amount is 123.007.").put("question", "What does the amount show?");
    answer.putArray("claims").addObject().put("text", answer.path("answer").asText()).putArray("evidenceIds").add("PAYMENT-ROW-1");
    answer.putArray("citations").addObject().put("id", "PAYMENT-ROW-1").put("kind", "evidence")
        .put("content", "{\"NUMAMOUNT_4038\":\"123.007\",\"REF\":\"000012345678901234567890\"}")
        .putObject("source").put("file", "case-evidence/EVD-REPORT-1.json");
    answer.putArray("unknowns").add("Final outcome is unknown."); answer.putArray("nextChecks").add("Inspect source confirmation.");
    when(investigations.detail(any(), eq(caseId), anyString())).thenAnswer(call -> {
      if (!call.getArgument(2).equals(job.path("id").asText())) throw ApiException.notFound();
      return job.deepCopy();
    });
    ObjectNode workbench = mapper.createObjectNode(); workbench.putArray("audit");
    when(investigations.workbench(any(), eq(caseId))).thenReturn(workbench);
  }
  byte[] bytes(JsonNode node) { return node.toString().getBytes(StandardCharsets.UTF_8); }
  ObjectNode selection(boolean raw) {
    ObjectNode result = mapper.createObjectNode().put("evidenceId", "EVD-REPORT-1").put("includeEvidenceRows", raw);
    result.putArray("investigationIds").add("CIN-REPORT-1"); return result;
  }
  ObjectNode preview(boolean raw) { return service.preview(analyst, caseId, bytes(selection(raw)), "test-report-command"); }
  ObjectNode download(ObjectNode report) {
    return mapper.createObjectNode().put("reportId", report.path("reportId").asText()).put("reportHash", report.path("reportHash").asText());
  }
  int count() { return db.queryForObject("SELECT COUNT(*) FROM fcr_case_report", Integer.class); }
  void status(int code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(code));
  }

  @Test void freezesExactEvidenceAnswerAndCitationsWithoutCopyingUncitedRetrievalOrCallingModel() {
    ObjectNode expectedAnswer = job.path("answer").deepCopy();
    ObjectNode report = preview(true);
    assertThat(report.path("case").path("amount").asText()).isEqualTo("123.007");
    assertThat(report.path("case").path("reference").asText()).startsWith("0000");
    assertThat(report.path("evidence").path("payload")).isEqualTo(snapshot.path("payload"));
    assertThat(report.path("investigations").get(0).path("answer")).isEqualTo(expectedAnswer);
    assertThat(report.toString()).doesNotContain("RETRIEVED-NOT-CITED");
    assertThat(report.path("review").path("status").asText()).isEqualTo("PENDING");
    assertThat(report.path("management").has("assignees")).isFalse();
    assertThat(count()).isEqualTo(1); verifyNoInteractions(pdf);
  }

  @Test void rawAppendixChoiceDoesNotDropCitedEvidence() {
    ObjectNode report = preview(false);
    assertThat(report.path("evidence").has("payload")).isFalse();
    assertThat(report.path("evidence").path("coverage")).isEqualTo(snapshot.path("coverage"));
    assertThat(report.path("evidence").path("sourceTimezone").asText()).isEqualTo("UNKNOWN");
    assertThat(report.path("investigations").get(0).path("answer").path("citations")).isEqualTo(job.path("answer").path("citations"));
  }

  @Test void summaryFreezesFullOriginalAnswerAndCitationsWithExplicitPresentationScope() {
    ObjectNode request = selection(false).put("reportMode", "SUMMARY");
    ObjectNode expectedAnswer = job.path("answer").deepCopy();
    ObjectNode report = service.preview(analyst, caseId, bytes(request), "summary-format-key");
    assertThat(report.path("scope")).isEqualTo(request);
    assertThat(report.path("evidence").has("payload")).isFalse();
    assertThat(report.path("investigations").get(0).path("answer")).isEqualTo(expectedAnswer);
    assertThat(report.path("warnings").toString()).contains("summary PDF omits full cited-source bodies", "frozen report snapshot")
        .doesNotContain("Complete documents cited by selected answers remain included");
    ObjectNode hashed = report.deepCopy(); hashed.remove("reportHash");
    assertThat(report.path("reportHash").asText()).isEqualTo(UatService.canonicalHash(hashed));
    assertThat(service.frozen(viewer, caseId, bytes(download(report)))).isEqualTo(report);
    assertThat(service.preview(analyst, caseId, bytes(request), "summary-format-key")).isEqualTo(report);
    status(409, () -> service.preview(analyst, caseId, bytes(request.deepCopy().put("reportMode", "DETAILED")), "summary-format-key"));
    assertThat(count()).isEqualTo(1); verifyNoInteractions(pdf);
  }

  @Test void omittedModePreservesLegacyScopeRequestHashAndReplayWhileExplicitDetailedRemainsAvailable() {
    ObjectNode request = selection(false);
    ObjectNode legacy = preview(false);
    assertThat(legacy.path("scope")).isEqualTo(request);
    assertThat(legacy.path("scope").has("reportMode")).isFalse();
    assertThat(db.queryForObject("SELECT request_hash FROM fcr_case_report", String.class)).isEqualTo(UatService.canonicalHash(request));
    assertThat(service.frozen(viewer, caseId, bytes(download(legacy)))).isEqualTo(legacy);
    assertThat(preview(false)).isEqualTo(legacy);
    assertThat(legacy.path("warnings").toString()).contains("Complete documents cited by selected answers remain included");
    ObjectNode explicit = request.deepCopy().put("reportMode", "DETAILED");
    status(409, () -> service.preview(analyst, caseId, bytes(explicit), "test-report-command"));
    ObjectNode detailed = service.preview(analyst, caseId, bytes(explicit), "explicit-detailed-key");
    assertThat(detailed.path("scope")).isEqualTo(explicit);
    assertThat(detailed.path("investigations")).isEqualTo(legacy.path("investigations"));
    assertThat(detailed.path("warnings")).isEqualTo(legacy.path("warnings"));
  }

  @Test void invalidModesRawSummaryTooManySummaryQuestionsAndMissingRequiredFieldsDoNotPersist() {
    for (JsonNode mode : List.of(mapper.getNodeFactory().textNode("summary"), mapper.getNodeFactory().textNode("OTHER"),
        mapper.getNodeFactory().nullNode(), mapper.getNodeFactory().booleanNode(true), mapper.getNodeFactory().numberNode(1), mapper.createArrayNode())) {
      ObjectNode request = selection(false); request.set("reportMode", mode);
      status(422, () -> service.preview(analyst, caseId, bytes(request), "invalid-mode-key"));
    }
    ObjectNode rawSummary = selection(true).put("reportMode", "SUMMARY");
    status(422, () -> service.preview(analyst, caseId, bytes(rawSummary), "invalid-summary-raw"));
    ObjectNode three = selection(false).put("reportMode", "SUMMARY");
    three.withArray("investigationIds").add("CIN-REPORT-2").add("CIN-REPORT-3");
    status(422, () -> service.preview(analyst, caseId, bytes(three), "invalid-summary-three"));
    ObjectNode missing = selection(false).put("reportMode", "SUMMARY"); missing.remove("evidenceId");
    status(422, () -> service.preview(analyst, caseId, bytes(missing), "invalid-summary-missing"));
    ObjectNode extra = selection(false).put("reportMode", "SUMMARY").put("presentation", "short");
    status(422, () -> service.preview(analyst, caseId, bytes(extra), "invalid-summary-extra"));
    assertThat(count()).isZero(); verifyNoInteractions(evidence, investigations, management, pdf);
  }

  @Test void detailedModeStillAllowsMoreThanTwoQuestionsAndSummaryAllowsTwoWithoutDroppingEither() {
    for (int i = 2; i <= 3; i++) {
      String id = "CIN-REPORT-" + i;
      when(investigations.detail(any(), eq(caseId), eq(id))).thenReturn(job.deepCopy().put("id", id));
    }
    ObjectNode request = selection(false).put("reportMode", "SUMMARY");
    request.withArray("investigationIds").add("CIN-REPORT-2");
    ObjectNode summary = service.preview(analyst, caseId, bytes(request), "two-summary-questions");
    assertThat(summary.path("investigations")).hasSize(2);
    request.put("reportMode", "DETAILED").withArray("investigationIds").add("CIN-REPORT-3");
    ObjectNode detailed = service.preview(analyst, caseId, bytes(request), "three-detailed-questions");
    assertThat(detailed.path("investigations")).hasSize(3);
  }

  @Test void laterCaseEvidenceAndManagementChangesDoNotAlterPreviewOrIdempotentReplay() {
    ObjectNode report = preview(true);
    caseItem.put("amount", "999.009"); snapshot.withObject("/payload/sections/PAYMENT/rows/0").put("NUMAMOUNT_4038", "999.009");
    managed.put("priority", "HIGH").put("version", 1);
    assertThat(service.frozen(viewer, caseId, bytes(download(report)))).isEqualTo(report);
    assertThat(preview(true)).isEqualTo(report);
    assertThat(count()).isEqualTo(1);
    status(409, () -> service.preview(analyst, caseId, bytes(selection(false)), "test-report-command"));
  }

  @Test void exportsCanBeCreatedByViewerWithoutGrantingCaseWriteAuthority() {
    ObjectNode report = service.preview(viewer, caseId, bytes(selection(false)), "viewer-report-key");
    assertThat(report.path("generatedBy").path("role").asText()).isEqualTo("VIEWER");
    assertThat(service.frozen(analyst, caseId, bytes(download(report)))).isEqualTo(report);
  }

  @Test void scopeRecheckedForEveryPreviewReplayAndDownload() {
    ObjectNode report = preview(true);
    status(404, () -> service.preview(other, caseId, bytes(selection(true)), "test-report-command"));
    status(404, () -> service.frozen(other, caseId, bytes(download(report))));
    status(404, () -> service.frozen(analyst, "FCR-DIFFERENT", bytes(download(report))));
    when(cases.caseDetail(eq(analyst), eq(caseId))).thenThrow(ApiException.notFound());
    status(404, () -> preview(true)); status(404, () -> service.frozen(analyst, caseId, bytes(download(report))));
    assertThat(count()).isEqualTo(1);
  }

  @Test void selectedSourcesAndJobsMustBelongToCase() {
    ObjectNode wrong = selection(true).put("evidenceId", "EVD-OTHER");
    status(404, () -> service.preview(analyst, caseId, bytes(wrong), "wrong-evidence"));
    wrong.put("evidenceId", "EVD-REPORT-1"); wrong.withArray("investigationIds").removeAll().add("CIN-OTHER");
    status(404, () -> service.preview(analyst, caseId, bytes(wrong), "wrong-question"));
    assertThat(count()).isZero();
  }

  @Test void oldAnswerSourcesStayOldWhenAnotherEvidenceVersionIsSelected() {
    snapshot.put("id", "EVD-REPORT-2").put("version", 2);
    ObjectNode request = selection(false).put("evidenceId", "EVD-REPORT-2");
    ObjectNode report = service.preview(analyst, caseId, bytes(request), "different-version");
    assertThat(report.path("evidence").path("version").asInt()).isEqualTo(2);
    assertThat(report.path("investigations").get(0).path("evidenceVersion").asInt()).isEqualTo(1);
    assertThat(report.path("investigations").get(0).path("answer").toString()).contains("EVD-REPORT-1");
    assertThat(report.path("warnings").toString()).contains("another evidence version");
  }

  @Test void failedJobsRemainFailedWithoutInventedAnswer() {
    job.put("status", "FAILED"); job.remove("answer"); job.putObject("error").put("message", "Model response failed validation.");
    ObjectNode report = preview(false);
    assertThat(report.path("investigations").get(0).has("answer")).isFalse();
    assertThat(report.path("warnings").toString()).contains("no completed answer");
  }

  @Test void activityUsesFrozenJobStateRatherThanReadingALaterCompletion() {
    job.put("status", "RUNNING"); job.remove("answer");
    ObjectNode later = mapper.createObjectNode(); later.putArray("audit").addObject()
        .put("id", "CIN-REPORT-1-finished").put("action", "INVESTIGATION_COMPLETED");
    when(investigations.workbench(any(), eq(caseId))).thenReturn(later);
    ObjectNode report = preview(false);
    assertThat(report.path("caseActivity").toString()).doesNotContain("INVESTIGATION_COMPLETED");
    verify(investigations, never()).workbench(any(), anyString());
  }

  @Test void onlyExactReviewedSelectionReceivesRecordedConclusion() {
    ObjectNode conclusion = managed.withArray("reviewerConclusions").addObject().put("id", "REV-TEST")
        .put("status", "RECORDED").put("evidenceId", "EVD-REPORT-1").put("evidenceHash", "a".repeat(64))
        .put("createdAt", "2026-09-14T12:00:00Z").put("conclusion", "Recorded facts reviewed; final credit remains unknown.");
    conclusion.putArray("investigationIds").add("CIN-REPORT-1");
    conclusion.set("evidenceHash", snapshot.path("evidenceHash"));
    assertThat(preview(false).path("review").path("conclusion")).isEqualTo(conclusion);
    ObjectNode request = selection(false); request.withArray("investigationIds").removeAll();
    ObjectNode empty = service.preview(analyst, caseId, bytes(request), "another-selection");
    assertThat(empty.path("review").path("status").asText()).isEqualTo("PENDING");
    conclusion.put("evidenceHash", "b".repeat(64));
    assertThat(service.preview(analyst, caseId, bytes(selection(false)), "changed-hash-selection").path("review").path("status").asText()).isEqualTo("PENDING");
  }

  @Test void summaryAndDetailedPresentationUseTheSameExactReviewBinding() {
    ObjectNode conclusion = managed.withArray("reviewerConclusions").addObject().put("id", "REV-FORMAT")
        .put("status", "RECORDED").put("evidenceId", snapshot.path("id").asText())
        .put("evidenceHash", snapshot.path("evidenceHash").asText()).put("createdAt", "2026-09-14T12:00:00Z")
        .put("conclusion", "Review is tied to the same source and question selection.");
    conclusion.putArray("investigationIds").add("CIN-REPORT-1");
    ObjectNode summaryRequest = selection(false).put("reportMode", "SUMMARY");
    ObjectNode summary = service.preview(analyst, caseId, bytes(summaryRequest), "summary-review-key");
    ObjectNode detailed = service.preview(analyst, caseId, bytes(selection(true).put("reportMode", "DETAILED")), "detailed-review-key");
    assertThat(summary.path("review")).isEqualTo(detailed.path("review"));
    assertThat(summary.path("review").path("conclusion")).isEqualTo(conclusion);
    summaryRequest.withArray("investigationIds").removeAll();
    assertThat(service.preview(analyst, caseId, bytes(summaryRequest), "summary-other-questions").path("review").path("status").asText()).isEqualTo("PENDING");
    conclusion.put("evidenceHash", "b".repeat(64));
    assertThat(service.preview(analyst, caseId, bytes(selection(false).put("reportMode", "SUMMARY")), "summary-other-hash").path("review").path("status").asText()).isEqualTo("PENDING");
  }

  @Test void noEvidenceAllowsHonestCaseSummaryOnly() {
    ObjectNode request = selection(false); request.putNull("evidenceId"); request.withArray("investigationIds").removeAll();
    ObjectNode report = service.preview(analyst, caseId, bytes(request), "summary-only-key");
    assertThat(report.path("evidence").isNull()).isTrue(); assertThat(report.path("investigations")).isEmpty();
    request.put("includeEvidenceRows", true);
    status(422, () -> service.preview(analyst, caseId, bytes(request), "invalid-no-evidence"));
  }

  @Test void duplicateKeysDuplicateJobsUnsupportedFieldsAndUnboundedInputsFailBeforeSaving() {
    status(422, () -> service.preview(analyst, caseId, "{\"evidenceId\":null,\"evidenceId\":null}".getBytes(StandardCharsets.UTF_8), "duplicates-key"));
    ObjectNode duplicate = selection(false); duplicate.withArray("investigationIds").add("CIN-REPORT-1");
    status(422, () -> service.preview(analyst, caseId, bytes(duplicate), "duplicate-ids"));
    ObjectNode extra = selection(false).put("tenantId", "silverline");
    status(422, () -> service.preview(analyst, caseId, bytes(extra), "extra-key-report"));
    status(413, () -> service.preview(analyst, caseId, new byte[8193], "oversize-report"));
    ObjectNode many = selection(false); for(int i=0;i<21;i++) many.withArray("investigationIds").add("CIN-"+i);
    status(422, () -> service.preview(analyst, caseId, bytes(many), "many-jobs-report"));
    status(400, () -> service.preview(analyst, caseId, bytes(selection(false)), null));
    assertThat(count()).isZero();
  }

  @Test void oversizedReportAndConcurrentManagementChangeDoNotPersistPartialReport() {
    job.withObject("answer").put("answer", "x".repeat(CaseReportService.MAX_REPORT_BYTES));
    status(413, () -> preview(false)); assertThat(count()).isZero();
    job.withObject("answer").put("answer", "Exact unchanged model text.");
    when(management.detail(any(), eq(caseId))).thenReturn(managed.deepCopy(), managed.deepCopy().put("version", 1));
    status(409, () -> preview(false)); assertThat(count()).isZero();
  }

  @Test void alteredStoredSnapshotAndWrongFingerprintAreRejected() {
    ObjectNode report = preview(false); ObjectNode command = download(report).put("reportHash", "0".repeat(64));
    status(409, () -> service.frozen(analyst, caseId, bytes(command)));
    ObjectNode changed = report.deepCopy().put("generatedAt", "2020-01-01T00:00:00Z");
    db.update("UPDATE fcr_case_report SET body=? WHERE id=?", changed.toString(), report.path("reportId").asText());
    status(503, () -> service.frozen(analyst, caseId, bytes(download(report))));
    status(503, () -> preview(false));
  }

  @Test void concurrentSameCommandCreatesOneImmutableReport() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<ObjectNode> first = pool.submit(() -> preview(false));
      Future<ObjectNode> second = pool.submit(() -> preview(false));
      assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
      assertThat(count()).isEqualTo(1);
    } finally { pool.shutdownNow(); }
  }
}
