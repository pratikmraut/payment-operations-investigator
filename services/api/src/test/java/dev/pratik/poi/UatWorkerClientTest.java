package dev.pratik.poi;

import static dev.pratik.poi.UatTestData.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class UatWorkerClientTest {
  HttpServer server;
  ExecutorService executor;
  UatWorkerClient client;
  @BeforeEach void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newCachedThreadPool(); server.setExecutor(executor); server.start();
    client = new UatWorkerClient(MAPPER, "http://127.0.0.1:" + server.getAddress().getPort(), "original-test-key", 1);
  }
  @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
  void respond(int status, String type, String body) {
    server.createContext("/uat/answer", exchange -> {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getRequestBody().readAllBytes();
      exchange.getResponseHeaders().set("Content-Type", type);
      exchange.sendResponseHeaders(status, bytes.length);
      try (var output = exchange.getResponseBody()) { output.write(bytes); }
    });
  }
  void error(int status, String code) {
    assertThatThrownBy(() -> client.answer(MAPPER.createObjectNode().put("question", "Original test")))
        .isInstanceOfSatisfying(ApiException.class, e -> { assertThat(e.status).isEqualTo(status); assertThat(e.code).isEqualTo(code); });
  }
  @Test void sendsOnlyAuthenticatedBoundedRequestToDedicatedEndpoint() throws Exception {
    var input = MAPPER.createObjectNode().put("question", "Original question").put("snapshotId", ID).put("evidenceHash", "a".repeat(64));
    input.set("documents", bundle().path("documents"));
    var response = response(input); var received = new CompletableFuture<String>();
    server.createContext("/uat/answer", exchange -> {
      received.complete(exchange.getRequestMethod() + "|" + exchange.getRequestHeaders().getFirst("X-Service-Key") + "|"
          + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] bytes = MAPPER.writeValueAsBytes(response); exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      exchange.sendResponseHeaders(200, bytes.length); try (var output = exchange.getResponseBody()) { output.write(bytes); }
    });
    assertThat(client.answer(input)).isEqualTo(response);
    assertThat(received.get(2, TimeUnit.SECONDS)).isEqualTo("POST|original-test-key|" + input);
  }
  @Test void distinguishesInvalidModelOutputFromUnavailableProviderWithoutEchoingBody() {
    respond(422, "application/json", "{\"private\":\"Do not echo this source\"}");
    error(502, "INVALID_UAT_ANSWER");
    server.removeContext("/uat/answer"); respond(503, "application/json", "{\"private\":\"Do not echo\"}");
    error(503, "UAT_MODEL_UNAVAILABLE");
  }
  @Test void caseRequestUsesSeparateAuthenticatedEndpointWithoutCallingExportBaseline() throws Exception {
    var input=MAPPER.createObjectNode().put("question","An original case question");
    var received=new CompletableFuture<String>();
    var baselineCalls=new AtomicInteger();
    server.createContext("/uat/answer",exchange->{baselineCalls.incrementAndGet();exchange.sendResponseHeaders(500,-1);exchange.close();});
    server.createContext("/case/answer",exchange->{
      received.complete(exchange.getRequestMethod()+"|"+exchange.getRequestHeaders().getFirst("X-Service-Key")+"|"
          +new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
      byte[] body="{\"rag\":{\"pipeline\":\"case-evidence-rag-v1\"}}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
      try(var output=exchange.getResponseBody()){output.write(body);}
    });
    assertThat(client.answerCase(input).path("rag").path("pipeline").asText()).isEqualTo("case-evidence-rag-v1");
    assertThat(received.get(2,TimeUnit.SECONDS)).isEqualTo("POST|original-test-key|"+input);
    assertThat(baselineCalls).hasValue(0);
    server.removeContext("/case/answer");
    assertThatThrownBy(()->client.answerCase(input)).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.code).isEqualTo("UAT_MODEL_UNAVAILABLE"));
    assertThat(baselineCalls).hasValue(0);
  }
  @Test void preservesExplicitTimeoutAndBusyFailuresWithoutEchoingProviderDetails() {
    for (Object[] failure : new Object[][] {
        {504, "UAT_MODEL_TIMEOUT", "UAT_MODEL_TIMEOUT"},
        {503, "UAT_MODEL_BUSY", "UAT_MODEL_BUSY"},
        {503, "PROVIDER_UNAVAILABLE", "UAT_MODEL_UNAVAILABLE"}}) {
      respond((int) failure[0], "application/json", "{\"code\":\"" + failure[1]
          + "\",\"message\":\"private source and internal provider details\"}");
      assertThatThrownBy(() -> client.answer(MAPPER.createObjectNode()))
          .isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.status).isEqualTo(failure[0]);
            assertThat(ex.code).isEqualTo(failure[2]);
            assertThat(ex.getMessage()).doesNotContain("private source", "internal provider details");
          });
      server.removeContext("/uat/answer");
    }
  }
  @Test void knowledgeSearchUsesFixedAuthenticatedRouteAndNeverFallsBackToAnswerRoutes() throws Exception {
    var input=MAPPER.createObjectNode().put("question","Explain a synthetic status");
    var calls=new AtomicInteger();var received=new CompletableFuture<String>();
    server.createContext("/uat/answer",exchange->{calls.incrementAndGet();exchange.sendResponseHeaders(500,-1);exchange.close();});
    server.createContext("/case/answer",exchange->{calls.incrementAndGet();exchange.sendResponseHeaders(500,-1);exchange.close();});
    server.createContext("/case/knowledge-search",exchange->{
      received.complete(exchange.getRequestMethod()+"|"+exchange.getRequestHeaders().getFirst("X-Service-Key")+"|"
          +new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
      byte[] body="{\"processor\":\"GPU\"}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
      try(var output=exchange.getResponseBody()){output.write(body);}
    });
    assertThat(client.searchCaseKnowledge(input).path("processor").asText()).isEqualTo("GPU");
    assertThat(received.get(2,TimeUnit.SECONDS)).isEqualTo("POST|original-test-key|"+input);
    assertThat(calls).hasValue(0);
    server.removeContext("/case/knowledge-search");
    assertThatThrownBy(()->client.searchCaseKnowledge(input)).isInstanceOf(ApiException.class);
    assertThat(calls).hasValue(0);
  }
  @Test void unrecognizedMalformedOrNonJsonErrorBodiesCannotSelectBusyHandling() {
    for (String body : new String[]{"{\"message\":\"UAT_MODEL_BUSY\"}",
        "{\"code\":\"UAT_MODEL_BUSY\",\"code\":\"PROVIDER_UNAVAILABLE\"}",
        "{\"code\":\"UAT_MODEL_BUSY\"} {}", "not JSON"}) {
      respond(503, "application/json", body); error(503, "UAT_MODEL_UNAVAILABLE"); server.removeContext("/uat/answer");
    }
    respond(503, "text/html", "{\"code\":\"UAT_MODEL_BUSY\"}"); error(503, "UAT_MODEL_UNAVAILABLE");
  }
  @Test
  @SuppressWarnings("unchecked")
  void dedicatedUatRequestTimeoutSupportsCpuWaitAndClampsBounds() throws Exception {
    for (long[] bounds : new long[][]{{930, 930}, {600, 600}, {10000, 930}, {0, 1}}) {
      HttpClient transport = mock(HttpClient.class);
      HttpResponse<byte[]> response = mock(HttpResponse.class);
      when(response.statusCode()).thenReturn(200);
      when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (a, b) -> true));
      when(response.body()).thenReturn("{}".getBytes(StandardCharsets.UTF_8));
      when(transport.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
          .thenReturn(CompletableFuture.completedFuture(response));
      UatWorkerClient configured = new UatWorkerClient(MAPPER, "http://worker.invalid", "original-test-key", bounds[0]);
      ReflectionTestUtils.setField(configured, "client", transport);
      configured.answer(MAPPER.createObjectNode());
      ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
      verify(transport).sendAsync(request.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
      assertThat(request.getValue().timeout()).as("configured UAT timeout %s", bounds[0]).contains(Duration.ofSeconds(bounds[1]));
    }
  }
  @Test void rejectsDuplicateTrailingNonJsonAndOversizedResponses() {
    for (String body : new String[]{"{\"answer\":1,\"answer\":2}", "{} {}", "[]", "{\"answer\":\"" + "x".repeat(1048576) + "\"}"}) {
      respond(200, "application/json", body); error(502, "INVALID_UAT_ANSWER"); server.removeContext("/uat/answer");
    }
    respond(200, "text/html", "<html>untrusted</html>"); error(502, "INVALID_UAT_ANSWER");
  }
  @Test void neverFollowsRedirectAndNeverSendsServiceKeyToOtherEndpoint() {
    var redirected = new AtomicInteger();
    server.createContext("/other", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
    server.createContext("/uat/answer", exchange -> { exchange.getResponseHeaders().set("Location", "/other"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
    error(503, "UAT_MODEL_UNAVAILABLE"); assertThat(redirected).hasValue(0);
  }
  @Test void timeoutBoundsSlowBodyAfterHeaders() {
    server.createContext("/uat/answer", exchange -> {
      exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 100);
      try (var output = exchange.getResponseBody()) {
        output.write('{'); output.flush();
        try { Thread.sleep(2500); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
      } catch (java.io.IOException ignored) { }
    });
    long start = System.nanoTime(); error(504, "UAT_MODEL_TIMEOUT");
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(2200);
  }
}
