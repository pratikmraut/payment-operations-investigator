package dev.pratik.poi;

import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Database admission, fair actor turns and a fenced lease for the single local model lane. */
final class CaseJobCoordinator {
  static final int GLOBAL_CAPACITY=32, ACTOR_CAPACITY=4;
  static final long LEASE_MILLIS=30_000;
  record Claim(String tenant,String caseId,String jobId,String actorId,long fence) { }
  private final JdbcTemplate db;private final TransactionTemplate tx;private final Clock clock;
  private final String owner=UUID.randomUUID().toString();
  CaseJobCoordinator(JdbcTemplate db,TransactionTemplate tx,Clock clock){this.db=db;this.tx=tx;this.clock=clock;}
  Map<String,Object> lock() {return db.queryForMap("SELECT * FROM fcr_case_dispatcher WHERE id=1 FOR UPDATE");}
  void checkCapacity(String tenant,String actor) {
    int all=db.queryForObject("SELECT COUNT(*) FROM fcr_case_investigation WHERE status IN ('QUEUED','RUNNING')",Integer.class);
    int mine=db.queryForObject("SELECT COUNT(*) FROM fcr_case_investigation WHERE tenant_id=? AND actor_id=? AND status IN ('QUEUED','RUNNING')",Integer.class,tenant,actor);
    if(all>=GLOBAL_CAPACITY || mine>=ACTOR_CAPACITY)throw new ApiException(429,"CASE_INVESTIGATION_BUSY",mine>=ACTOR_CAPACITY?
        "You already have four queued or running questions. Wait for one to finish or cancel a question before submitting another.":"The local investigation queue has 32 saved questions. Wait for a question to finish before submitting another.");
  }
  void add(String tenant,String caseId,String id,String actor,long seconds,int nanos) {
    db.update("INSERT INTO fcr_case_job_queue(job_id,tenant_id,case_id,actor_id,requested_seconds,requested_nanos,eligible_at) VALUES(?,?,?,?,?,?,0)",id,tenant,caseId,actor,seconds,nanos);
  }
  Claim claim() {
    return tx.execute(status->{
      Map<String,Object> lane=lock();long now=clock.millis();String holder=(String)lane.get("lease_owner"),jobId=(String)lane.get("job_id");
      long fence=((Number)lane.get("fence")).longValue(),until=((Number)lane.get("lease_until")).longValue();
      if(holder!=null&&!holder.equals(owner)&&until>now)return null;
      Map<String,Object> selected=null;
      if(jobId!=null) {
        var current=db.queryForList("SELECT q.*,j.status FROM fcr_case_job_queue q JOIN fcr_case_investigation j ON j.id=q.job_id WHERE q.job_id=?",jobId);
        if(!current.isEmpty() && Set.of("QUEUED","RUNNING").contains(current.get(0).get("status"))) {
          selected=current.get(0);
          if(((Number)selected.get("eligible_at")).longValue()>now)return null;
        }
      }
      if(selected==null) {
        var candidates=db.queryForList("SELECT q.* FROM fcr_case_job_queue q JOIN fcr_case_investigation j ON j.id=q.job_id"
            +" LEFT JOIN fcr_case_dispatch_fair f ON f.tenant_id=q.tenant_id AND f.actor_id=q.actor_id"
            +" WHERE j.status IN ('QUEUED','RUNNING') AND q.eligible_at<=?"
            +" ORDER BY COALESCE(f.last_turn,0),q.requested_seconds,q.requested_nanos,q.job_id LIMIT 1",now);
        if(candidates.isEmpty()){db.update("UPDATE fcr_case_dispatcher SET job_id=NULL,lease_owner=NULL,lease_until=0 WHERE id=1");return null;}
        selected=candidates.get(0);long turn=((Number)lane.get("turn_number")).longValue()+1;
        if(db.update("UPDATE fcr_case_dispatch_fair SET last_turn=? WHERE tenant_id=? AND actor_id=?",turn,selected.get("tenant_id"),selected.get("actor_id"))==0)
          db.update("INSERT INTO fcr_case_dispatch_fair(tenant_id,actor_id,last_turn) VALUES(?,?,?)",selected.get("tenant_id"),selected.get("actor_id"),turn);
        db.update("UPDATE fcr_case_dispatcher SET turn_number=? WHERE id=1",turn);
      }
      String chosen=(String)selected.get("job_id");
      if(!owner.equals(holder)||!chosen.equals(jobId)||until<=now)fence++;
      db.update("UPDATE fcr_case_dispatcher SET job_id=?,lease_owner=?,lease_until=?,fence=? WHERE id=1",chosen,owner,now+LEASE_MILLIS,fence);
      return new Claim((String)selected.get("tenant_id"),(String)selected.get("case_id"),chosen,(String)selected.get("actor_id"),fence);
    });
  }
  <T> T fenced(Claim claim,Supplier<T> write) {
    return tx.execute(status->{Map<String,Object> lane=lock();if(!owns(lane,claim))return null;return write.get();});
  }
  private boolean owns(Map<String,Object> lane,Claim claim) {
    return owner.equals(lane.get("lease_owner"))&&claim.jobId().equals(lane.get("job_id"))&&claim.fence()==((Number)lane.get("fence")).longValue()
        &&((Number)lane.get("lease_until")).longValue()>clock.millis();
  }
  boolean heartbeat(Claim claim) {return Boolean.TRUE.equals(fenced(claim,()->{db.update("UPDATE fcr_case_dispatcher SET lease_until=? WHERE id=1",clock.millis()+LEASE_MILLIS);return true;}));}
  void done(Claim claim) {db.update("UPDATE fcr_case_dispatcher SET job_id=NULL,lease_owner=NULL,lease_until=0 WHERE id=1 AND job_id=? AND fence=? AND lease_owner=?",claim.jobId(),claim.fence(),owner);}
  void later(Claim claim,long millis) {db.update("UPDATE fcr_case_job_queue SET eligible_at=? WHERE job_id=?",clock.millis()+millis,claim.jobId());}
  void close() {tx.executeWithoutResult(s->{lock();db.update("UPDATE fcr_case_dispatcher SET lease_owner=NULL,lease_until=0 WHERE id=1 AND lease_owner=?",owner);});}
}
