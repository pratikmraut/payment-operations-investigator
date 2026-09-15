package dev.pratik.poi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;

@RestController
public class PaymentDiscoveryController {
  private final PaymentDiscoveryService service;
  public PaymentDiscoveryController(PaymentDiscoveryService service){this.service=service;}
  @GetMapping("/api/payment-discovery/config") public ObjectNode config(Authentication auth){return service.config(Actor.from(auth));}
  @PostMapping(value="/api/payment-discovery/search",consumes="application/json")
  public ObjectNode search(Authentication auth,HttpServletRequest request)throws IOException{return service.search(Actor.from(auth),body(request));}
  @PostMapping(value="/api/payment-discovery/lookup",consumes="application/json")
  public ObjectNode lookup(Authentication auth,HttpServletRequest request)throws IOException{return service.lookup(Actor.from(auth),body(request));}
  @PostMapping(value="/api/payment-discovery/uploads",consumes="multipart/form-data")
  public ObjectNode upload(Authentication auth,@RequestParam("orgBranch")String branch,@RequestParam("orgBank")String bank,@RequestParam("file")MultipartFile file,HttpServletRequest request)throws IOException{
    Actor actor=Actor.from(auth);actor.requireWriter();if(file.getSize()>5*1024*1024)throw new ApiException(413,"UPLOAD_TOO_LARGE","Workbook limit is 5 MiB.");
    MultipartHttpServletRequest multipart=WebUtils.getNativeRequest(request,MultipartHttpServletRequest.class);
    if(request.getParameterMap().size()!=2 || request.getParameterValues("orgBranch").length!=1 || request.getParameterValues("orgBank").length!=1
        || multipart==null || multipart.getMultiFileMap().size()!=1
        || multipart.getFiles("file").size()!=1)throw PaymentDiscoveryService.invalid("Upload exactly one file, one orgBank and one orgBranch field.");
    return service.upload(actor,branch,bank,file.getBytes());
  }
  @PostMapping(value="/api/payment-cases",consumes="application/json")
  public ObjectNode create(Authentication auth,HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false)String key)throws IOException{return service.createCase(Actor.from(auth),body(request),key);}
  @GetMapping("/api/payment-cases") public ObjectNode cases(Authentication auth,@RequestParam(value="lifecycle",defaultValue="ACTIVE")String lifecycle){return service.cases(Actor.from(auth),lifecycle);}
  @GetMapping("/api/payment-cases/dashboard") public ObjectNode dashboard(Authentication auth){return service.dashboard(Actor.from(auth));}
  @GetMapping("/api/payment-cases/{id}") public ObjectNode detail(Authentication auth,@PathVariable String id){return service.caseDetail(Actor.from(auth),id);}
  private byte[] body(HttpServletRequest request)throws IOException{byte[] bytes=request.getInputStream().readNBytes(8193);if(bytes.length>8192)throw new ApiException(413,"DISCOVERY_REQUEST_TOO_LARGE","Discovery request limit is 8 KiB.");return bytes;}
}
