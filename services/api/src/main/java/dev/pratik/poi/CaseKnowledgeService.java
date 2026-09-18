package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Current source inventory, checked embeddings and scoped selection for new case questions. */
@Component
public final class CaseKnowledgeService {
  private static final String SCHEMA = "fcr-case-evidence-v1";
  private static final String MODEL = "qwen3-embedding:0.6b";
  private static final int MAX_BYTES = CaseKnowledgeLimits.INDEX_BYTES;
  static final Set<String> PINNED = Set.of("GUIDE-SOURCE-COVERAGE", "GUIDE-SOURCE-TIME", "GUIDE-RAW-STATUS",
      "NEFT-FIELD-DEFINITIONS", "EXPORT-INTERPRETATION", "N10-AND-OUTCOME-LIMITS");
  private static final Pattern TABLE_QUESTION = Pattern.compile("(?i)(?<![A-Z0-9_])(?:table|tables|schema|column|columns|PM_NEFT_TXN_LOG|PM_TXN_LOG|PM_TXN_LOG_HIST|NEFTTXNCODSTATUS)(?![A-Z0-9_])");
  private final ObjectMapper mapper;
  private final CaseGuidanceSource guidance;
  private final CaseStatusKnowledge statuses;
  private final UatWorkerClient worker;
  private final String indexFile;

  public CaseKnowledgeService(ObjectMapper mapper, CaseGuidanceSource guidance, CaseStatusKnowledge statuses,
      UatWorkerClient worker, @Value("${poi.case-investigation.knowledge-index-file:}") String indexFile) {
    this.mapper = mapper; this.guidance = guidance; this.statuses = statuses; this.worker = worker;
    this.indexFile = indexFile == null ? "" : indexFile;
  }
  boolean enabled() { return !indexFile.isBlank(); }

  /** Local-only preparation: never embeds, searches, or writes an index. */
  record Preview(ArrayNode documents, ArrayNode reservation, String version, String embeddingStatus, int totalDocuments) { }

  Preview preview(Actor actor, ObjectNode payload, String question, Set<String> warnings) {
    if (!SCHEMA.equals(payload.path("schemaVersion").asText())) throw unavailable();
    Inventory inventory = inventory(actor, warnings);
    LinkedHashMap<String, ObjectNode> selected = new LinkedHashMap<>();
    inventory.byId.forEach((id, document) -> { if (always(id, inventory)) selected.put(id, document); });
    for (JsonNode source : statuses.selectExact(actor, payload, question, warnings)) {
      String id = source.path("id").asText();
      if (!source.equals(inventory.byId.get(id))) throw unavailable();
      selected.put(id, (ObjectNode)source);
    }
    if (question != null && tableQuestion(question) && inventory.byId.containsKey("FCR-TABLE-REFERENCE-20260915"))
      selected.put("FCR-TABLE-REFERENCE-20260915", inventory.byId.get("FCR-TABLE-REFERENCE-20260915"));
    ArrayNode documents = mapper.createArrayNode(); selected.values().forEach(document -> documents.add(document.deepCopy()));
    ArrayNode reservation = documents.deepCopy();
    IndexState state = state(actor, inventory);
    if (question != null) {
      inventory.byId.entrySet().stream().filter(entry -> !selected.containsKey(entry.getKey()))
          .sorted(Comparator.comparingInt((Map.Entry<String,ObjectNode> entry) -> serializedLength(entry.getValue())).reversed())
          .limit(3).forEach(entry -> reservation.add(entry.getValue().deepCopy()));
      ArrayNode matches = mapper.createArrayNode();
      for (int i = 0; i < 3; i++) matches.addObject().put("id", "x".repeat(200)).put("score", -.9999999999999999);
      reservation.add(receipt(inventory.hash, state.index == null ? "0".repeat(64) : state.index.digest,
          mapper.valueToTree(selected.keySet()), matches));
    }
    return new Preview(documents, reservation, inventory.hash, state.status, inventory.documents.size());
  }

  private static int serializedLength(JsonNode source) {
    return source.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
  }

