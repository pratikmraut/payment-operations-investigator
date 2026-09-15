package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class CaseEvidenceServiceTest {
  final ObjectMapper mapper = new ObjectMapper();
  final Actor analyst = new Actor("analyst", "Analyst", "ANALYST", "northstar");
  final Actor viewer = new Actor("viewer", "Viewer", "VIEWER", "northstar");
  final Actor other = new Actor("other", "Other", "ANALYST", "silverline");
  JdbcTemplate db;
  TransactionTemplate tx;
  PaymentDiscoveryService cases;
  CaseEvidenceService service;
  ObjectNode item;
  String caseId;

  @BeforeEach void setup() {
    var dataSource = new DriverManagerDataSource("jdbc:h2:mem:case-evidence-" + UUID.randomUUID()
        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    db = new JdbcTemplate(dataSource);
    tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    var discovery = new PaymentDiscoveryClient(mapper, "MOCK", false, "", "TEST-UAT", 2,
        (u,b,t) -> { throw new AssertionError("Synthetic discovery cannot call a bank"); });
    cases = new PaymentDiscoveryService(mapper, db, tx, discovery, "northstar:1352:760,silverline:2468:760");
    var found = cases.search(analyst, bytes(mapper.createObjectNode().put("orgBank", "760")
        .put("orgBranch", "1352").put("inquiryDate", "2026-09-14").put("recordCount", 20)));
    var created = cases.createCase(analyst, bytes(mapper.createObjectNode()
        .put("candidateId", found.path("items").get(0).path("candidateId").asText())
        .put("reason", "Original synthetic test case")), "case-evidence-setup");
    item = (ObjectNode) created.path("item"); caseId = created.path("caseId").asText();
    service = make(new CaseEvidenceClient(mapper, false, "", 15, "",
        (u,b,h,t) -> { throw new AssertionError("Disabled evidence client called a bank"); }));
  }

  CaseEvidenceService make(CaseEvidenceClient client) { return new CaseEvidenceService(mapper, db, tx, cases, client); }
  byte[] bytes(JsonNode value) { return value.toString().getBytes(StandardCharsets.UTF_8); }
  byte[] emptyRequest() { return "{}".getBytes(StandardCharsets.UTF_8); }
  int count() { return db.queryForObject("SELECT COUNT(*) FROM fcr_case_evidence", Integer.class); }
  ObjectNode row(JsonNode payload, String group) { return (ObjectNode) payload.path("sections").path(group).path("rows").get(0); }
  ObjectNode acquisition(ObjectNode response, String group) { return (ObjectNode) response.path("acquisition").path(group); }
  void status(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, int expected) {
    assertThatThrownBy(operation).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(expected));
  }

  ObjectNode payload() {
    ObjectNode payload = service.template(item);
    for (String group : CaseEvidenceSchema.COLUMNS.keySet()) {
      ObjectNode row = ((ArrayNode) payload.path("sections").path(group).path("rows")).addObject();
      CaseEvidenceSchema.COLUMNS.get(group).forEach(column -> row.put(column, ""));
      row.put("SOURCE_TABLE", CaseEvidenceSchema.SOURCE_TABLES.get(group))
          .put("QUERY_OBSERVED_AT", "2026-09-14T12:30:00+05:30").put("SCOPE_ROW_COUNT", "1");
      if (group.equals("PAYMENT")) {
        row.put("REFTXNNUMBER", item.path("reference").asText()).put("UTR_REF_NO", item.path("utr").asText())
            .put("IDSEQUENCENO", "00012345678901234567890123456789012345")
            .put("NUMAMOUNT_4038", "900719925474099312345.007")
            .put("DATINITIATION", "2026-09-14T12:30:00").put("CODSTATUS", "2").put("MSGSTATUS", "11").put("ACCTSTATUS", "3");
      } else if (Set.of("HOST", "HISTORY").contains(group)) {
        row.put("REF_TXN_NO", item.path("reference").asText()).put("COD_ORG_BRN", "1352").put("COD_ORG_BANK", "760");
        if (group.equals("HOST")) row.put("REF_SUBSEQ_NO", "000001").put("AMT_TXN_TCY", "1.001");
      } else row.put("CODSTATUS", "2").put("MSGSTATUS", "11").put("ACCTSTATUS", "3").put("NEFTCODSTATUS", "SOURCE-TEST-LABEL");
    }
    ((ObjectNode) payload.path("sections").path("PAYMENT")).put("note", "Original test note\nNo outcome claim");
    return payload;
  }

  ObjectNode apiResponse() {
    ObjectNode response = payload(); ObjectNode acquisition = response.putObject("acquisition");
    for (String group : CaseEvidenceSchema.COLUMNS.keySet()) acquisition.putObject(group)
        .put("reference", item.path("reference").asText()).put("orgBank", "760").put("orgBranch", "1352")
        .put("returnCode", "0").put("fetchCompleted", true).put("observedAt", "2026-09-14T12:30:00+05:30")
        .put("rowCount", response.path("sections").path(group).path("rows").size()).put("hasMore", false);
    return response;
  }
  CaseEvidenceService api(AtomicReference<ObjectNode> response, AtomicInteger calls) {
    return make(new CaseEvidenceClient(mapper, true, "https://bank.example.test/evidence", 15, "", (u,b,h,t) -> {
      calls.incrementAndGet();
      assertThat(mapper.readTree(b)).isEqualTo(mapper.createObjectNode().put("reference", item.path("reference").asText()).put("orgBank", "760").put("orgBranch", "1352"));
      return new CaseEvidenceClient.Reply(200, "application/json", bytes(response.get()));
    }));
  }

  CaseEvidenceService flexcube(AtomicInteger calls, Consumer<ObjectNode> change) {
    var support=new FlexcubeInquirySupport(mapper,"TESTUSER","API","20260914","EXPLICIT","Asia/Kolkata","UNKNOWN");
    return make(new CaseEvidenceClient(mapper,true,"https://bank.example.test/evidence",15,"","FLEXCUBE",support,(u,b,h,t)->{
      calls.incrementAndGet();ObjectNode request=(ObjectNode)mapper.readTree(b);
      ObjectNode response=FlexcubeEvidenceFixtures.response(mapper,request);change.accept(response);
      return new CaseEvidenceClient.Reply(200,"application/json",bytes(response));
    }));
  }

  @Test void po02PreservesWireNullsAndHonestCoverageWithImmutableReplayAndCitedNullRestoration() throws Exception {
    AtomicInteger calls=new AtomicInteger();CaseEvidenceService api=flexcube(calls,response->{});
    ObjectNode saved=api.inquire(analyst,caseId,emptyRequest(),"po02-original-version");
    assertThat(saved.path("sourceKind").asText()).isEqualTo("BANK_API");
    for(String group:CaseEvidenceSchema.COLUMNS.keySet()) {
      assertThat(saved.path("coverage").path(group).path("completion").asText()).isEqualTo("UNVERIFIED");
      assertThat(saved.path("coverage").path(group).has("acquisition")).isFalse();
    }
    assertThat(saved.path("upstream").path("rawResponse").path("neftPaymentEvidenceDetails").get(0).path("idRelatedRef2006").isNull()).isTrue();
    assertThat(row(saved.path("payload"),"PAYMENT").path("IDRELATEDREF_2006").asText()).isEmpty();
    assertThat(saved.path("evidenceHash").asText()).isEqualTo(CaseEvidenceService.fingerprint(saved));
    assertThat(saved.path("warnings").toString()).contains("UNVERIFIED","nulls are preserved","does not by itself confirm");
    ObjectNode projected=new CaseEvidenceProjection(mapper,"").project(analyst,item,saved);
    JsonNode source=null;
    for(JsonNode document:projected.path("documents"))if(document.path("id").asText().equals("PAYMENT-ROW-1"))source=document;
    assertThat(source).isNotNull();
    assertThat(mapper.readTree(source.path("content").asText()).path("IDRELATEDREF_2006").isNull()).isTrue();
    assertThat(source.path("source").path("locator").asText()).contains("original source nulls retained");
    JsonNode hostEvent=null;
    for(JsonNode event:projected.path("timeline"))if(event.path("documentId").asText().equals("HOST-ROW-1"))hostEvent=event;
    assertThat(hostEvent).isNotNull();
    assertThat(hostEvent.path("fields").path("COD_EXT").isNull()).isTrue();
    assertThat(projected.path("documents").toString()).doesNotContain("TESTUSER");
    assertThat(api.inquire(analyst,caseId,emptyRequest(),"po02-original-version")).isEqualTo(saved);
    assertThat(calls).hasValue(1);
    assertThat(api.list(analyst,caseId).path("items").get(0).has("upstream")).isFalse();
    assertThat(api.detail(analyst,caseId,saved.path("id").asText())).isEqualTo(saved);
  }

  @Test void po02MismatchAndMalformedNullArraysNeverSaveOrOverrideExistingEvidence() {
    ObjectNode existing=service.submit(analyst,caseId,bytes(payload()),"JSON","original-before-po02");
    List<Consumer<ObjectNode>> changes=List.of(
        response->((ObjectNode)response.path("neftPaymentEvidenceDetails").get(0)).put("refTxnNumber","OTHER"),
        response->((ObjectNode)response.path("neftHostEvidenceDetails").get(0)).put("codOrgBank","999"),
        response->((ObjectNode)response.path("neftHistoryEvidenceDetails").get(0)).put("codOrgBrn","999"),
        response->((ObjectNode)response.path("neftStatusEvidenceDetails").get(0)).put("msgStatus","99"),
        response->((ObjectNode)response.path("neftHistoryEvidenceDetails").get(0)).put("scopeRowCount","2"),
        response->((ObjectNode)response.path("neftHostEvidenceDetails").get(0)).putNull("refTxnNo"),
        response->response.putNull("neftPaymentEvidenceDetails"),response->response.remove("neftStatusEvidenceDetails"));
    for(var change:changes)status(()->flexcube(new AtomicInteger(),change).inquire(analyst,caseId,emptyRequest(),"bad-po02-response"),502);
    assertThat(count()).isEqualTo(1);
    assertThat(service.detail(analyst,caseId,existing.path("id").asText())).isEqualTo(existing);
  }

  @Test void po02OmittedDtoColumnsSaveAsUnknownWithFrozenOriginalEvidenceAndExactCitedAbsence() throws Exception {
    ObjectNode existing=service.submit(analyst,caseId,bytes(payload()),"JSON","original-before-sparse");
    AtomicInteger calls=new AtomicInteger();
    var support=new FlexcubeInquirySupport(mapper,"TESTUSER","API","20260914","EXPLICIT","Asia/Kolkata","UNKNOWN");
    CaseEvidenceService api=make(new CaseEvidenceClient(mapper,true,"https://bank.example.test/evidence",15,"","FLEXCUBE",support,(u,b,h,t)->{
      calls.incrementAndGet();
      return new CaseEvidenceClient.Reply(200,"application/json",bytes(
          FlexcubeEvidenceFixtures.sparseResponse(mapper,(ObjectNode)mapper.readTree(b))));
    }));
    ObjectNode saved=api.inquire(analyst,caseId,emptyRequest(),"po02-sparse-version");
    assertThat(saved.path("version").asInt()).isEqualTo(2);
    assertThat(saved.path("upstream").path("omittedFields")).hasSize(22);
    assertThat(saved.path("upstream").path("nullFields")).isEmpty();
    assertThat(saved.path("warnings").toString()).contains("omitted");
    assertThat(saved.path("coverage").path("HISTORY").path("rowCount").asInt()).isEqualTo(3);
    assertThat(saved.path("coverage").path("STATUS").path("rowCount").asInt()).isZero();
    for(String group:CaseEvidenceSchema.COLUMNS.keySet())
      assertThat(saved.path("coverage").path(group).path("completion").asText()).isEqualTo("UNVERIFIED");
    ObjectNode projected=new CaseEvidenceProjection(mapper,"").project(analyst,item,saved);
    JsonNode firstHistoryEvent=null;
    for(JsonNode event:projected.path("timeline"))if(event.path("documentId").asText().equals("HISTORY-ROW-1"))firstHistoryEvent=event;
    assertThat(firstHistoryEvent).isNotNull();
    assertThat(firstHistoryEvent.path("fields").has("TXN_STAT")).isFalse();
    for(JsonNode document:projected.path("documents")) {
      if(document.path("id").asText().equals("HISTORY-ROW-1")) {
        assertThat(mapper.readTree(document.path("content").asText()).has("TXN_STAT")).isFalse();
        assertThat(document.path("source").path("locator").asText()).contains("omitted source fields remain absent");
      }
      if(document.path("id").asText().equals("PAYMENT-ROW-1"))
        assertThat(mapper.readTree(document.path("content").asText()).has("IDRELATEDREF_2006")).isFalse();
    }
    assertThat(api.inquire(analyst,caseId,emptyRequest(),"po02-sparse-version")).isEqualTo(saved);
    assertThat(calls).hasValue(1);
    assertThat(api.detail(analyst,caseId,saved.path("id").asText())).isEqualTo(saved);
    assertThat(service.detail(analyst,caseId,existing.path("id").asText())).isEqualTo(existing);
  }

  @Test void canonicalEvidenceStillRejectsMissingNativeColumnsWithOtherwiseValidAcquisition() {
    ObjectNode response=apiResponse();row(response,"PAYMENT").remove("IDRELATEDREF_2006");
    status(()->api(new AtomicReference<>(response),new AtomicInteger())
        .inquire(analyst,caseId,emptyRequest(),"canonical-missing-column"),502);
    assertThat(count()).isZero();
  }

  @Test void po02NullableStatusTupleCanMatchWithoutInventingCodesOrRelaxingPopulatedMismatch() {
    Consumer<ObjectNode> sparseTuple=response->{
      for(String array:List.of("neftPaymentEvidenceDetails","neftStatusEvidenceDetails"))
        ((ObjectNode)response.path(array).get(0)).remove(List.of("codStatus","msgStatus","acctStatus"));
      ((ObjectNode)response.path("neftStatusEvidenceDetails").get(0)).remove("neftCodStatus");
    };
    ObjectNode saved=flexcube(new AtomicInteger(),sparseTuple)
        .inquire(analyst,caseId,emptyRequest(),"po02-nullable-tuple");
    assertThat(saved.path("upstream").path("omittedFields")).hasSize(7);
    ObjectNode statusRow=FlexcubeEvidenceAdapter.sourceRow(saved,"STATUS",1,row(saved.path("payload"),"STATUS"));
    assertThat(statusRow.has("CODSTATUS")).isFalse();
    assertThat(statusRow.has("NEFTCODSTATUS")).isFalse();
    status(()->flexcube(new AtomicInteger(),response->{
      sparseTuple.accept(response);
      ((ObjectNode)response.path("neftStatusEvidenceDetails").get(0)).put("codStatus","0");
    }).inquire(analyst,caseId,emptyRequest(),"po02-unknown-mismatch"),502);
    assertThat(count()).isEqualTo(1);
  }

  @Test void po02EmptyArraysRemainUnverifiedAndCrossTenantOrViewerCannotFetch() {
    AtomicInteger calls=new AtomicInteger();CaseEvidenceService api=flexcube(calls,response->FlexcubeEvidenceAdapter.ARRAYS.values().forEach(response::putArray));
    status(()->api.inquire(viewer,caseId,emptyRequest(),"viewer-po02-inquiry"),403);
    status(()->api.inquire(other,caseId,emptyRequest(),"tenant-po02-inquiry"),404);
    assertThat(calls).hasValue(0);
    ObjectNode saved=api.inquire(analyst,caseId,emptyRequest(),"empty-po02-inquiry");
    for(String group:CaseEvidenceSchema.COLUMNS.keySet()) {
      assertThat(saved.path("coverage").path(group).path("rowCount").asInt()).isZero();
      assertThat(saved.path("coverage").path(group).path("completion").asText()).isEqualTo("UNVERIFIED");
    }
    assertThat(cases.caseDetail(analyst,caseId).path("evidenceStatus").asText()).isEqualTo("EMPTY_EVIDENCE_ATTACHED");
  }

  @Test void alteredPo02RawResponseIsDetectedByDetailAndProjectionWithoutChangingLegacyFingerprints() {
    ObjectNode legacy=service.submit(analyst,caseId,bytes(payload()),"JSON","legacy-evidence-hash");
    ObjectNode oldDigest=mapper.createObjectNode();for(String field:List.of("sourceKind","payload","coverage"))oldDigest.set(field,legacy.path(field));
    assertThat(CaseEvidenceService.fingerprint(legacy)).isEqualTo(UatService.canonicalHash(oldDigest));
    CaseEvidenceService api=flexcube(new AtomicInteger(),response->{});
    ObjectNode saved=api.inquire(analyst,caseId,emptyRequest(),"po02-corruption-test");
    ObjectNode corrupted=saved.deepCopy();((ObjectNode)corrupted.path("upstream").path("rawResponse")).put("postingDate","20260101");
    db.update("UPDATE fcr_case_evidence SET body=? WHERE id=?",corrupted.toString(),saved.path("id").asText());
    status(()->api.detail(analyst,caseId,saved.path("id").asText()),503);
    status(()->new CaseEvidenceProjection(mapper,"").project(analyst,item,corrupted),503);
    assertThat(service.detail(analyst,caseId,legacy.path("id").asText())).isEqualTo(legacy);
  }

  @Test void manualJsonRoundTripPreservesExactNativeTextAndMarksCompletionUnverified() {
    ObjectNode input = payload(); ObjectNode saved = service.submit(analyst, caseId, bytes(input), "MANUAL", "manual-roundtrip");
    assertThat(saved.path("payload")).isEqualTo(input);
    assertThat(row(saved.path("payload"), "PAYMENT").path("NUMAMOUNT_4038").asText()).isEqualTo("900719925474099312345.007");
    assertThat(row(saved.path("payload"), "PAYMENT").path("IDSEQUENCENO").asText()).startsWith("000123");
    assertThat(row(saved.path("payload"), "HOST").path("REF_SUBSEQ_NO").asText()).isEqualTo("000001");
    for (String group : CaseEvidenceSchema.COLUMNS.keySet()) assertThat(saved.path("coverage").path(group).path("completion").asText()).isEqualTo("UNVERIFIED");
    assertThat(saved.path("warnings").toString()).contains("amount differs", "timezone is unspecified");
    assertThat(cases.caseDetail(analyst, caseId).path("evidenceStatus").asText()).isEqualTo("EVIDENCE_ATTACHED");
  }

  @Test void versionsRemainImmutableAndListReturnsSummariesWithoutPayload() {
    ObjectNode original = payload(); ObjectNode first = service.submit(analyst, caseId, bytes(original), "JSON", "first-version");
    ObjectNode changed = original.deepCopy(); row(changed, "PAYMENT").put("N10_STATUS", "UNKNOWN-TEST-CODE");
    ObjectNode second = service.submit(analyst, caseId, bytes(changed), "JSON", "second-version");
    assertThat(first.path("version").asInt()).isEqualTo(1); assertThat(second.path("version").asInt()).isEqualTo(2);
    assertThat(first.path("evidenceHash")).isNotEqualTo(second.path("evidenceHash"));
    assertThat(service.detail(analyst, caseId, first.path("id").asText()).path("payload")).isEqualTo(original);
    JsonNode summaries = service.list(viewer, caseId).path("items"); assertThat(summaries.size()).isEqualTo(2);
    assertThat(summaries.get(0).path("version").asInt()).isEqualTo(2);
    for (JsonNode summary : summaries) assertThat(summary.has("payload")).isFalse();
  }

  @Test void sameKeyReplaysSameSnapshotAndChangedPayloadOrSourceConflicts() {
    ObjectNode input = payload(); byte[] original = bytes(input);
    ObjectNode first = service.submit(analyst, caseId, original, "MANUAL", "replay-evidence");
    assertThat(service.submit(analyst, caseId, original, "MANUAL", "replay-evidence")).isEqualTo(first);
    row(input, "PAYMENT").put("NUMRETRY", "2");
    status(() -> service.submit(analyst, caseId, bytes(input), "MANUAL", "replay-evidence"), 409);
    status(() -> service.submit(analyst, caseId, original, "JSON", "replay-evidence"), 409);
    assertThat(count()).isEqualTo(1);
  }

  @Test void roleTenantAndCaseChecksProtectReadsWritesAndTemplates() {
    ObjectNode input = payload();
    status(() -> service.submit(viewer, caseId, bytes(input), "MANUAL", "role-check"), 403);
    status(() -> service.submit(other, caseId, bytes(input), "MANUAL", "tenant-check"), 404);
    status(() -> service.config(other, caseId), 404); status(() -> service.list(other, caseId), 404);
    status(() -> service.excelTemplate(other, caseId, "PAYMENT"), 404);
    status(() -> service.inquire(other, caseId, emptyRequest(), "tenant-api"), 404);
    assertThat(service.config(viewer, caseId).path("api").path("enabled").asBoolean()).isFalse();
    ObjectNode saved = service.submit(analyst, caseId, bytes(input), "JSON", "valid-tenant");
    status(() -> service.detail(other, caseId, saved.path("id").asText()), 404);
    status(() -> service.detail(analyst, caseId, "EVD-unknown"), 404);
  }

  @Test void mismatchedTopLevelAndNativePaymentScopeRejectEveryRowAtomically() {
    List<Consumer<ObjectNode>> changes = List.of(
        p -> ((ObjectNode) p.path("payment")).put("reference", "OTHER"),
        p -> ((ObjectNode) p.path("payment")).put("orgBank", "999"),
        p -> ((ObjectNode) p.path("payment")).put("orgBranch", "2468"),
        p -> row(p,"PAYMENT").put("REFTXNNUMBER", "OTHER"),
        p -> row(p,"HOST").put("REF_TXN_NO", "OTHER"),
        p -> row(p,"HISTORY").put("COD_ORG_BRN", "2468"),
        p -> row(p,"HOST").put("COD_ORG_BANK", "999"));
    for (Consumer<ObjectNode> change : changes) {
      ObjectNode input = payload(); change.accept(input);
      status(() -> service.submit(analyst, caseId, bytes(input), "MANUAL", "mismatch-scope"), 422);
    }
    assertThat(count()).isZero(); assertThat(cases.caseDetail(analyst, caseId).path("evidenceStatus").asText()).isEqualTo("DISCOVERY_ONLY");
  }

  @Test void requiresFourSectionsExactHeadersAllStringsAndMatchingPopulatedScopeCounts() {
    List<Consumer<ObjectNode>> changes = List.of(
        p -> ((ObjectNode) p.path("sections")).remove("STATUS"),
        p -> ((ObjectNode) p.path("sections")).putObject("EXTRA"),
        p -> row(p,"HOST").remove("DAT_TXN"),
        p -> row(p,"PAYMENT").put("UNSUPPORTED", "value"),
        p -> row(p,"PAYMENT").put("NUMAMOUNT_4038", 1.01),
        p -> row(p,"PAYMENT").put("SCOPE_ROW_COUNT", "2"),
        p -> row(p,"HOST").put("SOURCE_TABLE", "OTHER_TABLE"),
        p -> ((ObjectNode) p.path("sections").path("PAYMENT")).put("note", 7),
        p -> p.put("sourceTimezone", "Unknown/InvalidZone"));
    for (Consumer<ObjectNode> change : changes) {
      ObjectNode input = payload(); change.accept(input);
      status(() -> service.submit(analyst, caseId, bytes(input), "JSON", "bad-field-contract"), 422);
    }
    ObjectNode tooMany = payload(); ArrayNode history = (ArrayNode) tooMany.path("sections").path("HISTORY").path("rows");
    ObjectNode historyRow = row(tooMany,"HISTORY").deepCopy(); for(int i=1; i<=500; i++)history.add(historyRow.deepCopy());
    status(() -> service.submit(analyst, caseId, bytes(tooMany), "JSON", "too-many-rows"), 422);
    assertThat(count()).isZero();
  }

  @Test void statusDomainRowsRequireACorrespondingSuppliedPaymentTuple() {
    ObjectNode wrong = payload(); row(wrong,"STATUS").put("MSGSTATUS", "99");
    status(() -> service.submit(analyst, caseId, bytes(wrong), "JSON", "wrong-status-tuple"), 422);
    ObjectNode missing = payload(); ((ArrayNode) missing.path("sections").path("PAYMENT").path("rows")).removeAll();
    status(() -> service.submit(analyst, caseId, bytes(missing), "JSON", "no-payment-tuple"), 422);
    assertThat(count()).isZero();
  }

  @Test void emptyManualSectionsAndHeaderOnlyWorkbooksNeverClaimSuccessfulSourceQueries() {
    ObjectNode saved = service.submit(analyst, caseId, bytes(service.template(item)), "JSON", "empty-json-groups");
    for(String group : CaseEvidenceSchema.COLUMNS.keySet()) {
      assertThat(saved.path("coverage").path(group).path("rowCount").asInt()).isZero();
      assertThat(saved.path("coverage").path(group).path("completion").asText()).isEqualTo("UNVERIFIED");
    }
    Map<String,byte[]> files = new LinkedHashMap<>(); CaseEvidenceSchema.COLUMNS.keySet().forEach(group -> files.put(group, CaseEvidenceSchema.template(group)));
    ObjectNode uploaded = service.excel(analyst, caseId, files, "UNKNOWN", "header-only-excel");
    assertThat(uploaded.path("sourceKind").asText()).isEqualTo("EXCEL");
    assertThat(uploaded.path("warnings").toString()).contains("query completion is unverified");
    assertThat(cases.caseDetail(analyst, caseId).path("evidenceStatus").asText()).isEqualTo("EMPTY_EVIDENCE_ATTACHED");
  }

  @Test void successfulApiReportsAcquisitionAndSameKeyRetryDoesNotFetchAgain() {
    var response = new AtomicReference<>(apiResponse()); var calls = new AtomicInteger(); var api = api(response, calls);
    ObjectNode first = api.inquire(analyst, caseId, emptyRequest(), "api-idempotent");
    assertThat(first.path("sourceKind").asText()).isEqualTo("BANK_API");
    for(String group : CaseEvidenceSchema.COLUMNS.keySet()) assertThat(first.path("coverage").path(group).path("completion").asText()).isEqualTo("COMPLETE");
    assertThat(api.inquire(analyst, caseId, emptyRequest(), "api-idempotent")).isEqualTo(first); assertThat(calls).hasValue(1);
    ObjectNode changed = apiResponse(); row(changed,"PAYMENT").put("NUMRETRY", "3"); response.set(changed);
    ObjectNode second = api.inquire(analyst, caseId, emptyRequest(), "api-new-observation");
    assertThat(second.path("version").asInt()).isEqualTo(2); assertThat(calls).hasValue(2);
    assertThat(row(api.detail(analyst,caseId,first.path("id").asText()).path("payload"),"PAYMENT").path("NUMRETRY").asText()).isEmpty();
  }

  @Test void invalidAcquisitionOrApiRowsReturn502WithoutOverwritingExistingEvidence() {
    ObjectNode original = service.submit(analyst, caseId, bytes(payload()), "JSON", "preexisting-evidence");
    var response = new AtomicReference<>(apiResponse()); var calls = new AtomicInteger(); var api = api(response, calls);
    List<Consumer<ObjectNode>> changes = List.of(
        p -> acquisition(p,"PAYMENT").put("rowCount", 2),
        p -> acquisition(p,"HOST").put("hasMore", true),
        p -> acquisition(p,"HISTORY").put("returnCode", "-1"),
        p -> acquisition(p,"STATUS").put("fetchCompleted", false),
        p -> acquisition(p,"HOST").put("observedAt", "2026-09-14T12:30:00"),
        p -> acquisition(p,"PAYMENT").put("orgBank", "999"),
        p -> ((ObjectNode) p.path("acquisition")).remove("STATUS"),
        p -> row(p,"HOST").put("REF_TXN_NO", "OTHER"),
        p -> row(p,"STATUS").put("MSGSTATUS", "99"));
    for(Consumer<ObjectNode> change : changes) {
      ObjectNode bad = apiResponse(); change.accept(bad); response.set(bad);
      status(() -> api.inquire(analyst,caseId,emptyRequest(),"invalid-api-response"),502);
    }
    assertThat(count()).isEqualTo(1); assertThat(service.detail(analyst,caseId,original.path("id").asText())).isEqualTo(original);
  }

  @Test void disabledTimeoutAndUpstreamFailureDoNotModifyPriorEvidence() {
    ObjectNode original = service.submit(analyst, caseId, bytes(payload()), "MANUAL", "baseline-before-failure");
    status(() -> service.inquire(analyst,caseId,emptyRequest(),"disabled-api-request"),503);
    var timeout = make(new CaseEvidenceClient(mapper,true,"https://bank.example.test/evidence",15,"",(u,b,h,t) -> {throw new TimeoutException();}));
    status(() -> timeout.inquire(analyst,caseId,emptyRequest(),"timeout-api-request"),504);
    var failed = make(new CaseEvidenceClient(mapper,true,"https://bank.example.test/evidence",15,"",(u,b,h,t) -> new CaseEvidenceClient.Reply(500,"application/json",emptyRequest())));
    status(() -> failed.inquire(analyst,caseId,emptyRequest(),"failed-api-request"),502);
    assertThat(count()).isEqualTo(1); assertThat(service.detail(analyst,caseId,original.path("id").asText())).isEqualTo(original);
  }

  @Test void rejectsInjectedApiSelectorsInvalidKeysDuplicateJsonAndLargeBodiesBeforeSaving() {
    var calls = new AtomicInteger(); var api = api(new AtomicReference<>(apiResponse()),calls);
    status(() -> api.inquire(analyst,caseId,bytes(mapper.createObjectNode().put("reference","OTHER")),"api-field-injection"),422);
    assertThat(calls).hasValue(0);
    status(() -> service.submit(analyst,caseId,bytes(payload()),"JSON","short"),400);
    status(() -> service.submit(analyst,caseId,"{\"x\":1,\"x\":2}".getBytes(StandardCharsets.UTF_8),"JSON","duplicate-json"),422);
    status(() -> service.submit(analyst,caseId,new byte[CaseEvidenceService.MAX_BYTES+1],"JSON","oversized-json"),413);
    ObjectNode input = payload(); service.submit(analyst,caseId,bytes(input),"MANUAL","cross-source-key");
    status(() -> api.inquire(analyst,caseId,emptyRequest(),"cross-source-key"),409); assertThat(calls).hasValue(0);
    assertThat(count()).isEqualTo(1);
  }

  @Test void concurrentSavesGetDistinctVersionsUnderTheCaseLock() throws Exception {
    ObjectNode first = payload(); ObjectNode second = payload(); row(second,"PAYMENT").put("NUMRETRY","2");
    var start = new CountDownLatch(1); var executor = Executors.newFixedThreadPool(2);
    try {
      var a = executor.submit(() -> {start.await(); return service.submit(analyst,caseId,bytes(first),"JSON","concurrent-first");});
      var b = executor.submit(() -> {start.await(); return service.submit(analyst,caseId,bytes(second),"JSON","concurrent-second");});
      start.countDown();
      assertThat(Set.of(a.get(10,TimeUnit.SECONDS).path("version").asInt(),b.get(10,TimeUnit.SECONDS).path("version").asInt())).containsExactlyInAnyOrder(1,2);
      assertThat(count()).isEqualTo(2);
    } finally {executor.shutdownNow();}
  }

  @Test void validPayloadForAnotherSavedCaseIsRejectedWithAnActionableReferenceErrorWithoutRemapping() {
    var found = cases.search(analyst, bytes(mapper.createObjectNode().put("orgBank", "760").put("orgBranch", "1352")
        .put("inquiryDate", "2026-09-14").put("recordCount", 20)));
    ObjectNode created = cases.createCase(analyst, bytes(mapper.createObjectNode()
        .put("candidateId", found.path("items").get(1).path("candidateId").asText()).put("reason", "A second original synthetic case")), "second-case-json-test");
    String differentCase = created.path("caseId").asText(); ObjectNode source = payload();
    assertThat(differentCase).isNotEqualTo(caseId);
    assertThatThrownBy(() -> service.submit(analyst, differentCase, bytes(source), "JSON", "wrong-selected-case"))
        .isInstanceOfSatisfying(ApiException.class, failure -> {
          assertThat(failure.status).isEqualTo(422); assertThat(failure.getMessage()).contains("payment.reference", "matching payment case", "do not rewrite")
              .doesNotContain(source.path("payment").path("reference").asText());
        });
    assertThat(count()).isZero();
    assertThat(cases.caseDetail(analyst, differentCase).path("evidenceStatus").asText()).isEqualTo("DISCOVERY_ONLY");
    ObjectNode saved = service.submit(analyst, caseId, bytes(source), "JSON", "correct-selected-case");
    assertThat(saved.path("payload")).isEqualTo(source); assertThat(count()).isEqualTo(1);
    assertThat(service.list(analyst, differentCase).path("items")).isEmpty();
  }

  @Test void diagnosticsDistinguishWrongSchemaIdentityFieldAndNumericTypeWithoutEchoingSourceValues() {
    Map<String,Consumer<ObjectNode>> changes = new LinkedHashMap<>();
    changes.put("schemaVersion", p -> p.put("schemaVersion", "PRIVATE-VERSION"));
    changes.put("payment.orgBank", p -> ((ObjectNode)p.path("payment")).put("orgBank", "999"));
    changes.put("payment.orgBranch", p -> ((ObjectNode)p.path("payment")).put("orgBranch", "9999"));
    changes.put("PAYMENT row 1.NUMAMOUNT_4038", p -> row(p,"PAYMENT").put("NUMAMOUNT_4038", 123.007));
    changes.put("HOST row 1: COD_ORG_BANK", p -> row(p,"HOST").put("COD_ORG_BANK", "999"));
    for(var entry : changes.entrySet()) {
      ObjectNode input = payload(); entry.getValue().accept(input);
      assertThatThrownBy(() -> service.submit(analyst,caseId,bytes(input),"JSON","diagnostic-save"))
          .isInstanceOfSatisfying(ApiException.class, failure -> {
            assertThat(failure.status).isEqualTo(422); assertThat(failure.getMessage()).contains(entry.getKey())
                .doesNotContain("PRIVATE-VERSION", "123.007", "9999");
          });
    }
    ObjectNode numericBank = payload(); ((ObjectNode)numericBank.path("payment")).put("orgBank",760);
    assertThatThrownBy(() -> service.submit(analyst,caseId,bytes(numericBank),"JSON","numeric-bank-test"))
        .hasMessageContaining("payment.orgBank: use quoted JSON text");
    assertThat(count()).isZero();
  }

  @Test void missingHeadersAndUnsupportedShapesHavePreciseLocationsAndKeepTheTemplateContract() {
    ObjectNode missing = payload(); row(missing,"HISTORY").remove("DAT_POST");
    assertThatThrownBy(() -> service.submit(analyst,caseId,bytes(missing),"JSON","missing-column-test"))
        .hasMessageContaining("HISTORY row 1: missing template field(s): DAT_POST");
    ObjectNode extra = payload(); row(extra,"PAYMENT").put("PRIVATE-UNKNOWN-FIELD","PRIVATE-SOURCE-VALUE");
    assertThatThrownBy(() -> service.submit(analyst,caseId,bytes(extra),"JSON","extra-column-test"))
        .satisfies(failure -> assertThat(failure.getMessage()).contains("PAYMENT row 1: unsupported field")
            .doesNotContain("PRIVATE-UNKNOWN-FIELD", "PRIVATE-SOURCE-VALUE"));
    ObjectNode badRows = payload(); ((ObjectNode)badRows.path("sections").path("HOST")).putObject("rows");
    assertThatThrownBy(() -> service.submit(analyst,caseId,bytes(badRows),"JSON","bad-rows-type"))
        .hasMessageContaining("HOST.rows: use a JSON array");
    ObjectNode envelope = mapper.createObjectNode(); envelope.set("payload", payload());
    assertThatThrownBy(() -> service.submit(analyst,caseId,bytes(envelope),"JSON","saved-envelope-test"))
        .hasMessageContaining("saved snapshot envelope").hasMessageContaining("do not change its payment identity");
    assertThat(count()).isZero();
  }

  @Test void apiValidationRetainsAGroupSpecificSafeCauseAndCreatesNoVersionOnFailure() {
    ObjectNode response = apiResponse(); acquisition(response,"HISTORY").put("rowCount",99);
    CaseEvidenceService api = api(new AtomicReference<>(response),new AtomicInteger());
    assertThatThrownBy(() -> api.inquire(analyst,caseId,emptyRequest(),"api-diagnostic-test"))
        .isInstanceOfSatisfying(ApiException.class,failure -> {
          assertThat(failure.status).isEqualTo(502); assertThat(failure.code).isEqualTo("INVALID_CASE_EVIDENCE_RESPONSE");
          assertThat(failure.getMessage()).contains("acquisition.HISTORY", "rowCount", "No evidence was saved")
              .doesNotContain(item.path("reference").asText());
        });
    assertThat(count()).isZero();
  }

  @Test void downloadedConfigurationTemplateRoundTripsExactlyWithoutCreatingPlaceholderSourceRows() {
    ObjectNode template = (ObjectNode) service.config(analyst,caseId).path("template");
    ObjectNode saved = service.submit(analyst,caseId,bytes(template),"JSON","configuration-template-test");
    assertThat(saved.path("payload")).isEqualTo(template);
    for(String group : CaseEvidenceSchema.COLUMNS.keySet()) {
      assertThat(saved.path("payload").path("sections").path(group).path("rows")).isEmpty();
      assertThat(saved.path("coverage").path(group).path("rowCount").asInt()).isZero();
      assertThat(saved.path("coverage").path(group).path("completion").asText()).isEqualTo("UNVERIFIED");
    }
  }
}
