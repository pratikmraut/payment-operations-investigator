package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Controlled races use isolated original records and mocked bank/model transports only. */
class CaseLifecycleRaceTest {
  final ObjectMapper mapper=new ObjectMapper();
  final Actor analyst=new Actor("analyst","Analyst","ANALYST","northstar");
  final Actor admin=new Actor("admin","Administrator","ADMIN","northstar");
  JdbcTemplate db; TransactionTemplate tx; PaymentDiscoveryService cases; CaseEvidenceService evidence;
  CaseLifecycleService lifecycle; ObjectNode item,snapshot; String caseId,caseNumber;

  @BeforeEach void setup() {
    var source=new DriverManagerDataSource("jdbc:h2:mem:lifecycle-race-"+UUID.randomUUID()
        +";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
    db=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
    var discovery=new PaymentDiscoveryClient(mapper,"MOCK",false,"","ISOLATED-LIFECYCLE",2,
        (u,b,t)->{throw new AssertionError("No bank calls");});
    cases=new PaymentDiscoveryService(mapper,db,tx,discovery,"northstar:1352:760");
    ObjectNode found=cases.search(analyst,bytes(mapper.createObjectNode().put("orgBank","760").put("orgBranch","1352")
        .put("inquiryDate","2026-09-14").put("recordCount",10)));
    ObjectNode created=cases.createCase(analyst,bytes(mapper.createObjectNode()
        .put("candidateId",found.path("items").get(0).path("candidateId").asText()).put("reason","Original race-test case")),"race-case-created");
    caseId=created.path("caseId").asText();caseNumber=created.path("caseNumber").asText();item=cases.caseRecord(analyst,caseId);
    evidence=new CaseEvidenceService(mapper,db,tx,cases,mock(CaseEvidenceClient.class));
    snapshot=evidence.submit(analyst,caseId,bytes(payload()),"JSON","race-initial-evidence");
    lifecycle=new CaseLifecycleService(mapper,db,tx,cases,new CaseNumberService(db,tx));
  }

  byte[] bytes(JsonNode value){return value.toString().getBytes(StandardCharsets.UTF_8);}
  ObjectNode payload() {
    ObjectNode payload=evidence.template(item);
    ObjectNode row=((ArrayNode)payload.path("sections").path("PAYMENT").path("rows")).addObject();
    CaseEvidenceSchema.COLUMNS.get("PAYMENT").forEach(column->row.put(column,""));
    row.put("REFTXNNUMBER",item.path("reference").asText()).put("SOURCE_TABLE","PM_NEFT_TXN_LOG")
        .put("SCOPE_ROW_COUNT","1").put("NUMAMOUNT_4038","123.007");
    return payload;
  }
  ObjectNode inquiryResponse() {
    ObjectNode response=payload();ObjectNode acquisition=response.putObject("acquisition");
    for(String group:CaseEvidenceSchema.COLUMNS.keySet()) acquisition.putObject(group)
        .put("reference",item.path("reference").asText()).put("orgBank","760").put("orgBranch","1352")
        .put("returnCode","0").put("fetchCompleted",true).put("observedAt","2026-09-14T12:30:00+05:30")
        .put("rowCount",response.path("sections").path(group).path("rows").size()).put("hasMore",false);
    return response;
  }
  ObjectNode archive(){return lifecycle.command(analyst,caseNumber,"ARCHIVED",bytes(command(0)),"race-archive-case");}
  ObjectNode command(long version){return mapper.createObjectNode().put("expectedVersion",version).put("reason","Original controlled lifecycle validation");}
  ObjectNode question(){return mapper.createObjectNode().put("question","What remains unknown?")
      .put("evidenceId",snapshot.path("id").asText()).put("evidenceHash",snapshot.path("evidenceHash").asText());}
  void await(CountDownLatch latch)throws InterruptedException {assertThat(latch.await(10,TimeUnit.SECONDS)).isTrue();}
  int count(String table){return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
  void failed(Future<?> future,int expected)throws Exception {
    assertThatThrownBy(()->future.get(10,TimeUnit.SECONDS)).isInstanceOfSatisfying(ExecutionException.class,
        exception->assertThat(exception.getCause()).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.status).isEqualTo(expected)));
  }

