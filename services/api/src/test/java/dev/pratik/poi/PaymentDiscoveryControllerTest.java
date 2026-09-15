package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@SpringBootTest(properties={"poi.import-fixtures=false","spring.datasource.url=jdbc:h2:mem:discovery-http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","poi.payment-discovery.mode=MOCK"})
@AutoConfigureMockMvc
class PaymentDiscoveryControllerTest {
  @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired JdbcTemplate db;
  final String request="{\"orgBank\":\"760\",\"orgBranch\":\"1352\",\"inquiryDate\":\"2026-09-14\",\"recordCount\":20}";
  @BeforeEach void clean(){for(String table:List.of("fcr_case_command","fcr_case_number","fcr_payment_case","fcr_discovery_batch","fcr_discovery_candidate"))db.update("DELETE FROM "+table);}
  @Test void authenticationWriterAndCsrfAreRequiredForMutation() throws Exception {
    mvc.perform(get("/api/payment-discovery/config")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/payment-discovery/config").with(user("viewer"))).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("MOCK"));
    mvc.perform(post("/api/payment-discovery/search").with(user("analyst")).contentType("application/json").content(request)).andExpect(status().isForbidden());
    mvc.perform(post("/api/payment-discovery/search").with(user("viewer")).with(csrf().asHeader()).contentType("application/json").content(request)).andExpect(status().isForbidden());
    mvc.perform(post("/api/payment-discovery/search").with(user("other")).with(csrf().asHeader()).contentType("application/json").content(request)).andExpect(status().isForbidden());
    mvc.perform(post("/api/payment-discovery/search").with(user("analyst")).with(csrf().asHeader()).contentType("application/json").content(request.replace("760","999"))).andExpect(status().isForbidden());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_discovery_batch",Integer.class)).isZero();
  }
  @Test void unknownDuplicateAndOversizedJsonRequestsAreRejected() throws Exception {
    for(String body:List.of("{}","[]","null",request.replace("\"orgBank\":\"760\",",""),request.replace("20}","20,\"url\":\"http://example.test\"}"),request.replace("20}","20,\"orgBranch\":\"2468\"}"),request.replace("20}","20,\"orgBank\":\"999\"}")))
      mvc.perform(post("/api/payment-discovery/search").with(user("analyst")).with(csrf().asHeader()).contentType("application/json").content(body)).andExpect(status().isUnprocessableEntity());
    mvc.perform(post("/api/payment-discovery/search").with(user("analyst")).with(csrf().asHeader()).contentType("application/json").content(" ".repeat(8193))).andExpect(status().isPayloadTooLarge());
  }
  @Test void exactLookupUsesItsOwnStrictRequestAndPreservesAuthAndScope() throws Exception {
    String reference=PaymentDiscoveryClient.mock("1352","760",java.time.LocalDate.of(2021,4,3)).rows().get(0).get("PIO_REF_TXN_NO");
    var lookup=mapper.createObjectNode().put("orgBank","760").put("orgBranch","1352").put("referenceType","FCR").put("reference",reference);
    mvc.perform(post("/api/payment-discovery/lookup").with(csrf().asHeader()).contentType("application/json").content(lookup.toString())).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/payment-discovery/lookup").with(user("analyst")).contentType("application/json").content(lookup.toString())).andExpect(status().isForbidden());
    mvc.perform(post("/api/payment-discovery/lookup").with(user("viewer")).with(csrf().asHeader()).contentType("application/json").content(lookup.toString())).andExpect(status().isForbidden());
    mvc.perform(post("/api/payment-discovery/lookup").with(user("other")).with(csrf().asHeader()).contentType("application/json").content(lookup.toString())).andExpect(status().isForbidden());
    mvc.perform(post("/api/payment-discovery/lookup").with(user("analyst")).with(csrf().asHeader()).contentType("application/json").content(lookup.toString())).andExpect(status().isOk()).andExpect(jsonPath("$.matchStatus").value("EXACT_MATCH")).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].hostSubsequences.length()").value(2));
    mvc.perform(post("/api/payment-discovery/lookup").with(user("analyst")).with(csrf().asHeader()).contentType("application/json").content(lookup.deepCopy().put("inquiryDate","2026-09-14").toString())).andExpect(status().isUnprocessableEntity());
    mvc.perform(post("/api/payment-discovery/lookup").with(user("analyst")).with(csrf().asHeader()).contentType("application/json").content(lookup.deepCopy().put("recordCount",20).toString())).andExpect(status().isUnprocessableEntity());
    mvc.perform(post("/api/payment-discovery/search").with(user("analyst")).with(csrf().asHeader()).contentType("application/json").content(mapper.readTree(request).deepCopy().toString().replace("20}","20,\"reference\":\"old-list-filter\"}"))).andExpect(status().isUnprocessableEntity());
  }
  @Test void multipartMissingFieldsAndOversizeGiveActionableClientErrors() throws Exception {
    var file=new MockMultipartFile("file","data.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",new byte[]{1});
    mvc.perform(multipart("/api/payment-discovery/uploads").file(file).with(user("analyst")).with(csrf().asHeader())).andExpect(status().isBadRequest());
    mvc.perform(multipart("/api/payment-discovery/uploads").file(file).param("orgBranch","1352").with(user("analyst")).with(csrf().asHeader())).andExpect(status().isBadRequest());
    mvc.perform(multipart("/api/payment-discovery/uploads").file(file).param("orgBank","760").with(user("analyst")).with(csrf().asHeader())).andExpect(status().isBadRequest());
    mvc.perform(multipart("/api/payment-discovery/uploads").param("orgBranch","1352").param("orgBank","760").with(user("analyst")).with(csrf().asHeader())).andExpect(status().isBadRequest());
    mvc.perform(multipart("/api/payment-discovery/uploads").file(file).param("orgBranch","1352","2468").param("orgBank","760").with(user("analyst")).with(csrf().asHeader())).andExpect(status().isUnprocessableEntity());
    mvc.perform(multipart("/api/payment-discovery/uploads").file(file).param("orgBranch","1352").param("orgBank","760","999").with(user("analyst")).with(csrf().asHeader())).andExpect(status().isUnprocessableEntity());
    mvc.perform(multipart("/api/payment-discovery/uploads").file(file).param("orgBranch","1352").param("orgBank","760").param("url","https://elsewhere.test").with(user("analyst")).with(csrf().asHeader())).andExpect(status().isUnprocessableEntity());
    mvc.perform(multipart("/api/payment-discovery/uploads").file(file).param("orgBranch","1352").param("orgBank","999").with(user("analyst")).with(csrf().asHeader())).andExpect(status().isForbidden());
    var large=new MockMultipartFile("file","large.xlsx","application/octet-stream",new byte[5*1024*1024+1]);
    mvc.perform(multipart("/api/payment-discovery/uploads").file(large).param("orgBranch","1352").param("orgBank","760").with(user("analyst")).with(csrf().asHeader())).andExpect(status().isPayloadTooLarge());
    assertThat(new ErrorHandler().uploadTooLarge(new MaxUploadSizeExceededException(5*1024*1024),new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(413);
  }
  @Test void persistedServerCandidateCreatesSeparatePrivateRegistryAndHidesOtherTenant() throws Exception {
    var search=mvc.perform(post("/api/payment-discovery/search").with(user("analyst")).with(csrf().asHeader()).contentType("application/json").content(request)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(6)).andReturn();
    String candidate=mapper.readTree(search.getResponse().getContentAsString()).path("items").get(0).path("candidateId").asText();
    String body=mapper.createObjectNode().put("candidateId",candidate).put("reason","Check supporting records").toString();
    var created=mvc.perform(post("/api/payment-cases").with(user("analyst")).with(csrf().asHeader()).header("Idempotency-Key","http-case").contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.item.evidenceStatus").value("DISCOVERY_ONLY")).andReturn();
    String id=mapper.readTree(created.getResponse().getContentAsString()).path("caseId").asText();
    mvc.perform(get("/api/payment-cases/"+id).with(user("other"))).andExpect(status().isNotFound());
    mvc.perform(get("/api/payment-cases").with(user("analyst"))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
    mvc.perform(get("/api/payment-cases/dashboard").with(user("analyst"))).andExpect(status().isOk()).andExpect(jsonPath("$.openCases").value(1));
    mvc.perform(get("/api/payment-cases").with(user("other"))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
  }
}
