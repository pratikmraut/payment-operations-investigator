package dev.pratik.poi;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class UatService {
  static final int MAX_FILE_BYTES = 1048576;
  private final ObjectMapper mapper;
  private final UatWorkerClient worker;
  private final Path bundleDirectory, resultDirectory;

  public UatService(ObjectMapper mapper, UatWorkerClient worker,
      @Value("${poi.uat-bundle-dir:}") String bundles,
      @Value("${poi.uat-result-dir:}") String results) {
    this.mapper = mapper; this.worker = worker;
    this.bundleDirectory = bundles.isBlank() ? null : Path.of(bundles).toAbsolutePath().normalize();
    this.resultDirectory = results.isBlank() ? null : Path.of(results).toAbsolutePath().normalize();
  }

  public ObjectNode list(Actor actor) {
    ObjectNode output = mapper.createObjectNode().put("enabled", bundleDirectory != null);
    var items = output.putArray("items");
    if (bundleDirectory == null) return output;
    try (Stream<Path> files = Files.list(bundleDirectory)) {
      List<Path> paths = files.filter(p -> p.getFileName().toString().endsWith(".json"))
          .sorted().limit(101).toList();
      if (paths.size() > 100) throw invalidBundle();
      for (Path file : paths) {
        String filename = file.getFileName().toString();
        String id = filename.substring(0, filename.length() - 5);
        if (!safeId(id)) throw invalidBundle();
        ObjectNode bundle = load(id);
        if (!bundle.path("tenantId").asText().equals(actor.tenantId())) continue;
        ObjectNode metadata = bundle.deepCopy();
        metadata.remove(List.of("tenantId", "documents"));
        items.add(metadata);
      }
      return output;
    } catch (IOException ex) { throw invalidBundle(); }
  }

  public ObjectNode snapshot(String id, Actor actor) {
    ObjectNode bundle = authorized(id, actor);
    bundle.remove("tenantId");
    return bundle;
  }

  public ObjectNode answer(String id, Actor actor, byte[] body) {
    actor.requireWriter();
    if (body.length > UatController.MAX_REQUEST_BYTES)
      throw new ApiException(413, "UAT_REQUEST_TOO_LARGE", "Question requests are limited to 16 KiB.");
    ApiException invalid = new ApiException(400, "INVALID_REQUEST",
        "Provide only question (1–2000 characters) and the current evidenceHash.");
    ObjectNode question = parseObject(mapper, body, invalid);
    fields(question, invalid, "question", "evidenceHash");
    String text = text(question, "question", 2000, invalid);
    String expectedHash = text(question, "evidenceHash", 64, invalid);
    if (!expectedHash.matches("[a-f0-9]{64}")) throw invalid;
    ObjectNode bundle = authorized(id, actor);
    if (!expectedHash.equals(bundle.path("evidenceHash").asText())) throw stale();
    // Fail before model execution if there is no usable private result location.
    Path directory = answerDirectory(actor, id, true);
    ObjectNode request = mapper.createObjectNode().put("question", text).put("snapshotId", id)
        .put("evidenceHash", expectedHash);
    request.set("documents", bundle.path("documents").deepCopy());
    ObjectNode response = worker.answer(request.deepCopy());
    validateAnswer(response, request);
    if (!expectedHash.equals(authorized(id, actor).path("evidenceHash").asText())) throw stale();
    persist(directory, actor, request, response);
    return response.deepCopy();
  }

  public ObjectNode history(String id, Actor actor) {
    authorized(id, actor);
    ObjectNode output = mapper.createObjectNode();
    var items = output.putArray("items");
    Path directory = answerDirectory(actor, id, false);
    if (directory == null) return output;
    try (Stream<Path> files = Files.list(directory)) {
      List<Path> paths = files.filter(p -> p.getFileName().toString().matches("[0-9]{13}-[a-f0-9-]{36}\\.json"))
          .sorted(Comparator.reverseOrder()).limit(50).toList();
      for (Path path : paths) {
        ObjectNode envelope = readFile(path, storageFailure());
        if (!envelope.path("schemaVersion").asText().equals("uat-answer-v1")
            || !envelope.path("classification").asText().equals("UAT")
            || !envelope.path("actor").path("tenantId").asText().equals(actor.tenantId())
            || !envelope.path("request").path("snapshotId").asText().equals(id)
            || !envelope.path("inputHash").asText().equals(canonicalHash(envelope.path("request"))))
          throw storageFailure();
        if (!(envelope.path("request") instanceof ObjectNode request)
            || !(envelope.path("answer") instanceof ObjectNode answer)) throw storageFailure();
        // Preserve earlier saved wording without allowing legacy responses on new worker calls.
        try { validateAnswer(answer, request, true); } catch (ApiException ex) { throw storageFailure(); }
        items.add(answer.deepCopy());
      }
      return output;
    } catch (IOException ex) { throw storageFailure(); }
  }

  private ObjectNode authorized(String id, Actor actor) {
    if (!safeId(id)) throw notFound();
    ObjectNode bundle = load(id);
    if (!bundle.path("tenantId").asText().equals(actor.tenantId())) throw notFound();
    return bundle;
  }

  private ObjectNode load(String id) {
    if (bundleDirectory == null)
      throw new ApiException(503, "UAT_DISABLED", "The private UAT workspace is not configured.");
    Path file = bundleDirectory.resolve(id + ".json");
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw notFound();
    ObjectNode bundle = readFile(file, invalidBundle());
    validateBundle(bundle, id);
    return bundle;
  }

  private void validateBundle(ObjectNode bundle, String expectedId) {
    ApiException invalid = invalidBundle();
    fields(bundle, invalid, "snapshotId", "classification", "title", "paymentReference", "utr", "amount",
        "currency", "tenantId", "evidenceHash", "documents", "coverage", "warnings");
    if (!text(bundle, "snapshotId", 100, invalid).equals(expectedId)
        || !text(bundle, "classification", 20, invalid).equals("UAT")) throw invalid;
    text(bundle, "title", 500, invalid);
    text(bundle, "paymentReference", 200, invalid);
    if (bundle.hasNonNull("utr")) text(bundle, "utr", 200, invalid);
    String amount = text(bundle, "amount", 80, invalid);
    if (!amount.matches("-?(0|[1-9][0-9]{0,37})(\\.[0-9]{1,18})?")) throw invalid;
    if (!text(bundle, "currency", 3, invalid).matches("[A-Z]{3}")) throw invalid;
    if (!text(bundle, "tenantId", 100, invalid).matches("[A-Za-z0-9_-]+")) throw invalid;
    String hash = text(bundle, "evidenceHash", 64, invalid);
    if (!hash.matches("[a-f0-9]{64}")) throw invalid;
    documentMap(bundle.path("documents"), invalid);
    JsonNode coverage = array(bundle, "coverage", 100, invalid);
    for (JsonNode group : coverage) {
      fields(group, invalid, "name", "rowCount", "completion");
      text(group, "name", 100, invalid); text(group, "completion", 500, invalid);
      if (!group.path("rowCount").isIntegralNumber() || !group.path("rowCount").canConvertToLong()
          || group.path("rowCount").longValue() < 0) throw invalid;
    }
    for (JsonNode warning : array(bundle, "warnings", 100, invalid)) textValue(warning, 2000, invalid);
    ObjectNode original = bundle.deepCopy(); original.remove("evidenceHash");
    if (!hash.equals(canonicalHash(original))) throw invalid;
  }

  static Map<String, JsonNode> documentMap(JsonNode documents, ApiException invalid) {
    return documentMap(documents, invalid, true);
  }

  static Map<String, JsonNode> documentMap(JsonNode documents, ApiException invalid, boolean requireEvidence) {
    if (!documents.isArray() || documents.isEmpty() || documents.size() > 100) throw invalid;
    Map<String, JsonNode> ids = new LinkedHashMap<>();
    int contentLength = 0;
    boolean evidence = false;
    for (JsonNode doc : documents) {
      fields(doc, invalid, "id", "kind", "title", "content", "source");
      String id = text(doc, "id", 200, invalid);
      if (ids.putIfAbsent(id, doc) != null) throw invalid;
      if (!Set.of("evidence", "knowledge").contains(text(doc, "kind", 20, invalid))) throw invalid;
      evidence |= doc.path("kind").asText().equals("evidence");
      contentLength += id.length() + text(doc, "title", 500, invalid).length();
      contentLength += text(doc, "content", 50000, invalid).length();
      JsonNode source = doc.path("source");
      fields(source, invalid, "file", "sheet", "range", "locator");
      contentLength += text(source, "file", 1000, invalid).length();
      for (String key : List.of("sheet", "range", "locator"))
        if (source.hasNonNull(key)) contentLength += text(source, key, key.equals("locator") ? 1000 : 200, invalid).length();
    }
    if ((requireEvidence && !evidence) || contentLength > 50000) throw invalid;
    return ids;
  }

  static void validateAnswer(ObjectNode answer, ObjectNode request) {
    validateAnswer(answer, request, false);
  }

  private static void validateAnswer(ObjectNode answer, ObjectNode request, boolean allowLegacyHistory) {
    ApiException invalid = invalidWorker();
    fields(answer, invalid, "answerId", "question", "snapshotId", "evidenceHash", "answer", "claims",
        "unknowns", "nextChecks", "citations", "model", "generatedAt", "mode", "retrieval", "validation", "answerComposition");
    if (!safeId(text(answer, "answerId", 100, invalid))) throw invalid;
    for (String key : List.of("question", "snapshotId", "evidenceHash"))
      if (!answer.path(key).equals(request.path(key))) throw invalid;
    if (!text(answer, "mode", 50, invalid).equals("model-generated")
        || !text(answer, "validation", 100, invalid).equals("structure-and-source-membership-only")) throw invalid;
    text(answer, "answer", 20000, invalid);
    try { Instant.parse(text(answer, "generatedAt", 100, invalid)); }
    catch (java.time.DateTimeException ex) { throw invalid; }
    Map<String, JsonNode> supplied = documentMap(request.path("documents"), invalid);
    JsonNode retrieval = answer.path("retrieval");
    fields(retrieval, invalid, "method", "documentIds");
    text(retrieval, "method", 100, invalid);
    Set<String> retrieved = idSet(array(retrieval, "documentIds", 100, invalid), invalid);
    if (retrieved.isEmpty() || !supplied.keySet().containsAll(retrieved)) throw invalid;
    Set<String> cited = new HashSet<>();
    for (JsonNode doc : array(answer, "citations", 100, invalid)) {
      String id = text(doc, "id", 200, invalid);
      if (!cited.add(id) || !retrieved.contains(id) || !doc.equals(supplied.get(id))) throw invalid;
    }
    Set<String> used = new HashSet<>();
    StringJoiner joinedClaims = new StringJoiner("\n\n");
    JsonNode claims = array(answer, "claims", 50, invalid);
    if (claims.isEmpty()) throw invalid;
    for (JsonNode claim : claims) {
      fields(claim, invalid, "text", "evidenceIds");
      joinedClaims.add(text(claim, "text", 5000, invalid));
      Set<String> ids = idSet(array(claim, "evidenceIds", 100, invalid), invalid);
      if (ids.isEmpty() || !cited.containsAll(ids)) throw invalid;
      used.addAll(ids);
    }
    if (!used.equals(cited)) throw invalid;
    if (!allowLegacyHistory || answer.has("answerComposition")) {
      if (!text(answer, "answerComposition", 100, invalid).equals("joined-model-claims")
          || !answer.path("answer").asText().equals(joinedClaims.toString())) throw invalid;
    }
    for (String key : List.of("unknowns", "nextChecks"))
      for (JsonNode item : array(answer, key, 50, invalid)) textValue(item, 5000, invalid);
    JsonNode model = answer.path("model");
    fields(model, invalid, "provider", "name", "actualCalls", "promptTokens", "completionTokens", "durationMs");
    if (!text(model, "provider", 50, invalid).equals("ollama")) throw invalid;
    text(model, "name", 200, invalid);
    if (!model.path("actualCalls").isIntegralNumber() || !model.path("actualCalls").canConvertToInt()
        || model.path("actualCalls").intValue() < 1 || model.path("actualCalls").intValue() > 100) throw invalid;
    for (String key : List.of("promptTokens", "completionTokens")) {
      JsonNode value = model.path(key);
      if (value.isMissingNode() || (!value.isNull() && (!value.isIntegralNumber() || !value.canConvertToLong()
          || value.longValue() < 0 || value.longValue() > JsonSupport.MAX_SAFE_INTEGER))) throw invalid;
    }
    if (!model.path("durationMs").isNumber() || !Double.isFinite(model.path("durationMs").doubleValue())
        || model.path("durationMs").doubleValue() < 0) throw invalid;
  }

  private Path answerDirectory(Actor actor, String id, boolean create) {
    if (resultDirectory == null) {
      if (!create) return null;
      throw storageFailure();
    }
    try {
      if (create) Files.createDirectories(resultDirectory);
      if (!Files.exists(resultDirectory)) return null;
      if (!Files.isDirectory(resultDirectory, LinkOption.NOFOLLOW_LINKS)) throw storageFailure();
      Path path = resultDirectory;
      for (String segment : List.of(actor.tenantId(), id)) {
        path = path.resolve(segment);
        if (create && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
          try { Files.createDirectory(path); } catch (FileAlreadyExistsException ignored) { }
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw storageFailure();
      }
      if (create && !Files.isWritable(path)) throw storageFailure();
      return path;
    } catch (IOException ex) { throw storageFailure(); }
  }

  private void persist(Path directory, Actor actor, ObjectNode request, ObjectNode response) {
    Path temporary = null;
    try {
      ObjectNode receipt = mapper.createObjectNode().put("schemaVersion", "uat-answer-v1")
          .put("classification", "UAT").put("savedAt", Instant.now().toString())
          .put("inputHash", canonicalHash(request));
      receipt.set("actor", mapper.valueToTree(actor));
      receipt.set("request", request.deepCopy()); receipt.set("answer", response.deepCopy());
      byte[] bytes = mapper.writeValueAsBytes(receipt);
      if (bytes.length > MAX_FILE_BYTES) throw storageFailure();
      temporary = Files.createTempFile(directory, ".pending-", ".tmp");
      try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) file.write(buffer);
        file.force(true);
      }
      Path destination = directory.resolve(System.currentTimeMillis() + "-" + UUID.randomUUID() + ".json");
      Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException ex) { throw storageFailure(); }
    finally {
      if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
    }
  }

  private ObjectNode readFile(Path file, ApiException error) {
    try {
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE_BYTES) throw error;
      try (var stream = Files.newInputStream(file)) {
        byte[] bytes = stream.readNBytes(MAX_FILE_BYTES + 1);
        if (bytes.length > MAX_FILE_BYTES) throw error;
        return parseObject(mapper, bytes, error);
      }
    } catch (IOException ex) { throw error; }
  }

  static ObjectNode parseObject(ObjectMapper mapper, byte[] bytes, ApiException error) {
    try (JsonParser parser = mapper.getFactory().createParser(bytes)) {
      parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      JsonNode node = mapper.readTree(parser);
      if (!(node instanceof ObjectNode object) || parser.nextToken() != null) throw error;
      return object;
    } catch (IOException ex) { throw error; }
  }

  static String canonicalHash(JsonNode input) {
    return InvestigationService.hash(canonical(input).toString());
  }

  private static JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      ObjectNode sorted = new ObjectMapper().createObjectNode();
      List<String> names = new ArrayList<>(); node.fieldNames().forEachRemaining(names::add);
      Collections.sort(names); names.forEach(name -> sorted.set(name, canonical(node.get(name)))); return sorted;
    }
    if (node.isArray()) {
      var sorted = new ObjectMapper().createArrayNode(); node.forEach(value -> sorted.add(canonical(value))); return sorted;
    }
    return node;
  }

  private static Set<String> idSet(JsonNode array, ApiException error) {
    Set<String> ids = new HashSet<>();
    for (JsonNode item : array) if (!ids.add(textValue(item, 200, error))) throw error;
    return ids;
  }
  private static JsonNode array(JsonNode object, String key, int limit, ApiException error) {
    JsonNode value = object.path(key);
    if (!value.isArray() || value.size() > limit) throw error;
    return value;
  }
  private static void fields(JsonNode object, ApiException error, String... allowed) {
    if (!object.isObject()) throw error;
    Set<String> fields = Set.of(allowed);
    object.fieldNames().forEachRemaining(name -> { if (!fields.contains(name)) throw error; });
  }
  private static String text(JsonNode object, String key, int limit, ApiException error) {
    return textValue(object.path(key), limit, error);
  }
  private static String textValue(JsonNode node, int limit, ApiException error) {
    if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() > limit
        || node.textValue().chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) throw error;
    return node.textValue();
  }
  private static boolean safeId(String id) { return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}"); }
  private static ApiException stale() { return new ApiException(409, "STALE_UAT_EVIDENCE", "The evidence changed. Refresh the snapshot before asking again."); }
  private static ApiException notFound() { return new ApiException(404, "UAT_SNAPSHOT_NOT_FOUND", "The UAT snapshot was not found."); }
  private static ApiException invalidBundle() { return new ApiException(503, "UAT_BUNDLE_UNAVAILABLE", "The private UAT bundle could not be read or validated."); }
  private static ApiException storageFailure() { return new ApiException(503, "UAT_STORAGE_UNAVAILABLE", "The private answer history could not be read or saved. No completed answer receipt was returned."); }
  static ApiException invalidWorker() { return new ApiException(502, "INVALID_UAT_ANSWER", "The model answer failed source or schema checks. No answer was stored."); }
}
