package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentDiscoveryServiceTest {
  final ObjectMapper mapper=new ObjectMapper();
  final Actor analyst=new Actor("analyst","Analyst","ANALYST","northstar");
  final Actor other=new Actor("other","Other","ANALYST","silverline");
  final Actor viewer=new Actor("viewer","Viewer","VIEWER","northstar");
  final PaymentDiscoveryService.Scope scope=new PaymentDiscoveryService.Scope("1352","760");
  JdbcTemplate db; TransactionTemplate tx; PaymentDiscoveryService service;
  @BeforeEach void setup(){
    var source=new DriverManagerDataSource("jdbc:h2:mem:discovery-"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
    db=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
    service=make(new PaymentDiscoveryClient(mapper,"MOCK",false,"","TEST-UAT",2,(u,b,t)->{throw new AssertionError("Mock must never call transport");}));
  }
  PaymentDiscoveryService make(PaymentDiscoveryClient client){return new PaymentDiscoveryService(mapper,db,tx,client,"northstar:1352:760,silverline:2468:760");}
  byte[] bytes(JsonNode n){return n.toString().getBytes(StandardCharsets.UTF_8);}
  ObjectNode request(int count){return mapper.createObjectNode().put("orgBank","760").put("orgBranch","1352").put("inquiryDate","2026-09-14").put("recordCount",count);}
  ObjectNode lookupRequest(String type,String reference){return mapper.createObjectNode().put("orgBank","760").put("orgBranch","1352").put("referenceType",type).put("reference",reference);}
  Map<String,String> row(String reference,String sub){
    Map<String,String> row=new LinkedHashMap<>();row.put("PIO_REF_TXN_NO",reference);row.put("PIO_ORG_BRN","1352");row.put("PIO_ORG_BANK","760");row.put("REF_SUBSEQ_NO",sub);row.put("UTR_REF_NO","UTR-SHARED");row.put("DATINITIATION","2026-09-14T12:30:00");row.put("NUMAMOUNT_4038","900719925474099312345.007");return row;
  }
  PaymentDiscoveryService bank(AtomicReference<List<Map<String,String>>> rows){
    return bank(rows,false);
  }
  PaymentDiscoveryService bank(AtomicReference<List<Map<String,String>>> rows,boolean hasMore){
    return make(new PaymentDiscoveryClient(mapper,"BANK_API",true,"https://bank.example.test/inquiry","TEST-UAT",2,(u,b,t)->{
      ObjectNode result=mapper.createObjectNode().put("hasMore",hasMore).put("observedAt","2026-09-14T08:00:00Z");result.set("items",mapper.valueToTree(rows.get()));
      return new PaymentDiscoveryClient.Reply(200,"application/json",bytes(result));
    }));
  }
  ObjectNode command(String candidate,String reason){return mapper.createObjectNode().put("candidateId",candidate).put("reason",reason);}
  void status(org.assertj.core.api.ThrowableAssert.ThrowingCallable run,int status){assertThatThrownBy(run).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(status));}

  @Test void exactNativeValuesAndCompleteHostGroupsArePreserved(){
    String ref="00012345678901234567890123456789012345";
    var result=service.normalize(analyst,scope,List.of(row(ref,"1"),row(ref,"0"),row(ref,"1")),"EXCEL");
    assertThat(result).hasSize(1);ObjectNode item=result.get(0);
    assertThat(item.path("reference").asText()).isEqualTo(ref);assertThat(item.path("amount").asText()).isEqualTo("900719925474099312345.007");
    assertThat(item.path("hostSubsequences")).isEqualTo(mapper.createArrayNode().add("0").add("1"));
    assertThat(item.path("currency").isNull()).isTrue();assertThat(item.path("dataClassification").asText()).isEqualTo("PRIVATE_UAT");
    assertThat(item.path("initiatedAt").asText()).isEqualTo("2026-09-14T12:30:00");
  }
  @Test void conflictingRowsRejectEntireBankBatchBeforeWriting(){
    var first=row("REF-1","0");var second=row("REF-1","1");second.put("NUMAMOUNT_4038","1.000");
    var bank=bank(new AtomicReference<>(List.of(first,second)));
    status(()->bank.search(analyst,bytes(request(20))),502);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_discovery_candidate",Integer.class)).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_discovery_batch",Integer.class)).isZero();
  }
  @Test void nullableHostSubsequencesStayDistinctFromZeroAndSortAfterKnownValues(){
    var rows=List.of(row("REF-NULL-MIX","1"),row("REF-NULL-MIX",null),row("REF-NULL-MIX","0"),
        row("REF-NULL-MIX",""),row("REF-NULL-MIX",null),row("REF-NULL-MIX","1"));
    var item=service.normalize(analyst,scope,rows,"EXCEL").get(0);
    assertThat(item.path("hostSubsequences")).isEqualTo(mapper.createArrayNode().add("0").add("1").addNull());
    var reversed=new ArrayList<>(rows);Collections.reverse(reversed);
    assertThat(service.normalize(analyst,scope,reversed,"EXCEL").get(0).path("candidateId")).isEqualTo(item.path("candidateId"));
    assertThat(rows.get(1).get("REF_SUBSEQ_NO")).isNull();
    assertThat(rows.get(3).get("REF_SUBSEQ_NO")).isEmpty();
  }
  @Test void allUnknownHostSubsequencesKeepOneNullWithoutInventingZero(){
    var item=service.normalize(analyst,scope,List.of(row("REF-NULL-ONLY",null),row("REF-NULL-ONLY",""),row("REF-NULL-ONLY",null)),"EXCEL").get(0);
    assertThat(item.path("hostSubsequences")).isEqualTo(mapper.createArrayNode().addNull());
    var zero=service.normalize(analyst,scope,List.of(row("REF-NULL-ONLY","0")),"EXCEL").get(0);
    assertThat(item.path("candidateId")).isNotEqualTo(zero.path("candidateId"));
  }
  @Test void nullableSubsequenceDoesNotPermitMissingColumnsOrInvalidKnownValues(){
    for(String value:List.of("null","NULL","NaN","-1","+1","1.0","1e3"," "," 0","0 ","0\n","9".repeat(39)))
      status(()->service.normalize(analyst,scope,List.of(row("REF-INVALID-SUB",value)),"EXCEL"),422);
    var missing=row("REF-MISSING-SUB","0");missing.remove("REF_SUBSEQ_NO");
    status(()->service.normalize(analyst,scope,List.of(missing),"EXCEL"),422);
    var missingBank=row("REF-MISSING-BANK",null);missingBank.put("PIO_ORG_BANK",null);
    status(()->service.normalize(analyst,scope,List.of(missingBank),"EXCEL"),422);
  }
  @Test void nullableSubsequencePersistsThroughInquiryCaseCreationAndIdempotentResume() throws Exception {
    var rows=new AtomicReference<List<Map<String,String>>>(List.of(row("REF-NULL-CASE",null),row("REF-NULL-CASE","0"),row("REF-NULL-CASE","")));
    var bank=bank(rows);
    JsonNode candidate=bank.search(analyst,bytes(request(20))).path("items").get(0);
    ArrayNode expected=mapper.createArrayNode().add("0").addNull();
    assertThat(candidate.path("hostSubsequences")).isEqualTo(expected);
    ObjectNode batch=(ObjectNode)mapper.readTree(db.queryForObject("SELECT body FROM fcr_discovery_batch",String.class));
    assertThat(batch.path("nativeRows").get(0).path("REF_SUBSEQ_NO").isNull()).isTrue();
    assertThat(batch.path("nativeRows").get(2).path("REF_SUBSEQ_NO").textValue()).isEmpty();
    byte[] create=bytes(command(candidate.path("candidateId").asText(),"Investigate the supplied rows with unknown host subsequence"));
    ObjectNode created=bank.createCase(analyst,create,"nullable-case");
    String caseId=created.path("caseId").asText();
    assertThat(created.path("item").path("hostSubsequences")).isEqualTo(expected);
    assertThat(bank.createCase(analyst,create,"nullable-case")).isEqualTo(created);
    assertThat(bank.caseDetail(analyst,caseId).path("hostSubsequences")).isEqualTo(expected);
    assertThat(bank.cases(analyst).path("items").get(0).path("hostSubsequences")).isEqualTo(expected);
    assertThat(bank.lookup(analyst,bytes(lookupRequest("FCR","REF-NULL-CASE"))).path("items").get(0).path("existingCaseId").asText()).isEqualTo(caseId);
    status(()->bank.caseDetail(other,caseId),404);
  }
  @Test void resumingOldCaseDoesNotReplaceItsSavedSubsequenceObservationWithNewNull(){
    var rows=new AtomicReference<List<Map<String,String>>>(List.of(row("REF-EXISTING-SUB","0")));
    var bank=bank(rows);
    JsonNode originalCandidate=bank.search(analyst,bytes(request(20))).path("items").get(0);
    ObjectNode original=bank.createCase(analyst,bytes(command(originalCandidate.path("candidateId").asText(),"Original reason")),"old-subsequence-case");
    rows.set(List.of(row("REF-EXISTING-SUB",null)));
    JsonNode newCandidate=bank.lookup(analyst,bytes(lookupRequest("FCR","REF-EXISTING-SUB"))).path("items").get(0);
    assertThat(newCandidate.path("hostSubsequences")).isEqualTo(mapper.createArrayNode().addNull());
    assertThat(newCandidate.path("candidateId")).isNotEqualTo(originalCandidate.path("candidateId"));
    ObjectNode resumed=bank.createCase(analyst,bytes(command(newCandidate.path("candidateId").asText(),"New inquiry reason")),"resume-old-subsequence-case");
    assertThat(resumed.path("status").asText()).isEqualTo("EXISTING");
    assertThat(resumed.path("caseId")).isEqualTo(original.path("caseId"));
    assertThat(resumed.path("item")).isEqualTo(original.path("item"));
    assertThat(bank.caseDetail(analyst,original.path("caseId").asText()).path("hostSubsequences")).isEqualTo(mapper.createArrayNode().add("0"));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_payment_case",Integer.class)).isEqualTo(1);
  }
  @Test void invalidNativeFormatsAndUnknownColumnsAreRejected(){
    for(String field:List.of("NUMAMOUNT_4038","DATINITIATION","REF_SUBSEQ_NO","PIO_REF_TXN_NO")) {
      var row=row("REF-1","0");row.put(field,switch(field){case "NUMAMOUNT_4038"->"1e3";case "DATINITIATION"->"2026-09-14T12:30:00Z";case "REF_SUBSEQ_NO"->"1.0";default->" bad ";});
      status(()->service.normalize(analyst,scope,List.of(row),"EXCEL"),422);
    }
    var extra=row("REF-1","0");extra.put("RAW_ACCOUNT","ignored");status(()->service.normalize(analyst,scope,List.of(extra),"EXCEL"),422);
  }
  @Test void mockListLimitsPaymentsAfterGroupingAndPreservesDuplicateUtrChoices(){
    JsonNode full=service.search(analyst,bytes(request(20)));
    assertThat(full.path("items").size()).isEqualTo(6);assertThat(full.path("truncated").asBoolean()).isFalse();
    assertThat(full.path("items").get(5).path("hostSubsequences").size()).isEqualTo(2);
    JsonNode limited=service.search(analyst,bytes(request(2)));assertThat(limited.path("items").size()).isEqualTo(2);assertThat(limited.path("truncated").asBoolean()).isTrue();
    var query=lookupRequest("UTR","MOCK-1352-2026-09-14-SHARED");
    JsonNode direct=service.lookup(analyst,bytes(query));assertThat(direct.path("items").size()).isEqualTo(2);
    assertThat(direct.path("sourceKind").asText()).isEqualTo("MOCK");assertThat(direct.path("matchStatus").asText()).isEqualTo("AMBIGUOUS");
  }
  @Test void directLookupChoosesCurrentSnapshotOncePerScopedIdentity(){
    var rows=new AtomicReference<List<Map<String,String>>>(List.of(row("REF-1","0")));
    var bank=bank(rows);bank.search(analyst,bytes(request(20)));
    var changed=row("REF-1","1");changed.put("NUMAMOUNT_4038","42.001");rows.set(List.of(changed));
    JsonNode result=bank.lookup(analyst,bytes(lookupRequest("FCR","REF-1")));
    assertThat(result.path("items").size()).isEqualTo(1);assertThat(result.path("items").get(0).path("amount").asText()).isEqualTo("42.001");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_discovery_candidate",Integer.class)).isEqualTo(2);
    rows.set(List.of());JsonNode absent=bank.lookup(analyst,bytes(lookupRequest("FCR","REF-1")));
    assertThat(absent.path("items").size()).isZero();assertThat(absent.path("matchStatus").asText()).isEqualTo("NOT_FOUND");
  }
  @Test void disabledModeRejectsExactLookupEvenWhenPrivateRecordsAreAlreadyLoaded(){
    var bank=bank(new AtomicReference<>(List.of(row("PRIVATE-1","0"))));bank.search(analyst,bytes(request(20)));
    var disabled=make(new PaymentDiscoveryClient(mapper,"BANK_API",false,"","TEST-UAT",2,(u,b,t)->{throw new AssertionError("Disabled mode called transport");}));
    status(()->disabled.search(analyst,bytes(request(20))),503);
    status(()->disabled.lookup(analyst,bytes(lookupRequest("FCR","PRIVATE-1"))),503);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_discovery_batch",Integer.class)).isEqualTo(1);
  }
  @Test void noMatchIsExplicitlyBoundedAndUnknownRequestFieldsCannotSupplyUrls(){
    var result=service.lookup(analyst,bytes(lookupRequest("FCR","MISSING")));
    assertThat(result.path("items").size()).isZero();assertThat(result.path("matchStatus").asText()).isEqualTo("NOT_FOUND");assertThat(result.path("warnings").toString()).contains("source coverage and retention");
    status(()->service.search(analyst,bytes(request(20).put("url","https://elsewhere.test"))),422);
    status(()->service.search(analyst,bytes(request(201))),422);
    status(()->service.search(analyst,bytes(request(20).put("reference","MISSING"))),422);
    status(()->service.lookup(analyst,bytes(lookupRequest("FCR","MISSING").put("inquiryDate","2026-09-14"))),422);
    status(()->service.lookup(analyst,bytes(lookupRequest("FCR","MISSING").put("recordCount",20))),422);
  }
  @Test void tenantScopeAndWriterRulesApplyBeforeTransportOrPersistence(){
    status(()->service.search(other,bytes(request(20))),403);status(()->service.search(viewer,bytes(request(20))),403);
    status(()->service.search(analyst,bytes(request(20).put("orgBank","999"))),403);
    status(()->service.lookup(analyst,bytes(lookupRequest("FCR","MISSING").put("orgBank","999"))),403);
    status(()->service.lookup(viewer,bytes(lookupRequest("FCR","MISSING"))),403);
    ObjectNode missingBank=request(20);missingBank.remove("orgBank");status(()->service.search(analyst,bytes(missingBank)),422);
    status(()->service.upload(analyst,"1352","999",new byte[]{1}),403);
    var row=row("REF-1","0");row.put("PIO_ORG_BANK","999");status(()->service.normalize(analyst,scope,List.of(row),"EXCEL"),403);
    assertThat(service.config(other).path("scopes").get(0).path("orgBranch").asText()).isEqualTo("2468");
  }
  @Test void sameBranchCanBelongToTwoAuthorizedBanksWithoutMergingTheirCases(){
    var mock=new PaymentDiscoveryClient(mapper,"MOCK",false,"","TEST-UAT",2,(u,b,t)->{throw new AssertionError("No transport in mock mode");});
    var multi=new PaymentDiscoveryService(mapper,db,tx,mock,"northstar:1352:760,northstar:1352:999");
    assertThat(multi.config(analyst).path("scopes").size()).isEqualTo(2);
    var first=multi.search(analyst,bytes(request(20))).path("items").get(0);
    var second=multi.search(analyst,bytes(request(20).put("orgBank","999"))).path("items").get(0);
    assertThat(first.path("reference")).isEqualTo(second.path("reference"));
    assertThat(first.path("candidateId")).isNotEqualTo(second.path("candidateId"));
    var firstCase=multi.createCase(analyst,bytes(command(first.path("candidateId").asText(),"Bank one")),"bank-one");
    var secondCase=multi.createCase(analyst,bytes(command(second.path("candidateId").asText(),"Bank two")),"bank-two");
    assertThat(firstCase.path("caseId")).isNotEqualTo(secondCase.path("caseId"));
    assertThat(secondCase.path("item").path("orgBank").asText()).isEqualTo("999");
    assertThat(service.cases(analyst).path("total").asInt()).isEqualTo(1);
    status(()->service.caseDetail(analyst,secondCase.path("caseId").asText()),404);
    var found=multi.lookup(analyst,bytes(lookupRequest("FCR",first.path("reference").asText()).put("orgBank","999")));
    assertThat(found.path("items").size()).isEqualTo(1);assertThat(found.path("items").get(0).path("orgBank").asText()).isEqualTo("999");
    assertThatThrownBy(()->new PaymentDiscoveryService(mapper,db,tx,mock,"northstar:1352:760,northstar:1352:760")).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void mockExactLookupFindsHistoricalPaymentWithoutAnyPriorListOrDateFilter(){
    var old=PaymentDiscoveryClient.mock("1352","760",LocalDate.of(2021,4,3)).rows().get(0);
    JsonNode found=service.lookup(analyst,bytes(lookupRequest("FCR",old.get("PIO_REF_TXN_NO"))));
    assertThat(found.path("matchStatus").asText()).isEqualTo("EXACT_MATCH");assertThat(found.path("items").size()).isEqualTo(1);
    assertThat(found.path("items").get(0).path("hostSubsequences").size()).isEqualTo(2);
    assertThat(found.path("items").get(0).path("initiatedAt").asText()).startsWith("2021-04-03T");
    JsonNode receipt=mapper.createObjectNode();
    try{receipt=mapper.readTree(db.queryForObject("SELECT body FROM fcr_discovery_batch",String.class));}catch(Exception ex){throw new AssertionError(ex);}
    assertThat(receipt.path("matchStatus").asText()).isEqualTo("EXACT_MATCH");
  }
  @Test void wrongReferenceWrongUtrAndIncompleteMatchesAreRejectedWithoutPersistence(){
    var rows=new AtomicReference<List<Map<String,String>>>(List.of(row("REF-OTHER","0")));
    var bank=bank(rows);status(()->bank.lookup(analyst,bytes(lookupRequest("FCR","REF-REQUESTED"))),502);
    status(()->bank.lookup(analyst,bytes(lookupRequest("UTR","UTR-REQUESTED"))),502);
    var incomplete=bank(rows,true);status(()->incomplete.lookup(analyst,bytes(lookupRequest("FCR","REF-OTHER"))),502);
    var wrongBank=row("REF-OTHER","0");wrongBank.put("PIO_ORG_BANK","999");rows.set(List.of(wrongBank));
    status(()->bank.lookup(analyst,bytes(lookupRequest("FCR","REF-OTHER"))),403);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_discovery_batch",Integer.class)).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_discovery_candidate",Integer.class)).isZero();
  }
  @Test void oversizedExactUtrSetIsRejectedRatherThanSilentlyTruncated(){
    List<Map<String,String>> rows=new ArrayList<>();for(int i=0;i<201;i++)rows.add(row("REF-"+i,"0"));
    var bank=bank(new AtomicReference<>(rows));status(()->bank.lookup(analyst,bytes(lookupRequest("UTR","UTR-SHARED"))),502);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_discovery_batch",Integer.class)).isZero();
  }
  @Test void caseCreationIsIdempotentImmutableAndSeparateFromOriginalCases(){
    var item=service.search(analyst,bytes(request(20))).path("items").get(0);String candidate=item.path("candidateId").asText();
    byte[] command=bytes(command(candidate,"Investigate supplied evidence\nCheck the supplied rows\tcarefully"));
    var created=service.createCase(analyst,command,"test-key");assertThat(created.path("status").asText()).isEqualTo("CREATED");
    assertThat(service.createCase(analyst,command,"test-key")).isEqualTo(created);
    var existing=service.createCase(analyst,bytes(command(candidate,"Different reason")),"test-key-2");
    assertThat(existing.path("status").asText()).isEqualTo("EXISTING");assertThat(existing.path("item")).isEqualTo(created.path("item"));
    status(()->service.createCase(analyst,bytes(command(candidate,"Changed")),"test-key"),409);
    status(()->service.createCase(viewer,command,"viewer-key"),403);
    status(()->service.caseDetail(other,created.path("caseId").asText()),404);
    assertThat(service.cases(other).path("total").asInt()).isZero();assertThat(service.cases(analyst).path("total").asInt()).isEqualTo(1);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM payment_case",Integer.class)).isZero();
    assertThat(created.path("item").path("evidenceStatus").asText()).isEqualTo("DISCOVERY_ONLY");
  }
  @Test void concurrentCreationProducesOneCaseAndDurableCommands() throws Exception {
    String candidate=service.search(analyst,bytes(request(20))).path("items").get(0).path("candidateId").asText();
    var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
    try {
      Callable<ObjectNode> a=()->{start.await();return service.createCase(analyst,bytes(command(candidate,"First")),"concurrent-a");};
      Callable<ObjectNode> b=()->{start.await();return service.createCase(analyst,bytes(command(candidate,"Second")),"concurrent-b");};
      var fa=executor.submit(a);var fb=executor.submit(b);start.countDown();
      assertThat(fa.get(10,TimeUnit.SECONDS).path("caseId")).isEqualTo(fb.get(10,TimeUnit.SECONDS).path("caseId"));
      assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_payment_case",Integer.class)).isEqualTo(1);
      assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_case_command",Integer.class)).isEqualTo(2);
    } finally {executor.shutdownNow();}
  }
  @Test void dashboardCountsStoredAuthorizedStateInsteadOfAssumingEveryCaseOpen(){
    String candidate=service.search(analyst,bytes(request(20))).path("items").get(0).path("candidateId").asText();
    var result=service.createCase(analyst,bytes(command(candidate,"Review")),"dashboard");
    ObjectNode item=(ObjectNode)result.path("item");item.put("status","AWAITING_REVIEW").put("priority","HIGH");
    db.update("UPDATE fcr_payment_case SET body=? WHERE id=?",item.toString(),item.path("id").asText());
    JsonNode dashboard=service.dashboard(analyst);assertThat(dashboard.path("openCases").asInt()).isEqualTo(1);assertThat(dashboard.path("awaitingReview").asInt()).isEqualTo(1);assertThat(dashboard.path("highPriorityCases").asInt()).isEqualTo(1);
    assertThat(service.dashboard(other).path("awaitingReview").asInt()).isZero();
  }
}
