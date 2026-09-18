package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"poi.import-fixtures=false","spring.datasource.url=jdbc:h2:mem:case-evidence-http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","poi.payment-discovery.mode=MOCK","poi.case-evidence.api-enabled=false"})
@AutoConfigureMockMvc
class CaseEvidenceControllerTest {
  @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate db;
  @Autowired PaymentDiscoveryService cases; @Autowired CaseEvidenceService service;
  final Actor actor=new Actor("analyst","Analyst","ANALYST","northstar");
  String caseId,base,payload;
  @BeforeEach void setup() {
    for(String table:List.of("fcr_case_management_command","fcr_case_management_event","fcr_case_management","fcr_case_evidence_command","fcr_case_evidence","fcr_case_command","fcr_case_number","fcr_payment_case","fcr_discovery_batch","fcr_discovery_candidate"))db.update("DELETE FROM "+table);
    var result=cases.search(actor,"{\"orgBank\":\"760\",\"orgBranch\":\"1352\",\"inquiryDate\":\"2026-09-14\",\"recordCount\":20}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var request=mapper.createObjectNode().put("candidateId",result.path("items").get(0).path("candidateId").asText()).put("reason","Original synthetic HTTP validation");
    var created=cases.createCase(actor,request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),"case-http-setup");
    caseId=created.path("caseId").asText();base="/api/payment-cases/"+caseId+"/evidence";
    payload=service.template((ObjectNode)created.path("item")).toString();
  }
  @Test void authCsrfTenantAndWriterChecksApplyToNewEndpoints() throws Exception {
    mvc.perform(get(base+"/config")).andExpect(status().isUnauthorized());
    mvc.perform(get(base+"/config").with(user("other"))).andExpect(status().isNotFound());
    mvc.perform(get(base+"/config").with(user("viewer"))).andExpect(status().isOk()).andExpect(jsonPath("$.groups.length()").value(4));
    mvc.perform(post(base+"/manual").with(user("analyst")).header("Idempotency-Key","http-save-one").contentType("application/json").content(payload)).andExpect(status().isForbidden());
    mvc.perform(post(base+"/manual").with(user("viewer")).with(csrf().asHeader()).header("Idempotency-Key","http-save-one").contentType("application/json").content(payload)).andExpect(status().isForbidden());
    mvc.perform(post(base+"/json").with(user("other")).with(csrf().asHeader()).header("Idempotency-Key","http-save-one").contentType("application/json").content(payload)).andExpect(status().isNotFound());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_case_evidence",Integer.class)).isZero();
  }
  @Test void jsonSaveReplayAndReadVersionAreDurableAndCaseScoped() throws Exception {
    String id=null;
    for(int i=0;i<2;i++) {
      var response=mvc.perform(post(base+"/json").with(user("analyst")).with(csrf().asHeader()).header("Idempotency-Key","http-save-json").contentType("application/json").content(payload))
          .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.sourceKind").value("JSON")).andReturn();
      id=mapper.readTree(response.getResponse().getContentAsString()).path("id").asText();
    }
    mvc.perform(get(base).with(user("viewer"))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].payload").doesNotExist());
    mvc.perform(get(base+"/"+id).with(user("viewer"))).andExpect(status().isOk()).andExpect(jsonPath("$.payload.payment.reference").isString());
    mvc.perform(get(base+"/"+id).with(user("other"))).andExpect(status().isNotFound());
    mvc.perform(get(base+"/missing").with(user("analyst"))).andExpect(status().isNotFound());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_case_evidence",Integer.class)).isEqualTo(1);
  }
  @Test void shortCaseNumberUsesCanonicalEvidenceAndManagementBindingsAndRetryKeys() throws Exception {
    String number=cases.caseDetail(actor,caseId).path("caseNumber").asText();
    assertThat(number).matches("[0-9]{13}");
    String alias="/api/payment-cases/"+number;
    String evidenceId=null;
    for(String path:List.of(alias+"/evidence/json",base+"/json")) {
      var response=mvc.perform(post(path).with(user("analyst")).with(csrf().asHeader())
          .header("Idempotency-Key","alias-json-save").contentType("application/json").content(payload))
          .andExpect(status().isOk()).andExpect(jsonPath("$.caseId").value(caseId))
          .andExpect(jsonPath("$.version").value(1)).andReturn();
      String current=mapper.readTree(response.getResponse().getContentAsString()).path("id").asText();
      if(evidenceId!=null)assertThat(current).isEqualTo(evidenceId);
      evidenceId=current;
    }
    mvc.perform(get(alias+"/evidence/"+evidenceId).with(user("viewer")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.caseId").value(caseId));
    mvc.perform(get(alias+"/workbench").with(user("viewer")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.caseId").value(caseId))
        .andExpect(jsonPath("$.evidence.length()").value(1));
    String note="{\"expectedVersion\":0,\"text\":\"Review this payment\"}";
    for(String path:List.of(alias+"/notes","/api/payment-cases/"+caseId+"/notes"))
      mvc.perform(post(path).with(user("analyst")).with(csrf().asHeader())
          .header("Idempotency-Key","alias-note-save").contentType("application/json").content(note))
          .andExpect(status().isOk()).andExpect(jsonPath("$.caseId").value(caseId))
          .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.notes.length()").value(1));
    mvc.perform(get(alias+"/management").with(user("viewer")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.notes[0].text").value("Review this payment"));
    for(String table:List.of("fcr_case_evidence","fcr_case_evidence_command","fcr_case_management",
        "fcr_case_management_event","fcr_case_management_command")) {
      assertThat(db.queryForList("SELECT case_id FROM "+table,String.class)).containsExactly(caseId);
    }
    for(String path:List.of("/evidence","/evidence/config","/management","/workbench"))
      mvc.perform(get(alias+path).with(user("other"))).andExpect(status().isNotFound());
    mvc.perform(post(alias+"/evidence/json").with(user("other")).with(csrf().asHeader())
        .header("Idempotency-Key","alias-denied-json").contentType("application/json").content(payload))
        .andExpect(status().isNotFound());
    mvc.perform(post(alias+"/notes").with(user("other")).with(csrf().asHeader())
        .header("Idempotency-Key","alias-denied-note").contentType("application/json").content(note))
        .andExpect(status().isNotFound());
  }
  @Test void fourHeaderOnlyWorkbooksAndTemplateDownloadsUseStandardColumns() throws Exception {
    var upload=multipart(base+"/excel");upload.param("sourceTimezone","UNKNOWN");
    for(String group:CaseEvidenceSchema.COLUMNS.keySet()) {
      var download=mvc.perform(get(base+"/template/"+group+".xlsx").with(user("analyst"))).andExpect(status().isOk())
          .andExpect(header().string("Cache-Control","no-store")).andReturn();
      byte[] bytes=download.getResponse().getContentAsByteArray();assertThat(CaseEvidenceSchema.read(group,bytes)).isEmpty();
      upload.file(new MockMultipartFile(group,group+".xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes));
    }
    mvc.perform(upload.with(user("analyst")).with(csrf().asHeader()).header("Idempotency-Key","http-excel-save"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.coverage.HOST.rowCount").value(0)).andExpect(jsonPath("$.coverage.HOST.completion").value("UNVERIFIED"));
  }
  @Test void malformedInputsAndDisabledInquiryDoNotCreateSnapshots() throws Exception {
    mvc.perform(multipart(base+"/excel").param("sourceTimezone","UNKNOWN").with(user("analyst")).with(csrf().asHeader()).header("Idempotency-Key","http-bad-excel")).andExpect(status().isUnprocessableEntity());
    mvc.perform(post(base+"/manual").with(user("analyst")).with(csrf().asHeader()).contentType("application/json").content(payload)).andExpect(status().isBadRequest());
    mvc.perform(post(base+"/json").with(user("analyst")).with(csrf().asHeader()).header("Idempotency-Key","http-bad-json").contentType("application/json").content("{\"x\":1,\"x\":2}")).andExpect(status().isUnprocessableEntity());
    mvc.perform(post(base+"/inquiry").with(user("analyst")).with(csrf().asHeader()).header("Idempotency-Key","http-bank-inquiry").contentType("application/json").content("{}" )).andExpect(status().isServiceUnavailable());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_case_evidence",Integer.class)).isZero();
  }
  @Test void jsonIdentityAndExcelGroupErrorsReachTheCallerWithoutSavingOrRemapping() throws Exception {
    ObjectNode wrong = (ObjectNode)mapper.readTree(payload);
    ((ObjectNode)wrong.path("payment")).put("orgBank","999");
    mvc.perform(post(base+"/json").with(user("analyst")).with(csrf().asHeader()).header("Idempotency-Key","http-specific-json")
        .contentType("application/json").content(wrong.toString())).andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.message",org.hamcrest.Matchers.containsString("payment.orgBank")))
        .andExpect(jsonPath("$.message",org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("999"))));
    var upload=multipart(base+"/excel");upload.param("sourceTimezone","UNKNOWN");
    for(String group:CaseEvidenceSchema.COLUMNS.keySet())upload.file(new MockMultipartFile(group,group+".xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",CaseEvidenceSchema.template(group.equals("HOST")?"STATUS":group)));
    mvc.perform(upload.with(user("analyst")).with(csrf().asHeader()).header("Idempotency-Key","http-specific-excel"))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message",org.hamcrest.Matchers.startsWith("HOST Excel file:")));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_case_evidence",Integer.class)).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_case_evidence_command",Integer.class)).isZero();
  }
  @Test void historyPagesAndPinnedSummariesUseAuthorizedCanonicalRoutes() throws Exception {
    String oldest=null;
    for(int n=0;n<12;n++) {
      String saved=service.submit(actor,caseId,payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),"JSON","history-http-"+n).path("id").asText();
      if(oldest==null)oldest=saved;
    }
    String alias="/api/payment-cases/"+cases.caseDetail(actor,caseId).path("caseNumber").asText();
    var first=mvc.perform(get(alias+"/evidence").with(user("viewer"))).andExpect(status().isOk())
        .andExpect(jsonPath("$.caseId").value(caseId)).andExpect(jsonPath("$.items.length()").value(10))
        .andExpect(jsonPath("$.limit").value(10)).andExpect(jsonPath("$.total").value(12)).andReturn();
    String cursor=mapper.readTree(first.getResponse().getContentAsString()).path("nextCursor").asText();
    mvc.perform(get(alias+"/evidence").param("cursor",cursor).with(user("viewer"))).andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.nextCursor").isEmpty());
    mvc.perform(get(alias+"/evidence/"+oldest+"/summary").with(user("viewer"))).andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.payload").doesNotExist());
    mvc.perform(get(alias+"/activity").param("limit","3").with(user("viewer"))).andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(3)).andExpect(jsonPath("$.total").value(13));
    mvc.perform(get(alias+"/investigations").param("status","COMPLETED").with(user("viewer"))).andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.total").value(0));
    for(String path:List.of("/evidence","/investigations","/activity")) {
      mvc.perform(get(alias+path).param("limit","0").with(user("viewer"))).andExpect(status().isBadRequest());
      mvc.perform(get(alias+path).param("limit","1","2").with(user("viewer"))).andExpect(status().isBadRequest());
      mvc.perform(get(alias+path).with(user("other"))).andExpect(status().isNotFound());
      mvc.perform(get(alias+path)).andExpect(status().isUnauthorized());
    }
    mvc.perform(get(alias+"/evidence/"+oldest+"/summary").with(user("other"))).andExpect(status().isNotFound());
  }

}
