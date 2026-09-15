package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;

class ObpmInquiryClientTest {
  final ObjectMapper mapper = new ObjectMapper();
  HttpServer server;
  ExecutorService executor;
  String baseUrl;
  ObjectNode fixture() throws Exception {
    return (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/inquiry-timeout.json"));
  }
  @BeforeEach void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newCachedThreadPool(); server.setExecutor(executor); server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }
  @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
  ObpmInquiryClient client() { return new ObpmInquiryClient(mapper, true, baseUrl, "test-inquiry-key", 1); }
  void reply(int status, String type, String body) {
    server.createContext("/", exchange -> {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", type);
      exchange.sendResponseHeaders(status, bytes.length);
      try (var output = exchange.getResponseBody()) { output.write(bytes); }
    });
  }
  static void failure(ThrowingCallable operation, int status, String code) {
    assertThatThrownBy(operation).isInstanceOfSatisfying(ApiException.class, ex -> {
      assertThat(ex.status).isEqualTo(status); assertThat(ex.code).isEqualTo(code);
    });
  }
  @FunctionalInterface interface ThrowingCallable extends org.assertj.core.api.ThrowableAssert.ThrowingCallable {}

  @Test void actualHttpSendsFixedScopeAndKeyThenDerivesExactAmountWithoutChangingInput() throws Exception {
    ObjectNode raw = fixture(); AtomicInteger called = new AtomicInteger();
    server.createContext("/", exchange -> {
      assertThat(exchange.getRequestMethod()).isEqualTo("GET");
      assertThat(exchange.getRequestURI().getPath()).isEqualTo("/inquiry/v1/neft/payments/DEMO-NEFT-0001");
      assertThat(exchange.getRequestURI().getRawQuery()).isEqualTo("referenceType=PAYMENT_REFERENCE&deploymentId=SYNTHETIC-OBPM&hostCode=DEMO-HOST&branchCode=DEMO-BRANCH");
      assertThat(exchange.getRequestHeaders().getFirst("X-Service-Key")).isEqualTo("test-inquiry-key");
      byte[] bytes = raw.toString().getBytes(StandardCharsets.UTF_8); called.incrementAndGet();
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      exchange.sendResponseHeaders(200, bytes.length);
      try (var out = exchange.getResponseBody()) { out.write(bytes); }
    });
    ObjectNode result = client().fetch("DEMO-NEFT-0001");
    assertThat(called).hasValue(1);
    assertThat(result.path("payment").path("amountMinor").asLong()).isEqualTo(1250000);
    assertThat(result.path("schemaVersion").asText()).isEqualTo("obpm-evidence-v1");
    assertThat(raw.path("payment").has("amountMinor")).isFalse();
    assertThat(result.path("queueRecords")).isEqualTo(raw.path("queueRecords"));
    assertThat(result.path("sourceCoverage")).isEqualTo(raw.path("sourceCoverage"));
  }
  @Test void rejectsUnsupportedMoneyClassificationScopeAndUncorrelatedEvidence() throws Exception {
    List<Consumer<ObjectNode>> edits = List.of(
        n -> ((ObjectNode) n.path("payment")).put("sourceAmountDecimal", "1.001"),
        n -> ((ObjectNode) n.path("payment")).put("sourceAmountDecimal", "1e3"),
        n -> ((ObjectNode) n.path("payment")).put("sourceAmountDecimal", 1),
        n -> ((ObjectNode) n.path("payment")).put("sourceAmountDecimal", "99999999999999.99"),
        n -> ((ObjectNode) n.path("payment")).put("amountMinor", 1250000),
        n -> ((ObjectNode) n.path("payment")).put("currency", "USD"),
        n -> ((ObjectNode) n.path("payment")).put("sourcePaymentId", "OTHER"),
        n -> ((ObjectNode) n.path("source")).put("deploymentId", "REAL-BANK"),
        n -> n.put("dataClassification", "REAL"),
        n -> n.put("tenantId", "other"),
        n -> n.put("schemaVersion", "obpm-evidence-v1"),
        n -> n.put("mappingVersion", "unverified"),
        n -> ((ObjectNode) n.path("queueRecords").get(0)).put("requestAttemptId", "OTHER"),
        n -> n.putArray("messages").addObject().put("id", "unsupported"));
    for (Consumer<ObjectNode> edit : edits) {
      ObjectNode raw = fixture(); edit.accept(raw);
      failure(() -> ObpmInquiryClient.normalize(raw, "DEMO-NEFT-0001"), 502, "INVALID_INQUIRY_RESPONSE");
    }
  }
  @Test void preservesPartialAndConflictingEvidenceRatherThanInventingCurrentState() throws Exception {
    ObjectNode raw = fixture();
    ((ObjectNode) raw.path("sourceCoverage").path("queueRecords")).put("status", "PARTIAL").put("reason", "Source missing an interval");
    ((ObjectNode) raw.path("queueRecords").get(0)).put("nativeResponseStatus", "DEMO_UNKNOWN");
    ObjectNode normalized = ObpmInquiryClient.normalize(raw, "DEMO-NEFT-0001");
    assertThat(normalized.path("sourceCoverage")).isEqualTo(raw.path("sourceCoverage"));
    assertThat(normalized.path("queueRecords")).isEqualTo(raw.path("queueRecords"));
  }
  @Test void rejectsRemoteConfigurationAndReferenceInjectionBeforeNetwork() {
    for (String url : List.of("http://example.com", "http://127.0.0.1@evil.invalid", "http://localhost/path", "http://localhost?x=1", "http://localhost#x"))
      assertThatThrownBy(() -> new ObpmInquiryClient(mapper, true, url, "key", 1)).isInstanceOf(IllegalStateException.class);
    for (String reference : List.of("../x", "http://localhost", "x?branch=other", "", "x/y", "x\n"))
      failure(() -> client().fetch(reference), 400, "INVALID_REQUEST");
    failure(() -> new ObpmInquiryClient(mapper, false, baseUrl, "key", 1).fetch("DEMO-1"), 503, "INQUIRY_DISABLED");
  }
  @Test void notFoundDoesNotFallback() { reply(404, "application/json", "{}"); failure(() -> client().fetch("UNKNOWN"), 404, "INQUIRY_NOT_FOUND"); }
  @Test void redirectsAreNotFollowed() {
    AtomicInteger followed = new AtomicInteger();
    server.createContext("/inquiry", exchange -> {
      exchange.getResponseHeaders().set("Location", baseUrl + "/target"); exchange.sendResponseHeaders(302, -1); exchange.close();
    });
    server.createContext("/target", exchange -> { followed.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
    failure(() -> client().fetch("DEMO-1"), 502, "INQUIRY_UPSTREAM_ERROR"); assertThat(followed).hasValue(0);
  }
  @Test void oversizedBodyIsCancelled() {
    reply(200, "application/json", "x".repeat(ObpmController.MAX_BYTES + 1));
    failure(() -> client().fetch("DEMO-1"), 502, "INVALID_INQUIRY_RESPONSE");
  }
  @Test void duplicateJsonKeysAreRejected() {
    reply(200, "application/json", "{\"schemaVersion\":\"a\",\"schemaVersion\":\"b\"}");
    failure(() -> client().fetch("DEMO-1"), 502, "INVALID_INQUIRY_RESPONSE");
  }
  @Test void trailingJsonIsRejected() throws Exception {
    reply(200, "application/json", fixture().toString() + " {}");
    failure(() -> client().fetch("DEMO-NEFT-0001"), 502, "INVALID_INQUIRY_RESPONSE");
  }
  @Test void nonJsonBodyIsRejected() { reply(200, "text/html", "{}"); failure(() -> client().fetch("DEMO-1"), 502, "INVALID_INQUIRY_RESPONSE"); }
  @Test void deadlineIncludesSlowResponseBody() {
    server.createContext("/", exchange -> {
      exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0);
      try (var out = exchange.getResponseBody()) {
        out.write('{'); out.flush();
        try { Thread.sleep(3000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        out.write('}');
      } catch (java.io.IOException ignored) { /* client deadline closes this response */ }
    });
    Assertions.assertTimeoutPreemptively(Duration.ofSeconds(3), () -> failure(() -> client().fetch("DEMO-1"), 504, "INQUIRY_TIMEOUT"));
  }
}
