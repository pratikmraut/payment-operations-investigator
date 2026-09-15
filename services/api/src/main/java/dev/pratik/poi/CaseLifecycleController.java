package dev.pratik.poi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payment-cases/{caseId}")
public class CaseLifecycleController {
  private final CaseLifecycleService service;
  public CaseLifecycleController(CaseLifecycleService service){this.service=service;}
  @GetMapping("/lifecycle") public ObjectNode detail(Authentication auth,@PathVariable String caseId){return service.detail(Actor.from(auth),caseId);}
  @PostMapping(value="/archive",consumes="application/json") public ObjectNode archive(Authentication auth,@PathVariable String caseId,HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false)String key)throws IOException {return command(auth,caseId,"ARCHIVED",request,key);}
  @PostMapping(value="/restore",consumes="application/json") public ObjectNode restore(Authentication auth,@PathVariable String caseId,HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false)String key)throws IOException {return command(auth,caseId,"ACTIVE",request,key);}
  @PostMapping(value="/permanent-delete",consumes="application/json") public ObjectNode delete(Authentication auth,@PathVariable String caseId,HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false)String key)throws IOException {return command(auth,caseId,"DELETED",request,key);}
  private ObjectNode command(Authentication auth,String id,String action,HttpServletRequest request,String key)throws IOException {
    Actor actor=Actor.from(auth);if(action.equals("DELETED"))actor.requireAdministrator();else actor.requireLifecycleManager();
    return service.command(actor,id,action,request.getInputStream().readNBytes(CaseLifecycleService.MAX_BYTES+1),key);
  }
}
