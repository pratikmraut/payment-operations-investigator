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
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic reference definitions and vectors; never loads private workbooks or calls a model. */
class CaseStatusKnowledgeTest {
  static final ObjectMapper MAPPER = new ObjectMapper();
  final Actor analyst = new Actor("fixture", "Fixture", "ANALYST", "northstar");
  final UatWorkerClient worker = mock(UatWorkerClient.class);
  @TempDir Path temporary;

  static ObjectNode document(String id, String content) {
    ObjectNode doc = MAPPER.createObjectNode().put("id", id).put("kind", "knowledge")
        .put("title", "Original synthetic reference " + id).put("content", content);
    doc.putObject("source").put("file", "synthetic-status-reference.xlsx").put("sheet", "Fixture")
        .put("range", "A1:B2").put("locator", "Original synthetic testing source");
    return doc;
  }
  static ObjectNode catalog() {
    ObjectNode root = MAPPER.createObjectNode().put("schemaVersion", "fcr-status-knowledge-v1")
        .put("tenantId", "northstar").put("evidenceSchema", "fcr-case-evidence-v1")
        .put("table", "PM_NEFT_TXN_LOG").put("sourceSha256", "a".repeat(64));
    root.putObject("embedding").put("model", "qwen3-embedding:0.6b").put("digest", "b".repeat(64)).put("dimensions", 1024);
    root.set("overview", document("FIXTURE-OVERVIEW", "Synthetic PM_NEFT_TXN_LOG reference; not a payment outcome."));
    ArrayNode entries = root.putArray("entries");
    for (String[] value : new String[][]{{"CODSTATUS", "991"}, {"ACCTSTATUS", "991"}, {"MSGSTATUS", "0"}, {"CODSTATUS", "992"}, {"MSGSTATUS", "3"}}) {
      String id = "FIXTURE-" + value[0] + "-" + value[1];
      ObjectNode entry = entries.addObject().put("field", value[0]).put("code", value[1]).put("label", "Synthetic label " + id);
      entry.set("document", document(id, "PM_NEFT_TXN_LOG." + value[0] + "=" + value[1] + ": synthetic test definition only."));
      ArrayNode vector = entry.putArray("vector"); vector.add(1.0); for (int i=1; i<1024; i++) vector.add(0.0);
    }
    return root;
  }
  static ObjectNode searchResponse(String... ids) {
    ObjectNode result = MAPPER.createObjectNode().put("model", "qwen3-embedding:0.6b")
        .put("digest", "b".repeat(64)).put("processor", "GPU");
    ArrayNode matches = result.putArray("matches");
    for (String id : ids) matches.addObject().put("id", id).put("score", .5);
    return result;
  }
  Path write(ObjectNode root) throws Exception { Path file=temporary.resolve("catalog.json"); Files.write(file,MAPPER.writeValueAsBytes(root)); return file; }
  CaseStatusKnowledge selector(ObjectNode root) throws Exception { return new CaseStatusKnowledge(MAPPER,write(root).toString(),worker); }
  ObjectNode payload() {
    ObjectNode payload = MAPPER.createObjectNode().put("schemaVersion", "fcr-case-evidence-v1");
    ObjectNode sections = payload.putObject("sections");
    sections.putObject("PAYMENT").putArray("rows").addObject().put("SOURCE_TABLE", "PM_NEFT_TXN_LOG")
        .put("CODSTATUS", "991").put("ACCTSTATUS", "").putNull("MSGSTATUS");
    sections.putObject("HOST").putArray("rows").addObject().put("SOURCE_TABLE", "PM_TXN_LOG").put("ACCTSTATUS", "991");
    return payload;
  }
  List<String> ids(ArrayNode docs) { List<String> ids=new ArrayList<>(); docs.forEach(doc->ids.add(doc.path("id").asText()));return ids; }
  void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, String code) {
    assertThatThrownBy(operation).isInstanceOfSatisfying(ApiException.class, error->assertThat(error.code).isEqualTo(code));
  }

  @Test void readOnlySelectionUsesExactPaymentFieldAndDistinguishesNullBlankAndZero() throws Exception {
    ObjectNode payload=payload(), before=payload.deepCopy();
    CaseStatusKnowledge selector=selector(catalog());
    assertThat(ids(selector.select(analyst,payload,null,new LinkedHashSet<>())))
        .containsExactly("FIXTURE-OVERVIEW","FIXTURE-CODSTATUS-991");
    assertThat(payload).isEqualTo(before);
    ((ObjectNode)payload.path("sections").path("PAYMENT").path("rows").get(0)).put("MSGSTATUS","0");
    assertThat(ids(selector.select(analyst,payload,null,new LinkedHashSet<>())))
        .containsExactly("FIXTURE-OVERVIEW","FIXTURE-CODSTATUS-991","FIXTURE-MSGSTATUS-0");
    verifyNoInteractions(worker);
  }
  @Test void exactExplicitAndSemanticSelectionsUnionWithoutChangingDefinitions() throws Exception {
    ObjectNode root=catalog();
    when(worker.searchCaseKnowledge(any())).thenReturn(searchResponse("FIXTURE-CODSTATUS-991","FIXTURE-MSGSTATUS-3"));
    String question="Explain PM_NEFT_TXN_LOG.CODSTATUS=992 and ACCTSTATUS: 991.";
    ArrayNode result=selector(root).select(analyst,payload(),question,new LinkedHashSet<>());
    assertThat(ids(result)).containsExactly("FIXTURE-OVERVIEW","FIXTURE-CODSTATUS-991","FIXTURE-CODSTATUS-992","FIXTURE-ACCTSTATUS-991","FIXTURE-MSGSTATUS-3","FCR-ENUM-RETRIEVAL");
    assertThat(result.get(1)).isEqualTo(root.path("entries").get(0).path("document"));
    JsonNode receipt=MAPPER.readTree(result.get(result.size()-1).path("content").asText());
    assertThat(receipt.path("exactEvidenceDocumentIds").get(0).asText()).isEqualTo("FIXTURE-CODSTATUS-991");
    assertThat(receipt.path("embeddingDigest").asText()).isEqualTo("b".repeat(64));
    assertThat(receipt.path("semanticMatches")).hasSize(2);
    var captured=org.mockito.ArgumentCaptor.forClass(ObjectNode.class); verify(worker).searchCaseKnowledge(captured.capture());
    ObjectNode request=captured.getValue();
    assertThat(request.path("question").asText()).isEqualTo(question);
    assertThat(request.path("limit").asInt()).isEqualTo(3);
    assertThat(request.path("entries")).hasSize(5);
    assertThat(request.toString()).doesNotContain("Synthetic label", "SOURCE_TABLE", "sourceSha256", "Fixture source");
  }
  @Test void otherTablesUnknownValuesAndUnconfiguredTenantsCannotBorrowDefinitions() throws Exception {
    when(worker.searchCaseKnowledge(any())).thenReturn(searchResponse());
    ObjectNode payload=payload();
    ((ObjectNode)payload.path("sections").path("PAYMENT").path("rows").get(0)).put("CODSTATUS","0991").put("N10_STATUS","991");
    Set<String> warnings=new LinkedHashSet<>();CaseStatusKnowledge selector=selector(catalog());
    assertThat(ids(selector.select(analyst,payload,"PM_TXN_LOG.CODSTATUS=992 and N10_STATUS=991",warnings))).containsExactly("FIXTURE-OVERVIEW","FCR-ENUM-RETRIEVAL");
    assertThat(warnings).anyMatch(value->value.contains("No exact workbook definition"));
    clearInvocations(worker);
    assertThat(selector.select(new Actor("other","Other","ANALYST","silverline"),payload,"CODSTATUS=991",warnings)).isEmpty();
    payload.put("schemaVersion","other-schema");
    assertThat(selector.select(analyst,payload,"CODSTATUS=991",warnings)).isEmpty();
    verifyNoInteractions(worker);
  }
  @Test void disabledCatalogIsBackwardCompatibleAndNeverSearches() {
    assertThat(new CaseStatusKnowledge(MAPPER,"",worker).select(analyst,payload(),"Any question",new LinkedHashSet<>())).isEmpty();
    verifyNoInteractions(worker);
  }
  @Test void rejectsForgedSemanticIdsMetadataDuplicateAndInvalidScores() throws Exception {
    CaseStatusKnowledge selector=selector(catalog());
    List<ObjectNode> invalidResponses=new ArrayList<>(List.of(searchResponse("UNKNOWN"),searchResponse("FIXTURE-CODSTATUS-991","FIXTURE-CODSTATUS-991"),
        searchResponse("FIXTURE-CODSTATUS-991","FIXTURE-CODSTATUS-992","FIXTURE-MSGSTATUS-0","FIXTURE-MSGSTATUS-3")));
    invalidResponses.add(searchResponse().put("digest","c".repeat(64)));
    invalidResponses.add(searchResponse().put("processor","CPU"));
    invalidResponses.add(searchResponse().put("model","other-model"));
    ObjectNode nonnumeric=searchResponse("FIXTURE-CODSTATUS-991");((ObjectNode)nonnumeric.path("matches").get(0)).put("score","0.5");invalidResponses.add(nonnumeric);
    ObjectNode extra=searchResponse();extra.put("documents","injected");invalidResponses.add(extra);
    for(ObjectNode response:invalidResponses) {
      when(worker.searchCaseKnowledge(any())).thenReturn(response);
      invalid(()->selector.select(analyst,payload(),"Explain the fields",new LinkedHashSet<>()),"INVALID_CASE_KNOWLEDGE_SEARCH");
    }
  }
  @Test void rejectsMalformedCatalogsWithoutCallingWorker() throws Exception {
    List<Consumer<ObjectNode>> mutations=List.of(
        value->value.put("table","PM_TXN_LOG"),value->value.put("sourceSha256","bad"),value->value.put("schemaVersion","bad"),
        value->((ObjectNode)value.path("embedding")).put("dimensions",3),
        value->((ObjectNode)value.path("entries").get(0)).put("field","N10_STATUS"),
        value->((ObjectNode)value.path("entries").get(0)).put("code"," 991"),
        value->((ArrayNode)value.path("entries")).add(value.path("entries").get(0).deepCopy()),
        value->((ObjectNode)value.path("entries").get(1).path("document")).put("id","FIXTURE-CODSTATUS-991"),
        value->((ObjectNode)value.path("entries").get(0).path("document")).put("kind","evidence"),
        value->((ObjectNode)value.path("entries").get(0).path("document")).put("id","PAYMENT-ROW-1"),
        value->((ArrayNode)value.path("entries").get(0).path("vector")).remove(0),
        value->((ArrayNode)value.path("entries").get(0).path("vector")).set(0,MAPPER.getNodeFactory().numberNode(0)),
        value->((ArrayNode)value.path("entries").get(0).path("vector")).set(0,MAPPER.getNodeFactory().numberNode(2)));
    for(Consumer<ObjectNode> mutate:mutations) {
      ObjectNode value=catalog();mutate.accept(value);CaseStatusKnowledge selector=selector(value);
      invalid(()->selector.select(analyst,payload(),null,new LinkedHashSet<>()),"CASE_STATUS_KNOWLEDGE_UNAVAILABLE");
    }
    Path file=write(catalog());Files.writeString(file,"x".repeat(CaseStatusKnowledge.MAX_BYTES+1));
    invalid(()->new CaseStatusKnowledge(MAPPER,file.toString(),worker).select(analyst,payload(),null,new LinkedHashSet<>()),"CASE_STATUS_KNOWLEDGE_UNAVAILABLE");
    verifyNoInteractions(worker);
  }
  @Test void projectionPreservesEvidenceAndFailsVisiblyRatherThanDroppingOversizedSelectedSources() throws Exception {
    CaseEvidenceProjectionTest fixture=new CaseEvidenceProjectionTest();
    ObjectNode root=catalog(), snapshot=fixture.snapshot();
    CaseEvidenceProjection projection=new CaseEvidenceProjection(MAPPER,"",selector(root));
    ObjectNode result=projection.project(analyst,fixture.item(),snapshot), before=snapshot.deepCopy();
    assertThat(result.path("documents").findValuesAsText("id")).contains("FIXTURE-CODSTATUS-991");
    assertThat(snapshot).isEqualTo(before);
    ((ObjectNode)root.path("entries").get(0).path("document")).put("content","large ".repeat(8000));
    CaseEvidenceProjection oversized=new CaseEvidenceProjection(MAPPER,"",selector(root));
    invalid(()->oversized.project(analyst,fixture.item(),snapshot),"CASE_INVESTIGATION_EVIDENCE_LIMIT");
    verifyNoInteractions(worker);
  }
}
