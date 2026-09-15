package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
    properties = {
      "poi.import-fixtures=false",
      "spring.datasource.url=jdbc:h2:mem:poi-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "poi.worker-timeout-seconds=1"
    })
@AutoConfigureMockMvc
class ApiIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate db;
  @Autowired CaseStore store;
  @Autowired InvestigationService service;
  static HttpServer worker;
  static final AtomicInteger calls = new AtomicInteger();
  static volatile String behavior = "ok";
  static volatile String workerUpgradeHeader;

  @Test
  @SuppressWarnings("unchecked")
  void workerRequestUsesConfiguredTimeoutWithBoundedMinimumAndMaximum() throws Exception {
    for (long[] bounds : new long[][] {{390, 390}, {10000, 390}, {0, 1}}) {
      HttpClient transport = mock(HttpClient.class);
      HttpResponse<String> response = mock(HttpResponse.class);
      when(response.statusCode()).thenReturn(200);
      when(response.body()).thenReturn("{}");
      when(transport.send(
              any(HttpRequest.class),
              org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
          .thenReturn(response);
      WorkerClient configured =
          new WorkerClient(mapper, "http://worker.invalid", "test-service-key", bounds[0]);
      ReflectionTestUtils.setField(configured, "client", transport);

      configured.investigate(mapper.createObjectNode());

      ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
      verify(transport)
          .send(
              request.capture(),
              org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
      assertThat(request.getValue().timeout())
          .as("configured timeout %s on outgoing worker request", bounds[0])
          .contains(Duration.ofSeconds(bounds[1]));
    }
  }

  static {
    try {
      worker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      worker.createContext(
          "/investigate",
          exchange -> {
            calls.incrementAndGet();
            workerUpgradeHeader = exchange.getRequestHeaders().getFirst("Upgrade");
            if (!"local-service-key-change-me"
                .equals(exchange.getRequestHeaders().getFirst("X-Service-Key"))) {
              exchange.sendResponseHeaders(403, -1);
              exchange.close();
              return;
            }
            if (behavior.equals("fail")) {
              exchange.sendResponseHeaders(503, -1);
              exchange.close();
              return;
            }
            try {
              var mapper = new ObjectMapper();
              JsonNode input = mapper.readTree(exchange.getRequestBody());
              ObjectNode result = mapper.createObjectNode();
              result
                  .put("id", input.path("investigationId").asText())
                  .put("caseId", input.path("case").path("id").asText())
                  .put("mode", input.path("mode").asText())
                  .put("outcome", "TIMEOUT_AFTER_SUCCESS")
                  .put("summary", "Provider succeeded; inspect evidence before any retry.")
                  .put("confidence", "HIGH");
              result
                  .putArray("findings")
                  .addObject()
                  .put("id", "F-1")
                  .put("text", "The synthetic provider acknowledged success.")
                  .putArray("evidenceIds")
                  .add(behavior.equals("bad-evidence") ? "OTHER-TENANT-EVENT" : "EVT-1");
              ((ObjectNode) result.path("findings").get(0))
                  .putArray("citationIds")
                  .add("RB-TIMEOUT:v1");
              result
                  .putArray("citations")
                  .addObject()
                  .put("id", "RB-TIMEOUT:v1")
                  .put("documentId", "RB-TIMEOUT")
                  .put("version", 1)
                  .put("title", "Timeout")
                  .put("excerpt", "Check outcome before retry.")
                  .put("source", "Original simulated policy")
                  .put("score", 1);
              result.putArray("missingEvidence");
              result
                  .putArray("toolCalls")
                  .addObject()
                  .put("name", "get_payment_timeline")
                  .put("status", "COMPLETED")
                  .put("durationMs", 1)
                  .putArray("evidenceIds")
                  .add("EVT-1");
              result
                  .putObject("proposal")
                  .put("action", "RESOLVE_CASE")
                  .put("reason", "Acknowledgement exists.");
              result
                  .putObject("metrics")
                  .put("durationMs", 2)
                  .put("toolCount", 1)
                  .put("modelCalls", input.path("mode").asText().equals("ollama") ? 1 : 0)
                  .put("retrievalMode", "lexical");
              result.putArray("warnings").add("Synthetic test worker.");
              if (behavior.equals("unsafe-resolution")) {
                result.put("outcome", "INSUFFICIENT_EVIDENCE").put("confidence", "INSUFFICIENT");
              }
              if (behavior.equals("empty-findings")) {
                result.putArray("findings");
              }
              if (behavior.startsWith("resolve:")) {
                result.put("outcome", behavior.substring("resolve:".length()));
              }
              if (behavior.equals("wrong-case")) result.put("caseId", "CASE-2");
              if (behavior.equals("wrong-investigation")) result.put("id", "INV-WRONG");
              if (behavior.equals("missing-case")) result.remove("caseId");
              if (behavior.equals("missing-investigation")) result.remove("id");
              byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes);
              exchange.close();
            } catch (Exception e) {
              exchange.close();
            }
          });
      worker.start();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void unresolvedOrUnsupportedResultsCannotProposeResolution() throws Exception {
    Session analyst = login("analyst");
    for (String invalidBehavior : List.of("unsafe-resolution", "empty-findings")) {
      behavior = invalidBehavior;
      mvc.perform(
              signed(post("/api/cases/CASE-1/investigations"), analyst)
                  .content("{\"question\":\"Investigate\",\"mode\":\"replay\"}"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("INVALID_WORKER_RESPONSE"));
    }
    assertThat(store.investigations("CASE-1", "northstar")).isEmpty();
    assertThat(store.caseDetail("CASE-1", "northstar").path("status").asText()).isEqualTo("OPEN");
  }

  @Test
  void workerResponseMustMatchSubmittedCaseAndInvestigationBeforeProvenanceIsRewritten()
      throws Exception {
    Session analyst = login("analyst");
    for (String mismatch :
        List.of("wrong-case", "wrong-investigation", "missing-case", "missing-investigation")) {
      behavior = mismatch;
      mvc.perform(
              signed(post("/api/cases/CASE-1/investigations"), analyst)
                  .content("{\"question\":\"Investigate\",\"mode\":\"replay\"}"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("INVALID_WORKER_RESPONSE"));
      assertThat(store.investigations("CASE-1", "northstar")).isEmpty();
      assertThat(store.investigations("CASE-2", "silverline")).isEmpty();
      assertThat(store.caseDetail("CASE-1", "northstar").path("version").longValue()).isEqualTo(1);
    }
    assertThat(calls.get()).isEqualTo(4);
  }

  @Test
  void linkedResolutionCannotHideKnownPositiveOrNegativePayoutMismatch() throws Exception {
    Session analyst = login("analyst");
    for (long payout : new long[] {99700, 100300}) {
      String caseId = "CASE-MISMATCH-" + payout;
      ObjectNode mismatched = fixture(caseId, "northstar", 100000);
      ((ObjectNode) mismatched.path("provider")).put("payoutMinor", payout);
      store.importCase(mismatched);
      assertThat(
              store
                  .caseDetail(caseId, "northstar")
                  .path("reconciliation")
                  .path("discrepancyMinor")
                  .longValue())
          .isEqualTo(100000 - payout);

      mvc.perform(
              signed(post("/api/cases/" + caseId + "/investigations"), analyst)
                  .content("{\"question\":\"Investigate\",\"mode\":\"replay\"}"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("INVALID_WORKER_RESPONSE"));

      assertThat(store.investigations(caseId, "northstar")).isEmpty();
      ObjectNode unchanged = store.caseDetail(caseId, "northstar");
      assertThat(unchanged.path("status").asText()).isEqualTo("OPEN");
      assertThat(unchanged.path("version").longValue()).isEqualTo(1);
      assertThat(store.auditEvents(caseId, "northstar"))
          .anySatisfy(
              event -> assertThat(event.path("action").asText()).isEqualTo("INVESTIGATION_FAILED"));
    }
  }

  @Test
  void linkedResolutionCannotTreatUnknownOrAbsentProviderPayoutAsZero() throws Exception {
    Session analyst = login("analyst");
    for (String scenario : List.of("UNKNOWN", "MISSING_PAYOUT")) {
      String caseId = "CASE-" + scenario;
      ObjectNode unavailable = fixture(caseId, "northstar", 100000);
      ObjectNode provider = (ObjectNode) unavailable.path("provider");
      if (scenario.equals("UNKNOWN")) provider.put("status", "UNKNOWN").put("payoutMinor", 0);
      else provider.remove("payoutMinor");
      store.importCase(unavailable);

      mvc.perform(
              signed(post("/api/cases/" + caseId + "/investigations"), analyst)
                  .content("{\"question\":\"Investigate\",\"mode\":\"replay\"}"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("INVALID_WORKER_RESPONSE"));

      assertThat(store.investigations(caseId, "northstar")).isEmpty();
      assertThat(store.caseDetail(caseId, "northstar").path("version").longValue()).isEqualTo(1);
    }
  }

  @Test
  void knownBalancedMoneyStillAllowsEachSupportedResolutionOutcome() throws Exception {
    Session analyst = login("analyst");
    for (String outcome :
        List.of("TIMEOUT_AFTER_SUCCESS", "DUPLICATE_WEBHOOK", "OUT_OF_ORDER_WEBHOOK")) {
      String caseId = "CASE-" + outcome;
      store.importCase(fixture(caseId, "northstar", 100000));
      behavior = "resolve:" + outcome;
      mvc.perform(
              signed(post("/api/cases/" + caseId + "/investigations"), analyst)
                  .content("{\"question\":\"Investigate\",\"mode\":\"replay\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.outcome").value(outcome))
          .andExpect(jsonPath("$.proposal.action").value("RESOLVE_CASE"));
      assertThat(store.caseDetail(caseId, "northstar").path("status").asText())
          .isEqualTo("AWAITING_REVIEW");
    }
  }

  @Test
  void exportIncludesReviewEvidenceButNoPrivateIdempotencyMetadata() throws Exception {
    JsonNode result = create(login("analyst"));
    Session reviewer = login("reviewer");
    mvc.perform(
            signed(post("/api/cases/CASE-1/decisions"), reviewer)
                .header("Idempotency-Key", "export-review-001")
                .content(command(result.path("id").asText(), 2, "Review evidence retained.")))
        .andExpect(status().isOk());
    mvc.perform(signed(get("/api/cases/CASE-1/export"), reviewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decisions[0].reviewedBy").value("reviewer"))
        .andExpect(jsonPath("$.decisions[0].note").value("Review evidence retained."))
        .andExpect(jsonPath("$.decisions[0].idempotencyKey").doesNotExist())
        .andExpect(jsonPath("$.decisions[0].requestHash").doesNotExist());
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("poi.worker-url", () -> "http://127.0.0.1:" + worker.getAddress().getPort());
  }

  @AfterAll
  static void stopWorker() {
    worker.stop(0);
  }

  @BeforeEach
  void reset() throws Exception {
    db.update("DELETE FROM review_decision");
    db.update("DELETE FROM investigation");
    db.update("DELETE FROM audit_event");
    db.update("DELETE FROM payment_case");
    store.importCase(fixture("CASE-1", "northstar", 100000));
    store.importCase(fixture("CASE-2", "silverline", 9900));
    behavior = "ok";
    calls.set(0);
    workerUpgradeHeader = null;
  }

  ObjectNode fixture(String id, String tenant, long amount) throws Exception {
    ObjectNode result =
        (ObjectNode)
            mapper.readTree(
                """
          {"id":"CASE-1","tenantId":"northstar","paymentId":"PAY-1","title":"Timeout","description":"A synthetic request timed out.",
          "priority":"HIGH","status":"OPEN","amountMinor":100000,"currency":"INR","rail":"SIMULATED_TRANSFER","merchant":"Juniper",
          "createdAt":"2026-09-11T09:00:00Z","updatedAt":"2026-09-11T09:00:00Z","version":1,"policyDate":"2026-09-11","tags":["timeout"],
          "events":[{"id":"EVT-1","type":"PROVIDER_ACK","occurredAt":"2026-09-11T09:00:01Z","summary":"Acknowledged","status":"SUCCESS","source":"simulator"}],
          "ledgerEntries":[{"id":"LED-1","type":"CAPTURE","amountMinor":100000,"currency":"INR"}],"webhooks":[],
          "provider":{"status":"SUCCEEDED","paymentId":"PRV-1","amountMinor":100000,"feeMinor":0,"refundMinor":0,"payoutMinor":100000}}
          """);
    result.put("id", id).put("tenantId", tenant).put("amountMinor", amount);
    return result;
  }

  record Session(MockHttpSession session, String csrf) {}

  Session login(String username) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/auth/login")
                    .contentType("application/json")
                    .content(
                        mapper.writeValueAsString(
                            java.util.Map.of("username", username, "password", "demo-pass-local"))))
            .andExpect(status().isOk())
            .andReturn();
    return new Session(
        (MockHttpSession) result.getRequest().getSession(false),
        mapper.readTree(result.getResponse().getContentAsString()).path("csrfToken").asText());
  }

  MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder request, Session session) {
    return request
        .session(session.session())
        .header("X-CSRF-Token", session.csrf())
        .contentType("application/json");
  }

  JsonNode create(Session session) throws Exception {
    return mapper.readTree(
        mvc.perform(
                signed(post("/api/cases/CASE-1/investigations"), session)
                    .content("{\"question\":\"Why did this time out?\",\"mode\":\"replay\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  String command(String id, long version, String note) throws Exception {
    return mapper.writeValueAsString(
        java.util.Map.of(
            "investigationId",
            id,
            "expectedVersion",
            version,
            "decision",
            "APPROVE",
            "note",
            note));
  }

  @Test
  void healthIsPublicButBusinessEndpointsRequireSession() throws Exception {
    mvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("synthetic"));
    mvc.perform(get("/api/cases"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.requestId").isNotEmpty());
    mvc.perform(
            post("/api/auth/login")
                .contentType("application/json")
                .content("{\"username\":\"analyst\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void sessionTenantCannotBeOverriddenAndCrossTenantRecordsAre404() throws Exception {
    Session analyst = login("analyst");
    mvc.perform(signed(get("/api/cases").header("X-Tenant-Id", "silverline"), analyst))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].id").value("CASE-1"))
        .andExpect(jsonPath("$.items[0].events").doesNotExist());
    for (String suffix : List.of("", "/audit", "/export", "/investigations"))
      mvc.perform(signed(get("/api/cases/CASE-2" + suffix), analyst))
          .andExpect(status().isNotFound());
    mvc.perform(signed(get("/api/cases"), login("other")))
        .andExpect(jsonPath("$.items[0].id").value("CASE-2"));
  }

  @Test
  void mutationsRequireCsrfAndViewerCannotCreate() throws Exception {
    Session analyst = login("analyst");
    String body = "{\"question\":\"Investigate\",\"mode\":\"replay\"}";
    mvc.perform(
            post("/api/cases/CASE-1/investigations")
                .session(analyst.session())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(signed(post("/api/cases/CASE-1/investigations"), login("viewer")).content(body))
        .andExpect(status().isForbidden());
    assertThat(calls.get()).isZero();
  }

  @Test
  void investigationIsDurableAndContainsServerOwnedProvenance() throws Exception {
    JsonNode result = create(login("analyst"));
    assertThat(workerUpgradeHeader)
        .as("HTTP/1.1 worker request must not ask Uvicorn for h2c upgrade")
        .isNull();
    assertThat(result.path("createdBy").asText()).isEqualTo("analyst");
    assertThat(result.path("caseVersion").asLong()).isEqualTo(2);
    assertThat(result.path("snapshotHash").asText()).hasSize(64);
    assertThat(result.path("metrics").path("modelCalls").asInt()).isZero();
    assertThat(store.investigation(result.path("id").asText(), "northstar")).isEqualTo(result);
    assertThat(store.caseDetail("CASE-1", "northstar").path("status").asText())
        .isEqualTo("AWAITING_REVIEW");
    mvc.perform(signed(get("/api/investigations/" + result.path("id").asText()), login("other")))
        .andExpect(status().isNotFound());
  }

  @Test
  void analystCannotApproveAndReviewerCannotApproveTheirOwnWork() throws Exception {
    Session analyst = login("analyst"), reviewer = login("reviewer");
    JsonNode a = create(analyst);
    mvc.perform(
            signed(post("/api/cases/CASE-1/decisions"), analyst)
                .header("Idempotency-Key", "decision-001")
                .content(command(a.path("id").asText(), 2, "Reviewed")))
        .andExpect(status().isForbidden());
    JsonNode b = create(reviewer);
    mvc.perform(
            signed(post("/api/cases/CASE-1/decisions"), reviewer)
                .header("Idempotency-Key", "decision-002")
                .content(command(b.path("id").asText(), 3, "Reviewed")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("MAKER_CHECKER_REQUIRED"));
  }

  @Test
  void approvalIsIdempotentAndDoesNotAlterMoneyOrStoredInvestigation() throws Exception {
    JsonNode result = create(login("analyst"));
    Session reviewer = login("reviewer");
    String body = command(result.path("id").asText(), 2, "Verified provider acknowledgement.");
    var before = store.caseDetail("CASE-1", "northstar").path("ledgerEntries").deepCopy();
    mvc.perform(
            signed(post("/api/cases/CASE-1/decisions"), reviewer)
                .header("Idempotency-Key", "approve-001")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.replayed").value(false))
        .andExpect(jsonPath("$.caseStatus").value("RESOLVED"));
    mvc.perform(
            signed(post("/api/cases/CASE-1/decisions"), reviewer)
                .header("Idempotency-Key", "approve-001")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.replayed").value(true));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM review_decision", Integer.class))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action='REVIEW_APPROVE'", Integer.class))
        .isEqualTo(1);
    assertThat(store.caseDetail("CASE-1", "northstar").path("ledgerEntries")).isEqualTo(before);
    assertThat(store.investigation(result.path("id").asText(), "northstar")).isEqualTo(result);
    mvc.perform(
            signed(post("/api/cases/CASE-1/decisions"), reviewer)
                .header("Idempotency-Key", "approve-001")
                .content(command(result.path("id").asText(), 2, "Different note")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
  }

  @Test
  void staleCaseAndSupersededProposalCannotBeApproved() throws Exception {
    Session analyst = login("analyst"), reviewer = login("reviewer");
    JsonNode first = create(analyst);
    mvc.perform(
            signed(post("/api/cases/CASE-1/decisions"), reviewer)
                .header("Idempotency-Key", "stale-001")
                .content(command(first.path("id").asText(), 1, "Reviewed")))
        .andExpect(status().isConflict());
    create(analyst);
    mvc.perform(
            signed(post("/api/cases/CASE-1/decisions"), reviewer)
                .header("Idempotency-Key", "stale-002")
                .content(command(first.path("id").asText(), 3, "Reviewed")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test
  void missingKeyAndClientSuppliedActionAreRejected() throws Exception {
    JsonNode result = create(login("analyst"));
    Session reviewer = login("reviewer");
    mvc.perform(
            signed(post("/api/cases/CASE-1/decisions"), reviewer)
                .content(command(result.path("id").asText(), 2, "Reviewed")))
        .andExpect(status().isBadRequest());
    ObjectNode input =
        (ObjectNode) mapper.readTree(command(result.path("id").asText(), 2, "Reviewed"));
    input.put("action", "MOVE_MONEY");
    mvc.perform(
            signed(post("/api/cases/CASE-1/decisions"), reviewer)
                .header("Idempotency-Key", "action-001")
                .content(input.toString()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void workerFailureIsHonestAndAuditedWithoutFallback() throws Exception {
    behavior = "fail";
    Session analyst = login("analyst");
    mvc.perform(
            signed(post("/api/cases/CASE-1/investigations"), analyst)
                .content("{\"question\":\"Investigate\",\"mode\":\"ollama\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("WORKER_UNAVAILABLE"));
    assertThat(store.investigations("CASE-1", "northstar")).isEmpty();
    assertThat(store.caseDetail("CASE-1", "northstar").path("version").asLong()).isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action='INVESTIGATION_FAILED'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void unauthorizedEvidenceIsNotStored() throws Exception {
    behavior = "bad-evidence";
    mvc.perform(
            signed(post("/api/cases/CASE-1/investigations"), login("analyst"))
                .content("{\"question\":\"Investigate\",\"mode\":\"replay\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("INVALID_WORKER_RESPONSE"));
    assertThat(store.investigations("CASE-1", "northstar")).isEmpty();
  }

  @Test
  void moneyRemainsExactAndFractionalMinorUnitsAreRejected() throws Exception {
    ObjectNode fractional = fixture("CASE-3", "northstar", 100);
    fractional.put("amountMinor", 1.5);
    assertThatThrownBy(() -> store.importCase(fractional))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("integer");
    assertThatThrownBy(
            () ->
                store.importCase(fixture("CASE-4", "northstar", JsonSupport.MAX_SAFE_INTEGER + 1)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("JSON-safe");
    store.importCase(fixture("CASE-4", "northstar", JsonSupport.MAX_SAFE_INTEGER - 100000));
    mvc.perform(signed(get("/api/dashboard"), login("analyst")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalAmountMinor").value(JsonSupport.MAX_SAFE_INTEGER));
    store.importCase(fixture("CASE-5", "northstar", 1));
    mvc.perform(signed(get("/api/dashboard"), login("analyst")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("AMOUNT_OUT_OF_RANGE"))
        .andExpect(
            jsonPath("$.message").value(org.hamcrest.Matchers.containsString("totalAmountMinor")));
  }

  @Test
  void ledgerAliasesReachPublicReconciliationAndInvalidSignsCannotBeImported() throws Exception {
    ObjectNode alias = fixture("CASE-ALIASES", "northstar", 100000);
    ((ObjectNode) alias.path("ledgerEntries").get(0)).put("type", "payment-captured");
    alias.withArray("ledgerEntries")
        .addObject()
        .put("id", "LED-REFUND-POSTED")
        .put("type", "refund.posted")
        .put("amountMinor", -10000)
        .put("currency", "INR");
    ((ObjectNode) alias.path("provider")).put("refundMinor", 10000).put("payoutMinor", 90000);
    store.importCase(alias);
    mvc.perform(signed(get("/api/cases/CASE-ALIASES"), login("analyst")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reconciliation.validMoney").value(true))
        .andExpect(jsonPath("$.reconciliation.calculatedBy").value("java-api"))
        .andExpect(jsonPath("$.reconciliation.captureCount").value(1))
        .andExpect(jsonPath("$.reconciliation.captureMinor").value(100000))
        .andExpect(jsonPath("$.reconciliation.refundMinor").value(10000))
        .andExpect(jsonPath("$.reconciliation.ledgerNetMinor").value(90000))
        .andExpect(jsonPath("$.reconciliation.discrepancyMinor").value(0));

    ObjectNode invalid = fixture("CASE-INVALID-SIGN", "northstar", 100000);
    ((ObjectNode) invalid.path("ledgerEntries").get(0)).put("type", "sale").put("amountMinor", -1);
    assertThatThrownBy(() -> store.importCase(invalid))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("positive");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM payment_case WHERE id='CASE-INVALID-SIGN'", Integer.class))
        .isZero();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE case_id='CASE-INVALID-SIGN'", Integer.class))
        .isZero();
  }

  @Test
  void nestedOperationalMoneyCannotBypassImportRangeOrCurrencyChecks() throws Exception {
    ObjectNode unsafe = fixture("CASE-3", "northstar", 100);
    ((ObjectNode) unsafe.path("events").get(0))
        .putObject("attributes")
        .put("amountMinor", JsonSupport.MAX_SAFE_INTEGER + 1)
        .put("currency", "INR");
    assertThatThrownBy(() -> store.importCase(unsafe))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("JSON-safe");
    ((ObjectNode) unsafe.path("events").get(0).path("attributes"))
        .put("amountMinor", 100)
        .put("currency", "USD");
    assertThatThrownBy(() -> store.importCase(unsafe))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("currency");
    assertThat(store.cases("northstar", null, null, null)).hasSize(1);
  }

  @Test
  void fixtureReimportPreservesExistingStateAndRejectsLabels() throws Exception {
    create(login("analyst"));
    store.importCase(fixture("CASE-1", "northstar", 1));
    assertThat(store.caseDetail("CASE-1", "northstar").path("version").asLong()).isEqualTo(2);
    ObjectNode labelled = fixture("CASE-3", "northstar", 100);
    labelled.put("expectedOutcome", "secret");
    assertThatThrownBy(() -> store.importCase(labelled))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("labels");
  }

  @Test
  void nestedKnownEvaluationLabelsAreRejectedBeforeTheyCanEnterCaseDataOrExports()
      throws Exception {
    for (String label : List.of("scenarioFamily", "groundTruth", "expectedOutcome")) {
      for (String location : List.of("provider", "event-attributes", "nested-array")) {
        String id = "CASE-LABEL-" + label + "-" + location;
        ObjectNode labelled = fixture(id, "northstar", 100000);
        ObjectNode destination;
        if (location.equals("provider")) {
          destination = (ObjectNode) labelled.path("provider");
        } else {
          ObjectNode attributes =
              ((ObjectNode) labelled.path("events").get(0)).putObject("attributes");
          destination =
              location.equals("nested-array")
                  ? attributes.putArray("observations").addObject()
                  : attributes;
        }
        destination.put(label, "evaluation-only-answer");
        assertThatThrownBy(() -> store.importCase(labelled))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("labels");
        assertThat(
                db.queryForObject(
                    "SELECT COUNT(*) FROM payment_case WHERE id=?", Integer.class, id))
            .isZero();
      }
    }
    assertThat(store.cases("northstar", null, null, null)).hasSize(1);
  }

  @Test
  void recursiveLabelGuardPreservesOrdinaryNestedDomainFields() throws Exception {
    ObjectNode operational = fixture("CASE-OPERATIONAL", "northstar", 100000);
    ((ObjectNode) operational.path("provider")).put("statusReason", "Confirmed");
    ((ObjectNode) operational.path("events").get(0))
        .putObject("attributes")
        .put("outcome", "DELIVERED")
        .put("split", "single-payment")
        .put("description", "The word groundTruth inside prose is not a label key.");
    store.importCase(operational);
    ObjectNode stored = store.caseDetail("CASE-OPERATIONAL", "northstar");
    assertThat(stored.path("provider").path("statusReason").asText()).isEqualTo("Confirmed");
    assertThat(stored.path("events").get(0).path("attributes").path("outcome").asText())
        .isEqualTo("DELIVERED");
    assertThat(stored.path("events").get(0).path("attributes").path("split").asText())
        .isEqualTo("single-payment");
  }

  @Test
  void concurrentSameKeyCreatesOneDecision() throws Exception {
    JsonNode result = create(login("analyst"));
    String id = result.path("id").asText();
    Actor reviewer = new Actor("reviewer", "Reviewer", "REVIEWER", "northstar");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Callable<ObjectNode> task =
          () -> {
            start.await();
            return service.decide(
                "CASE-1", reviewer, id, "APPROVE", "Concurrent replay", 2, "parallel-001");
          };
      var a = pool.submit(task);
      var b = pool.submit(task);
      start.countDown();
      ObjectNode first = a.get(10, TimeUnit.SECONDS), second = b.get(10, TimeUnit.SECONDS);
      assertThat(first.path("id").asText()).isEqualTo(second.path("id").asText());
      assertThat(first.path("replayed").asBoolean())
          .isNotEqualTo(second.path("replayed").asBoolean());
      assertThat(db.queryForObject("SELECT COUNT(*) FROM review_decision", Integer.class))
          .isEqualTo(1);
      assertThat(store.caseDetail("CASE-1", "northstar").path("version").asLong()).isEqualTo(3);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void logoutInvalidatesSessionAndCrossSiteLoginIsBlocked() throws Exception {
    Session analyst = login("analyst");
    mvc.perform(signed(post("/api/auth/logout"), analyst)).andExpect(status().isOk());
    mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/auth/login")
                .header("Sec-Fetch-Site", "cross-site")
                .contentType("application/json")
                .content("{\"username\":\"analyst\",\"password\":\"demo-pass-local\"}"))
        .andExpect(status().isForbidden());
  }
}
