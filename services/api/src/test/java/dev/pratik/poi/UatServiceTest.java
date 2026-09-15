package dev.pratik.poi;

import static dev.pratik.poi.UatTestData.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class UatServiceTest {
  @TempDir Path temporary;
  Path bundles, results;
  UatWorkerClient worker;
  UatService service;
  ObjectNode bundle;
  @BeforeEach void setup() throws Exception {
    bundles = Files.createDirectory(temporary.resolve("bundles")); results = temporary.resolve("answers");
    worker = mock(UatWorkerClient.class); service = new UatService(MAPPER, worker, bundles.toString(), results.toString());
    bundle = bundle(); saveBundle();
    doAnswer(call -> response(call.getArgument(0))).when(worker).answer(any());
  }
  void saveBundle() throws Exception { Files.write(bundles.resolve(ID + ".json"), MAPPER.writeValueAsBytes(bundle)); }
  void error(int status, String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.status).isEqualTo(status); assertThat(e.code).isEqualTo(code);
    });
  }
  long receipts() throws Exception {
    if (!Files.exists(results)) return 0;
    try (var paths = Files.walk(results)) { return paths.filter(p -> p.toString().endsWith(".json")).count(); }
  }
  @Test void disabledByDefaultAndScopedBeforeExposingOrAnswering() throws Exception {
    var disabled = new UatService(MAPPER, worker, "", "");
    assertThat(disabled.list(ANALYST).path("enabled").asBoolean()).isFalse();
    error(503, "UAT_DISABLED", () -> disabled.snapshot(ID, ANALYST));
    assertThat(service.list(OTHER).path("items")).isEmpty();
    error(404, "UAT_SNAPSHOT_NOT_FOUND", () -> service.snapshot(ID, OTHER));
    error(404, "UAT_SNAPSHOT_NOT_FOUND", () -> service.answer(ID, OTHER, question(bundle)));
    error(404, "UAT_SNAPSHOT_NOT_FOUND", () -> service.history(ID, OTHER));
    error(404, "UAT_SNAPSHOT_NOT_FOUND", () -> service.snapshot("../" + ID, ANALYST));
    error(404, "UAT_SNAPSHOT_NOT_FOUND", () -> service.snapshot("missing", ANALYST));
    assertThat(service.snapshot(ID, ANALYST).has("tenantId")).isFalse();
    assertThat(service.list(ANALYST).path("items").get(0).has("documents")).isFalse();
    verifyNoInteractions(worker);
  }
  @Test void requestBoundsDuplicatesUnknownFieldsAndStaleHashFailBeforeModel() throws Exception {
    String hash = bundle.path("evidenceHash").asText();
    for (String body : List.of("{}", "[]", "null", "{\"question\":3,\"evidenceHash\":\"" + hash + "\"}",
        "{\"question\":\"x\",\"question\":\"y\",\"evidenceHash\":\"" + hash + "\"}",
        "{\"question\":\"x\",\"tenantId\":\"silverline\",\"evidenceHash\":\"" + hash + "\"}",
        "{\"question\":\"x\",\"evidenceHash\":\"" + hash + "\"} {}",
        "{\"question\":\"" + "x".repeat(2001) + "\",\"evidenceHash\":\"" + hash + "\"}"))
      error(400, "INVALID_REQUEST", () -> service.answer(ID, ANALYST, body.getBytes(StandardCharsets.UTF_8)));
    error(413, "UAT_REQUEST_TOO_LARGE", () -> service.answer(ID, ANALYST, new byte[16385]));
    error(409, "STALE_UAT_EVIDENCE", () -> service.answer(ID, ANALYST,
        ("{\"question\":\"x\",\"evidenceHash\":\"" + "0".repeat(64) + "\"}").getBytes(StandardCharsets.UTF_8)));
    error(403, "FORBIDDEN", () -> service.answer(ID, new Actor("viewer", "Viewer", "VIEWER", "northstar"), question(bundle)));
    verifyNoInteractions(worker); assertThat(receipts()).isZero();
  }
  @Test void persistsActualWorkerResponseRequestActorAndInputHashWithoutChangingBundle() throws Exception {
    byte[] before = Files.readAllBytes(bundles.resolve(ID + ".json"));
    ObjectNode answer = service.answer(ID, ANALYST, question(bundle));
    assertThat(answer.path("model").path("actualCalls").asInt()).isEqualTo(1);
    assertThat(answer.path("model").path("promptTokens").isNull()).isTrue();
    assertThat(receipts()).isEqualTo(1);
    Path saved; try (var paths = Files.walk(results)) { saved = paths.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow(); }
    var receipt = MAPPER.readTree(saved.toFile());
    assertThat(receipt.path("answer")).isEqualTo(answer);
    assertThat(receipt.path("actor").path("id").asText()).isEqualTo("analyst");
    assertThat(receipt.path("request").path("documents")).isEqualTo(bundle.path("documents"));
    assertThat(receipt.path("inputHash").asText()).isEqualTo(UatService.canonicalHash(receipt.path("request")));
    assertThat(Files.readAllBytes(bundles.resolve(ID + ".json"))).isEqualTo(before);
    assertThat(service.history(ID, ANALYST).path("items").get(0)).isEqualTo(answer);
    String previousHash = bundle.path("evidenceHash").asText();
    bundle.put("title", "A later original test snapshot"); seal(bundle); saveBundle();
    assertThat(service.history(ID, ANALYST).path("items").get(0).path("evidenceHash").asText()).isEqualTo(previousHash);
    verify(worker, times(1)).answer(any());
  }
  @Test void rejectsTamperedBundleAndInvalidSourceShapeWithoutModel() throws Exception {
    bundle.put("title", "Changed without updating evidence hash"); saveBundle();
    error(503, "UAT_BUNDLE_UNAVAILABLE", () -> service.snapshot(ID, ANALYST));
    for (Consumer<ObjectNode> edit : List.<Consumer<ObjectNode>>of(
        b -> b.put("classification", "SYNTHETIC"), b -> b.put("amount", 10.25),
        b -> ((ObjectNode)b.path("documents").get(0)).put("kind", "knowledge"),
        b -> ((ObjectNode)b.path("documents").get(1)).put("id", "E1"),
        b -> ((ObjectNode)b.path("documents").get(0)).put("content", "x".repeat(50000)))) {
      bundle = bundle(); edit.accept(bundle); seal(bundle); saveBundle();
      error(503, "UAT_BUNDLE_UNAVAILABLE", () -> service.answer(ID, ANALYST, question(bundle)));
    }
    Files.writeString(bundles.resolve(ID + ".json"), "{\"snapshotId\":\"x\",\"snapshotId\":\"y\"}");
    error(503, "UAT_BUNDLE_UNAVAILABLE", () -> service.snapshot(ID, ANALYST));
    verifyNoInteractions(worker);
  }
  @Test void rejectsUnboundCitationsIdentityProviderFallbackAndFalseTelemetry() throws Exception {
    for (Consumer<ObjectNode> edit : List.<Consumer<ObjectNode>>of(
        a -> a.put("snapshotId", "someone-else"), a -> a.put("question", "different"),
        a -> a.put("evidenceHash", "0".repeat(64)), a -> a.put("mode", "replay"),
        a -> a.remove("validation"), a -> ((ObjectNode)a.path("model")).put("actualCalls", 0),
        a -> ((ObjectNode)a.path("model")).put("provider", "canned"),
        a -> ((ObjectNode)a.path("claims").get(0)).putArray("evidenceIds").add("invented"),
        a -> ((ObjectNode)a.path("citations").get(0)).put("content", "Changed source text"),
        a -> ((ObjectNode)a.path("retrieval")).putArray("documentIds").add("K1"),
        a -> a.putArray("claims"))) {
      doAnswer(call -> { ObjectNode a = response(call.getArgument(0)); edit.accept(a); return a; }).when(worker).answer(any());
      error(502, "INVALID_UAT_ANSWER", () -> service.answer(ID, ANALYST, question(bundle)));
      assertThat(receipts()).isZero();
    }
  }
  @Test void bundleChangingDuringModelCallCannotPersistStaleAnswer() throws Exception {
    doAnswer(call -> {
      ObjectNode answer = response(call.getArgument(0)); bundle.put("title", "Updated while model runs"); seal(bundle); saveBundle(); return answer;
    }).when(worker).answer(any());
    error(409, "STALE_UAT_EVIDENCE", () -> service.answer(ID, ANALYST, question(bundle)));
    assertThat(receipts()).isZero();
  }
  @Test void providerFailureHasNoCannedAnswerOrHistory() throws Exception {
    doThrow(new ApiException(503, "UAT_MODEL_UNAVAILABLE", "Test provider unavailable")).when(worker).answer(any());
    error(503, "UAT_MODEL_UNAVAILABLE", () -> service.answer(ID, ANALYST, question(bundle)));
    assertThat(service.history(ID, ANALYST).path("items")).isEmpty(); assertThat(receipts()).isZero();
  }
  @Test void storageFailureIsReportedBeforeModelAndAlsoAfterModel() throws Exception {
    Files.writeString(results, "Not a directory");
    error(503, "UAT_STORAGE_UNAVAILABLE", () -> service.answer(ID, ANALYST, question(bundle)));
    verifyNoInteractions(worker); Files.delete(results);
    doAnswer(call -> {
      Path leaf = results.resolve("northstar").resolve(ID); Files.delete(leaf); Files.writeString(leaf, "Storage became unavailable");
      return response(call.getArgument(0));
    }).when(worker).answer(any());
    error(503, "UAT_STORAGE_UNAVAILABLE", () -> service.answer(ID, ANALYST, question(bundle)));
    assertThat(receipts()).isZero();
  }
  @Test void canonicalHashMatchesIndependentUtf8SortedJsonVector() throws Exception {
    var object = MAPPER.readTree("{\"z\":\"₹ café\",\"a\":{\"b\":2,\"a\":1},\"list\":[2,1]}");
    assertThat(UatService.canonicalHash(object)).isEqualTo(InvestigationService.hash("{\"a\":{\"a\":1,\"b\":2},\"list\":[2,1],\"z\":\"₹ café\"}"));
  }
  @Test void citationLocatorBoundsMatchWorkerWithoutTruncatingSources() throws Exception {
    var source = (ObjectNode) bundle.path("documents").get(0).path("source");
    source.put("sheet", "s".repeat(200)).put("range", "r".repeat(200)).put("locator", "l".repeat(1000));
    seal(bundle); saveBundle();
    assertThat(service.snapshot(ID, ANALYST).path("documents").get(0).path("source")).isEqualTo(source);
    for (String key : List.of("sheet", "range", "locator")) {
      String previous = source.path(key).asText(); source.put(key, previous + "x"); seal(bundle); saveBundle();
      error(503, "UAT_BUNDLE_UNAVAILABLE", () -> service.snapshot(ID, ANALYST)); source.put(key, previous);
    }
    verifyNoInteractions(worker);
  }
  @Test void newAnswerPreservesExactOrderedClaimJoinAndCompositionInHistory() throws Exception {
    doAnswer(call -> {
      ObjectNode answer = response(call.getArgument(0));
      ((com.fasterxml.jackson.databind.node.ArrayNode)answer.path("claims")).addObject()
          .put("text", "The supplied observation leaves the meaning unknown.").putArray("evidenceIds").add("E1");
      answer.put("answer", "The fictional observation records X.\n\nThe supplied observation leaves the meaning unknown.");
      return answer;
    }).when(worker).answer(any());
    ObjectNode answer = service.answer(ID, ANALYST, question(bundle));
    assertThat(answer.path("answer").asText()).isEqualTo("The fictional observation records X.\n\nThe supplied observation leaves the meaning unknown.");
    assertThat(answer.path("answerComposition").asText()).isEqualTo("joined-model-claims");
    assertThat(service.history(ID, ANALYST).path("items").get(0)).isEqualTo(answer);
    assertThat(receipts()).isEqualTo(1);
  }
  @Test void newAnswersRejectMissingOrFalseCompositionAndAdditionalUncitedSummary() throws Exception {
    for (Consumer<ObjectNode> edit : List.<Consumer<ObjectNode>>of(
        a -> a.remove("answerComposition"), a -> a.putNull("answerComposition"),
        a -> a.put("answerComposition", "free-summary"),
        a -> a.put("answer", a.path("answer").asText() + "\n\nAn extra uncited conclusion."),
        a -> a.put("answer", " " + a.path("answer").asText()),
        a -> ((ObjectNode)a.path("claims").get(0)).put("text", "A changed claim without a matching answer."))) {
      doAnswer(call -> { ObjectNode a = response(call.getArgument(0)); edit.accept(a); return a; }).when(worker).answer(any());
      error(502, "INVALID_UAT_ANSWER", () -> service.answer(ID, ANALYST, question(bundle)));
      assertThat(receipts()).isZero();
    }
  }
  @Test void historicalAnswersWithoutCompositionRemainUnchangedButCannotBecomeNewAnswers() throws Exception {
    service.answer(ID, ANALYST, question(bundle));
    Path saved; try (var paths = Files.walk(results)) { saved = paths.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow(); }
    var receipt = (ObjectNode)MAPPER.readTree(saved.toFile());
    var historical = (ObjectNode)receipt.path("answer");
    historical.remove("answerComposition");
    historical.put("answer", "An original historical free summary retained exactly for review.");
    Files.write(saved, MAPPER.writeValueAsBytes(receipt));
    byte[] before = Files.readAllBytes(saved);
    assertThat(service.history(ID, ANALYST).path("items").get(0)).isEqualTo(historical);
    assertThat(Files.readAllBytes(saved)).isEqualTo(before);
    error(502, "INVALID_UAT_ANSWER", () -> UatService.validateAnswer(historical, (ObjectNode)receipt.path("request")));
    historical.put("answerComposition", "joined-model-claims");
    Files.write(saved, MAPPER.writeValueAsBytes(receipt));
    error(503, "UAT_STORAGE_UNAVAILABLE", () -> service.history(ID, ANALYST));
  }
}
