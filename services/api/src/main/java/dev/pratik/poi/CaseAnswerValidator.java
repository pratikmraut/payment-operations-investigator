package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks case-answer structure and exact quoted fields. This is not semantic entailment validation. */
final class CaseAnswerValidator {
  static final String PIPELINE = "case-evidence-rag-v1";
  static final List<String> CHECKS = List.of("source-membership", "literal-field-quotations",
      "required-unknowns-and-next-checks");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CaseAnswerValidator() { }

  static void validate(ObjectNode answer, ObjectNode input) {
    ApiException invalid = UatService.invalidWorker();
    if (answer == null) throw invalid;
    ObjectNode originalShape = answer.deepCopy();
    originalShape.remove("rag");
    UatService.validateAnswer(originalShape, input);
    if (answer.path("model").path("actualCalls").intValue() > 2) throw invalid;
    JsonNode rag = answer.path("rag");
    exactKeys(rag, Set.of("pipeline", "promptHash", "checks", "claimSupports"));
    if (!PIPELINE.equals(string(rag.path("pipeline"), false))
        || !string(rag.path("promptHash"), false).matches("[a-f0-9]{64}")) throw invalid;
    JsonNode checks = rag.path("checks");
    if (!checks.isArray() || checks.size() != CHECKS.size()) throw invalid;
    for (int index = 0; index < CHECKS.size(); index++)
      if (!CHECKS.get(index).equals(string(checks.get(index), false))) throw invalid;
    for (String key : List.of("unknowns", "nextChecks")) {
      JsonNode items = answer.path(key);
      if (items.isEmpty() || items.size() > 3) throw invalid;
      for (JsonNode item : items) if (item.textValue().length() > 2000) throw invalid;
    }

    Map<String, JsonNode> documents = UatService.documentMap(input.path("documents"), invalid);
    JsonNode claims = answer.path("claims"), supports = rag.path("claimSupports");
    if (claims.size() > 4 || !supports.isArray() || supports.size() != claims.size()) throw invalid;
    for (int index = 0; index < claims.size(); index++) {
      JsonNode claim = claims.get(index), support = supports.get(index);
      if (claim.path("text").textValue().length() > 2000) throw invalid;
      exactKeys(support, Set.of("claimIndex", "claimType", "fields"));
      if (!support.path("claimIndex").isIntegralNumber() || !support.path("claimIndex").canConvertToInt()
          || support.path("claimIndex").intValue() != index) throw invalid;
      String kind = string(support.path("claimType"), false);
      if (!Set.of("observation", "interpretation", "limitation", "source-cited").contains(kind)) throw invalid;
      Set<String> cited = new HashSet<>();
      claim.path("evidenceIds").forEach(id -> cited.add(id.textValue()));
      if (!kind.equals("source-cited")
          && cited.stream().noneMatch(id -> documents.get(id).path("kind").asText().equals("evidence"))) throw invalid;
      if (kind.equals("interpretation")
          && cited.stream().noneMatch(id -> documents.get(id).path("kind").asText().equals("knowledge"))) throw invalid;
      JsonNode fields = support.path("fields");
      if (!fields.isArray() || fields.size() > 6
          || (Set.of("observation", "interpretation").contains(kind) && fields.isEmpty())) throw invalid;
      Set<List<String>> seen = new HashSet<>();
      for (JsonNode field : fields) {
        exactKeys(field, Set.of("documentId", "field", "value"));
        String id = string(field.path("documentId"), false), name = string(field.path("field"), false);
        String value = string(field.path("value"), true);
        if (id.length() > 200 || name.length() > 200 || value.length() > 5000
            || !id.matches("(?:PAYMENT|HOST|HISTORY|STATUS)-ROW-[1-9][0-9]*")
            || !cited.contains(id) || !seen.add(List.of(id, name))) throw invalid;
        JsonNode document = documents.get(id);
        if (document == null || !document.path("kind").asText().equals("evidence")) throw invalid;
        ObjectNode source = UatService.parseObject(MAPPER,
            document.path("content").textValue().getBytes(StandardCharsets.UTF_8), invalid);
        if (!source.path(name).isTextual() || !source.path(name).textValue().equals(value)) throw invalid;
        String text = claim.path("text").textValue();
        if (!value.isBlank() && !text.contains(value)) throw invalid;
      }
    }
  }

  private static void exactKeys(JsonNode node, Set<String> expected) {
    if (!node.isObject()) throw UatService.invalidWorker();
    Set<String> keys = new HashSet<>();
    node.fieldNames().forEachRemaining(keys::add);
    if (!keys.equals(expected)) throw UatService.invalidWorker();
  }

  private static String string(JsonNode node, boolean blankAllowed) {
    if (node == null || !node.isTextual() || node.textValue().indexOf('\0') >= 0
        || (!blankAllowed && node.textValue().isBlank())) throw UatService.invalidWorker();
    return node.textValue();
  }
}
