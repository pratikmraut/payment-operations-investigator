package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class CaseStore implements ApplicationRunner {
  private static final Set<String> EVALUATION_LABEL_KEYS =
      Set.of("scenarioFamily", "groundTruth", "expectedOutcome");
  private final JdbcTemplate db;
  private final JsonSupport json;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;
  private final boolean importFixtures;
  private final String fixturePath;

  public CaseStore(
      JdbcTemplate db,
      JsonSupport json,
      ObjectMapper mapper,
      TransactionTemplate transactions,
      @Value("${poi.import-fixtures}") boolean importFixtures,
      @Value("${poi.fixtures}") String fixturePath) {
    this.db = db;
    this.json = json;
    this.mapper = mapper;
    this.transactions = transactions;
    this.importFixtures = importFixtures;
    this.fixturePath = fixturePath;
  }

  @Override
  public void run(ApplicationArguments arguments) throws Exception {
    if (!importFixtures) return;
    Path path = Path.of(fixturePath).toAbsolutePath().normalize();
    if (!Files.isRegularFile(path))
      throw new IllegalStateException("Synthetic case fixture file is missing: " + path);
    if (Files.size(path) > 20_000_000)
      throw new IllegalStateException("Fixture is larger than the allowed import size.");
    JsonNode fixtures = mapper.readTree(path.toFile());
    if (!fixtures.isArray()) throw new IllegalStateException("Synthetic fixture must be an array.");
    transactions.executeWithoutResult(
        tx -> {
          for (JsonNode record : fixtures) importCase(record);
        });
  }

  public void importCase(JsonNode record) {
    validateFixture(record);
    MoneyFacts.calculate((ObjectNode) record);
    String id = JsonSupport.requiredText(record, "id"),
        tenant = JsonSupport.requiredText(record, "tenantId");
    if (db.queryForObject("SELECT COUNT(*) FROM payment_case WHERE id=?", Integer.class, id) > 0)
      return;
    ObjectNode clean = record.deepCopy();
    clean.retain(
        "id",
        "tenantId",
        "paymentId",
        "title",
        "description",
        "priority",
        "status",
        "amountMinor",
        "currency",
        "rail",
        "merchant",
        "createdAt",
        "updatedAt",
        "version",
        "events",
        "ledgerEntries",
        "webhooks",
        "provider",
        "policyDate",
        "tags");
    db.update(
        "INSERT INTO payment_case(id,tenant_id,status,version,updated_at,body) VALUES(?,?,?,?,?,?)",
        id,
        tenant,
        clean.path("status").asText(),
        clean.path("version").longValue(),
        clean.path("updatedAt").asText(),
        clean.toString());
    audit(
        id,
        tenant,
        "system",
        "FIXTURE_IMPORTED",
        "Original synthetic operational fixture imported; no external funds.");
  }

  static void validateFixture(JsonNode record) {
    rejectEvaluationLabels(record);
    for (String field :
        List.of(
            "id",
            "tenantId",
            "paymentId",
            "title",
            "description",
            "priority",
            "status",
            "currency",
            "createdAt",
            "updatedAt",
            "policyDate")) JsonSupport.requiredText(record, field);
    if (!record.path("id").asText().matches("[A-Za-z0-9_-]{1,100}")
        || !record.path("tenantId").asText().matches("[A-Za-z0-9_-]{1,100}"))
      throw new ApiException(422, "INVALID_DATA", "Invalid case or tenant identifier.");
    if (!record.path("currency").asText().equals("INR"))
      throw new ApiException(
          422, "UNSUPPORTED_CURRENCY", "This demonstration supports INR fixtures.");
    if (JsonSupport.minor(record, "amountMinor") < 0)
      throw new ApiException(422, "INVALID_AMOUNT", "Case amount must be non-negative.");
    if (JsonSupport.minor(record, "version") < 1)
      throw new ApiException(422, "INVALID_DATA", "Case version must be positive.");
    for (String field : List.of("events", "ledgerEntries", "webhooks"))
      if (!record.path(field).isArray())
        throw new ApiException(422, "INVALID_DATA", field + " must be an array.");
    for (JsonNode entry : record.path("ledgerEntries")) {
      JsonSupport.minor(entry, "amountMinor");
      if (!entry.path("currency").asText().equals(record.path("currency").asText()))
        throw new ApiException(
            422, "CURRENCY_MISMATCH", "Ledger entries must match the case currency.");
    }
    if (record.path("provider").isObject())
      for (String field : List.of("amountMinor", "feeMinor", "refundMinor", "payoutMinor"))
        if (record.path("provider").hasNonNull(field))
          JsonSupport.minor(record.path("provider"), field);
  }

  private static void rejectEvaluationLabels(JsonNode node) {
    if (node.isObject()) {
      var fields = node.fields();
      while (fields.hasNext()) {
        var field = fields.next();
        if (EVALUATION_LABEL_KEYS.contains(field.getKey()))
          throw new ApiException(
              422, "LABELS_FORBIDDEN", "Operational fixtures must not contain evaluation labels.");
        rejectEvaluationLabels(field.getValue());
      }
    } else if (node.isArray()) {
      node.forEach(CaseStore::rejectEvaluationLabels);
    }
  }

  public ObjectNode caseDetail(String id, String tenant) {
    return findCase(id, tenant, false);
  }

  public ObjectNode lockCase(String id, String tenant) {
    return findCase(id, tenant, true);
  }

  private ObjectNode findCase(String id, String tenant, boolean lock) {
    List<ObjectNode> rows =
        db.query(
            "SELECT body,status,version,updated_at FROM payment_case WHERE id=? AND tenant_id=?"
                + (lock ? " FOR UPDATE" : ""),
            (rs, n) -> {
              ObjectNode result = json.object(rs.getString("body"));
              result.put("status", rs.getString("status"));
              result.put("version", rs.getLong("version"));
              result.put("updatedAt", rs.getString("updated_at"));
              result.set("reconciliation", MoneyFacts.calculate(result));
              return result;
            },
            id,
            tenant);
    if (rows.isEmpty()) throw ApiException.notFound();
    return rows.get(0);
  }

  public List<ObjectNode> cases(String tenant, String search, String status, String priority) {
    String needle = search == null ? "" : search.toLowerCase(Locale.ROOT).trim();
    return db
        .query(
            "SELECT id FROM payment_case WHERE tenant_id=? ORDER BY id",
            (rs, n) -> rs.getString(1),
            tenant)
        .stream()
        .map(id -> caseDetail(id, tenant))
        .filter(c -> status == null || status.isBlank() || c.path("status").asText().equals(status))
        .filter(
            c ->
                priority == null
                    || priority.isBlank()
                    || c.path("priority").asText().equals(priority))
        .filter(
            c ->
                needle.isBlank()
                    || (c.path("id").asText()
                            + " "
                            + c.path("paymentId").asText()
                            + " "
                            + c.path("title").asText()
                            + " "
                            + c.path("merchant").asText())
                        .toLowerCase(Locale.ROOT)
                        .contains(needle))
        .map(
            c -> {
              c.remove(List.of("events", "ledgerEntries", "webhooks", "provider"));
              return c;
            })
        .toList();
  }

  public void setStatus(String id, String tenant, String status, long nextVersion) {
    int changed =
        db.update(
            "UPDATE payment_case SET status=?,version=?,updated_at=? WHERE id=? AND tenant_id=? AND version=?",
            status,
            nextVersion,
            Instant.now().toString(),
            id,
            tenant,
            nextVersion - 1);
    if (changed != 1)
      throw new ApiException(409, "VERSION_CONFLICT", "Case changed. Refresh before continuing.");
  }

  public void insertInvestigation(ObjectNode value, String tenant, long version) {
    db.update(
        "INSERT INTO investigation(id,case_id,tenant_id,created_by,created_at,case_version,body) VALUES(?,?,?,?,?,?,?)",
        value.path("id").asText(),
        value.path("caseId").asText(),
        tenant,
        value.path("createdBy").asText(),
        value.path("createdAt").asText(),
        version,
        value.toString());
  }

  public ObjectNode investigation(String id, String tenant) {
    var rows =
        db.query(
            "SELECT body FROM investigation WHERE id=? AND tenant_id=?",
            (rs, n) -> json.object(rs.getString(1)),
            id,
            tenant);
    if (rows.isEmpty()) throw ApiException.notFound();
    return rows.get(0);
  }

  public long investigationVersion(String id, String tenant) {
    Long value =
        db.queryForObject(
            "SELECT case_version FROM investigation WHERE id=? AND tenant_id=?",
            Long.class,
            id,
            tenant);
    if (value == null) throw ApiException.notFound();
    return value;
  }

  public List<ObjectNode> investigations(String caseId, String tenant) {
    caseDetail(caseId, tenant);
    return db.query(
        "SELECT body FROM investigation WHERE case_id=? AND tenant_id=? ORDER BY created_at DESC,id DESC",
        (rs, n) -> json.object(rs.getString(1)),
        caseId,
        tenant);
  }

  public Optional<ObjectNode> replay(String tenant, String actor, String key, String hash) {
    var rows =
        db.query(
            "SELECT request_hash,body FROM review_decision WHERE tenant_id=? AND actor=? AND idempotency_key=?",
            (rs, n) -> Map.entry(rs.getString(1), json.object(rs.getString(2))),
            tenant,
            actor,
            key);
    if (rows.isEmpty()) return Optional.empty();
    if (!rows.get(0).getKey().equals(hash))
      throw new ApiException(
          409, "IDEMPOTENCY_CONFLICT", "Idempotency key was already used for a different request.");
    ObjectNode result = rows.get(0).getValue();
    result.put("replayed", true);
    return Optional.of(result);
  }

  public boolean hasDecision(String investigationId) {
    return db.queryForObject(
            "SELECT COUNT(*) FROM review_decision WHERE investigation_id=?",
            Integer.class,
            investigationId)
        > 0;
  }

  public void insertDecision(ObjectNode response, Actor actor, String key, String hash) {
    db.update(
        "INSERT INTO review_decision(id,case_id,investigation_id,tenant_id,actor,idempotency_key,request_hash,created_at,body) VALUES(?,?,?,?,?,?,?,?,?)",
        response.path("id").asText(),
        response.path("caseId").asText(),
        response.path("investigationId").asText(),
        actor.tenantId(),
        actor.id(),
        key,
        hash,
        Instant.now().toString(),
        response.toString());
  }

  public List<ObjectNode> decisions(String caseId, String tenant) {
    caseDetail(caseId, tenant);
    return db.query(
        "SELECT body FROM review_decision WHERE case_id=? AND tenant_id=? ORDER BY created_at,id",
        (rs, n) -> json.object(rs.getString(1)),
        caseId,
        tenant);
  }

  public void audit(String caseId, String tenant, String actor, String action, String detail) {
    db.update(
        "INSERT INTO audit_event(id,case_id,tenant_id,occurred_at,actor,action,detail) VALUES(?,?,?,?,?,?,?)",
        "AUD-" + UUID.randomUUID(),
        caseId,
        tenant,
        Instant.now().toString(),
        actor,
        action,
        detail);
  }

  public List<ObjectNode> auditEvents(String caseId, String tenant) {
    caseDetail(caseId, tenant);
    return db.query(
        "SELECT id,occurred_at,actor,action,detail FROM audit_event WHERE case_id=? AND tenant_id=? ORDER BY occurred_at,id",
        (rs, n) ->
            json.value(
                Map.of(
                    "id",
                    rs.getString(1),
                    "occurredAt",
                    rs.getString(2),
                    "actor",
                    rs.getString(3),
                    "action",
                    rs.getString(4),
                    "detail",
                    rs.getString(5))),
        caseId,
        tenant);
  }

  public List<ObjectNode> recentActivity(String tenant) {
    return db.query(
        "SELECT id,case_id,occurred_at,actor,action,detail FROM audit_event WHERE tenant_id=? ORDER BY occurred_at DESC,id DESC LIMIT 10",
        (rs, n) ->
            json.value(
                Map.of(
                    "id",
                    rs.getString(1),
                    "caseId",
                    rs.getString(2),
                    "occurredAt",
                    rs.getString(3),
                    "actor",
                    rs.getString(4),
                    "action",
                    rs.getString(5),
                    "detail",
                    rs.getString(6))),
        tenant);
  }
}
