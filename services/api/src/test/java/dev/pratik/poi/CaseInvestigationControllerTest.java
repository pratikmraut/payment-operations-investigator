package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP routing/security only; service lifecycle is independently covered without live inference. */
@WebMvcTest(value=CaseInvestigationController.class, properties="poi.demo-password=demo-pass-local")
@Import({SecurityConfig.class, RequestIdFilter.class})
class CaseInvestigationControllerTest {
  @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;
  @MockBean CaseInvestigationService service;
  @MockBean PaymentDiscoveryService cases;
  final String caseId = "FCR-ORIGINAL-TEST-CASE", base = "/api/payment-cases/" + caseId;
  ObjectNode command, queued;

  @BeforeEach void setup() {
    command = mapper.createObjectNode().put("question", "What can this supplied evidence establish?")
        .put("evidenceId", "EVD-ORIGINAL-TEST").put("evidenceHash", "a".repeat(64));
    queued = mapper.createObjectNode().put("id", "CIN-ORIGINAL-TEST").put("caseId", caseId).put("status", "QUEUED")
        .put("requestedAt", "2026-09-15T10:00:00Z").put("createdAt", "2026-09-15T10:00:02.500Z");
    queued.putObject("timing").put("preparationMs", 2500).putNull("queueMs").putNull("processingMs")
        .putNull("totalMs").put("totalBasis", "request-received");
    when(cases.caseDetail(any(), eq(caseId))).thenAnswer(call -> {
      Actor actor = call.getArgument(0); if(!actor.tenantId().equals("northstar"))throw ApiException.notFound();
      return mapper.createObjectNode().put("id", caseId);
    });
    when(service.start(any(), eq(caseId), any(byte[].class), any())).thenReturn(queued);
    when(service.workbench(any(), eq(caseId))).thenAnswer(call -> {
      Actor actor = call.getArgument(0); if(!actor.tenantId().equals("northstar"))throw ApiException.notFound();
      ObjectNode workbench = mapper.createObjectNode().put("caseId", caseId);
      workbench.putArray("investigations").add(queued); return workbench;
    });
  }

