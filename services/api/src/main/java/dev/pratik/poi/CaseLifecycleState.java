package dev.pratik.poi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Sidecar lifecycle: never decorate the original stored payment or evidence body. */
final class CaseLifecycleState {
  private CaseLifecycleState() {}
  record State(String state,long version) {}
  static State load(JdbcTemplate db,String tenant,String id) {
    var rows=db.query("SELECT state,version FROM fcr_case_lifecycle WHERE tenant_id=? AND case_id=?",
        (rs,n)->new State(rs.getString(1),rs.getLong(2)),tenant,id);
    return rows.isEmpty()?new State("ACTIVE",0):rows.get(0);
  }
  static ObjectNode decorate(JdbcTemplate db,String tenant,ObjectNode item) {
    ObjectNode copy=item.deepCopy();apply(copy,load(db,tenant,item.path("id").asText()));return copy;
  }
  static void decorateAll(JdbcTemplate db,String tenant,List<ObjectNode> items) {
    Map<String,State> states=new HashMap<>();
    db.query("SELECT case_id,state,version FROM fcr_case_lifecycle WHERE tenant_id=?",rs->{
      states.put(rs.getString(1),new State(rs.getString(2),rs.getLong(3)));},tenant);
    items.forEach(item->apply(item,states.getOrDefault(item.path("id").asText(),new State("ACTIVE",0))));
  }
  private static void apply(ObjectNode item,State state) { item.put("lifecycleState",state.state()).put("lifecycleVersion",state.version()); }
  static void requireReadable(JdbcTemplate db,String tenant,String id) {
    if(load(db,tenant,id).state().equals("DELETED"))throw ApiException.notFound();
  }
  static void requireActive(JdbcTemplate db,String tenant,String id) {
    String state=load(db,tenant,id).state();
    if(state.equals("DELETED"))throw ApiException.notFound();
    if(!state.equals("ACTIVE"))throw new ApiException(409,"CASE_ARCHIVED","This case is archived. Restore it before adding evidence, questions or case management changes.");
  }
}
