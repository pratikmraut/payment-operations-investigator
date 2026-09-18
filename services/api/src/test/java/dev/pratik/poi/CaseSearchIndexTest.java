package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class CaseSearchIndexTest {
  final ObjectMapper mapper = new ObjectMapper();
  final Actor analyst = Actor.knownActors().get(0), reviewer = Actor.knownActors().get(1), viewer = Actor.knownActors().get(2), other = Actor.knownActors().get(3), admin = Actor.knownActors().get(4);
  JdbcTemplate db; TransactionTemplate tx; CaseNumberService numbers; PaymentDiscoveryService cases;

  @BeforeEach void setup() {
    var source = new DriverManagerDataSource("jdbc:h2:mem:case-search-"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
    db = new JdbcTemplate(source); tx = new TransactionTemplate(new DataSourceTransactionManager(source)); numbers = new CaseNumberService(db,tx);
    var client = new PaymentDiscoveryClient(mapper,"DISABLED",false,"","TEST",1,(u,b,t)->{throw new AssertionError("Search must not call a bank");});
    cases = new PaymentDiscoveryService(mapper,db,tx,client,"northstar:1352:760,northstar:2468:761,silverline:9999:999",numbers);
  }
  Map<String,String[]> query(String... pairs) { var result = new LinkedHashMap<String,String[]>(); for(int i=0;i<pairs.length;i+=2)result.put(pairs[i],new String[]{pairs[i+1]}); return result; }
  ObjectNode page(String... pairs) { return cases.searchCases(analyst,query(pairs)); }
  ObjectNode seed(String id, String tenant, String bank, String branch, String time, String reason) {
    ObjectNode item = mapper.createObjectNode().put("id",id).put("reference","000-"+id).put("utr","UTR-"+id)
        .put("orgBank",bank).put("orgBranch",branch).put("reason",reason).put("createdAt",time).put("updatedAt",time)
        .put("createdBy","analyst").put("priority","MEDIUM").put("status","OPEN").put("evidenceStatus","DISCOVERY_ONLY")
        .put("amount","900719925474099312345.007").putNull("currency");
    db.update("INSERT INTO fcr_payment_case(id,tenant_id,identity_hash,created_at,body) VALUES(?,?,?,?,?)",id,tenant,PaymentDiscoveryService.hash(id),time,item.toString());
    return item;
  }
  ObjectNode seed(String id,String time,String reason) { return seed(id,"northstar","760","1352",time,reason); }
  void rebuild() { numbers.backfill(); cases.rebuildSearch(); }
  byte[] bytes(JsonNode value) { return value.toString().getBytes(StandardCharsets.UTF_8); }
  void status(int code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status).isEqualTo(code));
  }

  @Test void publicPageIsBoundedBeforeReadingBodiesAndCountsOnlyAuthorizedScopes() {
    for(int i=0;i<23;i++) seed(String.format("C-%02d",i),Instant.parse("2026-09-16T12:00:00Z").plusSeconds(i).toString(),"Routine review");
    seed("C-DENIED","northstar","760","9999","2026-09-16T14:00:00Z","Secret other branch");
    seed("C-TENANT","silverline","999","9999","2026-09-16T14:00:00Z","Secret other tenant"); rebuild();
    // Corrupt records outside page 1 must not be loaded by the bounded query.
    db.update("UPDATE fcr_payment_case SET body='not-json' WHERE id IN ('C-00','C-DENIED','C-TENANT')");
    ObjectNode first = page();
    assertThat(first.path("total").asInt()).isEqualTo(23); assertThat(first.path("items")).hasSize(10);
    assertThat(first.path("page").asInt()).isEqualTo(1); assertThat(first.path("totalPages").asInt()).isEqualTo(3);
    assertThat(first.path("items").get(0).path("id").asText()).isEqualTo("C-22");
    assertThat(first.toString()).doesNotContain("C-DENIED","C-TENANT","Secret");
    assertThat(cases.dashboard(analyst).path("openCases").asInt()).isEqualTo(23);
    assertThat(cases.searchCases(viewer,query()).path("items")).isEqualTo(first.path("items"));
    status(503,()->page("page","3"));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_case_search",Integer.class)).isEqualTo(25);
  }

  @Test void searchUsesAllWordsLiteralWildcardsNumbersAndExactCaseScope() {
    seed("C-A","2026-09-16T12:00:00Z","Refund 100% fee_code! Bank review");
    seed("C-B","2026-09-16T12:01:00Z","Refund 100x feeXcode Bank review"); rebuild();
    assertThat(page("search","  REFUND   100%  fee_code! ").path("items").findValuesAsText("id")).containsExactly("C-A");
    assertThat(page("search","Refund absent").path("total").asInt()).isZero();
    String number=numbers.find("northstar","C-A");
    assertThat(page("search",number).path("items").get(0).path("id").asText()).isEqualTo("C-A");
    assertThat(page("reference","000-C-A","bank","760","branch","1352").path("total").asInt()).isEqualTo(1);
    status(403,()->page("bank","760","branch","2468")); // Each code is known, but this pair is not authorized.
    status(403,()->page("branch","9999"));
    ObjectNode empty=page("search","unknown","page","999");
    assertThat(empty.path("page").asInt()).isEqualTo(1); assertThat(empty.path("totalPages").asInt()).isEqualTo(1);
  }

  @Test void deterministicSortsRetainNanosecondsOffsetsAndOriginalDisplayStrings() {
    String at="2026-09-16T12:00:00.000000100Z";
    seed("C-A",at,"earlier fractional"); seed("C-B","2026-09-16T17:30:00.000000200+05:30","later fractional");
    seed("C-C","2026-09-16T12:00:00.000000200Z","same later fractional"); rebuild();
    assertThat(page().path("items").findValuesAsText("id")).containsExactly("C-B","C-C","C-A");
    assertThat(page("sort","CREATED_ASC").path("items").findValuesAsText("id")).containsExactly("C-A","C-B","C-C");
    JsonNode b=page().path("items").get(0);
    assertThat(b.path("createdAt").asText()).isEqualTo("2026-09-16T17:30:00.000000200+05:30");
    assertThat(b.path("amount").asText()).isEqualTo("900719925474099312345.007");
    assertThat(b.path("currency").isNull()).isTrue();
    assertThat(page("page","500","pageSize","2").path("items").findValuesAsText("id")).containsExactly("C-A");
    assertThat(page("sort","CASE_NUMBER_ASC").path("items").findValuesAsText("caseNumber")).isSorted();
    assertThat(page("sort","CASE_NUMBER_DESC").path("items").get(0).path("id").asText()).isEqualTo("C-C");
  }

  @Test void startupBackfillIsBatchedRepeatableAndDoesNotRewriteAuthoritativeRecords() {
    for(int i=0;i<205;i++)seed(String.format("BACKFILL-%03d",i),"2026-09-16T12:00:00Z","Preserve original body");
    List<String> before=db.queryForList("SELECT body FROM fcr_payment_case ORDER BY id",String.class);
    rebuild(); assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_case_search",Integer.class)).isEqualTo(205);
    String firstNumber=numbers.find("northstar","BACKFILL-000");
    new CaseSearchIndex(mapper,db,tx,numbers).rebuild();
    assertThat(db.queryForList("SELECT body FROM fcr_payment_case ORDER BY id",String.class)).isEqualTo(before);
    assertThat(numbers.find("northstar","BACKFILL-000")).isEqualTo(firstNumber);
    assertThat(page("pageSize","50").path("items")).hasSize(50);
    assertThat(page("pageSize","50").path("totalPages").asInt()).isEqualTo(5);
  }

  @Test void commandsKeepOwnerPriorityWorkflowEvidenceAndLifecycleFreshWithoutReadTimeRepair() {
    ObjectNode original=seed("C-FRESH","2026-09-16T12:00:00Z","Follow up evidence"); rebuild();
    String before=db.queryForObject("SELECT body FROM fcr_payment_case WHERE id='C-FRESH'",String.class);
    var evidence=new CaseEvidenceService(mapper,db,tx,cases,new CaseEvidenceClient(mapper,false,"",15,"",(u,b,h,t)->{throw new AssertionError("No bank");}));
    var management=new CaseManagementService(mapper,db,tx,cases,evidence,mock(CaseInvestigationService.class));
    management.manage(analyst,"C-FRESH",bytes(mapper.createObjectNode().put("expectedVersion",0).put("ownerId",analyst.id()).put("priority","HIGH").put("reason","Take ownership")),"search-owner-command");
    management.transition(analyst,"C-FRESH",bytes(mapper.createObjectNode().put("expectedVersion",1).put("status","INVESTIGATING").put("reason","Start source review")),"search-workflow-command");
    assertThat(page("work","MINE","search","high investigating").path("total").asInt()).isEqualTo(1);
    assertThat(page("work","OPEN").path("total").asInt()).isZero();
    assertThat(page("sort","UPDATED_DESC").path("items").get(0).path("updatedAt").asText()).isNotEqualTo(original.path("updatedAt").asText());
    assertThat(db.queryForObject("SELECT body FROM fcr_payment_case WHERE id='C-FRESH'",String.class)).isEqualTo(before);
    ObjectNode snapshot=evidence.submit(analyst,"C-FRESH",bytes(evidence.template(original)),"MANUAL","search-empty-evidence");
    var projection=db.queryForMap("SELECT * FROM fcr_case_search WHERE case_id='C-FRESH'");
    assertThat(projection.get("latest_evidence_id")).isEqualTo(snapshot.path("id").asText());
    assertThat(projection.get("coverage_state")).isEqualTo("EMPTY"); assertThat(projection.get("version_count")).isEqualTo(1);
    assertThat(page().path("items").get(0).path("evidenceStatus").asText()).isEqualTo("EMPTY_EVIDENCE_ATTACHED");
    var lifecycle=new CaseLifecycleService(mapper,db,tx,cases,numbers);
    lifecycle.command(analyst,"C-FRESH","ARCHIVED",bytes(mapper.createObjectNode().put("expectedVersion",0).put("reason","Archive fixture")),"search-archive-command");
    assertThat(page().path("total").asInt()).isZero(); assertThat(page("lifecycle","ARCHIVED").path("total").asInt()).isEqualTo(1);
    assertThat(cases.dashboard(analyst).path("openCases").asInt()).isZero();
    lifecycle.command(analyst,"C-FRESH","ACTIVE",bytes(mapper.createObjectNode().put("expectedVersion",1).put("reason","Restore fixture")),"search-restore-command");
    assertThat(page().path("items").get(0).path("lifecycleVersion").asInt()).isEqualTo(2);
    lifecycle.command(analyst,"C-FRESH","ARCHIVED",bytes(mapper.createObjectNode().put("expectedVersion",2).put("reason","Archive before removal")),"search-archive-again");
    lifecycle.command(admin,"C-FRESH","DELETED",bytes(mapper.createObjectNode().put("expectedVersion",3).put("reason","Remove fixture").put("confirmation",numbers.find("northstar","C-FRESH"))),"search-delete-command");
    assertThat(page("lifecycle","ALL").path("total").asInt()).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_case_search",Integer.class)).isZero();
    assertThat(numbers.find("northstar","C-FRESH")).isNotNull();
  }

  @Test void rejectedMutationsRollBackTheProjectionAndPublicReadsNeverRepairIt() {
    seed("C-ROLLBACK","2026-09-16T12:00:00Z","Original reason"); rebuild();
    assertThatThrownBy(()->tx.executeWithoutResult(transaction->{
      db.update("UPDATE fcr_payment_case SET body=REPLACE(body,'Original reason','Uncommitted reason') WHERE id='C-ROLLBACK'");
      cases.refreshSearch("northstar","C-ROLLBACK"); throw new IllegalStateException("rollback");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(page("search","Original").path("total").asInt()).isEqualTo(1);
    assertThat(page("search","Uncommitted").path("total").asInt()).isZero();
    db.update("DELETE FROM fcr_case_search");
    assertThat(page().path("total").asInt()).isZero(); assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_case_search",Integer.class)).isZero();
    cases.rebuildSearch(); assertThat(page().path("total").asInt()).isEqualTo(1);
  }

  @Test void snapshotCountAndPageRemainConsistentWhenAnotherConnectionInserts() throws Exception {
    seed("C-ORIGINAL","2026-09-16T12:00:00Z","Original snapshot"); rebuild();
    ExecutorService writer=Executors.newSingleThreadExecutor();
    try {
      cases.searchIndex().readSnapshot(()->{
        assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_case_search",Integer.class)).isEqualTo(1);
        Future<?> inserted=writer.submit(()->tx.executeWithoutResult(transaction->{
          seed("C-CONCURRENT","2026-09-16T12:01:00Z","Concurrent insertion");
          cases.refreshSearch("northstar","C-CONCURRENT");
        }));
        try { inserted.get(10,TimeUnit.SECONDS); }
        catch(Exception failure) { throw new AssertionError("The independent insert did not complete",failure); }
        assertThat(db.queryForList("SELECT case_id FROM fcr_case_search ORDER BY case_id",String.class)).containsExactly("C-ORIGINAL");
        return null;
      });
      assertThat(page().path("total").asInt()).isEqualTo(2);
      assertThat(page().path("items").findValuesAsText("id")).containsExactly("C-CONCURRENT","C-ORIGINAL");
    } finally { writer.shutdownNow(); }
  }

  @Test void snapshotRetriesOnlySerializationConflictsWithABoundedAttemptCount() {
    AtomicInteger attempts=new AtomicInteger();
    String result=cases.searchIndex().readSnapshot(()->{
      if(attempts.incrementAndGet()<3)throw new CannotSerializeTransactionException("Concurrent database transaction");
      return "stable page";
    });
    assertThat(result).isEqualTo("stable page"); assertThat(attempts.get()).isEqualTo(3);
    attempts.set(0);
    status(503,()->cases.searchIndex().readSnapshot(()->{
      attempts.incrementAndGet(); throw new CannotSerializeTransactionException("Persistent conflict");
    }));
    assertThat(attempts.get()).isEqualTo(3);
    attempts.set(0);
    status(400,()->cases.searchIndex().readSnapshot(()->{
      attempts.incrementAndGet(); throw CaseSearchIndex.invalid();
    }));
    assertThat(attempts.get()).isEqualTo(1);
  }

  @Test void strictQueriesRejectAmbiguousUnsafeOrUnboundedInput() {
    for(String[] pairs:List.of(new String[]{"page","0"},new String[]{"page","-1"},new String[]{"page","2147483648"},
        new String[]{"pageSize","51"},new String[]{"pageSize","0"},new String[]{"sort","created_at DESC"},
        new String[]{"work","ADMIN"},new String[]{"lifecycle","DELETED"},new String[]{"search","x".repeat(201)},
        new String[]{"search","control\u0001"},new String[]{"bank","760 OR 1=1"},new String[]{"reference"," padded "},new String[]{"tenant","silverline"}))
      status(400,()->cases.searchCases(analyst,query(pairs)));
    status(400,()->cases.searchCases(analyst,Map.of("page",new String[]{"1","2"})));
    assertThat(page().path("total").asInt()).isZero();
  }
}
