package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

/** All source definitions, vectors and payments here are original synthetic fixtures. */
class CaseKnowledgeServiceTest {
  final ObjectMapper mapper = new ObjectMapper();
  final Actor actor = new Actor("fixture", "Fixture", "ANALYST", "northstar");
  final UatWorkerClient worker = mock(UatWorkerClient.class);
  @TempDir Path temporary;
  Path guidanceFile, catalogFile, indexFile;
  CaseGuidanceSource source;
  CaseStatusKnowledge statuses;
  CaseKnowledgeService service;

  void setup(boolean enabled) throws Exception {
    guidanceFile = temporary.resolve("guidance.json"); catalogFile = temporary.resolve("status.json"); indexFile = temporary.resolve("index.json");
    ObjectNode guide = mapper.createObjectNode().put("schemaVersion", "fcr-case-guidance-v1").put("tenantId", "northstar").put("evidenceSchema", "fcr-case-evidence-v1");
    ArrayNode docs = guide.putArray("documents");
    for (String id : List.of("NEFT-FIELD-DEFINITIONS", "EXPORT-INTERPRETATION", "N10-AND-OUTCOME-LIMITS", "FCR-TABLE-REFERENCE-20260915", "NEFT-POLLER-PATH", "NEFT-SPS-RESPONSE-PATH"))
      docs.add(CaseStatusKnowledgeTest.document(id, "Original private synthetic definition " + id));
    write(guidanceFile, guide);
    ObjectNode catalog = CaseStatusKnowledgeTest.catalog();
    ArrayNode entries = (ArrayNode) catalog.path("entries");
    for (int code = 1000; entries.size() < 80; code++) {
      ObjectNode entry = entries.addObject().put("field", "CODSTATUS").put("code", Integer.toString(code)).put("label", "Synthetic test state " + code);
      entry.set("document", CaseStatusKnowledgeTest.document("FIXTURE-CODSTATUS-" + code, "Original synthetic PM_NEFT_TXN_LOG.CODSTATUS=" + code));
      entry.set("vector", vector());
    }
    write(catalogFile, catalog);
    source = new CaseGuidanceSource(mapper, guidanceFile.toString());
    statuses = new CaseStatusKnowledge(mapper, catalogFile.toString(), worker);
    service = new CaseKnowledgeService(mapper, source, statuses, worker, enabled ? indexFile.toString() : "");
  }
  ArrayNode vector() { ArrayNode vector = mapper.createArrayNode().add(1.0); for (int i=1; i<1024; i++) vector.add(0.0); return vector; }
  void write(Path file, JsonNode value) throws Exception { Files.write(file, mapper.writeValueAsBytes(value)); }
  ObjectNode read(Path file) throws Exception { return (ObjectNode) mapper.readTree(Files.readAllBytes(file)); }
  ObjectNode index(ObjectNode library) {
    ObjectNode root = mapper.createObjectNode().put("schemaVersion", "case-knowledge-index-v1").put("model", "qwen3-embedding:0.6b")
        .put("digest", "b".repeat(64)).put("dimensions", 1024).put("indexedAt", "2026-01-02T03:04:05Z");
    ArrayNode docs = root.putArray("tenants").addObject().put("tenantId", "northstar").put("evidenceSchema", "fcr-case-evidence-v1").putArray("documents");
    for (JsonNode item : library.path("items")) docs.addObject().put("id", item.path("id").asText()).put("documentHash", item.path("version").asText()).set("vector", vector());
    return root;
  }
  void makeCurrent() throws Exception { write(indexFile, index(service.library(actor))); }
  ObjectNode payload() {
    ObjectNode root = mapper.createObjectNode().put("schemaVersion", "fcr-case-evidence-v1");
    root.putObject("sections").putObject("PAYMENT").putArray("rows").addObject().put("SOURCE_TABLE", "PM_NEFT_TXN_LOG").put("CODSTATUS", "991");
    return root;
  }
  List<String> ids(JsonNode docs) { List<String> ids = new ArrayList<>(); docs.forEach(doc -> ids.add(doc.path("id").asText())); return ids; }
  JsonNode find(JsonNode docs, String id) { for (JsonNode doc : docs) if (id.equals(doc.path("id").asText())) return doc; throw new AssertionError(id); }
  ObjectNode original(JsonNode item) { ObjectNode doc = mapper.createObjectNode(); for (String key : List.of("id", "kind", "title", "content", "source")) doc.set(key, item.path(key).deepCopy()); return doc; }

