package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Rebuildable relational read model; original cases, evidence and audit records stay authoritative. */
@Service
@DependsOnDatabaseInitialization
public class CaseSearchIndex {
  private static final Set<String> SOURCES = Set.of("BANK_API", "EXCEL", "JSON", "MANUAL");
  private static final Set<String> SUMMARY_KEYS = Set.of("id", "caseId", "version", "sourceKind", "dataClassification",
      "createdAt", "createdBy", "coverage", "warnings", "evidenceHash");
  private static final int BACKFILL_BATCH = 100;
  private final ObjectMapper mapper;
  private final JdbcTemplate db;
  private final TransactionTemplate writeTx, readTx;

  public CaseSearchIndex(ObjectMapper mapper, JdbcTemplate db, TransactionTemplate transactions, CaseNumberService numbers) {
    // Dependency on numbers ensures its initial backfill finishes before this bean's PostConstruct.
    this.mapper = mapper; this.db = db; this.writeTx = transactions;
    this.readTx = new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager()));
    this.readTx.setReadOnly(true);
    // H2 REPEATABLE_READ permits phantoms. Counts, page rows and library totals need one stable snapshot.
    this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Startup and explicit repair only. Reads never trigger reconciliation or mutation. */
  @PostConstruct
  public void rebuild() {
    String after = "";
    while (true) {
      List<Map<String,Object>> batch = db.queryForList(
          "SELECT id,tenant_id FROM fcr_payment_case WHERE id>? ORDER BY id LIMIT ?", after, BACKFILL_BATCH);
      if (batch.isEmpty()) return;
      for (Map<String,Object> item : batch) {
        String id = (String)item.get("id"), tenant = (String)item.get("tenant_id");
        writeTx.executeWithoutResult(status -> refresh(tenant, id));
      }
      after = (String)batch.get(batch.size()-1).get("id");
    }
  }

  /** Called after a mutation inside its existing shared-case transaction. */
  void refresh(String tenant, String id) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Case search refresh requires the case transaction.");
    List<String> bodies = db.queryForList("SELECT body FROM fcr_payment_case WHERE tenant_id=? AND id=? FOR UPDATE", String.class, tenant, id);
    if (bodies.isEmpty()) { db.update("DELETE FROM fcr_case_search WHERE tenant_id=? AND case_id=?", tenant, id); return; }
    var lifecycle = CaseLifecycleState.load(db, tenant, id);
    if (lifecycle.state().equals("DELETED")) { db.update("DELETE FROM fcr_case_search WHERE tenant_id=? AND case_id=?", tenant, id); return; }
    ObjectNode original = object(bodies.get(0));
    if (!id.equals(original.path("id").asText())) throw storage();
    ObjectNode managed = CaseManagementState.load(mapper, db, tenant, original);
    ObjectNode item = CaseManagementState.overlay(mapper, tenant, original, managed);
    List<String> numbers = db.queryForList("SELECT case_number FROM fcr_case_number WHERE tenant_id=? AND case_id=?", String.class, tenant, id);
    String number = numbers.isEmpty() ? null : numbers.get(0);
    Instant created = instant(item.path("createdAt")), updated = instant(item.path("updatedAt"));
    EvidenceMetadata evidence = new EvidenceMetadata(created);
    // Stream summaries; only the latest snapshot may be read below for optional currency provenance.
    db.query("SELECT id,version,created_at,summary FROM fcr_case_evidence WHERE tenant_id=? AND case_id=? ORDER BY version", row -> {
      evidence.count++;
      int version = row.getInt("version");
      evidence.id = row.getString("id"); evidence.version = version;
      try {
        ObjectNode summary = verifiedSummary(row.getString("summary"), evidence.id, id, version);
        evidence.summary = summary;
        evidence.source = summary.path("sourceKind").asText(); evidence.at = instant(summary.path("createdAt"));
        int populated = 0, rows = 0;
        for (String group : CaseEvidenceSchema.COLUMNS.keySet()) {
          int count = summary.path("coverage").path(group).path("rowCount").intValue();
          rows += count; if (count > 0) populated++;
        }
        evidence.rows = rows;
        evidence.coverage = populated == 0 ? "EMPTY" : populated == 4 ? "ALL_GROUPS" : "PARTIAL";
      } catch (RuntimeException invalid) {
        // Preserve scoped visibility of corruption without making another tenant's data block reads.
        evidence.invalid = true; evidence.source = null; evidence.coverage = "UNKNOWN"; evidence.rows = 0; evidence.summary = null;
        try { evidence.at = Instant.parse(row.getString("created_at")); } catch (RuntimeException ignored) { evidence.at = created; }
      }
    }, tenant, id);
    ObjectNode currency = null;
    if(EvidenceCurrencyProjection.missingCurrency(original) && evidence.summary != null) {
      List<String> latest = db.queryForList("SELECT body FROM fcr_case_evidence WHERE tenant_id=? AND case_id=? AND id=? AND version=? LIMIT 1",
          String.class,tenant,id,evidence.id,evidence.version);
      if(!latest.isEmpty())currency=EvidenceCurrencyProjection.derive(mapper,original,evidence.summary,latest.get(0));
    }
    String priority = item.path("priority").asText(), status = item.path("status").asText();
    String ownerId = item.path("owner").path("id").asText(null), ownerName = item.path("owner").path("name").asText(null);
    String libraryText = String.join("\n", id, Objects.toString(number, ""), item.path("reference").asText(),
        item.path("utr").asText(""), item.path("reason").asText()).toLowerCase(Locale.ROOT);
    var fields = new LinkedHashMap<String,Object>();
    fields.put("case_id", id); fields.put("tenant_id", tenant);
    fields.put("org_bank", item.path("orgBank").asText()); fields.put("org_branch", item.path("orgBranch").asText());
    fields.put("payment_reference", item.path("reference").asText()); fields.put("case_number", number);
    fields.put("owner_id", ownerId); fields.put("owner_name", ownerName);
    fields.put("priority", priority); fields.put("priority_rank", switch(priority) { case "CRITICAL" -> 4; case "HIGH" -> 3; case "MEDIUM" -> 2; default -> 1; });
    fields.put("workflow_status", status); fields.put("lifecycle_state", lifecycle.state());
    fields.put("lifecycle_version", lifecycle.version()); fields.put("management_version", managed.path("version").asLong());
    // PostgreSQL has microsecond precision. Whole seconds + nanos preserve exact Instant ordering on both databases.
    fields.put("created_at", created.truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC)); fields.put("created_nanos", created.getNano());
    fields.put("updated_at", updated.truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC)); fields.put("updated_nanos", updated.getNano());
    fields.put("updated_at_text", item.path("updatedAt").asText()); fields.put("evidence_status", item.path("evidenceStatus").asText("DISCOVERY_ONLY"));
    fields.put("latest_evidence_id", evidence.id); fields.put("latest_evidence_version", evidence.version); fields.put("latest_source", evidence.source);
    fields.put("coverage_state", evidence.coverage); fields.put("version_count", evidence.count); fields.put("row_count", evidence.rows);
    fields.put("evidence_sort_at", evidence.at.truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC)); fields.put("evidence_sort_nanos", evidence.at.getNano());
    fields.put("metadata_error", evidence.invalid);
    fields.put("evidence_currency", currency == null ? null : currency.toString());
    fields.put("library_search_text", libraryText);
    fields.put("search_text", String.join("\n", libraryText, Objects.toString(ownerId,""), Objects.toString(ownerName,""), priority, status.replace('_',' ')).toLowerCase(Locale.ROOT));
    List<Object> args = new ArrayList<>(fields.values()); args.add(tenant); args.add(id);
    String assignments = String.join(",", fields.keySet().stream().map(key -> key + "=?").toList());
    if (db.update("UPDATE fcr_case_search SET " + assignments + " WHERE tenant_id=? AND case_id=?", args.toArray()) == 0)
      db.update("INSERT INTO fcr_case_search(" + String.join(",", fields.keySet()) + ") VALUES(" + String.join(",", Collections.nCopies(fields.size(), "?")) + ")", fields.values().toArray());
  }

  ObjectNode verifiedSummary(String raw, String id, String caseId, int version) {
    if (raw == null || raw.length() > 65536) throw storage();
    ObjectNode value = object(raw); Set<String> keys = new HashSet<>(); value.fieldNames().forEachRemaining(keys::add);
    if (!keys.equals(SUMMARY_KEYS) || !id.equals(value.path("id").asText()) || !caseId.equals(value.path("caseId").asText())
        || !value.path("version").isIntegralNumber() || !value.path("version").canConvertToInt() || value.path("version").asInt() != version || version < 1
        || !SOURCES.contains(value.path("sourceKind").asText())) throw storage();
    instant(value.path("createdAt"));
    JsonNode groups = value.path("coverage");
    if (!groups.isObject() || groups.size() != CaseEvidenceSchema.COLUMNS.size()) throw storage();
    for (String group : CaseEvidenceSchema.COLUMNS.keySet()) {
      JsonNode count = groups.path(group).path("rowCount");
      if (!count.isIntegralNumber() || !count.canConvertToInt() || count.intValue() < 0 || count.intValue() > 500) throw storage();
    }
    return value;
  }

  <T> T readSnapshot(Supplier<T> work) {
    for (int attempt = 0; attempt < 3; attempt++) {
      try { return readTx.execute(status -> work.get()); }
      catch (CannotSerializeTransactionException conflict) {
        if (attempt == 2) throw new ApiException(503, "CASE_SEARCH_BUSY", "Saved cases changed while this page was being read. Refresh to try again.");
      }
    }
    throw new IllegalStateException("Unreachable read retry state");
  }

  record SqlWhere(String sql, List<Object> args) {
    SqlWhere { args = List.copyOf(args); }
    SqlWhere and(String condition, Object... values) {
      var parameters = new ArrayList<>(args); Collections.addAll(parameters, values);
      return new SqlWhere(sql + " AND (" + condition + ")", parameters);
    }
  }

  static SqlWhere scope(Actor actor, List<PaymentDiscoveryService.Scope> scopes, String bank, String branch) {
    if ((bank != null && !bank.matches("[0-9]{1,10}")) || (branch != null && !branch.matches("[0-9]{1,10}"))) throw invalid();
    List<PaymentDiscoveryService.Scope> allowed = scopes.stream().filter(scope -> (bank == null || bank.equals(scope.bank()))
        && (branch == null || branch.equals(scope.branch()))).toList();
    if ((bank != null || branch != null) && allowed.isEmpty())
      throw new ApiException(403, "DISCOVERY_SCOPE_FORBIDDEN", "The selected bank and branch combination is outside your authorized scopes.");
    List<Object> args = new ArrayList<>(List.of(actor.tenantId()));
    List<String> pairs = new ArrayList<>();
    for (var scope : allowed) { pairs.add("(s.org_bank=? AND s.org_branch=?)"); args.add(scope.bank()); args.add(scope.branch()); }
    return new SqlWhere("s.tenant_id=? AND s.lifecycle_state<>'DELETED' AND (" + (pairs.isEmpty() ? "1=0" : String.join(" OR ", pairs)) + ")", args);
  }

  static SqlWhere addTerms(SqlWhere where, String search, String column) {
    if (!Set.of("s.search_text", "s.library_search_text").contains(column)) throw new IllegalArgumentException("Unsupported search column");
    String normalized = normalizeSearch(search);
    if (!normalized.isEmpty()) for (String term : normalized.split("\\s+"))
      where = where.and(column + " LIKE ? ESCAPE '!'", "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
    return where;
  }
  static String normalizeSearch(String value) {
    if (value == null || value.length() > 200 || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
    return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  ObjectNode caseItem(ResultSet row) throws SQLException {
    ObjectNode item = object(row.getString("body"));
    item.put("priority", row.getString("priority")).put("status", row.getString("workflow_status"))
        .put("managementVersion", row.getLong("management_version")).put("lifecycleState", row.getString("lifecycle_state"))
        .put("lifecycleVersion", row.getLong("lifecycle_version")).put("updatedAt", row.getString("updated_at_text"));
    String number = row.getString("case_number"); if (number != null) item.put("caseNumber", number);
    String owner = row.getString("owner_id");
    if (owner == null) item.putNull("owner"); else item.putObject("owner").put("id", owner).put("name", row.getString("owner_name"));
    EvidenceCurrencyProjection.attach(mapper,item,row.getString("evidence_currency"),row.getString("latest_evidence_id"),row.getInt("latest_evidence_version"),row.getString("latest_source"));
    return item;
  }

  /** Caller has already authorized the case; only its small saved projection is read. */
  ObjectNode decorateEvidenceCurrency(String tenant,ObjectNode item) {
    item.remove("evidenceCurrency");
    db.query("SELECT evidence_currency,latest_evidence_id,latest_evidence_version,latest_source FROM fcr_case_search WHERE tenant_id=? AND case_id=?",
        (org.springframework.jdbc.core.RowCallbackHandler) row -> {
          EvidenceCurrencyProjection.attach(mapper,item,row.getString("evidence_currency"),row.getString("latest_evidence_id"),row.getInt("latest_evidence_version"),row.getString("latest_source"));
        },tenant,item.path("id").asText());
    return item;
  }

  private ObjectNode object(String raw) { return UatService.parseObject(mapper, raw.getBytes(StandardCharsets.UTF_8), storage()); }
  private static Instant instant(JsonNode value) {
    try { if (!value.isTextual()) throw new IllegalArgumentException(); return Instant.parse(value.textValue()); }
    catch (RuntimeException failure) { throw storage(); }
  }
  static ApiException invalid() { return new ApiException(400, "INVALID_CASE_QUERY", "Use one supported filter value, at most 200 search characters, authorized bank/branch codes and a positive page with 1–50 records."); }
  private static ApiException storage() { return new ApiException(503, "CASE_SEARCH_UNAVAILABLE", "Saved case search metadata could not be read or verified."); }
  private static final class EvidenceMetadata {
    String id, source, coverage = "NO_EVIDENCE"; int count, version, rows; boolean invalid; Instant at; ObjectNode summary;
    EvidenceMetadata(Instant created) { at = created; }
  }
}
