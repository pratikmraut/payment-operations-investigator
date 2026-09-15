package dev.pratik.poi;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ObpmController {
  public static final int MAX_BYTES = 131072;
  private final ObpmImportService service;
  private final ObjectMapper mapper;
  public ObpmController(ObpmImportService service, ObjectMapper mapper) { this.service = service; this.mapper = mapper; }

  @PostMapping(value = "/obpm/imports", consumes = "application/json")
  public ObjectNode ingest(HttpServletRequest request, Authentication auth) throws IOException {
    Actor actor = Actor.from(auth); actor.requireWriter();
    byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
    if (body.length > MAX_BYTES) throw new ApiException(413, "IMPORT_TOO_LARGE", "OBPM snapshots are limited to 128 KiB.");
    try (JsonParser parser = mapper.getFactory().createParser(body)) {
      parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(parser);
      if (parser.nextToken() != null) throw new ApiException(400, "INVALID_REQUEST", "Use one JSON snapshot.");
      return service.ingest(node, actor);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new ApiException(400, "INVALID_REQUEST", "Use valid JSON without duplicate fields.");
    }
  }
  @GetMapping("/obpm/imports")
  public Map<String, Object> imports(Authentication auth) { return Map.of("items", service.receipts(Actor.from(auth).tenantId())); }
  @GetMapping("/obpm/samples")
  public Map<String, Object> samples(Authentication auth) { Actor.from(auth); return Map.of("items", service.samples()); }
  @GetMapping("/cases/{id}/evidence-versions")
  public Map<String, Object> versions(@PathVariable String id, Authentication auth) {
    return Map.of("items", service.versions(id, Actor.from(auth).tenantId(), false));
  }
}
