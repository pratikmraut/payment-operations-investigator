package dev.pratik.poi;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers=CaseReportController.class, properties="poi.demo-password=demo-pass-local")
@Import({SecurityConfig.class, RequestIdFilter.class})
class CaseReportControllerTest {
  @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;
  @MockBean CaseReportService service; @MockBean PaymentDiscoveryService cases;
  final String base = "/api/payment-cases/FCR-TEST";
  ObjectNode report;
  @BeforeEach void setup() {
    report = mapper.createObjectNode().put("reportId", "RPT-TEST").put("reportHash", "a".repeat(64));
    when(cases.caseDetail(any(), eq("FCR-TEST"))).thenAnswer(call -> {
      Actor actor = call.getArgument(0); if (!actor.tenantId().equals("northstar")) throw ApiException.notFound();
      return mapper.createObjectNode().put("id", "FCR-TEST");
    });
    when(service.preview(any(), anyString(), any(), any())).thenReturn(report);
    when(service.frozen(any(), anyString(), any())).thenReturn(report);
    when(service.render(any())).thenReturn("%PDF-1.7\noriginal-test".getBytes());
  }
  @Test void bothActionsRequireAuthenticationCsrfAndAuthorizedCaseIncludingViewer() throws Exception {
    for (String route : new String[]{"/report-preview", "/report.pdf"}) {
      mvc.perform(post(base+route).contentType("application/json").content("{}"))
          .andExpect(status().isForbidden());
      mvc.perform(post(base+route).with(user("viewer")).contentType("application/json").content("{}"))
          .andExpect(status().isForbidden());
      mvc.perform(post(base+route).with(user("other")).with(csrf().asHeader()).contentType("application/json").content("{}"))
          .andExpect(status().isNotFound());
    }
    verifyNoInteractions(service);
    mvc.perform(post(base+"/report-preview").with(user("viewer")).with(csrf().asHeader())
        .header("Idempotency-Key", "viewer-preview-key").contentType("application/json").content("{}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.reportId").value("RPT-TEST"))
        .andExpect(header().string("Cache-Control", "no-store"));
    verify(service).preview(argThat(a -> a.role().equals("VIEWER")), eq("FCR-TEST"), any(), eq("viewer-preview-key"));
  }
  @Test void pdfIsAttachmentWithSafeFilenameAndNoCache() throws Exception {
    mvc.perform(post(base+"/report.pdf").with(user("analyst")).with(csrf().asHeader())
        .contentType("application/json").accept("application/pdf").content(report.toString()))
        .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
        .andExpect(header().string("Content-Disposition", "attachment; filename=\"payment-investigation-RPT-TEST.pdf\""))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"));
  }
  @Test void shortCaseNumberResolvesBothReportActionsBeforeSnapshotBinding() throws Exception {
    String number="2026091600001",alias="/api/payment-cases/"+number;
    when(cases.caseDetail(any(),eq(number))).thenAnswer(call -> {
      Actor actor=call.getArgument(0);if(!actor.tenantId().equals("northstar"))throw ApiException.notFound();
      return mapper.createObjectNode().put("id","FCR-TEST").put("caseNumber",number);
    });
    report.putObject("case").put("id","FCR-TEST").put("caseNumber",number);
    mvc.perform(post(alias+"/report-preview").with(user("viewer")).with(csrf().asHeader())
        .header("Idempotency-Key","alias-report-key").contentType("application/json").content("{}"))
        .andExpect(status().isOk());
    mvc.perform(post(alias+"/report.pdf").with(user("viewer")).with(csrf().asHeader())
        .contentType("application/json").accept("application/pdf").content(report.toString()))
        .andExpect(status().isOk()).andExpect(header().string("Content-Disposition",
            "attachment; filename=\"payment-case-"+number+"-report.pdf\""));
    verify(service).preview(any(),eq("FCR-TEST"),any(),eq("alias-report-key"));
    verify(service).frozen(any(),eq("FCR-TEST"),any());
    clearInvocations(service);
    mvc.perform(post(alias+"/report.pdf").with(user("other")).with(csrf().asHeader())
        .contentType("application/json").accept("application/pdf").content(report.toString()))
        .andExpect(status().isNotFound());
    verifyNoInteractions(service);
  }
  @Test void summarySelectionReachesServiceUnchangedAndReturnedScopeIsPreserved() throws Exception {
    String selection = "{\"evidenceId\":\"EVD-TEST\",\"investigationIds\":[\"CIN-TEST\"],\"includeEvidenceRows\":false,\"reportMode\":\"SUMMARY\"}";
    report.set("scope", mapper.readTree(selection));
    mvc.perform(post(base+"/report-preview").with(user("viewer")).with(csrf().asHeader())
        .header("Idempotency-Key", "summary-preview-key").contentType("application/json").content(selection))
        .andExpect(status().isOk()).andExpect(jsonPath("$.scope.reportMode").value("SUMMARY"))
        .andExpect(jsonPath("$.scope.includeEvidenceRows").value(false))
        .andExpect(header().string("Cache-Control", "no-store"));
    verify(service).preview(argThat(actor -> actor.role().equals("VIEWER")), eq("FCR-TEST"),
        argThat(body -> java.util.Arrays.equals(body, selection.getBytes(java.nio.charset.StandardCharsets.UTF_8))), eq("summary-preview-key"));
  }
  @Test void boundedBodyAndActionableErrorsAreNotRenderedAsPdf() throws Exception {
    when(service.preview(any(), anyString(), any(), any())).thenAnswer(call -> {
      byte[] body = call.getArgument(2);
      if (body.length > 8192) throw new ApiException(413, "CASE_REPORT_REQUEST_TOO_LARGE", "Selection too large.");
      throw new ApiException(409, "CASE_REPORT_CHANGED", "Generate a new preview.");
    });
    mvc.perform(post(base+"/report-preview").with(user("analyst")).with(csrf().asHeader())
        .contentType("application/json").content("x".repeat(20000))).andExpect(status().isPayloadTooLarge());
    mvc.perform(post(base+"/report-preview").with(user("analyst")).with(csrf().asHeader())
        .contentType("application/json").content("{}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_REPORT_CHANGED"));
  }
  @Test void pdfDownloadFailureStillReturnsStructuredJsonForPdfAcceptHeader() throws Exception {
    when(service.frozen(any(), anyString(), any())).thenThrow(new ApiException(409, "CASE_REPORT_CHANGED", "Generate a new preview."));
    mvc.perform(post(base+"/report.pdf").with(user("analyst")).with(csrf().asHeader())
        .contentType("application/json").accept("application/pdf").content(report.toString()))
        .andExpect(status().isConflict()).andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$.code").value("CASE_REPORT_CHANGED"));
  }
  @Test void reportHistoryIsAnAuthorizedReadWithDefaultBoundsAndAliasResolution() throws Exception {
    ObjectNode history = mapper.createObjectNode().put("caseId", "FCR-TEST"); history.putArray("items"); history.putNull("nextCursor");
    when(service.history(any(), eq("FCR-TEST"), anyInt(), nullable(String.class))).thenReturn(history);
    mvc.perform(get(base+"/reports")).andExpect(status().isUnauthorized());
    mvc.perform(get(base+"/reports").with(user("other"))).andExpect(status().isNotFound());
    verifyNoInteractions(service);
    mvc.perform(get(base+"/reports").with(user("viewer")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray())
        .andExpect(header().string("Cache-Control", "no-store"));
    verify(service).history(argThat(actor -> actor.role().equals("VIEWER")), eq("FCR-TEST"), eq(10), isNull());
    String number = "2026091600001";
    when(cases.caseDetail(any(), eq(number))).thenReturn(mapper.createObjectNode().put("id", "FCR-TEST").put("caseNumber", number));
    mvc.perform(get("/api/payment-cases/"+number+"/reports?limit=5&cursor=RPT-LAST").with(user("viewer")))
        .andExpect(status().isOk());
    verify(service).history(any(), eq("FCR-TEST"), eq(5), eq("RPT-LAST"));
    verify(service, never()).preview(any(), anyString(), any(), any());
    verify(service, never()).render(any());
  }
}