  public ObjectNode library(Actor actor) {
    LinkedHashSet<String> warnings = new LinkedHashSet<>();
    Inventory inventory = inventory(actor, warnings);
    IndexState state = state(actor, inventory);
    if (enabled() && !state.status.equals("CURRENT")) warnings.add("Knowledge embeddings need rebuilding from the current source documents before a new investigation can run.");
    ObjectNode result = mapper.createObjectNode().put("schemaVersion", "case-knowledge-library-v1")
        .put("tenantId", actor.tenantId()).put("evidenceSchema", SCHEMA).put("version", inventory.hash);
    ObjectNode embedding = result.putObject("embedding").put("enabled", enabled()).put("status", state.status)
        .put("indexedDocuments", state.matches).put("totalDocuments", inventory.documents.size());
    if (state.index == null) {
      embedding.putNull("model").putNull("digest").putNull("dimensions").putNull("indexedAt");
    } else {
      embedding.put("model", MODEL).put("digest", state.index.digest).put("dimensions", 1024).put("indexedAt", state.index.indexedAt);
    }
    ArrayNode items = result.putArray("items");
    for (JsonNode source : inventory.documents) {
      String id = source.path("id").asText();
      ObjectNode item = ((ObjectNode) source).deepCopy().put("version", UatService.canonicalHash(source))
          .put("category", id.equals(inventory.scopeId) ? "SCOPE" : inventory.statusIds.contains(id) ? "STATUS" : "GUIDANCE")
          .put("selection", always(id, inventory) ? "ALWAYS" : inventory.statusIds.contains(id) ? "EXACT_OR_SEMANTIC" : "SEMANTIC");
      Embedded entry = state.entries.get(id);
      item.put("embeddingStatus", entry != null && entry.hash.equals(UatService.canonicalHash(source)) ? "CURRENT" : state.index != null && entry == null ? "MISSING" : state.status.equals("CURRENT") ? "STALE" : state.status);
      items.add(item);
    }
    result.set("warnings", mapper.valueToTree(warnings));
    return result;
  }

  ArrayNode select(Actor actor, ObjectNode payload, String question, Set<String> warnings) {
    return select(actor, payload, question, warnings, null);
  }

  ArrayNode select(Actor actor, ObjectNode payload, String question, Set<String> warnings, ArrayNode evidence) {
    if (!SCHEMA.equals(payload.path("schemaVersion").asText())) throw unavailable();
    Inventory inventory = inventory(actor, warnings);
    LinkedHashMap<String, ObjectNode> selected = new LinkedHashMap<>();
    inventory.byId.forEach((id, document) -> { if (always(id, inventory)) selected.put(id, document); });
    for (JsonNode source : statuses.selectExact(actor, payload, question, warnings)) {
      String id = source.path("id").asText();
      if (!source.equals(inventory.byId.get(id))) throw unavailable();
      selected.put(id, ((ObjectNode) source));
    }
    if (question != null) {
      if (question.isBlank() || question.length() > 2000) throw unavailable();
      IndexState state = state(actor, inventory);
      if (!state.status.equals("CURRENT")) throw new ApiException(503, "CASE_KNOWLEDGE_INDEX_NOT_CURRENT",
          "Knowledge changed or its embeddings are missing. Rebuild the current knowledge index before investigating; no stale or substitute guidance was used.");
      if (tableQuestion(question) && inventory.byId.containsKey("FCR-TABLE-REFERENCE-20260915"))
        selected.put("FCR-TABLE-REFERENCE-20260915", inventory.byId.get("FCR-TABLE-REFERENCE-20260915"));
      ArrayNode exactIds = mapper.valueToTree(selected.keySet());
      if (evidence != null) {
        ArrayNode largest = mapper.createArrayNode();
        inventory.byId.entrySet().stream().filter(entry -> !selected.containsKey(entry.getKey()))
            .sorted(Comparator.comparingInt((Map.Entry<String, ObjectNode> entry) -> sourceLength(entry.getValue())).reversed())
            .limit(3).forEach(entry -> largest.add(entry.getValue()));
        ArrayNode provisional = evidence.deepCopy(); selected.values().forEach(provisional::add); provisional.addAll(largest);
        // Reserve a receipt with the complete pinned ID list and the largest
        // permitted semantic IDs. Reject before embedding, never drop rows.
        ArrayNode reserveMatches = mapper.createArrayNode();
        for (int i=0; i<3; i++) reserveMatches.addObject().put("id", "x".repeat(200)).put("score", -.9999999999999999);
        provisional.add(receipt(inventory.hash, state.index.digest, exactIds, reserveMatches));
        UatService.documentMap(provisional, tooLarge());
      }
      ArrayNode matches = search(state, question);
      for (JsonNode match : matches) selected.put(match.path("id").asText(), inventory.byId.get(match.path("id").asText()));
      selected.put("CASE-KNOWLEDGE-RETRIEVAL", receipt(inventory.hash, state.index.digest, exactIds, matches));
    }
    ArrayNode result = mapper.createArrayNode(); selected.values().forEach(doc -> result.add(doc.deepCopy()));
    UatService.documentMap(result, unavailable(), false);
    return result;
  }

