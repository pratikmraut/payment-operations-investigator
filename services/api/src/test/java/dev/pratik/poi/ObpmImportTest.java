package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"poi.import-fixtures=false", "spring.datasource.url=jdbc:h2:mem:obpm-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class ObpmImportTest {
  @Autowired ObjectMapper mapper;
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired ObpmImportService imports;
  @Autowired CaseStore cases;
  @Autowired InvestigationService investigations;
  @MockitoBean WorkerClient worker;
  final Actor analyst = new Actor("analyst", "Analyst", "ANALYST", "northstar");
  final Actor reviewer = new Actor("reviewer", "Reviewer", "REVIEWER", "northstar");

  @BeforeEach void clean() {
    for (String table : List.of("review_decision", "investigation", "audit_event", "obpm_import", "obpm_evidence_snapshot", "payment_case"))
      db.update("DELETE FROM " + table);
  }

  ObjectNode sample() throws Exception {
    return (ObjectNode) mapper.readTree("""
      {"schemaVersion":"obpm-evidence-v1","dataClassification":"SYNTHETIC","snapshotId":"SNAP-1",
       "mappingVersion":"synthetic-v1","extractedAt":"2026-09-12T05:20:00Z",
       "source":{"deploymentId":"SYNTHETIC-OBPM","releaseFamily":"14.7","exactMaintenanceRelease":null,"hostCode":"DEMO-HOST","branchCode":"DEMO-BRANCH"},
       "payment":{"sourcePaymentId":"DEMO-1","rail":"NEFT","direction":"OUTBOUND","sourceAmountDecimal":"12500.00","amountMinor":1250000,"currency":"INR","activationDate":"2026-09-12","createdAt":"2026-09-12T05:00:00Z","nativeTransactionStatus":null,"statusUnavailableReason":"Not supplied"},
       "queueRecords":[{"evidenceId":"Q-1","sourcePaymentId":"DEMO-1","queueReference":"QUEUE-1","requestAttemptId":"REQ-1","nativeQueueCode":"EC","nativeResponseStatus":"T","enteredAt":"2026-09-12T05:01:00Z","exitedAt":null,"isCurrentQueueRecord":true,"observedAt":"2026-09-12T05:20:00Z"}],
       "externalRequestAttempts":[{"evidenceId":"ECA-1","requestAttemptId":"REQ-1","sourcePaymentId":"DEMO-1","requestType":"ECA","requestedAt":"2026-09-12T05:00:30Z","timeoutRecordedAt":"2026-09-12T05:01:00Z","externalSystemFinalOutcome":null}],
       "messages":[],"accountingEntries":[],"sourceCoverage":{
       "queueRecords":{"status":"COMPLETE","scope":"DEMO-1 queues","asOf":"2026-09-12T05:20:00Z","paginationComplete":true},
       "externalCoreResponses":{"status":"UNAVAILABLE","reason":"Not connected"},"messages":{"status":"NOT_REQUESTED","reason":"Not supported"},"accountingEntries":{"status":"UNAVAILABLE","reason":"Not connected"}}}
      """);
  }
  ObjectNode changed(ObjectNode original) {
    ObjectNode next = original.deepCopy();
    next.put("snapshotId", "SNAP-2").put("extractedAt", "2026-09-12T05:30:00Z");
    ((ObjectNode) next.path("queueRecords").get(0)).put("nativeResponseStatus", "P");
    return next;
  }
  ObjectNode response(ObjectNode input, String action, String evidence) {
    ObjectNode result = mapper.createObjectNode().put("id", input.path("investigationId").asText())
        .put("caseId", input.path("case").path("id").asText()).put("mode", "replay")
        .put("outcome", "OBPM_ECA_TIMEOUT").put("summary", "A timeout is recorded; external outcome remains unknown.").put("confidence", "MEDIUM");
    var finding = result.putArray("findings").addObject().put("text", "Current EC/T record.");
    finding.putArray("evidenceIds").add(evidence); finding.putArray("citationIds").add("RB-OBPM:v1");
    result.putArray("citations").addObject().put("id", "RB-OBPM:v1").put("documentId", "RB-OBPM").put("version", 1);
    result.putArray("toolCalls"); result.putArray("warnings"); result.putArray("missingEvidence").add("External-core outcome");
    result.putObject("proposal").put("action", action); result.putObject("metrics").put("modelCalls", 0);
    return result;
  }

  @Test void importIsTenantScopedAndDuplicateDoesNotChangeCaseOrEvidence() throws Exception {
    ObjectNode input = sample(), first = imports.ingest(input, analyst);
    String id = first.path("caseId").asText();
    cases.setStatus(id, "northstar", "NEEDS_EVIDENCE", 2);
    ObjectNode reversed = mapper.createObjectNode();
    var names = new java.util.ArrayList<String>(); input.fieldNames().forEachRemaining(names::add);
    java.util.Collections.reverse(names); names.forEach(name -> reversed.set(name, input.get(name)));
    ObjectNode duplicate = imports.ingest(reversed, analyst);
    assertThat(duplicate.path("status").asText()).isEqualTo("UNCHANGED");
    assertThat(duplicate.path("evidenceVersion").asLong()).isEqualTo(1);
    assertThat(duplicate.path("caseVersion").asLong()).isEqualTo(2);
    assertThat(cases.caseDetail(id, "northstar").path("status").asText()).isEqualTo("NEEDS_EVIDENCE");
    assertThat(imports.versions(id, "northstar", true)).hasSize(1);
    ObjectNode other = imports.ingest(input, new Actor("other", "Other", "ANALYST", "silverline"));
    assertThat(other.path("caseId").asText()).isNotEqualTo(id);
    assertThatThrownBy(() -> imports.versions(id, "silverline", true)).isInstanceOf(ApiException.class);
    assertThat(imports.receipts("silverline")).hasSize(1);
  }

  @Test void updateRetainsImmutableHistoryAndRejectsStaleCutoffAndReusedSnapshotId() throws Exception {
    ObjectNode input = sample(); String id = imports.ingest(input, analyst).path("caseId").asText();
    ObjectNode next = changed(input); ObjectNode receipt = imports.ingest(next, analyst);
    assertThat(receipt.path("status").asText()).isEqualTo("UPDATED");
    var versions = imports.versions(id, "northstar", true);
    assertThat(versions).hasSize(2);
    assertThat(versions.get(1).path("evidence")).isEqualTo(input);
    assertThatThrownBy(() -> imports.ingest(input, analyst)).isInstanceOf(ApiException.class).hasMessageContaining("later extraction");
    ObjectNode collision = changed(input).put("snapshotId", "SNAP-1").put("extractedAt", "2026-09-12T05:40:00Z");
    assertThatThrownBy(() -> imports.ingest(collision, analyst)).isInstanceOf(ApiException.class).hasMessageContaining("snapshot ID");
    assertThat(imports.versions(id, "northstar", true)).hasSize(2);
  }

  @Test void invalidMoneyCorrelationCoverageAndUnknownFieldsHaveNoPersistenceEffects() throws Exception {
    List<Consumer<ObjectNode>> invalid = List.of(
        n -> ((ObjectNode) n.path("payment")).put("amountMinor", 1250001),
        n -> ((ObjectNode) n.path("payment")).put("sourceAmountDecimal", "12500.001"),
        n -> ((ObjectNode) n.path("payment")).put("amountMinor", 9007199254740992L),
        n -> ((ObjectNode) n.path("payment")).put("amountMinor", -1),
        n -> ((ObjectNode) n.path("payment")).put("currency", "USD"),
        n -> ((ObjectNode) n.path("payment")).put("rail", "RTGS"),
        n -> n.put("tenantId", "silverline"),
        n -> n.put("dataClassification", "PRODUCTION"),
        n -> ((ObjectNode) n.path("source")).put("groundTruth", "timeout"),
        n -> ((ObjectNode) n.path("queueRecords").get(0)).put("sourcePaymentId", "OTHER-PAYMENT"),
        n -> ((ObjectNode) n.path("queueRecords").get(0)).put("requestAttemptId", "MISSING"),
        n -> ((ObjectNode) n.path("queueRecords").get(0)).put("observedAt", "2026-09-12T06:00:00Z"),
        n -> ((ObjectNode) n.path("externalRequestAttempts").get(0)).put("timeoutRecordedAt", "2026-09-12T04:00:00Z"),
        n -> ((ObjectNode) n.path("sourceCoverage").path("queueRecords")).put("paginationComplete", false),
        n -> n.putArray("accountingEntries").addObject().put("amountMinor", 100),
        n -> n.putArray("messages").addObject().put("messageType", "N10"));
    for (var mutate : invalid) {
      ObjectNode input = sample(); mutate.accept(input);
      assertThatThrownBy(() -> imports.ingest(input, analyst)).isInstanceOf(ApiException.class);
    }
    assertThat(db.queryForObject("SELECT COUNT(*) FROM payment_case", Integer.class)).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM obpm_import", Integer.class)).isZero();
  }

  @Test void unknownNativeCodesRemainUnknownAndAccountingIsNotZero() throws Exception {
    ObjectNode input = sample(); ((ObjectNode) input.path("queueRecords").get(0)).put("nativeResponseStatus", "CUSTOM_UNKNOWN");
    String id = imports.ingest(input, analyst).path("caseId").asText();
    ObjectNode record = cases.caseDetail(id, "northstar");
    assertThat(record.path("obpm").path("queueRecords").get(0).path("nativeResponseStatus").asText()).isEqualTo("CUSTOM_UNKNOWN");
    assertThat(record.path("reconciliation").path("ledgerNetMinor").isNull()).isTrue();
    assertThat(record.path("reconciliation").path("providerAvailable").asBoolean()).isFalse();
  }

  @Test void httpBoundaryEnforcesRoleCsrfSizeStrictJsonAndTenant() throws Exception {
    String body = sample().toString();
    mvc.perform(get("/api/obpm/imports")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/obpm/imports").with(user("viewer")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
    mvc.perform(post("/api/obpm/imports").with(user("analyst")).contentType("application/json").content(body)).andExpect(status().isForbidden());
    mvc.perform(post("/api/obpm/imports").with(user("analyst")).with(csrf()).contentType("application/json").content(" ".repeat(ObpmController.MAX_BYTES + 1))).andExpect(status().isPayloadTooLarge());
    for (String invalid : List.of(body + " {}", "{\"schemaVersion\":1,\"schemaVersion\":2}"))
      mvc.perform(post("/api/obpm/imports").with(user("analyst")).with(csrf()).contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
    mvc.perform(post("/api/obpm/imports").with(user("analyst")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CREATED"));
    String id = imports.receipts("northstar").get(0).path("caseId").asText();
    mvc.perform(get("/api/cases/" + id + "/evidence-versions").with(user("other"))).andExpect(status().isNotFound());
    mvc.perform(get("/api/obpm/imports").with(user("other"))).andExpect(jsonPath("$.items").isEmpty());
    mvc.perform(get("/api/cases/" + id + "/export").with(user("analyst"))).andExpect(jsonPath("$.evidenceVersions[0].evidence.schemaVersion").value("obpm-evidence-v1"));
  }

  @Test void sourceRefreshInvalidatesReviewAndRetainsInvestigationSnapshot() throws Exception {
    ObjectNode input = sample(); String id = imports.ingest(input, analyst).path("caseId").asText();
    when(worker.investigate(any())).thenAnswer(call -> response(call.getArgument(0), "REQUEST_EVIDENCE", "Q-1"));
    ObjectNode investigation = investigations.investigate(id, analyst, "Why is ECA waiting?", "replay");
    assertThat(investigation.path("caseSnapshot").path("evidenceVersion").asInt()).isEqualTo(1);
    ObjectNode updated = imports.ingest(changed(input), analyst);
    assertThat(updated.path("caseVersion").asInt()).isEqualTo(3);
    assertThatThrownBy(() -> investigations.decide(id, reviewer, investigation.path("id").asText(), "APPROVE", "Reviewed", 3, "stale-review-key"))
        .isInstanceOf(ApiException.class).hasMessageContaining("stale");
    assertThat(cases.investigation(investigation.path("id").asText(), "northstar").path("caseSnapshot").path("obpm")).isEqualTo(input);
    assertThat(cases.caseDetail(id, "northstar").path("status").asText()).isEqualTo("OPEN");
  }

  @Test void javaRejectsObpmResolveOrCrossCaseEvidenceRegardlessOfWorkerClaims() throws Exception {
    String id = imports.ingest(sample(), analyst).path("caseId").asText();
    when(worker.investigate(any())).thenAnswer(call -> response(call.getArgument(0), "RESOLVE_CASE", "Q-1"));
    assertThatThrownBy(() -> investigations.investigate(id, analyst, "Resolve?", "replay")).isInstanceOf(ApiException.class);
    org.mockito.Mockito.doAnswer(call -> response(call.getArgument(0), "REQUEST_EVIDENCE", "OTHER-QUEUE")).when(worker).investigate(any());
    assertThatThrownBy(() -> investigations.investigate(id, analyst, "Inspect?", "replay")).isInstanceOf(ApiException.class);
    assertThat(cases.investigations(id, "northstar")).isEmpty();
    assertThat(cases.caseDetail(id, "northstar").path("version").asInt()).isEqualTo(1);
  }

  @Test void inFlightWorkerCannotSaveAgainstRefreshedEvidence() throws Exception {
    ObjectNode input = sample(); String id = imports.ingest(input, analyst).path("caseId").asText();
    when(worker.investigate(any())).thenAnswer(call -> {
      imports.ingest(changed(input), analyst);
      return response(call.getArgument(0), "REQUEST_EVIDENCE", "Q-1");
    });
    assertThatThrownBy(() -> investigations.investigate(id, analyst, "Inspect?", "replay")).isInstanceOf(ApiException.class).hasMessageContaining("changed while");
    assertThat(cases.investigations(id, "northstar")).isEmpty();
    assertThat(imports.versions(id, "northstar", true)).hasSize(2);
  }

  @Test void simultaneousFirstImportsNeverCreateDuplicateCasesOrSnapshots() throws Exception {
    ObjectNode input = sample();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CyclicBarrier start = new CyclicBarrier(2);
    try {
      Callable<String> command = () -> {
        start.await(5, TimeUnit.SECONDS);
        try { return imports.ingest(input, analyst).path("status").asText(); }
        catch (org.springframework.dao.DataIntegrityViolationException conflict) { return "CONFLICT"; }
        catch (ApiException conflict) {
          // The other first import can commit between the case-existence and snapshot-ID reads.
          // That race reports the existing API conflict before reaching the INSERT constraint.
          if (conflict.status == 409 && conflict.code.equals("SNAPSHOT_ID_CONFLICT")) return "CONFLICT";
          throw conflict;
        }
      };
      Future<String> first = pool.submit(command), second = pool.submit(command);
      var statuses = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
      assertThat(statuses).contains("CREATED").allMatch(s -> List.of("CREATED", "UNCHANGED", "CONFLICT").contains(s));
      ObjectNode retry = imports.ingest(input, analyst);
      assertThat(retry.path("status").asText()).isEqualTo("UNCHANGED");
      assertThat(db.queryForObject("SELECT COUNT(*) FROM payment_case", Integer.class)).isEqualTo(1);
      assertThat(db.queryForObject("SELECT COUNT(*) FROM obpm_evidence_snapshot", Integer.class)).isEqualTo(1);
    } finally { pool.shutdownNow(); }
  }
}
