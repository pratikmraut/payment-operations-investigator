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
import org.springframework.transaction.annotation.Transactional;

/** Read-only metadata index. Batched reads; all authorized metadata is filtered before paging.
 * This deliberately serves the current small local dataset, not unbounded SQL-scale pagination. */
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

  @Transactional(readOnly = true)
  public ObjectNode index(Actor actor, Map<String,String[]> parameters) {
    Filter filter = filter(parameters);
    ArrayNode scopes = (ArrayNode) cases.config(actor).path("scopes");
    authorizeFilter(scopes, filter);
    Map<String,ObjectNode> authorized = new LinkedHashMap<>();
    for(JsonNode item : cases.cases(actor,"ALL").path("items")) authorized.put(item.path("id").asText(), (ObjectNode)item);
    Map<String,Versions> versions = new HashMap<>();
    // Only summaries are selected. Disallowed case IDs are discarded before their metadata is parsed.
    db.query("SELECT id,case_id,version,summary FROM fcr_case_evidence WHERE tenant_id=?", row -> {
      String caseId = row.getString("case_id");
      if(!authorized.containsKey(caseId))return;
      ObjectNode summary = summary(row.getString("summary"), row.getString("id"), caseId, row.getInt("version"));
      Versions history = versions.computeIfAbsent(caseId, ignored -> new Versions());
      history.count++;
      if(history.latest == null || summary.path("version").intValue() > history.latest.path("version").intValue()) history.latest = summary;
    }, actor.tenantId());

    int withRows = 0, versionCount = 0;
    List<Entry> matching = new ArrayList<>();
    for(var pair : authorized.entrySet()) {
      ObjectNode savedCase = pair.getValue(); Versions history = versions.get(pair.getKey());
      ObjectNode latest = history == null ? null : history.latest;
      int count = history == null ? 0 : history.count; versionCount += count;
      String state = coverage(latest);
      if(state.equals("PARTIAL") || state.equals("ALL_GROUPS"))withRows++;
      if(!matches(savedCase, latest, state, filter))continue;
      ObjectNode item = mapper.createObjectNode().put("caseId", pair.getKey());
      if(savedCase.hasNonNull("caseNumber"))item.set("caseNumber", savedCase.get("caseNumber").deepCopy());
      for(String key : List.of("reference", "utr", "orgBank", "orgBranch", "reason", "amount", "currency", "updatedAt", "lifecycleState", "lifecycleVersion"))
        item.set(key, savedCase.path(key).deepCopy());
      item.put("versionCount", count).put("coverageState", state);
      if(latest == null)item.putNull("latestEvidence");else item.set("latestEvidence", latest.deepCopy());
      Instant sortTime = instant(latest == null ? savedCase.path("createdAt") : latest.path("createdAt"));
      matching.add(new Entry(item, sortTime));
    }
    matching.sort(Comparator.comparing(Entry::at).reversed().thenComparing(entry -> entry.item.path("caseId").asText()));
    int total = matching.size(), totalPages = Math.max(1, (int)Math.ceil(total / (double)PAGE_SIZE));
    int page = Math.min(filter.page, totalPages), start = (page - 1) * PAGE_SIZE;
    ObjectNode result = mapper.createObjectNode().put("generatedAt", Instant.now().toString()).put("page", page)
        .put("pageSize", PAGE_SIZE).put("total", total).put("totalPages", totalPages);
    result.putObject("summary").put("caseCount", authorized.size()).put("casesWithRows", withRows)
        .put("casesWithoutRows", authorized.size() - withRows).put("evidenceVersions", versionCount);
    result.set("scopes", scopes.deepCopy());
    ArrayNode items = result.putArray("items");
    for(int i = start; i < Math.min(start + PAGE_SIZE, total); i++)items.add(matching.get(i).item);
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
  private boolean matches(ObjectNode item, ObjectNode latest, String state, Filter filter) {
    if(!filter.coverage.equals("ALL") && !filter.coverage.equals(state))return false;
    if(!filter.source.equals("ALL") && (latest == null || !filter.source.equals(latest.path("sourceKind").asText())))return false;
    if(filter.bank != null && !filter.bank.equals(item.path("orgBank").asText()))return false;
    if(filter.branch != null && !filter.branch.equals(item.path("orgBranch").asText()))return false;
    String text = String.join(" ", item.path("id").asText(), item.path("caseNumber").asText(), item.path("reference").asText(),
        item.path("utr").isNull() ? "" : item.path("utr").asText(), item.path("reason").asText()).toLowerCase(Locale.ROOT);
    return filter.words.stream().allMatch(text::contains);
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
  private record Entry(ObjectNode item, Instant at) {}
  private static final class Versions { int count; ObjectNode latest; }
}
