package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"poi.import-fixtures=false", "spring.datasource.url=jdbc:h2:mem:evidence-library-http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "poi.payment-discovery.mode=DISABLED"})
@AutoConfigureMockMvc
class EvidenceLibraryControllerTest {
  @Autowired MockMvc mvc; @Autowired JdbcTemplate db;
  @BeforeEach void clean() { db.update("DELETE FROM fcr_case_evidence"); db.update("DELETE FROM fcr_case_number"); db.update("DELETE FROM fcr_payment_case"); }

  @Test void requiresSessionButAllowsViewerReadWithoutCsrf() throws Exception {
    mvc.perform(get("/api/evidences")).andExpect(status().isUnauthorized());
    for(String identity : List.of("analyst", "reviewer", "viewer", "other"))
      mvc.perform(get("/api/evidences").with(user(identity))).andExpect(status().isOk())
          .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.pageSize").value(10))
          .andExpect(jsonPath("$.totalPages").value(1)).andExpect(jsonPath("$.summary.caseCount").value(0));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_case_evidence", Integer.class)).isZero();
  }

  @Test void rejectsUnknownAndDuplicateQueryValuesInsteadOfSilentlySelectingOne() throws Exception {
    mvc.perform(get("/api/evidences").with(user("viewer")).param("page", "1", "2"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EVIDENCE_QUERY"));
    mvc.perform(get("/api/evidences").with(user("viewer")).param("search", "one", "two")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/evidences").with(user("viewer")).param("limit", "100")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/evidences").with(user("viewer")).param("source", "BANK_API").param("coverage", "PARTIAL")
        .param("bank", "760").param("branch", "1352").param("page", "99").param("search", "Original test"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(1));
  }

  @Test void deniesUnconfiguredBankBranchPairsWithoutReturningCounts() throws Exception {
    mvc.perform(get("/api/evidences").with(user("viewer")).param("bank", "999"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.summary").doesNotExist()).andExpect(jsonPath("$.scopes").doesNotExist());
    mvc.perform(get("/api/evidences").with(user("other")).param("branch", "1352"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.items").doesNotExist());
    mvc.perform(get("/api/evidences").with(user("other")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.scopes[0].orgBranch").value("2468"));
  }
}
