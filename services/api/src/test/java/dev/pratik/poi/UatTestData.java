package dev.pratik.poi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;

/** Entirely original test data; no user exports or source code. */
final class UatTestData {
  static final ObjectMapper MAPPER = new ObjectMapper();
  static final Actor ANALYST = new Actor("analyst", "Test Analyst", "ANALYST", "northstar");
  static final Actor OTHER = new Actor("other", "Other Test Analyst", "ANALYST", "silverline");
  static final String ID = "ORIGINAL-TEST-SNAPSHOT";
  static ObjectNode bundle() {
    ObjectNode b = MAPPER.createObjectNode().put("snapshotId", ID).put("classification", "UAT")
        .put("title", "Original test snapshot").put("paymentReference", "ORIGINAL-TEST-REFERENCE")
        .put("utr", "ORIGINAL-TEST-UTR").put("amount", "10.25").put("currency", "INR").put("tenantId", "northstar");
    var documents = b.putArray("documents");
    var evidence = documents.addObject().put("id", "E1").put("kind", "evidence")
        .put("title", "Original test observation").put("content", "A fictional observation records status X; its meaning is unknown.");
    evidence.putObject("source").put("file", "original-test.json").put("locator", "$.status");
    var knowledge = documents.addObject().put("id", "K1").put("kind", "knowledge")
        .put("title", "Original test guidance").put("content", "Status codes need a verified field-specific lookup.");
    knowledge.putObject("source").put("file", "original-guidance.md").putNull("sheet");
    b.putArray("coverage").addObject().put("name", "original_test").put("rowCount", 1).put("completion", "COMPLETE");
    b.putArray("warnings").add("Original synthetic test data in a UAT-shaped envelope.");
    return seal(b);
  }
  static ObjectNode seal(ObjectNode b) {
    b.remove("evidenceHash"); b.put("evidenceHash", UatService.canonicalHash(b)); return b;
  }
  static byte[] question(ObjectNode bundle) throws Exception {
    return MAPPER.writeValueAsBytes(MAPPER.createObjectNode().put("question", "What can the observation establish?")
        .put("evidenceHash", bundle.path("evidenceHash").asText()));
  }
  static ObjectNode response(ObjectNode request) {
    ObjectNode a = MAPPER.createObjectNode().put("answerId", "ANSWER-" + UUID.randomUUID())
        .put("question", request.path("question").asText()).put("snapshotId", request.path("snapshotId").asText())
        .put("evidenceHash", request.path("evidenceHash").asText())
        .put("answer", "The fictional observation records X.").put("answerComposition", "joined-model-claims")
        .put("mode", "model-generated").put("validation", "structure-and-source-membership-only")
        .put("generatedAt", Instant.now().toString());
    a.putArray("claims").addObject().put("text", "The fictional observation records X.").putArray("evidenceIds").add("E1");
    a.putArray("unknowns").add("The meaning of X is not supplied.");
    a.putArray("nextChecks").add("Obtain the field-specific status lookup.");
    a.putArray("citations").add(request.path("documents").get(0).deepCopy());
    a.putObject("retrieval").put("method", "all-evidence-plus-lexical-knowledge").putArray("documentIds").add("E1").add("K1");
    a.putObject("model").put("provider", "ollama").put("name", "original-test-model").put("actualCalls", 1)
        .putNull("promptTokens").putNull("completionTokens").put("durationMs", 3);
    return a;
  }
}
