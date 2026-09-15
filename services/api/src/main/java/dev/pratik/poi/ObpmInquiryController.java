package dev.pratik.poi;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/obpm")
public class ObpmInquiryController {
  private final ObpmInquiryClient client;
  private final ObpmImportService imports;
  private final ObjectMapper mapper;
  public ObpmInquiryController(ObpmInquiryClient client, ObpmImportService imports, ObjectMapper mapper) {
    this.client = client; this.imports = imports; this.mapper = mapper;
  }
  @GetMapping("/inquiry")
  public Map<String, Object> configuration(Authentication auth) { Actor.from(auth); return client.configuration(); }

  @PostMapping(value = "/inquiries", consumes = "application/json")
  public ObjectNode inquire(HttpServletRequest request, Authentication auth) throws IOException {
    Actor actor = Actor.from(auth); actor.requireWriter();
    byte[] bytes = request.getInputStream().readNBytes(1025);
    if (bytes.length > 1024) throw new ApiException(413, "INQUIRY_REQUEST_TOO_LARGE", "Inquiry requests are limited to 1 KiB.");
    String reference;
    try (JsonParser parser = mapper.getFactory().createParser(bytes)) {
      parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      JsonNode input = mapper.readTree(parser);
      if (parser.nextToken() != null || input == null || !input.isObject() || input.size() != 1
          || !input.path("paymentReference").isTextual()) throw invalidRequest();
      reference = input.path("paymentReference").textValue();
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw invalidRequest(); }
    ObpmInquiryClient.validateReference(reference);
    ObjectNode evidence = client.fetch(reference);
    return imports.ingest(evidence, actor);
  }
  private static ApiException invalidRequest() {
    return new ApiException(400, "INVALID_REQUEST", "Provide one JSON object containing only paymentReference, without duplicate fields.");
  }
}