  @Test void visibleInventoryHasEveryCurrentDefinitionAndNoVectorsOrModelCalls() throws Exception {
    setup(false);
    ObjectNode result = service.library(actor);
    assertThat(result.path("items")).hasSize(90);
    assertThat(result.path("embedding").path("status").asText()).isEqualTo("DISABLED");
    assertThat(result.path("embedding").path("totalDocuments").asInt()).isEqualTo(90);
    for (JsonNode item : result.path("items")) assertThat(item.path("version").asText()).isEqualTo(UatService.canonicalHash(original(item)));
    assertThat(result.toString()).doesNotContain("\"vector\"", temporary.toString(), "documentHash");
    assertThat(ids(result.path("items"))).doesNotHaveDuplicates();
    verifyNoInteractions(worker);
  }
  @Test void indexBuiltFromVisibleHashesExactlyMatchesSourceProviderAndCurrentStatus() throws Exception {
    setup(true);
    ObjectNode before = service.library(actor);
    assertThat(before.path("embedding").path("status").asText()).isEqualTo("MISSING");
    makeCurrent(); ObjectNode current = service.library(actor);
    assertThat(current.path("embedding").path("status").asText()).isEqualTo("CURRENT");
    assertThat(current.path("embedding").path("indexedDocuments").asInt()).isEqualTo(90);
    assertThat(current.path("version")).isEqualTo(before.path("version"));
    for (JsonNode item : current.path("items")) assertThat(item.path("embeddingStatus").asText()).isEqualTo("CURRENT");
    assertThat(find(current.path("items"), "FIXTURE-OVERVIEW").path("category").asText()).isEqualTo("SCOPE");
    assertThat(find(current.path("items"), "FIXTURE-CODSTATUS-991").path("selection").asText()).isEqualTo("EXACT_OR_SEMANTIC");
    verifyNoInteractions(worker);
  }
  @Test void changedContentOrSourceLocatorInvalidatesIndexWithoutLosingVisibleKnowledge() throws Exception {
    setup(true); makeCurrent(); ObjectNode before = service.library(actor), guide = read(guidanceFile);
    ObjectNode first = (ObjectNode) guide.path("documents").get(0);
    ((ObjectNode) first.path("source")).put("locator", "Changed source location");
    write(guidanceFile, guide);
    ObjectNode after = service.library(actor);
    assertThat(after.path("version")).isNotEqualTo(before.path("version"));
    assertThat(after.path("items")).hasSize(90);
    assertThat(after.path("embedding").path("status").asText()).isEqualTo("STALE");
    assertThat(after.path("embedding").path("indexedDocuments").asInt()).isEqualTo(89);
    assertThatThrownBy(() -> service.select(actor, payload(), "Explain the payment", new LinkedHashSet<>()))
        .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code).isEqualTo("CASE_KNOWLEDGE_INDEX_NOT_CURRENT"));
    verifyNoInteractions(worker);
  }
  @Test void anotherTenantSeesOnlyGenericSourcesAndNoPrivateIndexMetadata() throws Exception {
    setup(true); makeCurrent();
    Actor other = new Actor("other", "Other", "ANALYST", "silverline");
    ObjectNode result = service.library(other);
    assertThat(result.path("items")).hasSize(3);
    assertThat(result.path("embedding").path("totalDocuments").asInt()).isEqualTo(3);
    assertThat(result.path("embedding").path("indexedDocuments").asInt()).isZero();
    assertThat(result.path("embedding").path("digest").isNull()).isTrue();
    assertThat(result.toString()).doesNotContain("FIXTURE-", "synthetic-status-reference", "b".repeat(64), "northstar");
    verifyNoInteractions(worker);
  }
  @Test void oneSearchCoversAllKnowledgeAndSelectionPreservesExactNativeDefinitions() throws Exception {
    setup(true); makeCurrent(); ObjectNode library = service.library(actor);
    when(worker.searchCaseKnowledge(any())).thenReturn(CaseStatusKnowledgeTest.searchResponse("NEFT-POLLER-PATH", "FIXTURE-MSGSTATUS-3", "FIXTURE-CODSTATUS-991"));
    ObjectNode payload = payload(), unchanged = payload.deepCopy();
    ArrayNode result = service.select(actor, payload, "Explain CODSTATUS=992 and the source columns", new LinkedHashSet<>());
    assertThat(ids(result)).contains("GUIDE-SOURCE-COVERAGE", "GUIDE-SOURCE-TIME", "GUIDE-RAW-STATUS", "FIXTURE-OVERVIEW",
        "NEFT-FIELD-DEFINITIONS", "EXPORT-INTERPRETATION", "N10-AND-OUTCOME-LIMITS", "FIXTURE-CODSTATUS-991", "FIXTURE-CODSTATUS-992", "NEFT-POLLER-PATH", "FIXTURE-MSGSTATUS-3", "FCR-TABLE-REFERENCE-20260915", "CASE-KNOWLEDGE-RETRIEVAL")
        .doesNotContain("FCR-ENUM-RETRIEVAL").doesNotHaveDuplicates();
    for (JsonNode doc : result) if (!doc.path("id").asText().equals("CASE-KNOWLEDGE-RETRIEVAL"))
      assertThat(doc).isEqualTo(original(find(library.path("items"), doc.path("id").asText())));
    assertThat(payload).isEqualTo(unchanged);
    var argument = org.mockito.ArgumentCaptor.forClass(ObjectNode.class); verify(worker, times(1)).searchCaseKnowledge(argument.capture());
    assertThat(argument.getValue().path("entries")).hasSize(90);
    assertThat(argument.getValue().toString()).doesNotContain("Original private", "SOURCE_TABLE", "synthetic-status-reference");
    JsonNode receipt = mapper.readTree(find(result, "CASE-KNOWLEDGE-RETRIEVAL").path("content").asText());
    assertThat(receipt.path("knowledgeVersion")).isEqualTo(library.path("version"));
    assertThat(receipt.path("semanticMatches")).hasSize(3);
  }
  @Test void readingCaseContextSelectsExactGuidanceWithoutRequiringIndexOrRunningModel() throws Exception {
    setup(true);
    ArrayNode result = service.select(actor, payload(), null, new LinkedHashSet<>());
    assertThat(ids(result)).contains("FIXTURE-CODSTATUS-991", "FIXTURE-OVERVIEW").doesNotContain("CASE-KNOWLEDGE-RETRIEVAL", "NEFT-POLLER-PATH");
    verifyNoInteractions(worker);
  }
  @Test void malformedDuplicateOrOversizedIndexNeverRunsSearch() throws Exception {
    setup(true); ObjectNode good = index(service.library(actor));
    List<ObjectNode> invalid = new ArrayList<>();
    invalid.add(good.deepCopy().put("dimensions", 1023)); invalid.add(good.deepCopy().put("dimensions", 4294968320L)); invalid.add(good.deepCopy().put("digest", "bad"));
    invalid.add(good.deepCopy().put("unexpected", true));
    ObjectNode duplicate = good.deepCopy(); ((ArrayNode) duplicate.path("tenants")).add(duplicate.path("tenants").get(0).deepCopy()); invalid.add(duplicate);
    ObjectNode zero = good.deepCopy(); ((ArrayNode) zero.path("tenants").get(0).path("documents").get(0).path("vector")).set(0, mapper.getNodeFactory().numberNode(0)); invalid.add(zero);
    ObjectNode duplicateDocument = good.deepCopy(); ArrayNode docs = (ArrayNode) duplicateDocument.path("tenants").get(0).path("documents"); docs.add(docs.get(0).deepCopy()); invalid.add(duplicateDocument);
    for (ObjectNode value : invalid) {
      write(indexFile, value);
      assertThat(service.library(actor).path("embedding").path("status").asText()).isEqualTo("STALE");
      assertThatThrownBy(() -> service.select(actor, payload(), "Explain", new LinkedHashSet<>())).isInstanceOf(ApiException.class);
    }
    Files.writeString(indexFile, "{\"schemaVersion\":\"bad\",\"schemaVersion\":\"case-knowledge-index-v1\"}");
    assertThat(service.library(actor).path("embedding").path("status").asText()).isEqualTo("STALE");
    Files.writeString(indexFile, " ".repeat(4 * 1024 * 1024 + 1));
    assertThat(service.library(actor).path("embedding").path("status").asText()).isEqualTo("STALE");
    verifyNoInteractions(worker);
  }
  @Test void forgedSearchCannotIntroduceAnotherDocumentOrCpuFallback() throws Exception {
    setup(true); makeCurrent();
    for (ObjectNode response : List.of(CaseStatusKnowledgeTest.searchResponse("OTHER-TENANT"),
        CaseStatusKnowledgeTest.searchResponse("GUIDE-RAW-STATUS").put("processor", "CPU"),
        CaseStatusKnowledgeTest.searchResponse("GUIDE-RAW-STATUS", "GUIDE-RAW-STATUS"))) {
      when(worker.searchCaseKnowledge(any())).thenReturn(response);
      assertThatThrownBy(() -> service.select(actor, payload(), "Explain", new LinkedHashSet<>()))
          .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code).isEqualTo("INVALID_CASE_KNOWLEDGE_SEARCH"));
    }
  }
  @Test void projectionValidatesEvidenceBeforeSearchAndPreservesEveryRawRow() throws Exception {
    setup(true); makeCurrent(); when(worker.searchCaseKnowledge(any())).thenReturn(CaseStatusKnowledgeTest.searchResponse());
    CaseEvidenceProjectionTest fixtures = new CaseEvidenceProjectionTest();
    CaseEvidenceProjection projection = new CaseEvidenceProjection(mapper, source, statuses, service);
    ObjectNode snapshot = fixtures.snapshot(), before = snapshot.deepCopy();
    ObjectNode result = projection.project(actor, fixtures.item(), snapshot, "Explain the payment");
    for (String id : List.of("PAYMENT-ROW-1", "HOST-ROW-1", "HISTORY-ROW-1"))
      assertThat(find(result.path("documents"), id)).isEqualTo(fixtures.document(fixtures.project(snapshot), id));
    assertThat(snapshot).isEqualTo(before);
    clearInvocations(worker);
    ((ObjectNode) snapshot.path("payload").path("payment")).put("reference", "other-reference");
    assertThatThrownBy(() -> projection.project(actor, fixtures.item(), snapshot, "Explain")).isInstanceOf(ApiException.class);
    verifyNoInteractions(worker);
  }
  @Test void missingOneEmbeddingHasTruthfulPerItemStateAndCannotRunPartialIndex() throws Exception {
    setup(true); ObjectNode index = index(service.library(actor));
    ArrayNode indexed = (ArrayNode) index.path("tenants").get(0).path("documents");
    String removed = indexed.remove(indexed.size() - 1).path("id").asText(); write(indexFile, index);
    ObjectNode library = service.library(actor);
    assertThat(library.path("embedding").path("status").asText()).isEqualTo("STALE");
    assertThat(library.path("embedding").path("indexedDocuments").asInt()).isEqualTo(89);
    assertThat(find(library.path("items"), removed).path("embeddingStatus").asText()).isEqualTo("MISSING");
    assertThatThrownBy(() -> service.select(actor, payload(), "Explain", new LinkedHashSet<>())).isInstanceOf(ApiException.class);
    verifyNoInteractions(worker);
  }
  @Test void rawEvidencePlusReservedSelectionLimitsAreCheckedBeforeEmbedding() throws Exception {
    setup(true); makeCurrent();
    ArrayNode raw = mapper.createArrayNode();
    for (int i=0; i<95; i++) {
      ObjectNode doc = CaseStatusKnowledgeTest.document("PAYMENT-ROW-" + (i + 1), "{\"CODSTATUS\":\"991\"}");
      doc.put("kind", "evidence"); raw.add(doc);
    }
    UatService.documentMap(raw, new ApiException(422, "FIXTURE", "Invalid fixture"));
    assertThatThrownBy(() -> service.select(actor, payload(), "Explain", new LinkedHashSet<>(), raw))
        .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code).isEqualTo("CASE_INVESTIGATION_EVIDENCE_LIMIT"));
    verifyNoInteractions(worker);
  }
  @Test void existingDocumentValidatorStillRequiresEvidenceUnlessExplicitlyKnowledgeOnly() {
    ArrayNode docs = mapper.createArrayNode().add(CaseStatusKnowledgeTest.document("GUIDE", "Original synthetic guidance"));
    ApiException failure = new ApiException(422, "FIXTURE", "Invalid fixture");
    assertThatThrownBy(() -> UatService.documentMap(docs, failure)).isSameAs(failure);
    assertThat(UatService.documentMap(docs, failure, false)).containsKey("GUIDE");
  }
  @Test void libraryControllerRequiresKnownAuthenticatedIdentityAndAllowsViewerReading() throws Exception {
    setup(false); CaseKnowledgeController controller = new CaseKnowledgeController(service);
    assertThatThrownBy(() -> controller.library(null)).isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.status).isEqualTo(401));
    var auth = UsernamePasswordAuthenticationToken.authenticated("viewer", "unused", List.of());
    assertThat(controller.library(auth).path("items")).hasSize(90);
    verifyNoInteractions(worker);
  }
  @Test void inventoriesAboveOneHundredRemainCurrentAndSelectOnlyScopedGuidance() throws Exception {
    setup(true);
    ObjectNode guide = read(guidanceFile);
    ArrayNode docs = (ArrayNode) guide.path("documents");
    for (int i=0; i<120; i++) docs.add(CaseStatusKnowledgeTest.document("SCALE-GUIDE-" + i, "Synthetic investigation procedure " + i));
    write(guidanceFile, guide);
    ObjectNode library = service.library(actor);
    assertThat(library.path("items")).hasSize(210);
    makeCurrent();
    assertThat(service.library(actor).path("embedding").path("status").asText()).isEqualTo("CURRENT");
    CaseKnowledgeService.Preview preview = service.preview(actor, payload(), "Explain CODSTATUS=991", new LinkedHashSet<>());
    assertThat(preview.totalDocuments()).isEqualTo(210);
    assertThat(preview.version()).isEqualTo(library.path("version").asText());
    verifyNoInteractions(worker);
    when(worker.searchCaseKnowledge(any())).thenReturn(CaseStatusKnowledgeTest.searchResponse("SCALE-GUIDE-119", "FIXTURE-MSGSTATUS-3", "FIXTURE-CODSTATUS-991"));
    ArrayNode selected = service.select(actor, payload(), "Explain CODSTATUS=991", new LinkedHashSet<>());
    assertThat(selected.size()).isLessThan(30);
    assertThat(ids(selected)).contains("SCALE-GUIDE-119", "FIXTURE-CODSTATUS-991", "CASE-KNOWLEDGE-RETRIEVAL");
    assertThat(find(selected, "SCALE-GUIDE-119")).isEqualTo(original(find(library.path("items"), "SCALE-GUIDE-119")));
    verify(worker).searchCaseKnowledge(argThat(request -> request.path("entries").size() == 210));
  }

  @Test void localPreviewExposesStaleIndexWithoutCallingEmbeddingAndReservedIdsFail() throws Exception {
    setup(true);
    CaseKnowledgeService.Preview preview = service.preview(actor, payload(), "Explain payment", new LinkedHashSet<>());
    assertThat(preview.embeddingStatus()).isEqualTo("MISSING");
    verifyNoInteractions(worker);
    ObjectNode guide = read(guidanceFile);
    ((ArrayNode)guide.path("documents")).add(CaseStatusKnowledgeTest.document("CASE-EVIDENCE-SELECTION", "Reserved ID collision"));
    write(guidanceFile, guide);
    assertThatThrownBy(() -> service.library(actor)).isInstanceOf(ApiException.class);
    verifyNoInteractions(worker);
  }

}