  @Test void inquiryAlreadyInFlightCannotAttachEvidenceAfterArchive()throws Exception {
    var fetched=new CountDownLatch(1);var release=new CountDownLatch(1);
    CaseEvidenceClient client=mock(CaseEvidenceClient.class);
    when(client.fetchResult(anyString(),anyString(),anyString())).thenAnswer(call->{fetched.countDown();await(release);return new CaseEvidenceClient.Result(inquiryResponse(),null);});
    var api=new CaseEvidenceService(mapper,db,tx,cases,client);
    var pool=Executors.newSingleThreadExecutor();
    String originalBody=db.queryForObject("SELECT body FROM fcr_payment_case WHERE id=?",String.class,caseId);
    try {
      Future<?> save=pool.submit(()->api.inquire(analyst,caseId,bytes(mapper.createObjectNode()),"race-inflight-inquiry"));
      await(fetched);archive();release.countDown();failed(save,409);
      assertThat(count("fcr_case_evidence")).isEqualTo(1);
      assertThat(evidence.detail(analyst,caseId,snapshot.path("id").asText())).isEqualTo(snapshot);
      assertThat(db.queryForObject("SELECT body FROM fcr_payment_case WHERE id=?",String.class,caseId)).isEqualTo(originalBody);
      assertThat(cases.caseDetail(analyst,caseId).path("lifecycleState").asText()).isEqualTo("ARCHIVED");
      verify(client,times(1)).fetchResult(anyString(),anyString(),anyString());
    }finally{release.countDown();pool.shutdownNow();}
  }

