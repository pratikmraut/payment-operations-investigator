package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class PaymentDiscoveryClientTest {
  final ObjectMapper mapper=new ObjectMapper();
  PaymentDiscoveryClient client(PaymentDiscoveryClient.Transport transport){return new PaymentDiscoveryClient(mapper,"BANK_API",true,"https://bank.example.test/inquiry","TEST-UAT",2,transport);}
  PaymentDiscoveryClient.Reply reply(String body){return new PaymentDiscoveryClient.Reply(200,"application/json",body.getBytes(StandardCharsets.UTF_8));}
  void status(org.assertj.core.api.ThrowableAssert.ThrowingCallable run,int status){assertThatThrownBy(run).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(status));}
  @Test void bankRequestContainsOnlyTheFourAgreedFields() {
    AtomicInteger called=new AtomicInteger();
    var client=client((url,bytes,timeout)->{
      called.incrementAndGet();assertThat(url.toString()).isEqualTo("https://bank.example.test/inquiry");
      assertThat(mapper.readTree(bytes)).isEqualTo(mapper.readTree("{\"orgBank\":\"999\",\"orgBranch\":\"1352\",\"inquiryDate\":\"2026-09-14\",\"recordCount\":20}"));
      return reply("{\"items\":[],\"hasMore\":false,\"observedAt\":\"2026-09-14T12:00:00+05:30\"}");
    });
    var result=client.fetch("1352","999",LocalDate.of(2026,9,14),20);assertThat(result.rows()).isEmpty();assertThat(called).hasValue(1);
  }
  @Test void incompleteBankConfigurationFailsClosedAndMockNeverUsesTransport(){
    var disabled=new PaymentDiscoveryClient(mapper,"BANK_API",false,"https://bank.example.test/inquiry","TEST-UAT",2,(u,b,t)->{throw new AssertionError();});
    assertThat(disabled.mode()).isEqualTo("DISABLED");status(()->disabled.fetch("1352","760",LocalDate.now(),20),503);
    var mock=new PaymentDiscoveryClient(mapper,"MOCK",true,"https://bank.example.test/inquiry","TEST-UAT",2,(u,b,t)->{throw new AssertionError();});
    assertThat(mock.fetch("1352","760",LocalDate.of(2026,9,14),20).rows()).hasSize(7);
  }
  @Test void exactLookupPostsOnlyItsFourFieldsToTheSameConfiguredEndpoint(){
    String reference="00012345678901234567890123456789012345";AtomicInteger calls=new AtomicInteger();
    var client=client((url,body,timeout)->{
      calls.incrementAndGet();assertThat(url.toString()).isEqualTo("https://bank.example.test/inquiry");
      assertThat(mapper.readTree(body)).isEqualTo(mapper.createObjectNode().put("orgBank","999").put("orgBranch","1352").put("referenceType","FCR").put("reference",reference));
      return reply("{\"items\":[],\"hasMore\":false,\"observedAt\":\"2026-09-14T12:00:00+05:30\"}");
    });
    assertThat(client.lookup("1352","999","FCR",reference).rows()).isEmpty();assertThat(calls).hasValue(1);
  }
  @Test void mockExactLookupOnlyReturnsKnownCatalogRowsAcrossHistoricalDates(){
    var old=PaymentDiscoveryClient.mock("1352","760",LocalDate.of(2021,4,3)).rows().get(0);
    assertThat(PaymentDiscoveryClient.mockLookup("1352","760","FCR",old.get("PIO_REF_TXN_NO")).rows()).hasSize(2);
    assertThat(PaymentDiscoveryClient.mockLookup("1352","760","UTR","MOCK-1352-2021-04-03-SHARED").rows()).hasSize(2);
    for(String value:List.of("UNKNOWN","MOCK-1352-2021-02-30-SHARED","MOCK-2468-2021-04-03-SHARED","MOCK-1352-2021-04-03-999"))
      assertThat(PaymentDiscoveryClient.mockLookup("1352","760","UTR",value).rows()).isEmpty();
    assertThat(PaymentDiscoveryClient.mockLookup("1352","760","FCR",old.get("PIO_REF_TXN_NO").substring(0,26)+"9").rows()).isEmpty();
  }
  @Test void unsafeOrNonTlsConfiguredTargetsAreRejected(){
    for(String url:List.of("http://bank.example.test/inquiry","https://127.0.0.1/inquiry","https://localhost/inquiry","https://169.254.169.254/latest","https://user@bank.example.test/inquiry","https://bank.example.test/inquiry?secret=x","https://bank.example.test:99999/inquiry"))
      assertThatThrownBy(()->new PaymentDiscoveryClient(mapper,"BANK_API",true,url,"TEST-UAT",2,(u,b,t)->reply("{}"))).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void timeoutTransportErrorAndRedirectNeverFallBack(){
    status(()->client((u,b,t)->{throw new TimeoutException();}).fetch("1352","760",LocalDate.now(),20),504);
    status(()->client((u,b,t)->{throw new TimeoutException();}).lookup("1352","760","FCR","REF-1"),504);
    status(()->client((u,b,t)->{throw new java.io.IOException();}).fetch("1352","760",LocalDate.now(),20),503);
    status(()->client((u,b,t)->new PaymentDiscoveryClient.Reply(302,"application/json",new byte[0])).fetch("1352","760",LocalDate.now(),20),502);
  }
  @Test void tlsFailuresExplainConfigurationWithoutLeakingDetailsRetryingOrHoldingSlots(){
    String privateDetail="secret-token https://bank.example.test/inquiry REF-PRIVATE";
    var failures=List.of(new javax.net.ssl.SSLHandshakeException("hostname mismatch "+privateDetail),
        new javax.net.ssl.SSLHandshakeException("PKIX validation failed "+privateDetail),
        new javax.net.ssl.SSLPeerUnverifiedException(privateDetail));
    AtomicInteger calls=new AtomicInteger();
    var client=client((u,b,t)->{
      int call=calls.getAndIncrement();
      if(call<failures.size())throw failures.get(call);
      return reply("{\"items\":[],\"hasMore\":false,\"observedAt\":\"2026-09-14T00:00:00Z\"}");
    });
    for(int i=0;i<failures.size();i++){
      final boolean lookup=i%2==0;
      assertThatThrownBy(()->{
        if(lookup)client.lookup("1352","760","FCR","REF-PRIVATE");
        else client.fetch("1352","760",LocalDate.of(2026,9,14),20);
      }).isInstanceOfSatisfying(ApiException.class,error->{
        assertThat(error.status).isEqualTo(503);
        assertThat(error.code).isEqualTo("DISCOVERY_TLS_ERROR");
        assertThat(error.getMessage()).contains("hostname", "certificate", "trusted")
            .doesNotContain("secret-token", "bank.example.test", "REF-PRIVATE", "PKIX");
      });
      assertThat(calls).hasValue(i+1);
    }
    assertThat(client.fetch("1352","760",LocalDate.of(2026,9,14),20).rows()).isEmpty();
    assertThat(calls).hasValue(4);
  }
  @Test void malformedAndUnexpectedUpstreamFieldsReturn502(){
    for(String body:List.of("{}","{\"items\":[],\"hasMore\":false,\"observedAt\":\"bad\"}","{\"items\":[],\"hasMore\":false,\"observedAt\":\"2026-09-14T00:00:00Z\",\"extra\":1}","{\"items\":[{\"PIO_REF_TXN_NO\":12345678901234567890}],\"hasMore\":false,\"observedAt\":\"2026-09-14T00:00:00Z\"}"))
      status(()->client((u,b,t)->reply(body)).fetch("1352","760",LocalDate.now(),20),502);
  }
  @Test void responseByteLimitCancelsStreamingBeforeUnboundedAllocation(){
    var body=new PaymentDiscoveryClient.LimitedBody();AtomicBoolean cancelled=new AtomicBoolean();
    body.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){cancelled.set(true);}});
    body.onNext(List.of(ByteBuffer.allocate(PaymentDiscoveryClient.MAX_BYTES),ByteBuffer.wrap(new byte[]{1})));
    assertThat(cancelled).isTrue();assertThat(body.getBody().toCompletableFuture()).isCompletedExceptionally();
  }
  @Test void canonicalTransportPreservesNullSubsequenceButRejectsOtherNullAndNumericCells(){
    String body="{\"items\":[{\"REF_SUBSEQ_NO\":null}],\"hasMore\":false,\"observedAt\":\"2026-09-14T00:00:00Z\"}";
    var result=client((u,b,t)->reply(body)).lookup("1352","760","FCR","REF-1");
    assertThat(result.rows()).hasSize(1);
    assertThat(result.rows().get(0)).containsEntry("REF_SUBSEQ_NO",null);
    for(String invalid:List.of(body.replace("REF_SUBSEQ_NO","PIO_REF_TXN_NO"),body.replace("null","0")))
      status(()->client((u,b,t)->reply(invalid)).lookup("1352","760","FCR","REF-1"),502);
  }
}