  private ObjectNode receipt(String version, String digest, ArrayNode exactIds, ArrayNode matches) {
    ObjectNode provenance = mapper.createObjectNode().put("schemaVersion", "case-knowledge-selection-v1")
        .put("knowledgeVersion", version).put("embeddingModel", MODEL).put("embeddingDigest", digest)
        .put("processor", "GPU").put("meaning", "Current reference selection, not payment observations or final-outcome proof.");
    provenance.set("pinnedAndExactIds", exactIds); provenance.set("semanticMatches", matches);
    ObjectNode result = mapper.createObjectNode().put("id", "CASE-KNOWLEDGE-RETRIEVAL").put("kind", "knowledge")
        .put("title", "Knowledge retrieval provenance").put("content", provenance.toString());
    result.putObject("source").put("file", "case-knowledge-selection-v1").put("locator", "Frozen selection for this question");
    return result;
  }
  private int sourceLength(JsonNode doc) {
    int result = doc.path("id").asText().length() + doc.path("title").asText().length() + doc.path("content").asText().length();
    for (String key : List.of("file", "sheet", "range", "locator")) if (doc.path("source").hasNonNull(key)) result += doc.path("source").path(key).asText().length();
    return result;
  }
  private Inventory inventory(Actor actor, Set<String> warnings) {
    ArrayNode docs = guidance.documents(actor, warnings);
    ArrayNode status = statuses.documents(actor, warnings);
    String scopeId = status.isEmpty() ? "" : status.get(0).path("id").asText();
    Set<String> statusIds = new HashSet<>(); status.forEach(doc -> statusIds.add(doc.path("id").asText()));
    docs.addAll(status);
    CaseKnowledgeLimits.inventory(docs, unavailable());
    LinkedHashMap<String, ObjectNode> byId = new LinkedHashMap<>();
    for (JsonNode doc : docs) {
      if (doc.path("id").asText().equals("CASE-KNOWLEDGE-RETRIEVAL")) throw unavailable();
      byId.put(doc.path("id").asText(), (ObjectNode) doc);
    }
    return new Inventory(docs, byId, UatService.canonicalHash(docs), scopeId, statusIds);
  }
  private boolean always(String id, Inventory inventory) { return PINNED.contains(id) || id.equals(inventory.scopeId); }
  static boolean tableQuestion(String question) {
    if (TABLE_QUESTION.matcher(question).find()) return true;
    for (var columns : CaseEvidenceSchema.COLUMNS.values()) for (String column : columns)
      if (Pattern.compile("(?i)(?<![A-Z0-9_])" + Pattern.quote(column) + "(?![A-Z0-9_])").matcher(question).find()) return true;
    return false;
  }
  private IndexState state(Actor actor, Inventory inventory) {
    if (!enabled()) return new IndexState("DISABLED", null, Map.of(), 0);
    Index index;
    try {
      if (!Files.exists(Path.of(indexFile))) return new IndexState("MISSING", null, Map.of(), 0);
      index = load();
    } catch (RuntimeException failure) { return new IndexState("STALE", null, Map.of(), 0); }
    Map<String, Embedded> entries = index.tenants.get(actor.tenantId());
    // No metadata or counts from an index scoped only to another tenant.
    if (entries == null) return new IndexState("MISSING", null, Map.of(), 0);
    int count = 0;
    for (var item : inventory.byId.entrySet()) {
      Embedded embedded = entries.get(item.getKey());
      if (embedded != null && embedded.hash.equals(UatService.canonicalHash(item.getValue()))) count++;
    }
    return new IndexState(count == inventory.documents.size() && entries.size() == count ? "CURRENT" : "STALE", index, entries, count);
  }