  @Test void inquiryAlreadyInFlightCannotRecreatePurgedChildrenAfterDeletion()throws Exception {
    var fetched=new CountDownLatch(1);var release=new CountDownLatch(1);
    CaseEvidenceClient client=mock(CaseEvidenceClient.class);
    when(client.fetchResult(anyString(),anyString(),anyString())).thenAnswer(call->{fetched.countDown();await(release);return new CaseEvidenceClient.Result(inquiryResponse(),null);});
    var api=new CaseEvidenceService(mapper,db,tx,cases,client);var pool=Executors.newSingleThreadExecutor();
    try {
      Future<?> save=pool.submit(()->api.inquire(analyst,caseId,bytes(mapper.createObjectNode()),"race-inflight-delete"));
      await(fetched);archive();
      lifecycle.command(admin,caseNumber,"DELETED",bytes(command(1).put("confirmation",caseNumber)),"race-delete-case");
      release.countDown();failed(save,404);
      assertThat(count("fcr_case_evidence")).isZero();assertThat(count("fcr_case_evidence_command")).isZero();
      assertThatThrownBy(()->cases.caseDetail(analyst,caseNumber)).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.status).isEqualTo(404));
      assertThat(new CaseNumberService(db,tx).find(analyst.tenantId(),caseId)).isEqualTo(caseNumber);
    }finally{release.countDown();pool.shutdownNow();}
  }

  @Test void questionReadinessMustRecheckArchiveBeforeInsertingAJob()throws Exception {
    var prepared=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
    var projection=spy(new CaseEvidenceProjection(mapper,""));var worker=mock(UatWorkerClient.class);var queue=new HoldingExecutor();
    doAnswer(call->{ObjectNode projected=(ObjectNode)call.callRealMethod();prepared.countDown();await(release);return projected;})
        .when(projection).readiness(any(Actor.class),any(ObjectNode.class),any(ObjectNode.class),anyString());
    try(var investigation=new CaseInvestigationService(mapper,db,tx,cases,evidence,projection,worker,queue)) {
      Future<?> start=pool.submit(()->investigation.start(analyst,caseId,bytes(question()),"race-question-prepare"));
      await(prepared);archive();release.countDown();failed(start,409);
      assertThat(count("fcr_case_investigation")).isZero();assertThat(queue.tasks).isEmpty();verifyNoInteractions(worker);
    }finally{release.countDown();pool.shutdownNow();}
  }

  @Test void queuedQuestionPreventsArchiveWithoutChangingAnyCaseState() {
    var worker=mock(UatWorkerClient.class);var queue=new HoldingExecutor();
    try(var investigation=new CaseInvestigationService(mapper,db,tx,cases,evidence,new CaseEvidenceProjection(mapper,""),worker,queue)) {
      ObjectNode job=investigation.start(analyst,caseId,bytes(question()),"race-queued-question");
      assertThat(job.path("status").asText()).isEqualTo("QUEUED");
      assertThatThrownBy(this::archive).isInstanceOfSatisfying(ApiException.class,error->{
        assertThat(error.status).isEqualTo(409);assertThat(error.code).isEqualTo("CASE_INVESTIGATION_ACTIVE");});
      assertThat(lifecycle.detail(analyst,caseId).path("version").asInt()).isZero();
      assertThat(lifecycle.detail(analyst,caseId).path("state").asText()).isEqualTo("ACTIVE");
      assertThat(count("fcr_case_lifecycle_event")).isZero();assertThat(count("fcr_case_investigation")).isEqualTo(1);
      verifyNoInteractions(worker);
    }
  }

  @Test void archiveKeepsExistingFrozenReportExactAndAllowsAnArchivedSnapshotExport() {
    var investigations=mock(CaseInvestigationService.class);
    var management=new CaseManagementService(mapper,db,tx,cases,evidence,investigations);
    var reports=new CaseReportService(mapper,db,tx,cases,evidence,investigations,management,mock(CaseReportPdf.class));
    ObjectNode selection=mapper.createObjectNode().put("evidenceId",snapshot.path("id").asText()).put("includeEvidenceRows",false).put("reportMode","SUMMARY");
    selection.putArray("investigationIds");
    ObjectNode original=reports.preview(analyst,caseId,bytes(selection),"race-original-report");
    String body=db.queryForObject("SELECT body FROM fcr_case_report WHERE id=?",String.class,original.path("reportId").asText());
    assertThat(body).isEqualTo(original.toString());
    archive();
    ObjectNode download=mapper.createObjectNode().put("reportId",original.path("reportId").asText()).put("reportHash",original.path("reportHash").asText());
    // Compare the serialized contract: parsing a persisted LongNode may yield an equal-valued IntNode.
    assertThat(reports.frozen(analyst,caseId,bytes(download)).toString()).isEqualTo(body);
    assertThat(db.queryForObject("SELECT body FROM fcr_case_report WHERE id=?",String.class,original.path("reportId").asText())).isEqualTo(body);
    ObjectNode archived=reports.preview(analyst,caseId,bytes(selection),"race-archived-report");
    assertThat(archived.path("case").path("lifecycleState").asText()).isEqualTo("ARCHIVED");
    assertThat(archived.path("evidence").path("evidenceHash")).isEqualTo(snapshot.path("evidenceHash"));
    assertThat(count("fcr_case_report")).isEqualTo(2);verifyNoInteractions(investigations);
  }

  static final class HoldingExecutor extends AbstractExecutorService {
    final Queue<Runnable> tasks=new ConcurrentLinkedQueue<>();boolean stopped;
    public void execute(Runnable task){if(stopped)throw new RejectedExecutionException();tasks.add(task);}
    public void shutdown(){stopped=true;}
    public List<Runnable> shutdownNow(){stopped=true;var pending=new ArrayList<>(tasks);tasks.clear();return pending;}
    public boolean isShutdown(){return stopped;}
    public boolean isTerminated(){return stopped&&tasks.isEmpty();}
    public boolean awaitTermination(long timeout,TimeUnit unit){return isTerminated();}
  }
}
