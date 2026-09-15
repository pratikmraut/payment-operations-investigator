package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class CaseLifecycleServiceTest {
  final ObjectMapper mapper=new ObjectMapper();
  final Actor analyst=Actor.knownActors().get(0),viewer=Actor.knownActors().get(2),other=Actor.knownActors().get(3),admin=Actor.knownActors().get(4);
  JdbcTemplate db;TransactionTemplate tx;PaymentDiscoveryService cases;CaseNumberService numbers;CaseLifecycleService lifecycle;
  CaseEvidenceService evidence;CaseManagementService management;PaymentDiscoveryClient discovery;
  String id,number,candidate;ObjectNode createRequest;

  @BeforeEach void setup() {
    var ds=new DriverManagerDataSource("jdbc:h2:mem:lifecycle-"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
    db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));numbers=new CaseNumberService(db,tx);
    discovery=new PaymentDiscoveryClient(mapper,"MOCK",false,"","TEST",2,(u,b,t)->{throw new AssertionError("No bank calls");});
    cases=new PaymentDiscoveryService(mapper,db,tx,discovery,"northstar:1352:760,silverline:2468:760",numbers);
    ObjectNode found=cases.search(analyst,bytes(mapper.createObjectNode().put("orgBank","760").put("orgBranch","1352").put("inquiryDate","2026-09-14").put("recordCount",1)));
    candidate=found.path("items").get(0).path("candidateId").asText();
    createRequest=mapper.createObjectNode().put("candidateId",candidate).put("reason","Original investigation reason retained on archive.");
    ObjectNode created=cases.createCase(analyst,bytes(createRequest),"initial-create");
    id=created.path("caseId").asText();number=created.path("caseNumber").asText();
    lifecycle=new CaseLifecycleService(mapper,db,tx,cases,numbers);
    evidence=new CaseEvidenceService(mapper,db,tx,cases,new CaseEvidenceClient(mapper,false,"",15,"",(u,b,h,t)->{throw new AssertionError("No bank calls");}));
    management=new CaseManagementService(mapper,db,tx,cases,evidence,mock(CaseInvestigationService.class));
  }
  byte[] bytes(JsonNode value){return value.toString().getBytes(StandardCharsets.UTF_8);}
  ObjectNode input(long version){return mapper.createObjectNode().put("expectedVersion",version).put("reason","Remove an unwanted test case from the working queue.");}
  ObjectNode command(Actor actor,String action,long version,String key){return lifecycle.command(actor,id,action,bytes(input(version)),key);}
  ObjectNode remove(long version,String key){return lifecycle.command(admin,number,"DELETED",bytes(input(version).put("confirmation",number)),key);}
  int count(String table){return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
  void status(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,int expected){assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(expected));}

  @Test void initialLifecycleIsReadOnlyAndRoleScoped() {
    ObjectNode result=lifecycle.detail(analyst,number);
    assertThat(result.path("caseId").asText()).isEqualTo(id);assertThat(result.path("state").asText()).isEqualTo("ACTIVE");
    assertThat(result.path("version").asInt()).isZero();assertThat(result.path("canArchive").asBoolean()).isTrue();
    assertThat(result.path("canDelete").asBoolean()).isFalse();assertThat(result.path("audit").isEmpty()).isTrue();
    assertThat(count("fcr_case_lifecycle")).isZero();assertThat(count("fcr_case_lifecycle_event")).isZero();
    assertThat(lifecycle.detail(viewer,id).path("canArchive").asBoolean()).isFalse();
    status(()->lifecycle.detail(other,id),404);status(()->lifecycle.detail(other,number),404);
    status(()->command(viewer,"ARCHIVED",0,"viewer-archive"),403);
    status(admin::requireWriter,403);status(admin::requireReviewer,403);
    assertThat(management.detail(admin,id).path("assignees").findValuesAsText("id")).containsExactly("analyst","reviewer");
  }

  @Test void archiveRestorePreservePayloadsNumbersAndExposeCurrentStateOnResume() {
    management.addNote(analyst,id,bytes(mapper.createObjectNode().put("expectedVersion",0).put("text","A retained note.")),"note-before-archive");
    String before=db.queryForObject("SELECT body FROM fcr_payment_case WHERE id=?",String.class,id);
    List<Map<String,Object>> beforeNotes=db.queryForList("SELECT * FROM fcr_case_management_event");
    ObjectNode archived=command(analyst,"ARCHIVED",0,"archive-first");
    assertThat(archived.path("state").asText()).isEqualTo("ARCHIVED");assertThat(archived.path("canRestore").asBoolean()).isTrue();
    assertThat(lifecycle.detail(admin,id).path("canDelete").asBoolean()).isTrue();
    assertThat(cases.cases(analyst).path("total").asInt()).isZero();
    assertThat(cases.cases(analyst,"ARCHIVED").path("items").get(0).path("caseNumber").asText()).isEqualTo(number);
    assertThat(cases.cases(analyst,"ALL").path("items").get(0).path("lifecycleVersion").asInt()).isEqualTo(1);
    assertThat(cases.caseRecord(analyst,id).has("lifecycleState")).isFalse();
    assertThat(cases.createCase(analyst,bytes(createRequest),"initial-create").path("item").path("lifecycleState").asText()).isEqualTo("ARCHIVED");
    assertThat(cases.createCase(analyst,bytes(createRequest),"resume-archived").path("status").asText()).isEqualTo("EXISTING");
    assertThat(new EvidenceLibraryService(mapper,db,cases).index(analyst,Map.of()).path("items").get(0).path("lifecycleState").asText()).isEqualTo("ARCHIVED");
    status(()->cases.requireActive(analyst,id),409);
    status(()->management.addNote(analyst,id,bytes(mapper.createObjectNode().put("expectedVersion",1).put("text","Blocked note")),"note-after-archive"),409);
    status(()->evidence.inquire(analyst,id,bytes(mapper.createObjectNode()),"inquiry-after-archive"),409);
    ObjectNode restored=command(analyst,"ACTIVE",1,"restore-first");
    assertThat(restored.path("caseNumber").asText()).isEqualTo(number);assertThat(restored.path("version").asInt()).isEqualTo(2);
    assertThat(restored.path("audit").findValuesAsText("action")).containsExactly("RESTORED","ARCHIVED");
    assertThat(cases.cases(analyst).path("total").asInt()).isEqualTo(1);
    assertThat(db.queryForObject("SELECT body FROM fcr_payment_case WHERE id=?",String.class,id)).isEqualTo(before);
    assertThat(db.queryForList("SELECT * FROM fcr_case_management_event")).isEqualTo(beforeNotes);
    assertThat(count("fcr_case_number")).isEqualTo(1);
  }

  @Test void deletionRequiresArchiveAdministratorReasonExactNumberAndCurrentVersion() {
    status(()->remove(0,"delete-too-soon"),409);
    command(analyst,"ARCHIVED",0,"archive-for-delete");
    byte[] request=bytes(input(1).put("confirmation",number));
    status(()->lifecycle.command(analyst,id,"DELETED",request,"analyst-delete"),403);
    status(()->lifecycle.command(other,id,"ARCHIVED",bytes(input(1)),"other-archive"),404);
    status(()->lifecycle.command(admin,id,"DELETED",bytes(input(1).put("confirmation","0000000000000")),"wrong-number"),422);
    status(()->lifecycle.command(admin,id,"DELETED",bytes(input(1).put("confirmation",number).put("reason"," ")),"missing-reason"),422);
    status(()->remove(0,"stale-version"),409);
    assertThat(cases.caseDetail(analyst,number).path("lifecycleState").asText()).isEqualTo("ARCHIVED");
    var rescoped=new PaymentDiscoveryService(mapper,db,tx,discovery,"northstar:999:760",numbers);
    var denied=new CaseLifecycleService(mapper,db,tx,rescoped,numbers);
    status(()->denied.command(admin,number,"DELETED",request,"rescoped-delete"),404);
    assertThat(count("fcr_case_lifecycle_event")).isEqualTo(1);
  }

  @Test void deletionPurgesCasePayloadsKeepsTombstoneAndReservationsAndCannotReplayCreation() {
    management.addNote(analyst,id,bytes(mapper.createObjectNode().put("expectedVersion",0).put("text","Sensitive test note")),"private-note");
    ObjectNode payload=evidence.template(cases.caseRecord(analyst,id));
    evidence.submit(analyst,id,bytes(payload),"JSON","empty-test-evidence");
    String now="2026-09-16T12:00:00Z";
    db.update("INSERT INTO fcr_case_investigation(id,tenant_id,case_id,evidence_id,created_at,actor_id,idempotency_key,request_hash,status,summary,body) VALUES(?,?,?,?,?,?,?,?,?,?,?)","JOB-delete","northstar",id,"EVD-test",now,"analyst","test-job","a".repeat(64),"COMPLETED","{}","{\"sensitive\":\"test answer\"}");
    db.update("INSERT INTO fcr_case_report(id,tenant_id,case_id,created_at,actor_id,idempotency_key,request_hash,report_hash,body) VALUES(?,?,?,?,?,?,?,?,?)","RPT-delete","northstar",id,now,"analyst","test-report","a".repeat(64),"b".repeat(64),"{\"sensitive\":\"test report\"}");
    List<Map<String,Object>> numbering=db.queryForList("SELECT * FROM fcr_case_number");
    command(analyst,"ARCHIVED",0,"archive-to-purge");
    ObjectNode deleted=remove(1,"permanent-delete");
    assertThat(deleted.path("state").asText()).isEqualTo("DELETED");assertThat(deleted.path("caseNumber").asText()).isEqualTo(number);
    assertThat(deleted.path("version").asLong()).isEqualTo(2L);
    // The JSON wire receipt is stable even when Jackson reloads a small long as an IntNode.
    assertThat(remove(1,"permanent-delete").toString()).isEqualTo(deleted.toString());
    assertThat(lifecycle.command(admin,id,"DELETED",bytes(input(1).put("confirmation",number)),"permanent-delete").toString()).isEqualTo(deleted.toString());
    status(()->lifecycle.command(admin,id,"DELETED",bytes(input(1).put("confirmation",number).put("reason","different")),"permanent-delete"),409);
    for(String table:List.of("fcr_case_evidence","fcr_case_evidence_command","fcr_case_investigation","fcr_case_report","fcr_case_management","fcr_case_management_command","fcr_case_management_event"))assertThat(count(table)).as(table).isZero();
    assertThat(db.queryForList("SELECT * FROM fcr_case_number")).isEqualTo(numbering);
    ObjectNode tombstone=cases.lifecycleRecord(admin,id);
    assertThat(tombstone.properties()).hasSize(3);assertThat(tombstone.has("reason")).isFalse();assertThat(tombstone.has("reference")).isFalse();
    assertThat(count("fcr_case_lifecycle_event")).isEqualTo(2);
    assertThat(cases.cases(analyst,"ALL").path("total").asInt()).isZero();
    assertThat(new EvidenceLibraryService(mapper,db,cases).index(analyst,Map.of()).path("total").asInt()).isZero();
    status(()->cases.caseDetail(analyst,id),404);status(()->cases.caseDetail(analyst,number),404);status(()->lifecycle.detail(admin,number),404);
    status(()->cases.createCase(analyst,bytes(createRequest),"initial-create"),404);
    assertThat(db.queryForObject("SELECT body FROM fcr_case_command WHERE idempotency_key='initial-create'",String.class)).doesNotContain("Original investigation reason");
    ObjectNode newCase=cases.createCase(analyst,bytes(createRequest),"new-case-after-delete");
    assertThat(newCase.path("status").asText()).isEqualTo("CREATED");
    assertThat(newCase.path("caseId").asText()).isNotEqualTo(id);
    assertThat(Long.parseLong(newCase.path("caseNumber").asText())).isEqualTo(Long.parseLong(number)+1);
    numbers.backfill();assertThat(count("fcr_case_number")).isEqualTo(2);
  }

  @Test void lifecycleRetriesCannotRepeatEventsOrOverwriteLaterStateAndRejectChangedPayload() throws Exception {
    ObjectNode archived=command(analyst,"ARCHIVED",0,"one-archive");
    assertThat(command(analyst,"ARCHIVED",0,"one-archive")).isEqualTo(archived);
    status(()->command(analyst,"ACTIVE",0,"stale-restore"),409);
    status(()->lifecycle.command(analyst,id,"ARCHIVED",bytes(input(0).put("reason","Different")),"one-archive"),409);
    command(admin,"ACTIVE",1,"admin-restore");
    assertThat(command(analyst,"ARCHIVED",0,"one-archive").path("state").asText()).isEqualTo("ACTIVE");
    assertThat(count("fcr_case_lifecycle_event")).isEqualTo(2);
    var pool=Executors.newFixedThreadPool(2);var barrier=new CyclicBarrier(2);
    try {
      List<Future<Integer>> results=new ArrayList<>();
      for(int index=0;index<2;index++){int i=index;results.add(pool.submit(()->{barrier.await();try{command(analyst,"ARCHIVED",2,"parallel-archive-"+i);return 200;}catch(ApiException e){return e.status;}}));}
      assertThat(List.of(results.get(0).get(10,TimeUnit.SECONDS),results.get(1).get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
    }finally{pool.shutdownNow();}
    assertThat(count("fcr_case_lifecycle_event")).isEqualTo(3);
  }

  @Test void invalidFiltersCommandsAndKeysDoNotChangeLifecycle() {
    status(()->cases.cases(analyst,"DELETED"),422);
    status(()->lifecycle.command(analyst,id,"ARCHIVED",bytes(input(0)),null),400);
    status(()->lifecycle.command(analyst,id,"ARCHIVED",bytes(input(0).put("extra",true)),"extra-field"),422);
    status(()->lifecycle.command(analyst,id,"ARCHIVED",bytes(input(0).put("expectedVersion",0.2)),"fractional-version"),422);
    assertThat(count("fcr_case_lifecycle")).isZero();
  }
}
