package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only library backed by the scoped case search projection; only page bodies are read. */
@Service
public class EvidenceLibraryService {
  static final int PAGE_SIZE = 10;
  private static final Set<String> QUERY_KEYS = Set.of("search", "coverage", "source", "bank", "branch", "page");
  private static final Set<String> COVERAGE = Set.of("ALL", "NO_EVIDENCE", "EMPTY", "PARTIAL", "ALL_GROUPS");
  private static final Set<String> SOURCES = Set.of("ALL", "BANK_API", "EXCEL", "JSON", "MANUAL");
  private static final Set<String> SUMMARY_KEYS = Set.of("id", "caseId", "version", "sourceKind", "dataClassification",
      "createdAt", "createdBy", "coverage", "warnings", "evidenceHash");
  private final ObjectMapper mapper;
  private final JdbcTemplate db;
  private final PaymentDiscoveryService cases;

  public EvidenceLibraryService(ObjectMapper mapper, JdbcTemplate db, PaymentDiscoveryService cases) {
    this.mapper = mapper; this.db = db; this.cases = cases;
  }

  public ObjectNode index(Actor actor, Map<String,String[]> parameters) {
    Filter filter = filter(parameters);
    ArrayNode scopes = (ArrayNode) cases.config(actor).path("scopes");
    authorizeFilter(scopes, filter);
    return cases.searchIndex().readSnapshot(() -> page(actor, filter, scopes));
  }

