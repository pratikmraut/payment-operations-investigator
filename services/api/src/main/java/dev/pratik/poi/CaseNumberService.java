package dev.pratik.poi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Stable display numbers; original case identity and frozen evidence remain untouched. */
@Service
@DependsOnDatabaseInitialization
public class CaseNumberService {
  static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("uuuuMMdd", Locale.ROOT).withZone(ZONE);
  private static final long MAX_SEQUENCE = 99_999L;
  private final JdbcTemplate db;
  private final TransactionTemplate transactions;

  public CaseNumberService(JdbcTemplate db, TransactionTemplate transactions) {
    this.db = db;
    this.transactions = transactions;
  }

  /** Runs after schema initialization and before the dependent discovery service is exposed. */
  @PostConstruct
  void backfill() {
    transactions.executeWithoutResult(tx -> {
      lockAllocator();
      List<ExistingCase> missing = db.query("""
          SELECT c.id,c.tenant_id,c.created_at FROM fcr_payment_case c
          LEFT JOIN fcr_case_number n ON n.case_id=c.id AND n.tenant_id=c.tenant_id
          WHERE n.case_id IS NULL
          """, (rs, row) -> new ExistingCase(rs.getString(1), rs.getString(2), Instant.parse(rs.getString(3))));
      missing.sort(Comparator.comparing(ExistingCase::createdAt).thenComparing(ExistingCase::id));
      for (ExistingCase item : missing) allocateLocked(item);
    });
  }

  /** Caller must include allocation in the transaction that creates or resumes the case. */
  String assign(String tenant, String caseId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Case number allocation requires the case transaction.");
    lockAllocator();
    String previous = find(tenant, caseId);
    if (previous != null) return previous;
    List<ExistingCase> found = db.query("SELECT id,tenant_id,created_at FROM fcr_payment_case WHERE tenant_id=? AND id=?",
        (rs, row) -> new ExistingCase(rs.getString(1), rs.getString(2), Instant.parse(rs.getString(3))), tenant, caseId);
    if (found.isEmpty()) throw ApiException.notFound();
    return allocateLocked(found.get(0));
  }

  private void lockAllocator() {
    // A single persistent lock also protects first allocation of a day and multi-instance backfill.
    if (db.queryForList("SELECT id FROM fcr_case_number_mutex WHERE id=1 FOR UPDATE", Integer.class).size() != 1)
      throw new IllegalStateException("Case number allocator is not initialized.");
  }

  private String allocateLocked(ExistingCase item) {
    String day = DAY.format(item.createdAt());
    if (!day.matches("[0-9]{8}")) throw new IllegalStateException("Case creation date is outside the numbering range.");
    List<Long> stored = db.queryForList("SELECT last_value FROM fcr_case_number_counter WHERE number_date=?", Long.class, day);
    long last = stored.isEmpty() ? 0 : stored.get(0);
    if (last >= MAX_SEQUENCE) throw new ApiException(503, "CASE_NUMBER_CAPACITY", "The daily case number limit has been reached.");
    long next = last + 1;
    String number = day + String.format(Locale.ROOT, "%05d", next);
    if (stored.isEmpty()) db.update("INSERT INTO fcr_case_number_counter(number_date,last_value) VALUES(?,?)", day, next);
    else db.update("UPDATE fcr_case_number_counter SET last_value=? WHERE number_date=?", next, day);
    db.update("INSERT INTO fcr_case_number(case_id,tenant_id,case_number,number_date,sequence_no) VALUES(?,?,?,?,?)",
        item.id(), item.tenant(), number, day, next);
    return number;
  }

  String resolve(String tenant, String idOrNumber) {
    if (!idOrNumber.matches("[0-9]{13}")) return idOrNumber;
    List<String> ids = db.queryForList("SELECT case_id FROM fcr_case_number WHERE tenant_id=? AND case_number=?",
        String.class, tenant, idOrNumber);
    if (ids.isEmpty()) throw ApiException.notFound();
    return ids.get(0);
  }

  String find(String tenant, String caseId) {
    List<String> values = db.queryForList("SELECT case_number FROM fcr_case_number WHERE tenant_id=? AND case_id=?",
        String.class, tenant, caseId);
    return values.isEmpty() ? null : values.get(0);
  }

  ObjectNode decorate(String tenant, ObjectNode item) {
    ObjectNode copy = item.deepCopy();
    String number = find(tenant, copy.path("id").asText());
    if (number != null) copy.put("caseNumber", number);
    return copy;
  }

  void decorateAll(String tenant, List<ObjectNode> items) {
    Map<String,String> numbers = new HashMap<>();
    db.query("SELECT case_id,case_number FROM fcr_case_number WHERE tenant_id=?", rs -> {
      numbers.put(rs.getString(1), rs.getString(2));
    }, tenant);
    for (ObjectNode item : items) {
      String number = numbers.get(item.path("id").asText());
      if (number != null) item.put("caseNumber", number);
    }
  }

  private record ExistingCase(String id, String tenant, Instant createdAt) {}
}
