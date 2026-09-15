package dev.pratik.poi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payment-cases/{caseId}")
public class CaseReportController {
  private final CaseReportService service;
  private final PaymentDiscoveryService cases;
  public CaseReportController(CaseReportService service, PaymentDiscoveryService cases) { this.service = service; this.cases = cases; }

  @PostMapping(value="/report-preview", consumes="application/json")
  public ObjectNode preview(Authentication authentication, @PathVariable String caseId, HttpServletRequest request,
      @RequestHeader(value="Idempotency-Key", required=false) String key) throws IOException {
    Actor actor = Actor.from(authentication); caseId = CaseRouteIdentity.canonical(cases, actor, caseId); cases.caseDetail(actor, caseId);
    return service.preview(actor, caseId, body(request), key);
  }

  @PostMapping(value="/report.pdf", consumes="application/json", produces="application/pdf")
  public ResponseEntity<byte[]> download(Authentication authentication, @PathVariable String caseId, HttpServletRequest request) throws IOException {
    Actor actor = Actor.from(authentication); caseId = CaseRouteIdentity.canonical(cases, actor, caseId); cases.caseDetail(actor, caseId);
    ObjectNode report = service.frozen(actor, caseId, body(request));
    byte[] bytes = service.render(report);
    String caseNumber = report.path("case").path("caseNumber").asText();
    String name = caseNumber.matches("[0-9]{13}") ? "payment-case-" + caseNumber + "-report.pdf"
        : "payment-investigation-" + report.path("reportId").asText().replaceAll("[^A-Za-z0-9-]", "") + ".pdf";
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).contentLength(bytes.length)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header("X-Content-Type-Options", "nosniff").body(bytes);
  }
  private byte[] body(HttpServletRequest request) throws IOException { return request.getInputStream().readNBytes(CaseReportService.REQUEST_LIMIT + 1); }
}
