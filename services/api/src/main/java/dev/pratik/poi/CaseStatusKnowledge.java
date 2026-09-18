package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Selects private, field-specific reference knowledge before an investigation input is frozen. */
@Component
public final class CaseStatusKnowledge {
  static final int MAX_BYTES = CaseKnowledgeLimits.INDEX_BYTES;
  private static final String SCHEMA = "fcr-case-evidence-v1";
  private static final Set<String> FIELDS = Set.of("CODSTATUS", "ACCTSTATUS", "MSGSTATUS");
  private static final Pattern MENTION = Pattern.compile(
      "(?i)(?<![A-Z0-9_.])(?:PM_NEFT_TXN_LOG\\.)?(CODSTATUS|ACCTSTATUS|MSGSTATUS)[\"']?\\s*(?:=|:|\\bis\\b)?\\s*[\"']?([0-9]+)(?![A-Z0-9_]|\\.[0-9])");
  private final ObjectMapper mapper;
  private final String catalogFile;
  private final UatWorkerClient worker;

  public CaseStatusKnowledge(ObjectMapper mapper,
      @Value("${poi.case-investigation.status-catalog-file:}") String catalogFile, UatWorkerClient worker) {
    this.mapper = mapper;
    this.catalogFile = catalogFile == null ? "" : catalogFile;
    this.worker = worker;
  }

  ArrayNode documents(Actor actor, Set<String> warnings) {
    ArrayNode result = mapper.createArrayNode();
    if (catalogFile.isBlank()) return result;
    Catalog catalog = load();
    if (!actor.tenantId().equals(catalog.tenantId)) return result;
    result.add(catalog.overview.deepCopy());
    catalog.byId.values().forEach(entry -> result.add(entry.document.deepCopy()));
    return result;
  }

  ArrayNode selectExact(Actor actor, ObjectNode payload, String question, Set<String> warnings) {
    return select(actor, payload, question, warnings, false);
  }

  ArrayNode select(Actor actor, ObjectNode payload, String question, Set<String> warnings) {
    return select(actor, payload, question, warnings, true);
  }

  ArrayNode reserve(Actor actor, ObjectNode payload, String question, Set<String> warnings) {
    ArrayNode exact = selectExact(actor, payload, question, warnings);
    if (exact.isEmpty() || question == null) return exact;
    Set<String> ids = new HashSet<>(); exact.forEach(doc -> ids.add(doc.path("id").asText()));
    ArrayNode all = documents(actor, warnings);
    List<JsonNode> optional = new ArrayList<>();
    all.forEach(doc -> { if (!ids.contains(doc.path("id").asText())) optional.add(doc); });
    optional.sort(Comparator.comparingInt((JsonNode doc) -> doc.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).reversed());
    optional.stream().limit(3).forEach(exact::add);
    // The receipt carries two exact ID lists and three bounded matches. This
    // placeholder is only capacity reservation, never frozen or model-visible.
    ObjectNode receipt = mapper.createObjectNode().put("id", "FCR-ENUM-RETRIEVAL").put("kind", "knowledge")
        .put("title", "Reserved status selection receipt").put("content", "x".repeat(2000 + ids.size() * 410));
    receipt.putObject("source").put("file", "case-status-knowledge-selection-v1").put("locator", "Reserved selection receipt");
    exact.add(receipt);
    return exact;
  }

