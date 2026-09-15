package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"poi.import-fixtures=false", "spring.datasource.url=jdbc:h2:mem:inquiry-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class ObpmInquiryControllerTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate db;
  @MockitoBean ObpmInquiryClient client;
  @BeforeEach void clean() {
    for (String table : List.of("review_decision", "investigation", "audit_event", "obpm_import", "obpm_evidence_snapshot", "payment_case")) db.update("DELETE FROM " + table);
    reset(client);
  }
  @Test void requiresAuthenticationWriterAndCsrfBeforeFetching() throws Exception {
    mvc.perform(get("/api/obpm/inquiry")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/obpm/inquiries").contentType("application/json").content("{\"paymentReference\":\"DEMO-1\"}").with(user("analyst").roles("ANALYST"))).andExpect(status().isForbidden());
    mvc.perform(post("/api/obpm/inquiries").contentType("application/json").content("{\"paymentReference\":\"DEMO-1\"}").with(user("viewer").roles("VIEWER")).with(csrf())).andExpect(status().isForbidden());
    verifyNoInteractions(client);
  }
  @Test void rejectsExtraFieldsDuplicateKeysInvalidReferencesAndLargeBodiesBeforeFetching() throws Exception {
    for (String body : List.of("{}", "[]", "null", "{\"paymentReference\":1}", "{\"paymentReference\":\"x\",\"url\":\"http://example.com\"}", "{\"paymentReference\":\"a\",\"paymentReference\":\"b\"}", "{\"paymentReference\":\"../x\"}", "{\"paymentReference\":\"x\"} {}"))
      mvc.perform(post("/api/obpm/inquiries").with(user("analyst").roles("ANALYST")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
    mvc.perform(post("/api/obpm/inquiries").with(user("analyst").roles("ANALYST")).with(csrf()).contentType("application/json").content(" ".repeat(1025))).andExpect(status().isPayloadTooLarge());
    verifyNoInteractions(client);
  }
  @Test void importsValidatedEvidenceAndReusesIdempotentTenantScopedStore() throws Exception {
    ObjectNode raw = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/inquiry-timeout.json"));
    when(client.fetch("DEMO-NEFT-0001")).thenReturn(ObpmInquiryClient.normalize(raw, "DEMO-NEFT-0001"));
    String body = "{\"paymentReference\":\"DEMO-NEFT-0001\"}";
    String first = mvc.perform(post("/api/obpm/inquiries").with(user("analyst").roles("ANALYST")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CREATED")).andReturn().getResponse().getContentAsString();
    String caseId = mapper.readTree(first).path("caseId").asText();
    mvc.perform(post("/api/obpm/inquiries").with(user("analyst").roles("ANALYST")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNCHANGED")).andExpect(jsonPath("$.evidenceVersion").value(1));
    mvc.perform(get("/api/cases/" + caseId).with(user("other").roles("ANALYST"))).andExpect(status().isNotFound());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM obpm_evidence_snapshot", Integer.class)).isEqualTo(1);
  }
  @Test void upstreamFailureDoesNotPersistAReceiptOrCase() throws Exception {
    when(client.fetch("DEMO-1")).thenThrow(new ApiException(504, "INQUIRY_TIMEOUT", "Mock inquiry timed out"));
    mvc.perform(post("/api/obpm/inquiries").with(user("analyst").roles("ANALYST")).with(csrf()).contentType("application/json").content("{\"paymentReference\":\"DEMO-1\"}")).andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("INQUIRY_TIMEOUT"));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM obpm_import", Integer.class)).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM payment_case", Integer.class)).isZero();
  }
  @Test void configurationDoesNotFetchEvidence() throws Exception {
    when(client.configuration()).thenReturn(Map.of("enabled", false, "mode", "SYNTHETIC_MOCK", "examples", List.of()));
    mvc.perform(get("/api/obpm/inquiry").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
    verify(client).configuration(); verifyNoMoreInteractions(client);
  }
}
