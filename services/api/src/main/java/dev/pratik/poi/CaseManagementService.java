package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Human case coordination and reviewed operational closure. No payment action, automatic inquiry or model call. */
@Service
public class CaseManagementService {
  static final int MAX_BYTES = 32768;
  private static final Set<String> PRIORITIES = Set.of("LOW","MEDIUM","HIGH","CRITICAL");
  private final ObjectMapper mapper;
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final PaymentDiscoveryService cases;
  private final CaseEvidenceService evidence;
  private final CaseInvestigationService investigations;

  public CaseManagementService(ObjectMapper mapper, JdbcTemplate db, TransactionTemplate tx,
      PaymentDiscoveryService cases, CaseEvidenceService evidence, CaseInvestigationService investigations) {
    this.mapper=mapper; this.db=db; this.tx=tx; this.cases=cases; this.evidence=evidence; this.investigations=investigations;
  }

  /** Authorized coherent snapshot for the UI and saved case reports; no read causes a write. */
  public ObjectNode detail(Actor actor, String caseId) {
    cases.caseRecord(actor,caseId);
    return tx.execute(status -> {
      ObjectNode item=lockCase(actor,caseId);
      return view(actor,item,CaseManagementState.load(mapper,db,actor.tenantId(),item));
    });
  }

  /** HTTP responses bound activity; reports and command validation retain the complete internal view. */
  static ObjectNode publicView(ObjectNode internal) {
    if(internal==null)return null;
    ObjectNode result=internal.deepCopy();
    if(!(result.get("audit") instanceof ArrayNode all))return result;
    ArrayNode page=all.arrayNode();for(int i=0;i<Math.min(all.size(),CaseHistoryService.DEFAULT_LIMIT);i++)page.add(all.get(i));
    result.set("audit",page);
    ObjectNode metadata=result.putObject("auditPage").put("total",all.size()).put("limit",CaseHistoryService.DEFAULT_LIMIT);
    if(all.size()>page.size())metadata.put("nextCursor",page.get(page.size()-1).path("id").asText());else metadata.putNull("nextCursor");
    return result;
  }

  public ObjectNode manage(Actor actor,String caseId,byte[] bytes,String key) {
    return command(actor,caseId,"MANAGEMENT_CHANGED",null,bytes,key);
  }
  public ObjectNode addNote(Actor actor,String caseId,byte[] bytes,String key) {
    return command(actor,caseId,"NOTE_ADDED",null,bytes,key);
  }
  public ObjectNode requestEvidence(Actor actor,String caseId,byte[] bytes,String key) {
    return command(actor,caseId,"EVIDENCE_REQUESTED",null,bytes,key);
  }
  public ObjectNode updateEvidenceRequest(Actor actor,String caseId,String requestId,byte[] bytes,String key) {
    return command(actor,caseId,"EVIDENCE_REQUEST_UPDATED",requestId,bytes,key);
  }
  public ObjectNode conclude(Actor actor,String caseId,byte[] bytes,String key) {
    return command(actor,caseId,"REVIEWER_CONCLUSION_RECORDED",null,bytes,key);
  }
  public ObjectNode transition(Actor actor,String caseId,byte[] bytes,String key) {
    return command(actor,caseId,"WORKFLOW_CHANGED",null,bytes,key);
  }