  private ArrayNode select(Actor actor, ObjectNode payload, String question, Set<String> warnings, boolean semantic) {
    ArrayNode selected = mapper.createArrayNode();
    if (catalogFile.isBlank()) return selected;
    Catalog catalog = load();
    if (!actor.tenantId().equals(catalog.tenantId)
        || !SCHEMA.equals(payload.path("schemaVersion").asText())) {
      warnings.add("The configured status reference does not apply to this tenant or evidence schema; no status definitions were selected from it.");
      return selected;
    }
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    for (JsonNode row : payload.path("sections").path("PAYMENT").path("rows")) {
      if (!"PM_NEFT_TXN_LOG".equals(row.path("SOURCE_TABLE").asText())) continue;
      for (String field : List.of("CODSTATUS", "ACCTSTATUS", "MSGSTATUS")) {
        JsonNode value = row.path(field);
        if (value.isTextual() && !value.textValue().isBlank()) {
          Entry entry = catalog.byCode.get(field + "\u0000" + value.textValue());
          if (entry != null) ids.add(entry.id);
          else warnings.add("No exact workbook definition was found for an observed PM_NEFT_TXN_LOG." + field
              + " value. Retain its raw evidence and do not borrow another field's mapping.");
        }
      }
    }
    ObjectNode receipt = null;
    if (question != null) {
      if (question.isBlank() || question.length() > 2000) throw invalid();
      ArrayNode exactIds = mapper.valueToTree(ids);
      LinkedHashSet<String> explicitIds = new LinkedHashSet<>();
      var mention = MENTION.matcher(question);
      while (mention.find()) {
        Entry entry = catalog.byCode.get(mention.group(1).toUpperCase(Locale.ROOT) + "\u0000" + mention.group(2));
        if (entry != null) { ids.add(entry.id); explicitIds.add(entry.id); }
      }
      if (semantic) {
      ArrayNode matches = search(catalog, question);
      matches.forEach(match -> ids.add(match.path("id").asText()));
      ObjectNode selection = mapper.createObjectNode().put("schemaVersion", "fcr-status-selection-v1")
          .put("table", "PM_NEFT_TXN_LOG").put("sourceSha256", catalog.sourceHash)
          .put("embeddingModel", catalog.model).put("embeddingDigest", catalog.digest).put("processor", "GPU")
          .put("meaning", "Reference selection; applicability limits in " + catalog.overview.path("id").asText() + ".");
      selection.set("exactEvidenceDocumentIds", exactIds);
      selection.set("explicitQuestionDocumentIds", mapper.valueToTree(explicitIds));
      selection.set("semanticMatches", matches);
      receipt = mapper.createObjectNode().put("id", "FCR-ENUM-RETRIEVAL").put("kind", "knowledge")
          .put("title", "Selected status-reference retrieval provenance").put("content", selection.toString());
      receipt.putObject("source").put("file", "case-status-knowledge-selection-v1")
          .put("locator", "Frozen reference selection for this question");
      }
    }
    selected.add(catalog.overview.deepCopy());
    for (String id : ids) selected.add(catalog.byId.get(id).document.deepCopy());
    if (receipt != null) selected.add(receipt);
    warnings.add("Workbook status definitions apply only to PM_NEFT_TXN_LOG and their named field. They do not define HOST/HISTORY fields, N10_STATUS or establish a payment outcome by themselves.");
    return selected;
  }

  private ArrayNode search(Catalog catalog, String question) {
    ObjectNode request = mapper.createObjectNode().put("question", question).put("model", catalog.model)
        .put("digest", catalog.digest).put("limit", 3);
    ArrayNode vectors = request.putArray("entries");
    catalog.byId.values().forEach(entry -> vectors.addObject().put("id", entry.id).set("vector", entry.vector.deepCopy()));
    ObjectNode response = worker.searchCaseKnowledge(request);
    if (response == null || !names(response).equals(Set.of("model", "digest", "matches", "processor"))
        || !request.path("model").equals(response.path("model"))
        || !request.path("digest").equals(response.path("digest"))
        || !"GPU".equals(response.path("processor").asText())
        || !response.path("matches").isArray() || response.path("matches").size() > 3) throw searchInvalid();
    LinkedHashSet<String> matches = new LinkedHashSet<>();
    for (JsonNode match : response.path("matches")) {
      if (!names(match).equals(Set.of("id", "score")) || !match.path("id").isTextual()
          || !catalog.byId.containsKey(match.path("id").textValue())
          || !matches.add(match.path("id").textValue()) || !finite(match.path("score"))
          || Math.abs(match.path("score").doubleValue()) > 1) throw searchInvalid();
    }
    return ((ArrayNode) response.path("matches")).deepCopy();
  }

