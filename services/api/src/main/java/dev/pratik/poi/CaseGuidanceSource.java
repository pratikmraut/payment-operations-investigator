package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One source for the visible library and frozen case guidance. Reads never call a model. */
@Component
public final class CaseGuidanceSource {
  private static final int MAX_GUIDANCE_BYTES = 256 * 1024;
  private static final String SCHEMA = "fcr-case-evidence-v1";
  private final ObjectMapper mapper;
  private final String guidanceFile;
  public CaseGuidanceSource(ObjectMapper mapper,
      @Value("${poi.case-investigation.guidance-file:}") String guidanceFile) {
    this.mapper = mapper;
    this.guidanceFile = guidanceFile == null ? "" : guidanceFile;
  }
  ArrayNode documents(Actor actor, Set<String> warnings) {
    ArrayNode result = genericGuidance();
    result.addAll(privateGuidance(actor, warnings));
    UatService.documentMap(result, tooLarge(), false);
    return result;
  }
  ArrayNode genericGuidance() {
    ArrayNode result = mapper.createArrayNode();
    String source = "original-case-investigation-guidance-v1";
    result.add(document("GUIDE-SOURCE-COVERAGE", "knowledge", "Source completion and outcome limits",
        "Use supplied rows and the saved coverage report as bounded evidence. Uploaded or manually entered rows do not establish cursor completion. API COMPLETE coverage records a validated wrapper report, not independent verification of the source. A header-only or empty group does not establish that no event occurred. Four separately acquired result sets do not establish an atomic snapshot. Distinguish observations, uncertainty and further evidence needed; never treat a row count as a payment outcome.", source, "Original generic source-coverage guidance"));
    result.add(document("GUIDE-SOURCE-TIME", "knowledge", "Source-local time and observation ordering",
        "Source-local dates retain their original text and have no invented UTC offset. A timeline is an ordering of timestamp observations only; fields can have different source meanings. Invalid or missing timestamps prevent a reliable sort, ties do not establish order, and an elapsed interval alone does not establish a queue wait or SLA breach. Confirm timestamp semantics and source timezone before drawing timing conclusions.", source, "Original generic timestamp guidance"));
    result.add(document("GUIDE-RAW-STATUS", "knowledge", "Raw status codes require documented mappings",
        "Preserve raw status codes and their row citations. A numeric code has no verified meaning without applicable source documentation for that field and release. Private implementation notes do not verify the installed release or which source-code path actually executed. Do not invent labels for unmapped codes, infer beneficiary credit, settlement or final success from a UTR alone, or treat missing evidence as proof of failure. Operator notes and case reasons are reported context, not verified outcomes. This workbench investigates evidence and does not execute payment operations.", source, "Original generic status and interpretation guidance"));
    return result;
  }

  ArrayNode privateGuidance(Actor actor, Set<String> warnings) {
    ArrayNode empty = mapper.createArrayNode();
    if (guidanceFile.isBlank()) {
      warnings.add("No private general tenant guidance is configured. Generic interpretation caveats remain available; a separately configured status catalog is checked independently.");
      return empty;
    }
    ObjectNode config;
    try (var input = Files.newInputStream(Path.of(guidanceFile))) {
      byte[] bytes = input.readNBytes(MAX_GUIDANCE_BYTES + 1);
      if (bytes.length > MAX_GUIDANCE_BYTES) throw guidanceInvalid();
      config = UatService.parseObject(mapper, bytes, guidanceInvalid());
    } catch (IOException | java.nio.file.InvalidPathException failure) { throw guidanceInvalid(); }
    if (!names(config).equals(Set.of("schemaVersion", "tenantId", "evidenceSchema", "documents"))
        || !"fcr-case-guidance-v1".equals(config.path("schemaVersion").asText())
        || !SCHEMA.equals(config.path("evidenceSchema").asText())
        || !config.path("tenantId").isTextual() || !config.path("tenantId").asText().matches("[A-Za-z0-9_-]{1,100}")
        || !config.path("documents").isArray()) throw guidanceInvalid();
    if (!actor.tenantId().equals(config.path("tenantId").asText())) {
      warnings.add("The general private guidance does not apply to this tenant. Generic interpretation caveats remain available; a separately configured status catalog is checked independently.");
      return empty;
    }
    JsonNode configured = config.path("documents");
    if (configured.size() > 100) throw tooLarge();
    for (JsonNode document : configured) {
      if (!document.isObject() || !"knowledge".equals(document.path("kind").asText())
          || !names(document).equals(Set.of("id", "kind", "title", "content", "source"))
          || !document.path("source").isObject()
          || !Set.of("file", "sheet", "range", "locator").containsAll(names(document.path("source")))
          || document.path("id").asText().matches("(?:PAYMENT|HOST|HISTORY|STATUS)-(?:ROW-[0-9]+|COVERAGE)")
          || document.path("id").asText().equals("CASE-CONTEXT")) throw guidanceInvalid();
      empty.add(document.deepCopy());
    }
    return empty;
  }

  private ObjectNode document(String id, String kind, String title, String content, String file, String locator) {
    ObjectNode result = mapper.createObjectNode().put("id", id).put("kind", kind).put("title", title).put("content", content);
    result.putObject("source").put("file", file).put("locator", locator);
    return result;
  }
  private static Set<String> names(JsonNode object) { Set<String> result = new HashSet<>(); object.fieldNames().forEachRemaining(result::add); return result; }
  private static ApiException guidanceInvalid() { return new ApiException(503, "CASE_GUIDANCE_UNAVAILABLE", "The configured private guidance file could not be read or validated. Correct the tenant guidance contract before investigating."); }
  private static ApiException tooLarge() { return new ApiException(422, "CASE_INVESTIGATION_EVIDENCE_LIMIT", "Evidence and guidance must fit 100 source documents and 50,000 source characters with valid unique citations. Narrow the selected evidence or guidance; no source rows were truncated."); }
}
