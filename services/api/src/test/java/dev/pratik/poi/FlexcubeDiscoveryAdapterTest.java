package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class FlexcubeDiscoveryAdapterTest {
  final ObjectMapper mapper=new ObjectMapper();
  final Actor actor=new Actor("analyst","Analyst","ANALYST","northstar");
  final FlexcubeInquirySupport support=new FlexcubeInquirySupport(mapper,"TESTUSER","API","20260915","EXPLICIT","Asia/Kolkata","UNKNOWN");
  final AtomicInteger calls=new AtomicInteger();
  JdbcTemplate db; TransactionTemplate tx;
  @BeforeEach void setup() {
    var source=new DriverManagerDataSource("jdbc:h2:mem:flexcube-po01-"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
    db=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
  }
  @AfterEach void close(){db.execute("SHUTDOWN");}
  PaymentDiscoveryClient client(java.util.function.Function<ObjectNode,ObjectNode> handler) {
    return new PaymentDiscoveryClient(mapper,"BANK_API",true,"https://bank.example.test/discovery","OFFLINE",2,"FLEXCUBE",support,(url,body,timeout)->{
      calls.incrementAndGet();return new PaymentDiscoveryClient.Reply(200,"application/json",mapper.writeValueAsBytes(handler.apply((ObjectNode)mapper.readTree(body))));
    });
  }
  PaymentDiscoveryService service(java.util.function.Function<ObjectNode,ObjectNode> handler) {
    return new PaymentDiscoveryService(mapper,db,tx,client(handler),"northstar:2468:999");
  }
  ObjectNode response(ObjectNode request) {
    ObjectNode response=FlexcubeInquirySupportTest.success(mapper,request).put("observedAt","2026-09-14T13:00:00+05:30").put("hasMoreRecords",false);
    response.putArray("neftPaymentDiscoveryDetails");return response;
  }
  ObjectNode row(String reference,String sub) {
    return mapper.createObjectNode().put("referenceTransactionNumber",reference).put("originatingBranchCode","2468").put("originatingBankCode","999")
        .put("referenceSubsequenceNumber",sub).put("utrReferenceNumber","SHARED-UTR").put("initiationDateTime","2026-09-14T12:00:00")
        .put("transactionAmount","900719925474099312345.007");
  }
  byte[] bytes(JsonNode value) {try{return mapper.writeValueAsBytes(value);}catch(Exception error){throw new AssertionError(error);}}
  ObjectNode search() {return mapper.createObjectNode().put("orgBank","999").put("orgBranch","2468").put("inquiryDate","2026-09-14").put("recordCount",1);}
  ObjectNode lookup(String type,String reference) {return mapper.createObjectNode().put("orgBank","999").put("orgBranch","2468").put("referenceType",type).put("reference",reference);}
  @Test void dateUsesFourDtoFieldsAndKeepsCompleteHostGroupsAndOriginalReceipt() throws Exception {
    var service=service(request->{
      assertThat(request.path("args1")).isEqualTo(mapper.createObjectNode().put("originatingBankCode",999).put("originatingBranchCode",2468).put("inquiryDate","2026-09-14").put("recordCount",1));
      assertThat(request.path("args0").path("postingDateText").asText()).isEqualTo("20260915");
      ObjectNode result=response(request).put("hasMoreRecords",true);
      ((ArrayNode)result.path("neftPaymentDiscoveryDetails")).add(row("00012345678901234567890123456789012345","0").putNull("utrReferenceNumber")).add(row("00012345678901234567890123456789012345","1").putNull("utrReferenceNumber"));
      return result;
    });
    JsonNode result=service.search(actor,bytes(search()));
    assertThat(result.path("items")).hasSize(1);assertThat(result.path("truncated").booleanValue()).isTrue();
    JsonNode candidate=result.path("items").get(0);
    assertThat(candidate.path("hostSubsequences")).hasSize(2);
    assertThat(candidate.path("reference").asText()).isEqualTo("00012345678901234567890123456789012345");
    assertThat(candidate.path("amount").asText()).isEqualTo("900719925474099312345.007");
    assertThat(candidate.path("currency").isNull()).isTrue();assertThat(candidate.path("utr").isNull()).isTrue();
    JsonNode stored=mapper.readTree(db.queryForObject("SELECT body FROM fcr_discovery_batch",String.class));
    assertThat(stored.path("upstream").path("rawResponse").path("neftPaymentDiscoveryDetails").get(0).path("utrReferenceNumber").isNull()).isTrue();
    assertThat(candidate.path("discoveryReceipt").path("upstreamHash").asText()).isEqualTo(UatService.canonicalHash(stored.path("upstream")));
    JsonNode saved=service.createCase(actor,bytes(mapper.createObjectNode().put("candidateId",candidate.path("candidateId").asText()).put("reason","Review inquiry evidence")),"create-local");
    assertThat(saved.path("item").path("discoveryReceipt")).isEqualTo(candidate.path("discoveryReceipt"));
  }
  @Test void exactUtrOmitsDateAndCountAndReturnsAllMatchingPaymentChoices() {
    var service=service(request->{
      assertThat(request.path("args1")).isEqualTo(mapper.createObjectNode().put("originatingBankCode",999).put("originatingBranchCode",2468).put("referenceType","UTR").put("reference","SHARED-UTR"));
      ObjectNode result=response(request);((ArrayNode)result.path("neftPaymentDiscoveryDetails")).add(row("REF-1","0")).add(row("REF-2","0"));return result;
    });
    JsonNode result=service.lookup(actor,bytes(lookup("UTR","SHARED-UTR")));
    assertThat(result.path("matchStatus").asText()).isEqualTo("AMBIGUOUS");assertThat(result.path("items")).hasSize(2);
    assertThat(calls.get()).isEqualTo(1);
  }
  @Test void emptyArrayIsValidButNullArrayOrNumericIdentifiersAreRejected() {
    assertThat(client(this::response).lookup("2468","999","FCR","REF-1").rows()).isEmpty();
    for (int i=0;i<3;i++) {
      int variant=i;
      var client=client(request->{ObjectNode value=response(request);if(variant==0)value.putNull("neftPaymentDiscoveryDetails");
        else if(variant==1)value.remove("neftPaymentDiscoveryDetails");
        else ((ArrayNode)value.path("neftPaymentDiscoveryDetails")).add(row("REF-1","0").put("referenceTransactionNumber",123));return value;});
      assertThatThrownBy(()->client.lookup("2468","999","FCR","REF-1")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(502));
    }
  }
  @Test void sourceScopeAndExactReferenceMismatchOrFailureNeverSavePartialRecords() {
    for(int i=0;i<4;i++) {
      int variant=i;
      var service=service(request->{ObjectNode value=response(request);ObjectNode row=row("REF-1","0");
        if(variant==0)row.put("originatingBankCode","998");
        if(variant==1)row.put("referenceTransactionNumber","OTHER");
        if(variant==2)value.put("hasMoreRecords",true);
        if(variant==3)((ObjectNode)value.path("transactionStatus")).put("errorCode","999");
        ((ArrayNode)value.path("neftPaymentDiscoveryDetails")).add(row);return value;});
      assertThatThrownBy(()->service.lookup(actor,bytes(lookup("FCR","REF-1")))).isInstanceOf(ApiException.class);
      assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_discovery_batch",Integer.class)).isZero();
      assertThat(db.queryForObject("SELECT COUNT(*) FROM fcr_discovery_candidate",Integer.class)).isZero();
    }
  }
  @Test void unauthorizedScopeAndInvalidIntegerNeverReachTransport() {
    var service=service(this::response);
    assertThatThrownBy(()->service.search(actor,bytes(search().put("orgBank","760")))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(403));
    assertThatThrownBy(()->client(this::response).fetch("2468","2147483648",LocalDate.now(),20)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(422));
    assertThat(calls.get()).isZero();
  }
  @Test void repeatedIdenticalDiscoveryCreatesTheCaseFromTheSelectedInquiryReceipt() {
    var service=service(request->{ObjectNode result=response(request);((ArrayNode)result.path("neftPaymentDiscoveryDetails")).add(row("REF-1","0"));return result;});
    JsonNode first=service.lookup(actor,bytes(lookup("FCR","REF-1"))).path("items").get(0);
    JsonNode second=service.lookup(actor,bytes(lookup("FCR","REF-1"))).path("items").get(0);
    assertThat(second.path("candidateId")).isNotEqualTo(first.path("candidateId"));
    JsonNode created=service.createCase(actor,bytes(mapper.createObjectNode().put("candidateId",second.path("candidateId").asText()).put("reason","Selected latest inquiry")),"latest-inquiry-case");
    assertThat(created.path("item").path("discoveryReceipt")).isEqualTo(second.path("discoveryReceipt"));
    JsonNode resumed=service.createCase(actor,bytes(mapper.createObjectNode().put("candidateId",first.path("candidateId").asText()).put("reason","Older inquiry")),"older-inquiry-resume");
    assertThat(resumed.path("caseId")).isEqualTo(created.path("caseId"));
    assertThat(resumed.path("status").asText()).isEqualTo("EXISTING");
    assertThat(resumed.path("item").path("discoveryReceipt")).isEqualTo(second.path("discoveryReceipt"));
  }
  @ParameterizedTest
  @ValueSource(strings={"DATE","FCR","UTR"})
  void nullableHostSubsequencesSurviveDiscoveryCaseSaveAndReceipt(String mode) throws Exception {
    var service=service(request->{
      ObjectNode result=response(request);
      ((ArrayNode)result.path("neftPaymentDiscoveryDetails"))
          .add(row("REF-NULL","1")).add(row("REF-NULL",null))
          .add(row("REF-NULL","0")).add(row("REF-NULL",null));
      return result;
    });
    JsonNode found=mode.equals("DATE")?service.search(actor,bytes(search())):
        service.lookup(actor,bytes(lookup(mode,mode.equals("FCR")?"REF-NULL":"SHARED-UTR")));
    assertThat(found.path("items")).hasSize(1);
    JsonNode candidate=found.path("items").get(0);
    ArrayNode expected=mapper.createArrayNode().add("0").add("1").addNull();
    assertThat(candidate.path("hostSubsequences")).isEqualTo(expected);
    JsonNode batch=mapper.readTree(db.queryForObject("SELECT body FROM fcr_discovery_batch",String.class));
    JsonNode upstream=batch.path("upstream");
    assertThat(upstream.path("rawResponse").path("neftPaymentDiscoveryDetails")).hasSize(4);
    assertThat(upstream.path("rawResponse").path("neftPaymentDiscoveryDetails").get(1).path("referenceSubsequenceNumber").isNull()).isTrue();
    assertThat(upstream.path("nullFields")).isEqualTo(mapper.createArrayNode()
        .add(mapper.createObjectNode().put("rowIndex",2).put("field","REF_SUBSEQ_NO"))
        .add(mapper.createObjectNode().put("rowIndex",4).put("field","REF_SUBSEQ_NO")));
    assertThat(candidate.path("discoveryReceipt").path("upstreamHash").asText()).isEqualTo(UatService.canonicalHash(upstream));
    byte[] create=bytes(mapper.createObjectNode().put("candidateId",candidate.path("candidateId").asText()).put("reason","Review unknown host subsequence"));
    JsonNode saved=service.createCase(actor,create,"nullable-subsequence");
    assertThat(saved.path("item").path("hostSubsequences")).isEqualTo(expected);
    assertThat(saved.path("item").path("discoveryReceipt")).isEqualTo(candidate.path("discoveryReceipt"));
    assertThat(service.createCase(actor,create,"nullable-subsequence")).isEqualTo(saved);
    assertThat(calls.get()).isEqualTo(1);
  }
  @Test void unknownSubsequenceRemainsNullInAdapterInsteadOfZeroOrLiteralNullText() {
    var result=client(request->{ObjectNode value=response(request);
      ((ArrayNode)value.path("neftPaymentDiscoveryDetails")).add(row("REF-NULL",null));return value;
    }).lookup("2468","999","FCR","REF-NULL");
    assertThat(result.rows()).hasSize(1);
    assertThat(result.rows().get(0)).containsEntry("REF_SUBSEQ_NO",null);
  }
  @Test void nullCompatibilityDoesNotPermitMissingSubsequenceOrNullRequiredFields() {
    for(String field:FlexcubeDiscoveryAdapter.FIELDS.keySet()) {
      var missing=client(request->{ObjectNode value=response(request);ObjectNode item=row("REF-1","0");item.remove(field);
        ((ArrayNode)value.path("neftPaymentDiscoveryDetails")).add(item);return value;});
      assertThatThrownBy(()->missing.lookup("2468","999","FCR","REF-1")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(502));
      if(Set.of("referenceSubsequenceNumber","utrReferenceNumber").contains(field))continue;
      var nullValue=client(request->{ObjectNode value=response(request);
        ((ArrayNode)value.path("neftPaymentDiscoveryDetails")).add(row("REF-1","0").putNull(field));return value;});
      assertThatThrownBy(()->nullValue.lookup("2468","999","FCR","REF-1")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(502));
    }
  }
}