  private Catalog load() {
    ObjectNode root;
    try (var input = Files.newInputStream(Path.of(catalogFile))) {
      byte[] bytes = input.readNBytes(MAX_BYTES + 1);
      if (bytes.length > MAX_BYTES) throw invalid();
      root = UatService.parseObject(mapper, bytes, invalid());
    } catch (IOException | java.nio.file.InvalidPathException failure) { throw invalid(); }
    if (!names(root).equals(Set.of("schemaVersion", "tenantId", "evidenceSchema", "table", "sourceSha256", "embedding", "overview", "entries"))
        || !"fcr-status-knowledge-v1".equals(root.path("schemaVersion").asText())
        || !SCHEMA.equals(root.path("evidenceSchema").asText())
        || !"PM_NEFT_TXN_LOG".equals(root.path("table").asText())
        || !text(root.path("tenantId"), 100) || !root.path("tenantId").textValue().matches("[A-Za-z0-9_-]{1,100}")
        || !text(root.path("sourceSha256"), 64) || !root.path("sourceSha256").textValue().matches("[a-f0-9]{64}")) throw invalid();
    JsonNode embedding = root.path("embedding");
    if (!names(embedding).equals(Set.of("model", "digest", "dimensions"))
        || !"qwen3-embedding:0.6b".equals(embedding.path("model").asText())
        || !text(embedding.path("digest"), 71) || !embedding.path("digest").textValue().matches("(?:sha256:)?[a-f0-9]{64}")
        || !embedding.path("dimensions").isIntegralNumber() || !embedding.path("dimensions").canConvertToInt()
        || embedding.path("dimensions").intValue() != 1024
        || !root.path("entries").isArray() || root.path("entries").isEmpty() || root.path("entries").size() > CaseKnowledgeLimits.DOCUMENTS) throw invalid();
    ObjectNode overview = document(root.path("overview"));
    Map<String, Entry> byId = new LinkedHashMap<>(), byCode = new HashMap<>();
    for (JsonNode item : root.path("entries")) {
      if (!names(item).equals(Set.of("field", "code", "label", "document", "vector"))
          || !FIELDS.contains(item.path("field").asText()) || !text(item.path("code"), 100)
          || !item.path("code").textValue().equals(item.path("code").textValue().strip())
          || !text(item.path("label"), 500) || !item.path("vector").isArray() || item.path("vector").size() != 1024) throw invalid();
      boolean nonzero = false;
      for (JsonNode value : item.path("vector")) {
        if (!finite(value) || Math.abs(value.doubleValue()) > 1) throw invalid();
        nonzero |= value.doubleValue() != 0;
      }
      if (!nonzero) throw invalid();
      ObjectNode document = document(item.path("document"));
      String id = document.path("id").textValue();
      Entry entry = new Entry(id, document, (ArrayNode) item.path("vector"));
      if (id.equals(overview.path("id").textValue()) || byId.putIfAbsent(id, entry) != null
          || byCode.putIfAbsent(item.path("field").textValue() + "\u0000" + item.path("code").textValue(), entry) != null) throw invalid();
    }
    return new Catalog(root.path("tenantId").textValue(), root.path("sourceSha256").textValue(), embedding.path("model").textValue(),
        embedding.path("digest").textValue(), overview, byId, byCode);
  }

  private ObjectNode document(JsonNode doc) {
    if (!names(doc).equals(Set.of("id", "kind", "title", "content", "source"))
        || !text(doc.path("id"), 200) || !doc.path("id").textValue().matches("[A-Za-z0-9_-]+")
        || CaseKnowledgeLimits.reserved(doc.path("id").textValue()) || !"knowledge".equals(doc.path("kind").asText())
        || !text(doc.path("title"), 500) || !text(doc.path("content"), 50000)
        || !doc.path("source").isObject() || !Set.of("file", "sheet", "range", "locator").containsAll(names(doc.path("source")))
        || !text(doc.path("source").path("file"), 1000)) throw invalid();
    for (String key : List.of("sheet", "range", "locator"))
      if (doc.path("source").hasNonNull(key) && !text(doc.path("source").path(key), key.equals("locator") ? 1000 : 200)) throw invalid();
    return ((ObjectNode) doc).deepCopy();
  }

  private static boolean finite(JsonNode node) { return node.isNumber() && Double.isFinite(node.doubleValue()); }
  private static boolean text(JsonNode node, int limit) { return node.isTextual() && !node.textValue().isBlank() && node.textValue().length() <= limit; }
  private static Set<String> names(JsonNode node) { Set<String> result = new HashSet<>(); node.fieldNames().forEachRemaining(result::add); return result; }
  private record Entry(String id, ObjectNode document, ArrayNode vector) { }
  private record Catalog(String tenantId, String sourceHash, String model, String digest, ObjectNode overview, Map<String, Entry> byId, Map<String, Entry> byCode) { }
  private static ApiException invalid() { return new ApiException(503, "CASE_STATUS_KNOWLEDGE_UNAVAILABLE", "The configured status knowledge catalog could not be read or validated. Correct it before investigating; no substitute definitions were used."); }
  private static ApiException searchInvalid() { return new ApiException(502, "INVALID_CASE_KNOWLEDGE_SEARCH", "The status knowledge search did not return a valid scoped result. No substitute definitions were used."); }
}
