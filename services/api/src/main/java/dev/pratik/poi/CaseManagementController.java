package dev.pratik.poi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payment-cases/{caseId}")
public class CaseManagementController {
  private final CaseManagementService service;
  private final PaymentDiscoveryService cases;
  public CaseManagementController(CaseManagementService service,PaymentDiscoveryService cases) { this.service=service; this.cases=cases; }
  @GetMapping("/management") public ObjectNode detail(Authentication auth,@PathVariable String caseId) { Actor actor=Actor.from(auth);return CaseManagementService.publicView(service.detail(actor,CaseRouteIdentity.canonical(cases,actor,caseId))); }
  @PostMapping(value="/management",consumes="application/json")
  public ObjectNode manage(Authentication auth,@PathVariable String caseId,HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false)String key)throws IOException {
    Actor actor=Actor.from(auth);caseId=writer(actor,caseId);return CaseManagementService.publicView(service.manage(actor,caseId,body(request),key));
  }
  @PostMapping(value="/notes",consumes="application/json")
  public ObjectNode note(Authentication auth,@PathVariable String caseId,HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false)String key)throws IOException {
    Actor actor=Actor.from(auth);caseId=writer(actor,caseId);return CaseManagementService.publicView(service.addNote(actor,caseId,body(request),key));
  }
  @PostMapping(value="/workflow",consumes="application/json")
  public ObjectNode workflow(Authentication auth,@PathVariable String caseId,HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false)String key)throws IOException {
    Actor actor=Actor.from(auth);caseId=writer(actor,caseId);return CaseManagementService.publicView(service.transition(actor,caseId,body(request),key));
  }
  @PostMapping(value="/evidence-requests",consumes="application/json")
  public ObjectNode requestEvidence(Authentication auth,@PathVariable String caseId,HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false)String key)throws IOException {
    Actor actor=Actor.from(auth);caseId=writer(actor,caseId);return CaseManagementService.publicView(service.requestEvidence(actor,caseId,body(request),key));
  }
  @PostMapping(value="/evidence-requests/{requestId}",consumes="application/json")
  public ObjectNode updateRequest(Authentication auth,@PathVariable String caseId,@PathVariable String requestId,HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false)String key)throws IOException {
    Actor actor=Actor.from(auth);caseId=writer(actor,caseId);return CaseManagementService.publicView(service.updateEvidenceRequest(actor,caseId,requestId,body(request),key));
  }
  @PostMapping(value="/reviewer-conclusions",consumes="application/json")
  public ObjectNode conclude(Authentication auth,@PathVariable String caseId,HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false)String key)throws IOException {
    Actor actor=Actor.from(auth);caseId=writer(actor,caseId);actor.requireReviewer();return CaseManagementService.publicView(service.conclude(actor,caseId,body(request),key));
  }
  private String writer(Actor actor,String caseId) { actor.requireWriter();caseId=CaseRouteIdentity.canonical(cases,actor,caseId);cases.caseRecord(actor,caseId);return caseId; }
  private byte[] body(HttpServletRequest request)throws IOException {
    byte[] bytes=request.getInputStream().readNBytes(CaseManagementService.MAX_BYTES+1);
    if(bytes.length>CaseManagementService.MAX_BYTES)throw new ApiException(413,"CASE_MANAGEMENT_TOO_LARGE","Case management requests are limited to 32 KiB.");return bytes;
  }
}
