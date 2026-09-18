package dev.pratik.poi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payment-case-work")
public class CaseWorkQueueController {
  private final CaseWorkQueueService service;
  public CaseWorkQueueController(CaseWorkQueueService service) { this.service=service; }
  @GetMapping public ObjectNode list(Authentication auth,
      @RequestParam(defaultValue="ALL") String view, @RequestParam(defaultValue="0") int offset,
      @RequestParam(defaultValue="10") int limit) {
    return service.list(Actor.from(auth),view,offset,limit);
  }
}
