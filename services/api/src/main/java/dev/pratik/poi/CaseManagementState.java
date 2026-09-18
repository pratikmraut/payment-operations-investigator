package dev.pratik.poi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only projection. Management never overwrites original discovery or evidence JSON. */
final class CaseManagementState {
  private CaseManagementState() { }

  static ObjectNode load(ObjectMapper mapper, JdbcTemplate db, String tenant, ObjectNode item) {
    var rows = db.queryForList("SELECT version,owner_id,priority,updated_at FROM fcr_case_management WHERE tenant_id=? AND case_id=?", tenant, item.path("id").asText());
    ObjectNode state=rows.isEmpty() ? initial(mapper, item) : from(mapper, rows.get(0));
    state.put("status",item.path("status").asText("OPEN"));
    for(String body:db.queryForList("SELECT body FROM fcr_case_management_event WHERE tenant_id=? AND case_id=? AND action='WORKFLOW_CHANGED' ORDER BY version DESC LIMIT 1",String.class,tenant,item.path("id").asText()))
      state.put("status",workflowStatus(mapper,body));
    return state;
  }

  static ObjectNode initial(ObjectMapper mapper, ObjectNode item) {
    return mapper.createObjectNode().put("version", 0).putNull("ownerId")
        .put("priority", item.path("priority").asText("MEDIUM")).put("status",item.path("status").asText("OPEN")).putNull("updatedAt");
  }

  static ObjectNode from(ObjectMapper mapper, Map<String,Object> row) {
    ObjectNode result = mapper.createObjectNode().put("version", ((Number)row.get("version")).longValue())
        .put("priority", (String)row.get("priority")).put("updatedAt", (String)row.get("updated_at"));
    if(row.get("owner_id") == null) result.putNull("ownerId"); else result.put("ownerId", (String)row.get("owner_id"));
    return result;
  }

  static ObjectNode owner(ObjectMapper mapper, String tenant, String id) {
    if(id == null) return null;
    Actor actor = Actor.knownActors().stream().filter(a -> a.id().equals(id) && a.tenantId().equals(tenant)
        && !a.role().equals("VIEWER")).findFirst().orElseThrow(() -> new ApiException(503,"CASE_MANAGEMENT_STORAGE","The saved case owner is unavailable."));
    return mapper.createObjectNode().put("id", actor.id()).put("name", actor.name());
  }

  static ObjectNode overlay(ObjectMapper mapper, String tenant, ObjectNode item, ObjectNode state) {
    ObjectNode result = item.deepCopy();
    result.put("priority", state.path("priority").asText()).put("managementVersion", state.path("version").asLong());
    result.put("status",state.path("status").asText(item.path("status").asText("OPEN")));
    result.set("owner", owner(mapper, tenant, state.path("ownerId").isNull() ? null : state.path("ownerId").asText()));
    if(state.hasNonNull("updatedAt") && Instant.parse(state.path("updatedAt").asText()).isAfter(Instant.parse(item.path("updatedAt").asText())))
      result.set("updatedAt", state.get("updatedAt"));
    return result;
  }

  static void overlayAll(ObjectMapper mapper, JdbcTemplate db, String tenant, List<ObjectNode> items) {
    Map<String,ObjectNode> states = new HashMap<>();
    for(var row : db.queryForList("SELECT case_id,version,owner_id,priority,updated_at FROM fcr_case_management WHERE tenant_id=?", tenant))
      states.put((String)row.get("case_id"), from(mapper,row));
    for(var row:db.queryForList("SELECT case_id,body FROM fcr_case_management_event WHERE tenant_id=? AND action='WORKFLOW_CHANGED' ORDER BY version",tenant)) {
      ObjectNode state=states.get((String)row.get("case_id"));
      if(state==null)throw new ApiException(503,"CASE_MANAGEMENT_STORAGE","The saved case workflow state could not be verified.");
      state.put("status",workflowStatus(mapper,(String)row.get("body")));
    }
    items.replaceAll(item -> overlay(mapper,tenant,item,states.getOrDefault(item.path("id").asText(),initial(mapper,item))));
  }

  private static String workflowStatus(ObjectMapper mapper,String body) {
    try {
      String status=mapper.readTree(body).path("data").path("status").asText();
      if(!Set.of("OPEN","INVESTIGATING","AWAITING_EVIDENCE","AWAITING_REVIEW","RESOLVED").contains(status))throw new IllegalArgumentException();
      return status;
    } catch(Exception failure) {throw new ApiException(503,"CASE_MANAGEMENT_STORAGE","The saved case workflow state could not be verified.");}
  }
}
