package dev.pratik.poi;

import static dev.pratik.poi.UatTestData.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"poi.import-fixtures=false", "spring.datasource.url=jdbc:h2:mem:uat-api-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class UatControllerTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @MockitoBean UatService service;
  @BeforeEach void resetMock() { reset(service); }
  @Test void allRoutesRequireSessionAndPostingRequiresCsrfAndWriter() throws Exception {
    String path = "/api/uat/snapshots/" + ID;
    for (String route : new String[]{"/api/uat/snapshots", path, path + "/questions"})
      mvc.perform(get(route)).andExpect(status().isUnauthorized());
    mvc.perform(post(path + "/questions").contentType("application/json").content("{}")).andExpect(status().isForbidden());
    mvc.perform(post(path + "/questions").with(user("analyst").roles("ANALYST")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
    mvc.perform(post(path + "/questions").with(user("viewer").roles("VIEWER")).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }
  @Test void requestBodyIsBoundedBeforeServiceOrModel() throws Exception {
    mvc.perform(post("/api/uat/snapshots/" + ID + "/questions").with(user("analyst").roles("ANALYST")).with(csrf())
        .contentType("application/json").content("x".repeat(16385))).andExpect(status().isPayloadTooLarge());
    verifyNoInteractions(service);
  }
  @Test void actorTenantComesFromSessionAndNeverWritesSyntheticCases() throws Exception {
    when(service.list(any())).thenReturn(MAPPER.createObjectNode().put("enabled", true));
    mvc.perform(get("/api/uat/snapshots?tenantId=northstar").with(user("other").roles("ANALYST")))
        .andExpect(status().isOk());
    ArgumentCaptor<Actor> actor = ArgumentCaptor.forClass(Actor.class);
    verify(service).list(actor.capture()); assertThat(actor.getValue().tenantId()).isEqualTo("silverline");
    when(service.snapshot(eq(ID), any())).thenThrow(new ApiException(404, "UAT_SNAPSHOT_NOT_FOUND", "Not found"));
    mvc.perform(get("/api/uat/snapshots/" + ID).with(user("other").roles("ANALYST"))).andExpect(status().isNotFound());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM payment_case", Integer.class)).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM obpm_import", Integer.class)).isZero();
  }
  @Test void writerReceivesStoredResponseAndFailuresHaveNoAnswerFallback() throws Exception {
    var request = MAPPER.createObjectNode().put("question", "Original question").put("snapshotId", ID).put("evidenceHash", "a".repeat(64));
    request.set("documents", bundle().path("documents"));
    when(service.answer(eq(ID), any(), any())).thenReturn(response(request));
    mvc.perform(post("/api/uat/snapshots/" + ID + "/questions").with(user("reviewer").roles("REVIEWER")).with(csrf())
        .contentType("application/json").content("{}")).andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("model-generated")).andExpect(jsonPath("$.model.actualCalls").value(1));
    when(service.answer(eq(ID), any(), any())).thenThrow(new ApiException(503, "UAT_STORAGE_UNAVAILABLE", "Cannot save"));
    mvc.perform(post("/api/uat/snapshots/" + ID + "/questions").with(user("analyst").roles("ANALYST")).with(csrf())
        .contentType("application/json").content("{}")).andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("UAT_STORAGE_UNAVAILABLE")).andExpect(jsonPath("$.answer").doesNotExist());
  }
}
