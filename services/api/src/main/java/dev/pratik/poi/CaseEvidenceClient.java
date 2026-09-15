package dev.pratik.poi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Transport for the separate four-group FCR evidence API. Domain validation belongs to the caller. */
@Component
public class CaseEvidenceClient {
  static final int MAX_BYTES = 5 * 1024 * 1024;
  private static final byte[] METADATA_IPV6 = {
      (byte) 0xfd, 0, 0x0e, (byte) 0xc2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x02, 0x54};

  record Reply(int status, String contentType, byte[] body) {}
  record Result(ObjectNode response, ObjectNode upstream) {}

  interface Transport {
    Reply post(URI endpoint, byte[] body, Map<String, String> headers, Duration timeout)
        throws Exception;
  }

  private final ObjectMapper mapper;
  private final boolean enabled;
  private final URI endpoint;
  private final Duration timeout;
  private final Map<String, String> headers;
  private final Transport transport;
  private final String wireFormat;
  private final FlexcubeInquirySupport flexcube;
  private final Semaphore requests = new Semaphore(2);

  @Autowired
  public CaseEvidenceClient(
      ObjectMapper mapper,
      @Value("${poi.case-evidence.api-enabled:false}") boolean enabled,
      @Value("${poi.case-evidence.api-url:}") String url,
      @Value("${poi.case-evidence.timeout-seconds:15}") int seconds,
      @Value("${poi.case-evidence.api-token:}") String token,
      @Value("${poi.case-evidence.wire-format:CANONICAL}") String wireFormat,
      FlexcubeInquirySupport flexcube) {
    this(mapper, enabled, url, seconds, token, wireFormat, flexcube, httpTransport());
  }

  public CaseEvidenceClient(ObjectMapper mapper, boolean enabled, String url, int seconds, String token) {
    this(mapper, enabled, url, seconds, token, httpTransport());
  }

  CaseEvidenceClient(
      ObjectMapper mapper, boolean enabled, String url, int seconds, String token,
      Transport transport) {
    this(mapper, enabled, url, seconds, token, "CANONICAL", null, transport);
  }

  CaseEvidenceClient(ObjectMapper mapper, boolean enabled, String url, int seconds, String token,
      String wireFormat, FlexcubeInquirySupport flexcube, Transport transport) {
    if (seconds < 1 || seconds > 60)
      throw new IllegalArgumentException("Case evidence timeout must be from 1 through 60 seconds.");
    if (token == null || token.length() > 4096
        || token.chars().anyMatch(c -> c < 0x21 || c > 0x7e))
      throw new IllegalArgumentException("Case evidence token must be bounded printable text without whitespace.");
    this.mapper = mapper;
    if (!Set.of("CANONICAL", "FLEXCUBE").contains(wireFormat))
      throw new IllegalArgumentException("Case evidence wire format must be CANONICAL or FLEXCUBE.");
    if (wireFormat.equals("FLEXCUBE") && flexcube == null)
      throw new IllegalArgumentException("FLEXCUBE evidence requires configured inquiry support.");
    this.wireFormat = wireFormat; this.flexcube = flexcube;
    this.enabled = enabled && url != null && !url.isBlank();
    this.endpoint = this.enabled ? endpoint(url) : null;
    this.timeout = Duration.ofSeconds(seconds);
    this.headers = token.isEmpty()
        ? Map.of("Content-Type", "application/json", "Accept", "application/json")
        : Map.of("Content-Type", "application/json", "Accept", "application/json",
            "Authorization", "Bearer " + token);
    this.transport = transport;
  }

  public Map<String, Object> config() {
    return Map.of("enabled", enabled, "mode", enabled ? "BANK_API" : "DISABLED");
  }

  public ObjectNode fetch(String reference, String orgBank, String orgBranch) {
    return fetchResult(reference, orgBank, orgBranch).response();
  }

