package dev.pratik.poi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payment-cases/{caseId}")
public class CaseInvestigationController {
  private final CaseInvestigationService service;
  private final PaymentDiscoveryService cases;
  public CaseInvestigationController(CaseInvestigationService service,PaymentDiscoveryService cases){this.service=service;this.cases=cases;}
  @GetMapping("/workbench") public ObjectNode workbench(Authentication auth,@PathVariable String caseId){Actor actor=Actor.from(auth);return service.workbench(actor,CaseRouteIdentity.canonical(cases,actor,caseId));}
  @GetMapping("/evidence/{evidenceId}/context") public ObjectNode context(Authentication auth,@PathVariable String caseId,@PathVariable String evidenceId){Actor actor=Actor.from(auth);return service.context(actor,CaseRouteIdentity.canonical(cases,actor,caseId),evidenceId);}
  @GetMapping("/investigations/{id}") public ObjectNode detail(Authentication auth,@PathVariable String caseId,@PathVariable String id){Actor actor=Actor.from(auth);return service.detail(actor,CaseRouteIdentity.canonical(cases,actor,caseId),id);}
  @PostMapping(value="/investigations",consumes="application/json")
  public ResponseEntity<ObjectNode> start(Authentication auth,@PathVariable String caseId,HttpServletRequest request,
      @RequestHeader(value="Idempotency-Key",required=false)String key)throws IOException {
    Actor actor=Actor.from(auth);actor.requireWriter();caseId=CaseRouteIdentity.canonical(cases,actor,caseId);cases.caseDetail(actor,caseId);
    byte[] body=request.getInputStream().readNBytes(16385);
    return ResponseEntity.accepted().body(service.start(actor,caseId,body,key));
  }
}
