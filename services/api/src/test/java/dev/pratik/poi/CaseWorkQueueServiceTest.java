package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import org.junit.jupiter.api.*;

class CaseWorkQueueServiceTest {
  final ObjectMapper mapper=new ObjectMapper();
  final Actor analyst=new Actor("analyst","Analyst","ANALYST","northstar");
  final Actor other=new Actor("other","Other","ANALYST","silverline");
  PaymentDiscoveryService cases; CaseManagementService management; CaseWorkQueueService service;
  @BeforeEach void setup() {
    cases=mock(PaymentDiscoveryService.class); management=mock(CaseManagementService.class);
    var record=mapper.createObjectNode().put("id","case-a").put("caseNumber","2026091600001").put("reference","SYNTHETIC-REFERENCE").put("status","OPEN");
    var result=mapper.createObjectNode();result.putArray("items").add(record);
    when(cases.cases(analyst)).thenReturn(result);when(cases.cases(other)).thenReturn(mapper.createObjectNode().set("items",mapper.createArrayNode()));
    var state=mapper.createObjectNode().put("status","AWAITING_EVIDENCE").putNull("owner");
    var requests=state.putArray("evidenceRequests");
    requests.add(request("overdue","2026-09-16","analyst","OPEN"));
    requests.add(request("today","2026-09-17",null,"OPEN"));
    requests.add(request("undated",null,"analyst","OPEN"));
    requests.add(request("fulfilled","2020-01-01","analyst","FULFILLED"));
    when(management.detail(analyst,"case-a")).thenReturn(state);
    service=new CaseWorkQueueService(mapper,cases,management,Clock.fixed(Instant.parse("2026-09-16T20:00:00Z"),ZoneId.of("Asia/Kolkata")));
  }
  ObjectNode request(String id,String due,String assignee,String status) {
    var row=mapper.createObjectNode().put("id",id).put("title","Collect source "+id).put("dueDate",due).put("status",status).put("createdAt","2026-09-15T00:00:00Z");
    if(assignee==null)row.putNull("assignee");else row.putObject("assignee").put("id",assignee).put("name","Analyst");
    return row;
  }
  @Test void usesCalendarTimezoneAndExcludesFulfilledRequests() {
    var result=service.list(analyst,"ALL",0,10);
    assertThat(result.path("today").asText()).isEqualTo("2026-09-17");
    assertThat(result.path("openCount").asInt()).isEqualTo(3);assertThat(result.path("overdueCount").asInt()).isEqualTo(1);
    assertThat(result.path("mineCount").asInt()).isEqualTo(2);
    assertThat(result.path("items").get(0).path("daysOverdue").asInt()).isEqualTo(1);
    assertThat(result.path("items").get(1).path("daysOverdue").asInt()).isZero();
    assertThat(result.path("items").get(2).path("dueDate").isNull()).isTrue();
  }
  @Test void filtersAssignedRequestsThenPagesWithoutUnfilteredCountsChanging() {
    var first=service.list(analyst,"MINE",0,1);
    var second=service.list(analyst,"MINE",1,1);
    assertThat(first.path("total").asInt()).isEqualTo(2);assertThat(first.path("hasMore").asBoolean()).isTrue();
    assertThat(second.path("items").get(0).path("requestId").asText()).isEqualTo("undated");
    assertThat(second.path("hasMore").asBoolean()).isFalse();
    assertThat(service.list(analyst,"OVERDUE",0,10).path("items").size()).isEqualTo(1);
    assertThat(service.list(analyst,"ALL",Integer.MAX_VALUE,25).path("items")).isEmpty();
  }
  @Test void onlyProjectsCasesReturnedForTheCurrentActor() {
    assertThat(service.list(other,"ALL",0,10).path("items")).isEmpty();verify(management,never()).detail(eq(other),anyString());
    verify(cases).cases(other);
  }
  @Test void invalidQueryRejectedBeforeReadingCases() {
    for(String view:new String[]{"all","","PRIVATE"})assertThatThrownBy(()->service.list(analyst,view,0,10)).isInstanceOf(ApiException.class);
    assertThatThrownBy(()->service.list(analyst,"ALL",-1,10)).isInstanceOf(ApiException.class);
    assertThatThrownBy(()->service.list(analyst,"ALL",0,26)).isInstanceOf(ApiException.class);verifyNoInteractions(cases);
  }
  @Test void suppressesFollowUpsWhenCaseIsArchivedOrResolvedAfterInitialListRead() {
    ObjectNode current=management.detail(analyst,"case-a");
    current.put("lifecycleState","ARCHIVED");
    assertThat(service.list(analyst,"ALL",0,10).path("items")).isEmpty();
    current.put("lifecycleState","ACTIVE").put("status","RESOLVED");
    assertThat(service.list(analyst,"ALL",0,10).path("openCount").asInt()).isZero();
  }
}
