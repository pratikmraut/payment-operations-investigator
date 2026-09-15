package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CaseEvidenceClientTest {
  final ObjectMapper mapper = new ObjectMapper();
  static final String URL = "https://bank.example.test/fcr/evidence";
  static final String REFERENCE = "00012345678901234567890123456789012345";

  CaseEvidenceClient client(CaseEvidenceClient.Transport transport) {
    return new CaseEvidenceClient(mapper, true, URL, 15, "", transport);
  }
  CaseEvidenceClient.Reply reply(String body) {
    return new CaseEvidenceClient.Reply(200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
  }
  void failure(org.assertj.core.api.ThrowableAssert.ThrowingCallable run, int status, String code) {
    assertThatThrownBy(run).isInstanceOfSatisfying(ApiException.class, exception -> {
      assertThat(exception.status).isEqualTo(status);
      assertThat(exception.code).isEqualTo(code);
      assertThat(exception.getMessage()).doesNotContain(URL, "secret-token", REFERENCE);
    });
  }

  @Test void postsOnlySavedIdentityAndReturnsUnchangedRawJsonForDomainValidation() {
    AtomicInteger calls = new AtomicInteger();
    var client = new CaseEvidenceClient(mapper, true, URL, 15, "secret-token", (url, body, headers, timeout) -> {
      calls.incrementAndGet();
      assertThat(url.toString()).isEqualTo(URL);
      assertThat(timeout.toSeconds()).isEqualTo(15);
      assertThat(mapper.readTree(body)).isEqualTo(mapper.createObjectNode().put("reference", REFERENCE)
          .put("orgBank", "760").put("orgBranch", "1352"));
      assertThat(headers).containsExactlyInAnyOrderEntriesOf(Map.of("Content-Type", "application/json", "Accept", "application/json", "Authorization", "Bearer secret-token"));
      return reply("{\"schemaVersion\":\"fcr-case-evidence-v1\",\"payment\":{\"reference\":\"" + REFERENCE + "\"}}");
    });
    // This deliberately incomplete object tests transport preservation, not evidence validity.
    assertThat(client.fetch(REFERENCE, "760", "1352").path("payment").path("reference").asText()).isEqualTo(REFERENCE);
    assertThat(calls).hasValue(1);
    assertThat(client.config()).containsExactlyInAnyOrderEntriesOf(Map.of("enabled", true, "mode", "BANK_API"));
    assertThat(client.config().toString()).doesNotContain(URL, "secret-token");
  }

  @Test void flexcubeOptInBuildsPo02EnvelopeAndPreservesResponseWithoutInventingAcquisition() throws Exception {
    var support = new FlexcubeInquirySupport(mapper,"TESTUSER","API","20260914","EXPLICIT","Asia/Kolkata","UNKNOWN");
    AtomicInteger calls = new AtomicInteger();
    var client = new CaseEvidenceClient(mapper,true,URL,15,"secret-token","FLEXCUBE",support,(url,body,headers,timeout)->{
      calls.incrementAndGet();
      var request = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(body);
      assertThat(request.path("args1").path("referenceTransactionNumber").asText()).isEqualTo(REFERENCE);
      assertThat(request.path("args1").path("originatingBankCode").isIntegralNumber()).isTrue();
      assertThat(request.path("args1").path("originatingBranchCode").intValue()).isEqualTo(1352);
      assertThat(request.path("args0").path("serviceCode").asText()).isEqualTo("PO02");
      assertThat(request.path("args0").path("bankCode").intValue()).isEqualTo(760);
      assertThat(request.path("args0").path("transactionBranch").asText()).isEqualTo("1352");
      return reply(FlexcubeEvidenceFixtures.response(mapper,request).toString());
    });
    var result=client.fetchResult(REFERENCE,"760","1352");
    assertThat(calls).hasValue(1);
    assertThat(result.response().path("payment").path("reference").asText()).isEqualTo(REFERENCE);
    assertThat(result.response().has("acquisition")).isFalse();
    assertThat(result.upstream().path("rawResponse").path("neftPaymentEvidenceDetails").get(0).path("idRelatedRef2006").isNull()).isTrue();
    assertThat(result.upstream().toString()).doesNotContain("secret-token");
  }

  @Test void flexcubeRejectsPlatformInvalidIdentityBeforeTransportAndRequiresExplicitWireChoice() {
    var support = new FlexcubeInquirySupport(mapper,"TESTUSER","API","20260914","EXPLICIT","Asia/Kolkata","UNKNOWN");
    var client = new CaseEvidenceClient(mapper,true,URL,15,"","FLEXCUBE",support,(url,body,headers,timeout)->{throw new AssertionError("Invalid identity reached bank transport");});
    failure(()->client.fetch("R".repeat(41),"760","1352"),422,"INVALID_CASE_EVIDENCE_REQUEST");
    failure(()->client.fetch(REFERENCE,"2147483648","1352"),422,"INVALID_FLEXCUBE_SCOPE");
    failure(()->client.fetch(REFERENCE,"0760","1352"),422,"INVALID_FLEXCUBE_SCOPE");
    assertThatThrownBy(()->new CaseEvidenceClient(mapper,true,URL,15,"","AUTODETECT",support,(u,b,h,t)->reply("{}")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void missingConfigurationIsDisabledWithoutCallingAnyTransport() {
    for (var config : List.of(Map.entry(false, URL), Map.entry(true, ""), Map.entry(true, " "))) {
      var client = new CaseEvidenceClient(mapper, config.getKey(), config.getValue(), 15, "", (u,b,h,t) -> { throw new AssertionError("Disabled client called transport"); });
      assertThat(client.config()).containsExactlyInAnyOrderEntriesOf(Map.of("enabled", false, "mode", "DISABLED"));
      failure(() -> client.fetch(REFERENCE, "760", "1352"), 503, "CASE_EVIDENCE_DISABLED");
    }
  }

  @Test void rejectsUnsafeConfiguredEndpointsWithoutLeakingTheirValue() {
    for (String value : List.of("http://bank.example.test/evidence", "https://localhost/evidence", "https://localhost./evidence",
        "https://127.0.0.1/evidence", "https://2130706433/evidence", "https://0177.0.0.1/evidence", "https://[::1]/evidence",
        "https://[0:0:0:0:0:0:0:1]/evidence", "https://[::ffff:127.0.0.1]/evidence", "https://169.254.169.254/latest",
        "https://[fe80::1]/evidence", "https://[fd00:ec2::254]/latest", "https://[fd00:0ec2:0:0:0:0:0:0254]/latest",
        "https://metadata.google.internal/evidence", "https://0.0.0.0/evidence",
        "https://secret-token@bank.example.test/evidence", "https://bank.example.test/evidence?secret-token=x",
        "https://bank.example.test/evidence#secret-token", "https://bank.example.test:99999/evidence", "https://bank.example.test")) {
      assertThatThrownBy(() -> new CaseEvidenceClient(mapper, true, value, 15, "", (u,b,h,t) -> reply("{}")))
          .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(value).hasMessageNotContaining("secret-token");
    }
  }

  @Test void permitsConfiguredBankEndpointAndOmitsAuthorizationWhenNoTokenExists() {
    var client = new CaseEvidenceClient(mapper, true, "https://bank.example.test/evidence", 15, "", (url,body,headers,timeout) -> {
      assertThat(headers).doesNotContainKey("Authorization");
      return reply("{}");
    });
    assertThat(client.fetch(REFERENCE, "760", "1352").isObject()).isTrue();
  }

  @Test void rejectsUnsafeTokenAndTimeoutConfiguration() {
    for (String token : List.of("secret-token\r\nInjected: yes", "secret\ttoken", "token value", "é", "x".repeat(4097)))
      assertThatThrownBy(() -> new CaseEvidenceClient(mapper, true, URL, 15, token, (u,b,h,t) -> reply("{}")))
          .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret-token");
    for (int seconds : List.of(0, 61))
      assertThatThrownBy(() -> new CaseEvidenceClient(mapper, true, URL, seconds, "", (u,b,h,t) -> reply("{}"))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void rejectsInvalidSavedSelectorsBeforeCallingTransport() {
    var client = client((u,b,h,t) -> { throw new AssertionError("Invalid input reached transport"); });
    failure(() -> client.fetch(" bad ", "760", "1352"), 422, "INVALID_CASE_EVIDENCE_REQUEST");
    failure(() -> client.fetch(REFERENCE, "760\n", "1352"), 422, "INVALID_CASE_EVIDENCE_REQUEST");
    failure(() -> client.fetch(REFERENCE, "760", "1.5"), 422, "INVALID_CASE_EVIDENCE_REQUEST");
  }

  @Test void rejectsDuplicateKeysTrailingDocumentsAndNonObjectResponses() {
    for (String body : List.of("", "null", "[]", "{\"x\":1,\"x\":2}", "{\"nested\":{\"x\":1,\"x\":2}}", "{} {}", "{} trailing"))
      failure(() -> client((u,b,h,t) -> reply(body)).fetch(REFERENCE, "760", "1352"), 502, "INVALID_CASE_EVIDENCE_RESPONSE");
  }

  @Test void upstreamFailureWrongContentTypeAndOversizedBodyNeverReturnFallback() {
    for (int status : List.of(204, 302, 401, 404, 500))
      failure(() -> client((u,b,h,t) -> new CaseEvidenceClient.Reply(status, "application/json", "secret-token".getBytes(StandardCharsets.UTF_8))).fetch(REFERENCE,"760","1352"), 502, "CASE_EVIDENCE_UPSTREAM_ERROR");
    failure(() -> client((u,b,h,t) -> new CaseEvidenceClient.Reply(200, "text/html", new byte[0])).fetch(REFERENCE,"760","1352"), 502, "INVALID_CASE_EVIDENCE_RESPONSE");
    failure(() -> client((u,b,h,t) -> new CaseEvidenceClient.Reply(200, "application/json", new byte[CaseEvidenceClient.MAX_BYTES + 1])).fetch(REFERENCE,"760","1352"), 502, "CASE_EVIDENCE_RESPONSE_TOO_LARGE");
  }

  @Test void timeoutsAndTransportErrorsAreSanitizedAndReleaseTheConcurrencySlot() {
    AtomicInteger calls = new AtomicInteger();
    var client = client((u,b,h,t) -> { if(calls.incrementAndGet() <= 3) throw new TimeoutException("secret-token " + URL); return reply("{}"); });
    for (int i=0; i<3; i++) failure(() -> client.fetch(REFERENCE,"760","1352"), 504, "CASE_EVIDENCE_TIMEOUT");
    assertThat(client.fetch(REFERENCE,"760","1352").isObject()).isTrue();
    failure(() -> client((u,b,h,t) -> {throw new HttpTimeoutException("secret-token");}).fetch(REFERENCE,"760","1352"), 504, "CASE_EVIDENCE_TIMEOUT");
    failure(() -> client((u,b,h,t) -> {throw new java.io.IOException("secret-token " + URL);}).fetch(REFERENCE,"760","1352"), 503, "CASE_EVIDENCE_UNAVAILABLE");
  }

  @Test void tlsFailuresExplainConfigurationWithoutLeakingDetailsRetryingOrHoldingSlots() {
    String privateDetail="secret-token " + URL + " " + REFERENCE;
    var failures=List.of(new javax.net.ssl.SSLHandshakeException("hostname mismatch " + privateDetail),
        new javax.net.ssl.SSLHandshakeException("PKIX validation failed " + privateDetail),
        new javax.net.ssl.SSLPeerUnverifiedException(privateDetail));
    AtomicInteger calls=new AtomicInteger();
    var client=client((u,b,h,t)->{
      int call=calls.getAndIncrement();
      if(call<failures.size())throw failures.get(call);
      return reply("{}");
    });
    for(int i=0;i<failures.size();i++){
      assertThatThrownBy(()->client.fetch(REFERENCE,"760","1352"))
          .isInstanceOfSatisfying(ApiException.class,error->{
            assertThat(error.status).isEqualTo(503);
            assertThat(error.code).isEqualTo("CASE_EVIDENCE_TLS_ERROR");
            assertThat(error.getMessage()).contains("hostname", "certificate", "trusted")
                .doesNotContain("secret-token", URL, REFERENCE, "PKIX");
          });
      assertThat(calls).hasValue(i+1);
    }
    assertThat(client.fetch(REFERENCE,"760","1352").isObject()).isTrue();
    assertThat(calls).hasValue(4);
  }

  @Test void atMostTwoCallsCanBeInFlight() throws Exception {
    var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
    var client = client((u,b,h,t) -> {entered.countDown(); if(!release.await(5, TimeUnit.SECONDS))throw new TimeoutException(); return reply("{}");});
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> client.fetch(REFERENCE,"760","1352"));
      var second = executor.submit(() -> client.fetch(REFERENCE,"760","1352"));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      failure(() -> client.fetch(REFERENCE,"760","1352"), 503, "CASE_EVIDENCE_BUSY");
      release.countDown(); assertThat(first.get(5,TimeUnit.SECONDS).isObject()).isTrue(); assertThat(second.get(5,TimeUnit.SECONDS).isObject()).isTrue();
    } finally { release.countDown(); executor.shutdownNow(); }
  }

  @Test void streamedBodyIsCancelledAtTheBoundAndSmallBodyIsPreserved() {
    var subscriber = new CaseEvidenceClient.LimitedBody(); AtomicBoolean cancelled = new AtomicBoolean();
    subscriber.onSubscribe(new Flow.Subscription() {public void request(long n){}public void cancel(){cancelled.set(true);}});
    subscriber.onNext(List.of(ByteBuffer.allocate(CaseEvidenceClient.MAX_BYTES),ByteBuffer.wrap(new byte[]{1})));
    assertThat(cancelled).isTrue(); assertThat(subscriber.getBody().toCompletableFuture()).isCompletedExceptionally();
    var small = new CaseEvidenceClient.LimitedBody();
    small.onSubscribe(new Flow.Subscription() {public void request(long n){}public void cancel(){throw new AssertionError();}});
    small.onNext(List.of(ByteBuffer.wrap("{}".getBytes(StandardCharsets.UTF_8)))); small.onComplete();
    assertThat(small.getBody().toCompletableFuture().join()).isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
  }
}
