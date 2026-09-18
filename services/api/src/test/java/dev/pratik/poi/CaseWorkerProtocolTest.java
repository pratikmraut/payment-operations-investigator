package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class CaseWorkerProtocolTest {
  final ObjectMapper mapper = new ObjectMapper();

  @SuppressWarnings("unchecked")
  HttpClient transport(UatWorkerClient client,int status,String content) {
    HttpClient transport=mock(HttpClient.class);
    HttpResponse<byte[]> response=mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type",List.of("application/json")),(a,b)->true));
    when(response.body()).thenReturn(content.getBytes(StandardCharsets.UTF_8));
    when(transport.sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
        .thenReturn(CompletableFuture.completedFuture(response));
    ReflectionTestUtils.setField(client,"client",transport);
    return transport;
  }

  @Test void preflightAndJobControlUseShortAuthenticatedFixedRoutes() {
    for(String operation:List.of("preflight","submit","status","cancel","forget-case")) {
      UatWorkerClient client=new UatWorkerClient(mapper,"http://worker.invalid/","synthetic-service-key",930);
      HttpClient transport=transport(client,200,"{\"status\":\"QUEUED\"}");
      ObjectNode input=mapper.createObjectNode().put("jobId","CIN-synthetic");
      switch(operation) {
        case "preflight" -> client.preflightCase(input);
        case "submit" -> client.submitCaseJob(input);
        case "status" -> client.caseJobStatus(input);
        case "cancel" -> client.cancelCaseJob(input);
        case "forget-case" -> client.forgetCaseJobs(input);
      }
      var request=ArgumentCaptor.forClass(HttpRequest.class);
      verify(transport).sendAsync(request.capture(),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
      assertThat(request.getValue().uri().getPath()).isEqualTo(operation.equals("preflight")?"/case/preflight":"/case/jobs/"+operation);
      assertThat(request.getValue().timeout()).contains(Duration.ofSeconds(10));
      assertThat(request.getValue().method()).isEqualTo("POST");
      assertThat(request.getValue().headers().firstValue("X-Service-Key")).contains("synthetic-service-key");
      assertThat(request.getValue().uri().getQuery()).isNull();
    }
  }

  @Test void onlyExplicitReceiptNotFoundAllowsCallerToResubmit() {
    for(String body:List.of("{\"detail\":{\"code\":\"CASE_WORKER_JOB_NOT_FOUND\"}}","{\"code\":\"CASE_WORKER_JOB_NOT_FOUND\"}")) {
      UatWorkerClient client=new UatWorkerClient(mapper,"http://worker.invalid","synthetic-service-key",930);
      transport(client,404,body);
      assertThatThrownBy(()->client.caseJobStatus(mapper.createObjectNode()))
          .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("CASE_WORKER_JOB_NOT_FOUND"));
    }
    UatWorkerClient unavailable=new UatWorkerClient(mapper,"http://worker.invalid","synthetic-service-key",930);
    transport(unavailable,404,"{\"detail\":\"Private endpoint unavailable\"}");
    assertThatThrownBy(()->unavailable.caseJobStatus(mapper.createObjectNode()))
        .isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.code).isEqualTo("UAT_MODEL_UNAVAILABLE");assertThat(e.getMessage()).doesNotContain("Private endpoint");});
  }

  @Test void conflictingOrFullWorkerQueuesRemainDistinctFromLostResponses() {
    for(Object[] test:List.of(new Object[]{409,"CASE_WORKER_JOB_CONFLICT"},new Object[]{429,"CASE_WORKER_QUEUE_FULL"})) {
      UatWorkerClient client=new UatWorkerClient(mapper,"http://worker.invalid","synthetic-service-key",930);
      transport(client,(int)test[0],"{\"detail\":\"Do not expose private request\"}");
      assertThatThrownBy(()->client.submitCaseJob(mapper.createObjectNode()))
          .isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.code).isEqualTo(test[1]);assertThat(e.getMessage()).doesNotContain("private request");});
    }
  }
}
