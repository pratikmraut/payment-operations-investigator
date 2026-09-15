package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {
  private final CaseStore store;
  private final InvestigationService investigations;
  private final WorkerClient worker;
  private final String datasetVersion;
  private final ObpmImportService obpm;

  public ApiController(
      CaseStore store,
      InvestigationService investigations,
      WorkerClient worker,
      ObpmImportService obpm,
      @Value("${poi.dataset-version}") String datasetVersion) {
    this.store = store;
    this.investigations = investigations;
    this.worker = worker;
    this.obpm = obpm;
    this.datasetVersion = datasetVersion;
  }

  @GetMapping("/health")
  public Map<String, Object> health() {
    return Map.of("status", "UP", "service", "payment-operations-api", "mode", "synthetic");
  }

  @GetMapping("/cases")
  public Map<String, Object> cases(
      Authentication auth,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String priority) {
    var items = store.cases(Actor.from(auth).tenantId(), search, status, priority);
    return Map.of("items", items, "total", items.size());
  }

  @GetMapping("/cases/{id}")
  public ObjectNode caseDetail(@PathVariable String id, Authentication auth) {
    return store.caseDetail(id, Actor.from(auth).tenantId());
  }

  record InvestigateRequest(
      @NotBlank @Size(max = 2000) String question,
      @NotBlank @Pattern(regexp = "replay|ollama") String mode) {}

  @PostMapping("/cases/{id}/investigations")
  public ObjectNode investigate(
      @PathVariable String id, @Valid @RequestBody InvestigateRequest input, Authentication auth) {
    return investigations.investigate(id, Actor.from(auth), input.question(), input.mode());
  }

  @GetMapping("/cases/{id}/investigations")
  public Map<String, Object> investigations(@PathVariable String id, Authentication auth) {
    return Map.of("items", store.investigations(id, Actor.from(auth).tenantId()));
  }

  @GetMapping("/investigations/{id}")
  public ObjectNode investigation(@PathVariable String id, Authentication auth) {
    return store.investigation(id, Actor.from(auth).tenantId());
  }

  record DecisionRequest(
      @NotBlank @Size(max = 100) String investigationId,
      @Pattern(regexp = "APPROVE|REJECT") @NotBlank String decision,
      @NotBlank @Size(max = 1000) String note,
      @Min(1) long expectedVersion) {}

  @PostMapping("/cases/{id}/decisions")
  public ObjectNode decision(
      @PathVariable String id,
      @Valid @RequestBody DecisionRequest input,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      Authentication auth) {
    return investigations.decide(
        id,
        Actor.from(auth),
        input.investigationId(),
        input.decision(),
        input.note(),
        input.expectedVersion(),
        key);
  }

  @GetMapping("/cases/{id}/audit")
  public Map<String, Object> audit(@PathVariable String id, Authentication auth) {
    return Map.of("items", store.auditEvents(id, Actor.from(auth).tenantId()));
  }

  @GetMapping("/cases/{id}/export")
  public Map<String, Object> export(@PathVariable String id, Authentication auth) {
    String tenant = Actor.from(auth).tenantId();
    return Map.of(
        "case",
        store.caseDetail(id, tenant),
        "investigations",
        store.investigations(id, tenant),
        "audit",
        store.auditEvents(id, tenant),
        "decisions",
        store.decisions(id, tenant),
        "evidenceVersions",
        obpm.versions(id, tenant, true),
        "mode",
        "synthetic",
        "datasetVersion",
        datasetVersion);
  }

  @GetMapping("/dashboard")
  public Map<String, Object> dashboard(Authentication auth) {
    String tenant = Actor.from(auth).tenantId();
    var cases = store.cases(tenant, null, null, null);
    long total = 0;
    try {
      for (ObjectNode c : cases)
        total =
            JsonSupport.safeInteger(
                Math.addExact(total, JsonSupport.minor(c, "amountMinor")), "totalAmountMinor");
    } catch (ArithmeticException e) {
      throw new ApiException(
          422, "AMOUNT_OVERFLOW", "Aggregate exceeds the supported minor-unit range.");
    }
    return Map.of(
        "openCases",
        cases.stream().filter(c -> !c.path("status").asText().equals("RESOLVED")).count(),
        "highPriorityCases",
        cases.stream()
            .filter(
                c ->
                    c.path("priority").asText().equals("HIGH")
                        && !c.path("status").asText().equals("RESOLVED"))
            .count(),
        "awaitingReview",
        cases.stream().filter(c -> c.path("status").asText().equals("AWAITING_REVIEW")).count(),
        "resolvedCases",
        cases.stream().filter(c -> c.path("status").asText().equals("RESOLVED")).count(),
        "totalAmountMinor",
        total,
        "currency",
        "INR",
        "recentActivity",
        store.recentActivity(tenant),
        "mode",
        "synthetic");
  }

  @GetMapping("/knowledge")
  public Map<String, Object> knowledge(Authentication auth) {
    String tenant = Actor.from(auth).tenantId();
    JsonNode all = worker.knowledge(tenant).path("items");
    if (!all.isArray())
      throw new ApiException(503, "INVALID_WORKER_RESPONSE", "Knowledge response is invalid.");
    List<JsonNode> visible = new ArrayList<>();
    for (JsonNode book : all)
      if (book.path("tenantId").asText().equals("*")
          || book.path("tenantId").asText().equals(tenant)) visible.add(book);
    return Map.of("items", visible);
  }

  @GetMapping("/system")
  public Map<String, Object> system(Authentication auth) {
    String tenant = Actor.from(auth).tenantId(), workerStatus = worker.health();
    int books = 0;
    boolean knowledgeAvailable = false;
    if (workerStatus.equals("UP"))
      try {
        books = ((List<?>) knowledge(auth).get("items")).size();
        knowledgeAvailable = true;
      } catch (ApiException ignored) {
      }
    return Map.of(
        "datasetVersion",
        datasetVersion,
        "caseCount",
        store.cases(tenant, null, null, null).size(),
        "runbookCount",
        books,
        "knowledgeAvailable",
        knowledgeAvailable,
        "workerStatus",
        workerStatus,
        "supportedModes",
        List.of("replay", "ollama"),
        "limitations",
        List.of(
            "Synthetic original records only; no bank or payment-network connection.",
            "Replay is deterministic graph execution without LLM inference.",
            "Ollama requires a reachable server and installed model; failure does not fall back.",
            "Local demo identities; production profile intentionally refuses startup.",
            "The runbook count is unavailable when the operating guidance cannot be loaded."));
  }
}