  private Index load() {
    ObjectNode root;
    try (var input = Files.newInputStream(Path.of(indexFile))) {
      byte[] bytes = input.readNBytes(MAX_BYTES + 1);
      if (bytes.length > MAX_BYTES) throw unavailable();
      root = UatService.parseObject(mapper, bytes, unavailable());
    } catch (IOException failure) { throw unavailable(); }
    if (!names(root).equals(Set.of("schemaVersion", "model", "digest", "dimensions", "indexedAt", "tenants"))
        || !"case-knowledge-index-v1".equals(root.path("schemaVersion").asText()) || !MODEL.equals(root.path("model").asText())
        || !hash(root.path("digest")) || !root.path("dimensions").isIntegralNumber() || !root.path("dimensions").canConvertToInt() || root.path("dimensions").asInt() != 1024
        || !root.path("indexedAt").isTextual() || !root.path("tenants").isArray() || root.path("tenants").isEmpty() || root.path("tenants").size() > 10) throw unavailable();
    try { OffsetDateTime.parse(root.path("indexedAt").asText()); } catch (RuntimeException failure) { throw unavailable(); }
    Map<String, Map<String, Embedded>> tenants = new LinkedHashMap<>();
    int totalDocuments = 0;
    for (JsonNode tenant : root.path("tenants")) {
      if (!names(tenant).equals(Set.of("tenantId", "evidenceSchema", "documents")) || !tenant.path("tenantId").isTextual()
          || !tenant.path("tenantId").asText().matches("[A-Za-z0-9_-]{1,100}") || !SCHEMA.equals(tenant.path("evidenceSchema").asText())
          || !tenant.path("documents").isArray() || tenant.path("documents").isEmpty() || tenant.path("documents").size() > CaseKnowledgeLimits.DOCUMENTS) throw unavailable();
      totalDocuments += tenant.path("documents").size();
      if (totalDocuments > CaseKnowledgeLimits.TOTAL_INDEX_DOCUMENTS) throw unavailable();
      Map<String, Embedded> entries = new LinkedHashMap<>();
      for (JsonNode doc : tenant.path("documents")) {
        if (!names(doc).equals(Set.of("id", "documentHash", "vector")) || !doc.path("id").isTextual()
            || !doc.path("id").asText().matches("[A-Za-z0-9_-]{1,200}") || !hash(doc.path("documentHash"))
            || !doc.path("vector").isArray() || doc.path("vector").size() != 1024) throw unavailable();
        boolean nonzero = false;
        for (JsonNode value : doc.path("vector")) {
          if (!value.isNumber() || !Double.isFinite(value.doubleValue()) || Math.abs(value.doubleValue()) > 1) throw unavailable();
          nonzero |= value.doubleValue() != 0;
        }
        if (!nonzero || entries.putIfAbsent(doc.path("id").asText(), new Embedded(doc.path("documentHash").asText(), ((ArrayNode) doc.path("vector")).deepCopy())) != null) throw unavailable();
      }
      if (tenants.putIfAbsent(tenant.path("tenantId").asText(), entries) != null) throw unavailable();
    }
    return new Index(root.path("digest").asText(), root.path("indexedAt").asText(), tenants);
  }

  private ArrayNode search(IndexState state, String question) {
    ObjectNode request = mapper.createObjectNode().put("question", question).put("model", MODEL).put("digest", state.index.digest).put("limit", 3);
    ArrayNode entries = request.putArray("entries");
    state.entries.forEach((id, entry) -> entries.addObject().put("id", id).set("vector", entry.vector.deepCopy()));
    ObjectNode response = worker.searchCaseKnowledge(request);
    if (response == null || !names(response).equals(Set.of("model", "digest", "matches", "processor"))
        || !request.path("model").equals(response.path("model")) || !request.path("digest").equals(response.path("digest"))
        || !"GPU".equals(response.path("processor").asText()) || !response.path("matches").isArray() || response.path("matches").size() > 3) throw invalidSearch();
    Set<String> ids = new HashSet<>();
    for (JsonNode match : response.path("matches")) {
      if (!names(match).equals(Set.of("id", "score")) || !match.path("id").isTextual() || !state.entries.containsKey(match.path("id").asText())
          || !ids.add(match.path("id").asText()) || !match.path("score").isNumber() || !Double.isFinite(match.path("score").doubleValue())
          || Math.abs(match.path("score").doubleValue()) > 1) throw invalidSearch();
    }
    return ((ArrayNode) response.path("matches")).deepCopy();
  }
  private static boolean hash(JsonNode value) { return value.isTextual() && value.asText().matches("[a-f0-9]{64}"); }
  private static Set<String> names(JsonNode object) { Set<String> result = new HashSet<>(); object.fieldNames().forEachRemaining(result::add); return result; }
  private record Embedded(String hash, ArrayNode vector) { }
  private record Index(String digest, String indexedAt, Map<String, Map<String, Embedded>> tenants) { }
  private record IndexState(String status, Index index, Map<String, Embedded> entries, int matches) { }
  private record Inventory(ArrayNode documents, LinkedHashMap<String, ObjectNode> byId, String hash, String scopeId, Set<String> statusIds) { }
  private static ApiException tooLarge() { return new ApiException(422, "CASE_INVESTIGATION_EVIDENCE_LIMIT", "Evidence and selected knowledge must fit 100 documents and 50,000 source characters. No rows were removed or model call made."); }
  private static ApiException unavailable() { return new ApiException(503, "CASE_KNOWLEDGE_UNAVAILABLE", "Case knowledge could not be read or validated. Correct its source contract before investigating."); }
  private static ApiException invalidSearch() { return new ApiException(502, "INVALID_CASE_KNOWLEDGE_SEARCH", "Knowledge search did not return valid scoped matches. No substitute guidance was used."); }
}
