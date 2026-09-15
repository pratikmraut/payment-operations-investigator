package dev.pratik.poi;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Fixed local mock transport. A real bank connector requires a separate deployment contract. */
@Component
public class ObpmInquiryClient {
  private final ObjectMapper mapper;
  private final boolean enabled;
  private final String baseUrl, serviceKey;
  private final long timeoutSeconds;
  private final HttpClient client;
  private final Semaphore inFlight = new Semaphore(4);

  public ObpmInquiryClient(ObjectMapper mapper,
      @Value("${poi.obpm-inquiry-enabled:false}") boolean enabled,
      @Value("${poi.obpm-inquiry-url:http://127.0.0.1:8092}") String baseUrl,
      @Value("${poi.obpm-inquiry-service-key:poi-local-inquiry-key}") String serviceKey,
      @Value("${poi.obpm-inquiry-timeout-seconds:5}") long timeoutSeconds) {
    this.mapper = mapper;
    this.enabled = enabled;
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.serviceKey = serviceKey;
    this.timeoutSeconds = Math.max(1, Math.min(15, timeoutSeconds));
    if (enabled) {
      URI uri;
      try { uri = URI.create(this.baseUrl); }
      catch (IllegalArgumentException ex) { throw new IllegalStateException("Invalid local mock inquiry configuration."); }
      if (!"http".equals(uri.getScheme()) || uri.getHost() == null
          || !Set.of("127.0.0.1", "localhost", "::1", "[::1]", "mock-inquiry").contains(uri.getHost())
          || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
          || !uri.getPath().isEmpty() || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535
          || serviceKey.isBlank() || serviceKey.chars().anyMatch(Character::isISOControl))
        throw new IllegalStateException("Inquiry client is restricted to the configured local synthetic mock.");
    }
    client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(Math.min(3, this.timeoutSeconds)))
        .followRedirects(HttpClient.Redirect.NEVER).build();
  }

  public Map<String, Object> configuration() {
    return Map.of("enabled", enabled, "mode", "SYNTHETIC_MOCK", "referenceType", "PAYMENT_REFERENCE",
        "examples", enabled ? List.of(
            Map.of("reference", "MOCK-NEFT-1001", "label", "Recorded ECA timeout"),
            Map.of("reference", "MOCK-NEFT-1002", "label", "New pending request after a timeout"),
            Map.of("reference", "MOCK-NEFT-1003", "label", "Incomplete evidence and unknown status"),
            Map.of("reference", "MOCK-NEFT-1004", "label", "Conflicting current queue records")) : List.of());
  }

  public ObjectNode fetch(String reference) {
    validateReference(reference);
    if (!enabled) throw new ApiException(503, "INQUIRY_DISABLED", "The local mock inquiry API is not enabled.");
    if (!inFlight.tryAcquire()) throw new ApiException(503, "INQUIRY_BUSY", "The mock inquiry client is busy. Try again shortly.");
    CompletableFuture<HttpResponse<byte[]>> pending = null;
    try {
      URI uri = URI.create(baseUrl + "/inquiry/v1/neft/payments/" + reference
          + "?referenceType=PAYMENT_REFERENCE&deploymentId=SYNTHETIC-OBPM&hostCode=DEMO-HOST&branchCode=DEMO-BRANCH");
      HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds))
          .header("Accept", "application/json").header("X-Service-Key", serviceKey).GET().build();
      pending = client.sendAsync(request, info -> new LimitedBodySubscriber(ObpmController.MAX_BYTES));
      // Bound the entire exchange, including a slow body after response headers.
      HttpResponse<byte[]> response = pending.get(timeoutSeconds, TimeUnit.SECONDS);
      if (response.statusCode() == 404)
        throw new ApiException(404, "INQUIRY_NOT_FOUND", "Payment reference was not found in the original mock data.");
      if (response.statusCode() != 200)
        throw new ApiException(502, "INQUIRY_UPSTREAM_ERROR", "The mock inquiry API did not return evidence. No case was imported.");
      String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
      if (!type.equalsIgnoreCase("application/json")) throw invalidResponse();
      try (JsonParser parser = mapper.getFactory().createParser(response.body())) {
        parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        JsonNode input = mapper.readTree(parser);
        if (parser.nextToken() != null) throw invalidResponse();
        return normalize(input, reference);
      } catch (java.io.IOException ex) { throw invalidResponse(); }
    } catch (TimeoutException ex) {
      if (pending != null) pending.cancel(true);
      throw timedOut();
    } catch (InterruptedException ex) {
      if (pending != null) pending.cancel(true);
      Thread.currentThread().interrupt();
      throw new ApiException(503, "INQUIRY_UNAVAILABLE", "Mock inquiry was interrupted. No case was imported.");
    } catch (ExecutionException ex) {
      Throwable cause = ex.getCause();
      while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
      if (cause instanceof ApiException failure) throw failure;
      if (cause instanceof HttpTimeoutException) throw timedOut();
      throw new ApiException(503, "INQUIRY_UNAVAILABLE", "The mock inquiry API could not be reached. No sample fallback was used.");
    } finally { inFlight.release(); }
  }

  static void validateReference(String reference) {
    if (reference == null || !reference.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}"))
      throw new ApiException(400, "INVALID_REQUEST", "Provide a payment reference of 1–100 letters, digits, dots, underscores, colons or hyphens.");
  }

  static ObjectNode normalize(JsonNode input, String reference) {
    if (!(input instanceof ObjectNode original)
        || !input.path("schemaVersion").asText().equals("neft-inquiry-v1")
        || !input.path("mappingVersion").asText().equals("original-synthetic-inquiry-v1")
        || !(input.path("payment") instanceof ObjectNode payment)
        || payment.has("amountMinor")
        || !payment.path("sourcePaymentId").asText().equals(reference)
        || !payment.path("sourceAmountDecimal").isTextual()) throw invalidResponse();
    String decimal = payment.path("sourceAmountDecimal").textValue();
    if (!decimal.matches("(0|[1-9][0-9]{0,13})(\\.[0-9]{1,2})?")) throw invalidResponse();
    ObjectNode evidence = original.deepCopy();
    evidence.put("schemaVersion", "obpm-evidence-v1");
    try {
      ((ObjectNode) evidence.path("payment")).put("amountMinor", new BigDecimal(decimal).movePointRight(2).longValueExact());
      return ObpmEvidenceValidator.validate(evidence);
    } catch (ArithmeticException | ApiException ex) { throw invalidResponse(); }
  }

  private static ApiException invalidResponse() {
    return new ApiException(502, "INVALID_INQUIRY_RESPONSE", "Mock inquiry evidence failed contract validation. No case was imported.");
  }
  private static ApiException timedOut() {
    return new ApiException(504, "INQUIRY_TIMEOUT", "The mock inquiry API timed out. No sample fallback was used.");
  }

  private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int maximum;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;
    LimitedBodySubscriber(int maximum) { this.maximum = maximum; }
    @Override public CompletionStage<byte[]> getBody() { return result; }
    @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
    @Override public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > maximum - bytes.size()) {
          subscription.cancel(); result.completeExceptionally(invalidResponse()); return;
        }
        byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }
    @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
    @Override public void onComplete() { result.complete(bytes.toByteArray()); }
  }
}
