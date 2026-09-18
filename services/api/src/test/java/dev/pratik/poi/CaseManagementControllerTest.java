package dev.pratik.poi;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value=CaseManagementController.class,properties="poi.demo-password=demo-pass-local")
@Import({SecurityConfig.class,RequestIdFilter.class})
class CaseManagementControllerTest {
  @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;
  @MockBean CaseManagementService service; @MockBean PaymentDiscoveryService cases;
  final String caseId="FCR-HTTP-MANAGEMENT",base="/api/payment-cases/"+caseId;
  @BeforeEach void setup() {
    when(cases.caseRecord(any(),eq(caseId))).thenAnswer(call->{Actor actor=call.getArgument(0);if(!actor.tenantId().equals("northstar"))throw ApiException.notFound();return mapper.createObjectNode().put("id",caseId);});
    when(service.detail(any(),eq(caseId))).thenReturn(mapper.createObjectNode().put("caseId",caseId).put("version",0));
  }

  @Test void viewerCanReadWithoutCsrfButAnonymousCannot()throws Exception {
    mvc.perform(get(base+"/management")).andExpect(status().isUnauthorized());
    mvc.perform(get(base+"/management").with(user("viewer"))).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0)).andExpect(header().string("Cache-Control","no-store"));
    verify(service).detail(argThat(a->a.role().equals("VIEWER")&&a.tenantId().equals("northstar")),eq(caseId));
  }
  @Test void allWritesRequireCsrfWriterAndScopeBeforeServiceDispatch()throws Exception {
    for(String path:new String[]{"/management","/notes","/evidence-requests","/evidence-requests/REQ-ONE","/reviewer-conclusions","/workflow"}) {
      mvc.perform(post(base+path).with(user("analyst")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
      mvc.perform(post(base+path).with(user("viewer")).with(csrf().asHeader()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
      mvc.perform(post(base+path).with(user("other")).with(csrf().asHeader()).contentType("application/json").content("{}")).andExpect(status().isNotFound());
    }
    verifyNoInteractions(service);
  }
  @Test void managementRoutesCarryActorExactResourceBodyAndIdempotencyKey()throws Exception {
    String command="{\"expectedVersion\":0,\"text\":\"Human note\"}";
    mvc.perform(post(base+"/management").with(user("analyst")).with(csrf().asHeader()).header("Idempotency-Key","http-command-key").contentType("application/json").content(command)).andExpect(status().isOk());
    mvc.perform(post(base+"/workflow").with(user("analyst")).with(csrf().asHeader()).header("Idempotency-Key","http-command-key").contentType("application/json").content(command)).andExpect(status().isOk());
    verify(service).transition(any(),eq(caseId),aryEq(command.getBytes(java.nio.charset.StandardCharsets.UTF_8)),eq("http-command-key"));
    mvc.perform(post(base+"/notes").with(user("analyst")).with(csrf().asHeader()).header("Idempotency-Key","http-command-key").contentType("application/json").content(command)).andExpect(status().isOk());
    mvc.perform(post(base+"/evidence-requests").with(user("analyst")).with(csrf().asHeader()).header("Idempotency-Key","http-command-key").contentType("application/json").content(command)).andExpect(status().isOk());
    mvc.perform(post(base+"/evidence-requests/REQ-ONE").with(user("analyst")).with(csrf().asHeader()).header("Idempotency-Key","http-command-key").contentType("application/json").content(command)).andExpect(status().isOk());
    verify(service).manage(argThat(a->a.id().equals("analyst")),eq(caseId),aryEq(command.getBytes(java.nio.charset.StandardCharsets.UTF_8)),eq("http-command-key"));
    verify(service).addNote(any(),eq(caseId),any(byte[].class),eq("http-command-key"));
    verify(service).requestEvidence(any(),eq(caseId),any(byte[].class),eq("http-command-key"));
    verify(service).updateEvidenceRequest(any(),eq(caseId),eq("REQ-ONE"),any(byte[].class),eq("http-command-key"));
  }
  @Test void conclusionsAreReviewerOnlyAndLargeRequestsAreBounded()throws Exception {
    mvc.perform(post(base+"/reviewer-conclusions").with(user("analyst")).with(csrf().asHeader()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
    mvc.perform(post(base+"/reviewer-conclusions").with(user("reviewer")).with(csrf().asHeader()).header("Idempotency-Key","review-http-key").contentType("application/json").content("{}")).andExpect(status().isOk());
    verify(service).conclude(argThat(a->a.role().equals("REVIEWER")),eq(caseId),any(byte[].class),eq("review-http-key"));
    mvc.perform(post(base+"/notes").with(user("analyst")).with(csrf().asHeader()).contentType("application/json").content("x".repeat(CaseManagementService.MAX_BYTES+1))).andExpect(status().isPayloadTooLarge());
    verify(service,never()).addNote(any(),anyString(),any(),any());
  }
}
