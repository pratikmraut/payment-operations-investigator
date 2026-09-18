package dev.pratik.poi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;
import org.springframework.web.util.WebUtils;

@RestController
@RequestMapping("/api/payment-cases/{caseId}/evidence")
public class CaseEvidenceController {
  private final CaseEvidenceService service;
  private final PaymentDiscoveryService cases;
  public CaseEvidenceController(CaseEvidenceService service,PaymentDiscoveryService cases){this.service=service;this.cases=cases;}
  @GetMapping("/config") public ObjectNode config(Authentication auth,@PathVariable String caseId){Actor actor=Actor.from(auth);return service.config(actor,CaseRouteIdentity.canonical(cases,actor,caseId));}
  @GetMapping public ObjectNode list(Authentication auth,@PathVariable String caseId,HttpServletRequest request){Actor actor=Actor.from(auth);return service.history(actor,CaseRouteIdentity.canonical(cases,actor,caseId),request.getParameterMap());}
  @GetMapping("/{snapshotId}/summary") public ObjectNode summary(Authentication auth,@PathVariable String caseId,@PathVariable String snapshotId){Actor actor=Actor.from(auth);return service.summary(actor,CaseRouteIdentity.canonical(cases,actor,caseId),snapshotId);}
  @GetMapping("/{snapshotId}") public ObjectNode detail(Authentication auth,@PathVariable String caseId,@PathVariable String snapshotId){Actor actor=Actor.from(auth);return service.detail(actor,CaseRouteIdentity.canonical(cases,actor,caseId),snapshotId);}
  @GetMapping("/template/{group}.xlsx")
  public ResponseEntity<byte[]> template(Authentication auth,@PathVariable String caseId,@PathVariable String group){
    Actor actor=Actor.from(auth);caseId=CaseRouteIdentity.canonical(cases,actor,caseId);
    byte[] bytes=service.excelTemplate(actor,caseId,group);
    return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+group+"-evidence-template.xlsx\"")
        .header(HttpHeaders.CACHE_CONTROL,"no-store").body(bytes);
  }
  @PostMapping(value="/manual",consumes="application/json")
  public ObjectNode manual(Authentication auth,@PathVariable String caseId,HttpServletRequest request,
      @RequestHeader(value="Idempotency-Key",required=false)String key)throws IOException {
    Actor actor=Actor.from(auth);actor.requireWriter();caseId=CaseRouteIdentity.canonical(cases,actor,caseId);
    service.config(actor,caseId);return service.submit(actor,caseId,body(request),"MANUAL",key);
  }
  @PostMapping(value="/json",consumes="application/json")
  public ObjectNode json(Authentication auth,@PathVariable String caseId,HttpServletRequest request,
      @RequestHeader(value="Idempotency-Key",required=false)String key)throws IOException {
    Actor actor=Actor.from(auth);actor.requireWriter();caseId=CaseRouteIdentity.canonical(cases,actor,caseId);
    service.config(actor,caseId);return service.submit(actor,caseId,body(request),"JSON",key);
  }
  @PostMapping(value="/inquiry",consumes="application/json")
  public ObjectNode inquire(Authentication auth,@PathVariable String caseId,HttpServletRequest request,
      @RequestHeader(value="Idempotency-Key",required=false)String key)throws IOException {
    Actor actor=Actor.from(auth);actor.requireWriter();caseId=CaseRouteIdentity.canonical(cases,actor,caseId);
    service.config(actor,caseId);return service.inquire(actor,caseId,body(request),key);
  }
  @PostMapping(value="/excel",consumes="multipart/form-data")
  public ObjectNode excel(Authentication auth,@PathVariable String caseId,HttpServletRequest request,
      @RequestHeader(value="Idempotency-Key",required=false)String key)throws IOException {
    Actor actor=Actor.from(auth);actor.requireWriter();
    caseId=CaseRouteIdentity.canonical(cases,actor,caseId);
    // Authorize before parsing up to four workbooks.
    service.config(actor,caseId);
    MultipartHttpServletRequest multipart=WebUtils.getNativeRequest(request,MultipartHttpServletRequest.class);
    if(multipart==null || !multipart.getMultiFileMap().keySet().equals(CaseEvidenceSchema.COLUMNS.keySet())
        || !request.getParameterMap().keySet().equals(Set.of("sourceTimezone"))
        || request.getParameterValues("sourceTimezone").length!=1)
      throw CaseEvidenceService.invalid("Upload four Excel files named PAYMENT, HOST, HISTORY and STATUS, plus one sourceTimezone field.");
    Map<String,byte[]> files=new LinkedHashMap<>();
    for(String group:CaseEvidenceSchema.COLUMNS.keySet()) {
      List<MultipartFile> groupFiles=multipart.getFiles(group);
      if(groupFiles.size()!=1)throw CaseEvidenceService.invalid("Supply exactly one Excel file for each result group.");
      MultipartFile file=groupFiles.get(0);String name=file.getOriginalFilename();
      if(file.isEmpty() || name==null || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx"))
        throw CaseEvidenceService.invalid(group+": choose a non-empty .xlsx file.");
      if(file.getSize()>CaseEvidenceService.MAX_BYTES)throw new ApiException(413,"EVIDENCE_UPLOAD_TOO_LARGE","Each Excel file must be no larger than 5 MiB.");
      files.put(group,file.getBytes());
    }
    return service.excel(actor,caseId,files,request.getParameter("sourceTimezone"),key);
  }
  private byte[] body(HttpServletRequest request)throws IOException {
    byte[] bytes=request.getInputStream().readNBytes(CaseEvidenceService.MAX_BYTES+1);
    if(bytes.length>CaseEvidenceService.MAX_BYTES)throw CaseEvidenceService.tooLarge();return bytes;
  }
}