  @Test void allReadsRequireAuthenticationAndPassActorAndExactResourceIds() throws Exception {
    for(String path : new String[]{"/workbench", "/evidence/EVD-ORIGINAL-TEST/context", "/investigations/CIN-ORIGINAL-TEST"})
      mvc.perform(get(base + path)).andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
    when(service.context(any(), eq(caseId), eq("EVD-ORIGINAL-TEST"))).thenReturn(mapper.createObjectNode().put("evidenceId", "EVD-ORIGINAL-TEST"));
    when(service.detail(any(), eq(caseId), eq("CIN-ORIGINAL-TEST"))).thenReturn(queued);
    mvc.perform(get(base + "/workbench").with(user("viewer"))).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.investigations[0].timing.preparationMs").value(2500));
    mvc.perform(get(base + "/evidence/EVD-ORIGINAL-TEST/context").with(user("viewer"))).andExpect(status().isOk()).andExpect(jsonPath("$.evidenceId").value("EVD-ORIGINAL-TEST"));
    mvc.perform(get(base + "/investigations/CIN-ORIGINAL-TEST").with(user("analyst"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"))
        .andExpect(jsonPath("$.timing.totalBasis").value("request-received"));
    verify(service).context(argThat(a -> a.role().equals("VIEWER") && a.tenantId().equals("northstar")), eq(caseId), eq("EVD-ORIGINAL-TEST"));
    verify(service).detail(argThat(a -> a.id().equals("analyst")), eq(caseId), eq("CIN-ORIGINAL-TEST"));
    mvc.perform(get(base + "/workbench").with(user("other"))).andExpect(status().isNotFound());
  }

  @Test void submissionRequiresCsrfWriterAndCaseScopeBeforeServiceDispatch() throws Exception {
    mvc.perform(post(base + "/investigations").with(user("analyst")).header("Idempotency-Key", "http-question-key")
        .contentType("application/json").content(command.toString())).andExpect(status().isForbidden());
    mvc.perform(post(base + "/investigations").with(user("viewer")).with(csrf().asHeader()).header("Idempotency-Key", "http-question-key")
        .contentType("application/json").content(command.toString())).andExpect(status().isForbidden());
    mvc.perform(post(base + "/investigations").with(user("other")).with(csrf().asHeader()).header("Idempotency-Key", "http-question-key")
        .contentType("application/json").content(command.toString())).andExpect(status().isNotFound());
    verify(service, never()).start(any(), anyString(), any(byte[].class), any());
  }

  @Test void acceptedSubmissionPassesOriginalCommandAndRetryKeyWithoutBrowserDocuments() throws Exception {
    byte[] original = command.toString().getBytes(StandardCharsets.UTF_8);
    mvc.perform(post(base + "/investigations").with(user("reviewer")).with(csrf().asHeader())
        .header("Idempotency-Key", "http-question-key").contentType("application/json").content(original))
        .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED"))
        .andExpect(jsonPath("$.requestedAt").value("2026-09-15T10:00:00Z"))
        .andExpect(jsonPath("$.timing.preparationMs").value(2500))
        .andExpect(jsonPath("$.timing.totalMs").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.answer").doesNotExist()).andExpect(jsonPath("$.input").doesNotExist());
    ArgumentCaptor<byte[]> input = ArgumentCaptor.forClass(byte[].class);
    verify(service).start(argThat(a -> a.role().equals("REVIEWER") && a.tenantId().equals("northstar")), eq(caseId), input.capture(), eq("http-question-key"));
    assertThat(input.getValue()).isEqualTo(original);
  }

  @Test void shortCaseNumberReadsAndWritesDispatchCanonicalCaseId() throws Exception {
    String number="2026091600001",alias="/api/payment-cases/"+number;
    when(cases.caseDetail(any(),eq(number))).thenAnswer(call -> {
      Actor actor=call.getArgument(0);if(!actor.tenantId().equals("northstar"))throw ApiException.notFound();
      return mapper.createObjectNode().put("id",caseId).put("caseNumber",number);
    });
    when(service.context(any(),eq(caseId),eq("EVD-ORIGINAL-TEST"))).thenReturn(mapper.createObjectNode());
    when(service.detail(any(),eq(caseId),eq("CIN-ORIGINAL-TEST"))).thenReturn(queued);
    mvc.perform(get(alias+"/workbench").with(user("viewer"))).andExpect(status().isOk());
    mvc.perform(get(alias+"/evidence/EVD-ORIGINAL-TEST/context").with(user("viewer"))).andExpect(status().isOk());
    mvc.perform(get(alias+"/investigations/CIN-ORIGINAL-TEST").with(user("viewer"))).andExpect(status().isOk());
    mvc.perform(post(alias+"/investigations").with(user("analyst")).with(csrf().asHeader())
        .header("Idempotency-Key","alias-question-key").contentType("application/json").content(command.toString()))
        .andExpect(status().isAccepted()).andExpect(jsonPath("$.caseId").value(caseId));
    verify(service).workbench(any(),eq(caseId));
    verify(service).context(any(),eq(caseId),eq("EVD-ORIGINAL-TEST"));
    verify(service).detail(any(),eq(caseId),eq("CIN-ORIGINAL-TEST"));
    verify(service).start(any(),eq(caseId),any(),eq("alias-question-key"));
    clearInvocations(service);
    mvc.perform(post(alias+"/investigations").with(user("other")).with(csrf().asHeader())
        .header("Idempotency-Key","alias-question-denied").contentType("application/json").content(command.toString()))
        .andExpect(status().isNotFound());
    verifyNoInteractions(service);
  }

  @Test void boundedReadAndServiceErrorsKeepActionableStatusesWithoutExecutingInference() throws Exception {
    when(service.start(any(), eq(caseId), any(byte[].class), any())).thenAnswer(call -> {
      byte[] bytes = call.getArgument(2); String key = call.getArgument(3);
      if(bytes.length > 16384)throw new ApiException(413, "CASE_QUESTION_TOO_LARGE", "Question requests are limited to 16 KiB.");
      if(key == null)throw new ApiException(400, "CASE_QUESTION_KEY_REQUIRED", "An Idempotency-Key is required.");
      throw new ApiException(409, "CASE_EVIDENCE_CHANGED", "Refresh the saved evidence version.");
    });
    mvc.perform(post(base + "/investigations").with(user("analyst")).with(csrf().asHeader()).contentType("application/json")
        .content(command.toString())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CASE_QUESTION_KEY_REQUIRED"));
    mvc.perform(post(base + "/investigations").with(user("analyst")).with(csrf().asHeader()).header("Idempotency-Key", "http-question-key")
        .contentType("application/json").content(command.toString())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_EVIDENCE_CHANGED"));
    mvc.perform(post(base + "/investigations").with(user("analyst")).with(csrf().asHeader()).header("Idempotency-Key", "http-question-key")
        .contentType("application/json").content("x".repeat(100000))).andExpect(status().isPayloadTooLarge());
    ArgumentCaptor<byte[]> input = ArgumentCaptor.forClass(byte[].class);
    verify(service, times(3)).start(any(), eq(caseId), input.capture(), any());
    assertThat(input.getAllValues().get(2)).hasSize(16385);
  }
}