  private ObjectNode command(Actor actor,String caseId,String action,String target,byte[] bytes,String key) {
    actor.requireWriter(); cases.requireUnarchived(actor,caseId);
    if(action.equals("REVIEWER_CONCLUSION_RECORDED")) actor.requireReviewer();
    if(key==null || !key.matches("[A-Za-z0-9._:-]{8,200}"))
      throw new ApiException(400,"CASE_MANAGEMENT_KEY_REQUIRED","Use an Idempotency-Key of 8–200 safe characters.");
    if(bytes.length>MAX_BYTES) throw new ApiException(413,"CASE_MANAGEMENT_TOO_LARGE","Case management requests are limited to 32 KiB.");
    ObjectNode input=UatService.parseObject(mapper,bytes,invalid("Supply a JSON object with the fields for this command."));
    long expected=version(input.get("expectedVersion"));
    Set<String> required=switch(action) {
      case "MANAGEMENT_CHANGED" -> Set.of("expectedVersion","ownerId","priority","reason");
      case "NOTE_ADDED" -> Set.of("expectedVersion","text");
      case "EVIDENCE_REQUESTED" -> Set.of("expectedVersion","title","detail");
      case "EVIDENCE_REQUEST_UPDATED" -> Set.of("expectedVersion","status","note");
      case "WORKFLOW_CHANGED" -> Set.of("expectedVersion","status","reason");
      default -> Set.of("expectedVersion","evidenceId","evidenceHash","investigationIds","conclusion");
    };
    Set<String> allowed=new HashSet<>(required);
    if(action.equals("EVIDENCE_REQUESTED")) allowed.add("dueDate");
    if(action.equals("EVIDENCE_REQUEST_UPDATED")) allowed.add("evidenceId");
    if(action.equals("EVIDENCE_REQUESTED") || action.equals("EVIDENCE_REQUEST_UPDATED")) allowed.add("assigneeId");
    if(action.equals("WORKFLOW_CHANGED")) allowed.add("reviewerConclusionId");
    keys(input,required,allowed);
    ObjectNode identity=mapper.createObjectNode().put("action",action).put("target",target);
    identity.set("input",input);
    String requestHash=UatService.canonicalHash(identity);
    return tx.execute(status -> {
      ObjectNode item=lockCase(actor,caseId);
      var previous=db.queryForList("SELECT request_hash FROM fcr_case_management_command WHERE tenant_id=? AND case_id=? AND actor_id=? AND idempotency_key=?",
          actor.tenantId(),caseId,actor.id(),key);
      cases.requireUnarchived(actor,caseId);
      ObjectNode state=CaseManagementState.load(mapper,db,actor.tenantId(),item);
      if(!previous.isEmpty()) {
        if(!requestHash.equals(previous.get(0).get("request_hash")))
          throw new ApiException(409,"CASE_MANAGEMENT_KEY_CONFLICT","This retry key belongs to a different case command.");
        return view(actor,item,state);
      }
      if(!action.equals("WORKFLOW_CHANGED")) cases.requireActive(actor,caseId);
      long current=state.path("version").asLong();
      if(current!=expected || current>=JsonSupport.MAX_SAFE_INTEGER)
        throw new ApiException(409,"CASE_MANAGEMENT_VERSION_CONFLICT","Case management changed. Refresh, review the current version, and submit your draft again.");
      long next=current+1; String now=Instant.now().toString();
      ObjectNode event=mapper.createObjectNode().put("id","MGE-"+UUID.randomUUID()).put("caseId",caseId)
          .put("version",next).put("action",action).put("occurredAt",now).put("actor",actor.id()).put("actorName",actor.name());
      ObjectNode data;
      switch(action) {
        case "MANAGEMENT_CHANGED" -> {
          String priority=text(input,"priority",20);
          if(!PRIORITIES.contains(priority)) throw invalid("Choose LOW, MEDIUM, HIGH or CRITICAL priority.");
          String reason=text(input,"reason",4000); JsonNode owner=input.get("ownerId");
          if(!owner.isNull()) {
            String ownerId=text(input,"ownerId",100);
            if(eligible(actor,caseId).stream().noneMatch(a -> a.id().equals(ownerId)))
              throw invalid("Choose an eligible owner from this case's assignee list.");
          }
          if(owner.equals(state.get("ownerId")) && priority.equals(state.path("priority").asText()))
            throw invalid("Change the owner or priority before saving.");
          data=mapper.createObjectNode().put("reason",reason);
          data.set("before",state.deepCopy());
          state.set("ownerId",owner.deepCopy()); state.put("priority",priority);
          data.set("after",state.deepCopy().put("version",next).put("updatedAt",now));
          event.put("detail","Owner: "+data.path("before").path("ownerId").asText("unassigned")+" → "+owner.asText("unassigned")
              +"; priority: "+data.path("before").path("priority").asText()+" → "+priority+". "+reason);
        }
        case "NOTE_ADDED" -> {
          data=created(actor,"NOTE-",now).put("text",text(input,"text",4000));
          event.put("detail","Case note added.");
        }
        case "EVIDENCE_REQUESTED" -> {
          data=created(actor,"REQ-",now).put("title",text(input,"title",200)).put("detail",text(input,"detail",4000))
              .put("status","OPEN").put("updatedAt",now).put("updatedBy",actor.id()).put("updatedByName",actor.name())
              .putNull("evidenceId").putNull("evidenceVersion").putNull("evidenceHash");
          JsonNode due=input.get("dueDate");
          if(due==null || due.isNull()) data.putNull("dueDate");
          else {
            String date=text(input,"dueDate",10);
            try { if(!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !LocalDate.parse(date).toString().equals(date)) throw new DateTimeParseException("date",date,0); }
            catch(DateTimeParseException failure) { throw invalid("dueDate must be a valid YYYY-MM-DD date or null."); }
            data.put("dueDate",date);
          }
          data.putArray("updates"); event.put("detail","Evidence requested: "+data.path("title").asText());
          data.set("assignee",assignee(actor,caseId,input.get("assigneeId")));
        }
        case "EVIDENCE_REQUEST_UPDATED" -> {
          ObjectNode request=find(view(actor,item,state).path("evidenceRequests"),target);
          String nextStatus=text(input,"status",20), note=text(input,"note",4000);
          if(!Set.of("OPEN","FULFILLED","CANCELLED").contains(nextStatus)) throw invalid("Choose OPEN, FULFILLED or CANCELLED.");
          JsonNode assigned=input.has("assigneeId")?assignee(actor,caseId,input.get("assigneeId")):request.path("assignee").deepCopy();
          if(request.path("status").asText().equals(nextStatus) && assigned.equals(request.path("assignee"))) throw invalid("Change the evidence request status or assignee before saving.");
          data=created(actor,"REQUP-",now).put("requestId",target).put("status",nextStatus).put("note",note)
              .putNull("evidenceId").putNull("evidenceVersion").putNull("evidenceHash");
          if(nextStatus.equals("FULFILLED")) {
            String evidenceId=text(input,"evidenceId",100);
            ObjectNode snapshot=verifiedEvidence(actor,item,evidenceId);
            data.put("evidenceId",evidenceId).set("evidenceVersion",snapshot.get("version"));
            data.set("evidenceHash",snapshot.get("evidenceHash"));
          } else if(input.has("evidenceId")) throw invalid("Only a fulfilled request can select an evidence version.");
          data.set("assignee",assigned);
          event.put("detail","Evidence request marked "+nextStatus+": "+note);
        }
        case "WORKFLOW_CHANGED" -> {
          String from=state.path("status").asText("OPEN"), to=text(input,"status",30), reason=text(input,"reason",4000);
          if(!transitions(from).contains(to))throw invalid("Choose an allowed case transition. A resolved case must be explicitly reopened to INVESTIGATING.");
          if(activeJobs(actor,caseId)>0)throw new ApiException(409,"CASE_INVESTIGATION_ACTIVE","Wait for queued or running investigations to finish before changing the case workflow.");
          ObjectNode currentView=view(actor,item,state);
          data=created(actor,"FLOW-",now).put("previousStatus",from).put("status",to).put("reason",reason).putNull("reviewerConclusionId");
          if(to.equals("AWAITING_REVIEW") || to.equals("RESOLVED")) {
            if(evidence.latest(actor,caseId)==null)throw invalid("Save evidence before submitting the case for review.");
            for(JsonNode request:currentView.path("evidenceRequests"))
              if(request.path("status").asText().equals("OPEN"))throw new ApiException(409,"CASE_EVIDENCE_REQUESTS_OPEN","Fulfil or explicitly cancel outstanding evidence requests before review or resolution.");
          }
          if(to.equals("RESOLVED")) {
            actor.requireReviewer();
            if(item.path("createdBy").asText().equals(actor.id()))throw independent();
            ObjectNode conclusion=find(currentView.path("reviewerConclusions"),text(input,"reviewerConclusionId",100));
            JsonNode latest=evidence.latest(actor,caseId);
            ObjectNode snapshot=verifiedEvidence(actor,item,latest.path("id").asText());
            if(snapshot.path("createdBy").asText().equals(actor.id()))throw independent();
            if(!conclusion.path("evidenceId").equals(snapshot.path("id")) || !conclusion.path("evidenceHash").equals(snapshot.path("evidenceHash")))
              throw new ApiException(409,"CASE_REVIEW_EVIDENCE_CHANGED","Resolution requires a reviewer conclusion for the latest saved evidence version.");
            if(!conclusion.path("status").asText().equals("RECORDED"))throw storage();
            if(conclusion.path("createdBy").asText().equals(item.path("createdBy").asText()) || conclusion.path("createdBy").asText().equals(snapshot.path("createdBy").asText()))throw independent();
            // Preserve maker/checker independence even when a different reviewer resolves the case.
            for(JsonNode reviewed:conclusion.path("investigations"))
              if(reviewed.path("createdBy").asText().equals(actor.id()))throw independent();
            data.put("reviewerConclusionId",conclusion.path("id").asText()).put("evidenceId",snapshot.path("id").asText())
                .put("evidenceHash",snapshot.path("evidenceHash").asText()).put("evidenceVersion",snapshot.path("version").asInt());
          } else if(input.has("reviewerConclusionId"))throw invalid("Only resolution can select a reviewer conclusion.");
          state.put("status",to);
          event.put("detail",(from.equals("RESOLVED")?"Case reopened":"Case workflow changed")+": "+from+" → "+to+". "+reason);
        }
        default -> {
          if(item.path("createdBy").asText().equals(actor.id())) throw independent();
          String evidenceId=text(input,"evidenceId",100), hash=text(input,"evidenceHash",64);
          ObjectNode snapshot=verifiedEvidence(actor,item,evidenceId);
          if(snapshot.path("createdBy").asText().equals(actor.id()))throw independent();
          if(!hash.matches("[a-f0-9]{64}") || !hash.equals(snapshot.path("evidenceHash").asText()))
            throw new ApiException(409,"CASE_REVIEW_EVIDENCE_CHANGED","The selected evidence fingerprint differs. Refresh and select its saved version again.");
          JsonNode ids=input.get("investigationIds");
          if(ids==null || !ids.isArray() || ids.size()>20) throw invalid("Select up to 20 completed investigations, or use an empty selection for an evidence-only review.");
          Set<String> seen=new HashSet<>();
          data=created(actor,"CON-",now).put("status","RECORDED").put("conclusion",text(input,"conclusion",4000))
              .put("evidenceId",evidenceId).put("evidenceHash",hash).put("evidenceVersion",snapshot.path("version").asInt());
          ArrayNode preserved=data.putArray("investigations");
          for(JsonNode value:ids) {
            String id=textValue(value,"investigationIds",100);
            if(!seen.add(id)) throw invalid("Select each investigation only once.");
            ObjectNode job=investigations.detail(actor,caseId,id);
            if(!job.path("status").asText().equals("COMPLETED") || !job.path("evidenceId").asText().equals(evidenceId)
                || !job.path("evidenceHash").asText().equals(hash)) throw invalid("Every selected investigation must be completed against this exact evidence version.");
            if(job.path("createdBy").asText().equals(actor.id())) throw independent();
            if(!(job.get("answer") instanceof ObjectNode answer)) throw storage();
            preserved.addObject().put("id",id).put("question",job.path("question").asText())
                .put("answerId",answer.path("answerId").asText()).put("inputHash",job.path("inputHash").asText()).put("createdBy",job.path("createdBy").asText());
          }
          data.set("investigationIds",ids.deepCopy());
          data.put("reviewBasis",ids.isEmpty()?"EVIDENCE_ONLY":"EVIDENCE_AND_INVESTIGATIONS");
          event.put("detail","Independent reviewer conclusion recorded for evidence version "+snapshot.path("version").asInt()+". Case workflow status is unchanged.");
        }
      }
      event.set("data",data); state.put("version",next).put("updatedAt",now);
      if(current==0) db.update("INSERT INTO fcr_case_management(tenant_id,case_id,version,owner_id,priority,updated_at) VALUES(?,?,?,?,?,?)",
          actor.tenantId(),caseId,next,state.path("ownerId").isNull()?null:state.path("ownerId").asText(),state.path("priority").asText(),now);
      else db.update("UPDATE fcr_case_management SET version=?,owner_id=?,priority=?,updated_at=? WHERE tenant_id=? AND case_id=?",
          next,state.path("ownerId").isNull()?null:state.path("ownerId").asText(),state.path("priority").asText(),now,actor.tenantId(),caseId);
      db.update("INSERT INTO fcr_case_management_event(id,tenant_id,case_id,version,action,occurred_at,actor_id,body) VALUES(?,?,?,?,?,?,?,?)",
          event.path("id").asText(),actor.tenantId(),caseId,next,action,now,actor.id(),event.toString());
      CaseHistoryIndex.recordManagement(mapper,db,actor.tenantId(),event);
      db.update("INSERT INTO fcr_case_management_command(tenant_id,case_id,actor_id,idempotency_key,request_hash) VALUES(?,?,?,?,?)",
          actor.tenantId(),caseId,actor.id(),key,requestHash);
      cases.refreshSearch(actor.tenantId(),caseId);
      return view(actor,item,state);
    });
  }

