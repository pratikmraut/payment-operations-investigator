package dev.pratik.poi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EvidenceLibraryController {
  private final EvidenceLibraryService service;
  public EvidenceLibraryController(EvidenceLibraryService service) { this.service = service; }
  @GetMapping("/api/evidences")
  public ObjectNode index(Authentication auth, HttpServletRequest request) {
    return service.index(Actor.from(auth), request.getParameterMap());
  }
}
