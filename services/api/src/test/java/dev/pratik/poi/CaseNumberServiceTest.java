package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class CaseNumberServiceTest {
  final ObjectMapper mapper = new ObjectMapper();
  final Actor analyst = new Actor("analyst", "Analyst", "ANALYST", "northstar");
  final Actor other = new Actor("other", "Other", "ANALYST", "silverline");
  JdbcTemplate db;
  TransactionTemplate tx;
  CaseNumberService numbers;
  PaymentDiscoveryService cases;

  @BeforeEach void setup() {
    var source = new DriverManagerDataSource("jdbc:h2:mem:numbers-" + UUID.randomUUID()
        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
    db = new JdbcTemplate(source);
    tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    numbers = new CaseNumberService(db, tx);
    var discovery = new PaymentDiscoveryClient(mapper, "MOCK", false, "", "TEST", 2,
        (url, body, timeout) -> { throw new AssertionError("No bank calls"); });
    cases = new PaymentDiscoveryService(mapper, db, tx, discovery, "northstar:1352:760,silverline:2468:760", numbers);
  }

  void seed(String id, String tenant, String createdAt) {
    ObjectNode item = mapper.createObjectNode().put("id", id).put("reference", "REF-" + id)
        .put("orgBank", "760").put("orgBranch", tenant.equals("northstar") ? "1352" : "2468")
        .put("createdAt", createdAt).put("updatedAt", createdAt).put("createdBy", "analyst")
        .put("reason", "Inspect the recorded payment").put("status", "OPEN").put("priority", "MEDIUM")
        .put("sourceKind", "EXCEL").put("evidenceStatus", "DISCOVERY_ONLY");
    db.update("INSERT INTO fcr_payment_case(id,tenant_id,identity_hash,created_at,body) VALUES(?,?,?,?,?)",
        id, tenant, PaymentDiscoveryService.hash(id), createdAt, item.toString());
  }

  String assign(String tenant, String id) { return tx.execute(status -> numbers.assign(tenant, id)); }

  @Test void backfillUsesOriginalKolkataDateAndStableInstantThenIdOrderWithoutChangingBodies() {
    seed("FCR-z", "northstar", "2026-09-15T18:30:00Z");
    seed("FCR-a", "silverline", "2026-09-15T18:30:00.000Z");
    seed("FCR-yesterday", "northstar", "2026-09-15T18:29:59Z");
    seed("FCR-later", "northstar", "2026-09-16T00:00:00Z");
    List<Map<String,Object>> before = db.queryForList("SELECT * FROM fcr_payment_case ORDER BY id");

    numbers.backfill();

    assertThat(numbers.find("northstar", "FCR-yesterday")).isEqualTo("2026091500001");
    assertThat(numbers.find("silverline", "FCR-a")).isEqualTo("2026091600001");
    assertThat(numbers.find("northstar", "FCR-z")).isEqualTo("2026091600002");
    assertThat(numbers.find("northstar", "FCR-later")).isEqualTo("2026091600003");
    assertThat(db.queryForList("SELECT * FROM fcr_payment_case ORDER BY id")).isEqualTo(before);
    List<Map<String,Object>> assigned = db.queryForList("SELECT * FROM fcr_case_number ORDER BY case_id");
    new CaseNumberService(db, tx).backfill();
    assertThat(db.queryForList("SELECT * FROM fcr_case_number ORDER BY case_id")).isEqualTo(assigned);
    assertThat(db.queryForObject("SELECT last_value FROM fcr_case_number_counter WHERE number_date='20260916'", Long.class)).isEqualTo(3L);
  }

  @Test void newDayStartsAtOneAndAResumeNeverChangesTheOriginalPrefix() {
    seed("FCR-first", "northstar", "2026-09-15T18:29:59.999999999Z");
    seed("FCR-next", "northstar", "2026-09-15T18:30:00Z");
    assertThat(assign("northstar", "FCR-first")).isEqualTo("2026091500001");
    assertThat(assign("northstar", "FCR-next")).isEqualTo("2026091600001");
    assertThat(assign("northstar", "FCR-first")).isEqualTo("2026091500001");
    assertThat(db.queryForList("SELECT last_value FROM fcr_case_number_counter ORDER BY number_date", Long.class)).containsExactly(1L, 1L);
  }

  @Test void parallelInstancesAllocateUniqueGlobalNumbersAcrossTenants() throws Exception {
    int count = 20;
    for (int i=0; i<count; i++) seed("FCR-parallel-"+i, i%2==0 ? "northstar" : "silverline", "2026-09-16T03:00:00Z");
    var executor = Executors.newFixedThreadPool(8);
    var start = new CountDownLatch(1);
    List<Future<String>> futures = new ArrayList<>();
    try {
      for (int i=0; i<count; i++) {
        int index = i;
        futures.add(executor.submit(() -> {
          start.await();
          CaseNumberService instance = new CaseNumberService(db, tx);
          return tx.execute(status -> instance.assign(index%2==0 ? "northstar" : "silverline", "FCR-parallel-"+index));
        }));
      }
      start.countDown();
      List<String> actual = new ArrayList<>();
      for (Future<String> future : futures) actual.add(future.get(20, TimeUnit.SECONDS));
      assertThat(actual).doesNotHaveDuplicates().allMatch(number -> number.matches("20260916[0-9]{5}"));
      assertThat(actual.stream().sorted().toList()).startsWith("2026091600001").endsWith("2026091600020");
      assertThat(db.queryForObject("SELECT last_value FROM fcr_case_number_counter", Long.class)).isEqualTo(20L);
    } finally { executor.shutdownNow(); }
  }

  @Test void concurrentBackfillsAndResumeAssignOnlyOneNumberPerCase() throws Exception {
    seed("FCR-once", "northstar", "2026-09-16T03:00:00Z");
    var executor = Executors.newFixedThreadPool(3);
    var start = new CountDownLatch(1);
    try {
      Future<?> a = executor.submit(() -> { start.await(); numbers.backfill(); return null; });
      Future<?> b = executor.submit(() -> { start.await(); new CaseNumberService(db, tx).backfill(); return null; });
      Future<String> c = executor.submit(() -> { start.await(); return assign("northstar", "FCR-once"); });
      start.countDown(); a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
      assertThat(c.get(10, TimeUnit.SECONDS)).isEqualTo("2026091600001");
      assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_case_number", Integer.class)).isEqualTo(1);
      assertThat(db.queryForObject("SELECT last_value FROM fcr_case_number_counter", Long.class)).isEqualTo(1L);
    } finally { executor.shutdownNow(); }
  }

  @Test void failedCaseTransactionRollsBackItsNumberAndCounterTogether() {
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
      seed("FCR-rollback", "northstar", "2026-09-16T03:00:00Z");
      assertThat(numbers.assign("northstar", "FCR-rollback")).isEqualTo("2026091600001");
      throw new IllegalStateException("Later case command failed");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_payment_case", Integer.class)).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_case_number", Integer.class)).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_case_number_counter", Integer.class)).isZero();
    seed("FCR-kept", "northstar", "2026-09-16T03:00:00Z");
    assertThat(assign("northstar", "FCR-kept")).isEqualTo("2026091600001");
    assertThatThrownBy(() -> numbers.assign("northstar", "FCR-kept")).isInstanceOf(IllegalStateException.class);
  }

  @Test void fiveDigitCapacityFailsClearlyWithoutWrappingOrAdvancingCounter() {
    seed("FCR-last", "northstar", "2026-09-16T03:00:00Z");
    seed("FCR-overflow", "northstar", "2026-09-16T03:00:00Z");
    db.update("INSERT INTO fcr_case_number_counter(number_date,last_value) VALUES('20260916',99998)");
    assertThat(assign("northstar", "FCR-last")).isEqualTo("2026091699999");
    assertThatThrownBy(() -> assign("northstar", "FCR-overflow")).isInstanceOfSatisfying(ApiException.class,
        error -> { assertThat(error.status).isEqualTo(503); assertThat(error.code).isEqualTo("CASE_NUMBER_CAPACITY"); });
    assertThat(numbers.find("northstar", "FCR-overflow")).isNull();
    assertThat(db.queryForObject("SELECT last_value FROM fcr_case_number_counter", Long.class)).isEqualTo(99999L);
  }

  @Test void displayNumberLookupRetainsTenantAndBankBranchAuthorizationAndGetIsReadOnly() {
    seed("FCR-north", "northstar", "2026-09-16T03:00:00Z");
    seed("FCR-silver", "silverline", "2026-09-16T04:00:00Z");
    numbers.backfill();
    List<Map<String,Object>> before = db.queryForList("SELECT * FROM fcr_case_number_counter");
    ObjectNode item = cases.caseDetail(analyst, "2026091600001");
    assertThat(item.path("caseNumber").isTextual()).isTrue();
    assertThat(item.path("caseNumber").asText()).isEqualTo("2026091600001");
    assertThat(item.path("id").asText()).isEqualTo("FCR-north");
    assertThat(cases.caseRecord(analyst, "FCR-north").has("caseNumber")).isFalse();
    assertThat(cases.cases(analyst).path("items").get(0).path("caseNumber").asText()).isEqualTo("2026091600001");
    for (String id : List.of("FCR-north", "2026091600001"))
      assertThatThrownBy(() -> cases.caseDetail(other, id)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(404));
    db.update("UPDATE fcr_payment_case SET body=REPLACE(body,'1352','9999') WHERE id='FCR-north'");
    assertThatThrownBy(() -> cases.caseDetail(analyst, "2026091600001")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(404));
    assertThatThrownBy(() -> cases.caseDetail(analyst, "2026091699999")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(404));
    assertThat(db.queryForList("SELECT * FROM fcr_case_number_counter")).isEqualTo(before);
  }

  @Test void createResumeAndOldReceiptReplayExposeTheSameNumberWithoutChangingStoredReceipts() {
    ObjectNode search = mapper.createObjectNode().put("orgBank", "760").put("orgBranch", "1352")
        .put("inquiryDate", "2026-09-14").put("recordCount", 20);
    String candidate = cases.search(analyst, search.toString().getBytes(StandardCharsets.UTF_8)).path("items").get(0).path("candidateId").asText();
    ObjectNode command = mapper.createObjectNode().put("candidateId", candidate).put("reason", "Review this payment");
    byte[] bytes = command.toString().getBytes(StandardCharsets.UTF_8);
    ObjectNode created = cases.createCase(analyst, bytes, "number-create");
    String id = created.path("caseId").asText();
    String expectedDay = DateTimeFormatter.ofPattern("uuuuMMdd").withZone(ZoneId.of("Asia/Kolkata"))
        .format(Instant.parse(created.path("item").path("createdAt").asText()));
    assertThat(created.path("caseNumber").asText()).isEqualTo(expectedDay + "00001");
    assertThat(created.path("item").path("caseNumber")).isEqualTo(created.path("caseNumber"));
    String bodyBefore = db.queryForObject("SELECT body FROM fcr_payment_case WHERE id=?", String.class, id);
    String receiptBefore = db.queryForObject("SELECT body FROM fcr_case_command WHERE idempotency_key='number-create'", String.class);
    assertThat(bodyBefore).doesNotContain("caseNumber");
    assertThat(receiptBefore).doesNotContain("caseNumber");
    assertThat(cases.createCase(analyst, bytes, "number-create")).isEqualTo(created);
    ObjectNode resumed = cases.createCase(analyst, bytes, "number-resume");
    assertThat(resumed.path("caseNumber")).isEqualTo(created.path("caseNumber"));
    assertThat(resumed.path("status").asText()).isEqualTo("EXISTING");
    assertThat(db.queryForObject("SELECT last_value FROM fcr_case_number_counter", Long.class)).isEqualTo(1L);
    assertThat(db.queryForObject("SELECT body FROM fcr_payment_case WHERE id=?", String.class, id)).isEqualTo(bodyBefore);
    assertThat(db.queryForObject("SELECT body FROM fcr_case_command WHERE idempotency_key='number-create'", String.class)).isEqualTo(receiptBefore);
    ObjectNode repeatedSearch = cases.search(analyst, search.toString().getBytes(StandardCharsets.UTF_8));
    assertThat(repeatedSearch.path("items").get(0).path("existingCaseNumber")).isEqualTo(created.path("caseNumber"));
  }

  @Test void legacyReceiptIsDecoratedAfterBackfillWithoutRewritingItsQuestionOrCaseBody() {
    seed("FCR-old", "northstar", "2026-09-13T19:00:00Z");
    ObjectNode raw = cases.caseRecord(analyst, "FCR-old");
    ObjectNode command = mapper.createObjectNode().put("candidateId", "CAND-retained").put("reason", "The original question");
    ObjectNode receipt = mapper.createObjectNode().put("caseId", "FCR-old").put("status", "CREATED");
    receipt.set("item", raw);
    db.update("INSERT INTO fcr_case_command(tenant_id,actor_id,idempotency_key,request_hash,body) VALUES(?,?,?,?,?)",
        "northstar", "analyst", "old-command", UatService.canonicalHash(command), receipt.toString());
    numbers.backfill();
    ObjectNode replay = cases.createCase(analyst, command.toString().getBytes(StandardCharsets.UTF_8), "old-command");
    assertThat(replay.path("caseNumber").asText()).isEqualTo("2026091400001");
    assertThat(replay.path("item").path("caseNumber")).isEqualTo(replay.path("caseNumber"));
    assertThat(db.queryForObject("SELECT body FROM fcr_case_command WHERE idempotency_key='old-command'", String.class)).isEqualTo(receipt.toString());
    assertThat(cases.caseRecord(analyst, "FCR-old")).isEqualTo(raw);
  }
}