  private ObjectNode view(Actor actor,ObjectNode item,ObjectNode state) {
    String caseId=item.path("id").asText();
    ObjectNode result=mapper.createObjectNode().put("caseId",caseId).put("version",state.path("version").asLong()).put("priority",state.path("priority").asText()).put("status",state.path("status").asText("OPEN"));
    String lifecycle=CaseLifecycleState.load(db,actor.tenantId(),caseId).state();
    result.put("lifecycleState",lifecycle);
    result.put("activeInvestigationCount",activeJobs(actor,caseId));
    ArrayNode available=result.putArray("allowedTransitions");
    if(Set.of("ANALYST","REVIEWER").contains(actor.role()) && lifecycle.equals("ACTIVE") && activeJobs(actor,caseId)==0)
      transitions(state.path("status").asText("OPEN")).stream().filter(s->!s.equals("RESOLVED") || (actor.role().equals("REVIEWER") && !item.path("createdBy").asText().equals(actor.id()))).forEach(available::add);
    result.set("owner",CaseManagementState.owner(mapper,actor.tenantId(),state.path("ownerId").isNull()?null:state.path("ownerId").asText()));
    ArrayNode assignees=result.putArray("assignees");
    for(Actor person:eligible(actor,caseId)) assignees.addObject().put("id",person.id()).put("name",person.name()).put("role",person.role());
    List<ObjectNode> notes=new ArrayList<>(), conclusions=new ArrayList<>(), audit=new ArrayList<>();
    Map<String,ObjectNode> requests=new LinkedHashMap<>(); long observedVersion=0;
    for(String body:db.queryForList("SELECT body FROM fcr_case_management_event WHERE tenant_id=? AND case_id=? ORDER BY version",String.class,actor.tenantId(),caseId)) {
      ObjectNode event=UatService.parseObject(mapper,body.getBytes(StandardCharsets.UTF_8),storage());
      if(!event.path("caseId").asText().equals(caseId) || event.path("version").asLong()!=++observedVersion || !(event.get("data") instanceof ObjectNode data)) throw storage();
      ObjectNode auditItem=event.deepCopy(); auditItem.remove(List.of("data","caseId")); audit.add(auditItem);
      switch(event.path("action").asText()) {
        case "NOTE_ADDED" -> notes.add(data.deepCopy());
        case "REVIEWER_CONCLUSION_RECORDED" -> conclusions.add(data.deepCopy());
        case "EVIDENCE_REQUESTED" -> {
          ObjectNode request=data.deepCopy();if(!request.has("assignee"))request.putNull("assignee");
          requests.put(data.path("id").asText(),request);
        }
        case "EVIDENCE_REQUEST_UPDATED" -> {
          ObjectNode request=requests.get(data.path("requestId").asText()); if(request==null) throw storage();
          for(String field:List.of("status","evidenceId","evidenceVersion","evidenceHash")) request.set(field,data.path(field).deepCopy());
          if(data.has("assignee"))request.set("assignee",data.path("assignee").deepCopy());
          request.set("updatedAt",data.path("createdAt").deepCopy()); request.set("updatedBy",data.path("createdBy").deepCopy());
          request.set("updatedByName",data.path("createdByName").deepCopy());
          ObjectNode update=data.deepCopy(); update.remove("requestId"); ((ArrayNode)request.path("updates")).add(update);
        }
        case "MANAGEMENT_CHANGED", "WORKFLOW_CHANGED" -> { }
        default -> throw storage();
      }
    }
    if(observedVersion!=state.path("version").asLong()) throw storage();
    List<ObjectNode> requestList=new ArrayList<>(requests.values());
    Collections.reverse(notes); Collections.reverse(conclusions); Collections.reverse(audit); Collections.reverse(requestList);
    result.set("notes",mapper.valueToTree(notes)); result.set("evidenceRequests",mapper.valueToTree(requestList));
    result.set("reviewerConclusions",mapper.valueToTree(conclusions)); result.set("audit",mapper.valueToTree(audit)); return result;
  }

