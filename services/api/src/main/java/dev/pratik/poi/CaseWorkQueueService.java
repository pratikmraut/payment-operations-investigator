package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Read-only follow-ups for authorized active cases. Due dates are calendar dates in the displayed zone. */
@Service
public class CaseWorkQueueService {
  private final ObjectMapper mapper;
  private final PaymentDiscoveryService cases;
  private final CaseManagementService management;
  private final Clock clock;
  @Autowired public CaseWorkQueueService(ObjectMapper mapper,PaymentDiscoveryService cases,
      CaseManagementService management,@Value("${poi.case-management.timezone:Asia/Kolkata}") String timezone) {
    this(mapper,cases,management,Clock.system(ZoneId.of(timezone)));
  }
  CaseWorkQueueService(ObjectMapper mapper,PaymentDiscoveryService cases,CaseManagementService management,Clock clock) {
    this.mapper=mapper;this.cases=cases;this.management=management;this.clock=clock;
  }
  ObjectNode list(Actor actor,String view,int offset,int limit) {
    if(!Set.of("ALL","MINE","OVERDUE").contains(view)||offset<0||limit<1||limit>25)
      throw new ApiException(422,"CASE_WORK_QUERY_INVALID","Choose ALL, MINE or OVERDUE, a nonnegative offset and a limit of 1–25.");
    LocalDate today=LocalDate.now(clock); var rows=new ArrayList<ObjectNode>();
    for(JsonNode item:cases.cases(actor).path("items")) {
      if(item.path("status").asText().equals("RESOLVED"))continue;
      String caseId=item.path("id").asText();
      // Reuse the same verified management projection as the case and reports.
      ObjectNode state;
      try { state=management.detail(actor,caseId); }
      catch(ApiException error) { if(error.status==404)continue; throw error; }
      if(!state.path("lifecycleState").asText("ACTIVE").equals("ACTIVE") || state.path("status").asText().equals("RESOLVED"))continue;
      for(JsonNode request:state.path("evidenceRequests")) {
        if(!request.path("status").asText().equals("OPEN"))continue;
        ObjectNode row=mapper.createObjectNode().put("caseId",caseId)
            .put("caseNumber",item.path("caseNumber").asText(caseId)).put("reference",item.path("reference").asText())
            .put("caseStatus",state.path("status").asText(item.path("status").asText()))
            .put("requestId",request.path("id").asText()).put("title",request.path("title").asText())
            .put("createdAt",request.path("createdAt").asText());
        row.set("assignee",request.path("assignee").isObject()?request.path("assignee").deepCopy():mapper.nullNode());
        row.set("caseOwner",state.path("owner").deepCopy());
        long overdue=0;
        if(request.hasNonNull("dueDate")) {
          String due=request.path("dueDate").asText();
          try { overdue=Math.max(0,ChronoUnit.DAYS.between(LocalDate.parse(due),today)); }
          catch(DateTimeException error) {throw new ApiException(503,"CASE_WORK_DATE_INVALID","An evidence request has an invalid saved due date.");}
          row.put("dueDate",due);
        } else row.putNull("dueDate");
        row.put("daysOverdue",overdue);rows.add(row);
      }
    }
    long overdueCount=rows.stream().filter(row->row.path("daysOverdue").asLong()>0).count();
    long mineCount=rows.stream().filter(row->row.path("assignee").path("id").asText().equals(actor.id())).count();
    int openCount=rows.size();
    rows.removeIf(row->view.equals("MINE")&&!row.path("assignee").path("id").asText().equals(actor.id())
        ||view.equals("OVERDUE")&&row.path("daysOverdue").asLong()==0);
    rows.sort(Comparator.comparing((ObjectNode row)->row.path("dueDate").isNull()?"9999-12-31":row.path("dueDate").asText())
        .thenComparing(row->row.path("createdAt").asText()).thenComparing(row->row.path("requestId").asText()));
    ObjectNode result=mapper.createObjectNode().put("view",view).put("today",today.toString()).put("timezone",clock.getZone().getId())
        .put("total",rows.size()).put("offset",offset).put("limit",limit).put("openCount",openCount).put("overdueCount",overdueCount).put("mineCount",mineCount);
    int start=Math.min(offset,rows.size()),end=(int)Math.min((long)start+limit,rows.size());
    result.set("items",mapper.valueToTree(rows.subList(start,end)));result.put("hasMore",end<rows.size());return result;
  }
}
