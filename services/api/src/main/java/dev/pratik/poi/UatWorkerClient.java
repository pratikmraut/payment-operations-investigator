package dev.pratik.poi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class UatWorkerClient {
  static final int MAX_RESPONSE_BYTES = 1048576;
  private final ObjectMapper mapper;
  private final URI endpoint;
  private final URI caseEndpoint;
  private final URI knowledgeEndpoint;
  private final URI preflightEndpoint;
  private final URI caseJobsEndpoint;
  private final String serviceKey;
  private final long seconds;
  private final HttpClient client;

  public UatWorkerClient(ObjectMapper mapper, @Value("${poi.worker-url}") String baseUrl,
      @Value("${poi.service-key}") String serviceKey,
      @Value("${poi.uat-worker-timeout-seconds:930}") long seconds) {
    this.mapper = mapper;
    this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/uat/answer");
    this.caseEndpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/case/answer");
    this.knowledgeEndpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/case/knowledge-search");
    this.preflightEndpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/case/preflight");
    this.caseJobsEndpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/case/jobs/");
    this.serviceKey = serviceKey;
    this.seconds = Math.max(1, Math.min(930, seconds));
    this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  public ObjectNode answer(ObjectNode input) {
    return answerAt(endpoint, input);
  }

  public ObjectNode answerCase(ObjectNode input) {
    return answerAt(caseEndpoint, input);
  }

  public ObjectNode searchCaseKnowledge(ObjectNode input) {
    return answerAt(knowledgeEndpoint, input);
  }

  public ObjectNode preflightCase(ObjectNode input) {
    return answerAt(preflightEndpoint, input);
  }

  public ObjectNode submitCaseJob(ObjectNode envelope) {
    return answerAt(caseJobsEndpoint.resolve("submit"), envelope);
  }

  public ObjectNode caseJobStatus(ObjectNode identity) {
    return answerAt(caseJobsEndpoint.resolve("status"), identity);
  }

  public ObjectNode cancelCaseJob(ObjectNode identity) {
    return answerAt(caseJobsEndpoint.resolve("cancel"), identity);
  }

  public ObjectNode forgetCaseJobs(ObjectNode scope) {
    return answerAt(caseJobsEndpoint.resolve("forget-case"), scope);
  }

  private ObjectNode answerAt(URI target, ObjectNode input) {
    boolean jobRequest = target.toString().startsWith(caseJobsEndpoint.toString());
    long requestSeconds = target.equals(preflightEndpoint) || jobRequest ? 10 : target.equals(knowledgeEndpoint) ? 150 : seconds;
    HttpRequest request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(requestSeconds))
        .header("Content-Type", "application/json").header("Accept", "application/json")
        .header("X-Service-Key", serviceKey)
        .POST(HttpRequest.BodyPublishers.ofString(input.toString())).build();
    CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, info -> new LimitedBody());
    try {
      HttpResponse<byte[]> response = pending.get(requestSeconds, TimeUnit.SECONDS);
      if (jobRequest && response.statusCode() == 404 && workerCode(response).equals("CASE_WORKER_JOB_NOT_FOUND"))
        throw new ApiException(404, "CASE_WORKER_JOB_NOT_FOUND", "No model receipt exists for this investigation.");
      if (jobRequest && response.statusCode() == 409)
        throw new ApiException(409, "CASE_WORKER_JOB_CONFLICT", "The saved model request identity conflicts with its original receipt.");
      if (jobRequest && response.statusCode() == 429)
        throw new ApiException(429, "CASE_WORKER_QUEUE_FULL", "The local model queue is full. The saved question is waiting for capacity.");
      if (response.statusCode() == 422) throw UatService.invalidWorker();
      if (response.statusCode() == 504) throw timedOut();
      if (response.statusCode() == 503 && workerCode(response).equals("UAT_MODEL_BUSY"))
        throw new ApiException(503, "UAT_MODEL_BUSY",
            "The local model is answering another request. Wait for it to finish, then try again. No fallback answer was used.");
      if (response.statusCode() != 200) throw unavailable();
      String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
      if (!type.equalsIgnoreCase("application/json")) throw UatService.invalidWorker();
      return UatService.parseObject(mapper, response.body(), UatService.invalidWorker());
    } catch (TimeoutException ex) {
      pending.cancel(true);
      throw timedOut();
    } catch (InterruptedException ex) {
      pending.cancel(true); Thread.currentThread().interrupt(); throw unavailable();
    } catch (ExecutionException ex) {
      Throwable cause = ex.getCause();
      while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
      if (cause instanceof ApiException failure) throw failure;
      if (cause instanceof HttpTimeoutException)
        throw timedOut();
      throw unavailable();
    }
  }

  private String workerCode(HttpResponse<byte[]> response) {
    String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
    if (!type.equalsIgnoreCase("application/json")) return "";
    try {
      // Only a known machine code is used; never expose source-bearing provider messages.
      ObjectNode body = UatService.parseObject(mapper, response.body(), UatService.invalidWorker());
      return body.has("code") ? body.path("code").asText("") : body.path("detail").path("code").asText("");
    } catch (ApiException failure) {
      return "";
    }
  }

  private static ApiException timedOut() {
    return new ApiException(504, "UAT_MODEL_TIMEOUT",
        "The local model exceeded the configured response wait. CPU inference may still be finishing; wait before retrying. No fallback answer was used.");
  }

  private static ApiException unavailable() {
    return new ApiException(503, "UAT_MODEL_UNAVAILABLE",
        "The local model worker is unavailable. No fallback answer was used.");
  }

  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;
    @Override public CompletionStage<byte[]> getBody() { return result; }
    @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
    @Override public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
          subscription.cancel(); result.completeExceptionally(UatService.invalidWorker()); return;
        }
        byte[] value = new byte[buffer.remaining()]; buffer.get(value); bytes.writeBytes(value);
      }
      subscription.request(1);
    }
    @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
    @Override public void onComplete() { result.complete(bytes.toByteArray()); }
  }
}