  private List<Actor> eligible(Actor actor,String caseId) {
    return Actor.knownActors().stream().filter(a -> a.tenantId().equals(actor.tenantId()) && Set.of("ANALYST","REVIEWER").contains(a.role()))
        .filter(a -> { try { cases.caseRecord(a,caseId); return true; } catch(ApiException denied) { if(denied.status==403 || denied.status==404) return false; throw denied; } }).toList();
  }
  private JsonNode assignee(Actor actor,String caseId,JsonNode value) {
    if(value==null || value.isNull())return mapper.nullNode();
    String id=textValue(value,"assigneeId",100);
    Actor person=eligible(actor,caseId).stream().filter(a->a.id().equals(id)).findFirst().orElseThrow(()->invalid("Choose an eligible evidence-request assignee for this case."));
    return mapper.createObjectNode().put("id",person.id()).put("name",person.name());
  }
  private ObjectNode lockCase(Actor actor,String caseId) {
    if(db.queryForList("SELECT id FROM fcr_payment_case WHERE tenant_id=? AND id=? FOR UPDATE",String.class,actor.tenantId(),caseId).isEmpty()) throw ApiException.notFound();
    return cases.caseRecord(actor,caseId);
  }
  private ObjectNode verifiedEvidence(Actor actor,ObjectNode item,String id) {
    ObjectNode snapshot=evidence.detail(actor,item.path("id").asText(),id);
    if(!snapshot.path("caseId").equals(item.path("id"))) throw storage();
    for(String field:List.of("reference","orgBank","orgBranch"))
      if(!snapshot.path("payload").path("payment").path(field).equals(item.path(field))) throw storage();
    if(!CaseEvidenceService.fingerprint(snapshot).equals(snapshot.path("evidenceHash").asText())) throw storage();
    return snapshot;
  }
  private ObjectNode created(Actor actor,String prefix,String now) {
    return mapper.createObjectNode().put("id",prefix+UUID.randomUUID()).put("createdAt",now).put("createdBy",actor.id()).put("createdByName",actor.name());
  }
  private ObjectNode find(JsonNode array,String id) {
    for(JsonNode value:array) if(value.path("id").asText().equals(id)) return (ObjectNode)value;
    throw ApiException.notFound();
  }
  private int activeJobs(Actor actor,String caseId) {
    return db.queryForObject("SELECT COUNT(*) FROM fcr_case_investigation WHERE tenant_id=? AND case_id=? AND status IN ('QUEUED','RUNNING')",Integer.class,actor.tenantId(),caseId);
  }
  private static List<String> transitions(String status) {
    return switch(status) {
      case "OPEN" -> List.of("INVESTIGATING","AWAITING_EVIDENCE");
      case "INVESTIGATING" -> List.of("AWAITING_EVIDENCE","AWAITING_REVIEW");
      case "AWAITING_EVIDENCE" -> List.of("INVESTIGATING","AWAITING_REVIEW");
      case "AWAITING_REVIEW" -> List.of("INVESTIGATING","AWAITING_EVIDENCE","RESOLVED");
      case "RESOLVED" -> List.of("INVESTIGATING");
      default -> List.of();
    };
  }
  private static long version(JsonNode node) {
    if(node==null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue()<0 || node.longValue()>JsonSupport.MAX_SAFE_INTEGER)
      throw invalid("expectedVersion must be the current nonnegative management version.");
    return node.longValue();
  }
  private static void keys(ObjectNode value,Set<String> required,Set<String> allowed) {
    Set<String> actual=new HashSet<>();value.fieldNames().forEachRemaining(actual::add);
    if(!actual.containsAll(required) || !allowed.containsAll(actual)) throw invalid("Supply only the required fields for this command.");
  }
  private static String text(ObjectNode value,String name,int max) { return textValue(value.get(name),name,max); }
  private static String textValue(JsonNode value,String name,int max) {
    if(value==null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length()>max
        || value.textValue().chars().anyMatch(c -> Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t'))
      throw invalid(name+": supply nonblank text of at most "+max+" characters.");
    return value.textValue();
  }
  private static ApiException independent() { return new ApiException(403,"MAKER_CHECKER_REQUIRED","The reviewer must be different from the case creator, selected evidence creator and every selected investigation creator."); }
  private static ApiException invalid(String message) { return new ApiException(422,"INVALID_CASE_MANAGEMENT",message); }
  private static ApiException storage() { return new ApiException(503,"CASE_MANAGEMENT_STORAGE","The saved case management records could not be verified."); }
}
