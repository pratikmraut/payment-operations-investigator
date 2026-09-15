package dev.pratik.poi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/case-knowledge")
public final class CaseKnowledgeController {
  private final CaseKnowledgeService service;
  public CaseKnowledgeController(CaseKnowledgeService service) { this.service = service; }
  @GetMapping public ObjectNode library(Authentication authentication) { return service.library(Actor.from(authentication)); }
}
