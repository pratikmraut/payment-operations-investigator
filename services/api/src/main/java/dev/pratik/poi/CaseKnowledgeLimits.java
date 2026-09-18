package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;

/** Inventory bounds are separate from the unchanged 100-document model input. */
final class CaseKnowledgeLimits {
  static final int DOCUMENTS = 1000;
  static final int TOTAL_INDEX_DOCUMENTS = 3000;
  static final int SOURCE_BYTES = 8 * 1024 * 1024;
  static final int SOURCE_CHARACTERS = 4 * 1024 * 1024;
  static final int INDEX_BYTES = 64 * 1024 * 1024;

  private CaseKnowledgeLimits() { }

  static void inventory(JsonNode documents, ApiException invalid) {
    if (!documents.isArray() || documents.isEmpty() || documents.size() > DOCUMENTS) throw invalid;
    Set<String> ids = new HashSet<>();
    long characters = 0;
    for (JsonNode document : documents) {
      // Reuse the strict standard-document validation without applying a model
      // context's aggregate count/text allowance to the source inventory.
      var single = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode().add(document);
      UatService.documentMap(single, invalid, false);
      String id = document.path("id").asText();
      if (!"knowledge".equals(document.path("kind").asText()) || !ids.add(id)
          || !id.matches("[A-Za-z0-9_-]{1,200}") || reserved(id)) throw invalid;
      characters += document.toString().length();
      if (characters > SOURCE_CHARACTERS) throw invalid;
    }
  }

  static boolean reserved(String id) {
    return Set.of("CASE-CONTEXT", "CASE-EVIDENCE-SELECTION", "CASE-KNOWLEDGE-RETRIEVAL", "FCR-ENUM-RETRIEVAL").contains(id)
        || id.matches("(?:PAYMENT|HOST|HISTORY|STATUS)-(?:ROW-[0-9]+|COVERAGE)");
  }
}