  Result fetchResult(String reference, String orgBank, String orgBranch) {
    if (!enabled)
      throw new ApiException(503, "CASE_EVIDENCE_DISABLED",
          "The case evidence API is not explicitly configured and enabled.");
    if (!validReference(reference) || orgBank == null || !orgBank.matches("[0-9]{1,10}")
        || orgBranch == null || !orgBranch.matches("[0-9]{1,10}"))
      throw new ApiException(422, "INVALID_CASE_EVIDENCE_REQUEST",
          "A saved payment reference and exact bank and branch codes are required.");
    if (!requests.tryAcquire())
      throw new ApiException(503, "CASE_EVIDENCE_BUSY", "Case evidence inquiry is busy; retry later.");
    try {
      ObjectNode request = mapper.createObjectNode().put("reference", reference)
          .put("orgBank", orgBank).put("orgBranch", orgBranch);
      if (wireFormat.equals("FLEXCUBE")) {
        if (reference.length() > 40) throw new ApiException(422, "INVALID_CASE_EVIDENCE_REQUEST", "PO02 references must not exceed 40 characters.");
        ObjectNode args = mapper.createObjectNode().put("referenceTransactionNumber", reference)
            .put("originatingBankCode", FlexcubeInquirySupport.code(orgBank)).put("originatingBranchCode", FlexcubeInquirySupport.code(orgBranch));
        request = flexcube.request("PO02", orgBank, orgBranch, args);
      }
      Reply response = transport.post(endpoint, mapper.writeValueAsBytes(request), headers, timeout);
      if (response == null || response.status() != 200)
        throw new ApiException(502, "CASE_EVIDENCE_UPSTREAM_ERROR",
            "The configured case evidence API did not return a successful response.");
      if (response.body() == null || response.contentType() == null) throw invalidResponse();
      if (response.body().length > MAX_BYTES) throw responseTooLarge();
      if (!response.contentType().split(";", 2)[0].trim().equalsIgnoreCase("application/json"))
        throw invalidResponse();
      ObjectNode raw = UatService.parseObject(mapper, response.body(), invalidResponse());
      if (wireFormat.equals("CANONICAL")) return new Result(raw, null);
      flexcube.validateResponse(raw, request);
      var adapted = new FlexcubeEvidenceAdapter(mapper).adapt(raw, request, flexcube.sourceTimezone(), Instant.now());
      return new Result(adapted.payload(), adapted.upstream());
    } catch (ApiException exception) {
      throw exception;
    } catch (TimeoutException | HttpTimeoutException exception) {
      throw new ApiException(504, "CASE_EVIDENCE_TIMEOUT",
          "The case evidence inquiry timed out; no fallback response was used.");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ApiException(503, "CASE_EVIDENCE_UNAVAILABLE", "The case evidence inquiry was interrupted.");
    } catch (javax.net.ssl.SSLHandshakeException | javax.net.ssl.SSLPeerUnverifiedException exception) {
      throw new ApiException(503, "CASE_EVIDENCE_TLS_ERROR",
          "The bank HTTPS connection could not be verified. Ask an administrator to check that the configured hostname matches the server certificate and that its certificate chain is trusted.");
    } catch (Exception exception) {
      throw new ApiException(503, "CASE_EVIDENCE_UNAVAILABLE",
          "The configured case evidence API could not complete; no fallback response was used.");
    } finally {
      requests.release();
    }
  }

  private static boolean validReference(String value) {
    return value != null && !value.isBlank() && value.length() <= 200
        && value.equals(value.strip()) && value.chars().noneMatch(Character::isISOControl);
  }


  private static URI endpoint(String value) {
    try {
      URI uri = URI.create(value);
      String host = uri.getHost();
      if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
          || uri.getQuery() != null || uri.getFragment() != null
          || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getPath().isBlank()
          || forbiddenHost(host)) throw new IllegalArgumentException();
      return uri;
    } catch (Exception exception) {
      // Never include the supplied endpoint or token in configuration failures.
      throw new IllegalArgumentException("Case evidence requires a fixed permitted HTTPS endpoint with normal TLS validation.");
    }
  }

  private static boolean forbiddenHost(String supplied) throws Exception {
    String host = supplied.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
    if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
    if (host.equals("localhost") || host.endsWith(".localhost")
        || Set.of("metadata", "metadata.google.internal", "instance-data.ec2.internal").contains(host))
      return true;
    InetAddress literal = null;
    if (host.matches("[0-9a-f:.]+") && host.contains(":")) {
      // Literal-only parsing; this branch cannot resolve a DNS hostname.
      literal = InetAddress.getByName(host);
    } else if (host.matches("[0-9]+(?:\\.[0-9]+){3}")) {
      String[] parts = host.split("\\.");
      byte[] bytes = new byte[4];
      for (int i = 0; i < parts.length; i++) {
        if ((parts[i].length() > 1 && parts[i].startsWith("0")) || parts[i].length() > 3
            || Integer.parseInt(parts[i]) > 255) return true;
        bytes[i] = (byte) Integer.parseInt(parts[i]);
      }
      literal = InetAddress.getByAddress(bytes);
    } else if (host.matches("[0-9.]+") || host.matches("0x[0-9a-f]+") || host.contains(":")) {
      return true;
    }
    return literal != null && (Arrays.equals(literal.getAddress(), METADATA_IPV6)
        || literal.isAnyLocalAddress() || literal.isLoopbackAddress()
        || literal.isLinkLocalAddress() || literal.isMulticastAddress());
  }

  private static ApiException invalidResponse() {
    return new ApiException(502, "INVALID_CASE_EVIDENCE_RESPONSE",
        "The case evidence API must return one bounded strict JSON object.");
  }

  private static ApiException responseTooLarge() {
    return new ApiException(502, "CASE_EVIDENCE_RESPONSE_TOO_LARGE", "Case evidence response exceeds 5 MiB.");
  }

  private static Transport httpTransport() {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    return (uri, body, headers, timeout) -> {
      HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
      headers.forEach(builder::header);
      HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
      var future = client.sendAsync(request, info -> new LimitedBody());
      try {
        var response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return new Reply(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""), response.body());
      } catch (TimeoutException | InterruptedException exception) {
        future.cancel(true);
        throw exception;
      } catch (ExecutionException exception) {
        if (exception.getCause() instanceof Exception cause) throw cause;
        throw exception;
      }
    };
  }

  static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> completed = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    public CompletionStage<byte[]> getBody() { return completed; }
    public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > MAX_BYTES - bytes.size()) {
          subscription.cancel();
          completed.completeExceptionally(responseTooLarge());
          return;
        }
        byte[] part = new byte[buffer.remaining()];
        buffer.get(part);
        bytes.writeBytes(part);
      }
      subscription.request(1);
    }
    public void onError(Throwable error) { completed.completeExceptionally(error); }
    public void onComplete() { completed.complete(bytes.toByteArray()); }
  }
}
