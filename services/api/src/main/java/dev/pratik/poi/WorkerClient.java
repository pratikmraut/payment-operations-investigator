package dev.pratik.poi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WorkerClient {
  private final HttpClient client =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final ObjectMapper mapper;
  private final String baseUrl, serviceKey;
  private final Duration timeout;

  public WorkerClient(
      ObjectMapper mapper,
      @Value("${poi.worker-url}") String baseUrl,
      @Value("${poi.service-key}") String serviceKey,
      @Value("${poi.worker-timeout-seconds}") long seconds) {
    this.mapper = mapper;
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.serviceKey = serviceKey;
    this.timeout = Duration.ofSeconds(Math.max(1, Math.min(390, seconds)));
  }

  public ObjectNode investigate(ObjectNode input) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/investigate"))
            .timeout(timeout)
            .header("X-Service-Key", serviceKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(input.toString()))
            .build();
    return exchange(request);
  }

  public ObjectNode knowledge(String tenant) {
    return exchange(
        HttpRequest.newBuilder(
                URI.create(
                    baseUrl
                        + "/knowledge?tenantId="
                        + URLEncoder.encode(tenant, StandardCharsets.UTF_8)))
            .timeout(Duration.ofSeconds(5))
            .header("X-Service-Key", serviceKey)
            .GET()
            .build());
  }

  public String health() {
    try {
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                  .timeout(Duration.ofSeconds(2))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      return response.statusCode() == 200
              && mapper.readTree(response.body()).path("status").asText().equals("UP")
          ? "UP"
          : "DOWN";
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      return "DOWN";
    }
  }

  private ObjectNode exchange(HttpRequest request) {
    try {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new ApiException(
            503,
            "WORKER_UNAVAILABLE",
            "Investigator worker could not complete the request (HTTP "
                + response.statusCode()
                + "). No fallback was used.");
      if (response.body().length() > 2_000_000)
        throw new ApiException(
            503, "INVALID_WORKER_RESPONSE", "Worker returned an oversized response.");
      var node = mapper.readTree(response.body());
      if (!(node instanceof ObjectNode object))
        throw new ApiException(
            503, "INVALID_WORKER_RESPONSE", "Worker returned an invalid response.");
      return object;
    } catch (ApiException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (Exception e) {
      throw unavailable();
    }
  }

  private ApiException unavailable() {
    return new ApiException(
        503,
        "WORKER_UNAVAILABLE",
        "Investigator worker is unavailable or timed out. No investigation result or fallback was fabricated.");
  }
}
