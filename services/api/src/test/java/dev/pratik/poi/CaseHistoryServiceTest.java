package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Synthetic metadata only. These tests never call a bank or local model. */
class CaseHistoryServiceTest {
  final ObjectMapper mapper=new ObjectMapper();
  final Actor analyst=Actor.knownActors().get(0),viewer=Actor.knownActors().get(2),other=Actor.knownActors().get(3),admin=Actor.knownActors().get(4);
  JdbcTemplate db;TransactionTemplate tx;PaymentDiscoveryService cases;CaseEvidenceService evidence;CaseHistoryService history;
  CaseManagementService management;CaseNumberService numbers;DriverManagerDataSource source;
  ObjectNode item;String id;
  @BeforeEach void setup() {
    source=new DriverManagerDataSource("jdbc:h2:mem:history-"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
    db=spy(new JdbcTemplate(source));tx=new TransactionTemplate(new DataSourceTransactionManager(source));numbers=new CaseNumberService(db,tx);
    var client=new PaymentDiscoveryClient(mapper,"MOCK",false,"","TEST",2,(u,b,t)->{throw new AssertionError("No bank call");});
    cases=new PaymentDiscoveryService(mapper,db,tx,client,"northstar:1352:760,silverline:2468:760",numbers);
    var found=cases.search(analyst,bytes(mapper.createObjectNode().put("orgBank","760").put("orgBranch","1352").put("inquiryDate","2026-09-14").put("recordCount",2)));
    var created=cases.createCase(analyst,bytes(mapper.createObjectNode().put("candidateId",found.path("items").get(0).path("candidateId").asText()).put("reason","Original case history test")),"history-case-create");
    item=(ObjectNode)created.path("item");id=item.path("id").asText();
    evidence=new CaseEvidenceService(mapper,db,tx,cases,new CaseEvidenceClient(mapper,false,"",15,"",(u,b,h,t)->{throw new AssertionError("No inquiry call");}));
    management=new CaseManagementService(mapper,db,tx,cases,evidence,mock(CaseInvestigationService.class));
    history=new CaseHistoryService(mapper,db,tx,cases);
  }
  byte[] bytes(JsonNode value){return value.toString().getBytes(StandardCharsets.UTF_8);}
  ObjectNode version(int n){return evidence.submit(analyst,id,bytes(evidence.template(item)),"JSON","history-evidence-"+n);}
  Map<String,String[]> query(String... pairs){Map<String,String[]> result=new LinkedHashMap<>();for(int n=0;n<pairs.length;n+=2)result.put(pairs[n],new String[]{pairs[n+1]});return result;}
  ObjectNode job(String jobId,ObjectNode version,String at,String status) {
    ObjectNode summary=mapper.createObjectNode().put("id",jobId).put("caseId",id).put("evidenceId",version.path("id").asText())
        .put("evidenceVersion",version.path("version").asInt()).put("evidenceHash",version.path("evidenceHash").asText())
        .put("createdAt",at).put("createdBy","analyst").put("question","Original synthetic question "+jobId).put("status",status);
    if(status.equals("COMPLETED"))summary.put("startedAt",Instant.parse(at).plusSeconds(1).toString()).put("finishedAt",Instant.parse(at).plusSeconds(2).toString());
    tx.executeWithoutResult(s->{db.update("INSERT INTO fcr_case_investigation(id,tenant_id,case_id,evidence_id,created_at,actor_id,idempotency_key,request_hash,status,summary,body) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        jobId,analyst.tenantId(),id,version.path("id").asText(),at,analyst.id(),jobId,"a".repeat(64),status,summary.toString(),"{\"original\":\"preserve exact source\"}");CaseHistoryIndex.recordJob(mapper,db,analyst.tenantId(),summary);});
    return summary;
  }
  List<String> ids(JsonNode page){List<String> result=new ArrayList<>();page.path("items").forEach(v->result.add(v.path("id").asText()));return result;}
  void status(int expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(expected));}

  @Test void evidencePagesAreBoundedStableAndOldVersionsHavePointSummaries() {
    List<ObjectNode> saved=new ArrayList<>();for(int n=1;n<=27;n++)saved.add(version(n));
    var original=db.queryForList("SELECT id,summary,body FROM fcr_case_evidence ORDER BY version");
    ObjectNode first=history.evidence(viewer,id,Map.of());assertThat(first.path("items").size()).isEqualTo(10);assertThat(first.path("total").asInt()).isEqualTo(27);
    assertThat(first.path("items").get(0).path("version").asInt()).isEqualTo(27);assertThat(first.path("limit").asInt()).isEqualTo(10);
    version(28); // A new head cannot cause duplicates or skip an older item behind the cursor.
    ObjectNode second=history.evidence(viewer,id,query("cursor",first.path("nextCursor").asText()));
    assertThat(second.path("items").get(0).path("version").asInt()).isEqualTo(17);
    ObjectNode third=history.evidence(viewer,id,query("cursor",second.path("nextCursor").asText()));
    List<String> all=new ArrayList<>(ids(first));all.addAll(ids(second));all.addAll(ids(third));
    assertThat(all).hasSize(27).doesNotHaveDuplicates();assertThat(third.path("nextCursor").isNull()).isTrue();
    ObjectNode old=history.evidenceSummary(viewer,id,saved.get(0).path("id").asText());
    assertThat(old.path("version").asInt()).isEqualTo(1);assertThat(old.has("payload")).isFalse();assertThat(old.has("upstream")).isFalse();
    assertThat(db.queryForList("SELECT id,summary,body FROM fcr_case_evidence WHERE version<=27 ORDER BY version")).isEqualTo(original);
    clearInvocations(db);history.evidence(viewer,id,query("limit","3"));
    assertThat(mockingDetails(db).getInvocations().stream().map(i->i.getArguments()).filter(a->a.length>0&&a[0] instanceof String).map(a->(String)a[0]).toList())
        .noneMatch(sql->sql.contains("SELECT body FROM fcr_case_evidence"))
        .anyMatch(sql->sql.contains("origin.summary")&&sql.contains("LIMIT ?"));
  }
  @Test void jobPagesUseNanosecondPositionsAndFilterBeforePaging() {
    ObjectNode a=version(1),b=version(2);
    job("CIN-A",a,"2030-01-01T10:00:00Z","COMPLETED");
    job("CIN-B",a,"2030-01-01T10:00:00.000000001Z","COMPLETED");
    job("CIN-C",a,"2030-01-01T10:00:00.000000001Z","COMPLETED");
    job("CIN-D",b,"2030-01-01T10:00:01Z","COMPLETED");
    job("CIN-E",a,"2030-01-01T10:00:02Z","FAILED");
    var filters=query("limit","1","evidenceId",a.path("id").asText(),"status","COMPLETED");
    ObjectNode first=history.investigations(viewer,id,filters);assertThat(ids(first)).containsExactly("CIN-C");assertThat(first.path("total").asInt()).isEqualTo(3);
    filters.put("cursor",new String[]{first.path("nextCursor").asText()});ObjectNode second=history.investigations(viewer,id,filters);assertThat(ids(second)).containsExactly("CIN-B");
    filters.put("cursor",new String[]{second.path("nextCursor").asText()});ObjectNode last=history.investigations(viewer,id,filters);assertThat(ids(last)).containsExactly("CIN-A");assertThat(last.path("nextCursor").isNull()).isTrue();
    assertThat(first.toString()).doesNotContain("preserve exact source");
    status(409,()->history.investigations(viewer,id,query("cursor","CIN-D","evidenceId",a.path("id").asText())));
  }
  @Test void activeJobsAreRetainedEvenOutsideTheFirstWorkbenchPage() {
    ObjectNode evidence=version(1);job("CIN-OLDER-ACTIVE",evidence,"2030-01-01T00:00:00Z","RUNNING");
    for(int n=1;n<=22;n++)job("CIN-NEWER-"+n,evidence,Instant.parse("2030-01-01T00:00:00Z").plusSeconds(n).toString(),"COMPLETED");
    ObjectNode work=history.workbench(viewer,id);
    assertThat(work.path("investigations").size()).isEqualTo(10);assertThat(work.path("investigationPage").path("total").asInt()).isEqualTo(23);
    assertThat(work.path("activeInvestigations").findValuesAsText("id")).containsExactly("CIN-OLDER-ACTIVE");
    assertThat(work.path("activeInvestigationPage").path("total").asInt()).isEqualTo(1);assertThat(work.path("audit").size()).isEqualTo(10);
  }
  @Test void cursorValidationScopeAndReadOnlyRolesNeverExposeOtherCaseHistories() {
    String evidenceId=version(1).path("id").asText();
    for(Actor actor:List.of(viewer,admin))assertThat(history.evidence(actor,id,Map.of()).path("total").asInt()).isEqualTo(1);
    status(404,()->history.evidence(other,id,Map.of()));status(404,()->history.evidenceSummary(other,id,evidenceId));
    status(404,()->history.investigations(viewer,id,query("evidenceId","EVD-OTHER-CASE")));
    status(409,()->history.evidence(viewer,id,query("cursor","EVD-OTHER-CASE")));
    for(Map<String,String[]> bad:List.of(query("limit","0"),query("limit","26"),query("limit","-1"),query("limit","1.0"),query("cursor",""),query("cursor","../other"),query("extra","x"),Map.of("limit",new String[]{"1","2"})))
      status(400,()->history.evidence(viewer,id,bad));
    status(400,()->history.investigations(viewer,id,query("status","unknown")));
  }
  @Test void mergedActivityPagesIncludeManagementAndKeepInternalReportViewComplete() {
    version(1);
    for(int n=0;n<14;n++)management.addNote(analyst,id,bytes(mapper.createObjectNode().put("expectedVersion",n).put("text","Preserved note "+n)),"history-note-"+n);
    ObjectNode complete=management.detail(viewer,id),publicView=CaseManagementService.publicView(complete);
    assertThat(complete.path("audit").size()).isEqualTo(14);assertThat(publicView.path("audit").size()).isEqualTo(10);
    assertThat(publicView.path("notes")).isEqualTo(complete.path("notes"));assertThat(publicView.path("auditPage").path("total").asInt()).isEqualTo(14);
    ObjectNode first=history.activity(viewer,id,Map.of());assertThat(first.path("total").asInt()).isEqualTo(16);
    ObjectNode next=history.activity(viewer,id,query("cursor",first.path("nextCursor").asText()));
    List<String> all=new ArrayList<>(ids(first));all.addAll(ids(next));assertThat(all).hasSize(16).doesNotHaveDuplicates();
    assertThat(first.path("items").findValuesAsText("action")).contains("NOTE_ADDED");
    assertThat(next.path("items").findValuesAsText("action")).contains("CASE_OPENED","EVIDENCE_ATTACHED");
    assertThat(complete.path("audit").size()).isEqualTo(14); // Public truncation never mutates report input.
  }
  @Test void backfillIsIdempotentBatchedAndPreservesEveryOriginalByte() {
    ObjectNode evidence=version(1);for(int n=0;n<112;n++)job("CIN-BACKFILL-"+n,evidence,Instant.parse("2030-01-01T00:00:00Z").plusSeconds(n).toString(),"COMPLETED");
    Map<String,List<Map<String,Object>>> before=new LinkedHashMap<>();for(String table:List.of("fcr_payment_case","fcr_case_evidence","fcr_case_investigation"))before.put(table,db.queryForList("SELECT * FROM "+table+" ORDER BY id"));
    db.update("DELETE FROM fcr_case_history_item");clearInvocations(db);
    CaseHistoryIndex index=new CaseHistoryIndex(mapper,db,tx);index.rebuild();index.rebuild();
    assertThat(history.investigations(viewer,id,Map.of()).path("total").asInt()).isEqualTo(112);
    assertThat(history.activity(viewer,id,query("limit","25")).path("total").asInt()).isEqualTo(338);
    before.forEach((table,records)->assertThat(db.queryForList("SELECT * FROM "+table+" ORDER BY id")).isEqualTo(records));
    assertThat(mockingDetails(db).getInvocations().stream().map(i->i.getArguments()).filter(a->a.length>0&&a[0] instanceof String).map(a->(String)a[0]).toList())
        .filteredOn(sql->sql.contains("summary AS stored_value FROM fcr_case_investigation")).allMatch(sql->sql.contains("id>? ORDER BY id LIMIT ?"));
  }
  @Test void lifecycleActivityAndDeletionKeepTombstoneButPurgeHistory() {
    version(1);CaseLifecycleService lifecycle=new CaseLifecycleService(mapper,db,tx,cases,numbers);
    lifecycle.command(analyst,id,"ARCHIVED",bytes(mapper.createObjectNode().put("expectedVersion",0).put("reason","Original archive reason")),"history-archive");
    assertThat(history.activity(viewer,id,Map.of()).path("items").findValuesAsText("action")).contains("CASE_ARCHIVED");
    String number=cases.caseDetail(viewer,id).path("caseNumber").asText();
    lifecycle.command(admin,id,"DELETED",bytes(mapper.createObjectNode().put("expectedVersion",1).put("reason","Original remove reason").put("confirmation",number)),"history-delete");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_case_history_item WHERE case_id=?",Integer.class,id)).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_payment_case WHERE id=?",Integer.class,id)).isEqualTo(1);
    new CaseHistoryIndex(mapper,db,tx).rebuild();assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_case_history_item WHERE case_id=?",Integer.class,id)).isZero();
    status(404,()->history.activity(viewer,id,Map.of()));
  }
}
