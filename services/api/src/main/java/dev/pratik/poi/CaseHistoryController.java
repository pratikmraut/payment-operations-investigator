package dev.pratik.poi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payment-cases/{caseId}")
public class CaseHistoryController {
  private final CaseHistoryService history;
  private final PaymentDiscoveryService cases;
  public CaseHistoryController(CaseHistoryService history,PaymentDiscoveryService cases){this.history=history;this.cases=cases;}
  @GetMapping("/investigations") public ObjectNode investigations(Authentication auth,@PathVariable String caseId,HttpServletRequest request){Actor actor=Actor.from(auth);return history.investigations(actor,CaseRouteIdentity.canonical(cases,actor,caseId),request.getParameterMap());}
  @GetMapping("/investigations/{id}/summary") public ObjectNode investigationSummary(Authentication auth,@PathVariable String caseId,@PathVariable String id){Actor actor=Actor.from(auth);return history.investigationSummary(actor,CaseRouteIdentity.canonical(cases,actor,caseId),id);}
  @GetMapping("/activity") public ObjectNode activity(Authentication auth,@PathVariable String caseId,HttpServletRequest request){Actor actor=Actor.from(auth);return history.activity(actor,CaseRouteIdentity.canonical(cases,actor,caseId),request.getParameterMap());}
}
