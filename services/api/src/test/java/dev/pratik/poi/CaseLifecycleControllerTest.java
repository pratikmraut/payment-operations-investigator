package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/** Real security/session and lifecycle services against this test's isolated database. */
@SpringBootTest(properties={"poi.import-fixtures=false","poi.demo-password=demo-pass-local",
    "spring.datasource.url=jdbc:h2:mem:case-lifecycle-http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "poi.payment-discovery.mode=MOCK","poi.case-evidence.api-enabled=false"})
@AutoConfigureMockMvc
class CaseLifecycleControllerTest {
  @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate db;
  @Autowired PaymentDiscoveryService cases;
  final Actor analyst=new Actor("analyst","Analyst","ANALYST","northstar");
  String caseId,number,base;

  @BeforeEach void setup() {
    for(String table:List.of("fcr_case_lifecycle_command","fcr_case_lifecycle_event","fcr_case_lifecycle",
        "fcr_case_management_command","fcr_case_management_event","fcr_case_management","fcr_case_report",
        "fcr_case_investigation","fcr_case_evidence_command","fcr_case_evidence","fcr_case_command",
        "fcr_case_number","fcr_payment_case","fcr_discovery_batch","fcr_discovery_candidate"))db.update("DELETE FROM "+table);
    ObjectNode found=cases.search(analyst,bytes(mapper.createObjectNode().put("orgBank","760").put("orgBranch","1352")
        .put("inquiryDate","2026-09-14").put("recordCount",10)));
    ObjectNode created=cases.createCase(analyst,bytes(mapper.createObjectNode()
        .put("candidateId",found.path("items").get(0).path("candidateId").asText()).put("reason","Original HTTP lifecycle case")),"lifecycle-http-create");
    caseId=created.path("caseId").asText();number=created.path("caseNumber").asText();base="/api/payment-cases/"+number;
  }
  byte[] bytes(JsonNode node){return node.toString().getBytes(StandardCharsets.UTF_8);}
  ObjectNode request(long version){return mapper.createObjectNode().put("expectedVersion",version).put("reason","Remove an unwanted isolated test case");}

  @Test void realAdminSessionCanDeleteThroughNumericAliasAndRepeatExactlyWhileOtherRolesCannot()throws Exception {
    var login=mvc.perform(post("/api/auth/login").contentType("application/json")
        .content("{\"username\":\"admin\",\"password\":\"demo-pass-local\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("ADMIN"))
        .andExpect(jsonPath("$.user.tenantId").value("northstar")).andReturn();
    MockHttpSession session=(MockHttpSession)login.getRequest().getSession(false);
    String token=mapper.readTree(login.getResponse().getContentAsString()).path("csrfToken").asText();
    ObjectNode deletion=request(1).put("confirmation",number);
    for(String role:List.of("analyst","reviewer","viewer"))
      mvc.perform(post(base+"/permanent-delete").with(user(role)).with(csrf().asHeader())
          .header("Idempotency-Key","http-delete-one").contentType("application/json").content(bytes(deletion)))
          .andExpect(status().isForbidden());
    mvc.perform(post(base+"/permanent-delete").session(session).header("Idempotency-Key","http-delete-one")
        .contentType("application/json").content(bytes(deletion))).andExpect(status().isForbidden());
    // Administrator lifecycle rights do not silently grant case writing or reviewer rights.
    for(String path:List.of("/notes","/reviewer-conclusions","/evidence/json","/investigations"))
      mvc.perform(post(base+path).session(session).header("X-CSRF-Token",token)
          .header("Idempotency-Key","http-admin-forbidden").contentType("application/json").content("{}"))
          .andExpect(status().isForbidden());
    mvc.perform(post(base+"/archive").with(user("analyst")).with(csrf().asHeader())
        .header("Idempotency-Key","http-archive-one").contentType("application/json").content(bytes(request(0))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ARCHIVED"));
    mvc.perform(post(base+"/permanent-delete").session(session).header("X-CSRF-Token",token)
        .header("Idempotency-Key","http-delete-wrong").contentType("application/json").content(bytes(request(1).put("confirmation","0000000000000"))))
        .andExpect(status().isUnprocessableEntity());
    String receipt=null;
    for(int attempt=0;attempt<2;attempt++) {
      var result=mvc.perform(post(base+"/permanent-delete").session(session).header("X-CSRF-Token",token)
          .header("Idempotency-Key","http-delete-one").contentType("application/json").content(bytes(deletion)))
          .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("DELETED"))
          .andExpect(jsonPath("$.caseNumber").value(number)).andReturn();
      if(receipt==null)receipt=result.getResponse().getContentAsString();
      else assertThat(result.getResponse().getContentAsString()).isEqualTo(receipt);
    }
    mvc.perform(get(base).session(session)).andExpect(status().isNotFound());
    mvc.perform(get("/api/payment-cases/"+caseId).with(user("analyst"))).andExpect(status().isNotFound());
    mvc.perform(get("/api/payment-cases?lifecycle=ALL").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_case_number WHERE case_id=? AND case_number=?",Integer.class,caseId,number)).isEqualTo(1);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_case_lifecycle_event WHERE case_id=?",Integer.class,caseId)).isEqualTo(2);
    assertThat(mapper.readTree(db.queryForObject("SELECT body FROM fcr_payment_case WHERE id=?",String.class,caseId)).has("reference")).isFalse();
  }

  @Test void lifecycleReadAndCommandsPreserveAuthenticationCsrfAndTenantScope()throws Exception {
    mvc.perform(get(base+"/lifecycle")).andExpect(status().isUnauthorized());
    mvc.perform(get(base+"/lifecycle").with(user("other"))).andExpect(status().isNotFound());
    mvc.perform(get(base+"/lifecycle").with(user("viewer"))).andExpect(status().isOk())
        .andExpect(jsonPath("$.canArchive").value(false)).andExpect(jsonPath("$.canDelete").value(false));
    mvc.perform(post(base+"/archive").with(user("analyst")).header("Idempotency-Key","csrf-archive-key")
        .contentType("application/json").content(bytes(request(0)))).andExpect(status().isForbidden());
    mvc.perform(post(base+"/archive").with(user("viewer")).with(csrf().asHeader()).header("Idempotency-Key","viewer-archive-key")
        .contentType("application/json").content(bytes(request(0)))).andExpect(status().isForbidden());
    mvc.perform(post(base+"/archive").with(user("other")).with(csrf().asHeader()).header("Idempotency-Key","other-archive-key")
        .contentType("application/json").content(bytes(request(0)))).andExpect(status().isNotFound());
    mvc.perform(get("/api/payment-cases?lifecycle=DELETED").with(user("analyst"))).andExpect(status().isBadRequest());
    mvc.perform(get(base+"/lifecycle").with(user("analyst"))).andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("ACTIVE")).andExpect(jsonPath("$.version").value(0));
  }
}
