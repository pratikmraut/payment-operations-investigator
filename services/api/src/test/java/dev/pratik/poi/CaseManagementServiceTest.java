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

class CaseManagementServiceTest {
  final ObjectMapper mapper=new ObjectMapper();
  final Actor analyst=Actor.knownActors().get(0), reviewer=Actor.knownActors().get(1), viewer=Actor.knownActors().get(2), other=Actor.knownActors().get(3);
  JdbcTemplate db; TransactionTemplate tx; PaymentDiscoveryClient client; PaymentDiscoveryService cases;
  CaseEvidenceService evidence; CaseInvestigationService investigations; CaseManagementService service;
  ObjectNode item; String caseId;

  @BeforeEach void setup() {
    var source=new DriverManagerDataSource("jdbc:h2:mem:management-"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
    db=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
    client=new PaymentDiscoveryClient(mapper,"MOCK",false,"","TEST",2,(u,b,t)->{throw new AssertionError("No bank calls");});
    cases=new PaymentDiscoveryService(mapper,db,tx,client,"northstar:1352:760,silverline:2468:760");
    ObjectNode found=cases.search(analyst,bytes(mapper.createObjectNode().put("orgBank","760").put("orgBranch","1352").put("inquiryDate","2026-09-14").put("recordCount",10)));
    ObjectNode created=cases.createCase(analyst,bytes(mapper.createObjectNode().put("candidateId",found.path("items").get(0).path("candidateId").asText()).put("reason","Original case reason")),"management-case");
    caseId=created.path("caseId").asText();item=cases.caseRecord(analyst,caseId);
    evidence=new CaseEvidenceService(mapper,db,tx,cases,new CaseEvidenceClient(mapper,false,"",15,"",(u,b,h,t)->{throw new AssertionError("No bank calls");}));
    investigations=mock(CaseInvestigationService.class);
    service=new CaseManagementService(mapper,db,tx,cases,evidence,investigations);
  }
  byte[] bytes(JsonNode value){return value.toString().getBytes(StandardCharsets.UTF_8);}
  byte[] raw(String value){return value.getBytes(StandardCharsets.UTF_8);}
  int count(String table){return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
  ObjectNode note(long version,String text){return mapper.createObjectNode().put("expectedVersion",version).put("text",text);}
  ObjectNode manage(long version,String owner,String priority){return mapper.createObjectNode().put("expectedVersion",version).put("ownerId",owner).put("priority",priority).put("reason","Assign follow-up after inspecting source records.");}
  ObjectNode request(long version){return mapper.createObjectNode().put("expectedVersion",version).put("title","Native acceptance record").put("detail","Obtain the native inquiry state for this reference.").put("dueDate","2026-09-20");}
  void status(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,int expected){assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(expected));}
  ObjectNode savedEvidence(String key){
    ObjectNode payload=evidence.template(item);
    ObjectNode row=((ArrayNode)payload.path("sections").path("PAYMENT").path("rows")).addObject();
    CaseEvidenceSchema.COLUMNS.get("PAYMENT").forEach(column->row.put(column,""));
    row.put("SOURCE_TABLE","PM_NEFT_TXN_LOG").put("REFTXNNUMBER",item.path("reference").asText()).put("SCOPE_ROW_COUNT","1").put("NUMAMOUNT_4038","105.001");
    return evidence.submit(analyst,caseId,bytes(payload),"JSON",key);
  }
  ObjectNode job(ObjectNode snapshot,String id,String creator){
    ObjectNode job=mapper.createObjectNode().put("id",id).put("caseId",caseId).put("status","COMPLETED").put("evidenceId",snapshot.path("id").asText())
        .put("evidenceHash",snapshot.path("evidenceHash").asText()).put("question","What remains unknown?").put("createdBy",creator).put("inputHash","a".repeat(64));
    job.putObject("answer").put("answerId","ANSWER-"+id);
    when(investigations.detail(any(),eq(caseId),eq(id))).thenReturn(job);return job;
  }
  ObjectNode conclusion(ObjectNode snapshot,long version,String...ids){
    ObjectNode input=mapper.createObjectNode().put("expectedVersion",version).put("evidenceId",snapshot.path("id").asText()).put("evidenceHash",snapshot.path("evidenceHash").asText())
        .put("conclusion","Reviewed the selected evidence. Beneficiary credit remains unconfirmed.");
    input.set("investigationIds",mapper.valueToTree(ids));return input;
  }

  @Test void initialReadIsScopedReadOnlyAndShowsEligibleExistingWriters() {
    ObjectNode result=service.detail(viewer,caseId);
    assertThat(result.path("version").asInt()).isZero();assertThat(result.path("owner").isNull()).isTrue();assertThat(result.path("priority").asText()).isEqualTo("MEDIUM");
    assertThat(result.path("assignees").findValuesAsText("id")).containsExactly("analyst","reviewer");
    for(String key:List.of("notes","evidenceRequests","reviewerConclusions","audit"))assertThat(result.path(key).isEmpty()).isTrue();
    assertThat(count("fcr_case_management")).isZero();assertThat(count("fcr_case_management_event")).isZero();
    status(()->service.detail(other,caseId),404);verifyNoInteractions(investigations);
  }

  @Test void ownerPriorityAndNotesOverlayQueueWithoutMutatingDiscoveryOrClosingCase() {
    ObjectNode original=cases.caseRecord(analyst,caseId);
    ObjectNode updated=service.manage(analyst,caseId,bytes(manage(0,"reviewer","HIGH")),"manage-owner");
    assertThat(updated.path("owner").path("name").asText()).isEqualTo(reviewer.name());
    assertThat(updated.path("version").asInt()).isEqualTo(1);
    String text="Source note with exact 001.007\nAwaiting confirmation.";
    ObjectNode result=service.addNote(analyst,caseId,bytes(note(1,text)),"add-original-note");
    assertThat(result.path("notes").get(0).path("text").asText()).isEqualTo(text);
    assertThat(result.path("audit").size()).isEqualTo(2);
    assertThat(result.path("audit").get(0).path("action").asText()).isEqualTo("NOTE_ADDED");
    assertThat(cases.caseDetail(analyst,caseId).path("priority").asText()).isEqualTo("HIGH");
    assertThat(cases.cases(analyst).path("items").get(0).path("owner").path("id").asText()).isEqualTo("reviewer");
    assertThat(cases.dashboard(analyst).path("highPriorityCases").asInt()).isEqualTo(1);
    assertThat(cases.caseRecord(analyst,caseId)).isEqualTo(original);
    assertThat(cases.caseDetail(analyst,caseId).path("status").asText()).isEqualTo("OPEN");
    verifyNoInteractions(investigations);
  }

  @Test void retryIsScopedAndDoesNotDuplicateAppendOnlyEventsOrOverwriteNewerState() {
    ObjectNode input=note(0,"First note");
    ObjectNode first=service.addNote(analyst,caseId,bytes(input),"retry-note");
    assertThat(service.addNote(analyst,caseId,bytes(input),"retry-note")).isEqualTo(first);
    service.addNote(analyst,caseId,bytes(note(1,"Second note")),"second-note");
    assertThat(service.addNote(analyst,caseId,bytes(input),"retry-note").path("version").asInt()).isEqualTo(2);
    assertThat(count("fcr_case_management_event")).isEqualTo(2);
    status(()->service.addNote(analyst,caseId,bytes(note(0,"Changed note")),"retry-note"),409);
    status(()->service.addNote(viewer,caseId,bytes(input),"retry-note"),403);
    status(()->service.addNote(other,caseId,bytes(input),"retry-note"),404);
    var rescoped=new PaymentDiscoveryService(mapper,db,tx,client,"northstar:999:760,silverline:2468:760");
    var unavailable=new CaseManagementService(mapper,db,tx,rescoped,evidence,investigations);
    status(()->unavailable.addNote(analyst,caseId,bytes(input),"retry-note"),404);
    status(()->service.addNote(analyst,caseId,bytes(note(0,"Stale note")),"stale-note"),409);
    assertThat(count("fcr_case_management_event")).isEqualTo(2);
  }

  @Test void concurrentSameVersionOnlyOneCommandCommitsAndIdenticalRetryCommitsOnce()throws Exception {
    var pool=Executors.newFixedThreadPool(2); var barrier=new CyclicBarrier(2);
    try {
      List<Future<Integer>> results=new ArrayList<>();
      for(int index=0;index<2;index++){final int i=index;results.add(pool.submit(()->{barrier.await();try{service.addNote(analyst,caseId,bytes(note(0,"Concurrent "+i)),"concurrent-note-"+i);return 200;}catch(ApiException e){return e.status;}}));}
      assertThat(List.of(results.get(0).get(10,TimeUnit.SECONDS),results.get(1).get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
      assertThat(count("fcr_case_management_event")).isEqualTo(1);
      var same=new CyclicBarrier(2);List<Future<ObjectNode>> retries=new ArrayList<>();
      for(int i=0;i<2;i++)retries.add(pool.submit(()->{same.await();return service.addNote(analyst,caseId,bytes(note(1,"Same command")),"same-concurrent-key");}));
      assertThat(retries.get(0).get(10,TimeUnit.SECONDS)).isEqualTo(retries.get(1).get(10,TimeUnit.SECONDS));
      assertThat(count("fcr_case_management_event")).isEqualTo(2);
    }finally{pool.shutdownNow();}
  }

  @Test void concurrentEvidenceAndManagementSavePreserveBothChangesAndOriginalReason()throws Exception {
    var pool=Executors.newFixedThreadPool(2);var barrier=new CyclicBarrier(2);
    try {
      var managing=pool.submit(()->{barrier.await();return service.manage(analyst,caseId,bytes(manage(0,"analyst","CRITICAL")),"concurrent-manage");});
      var attaching=pool.submit(()->{barrier.await();return savedEvidence("concurrent-evidence");});
      managing.get(10,TimeUnit.SECONDS);attaching.get(10,TimeUnit.SECONDS);
      ObjectNode merged=cases.caseDetail(analyst,caseId),raw=cases.caseRecord(analyst,caseId);
      assertThat(merged.path("priority").asText()).isEqualTo("CRITICAL");assertThat(merged.path("owner").path("id").asText()).isEqualTo("analyst");
      assertThat(merged.path("evidenceStatus").asText()).isEqualTo("EVIDENCE_ATTACHED");assertThat(merged.path("reason")).isEqualTo(item.path("reason"));
      assertThat(raw.path("priority").asText()).isEqualTo("MEDIUM");assertThat(raw.has("owner")).isFalse();assertThat(raw.has("managementVersion")).isFalse();
    }finally{pool.shutdownNow();}
  }

  @Test void evidenceRequestLifecyclePreservesTransitionsAndVersionBindingWithoutBankCalls() {
    ObjectNode first=service.requestEvidence(analyst,caseId,bytes(request(0)),"request-source");
    String id=first.path("evidenceRequests").get(0).path("id").asText();ObjectNode snapshot=savedEvidence("requested-evidence");
    ObjectNode update=mapper.createObjectNode().put("expectedVersion",1).put("status","FULFILLED").put("note","Supplied inquiry attached; outcome remains unknown.").put("evidenceId",snapshot.path("id").asText());
    ObjectNode fulfilled=service.updateEvidenceRequest(analyst,caseId,id,bytes(update),"fulfill-request");
    ObjectNode record=(ObjectNode)fulfilled.path("evidenceRequests").get(0);
    assertThat(record.path("status").asText()).isEqualTo("FULFILLED");assertThat(record.path("evidenceHash")).isEqualTo(snapshot.path("evidenceHash"));
    savedEvidence("later-requested-evidence");
    assertThat(service.detail(viewer,caseId).path("evidenceRequests").get(0)).isEqualTo(record);
    update.remove("evidenceId");update.put("expectedVersion",2).put("status","OPEN").put("note","Additional source requested.");
    ObjectNode reopened=service.updateEvidenceRequest(analyst,caseId,id,bytes(update),"reopen-request");
    JsonNode current=reopened.path("evidenceRequests").get(0);
    assertThat(current.path("evidenceId").isNull()).isTrue();assertThat(current.path("updates").size()).isEqualTo(2);
    assertThat(current.path("updates").get(0).path("evidenceHash")).isEqualTo(snapshot.path("evidenceHash"));
    update.put("expectedVersion",3).put("status","CANCELLED");
    assertThat(service.updateEvidenceRequest(analyst,caseId,id,bytes(update),"cancel-request").path("evidenceRequests").get(0).path("status").asText()).isEqualTo("CANCELLED");
    assertThat(count("fcr_case_management_event")).isEqualTo(4);verifyNoInteractions(investigations);
  }

  @Test void requestValidationRejectsUnknownVersionsInvalidDatesAndUnlinkedFulfillment() {
    ObjectNode bad=request(0).put("dueDate","2026-02-30");status(()->service.requestEvidence(analyst,caseId,bytes(bad),"bad-request-date"),422);
    String id=service.requestEvidence(analyst,caseId,bytes(request(0)),"valid-request").path("evidenceRequests").get(0).path("id").asText();
    ObjectNode input=mapper.createObjectNode().put("expectedVersion",1).put("status","FULFILLED").put("note","Recorded by operator.");
    status(()->service.updateEvidenceRequest(analyst,caseId,id,bytes(input),"missing-version"),422);
    input.put("evidenceId","EVD-DOES-NOT-EXIST");status(()->service.updateEvidenceRequest(analyst,caseId,id,bytes(input),"wrong-version"),404);
    input.put("status","CANCELLED");status(()->service.updateEvidenceRequest(analyst,caseId,id,bytes(input),"unwanted-version"),422);
    input.remove("evidenceId");status(()->service.updateEvidenceRequest(analyst,caseId,"REQ-OTHER-CASE",bytes(input),"wrong-request"),404);
    assertThat(count("fcr_case_management_event")).isEqualTo(1);
  }

  @Test void reviewerConclusionIsIndependentImmutableAndDoesNotApproveOrCloseCase() {
    ObjectNode snapshot=savedEvidence("review-evidence");job(snapshot,"CIN-ONE",analyst.id());
    ObjectNode input=conclusion(snapshot,0,"CIN-ONE");
    ObjectNode result=service.conclude(reviewer,caseId,bytes(input),"record-conclusion");
    JsonNode preserved=result.path("reviewerConclusions").get(0);
    assertThat(preserved.path("status").asText()).isEqualTo("RECORDED");assertThat(preserved.path("evidenceHash")).isEqualTo(snapshot.path("evidenceHash"));
    assertThat(preserved.path("investigations").get(0).path("answerId").asText()).isEqualTo("ANSWER-CIN-ONE");
    assertThat(cases.caseDetail(analyst,caseId).path("status").asText()).isEqualTo("OPEN");
    savedEvidence("newer-review-evidence");service.addNote(analyst,caseId,bytes(note(1,"Follow-up after review")),"post-review-note");
    assertThat(service.detail(viewer,caseId).path("reviewerConclusions").get(0)).isEqualTo(preserved);
    assertThat(service.conclude(reviewer,caseId,bytes(input),"record-conclusion").path("reviewerConclusions").size()).isEqualTo(1);
    verify(investigations,times(1)).detail(reviewer,caseId,"CIN-ONE");
  }

  @Test void conclusionsRejectNonReviewersSelfReviewIncompleteMismatchedAndDuplicateJobs() {
    ObjectNode snapshot=savedEvidence("review-checks");ObjectNode first=job(snapshot,"CIN-ONE",analyst.id());ObjectNode input=conclusion(snapshot,0,"CIN-ONE");
    status(()->service.conclude(analyst,caseId,bytes(input),"analyst-review"),403);status(()->service.conclude(viewer,caseId,bytes(input),"viewer-review"),403);
    first.put("createdBy",reviewer.id());status(()->service.conclude(reviewer,caseId,bytes(input),"self-review"),403);
    first.put("createdBy",analyst.id()).put("status","RUNNING");status(()->service.conclude(reviewer,caseId,bytes(input),"running-review"),422);
    first.put("status","COMPLETED").put("evidenceId","EVD-OTHER");status(()->service.conclude(reviewer,caseId,bytes(input),"wrong-evidence-review"),422);
    first.put("evidenceId",snapshot.path("id").asText()).put("evidenceHash","0".repeat(64));status(()->service.conclude(reviewer,caseId,bytes(input),"wrong-hash-review"),422);
    first.put("evidenceHash",snapshot.path("evidenceHash").asText());
    status(()->service.conclude(reviewer,caseId,bytes(conclusion(snapshot,0,"CIN-ONE","CIN-ONE")),"duplicate-review"),422);
    input.put("evidenceHash","f".repeat(64));status(()->service.conclude(reviewer,caseId,bytes(input),"changed-fingerprint"),409);
    ObjectNode changed=item.deepCopy().put("createdBy",reviewer.id());db.update("UPDATE fcr_payment_case SET body=? WHERE id=?",changed.toString(),caseId);
    status(()->service.conclude(reviewer,caseId,bytes(conclusion(snapshot,0,"CIN-ONE")),"creator-review"),403);
    assertThat(count("fcr_case_management_event")).isZero();
  }

  @Test void fulfilledRequestAndReviewRejectTamperedSavedEvidence() {
    ObjectNode snapshot=savedEvidence("tamper-evidence");job(snapshot,"CIN-ONE",analyst.id());
    ((ObjectNode)snapshot.path("payload").path("payment")).put("reference","OTHER-PAYMENT");
    db.update("UPDATE fcr_case_evidence SET body=? WHERE id=?",snapshot.toString(),snapshot.path("id").asText());
    status(()->service.conclude(reviewer,caseId,bytes(conclusion(snapshot,0,"CIN-ONE")),"tampered-review"),503);
    String id=service.requestEvidence(analyst,caseId,bytes(request(0)),"request-tampered").path("evidenceRequests").get(0).path("id").asText();
    ObjectNode update=mapper.createObjectNode().put("expectedVersion",1).put("status","FULFILLED").put("note","Should not save").put("evidenceId",snapshot.path("id").asText());
    status(()->service.updateEvidenceRequest(analyst,caseId,id,bytes(update),"tampered-request"),503);
    assertThat(count("fcr_case_management_event")).isEqualTo(1);
  }

  @Test void strictCommandsRejectUnknownKeysInvalidOwnersUnsafeVersionsAndOversizeBeforeWriting() {
    for(String owner:List.of("other","viewer","invented"))status(()->service.manage(analyst,caseId,bytes(manage(0,owner,"HIGH")),"invalid-owner-"+owner),422);
    status(()->service.manage(analyst,caseId,bytes(manage(0,null,"URGENT")),"invalid-priority"),422);
    status(()->service.manage(analyst,caseId,bytes(manage(0,null,"MEDIUM")),"no-change-command"),422);
    status(()->service.addNote(analyst,caseId,bytes(note(0,"")),"empty-note"),422);
    status(()->service.addNote(analyst,caseId,bytes(note(0,"x".repeat(4001))),"long-note"),422);
    status(()->service.addNote(analyst,caseId,bytes(note(0,"x").put("tenantId","silverline")),"forged-tenant"),422);
    status(()->service.addNote(analyst,caseId,raw("{\"expectedVersion\":0,\"text\":\"one\",\"text\":\"two\"}"),"duplicate-fields"),422);
    status(()->service.addNote(analyst,caseId,raw("{\"expectedVersion\":0.5,\"text\":\"note\"}"),"float-version"),422);
    status(()->service.addNote(analyst,caseId,bytes(note(9007199254740992L,"note")),"unsafe-version"),422);
    status(()->service.addNote(analyst,caseId,bytes(note(0,"note")),null),400);
    status(()->service.addNote(analyst,caseId,new byte[CaseManagementService.MAX_BYTES+1],"oversized-note"),413);
    assertThat(count("fcr_case_management")).isZero();assertThat(count("fcr_case_management_event")).isZero();
  }
}