  private ObjectNode page(Actor actor, Filter filter, ArrayNode scopes) {
    CaseSearchIndex.SqlWhere authorized = CaseSearchIndex.scope(actor, cases.authorizedScopes(actor), null, null);
    String authorizedFrom = " FROM fcr_case_search s WHERE " + authorized.sql();
    Long errors = db.queryForObject("SELECT COUNT(*)" + authorizedFrom + " AND s.metadata_error=TRUE",
        Long.class, authorized.args().toArray());
    if(errors != null && errors > 0)throw storage();
    // Global cards retain their meaning: all authorized, non-deleted cases before UI filters.
    ObjectNode totals = db.queryForObject("SELECT COUNT(*) AS cases, COALESCE(SUM(CASE WHEN s.row_count>0 THEN 1 ELSE 0 END),0) AS with_rows, "
        + "COALESCE(SUM(s.version_count),0) AS versions" + authorizedFrom, (rs, row) -> {
          long count = rs.getLong("cases"), withRows = rs.getLong("with_rows");
          return mapper.createObjectNode().put("caseCount", count).put("casesWithRows", withRows)
              .put("casesWithoutRows", count - withRows).put("evidenceVersions", rs.getLong("versions"));
        }, authorized.args().toArray());
    CaseSearchIndex.SqlWhere matched = CaseSearchIndex.addTerms(
        CaseSearchIndex.scope(actor, cases.authorizedScopes(actor), filter.bank, filter.branch),
        String.join(" ", filter.words), "s.library_search_text");
    String where = matched.sql(); List<Object> arguments = new ArrayList<>(matched.args());
    if(!filter.coverage.equals("ALL")) { where += " AND s.coverage_state=?"; arguments.add(filter.coverage); }
    if(!filter.source.equals("ALL")) { where += " AND s.latest_source=?"; arguments.add(filter.source); }
    long total = db.queryForObject("SELECT COUNT(*) FROM fcr_case_search s WHERE " + where, Long.class, arguments.toArray());
    long totalPages = Math.max(1L, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    long page = Math.min((long)filter.page, totalPages);
    ObjectNode result = mapper.createObjectNode().put("generatedAt", Instant.now().toString()).put("page", page)
        .put("pageSize", PAGE_SIZE).put("total", total).put("totalPages", totalPages);
    result.set("summary", totals); result.set("scopes", scopes.deepCopy());
    ArrayNode items = result.putArray("items");
    arguments.add(PAGE_SIZE); arguments.add((page - 1) * PAGE_SIZE);
    db.query("SELECT c.body,s.*,e.summary AS evidence_summary FROM fcr_case_search s "
        + "JOIN fcr_payment_case c ON c.tenant_id=s.tenant_id AND c.id=s.case_id "
        + "LEFT JOIN fcr_case_evidence e ON e.tenant_id=s.tenant_id AND e.case_id=s.case_id AND e.id=s.latest_evidence_id "
        + "WHERE " + where + " ORDER BY s.evidence_sort_at DESC,s.evidence_sort_nanos DESC,s.case_id ASC LIMIT ? OFFSET ?", row -> {
      ObjectNode savedCase = cases.searchIndex().caseItem(row);
      String caseId = row.getString("case_id"), latestId = row.getString("latest_evidence_id");
      ObjectNode latest = latestId == null ? null : summary(row.getString("evidence_summary"), latestId, caseId, row.getInt("latest_evidence_version"));
      if(!coverage(latest).equals(row.getString("coverage_state")))throw storage();
      ObjectNode item = mapper.createObjectNode().put("caseId", caseId);
      if(savedCase.hasNonNull("caseNumber"))item.set("caseNumber", savedCase.get("caseNumber").deepCopy());
      for(String key : List.of("reference", "utr", "orgBank", "orgBranch", "reason", "amount", "currency", "updatedAt", "lifecycleState", "lifecycleVersion"))
        item.set(key, savedCase.path(key).deepCopy());
      if(savedCase.has("evidenceCurrency"))item.set("evidenceCurrency",savedCase.get("evidenceCurrency").deepCopy());
      item.put("versionCount", row.getLong("version_count")).put("coverageState", row.getString("coverage_state"));
      if(latest == null)item.putNull("latestEvidence");else item.set("latestEvidence", latest);
      items.add(item);
    }, arguments.toArray());
    return result;
  }

  private Filter filter(Map<String,String[]> input) {
    if(input == null)throw invalid();
    for(var entry : input.entrySet())
      if(!QUERY_KEYS.contains(entry.getKey()) || entry.getValue() == null || entry.getValue().length != 1 || entry.getValue()[0] == null)throw invalid();
    String search = value(input, "search", "");
    if(search.length() > 200 || search.codePoints().anyMatch(Character::isISOControl))throw invalid();
    String coverage = value(input, "coverage", "ALL"), source = value(input, "source", "ALL");
    if(!COVERAGE.contains(coverage) || !SOURCES.contains(source))throw invalid();
    String bank = value(input, "bank", null), branch = value(input, "branch", null);
    if((bank != null && !bank.matches("[0-9]{1,10}")) || (branch != null && !branch.matches("[0-9]{1,10}")))throw invalid();
    String pageText = value(input, "page", "1");
    if(!pageText.matches("[1-9][0-9]{0,9}"))throw invalid();
    int page;
    try { page = Integer.parseInt(pageText); } catch(NumberFormatException failure) { throw invalid(); }
    String normalized = search.strip().toLowerCase(Locale.ROOT);
    return new Filter(normalized.isEmpty() ? List.of() : Arrays.asList(normalized.split("\\s+")), coverage, source, bank, branch, page);
  }
  private String value(Map<String,String[]> input, String key, String fallback) { return input.containsKey(key) ? input.get(key)[0] : fallback; }
  private void authorizeFilter(ArrayNode scopes, Filter filter) {
    if(filter.bank == null && filter.branch == null)return;
    for(JsonNode scope : scopes)
      if((filter.bank == null || filter.bank.equals(scope.path("orgBank").asText()))
          && (filter.branch == null || filter.branch.equals(scope.path("orgBranch").asText())))return;
    throw new ApiException(403, "EVIDENCE_SCOPE_FORBIDDEN", "The selected bank or branch filter is outside your authorized scopes.");
  }
  private ObjectNode summary(String raw, String id, String caseId, int version) {
    if(raw == null || raw.length() > 65536)throw storage();
    ObjectNode result = UatService.parseObject(mapper, raw.getBytes(StandardCharsets.UTF_8), storage());
    Set<String> keys = new HashSet<>(); result.fieldNames().forEachRemaining(keys::add);
    if(!keys.equals(SUMMARY_KEYS) || !result.path("id").asText().equals(id) || !result.path("caseId").asText().equals(caseId)
        || !result.path("version").isIntegralNumber() || result.path("version").intValue() != version || version < 1
        || !SOURCES.contains(result.path("sourceKind").asText()) || result.path("sourceKind").asText().equals("ALL"))throw storage();
    instant(result.path("createdAt")); coverage(result);
    return result;
  }
  private String coverage(ObjectNode summary) {
    if(summary == null)return "NO_EVIDENCE";
    JsonNode groups = summary.path("coverage");
    if(!groups.isObject() || groups.size() != CaseEvidenceSchema.COLUMNS.size())throw storage();
    int nonEmpty = 0;
    for(String group : CaseEvidenceSchema.COLUMNS.keySet()) {
      JsonNode count = groups.path(group).path("rowCount");
      if(!count.isIntegralNumber() || !count.canConvertToInt() || count.intValue() < 0 || count.intValue() > 500)throw storage();
      if(count.intValue() > 0)nonEmpty++;
    }
    return nonEmpty == 0 ? "EMPTY" : nonEmpty == 4 ? "ALL_GROUPS" : "PARTIAL";
  }
  private Instant instant(JsonNode value) {
    try { if(!value.isTextual())throw new IllegalArgumentException(); return Instant.parse(value.textValue()); }
    catch(RuntimeException failure) { throw storage(); }
  }
  private static ApiException invalid() { return new ApiException(400, "INVALID_EVIDENCE_QUERY", "Use one value per supported evidence filter, at most 200 search characters, exact bank/branch digits and a positive integer page."); }
  private static ApiException storage() { return new ApiException(503, "EVIDENCE_LIBRARY_UNAVAILABLE", "Saved evidence metadata could not be read or verified."); }
  private record Filter(List<String> words, String coverage, String source, String bank, String branch, int page) {}
}
