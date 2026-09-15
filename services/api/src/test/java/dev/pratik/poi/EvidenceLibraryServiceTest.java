package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Original synthetic metadata only; no UAT fixture, source payload, bank call or model call. */
class EvidenceLibraryServiceTest {
  final ObjectMapper mapper = new ObjectMapper();
  final Actor analyst = new Actor("analyst", "Analyst", "ANALYST", "northstar");
  final Actor viewer = new Actor("viewer", "Viewer", "VIEWER", "northstar");
  final Actor other = new Actor("other", "Other", "ANALYST", "silverline");
  JdbcTemplate db; PaymentDiscoveryService cases; EvidenceLibraryService service;

  @BeforeEach void setup() {
    var source = new DriverManagerDataSource("jdbc:h2:mem:evidence-library-" + UUID.randomUUID()
        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
    db = spy(new JdbcTemplate(source));
    var discovery = new PaymentDiscoveryClient(mapper, "DISABLED", false, "", "TEST", 1,
        (u,b,t) -> { throw new AssertionError("Evidence library cannot call discovery API"); });
    cases = spy(new PaymentDiscoveryService(mapper, db, new TransactionTemplate(new DataSourceTransactionManager(source)), discovery,
        "northstar:1352:760,northstar:2468:760,northstar:1352:761,silverline:9999:999"));
    service = new EvidenceLibraryService(mapper, db, cases);
  }
  Map<String,String[]> query(String... values) {
    Map<String,String[]> result = new LinkedHashMap<>();
    for(int i = 0; i < values.length; i += 2)result.put(values[i], new String[]{values[i + 1]});
    return result;
  }
  ObjectNode index(String... values) { return service.index(analyst, query(values)); }
  ObjectNode savedCase(String id, String bank, String branch, String tenant, String at) {
    ObjectNode item = mapper.createObjectNode().put("id", id).put("reference", "00012345678901234567890123456789012345-" + id)
        .putNull("utr").put("orgBank", bank).put("orgBranch", branch).put("reason", "Original synthetic evidence review")
        .put("amount", "900719925474099312345.007").putNull("currency").put("createdAt", at).put("updatedAt", at)
        .put("status", "OPEN").put("priority", "MEDIUM").put("createdBy", "analyst").put("evidenceStatus", "DISCOVERY_ONLY");
    db.update("INSERT INTO fcr_payment_case(id,tenant_id,identity_hash,created_at,body) VALUES(?,?,?,?,?)",
        id, tenant, PaymentDiscoveryService.hash(id), at, item.toString());
    return item;
  }
  ObjectNode savedCase(String id) { return savedCase(id, "760", "1352", "northstar", "2026-09-14T01:00:00Z"); }
  void replaceCase(ObjectNode item) { db.update("UPDATE fcr_payment_case SET body=? WHERE id=?", item.toString(), item.path("id").asText()); }
  ObjectNode evidence(String caseId, String tenant, int version, String kind, String at, int... counts) {
    String id = "EVD-" + caseId + "-" + version;
    ObjectNode summary = mapper.createObjectNode().put("id", id).put("caseId", caseId).put("version", version)
        .put("sourceKind", kind).put("dataClassification", "PRIVATE_EVIDENCE").put("createdAt", at)
        .put("createdBy", "analyst").put("evidenceHash", "a".repeat(64));
    ObjectNode coverage = summary.putObject("coverage"); int i = 0;
    for(String group : CaseEvidenceSchema.COLUMNS.keySet())coverage.putObject(group).put("rowCount", counts[i++])
        .put("completion", kind.equals("BANK_API") ? "COMPLETE" : "UNVERIFIED");
    summary.putArray("warnings").add("Original synthetic metadata; group presence does not establish payment outcome.");
    db.update("INSERT INTO fcr_case_evidence(id,tenant_id,case_id,version,created_at,summary,body) VALUES(?,?,?,?,?,?,?)",
        id, tenant, caseId, version, at, summary.toString(), "{\"payload\":\"SOURCE-PAYLOAD-MUST-NOT-LEAK\"}");
    return summary;
  }
  ObjectNode evidence(String id, int version, String kind, int... counts) {
    return evidence(id, "northstar", version, kind, "2026-09-14T02:00:00Z", counts);
  }
  void status(int expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
    assertThatThrownBy(operation).isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.status).isEqualTo(expected));
  }
  List<String> ids(JsonNode output) { List<String> result = new ArrayList<>(); output.path("items").forEach(item -> result.add(item.path("caseId").asText())); return result; }

  @Test void emptyLibraryReturnsOneEmptyPageAndAllAuthorizedChoicesToAViewer() {
    ObjectNode result = service.index(viewer, Map.of());
    assertThat(result.path("page").asInt()).isEqualTo(1); assertThat(result.path("pageSize").asInt()).isEqualTo(10);
    assertThat(result.path("total").asInt()).isZero(); assertThat(result.path("totalPages").asInt()).isEqualTo(1);
    assertThat(result.path("items")).isEmpty(); assertThat(result.path("scopes").size()).isEqualTo(3);
    assertThat(result.path("summary")).isEqualTo(mapper.createObjectNode().put("caseCount",0).put("casesWithRows",0).put("casesWithoutRows",0).put("evidenceVersions",0));
    assertThat(Instant.parse(result.path("generatedAt").asText())).isBeforeOrEqualTo(Instant.now());
  }

  @Test void exposesAndSearchesCaseNumberWithoutReplacingInternalIdentity() {
    ObjectNode record = savedCase("NUMBERED");
    record.put("caseNumber", "2026091600001");
    ObjectNode list = mapper.createObjectNode().put("total", 1);
    list.putArray("items").add(record);
    doReturn(list).when(cases).cases(analyst,"ALL");
    JsonNode result = index("search", "2026091600001");
    assertThat(ids(result)).containsExactly("NUMBERED");
    assertThat(result.path("items").get(0).path("caseNumber").asText()).isEqualTo("2026091600001");
    assertThat(result.path("items").get(0).path("reference")).isEqualTo(record.path("reference"));
    assertThat(ids(index("search", "NUMBERED"))).containsExactly("NUMBERED");
    assertThat(index("search", "2026091600002").path("total").asInt()).isZero();
    assertThat(db.queryForObject("SELECT body FROM fcr_payment_case WHERE id='NUMBERED'", String.class))
        .doesNotContain("caseNumber");
  }

  @Test void distinguishesMissingEmptyPartialAndAllGroupsWithoutCallingThemCompleteOutcomes() {
    for(String id : List.of("NONE", "EMPTY", "PARTIAL", "ALL"))savedCase(id);
    evidence("EMPTY", 1, "JSON", 0,0,0,0); evidence("PARTIAL", 1, "EXCEL", 1,2,0,0);
    ObjectNode all = evidence("ALL", 1, "MANUAL", 1,2,3,1);
    ObjectNode result = index(); Map<String,JsonNode> rows = new HashMap<>(); result.path("items").forEach(row -> rows.put(row.path("caseId").asText(), row));
    assertThat(rows.get("NONE").path("coverageState").asText()).isEqualTo("NO_EVIDENCE");
    assertThat(rows.get("NONE").path("latestEvidence").isNull()).isTrue();
    assertThat(rows.get("EMPTY").path("coverageState").asText()).isEqualTo("EMPTY");
    assertThat(rows.get("PARTIAL").path("coverageState").asText()).isEqualTo("PARTIAL");
    assertThat(rows.get("ALL").path("coverageState").asText()).isEqualTo("ALL_GROUPS");
    assertThat(rows.get("ALL").path("latestEvidence")).isEqualTo(all);
    assertThat(result.path("summary")).isEqualTo(mapper.createObjectNode().put("caseCount",4).put("casesWithRows",2).put("casesWithoutRows",2).put("evidenceVersions",3));
    assertThat(result.toString()).doesNotContain("SOURCE-PAYLOAD-MUST-NOT-LEAK", "\"payload\"", "RESOLVED");
    assertThat(rows.get("PARTIAL").path("amount").asText()).isEqualTo("900719925474099312345.007");
    assertThat(rows.get("PARTIAL").path("utr").isNull()).isTrue(); assertThat(rows.get("PARTIAL").path("currency").isNull()).isTrue();
    assertThat(rows.get("PARTIAL").path("reference").asText()).startsWith("00012345678901234567890123456789012345");
  }

  @Test void latestVersionRatherThanLatestTimestampDeterminesCoverageSourceAndCounts() {
    savedCase("VERSIONED");
    evidence("VERSIONED", "northstar", 1, "EXCEL", "2026-09-14T04:00:00Z", 1,1,1,1);
    ObjectNode latest = evidence("VERSIONED", "northstar", 2, "JSON", "2026-09-14T03:00:00Z", 0,0,0,0);
    ObjectNode result = index(); JsonNode item = result.path("items").get(0);
    assertThat(item.path("versionCount").asInt()).isEqualTo(2); assertThat(item.path("latestEvidence")).isEqualTo(latest);
    assertThat(item.path("coverageState").asText()).isEqualTo("EMPTY");
    assertThat(result.path("summary").path("casesWithRows").asInt()).isZero();
    assertThat(index("source", "EXCEL").path("total").asInt()).isZero();
    assertThat(index("source", "JSON", "coverage", "EMPTY").path("total").asInt()).isEqualTo(1);
  }

  @Test void scopeAndTenantRestrictRowsCountsAndVersionsBeforeAnyFilters() {
    savedCase("VISIBLE"); evidence("VISIBLE", 1, "JSON", 1,0,0,0);
    savedCase("OTHER-TENANT", "999", "9999", "silverline", "2026-09-14T05:00:00Z");
    evidence("OTHER-TENANT", "silverline", 1, "JSON", "2026-09-14T05:00:00Z", 1,1,1,1);
    savedCase("DISALLOWED-BRANCH", "760", "9999", "northstar", "2026-09-14T06:00:00Z");
    ObjectNode hidden = evidence("DISALLOWED-BRANCH", 1, "JSON", 1,1,1,1);
    db.update("UPDATE fcr_case_evidence SET summary=? WHERE id=?", "not even valid JSON", hidden.path("id").asText());
    ObjectNode result = service.index(viewer, Map.of());
    assertThat(ids(result)).containsExactly("VISIBLE"); assertThat(result.path("summary").path("caseCount").asInt()).isEqualTo(1);
    assertThat(result.path("summary").path("evidenceVersions").asInt()).isEqualTo(1);
    assertThat(result.toString()).doesNotContain("OTHER-TENANT", "DISALLOWED-BRANCH", "9999");
    assertThat(ids(service.index(other, Map.of()))).containsExactly("OTHER-TENANT");
    ObjectNode noScope = service.index(new Actor("unconfigured", "Unconfigured", "VIEWER", "unknown"), Map.of());
    assertThat(noScope.path("total").asInt()).isZero(); assertThat(noScope.path("summary").path("evidenceVersions").asInt()).isZero();
  }

  @Test void filtersSupportExactBankBranchPairsIncludingTheSameBranchAcrossBanks() {
    savedCase("BANK-A"); savedCase("BANK-B", "761", "1352", "northstar", "2026-09-14T01:00:00Z");
    savedCase("BRANCH-B", "760", "2468", "northstar", "2026-09-14T01:00:00Z");
    assertThat(ids(index("bank", "761", "branch", "1352"))).containsExactly("BANK-B");
    assertThat(ids(index("branch", "1352"))).containsExactly("BANK-A", "BANK-B");
    assertThat(ids(index("bank", "760"))).containsExactly("BANK-A", "BRANCH-B");
    for(Map<String,String[]> bad : List.of(query("bank", "999"), query("branch", "9999"), query("bank", "761", "branch", "2468")))
      status(403, () -> service.index(analyst, bad));
    assertThat(index("bank", "761").path("summary").path("caseCount").asInt()).isEqualTo(3);
    assertThat(index("bank", "761").path("scopes").size()).isEqualTo(3);
  }

  @Test void allSearchWordsMatchAcrossAllowedFieldsBeforePaginationAndPreserveGlobalSummary() {
    for(int i = 0; i < 22; i++)savedCase("CASE-" + String.format("%02d", i));
    ObjectNode special = savedCase("SPECIAL-ID"); special.put("reference", "000987654321001234567890")
        .put("utr", "TEST-UTR-ALPHA").put("reason", "Inspect Waiting\nreported by operations"); replaceCase(special);
    assertThat(ids(index("search", "ALPHA waiting special-ID 000987"))).containsExactly("SPECIAL-ID");
    assertThat(index("search", "ALPHA waiting special-ID 000987").path("summary").path("caseCount").asInt()).isEqualTo(23);
    assertThat(index("search", "ALPHA absent").path("total").asInt()).isZero();
    assertThat(index("search", "  ").path("total").asInt()).isEqualTo(23);
    assertThat(index("search", "900719925474099312345.007").path("total").asInt()).isZero();
  }

  @Test void fixedPaginationClampsAfterFilteringAndUsesStableInstantOrdering() {
    for(int i = 0; i < 22; i++)savedCase(String.format("CASE-%02d", i));
    ObjectNode first = index(), last = index("page", "999");
    assertThat(first.path("items").size()).isEqualTo(10); assertThat(first.path("totalPages").asInt()).isEqualTo(3);
    assertThat(ids(first)).startsWith("CASE-00", "CASE-01");
    assertThat(last.path("page").asInt()).isEqualTo(3); assertThat(ids(last)).containsExactly("CASE-20", "CASE-21");
    ObjectNode empty = index("search", "no-such-source", "page", "2147483647");
    assertThat(empty.path("page").asInt()).isEqualTo(1); assertThat(empty.path("totalPages").asInt()).isEqualTo(1);
    assertThat(empty.path("items")).isEmpty();
    evidence("CASE-21", "northstar", 1, "JSON", "2026-09-14T08:00:00+05:30", 1,0,0,0);
    evidence("CASE-20", "northstar", 1, "JSON", "2026-09-14T03:00:00Z", 1,0,0,0);
    assertThat(ids(index())).startsWith("CASE-20", "CASE-21", "CASE-00");
    assertThat(ids(index("coverage", "PARTIAL", "page", "20"))).containsExactly("CASE-20", "CASE-21");
    assertThat(index("coverage", "PARTIAL", "page", "20").path("page").asInt()).isEqualTo(1);
  }

  @Test void everyCoverageAndSourceFilterUsesTheLatestSavedSummary() {
    savedCase("NONE"); savedCase("BANK"); savedCase("EXCEL"); savedCase("JSON"); savedCase("MANUAL");
    evidence("BANK", 1, "BANK_API", 1,1,1,1); evidence("EXCEL", 1, "EXCEL", 1,0,0,0);
    evidence("JSON", 1, "JSON", 0,0,0,0); evidence("MANUAL", 1, "MANUAL", 0,0,1,0);
    assertThat(ids(index("coverage", "NO_EVIDENCE"))).containsExactly("NONE");
    assertThat(ids(index("coverage", "EMPTY"))).containsExactly("JSON");
    assertThat(ids(index("coverage", "PARTIAL"))).containsExactly("EXCEL", "MANUAL");
    assertThat(ids(index("coverage", "ALL_GROUPS"))).containsExactly("BANK");
    for(String source : List.of("BANK_API", "EXCEL", "JSON", "MANUAL")) {
      assertThat(ids(index("source", source))).containsExactly(source.equals("BANK_API") ? "BANK" : source);
      assertThat(index("source", source).path("summary").path("caseCount").asInt()).isEqualTo(5);
    }
    assertThat(index("source", "EXCEL", "coverage", "EMPTY").path("total").asInt()).isZero();
  }

  @Test void unknownDuplicateAndUnboundedFiltersFailBeforeAnyMetadataRead() {
    List<Map<String,String[]>> bad = new ArrayList<>();
    for(String[] entry : List.of(new String[]{"unknown", "x"}, new String[]{"page", "0"}, new String[]{"page", "-1"},
        new String[]{"page", "1.5"}, new String[]{"page", "2147483648"}, new String[]{"page", "99999999999999999999"},
        new String[]{"bank", ""}, new String[]{"bank", " 760"}, new String[]{"branch", "1352 OR 1=1"},
        new String[]{"branch", "12345678901"}, new String[]{"coverage", "COMPLETE"}, new String[]{"source", "MOCK"},
        new String[]{"search", "x".repeat(201)}, new String[]{"search", "secret\nvalue"}))bad.add(query(entry));
    bad.add(Map.of("page", new String[]{"1", "2"})); bad.add(Map.of("search", new String[]{}));
    clearInvocations(cases, db);
    for(Map<String,String[]> query : bad)status(400, () -> service.index(analyst, query));
    verifyNoInteractions(cases, db);
  }

  @Test void corruptAuthorizedSummaryFailsClosedWithoutReturningAPayloadOrMisleadingCounts() {
    savedCase("BAD"); ObjectNode original = evidence("BAD", 1, "JSON", 1,0,0,0);
    for(Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(s -> s.put("payload", "must not leak"),
        s -> s.put("caseId", "OTHER"), s -> s.put("version", 2), s -> s.put("createdAt", "invalid"),
        s -> s.put("sourceKind", "MOCK"), s -> ((ObjectNode)s.path("coverage").path("PAYMENT")).put("rowCount", -1),
        s -> ((ObjectNode)s.path("coverage").path("HOST")).put("rowCount", 0.5))) {
      ObjectNode changed = original.deepCopy(); mutation.accept(changed);
      db.update("UPDATE fcr_case_evidence SET summary=? WHERE id=?", changed.toString(), original.path("id").asText());
      status(503, () -> index());
    }
  }

  @Test void metadataBatchAvoidsNPlusOneAndIndexDoesNotMutateAnyStoredRecords() {
    for(int i = 0; i < 12; i++) { savedCase("READ-ONLY-" + i); evidence("READ-ONLY-" + i, 1, "JSON", 1,0,0,0); }
    List<Map<String,Object>> beforeCases = db.queryForList("SELECT * FROM fcr_payment_case ORDER BY id");
    List<Map<String,Object>> beforeEvidence = db.queryForList("SELECT * FROM fcr_case_evidence ORDER BY id");
    clearInvocations(db, cases); ObjectNode result = service.index(viewer, query("page", "2"));
    assertThat(result.path("items").size()).isEqualTo(2);
    verify(cases, times(1)).cases(viewer,"ALL"); verify(cases, times(1)).config(viewer);
    verify(cases, never()).caseDetail(any(), anyString());
    verify(db, times(1)).query(eq("SELECT id,case_id,version,summary FROM fcr_case_evidence WHERE tenant_id=?"), any(RowCallbackHandler.class), eq("northstar"));
    assertThat(db.queryForList("SELECT * FROM fcr_payment_case ORDER BY id")).isEqualTo(beforeCases);
    assertThat(db.queryForList("SELECT * FROM fcr_case_evidence ORDER BY id")).isEqualTo(beforeEvidence);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_case_investigation", Integer.class)).isZero();
  }
}
