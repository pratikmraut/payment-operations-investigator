package dev.pratik.poi;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Discovery metadata and immutable private cases; no model or payment operation. */
@Service
public class PaymentDiscoveryService {
  static final Set<String> REQUIRED=Set.of("PIO_REF_TXN_NO","PIO_ORG_BRN","PIO_ORG_BANK","REF_SUBSEQ_NO","UTR_REF_NO","DATINITIATION","NUMAMOUNT_4038");
  static final Set<String> OPTIONAL=Set.of("CURRENCY","DIRECTION");
  record Scope(String branch,String bank) {}
  private final ObjectMapper mapper; private final JdbcTemplate db; private final TransactionTemplate transactions;
  private final PaymentDiscoveryClient client; private final Map<String,List<Scope>> scopes=new LinkedHashMap<>();
  private final CaseNumberService caseNumbers;
  private final CaseSearchIndex searchIndex;
  @Autowired
  public PaymentDiscoveryService(ObjectMapper mapper,JdbcTemplate db,TransactionTemplate transactions,PaymentDiscoveryClient client,
      @Value("${poi.payment-discovery.scopes:northstar:1352:760,silverline:2468:760}") String configuredScopes,CaseNumberService caseNumbers,CaseSearchIndex searchIndex) {
    this.mapper=mapper;this.db=db;this.transactions=transactions;this.client=client;this.caseNumbers=caseNumbers;this.searchIndex=searchIndex;
    for(String entry:configuredScopes.split(",")) {
      String[] parts=entry.trim().split(":",-1);
      if(parts.length!=3 || !parts[0].matches("[A-Za-z0-9_-]{1,100}") || !parts[1].matches("[0-9]{1,10}") || !parts[2].matches("[0-9]{1,10}")) throw new IllegalArgumentException("Invalid server discovery scopes.");
      List<Scope> tenant=scopes.computeIfAbsent(parts[0],k->new ArrayList<>());
      if(tenant.stream().anyMatch(s->s.branch.equals(parts[1])&&s.bank.equals(parts[2]))) throw new IllegalArgumentException("Duplicate bank and branch scope.");
      tenant.add(new Scope(parts[1],parts[2]));
    }
  }
  public PaymentDiscoveryService(ObjectMapper mapper,JdbcTemplate db,TransactionTemplate transactions,PaymentDiscoveryClient client,String configuredScopes) {
    this(mapper,db,transactions,client,configuredScopes,new CaseNumberService(db,transactions));
  }
  public PaymentDiscoveryService(ObjectMapper mapper,JdbcTemplate db,TransactionTemplate transactions,PaymentDiscoveryClient client,String configuredScopes,CaseNumberService numbers) {
    this(mapper,db,transactions,client,configuredScopes,numbers,new CaseSearchIndex(mapper,db,transactions,numbers));
  }
  CaseSearchIndex searchIndex() { return searchIndex; }
  List<Scope> authorizedScopes(Actor actor) { return List.copyOf(scopes.getOrDefault(actor.tenantId(),List.of())); }
  void refreshSearch(String tenant,String caseId) { searchIndex.refresh(tenant,caseId); }
  void rebuildSearch() { searchIndex.rebuild(); }
  ObjectNode config(Actor actor) {
    ObjectNode result=mapper.createObjectNode().put("mode",client.mode()).put("wireFormat",client.wireFormat()).put("today",LocalDate.now(ZoneId.of("Asia/Kolkata")).toString())
        .put("timezone","Asia/Kolkata").put("maxRecords",200)
        .put("directLookupScope","Exact FCR reference or UTR inquiry in the selected authorized bank and branch, using the configured source API (or original catalog in MOCK mode). No date window, list scan or loaded-record fallback. Source scope and retention still apply.");
    var array=result.putArray("scopes");
    for(Scope scope:scopes.getOrDefault(actor.tenantId(),List.of())) array.addObject().put("orgBranch",scope.branch).put("orgBank",scope.bank).put("label","Bank "+scope.bank+" · Branch "+scope.branch);
    return result;
  }
  ObjectNode search(Actor actor,byte[] bytes) {
    actor.requireWriter(); ObjectNode request=parse(bytes,8192);
    fields(request,Set.of("orgBank","orgBranch","inquiryDate","recordCount"));
    String branch=text(request,"orgBranch",10,true),bank=text(request,"orgBank",10,true); Scope scope=scope(actor,branch,bank);
    String dateText=text(request,"inquiryDate",10,true);
    LocalDate date;
    try { if(!dateText.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException(); date=LocalDate.parse(dateText); }
    catch(Exception ex){throw invalid("Provide an ISO inquiryDate.");}
    JsonNode countNode=request.path("recordCount");
    if(!countNode.isIntegralNumber() || !countNode.canConvertToInt() || countNode.intValue()<1 || countNode.intValue()>200) throw invalid("recordCount must be an integer from 1 through 200.");
    int count=countNode.intValue();
    PaymentDiscoveryClient.Result upstream=client.fetch(scope.branch,scope.bank,date,count);
    List<ObjectNode> candidates=normalizeResponse(actor,scope,upstream);
    for(ObjectNode candidate:candidates) if(!candidate.path("initiatedAt").asText().startsWith(date.toString()+"T"))
      throw new ApiException(502,"INVALID_DISCOVERY_RESPONSE","Inquiry returned a row outside the selected date scope; no records were imported.");
    boolean truncated=upstream.hasMore() || candidates.size()>count;
    if(candidates.size()>count) candidates=new ArrayList<>(candidates.subList(0,count));
    List<String> warnings=new ArrayList<>();
    warnings.add("Discovery metadata only. No queue, handoff, accounting or final outcome is established.");
    if(truncated) warnings.add("The result is truncated or the source reports additional records.");
    if(upstream.upstream()!=null) warnings.add("FLEXCUBE PO01 discovery only. observedAt is the source query observation time; it is not the bank posting date or a payment completion time. Original response text and nulls are retained in the inquiry receipt.");
    return saveBatch(actor,scope,client.mode(),upstream.observedAt(),candidates,upstream.rows(),truncated,warnings,null,upstream.upstream());
  }
  ObjectNode lookup(Actor actor,byte[] bytes) {
    actor.requireWriter();ObjectNode request=parse(bytes,8192);
    fields(request,Set.of("orgBank","orgBranch","referenceType","reference"));
    Scope scope=scope(actor,text(request,"orgBranch",10,true),text(request,"orgBank",10,true));
    String type=text(request,"referenceType",3,true),reference=text(request,"reference",200,true);
    if(!Set.of("FCR","UTR").contains(type))throw invalid("referenceType must be FCR or UTR.");
    PaymentDiscoveryClient.Result upstream=client.lookup(scope.branch,scope.bank,type,reference);
    if(upstream.hasMore())throw new ApiException(502,"INCOMPLETE_DISCOVERY_LOOKUP","The exact inquiry reports additional matches; no partial result was imported.");
    String column=type.equals("FCR")?"PIO_REF_TXN_NO":"UTR_REF_NO";
    for(Map<String,String> row:upstream.rows())if(!reference.equals(row.get(column)))
      throw new ApiException(502,"INVALID_DISCOVERY_RESPONSE","An inquiry row does not match the requested exact reference; no records were imported.");
    List<ObjectNode> candidates=normalizeResponse(actor,scope,upstream);
    if(candidates.size()>200)throw new ApiException(502,"DISCOVERY_LOOKUP_TOO_LARGE","The exact inquiry exceeds 200 matched payments; no partial result was imported.");
    String match=candidates.isEmpty()?"NOT_FOUND":candidates.size()==1?"EXACT_MATCH":"AMBIGUOUS";
    List<String> warnings=new ArrayList<>(List.of("Discovery metadata only. No queue, handoff, accounting or final outcome is established.","Exact lookup uses the configured source for the selected bank and branch; its source coverage and retention apply. No loaded-record fallback was used."));
    if(match.equals("AMBIGUOUS"))warnings.add("Multiple payments match this UTR. Select the correct scoped payment reference before creating a case.");
    return saveBatch(actor,scope,client.mode(),upstream.observedAt(),candidates,upstream.rows(),false,warnings,match,upstream.upstream());
  }
  private List<ObjectNode> normalizeResponse(Actor actor,Scope scope,PaymentDiscoveryClient.Result upstream) {
    try{return normalize(actor,scope,upstream.rows(),client.mode());}
    catch(ApiException ex){if(ex.status!=403)throw new ApiException(502,"INVALID_DISCOVERY_RESPONSE","Inquiry rows do not match the discovery contract; no records were imported.");throw ex;}
  }
  ObjectNode upload(Actor actor,String branch,String bank,byte[] bytes) {
    actor.requireWriter(); Scope scope=scope(actor,branch,bank);
    if(bytes.length>5*1024*1024) throw new ApiException(413,"UPLOAD_TOO_LARGE","Workbook limit is 5 MiB.");
    List<Map<String,String>> rows=PaymentDiscoveryWorkbook.read(bytes);
    List<ObjectNode> candidates=normalize(actor,scope,rows,"EXCEL");
    if(candidates.size()>200) throw new ApiException(413,"UPLOAD_TOO_MANY_PAYMENTS","Upload at most 200 distinct payments and 2000 host rows per workbook.");
    return saveBatch(actor,scope,"EXCEL",Instant.now().toString(),candidates,rows,false,
        List.of("Private uploaded discovery metadata only; source-query completion and full payment evidence are not established."),null);
  }
  List<ObjectNode> normalize(Actor actor,Scope scope,List<Map<String,String>> rows,String kind) {
    if(rows.size()>2000) throw new ApiException(413,"TOO_MANY_DISCOVERY_ROWS","At most 2000 native rows are supported.");
    LinkedHashMap<String,ObjectNode> grouped=new LinkedHashMap<>(); Map<String,String> directionByIdentity=new HashMap<>();
    for(Map<String,String> row:rows) {
      if(!row.keySet().containsAll(REQUIRED) || row.keySet().stream().anyMatch(k->!REQUIRED.contains(k)&&!OPTIONAL.contains(k))) throw invalid("Native columns do not match the discovery contract.");
      for(var entry:row.entrySet()) {
        boolean nullableSubsequence=entry.getKey().equals("REF_SUBSEQ_NO");
        nativeText(nullableSubsequence && entry.getValue()==null?"":entry.getValue(),200,
            nullableSubsequence || entry.getKey().equals("UTR_REF_NO") || OPTIONAL.contains(entry.getKey()));
      }
      if(!scope.branch.equals(row.get("PIO_ORG_BRN")) || !scope.bank.equals(row.get("PIO_ORG_BANK"))) throw new ApiException(403,"DISCOVERY_SCOPE_MISMATCH","Every source row must match the selected authorized branch and bank.");
      String reference=row.get("PIO_REF_TXN_NO"), sub=blank(row.get("REF_SUBSEQ_NO")), amount=row.get("NUMAMOUNT_4038"), timestamp=row.get("DATINITIATION");
      if(sub!=null && !sub.matches("[0-9]{1,38}")) throw invalid("Host subsequence must be an exact nonnegative integer string, blank or null.");
      if(!amount.matches("(0|[1-9][0-9]{0,37})(\\.[0-9]{1,18})?")) throw invalid("Amount must be an exact nonnegative decimal string without rounding or exponent notation.");
      new BigDecimal(amount);
      try { if(!timestamp.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?")) throw new IllegalArgumentException(); LocalDateTime.parse(timestamp); }
      catch(Exception ex){throw invalid("DATINITIATION must be an ISO source-local timestamp without an invented UTC offset.");}
      String currency=blank(row.get("CURRENCY")), direction=blank(row.get("DIRECTION")), utr=blank(row.get("UTR_REF_NO"));
      if(currency!=null && !currency.matches("[A-Z]{3}")) throw invalid("Optional CURRENCY must be an ISO text code.");
      if(direction!=null && !Set.of("IN","OUT","INBOUND","OUTBOUND").contains(direction)) throw invalid("Unsupported optional DIRECTION.");
      String identity=identity(actor,scope,reference,kind.equals("MOCK"));
      ObjectNode item=mapper.createObjectNode().put("reference",reference).put("orgBranch",scope.branch).put("orgBank",scope.bank)
          .put("initiatedAt",timestamp).put("amount",amount).put("sourceKind",kind)
          .put("dataClassification",kind.equals("MOCK")?"SYNTHETIC":"PRIVATE_UAT");
      item.set("utr",utr==null?NullNode.instance:TextNode.valueOf(utr)); item.set("currency",currency==null?NullNode.instance:TextNode.valueOf(currency));
      if(grouped.containsKey(identity)) {
        ObjectNode previous=grouped.get(identity).deepCopy();previous.remove("hostSubsequences");
        if(!previous.equals(item) || !Objects.equals(directionByIdentity.get(identity),direction)) throw invalid("Conflicting metadata for the same scoped payment reference; no rows were imported.");
      } else {item.putArray("hostSubsequences");grouped.put(identity,item);directionByIdentity.put(identity,direction);}
      ArrayNode subs=(ArrayNode)grouped.get(identity).path("hostSubsequences");
      JsonNode subsequence=sub==null?NullNode.instance:TextNode.valueOf(sub);
      boolean present=false;for(JsonNode value:subs)present|=value.equals(subsequence);if(!present)subs.add(subsequence);
    }
    List<ObjectNode> result=new ArrayList<>();
    for(var entry:grouped.entrySet()) {
      ObjectNode item=entry.getValue(); List<String> subs=new ArrayList<>();item.path("hostSubsequences").forEach(s->subs.add(s.isNull()?null:s.textValue()));
      subs.sort(Comparator.nullsLast(Comparator.comparing((String s)->new java.math.BigInteger(s)).thenComparing(s->s)));
      ArrayNode sorted=item.putArray("hostSubsequences");subs.forEach(s->{if(s==null)sorted.addNull();else sorted.add(s);});
      item.put("candidateId","CAND-"+hash(entry.getKey()+"|"+UatService.canonicalHash(item)));result.add(item);
    }
    result.sort(Comparator.comparing((ObjectNode n)->n.path("initiatedAt").asText()).thenComparing(n->n.path("reference").asText()).reversed());
    return result;
  }
  private ObjectNode saveBatch(Actor actor,Scope scope,String kind,String observed,List<ObjectNode> items,List<Map<String,String>> rows,boolean truncated,List<String> warnings,String matchStatus) {
    return saveBatch(actor,scope,kind,observed,items,rows,truncated,warnings,matchStatus,null);
  }
  private ObjectNode saveBatch(Actor actor,Scope scope,String kind,String observed,List<ObjectNode> items,List<Map<String,String>> rows,boolean truncated,List<String> warnings,String matchStatus,ObjectNode upstream) {
    ObjectNode result=mapper.createObjectNode().put("batchId","BATCH-"+UUID.randomUUID()).put("mode",client.mode()).put("sourceKind",kind)
        .put("observedAt",observed).put("coverage",matchStatus!=null?"Exact reference matches reported by the configured source within the authorized bank and branch; no date/list scan or loaded-record fallback. Source scope and retention apply.":kind.equals("MOCK")?"Original synthetic catalog for the selected scope/date.":"Bounded discovery records only; no full-system or payment-outcome coverage is asserted.").put("truncated",truncated);
    if(matchStatus!=null)result.put("matchStatus",matchStatus);
    if(upstream!=null) {
      ObjectNode receipt=mapper.createObjectNode().put("batchId",result.path("batchId").asText()).put("wireFormat","FLEXCUBE")
          .put("serviceCode","PO01").put("receivedAt",upstream.path("receivedAt").asText())
          .put("externalReferenceNo",upstream.path("request").path("args0").path("externalReferenceNo").asText())
          .put("upstreamHash",UatService.canonicalHash(upstream));
      items.forEach(item->{
        // A fresh inquiry is a fresh observation even when all returned values match.
        item.put("candidateId","CAND-"+hash(item.path("candidateId").asText()+"|"+UatService.canonicalHash(receipt)));
        item.set("discoveryReceipt",receipt.deepCopy());
      });
      result.set("inquiryReceipt",receipt);
    }
    result.set("items",mapper.valueToTree(items));result.set("warnings",mapper.valueToTree(warnings));
    ObjectNode stored=result.deepCopy();stored.set("nativeRows",mapper.valueToTree(rows));
    if(upstream!=null)stored.set("upstream",upstream.deepCopy());
    retry(()->transactions.execute(tx->{
      for(ObjectNode item:items) {
        String id=item.path("candidateId").asText();
        if(db.queryForObject("SELECT COUNT(*) FROM fcr_discovery_candidate WHERE id=? AND tenant_id=?",Integer.class,id,actor.tenantId())==0)
          db.update("INSERT INTO fcr_discovery_candidate(id,tenant_id,identity_hash,org_branch,org_bank,payment_reference,utr,created_at,body) VALUES(?,?,?,?,?,?,?,?,?)",
              id,actor.tenantId(),identity(actor,scope,item.path("reference").asText(),item.path("dataClassification").asText().equals("SYNTHETIC")),scope.branch,scope.bank,item.path("reference").asText(),item.path("utr").isNull()?null:item.path("utr").asText(),Instant.now().toString(),item.toString());
      }
      db.update("INSERT INTO fcr_discovery_batch(id,tenant_id,observed_at,body) VALUES(?,?,?,?)",result.path("batchId").asText(),actor.tenantId(),observed,stored.toString());return true;
    }));
    ArrayNode decorated=result.putArray("items");items.forEach(item->decorated.add(decorate(item,actor,scope)));return result;
  }
  private ObjectNode decorate(ObjectNode item,Actor actor,Scope scope) {
    ObjectNode copy=item.deepCopy();String identity=identity(actor,scope,item.path("reference").asText(),item.path("dataClassification").asText().equals("SYNTHETIC"));
    List<String> cases=db.queryForList("SELECT id FROM fcr_payment_case WHERE tenant_id=? AND identity_hash=?",String.class,actor.tenantId(),identity);
    if(!cases.isEmpty()) {
      copy.put("existingCaseId",cases.get(0));String number=caseNumbers.find(actor.tenantId(),cases.get(0));
      if(number!=null)copy.put("existingCaseNumber",number);
      copy.put("existingCaseLifecycleState",CaseLifecycleState.load(db,actor.tenantId(),cases.get(0)).state());
    }
    return copy;
  }
  ObjectNode createCase(Actor actor,byte[] bytes,String idempotencyKey) {
    actor.requireWriter(); if(idempotencyKey==null || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,200}")) throw invalid("A valid Idempotency-Key is required.");
    ObjectNode request=parse(bytes,8192);fields(request,Set.of("candidateId","reason"));
    String candidateId=text(request,"candidateId",100,true), reason=reason(request);
    String requestHash=UatService.canonicalHash(request);
    return retry(()->transactions.execute(tx->{
      List<Map<String,Object>> previous=db.queryForList("SELECT request_hash,body FROM fcr_case_command WHERE tenant_id=? AND actor_id=? AND idempotency_key=?",actor.tenantId(),actor.id(),idempotencyKey);
      if(!previous.isEmpty()) {
        if(!requestHash.equals(previous.get(0).get("request_hash")))throw new ApiException(409,"IDEMPOTENCY_CONFLICT","The key already names a different case command.");
        ObjectNode replay=object((String)previous.get(0).get("body"));
        String replayId=replay.path("caseId").asText();
        // Authorize the current case and reject deleted receipts, while preserving the original
        // idempotent discovery response. Only display number/lifecycle are decorated on return.
        lockCase(actor,replayId);return decorateCaseResult(actor,replay);
      }
      List<ObjectNode> candidates=db.query("SELECT body FROM fcr_discovery_candidate WHERE id=? AND tenant_id=?",(rs,n)->object(rs.getString(1)),candidateId,actor.tenantId());
      if(candidates.isEmpty())throw ApiException.notFound();ObjectNode candidate=candidates.get(0);Scope scope=scope(actor,candidate.path("orgBranch").asText(),candidate.path("orgBank").asText());
      if(!scope.bank.equals(candidate.path("orgBank").asText()))throw ApiException.notFound();
      String identity=identity(actor,scope,candidate.path("reference").asText(),candidate.path("dataClassification").asText().equals("SYNTHETIC"));
      List<ObjectNode> existing=db.query("SELECT body FROM fcr_payment_case WHERE tenant_id=? AND identity_hash=?",(rs,n)->object(rs.getString(1)),actor.tenantId(),identity);
      ObjectNode item; String status;
      if(!existing.isEmpty()){String existingId=existing.get(0).path("id").asText();lockCase(actor,existingId);item=caseRecord(actor,existingId);status="EXISTING";}
      else {
        String id="FCR-"+identity;
        if(db.queryForObject("SELECT COUNT(*) FROM fcr_payment_case WHERE id=?",Integer.class,id)>0)id="FCR-"+UUID.randomUUID();
        String now=Instant.now().toString();item=candidate.deepCopy();item.put("id",id).put("reason",reason).put("status","OPEN").put("priority","MEDIUM")
            .put("createdAt",now).put("updatedAt",now).put("createdBy",actor.id()).put("evidenceStatus","DISCOVERY_ONLY");
        db.update("INSERT INTO fcr_payment_case(id,tenant_id,identity_hash,created_at,body) VALUES(?,?,?,?,?)",item.path("id").asText(),actor.tenantId(),identity,now,item.toString());CaseHistoryIndex.recordCase(mapper,db,actor.tenantId(),item);status="CREATED";
      }
      caseNumbers.assign(actor.tenantId(),item.path("id").asText());
      refreshSearch(actor.tenantId(),item.path("id").asText());
      ObjectNode result=mapper.createObjectNode().put("caseId",item.path("id").asText()).put("status",status);result.set("item",item);
      db.update("INSERT INTO fcr_case_command(tenant_id,actor_id,idempotency_key,request_hash,body) VALUES(?,?,?,?,?)",actor.tenantId(),actor.id(),idempotencyKey,requestHash,result.toString());return decorateCaseResult(actor,result);
    }));
  }
  private ObjectNode decorateCaseResult(Actor actor,ObjectNode result) {
    ObjectNode copy=result.deepCopy();ObjectNode item=CaseLifecycleState.decorate(db,actor.tenantId(),caseNumbers.decorate(actor.tenantId(),(ObjectNode)copy.path("item")));
    copy.set("item",item);if(item.has("caseNumber"))copy.set("caseNumber",item.get("caseNumber"));return copy;
  }
  ObjectNode cases(Actor actor) {
    return cases(actor,"ACTIVE");
  }
  ObjectNode searchCases(Actor actor,Map<String,String[]> parameters) {
    CaseSearchQuery query=CaseSearchQuery.parse(parameters);
    CaseSearchIndex.SqlWhere where=query.where(actor,authorizedScopes(actor));
    return searchIndex.readSnapshot(()->{
      Long total=db.queryForObject("SELECT COUNT(*) FROM fcr_case_search s WHERE "+where.sql(),Long.class,where.args().toArray());
      long pages=total==0?1:1+(total-1)/query.pageSize(),page=Math.min(query.page(),pages),offset=(page-1)*query.pageSize();
      List<Object> args=new ArrayList<>(where.args());args.add(query.pageSize());args.add(offset);
      List<ObjectNode> items=db.query("SELECT c.body,s.* FROM fcr_case_search s JOIN fcr_payment_case c ON c.id=s.case_id AND c.tenant_id=s.tenant_id WHERE "
          +where.sql()+" ORDER BY "+query.orderBy()+" LIMIT ? OFFSET ?",(row,n)->searchIndex.caseItem(row),args.toArray());
      ObjectNode result=mapper.createObjectNode().put("total",total).put("page",page).put("pageSize",query.pageSize()).put("totalPages",pages)
          .put("sort",query.sort()).put("search",query.search()).put("work",query.work()).put("lifecycle",query.lifecycle());
      result.set("items",mapper.valueToTree(items));return result;
    });
  }
  ObjectNode cases(Actor actor,String lifecycle) {
    if(!Set.of("ACTIVE","ARCHIVED","ALL").contains(lifecycle))throw invalid("Choose ACTIVE, ARCHIVED or ALL for the case lifecycle filter.");
    List<ObjectNode> all=db.query("SELECT body FROM fcr_payment_case WHERE tenant_id=? ORDER BY created_at DESC,id",(rs,n)->object(rs.getString(1)),actor.tenantId());
    all.removeIf(item->!authorizedScope(actor,item));CaseLifecycleState.decorateAll(db,actor.tenantId(),all);
    all.removeIf(item->item.path("lifecycleState").asText().equals("DELETED") || (!lifecycle.equals("ALL")&&!item.path("lifecycleState").asText().equals(lifecycle)));
    CaseManagementState.overlayAll(mapper,db,actor.tenantId(),all);
    caseNumbers.decorateAll(actor.tenantId(),all);
    ObjectNode result=mapper.createObjectNode().put("total",all.size());result.set("items",mapper.valueToTree(all));return result;
  }
  ObjectNode caseDetail(Actor actor,String id) {
    ObjectNode item=caseRecord(actor,caseNumbers.resolve(actor.tenantId(),id));
    return CaseLifecycleState.decorate(db,actor.tenantId(),caseNumbers.decorate(actor.tenantId(),CaseManagementState.overlay(mapper,actor.tenantId(),item,CaseManagementState.load(mapper,db,actor.tenantId(),item))));
  }
  /** Latest-evidence display metadata is deliberately excluded from internal report/investigation snapshots. */
  ObjectNode caseDisplayDetail(Actor actor,String id) { return searchIndex.decorateEvidenceCurrency(actor.tenantId(),caseDetail(actor,id)); }
  ObjectNode caseRecord(Actor actor,String id) {
    ObjectNode item=lifecycleRecord(actor,id);CaseLifecycleState.requireReadable(db,actor.tenantId(),id);return item;
  }
  /** Only lifecycle commands may read the minimal tombstone, always under normal scope authorization. */
  ObjectNode lifecycleRecord(Actor actor,String id) {
    List<ObjectNode> rows=db.query("SELECT body FROM fcr_payment_case WHERE id=? AND tenant_id=?",(rs,n)->object(rs.getString(1)),id,actor.tenantId());
    if(rows.isEmpty() || !authorizedScope(actor,rows.get(0)))throw ApiException.notFound();return rows.get(0);
  }
  ObjectNode requireActive(Actor actor,String id) {
    ObjectNode item=requireUnarchived(actor,id);
    if(item.path("status").asText().equals("RESOLVED"))throw new ApiException(409,"CASE_RESOLVED","Reopen the resolved case with a reason before adding evidence, questions or management changes.");
    return item;
  }
  ObjectNode requireUnarchived(Actor actor,String id) {
    ObjectNode item=caseDetail(actor,id);CaseLifecycleState.requireActive(db,actor.tenantId(),item.path("id").asText());return item;
  }
  void lockCase(Actor actor,String id) {
    if(db.query("SELECT id FROM fcr_payment_case WHERE tenant_id=? AND id=? FOR UPDATE",(rs,n)->rs.getString(1),actor.tenantId(),id).isEmpty())throw ApiException.notFound();
    caseRecord(actor,id);
  }
  ObjectNode dashboard(Actor actor) {
    CaseSearchIndex.SqlWhere where=CaseSearchIndex.scope(actor,authorizedScopes(actor),null,null).and("s.lifecycle_state='ACTIVE'");
    return db.queryForObject("SELECT COALESCE(SUM(CASE WHEN s.workflow_status NOT IN ('RESOLVED','CLOSED') THEN 1 ELSE 0 END),0) AS open_count,"
        +"COALESCE(SUM(CASE WHEN s.priority IN ('HIGH','CRITICAL') AND s.workflow_status NOT IN ('RESOLVED','CLOSED') THEN 1 ELSE 0 END),0) AS high_count,"
        +"COALESCE(SUM(CASE WHEN s.workflow_status='AWAITING_REVIEW' THEN 1 ELSE 0 END),0) AS review_count,"
        +"COALESCE(SUM(CASE WHEN s.workflow_status IN ('RESOLVED','CLOSED') THEN 1 ELSE 0 END),0) AS resolved_count FROM fcr_case_search s WHERE "+where.sql(),
        (row,n)->mapper.createObjectNode().put("openCases",row.getLong("open_count")).put("highPriorityCases",row.getLong("high_count"))
            .put("awaitingReview",row.getLong("review_count")).put("resolvedCases",row.getLong("resolved_count")),where.args().toArray());
  }
  private boolean authorizedScope(Actor actor,JsonNode item){return scopes.getOrDefault(actor.tenantId(),List.of()).stream().anyMatch(s->s.branch.equals(item.path("orgBranch").asText())&&s.bank.equals(item.path("orgBank").asText()));}
  private Scope scope(Actor actor,String branch,String bank){return scopes.getOrDefault(actor.tenantId(),List.of()).stream().filter(s->s.branch.equals(branch)&&s.bank.equals(bank)).findFirst().orElseThrow(()->new ApiException(403,"DISCOVERY_SCOPE_FORBIDDEN","The selected bank and branch pair is not authorized for this tenant."));}
  private String identity(Actor actor,Scope scope,String reference,boolean synthetic){return hash(mapper.createArrayNode().add(actor.tenantId()).add(client.deployment(synthetic)).add("FCR_NEFT").add(synthetic?"SYNTHETIC":"PRIVATE_UAT").add(scope.branch).add(scope.bank).add(reference).toString());}
  private ObjectNode object(String text){try{return (ObjectNode)mapper.readTree(text);}catch(Exception ex){throw new ApiException(503,"DISCOVERY_STORAGE_UNAVAILABLE","Stored discovery data could not be read.");}}
  private ObjectNode parse(byte[] bytes,int limit){if(bytes.length>limit)throw new ApiException(413,"DISCOVERY_REQUEST_TOO_LARGE","Discovery request exceeds its limit.");return UatService.parseObject(mapper,bytes,invalid("Invalid discovery request."));}
  static void fields(JsonNode value,Set<String> permitted){if(!value.isObject())throw invalid("Expected a JSON object.");value.fieldNames().forEachRemaining(key->{if(!permitted.contains(key))throw invalid("Unsupported request or native source field.");});}
  static String text(JsonNode value,String key,int max,boolean required){JsonNode field=value.get(key);if(field==null && !required)return null;if(field==null || !field.isTextual())throw invalid("A required text field is missing or invalid.");String result=field.textValue();nativeText(result,max,false);return result;}
  private static String reason(JsonNode value){JsonNode field=value.get("reason");if(field==null||!field.isTextual())throw invalid("A case reason is required.");String result=field.textValue();if(result.isBlank()||result.length()>2000||result.chars().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))throw invalid("Case reason must contain 1 through 2000 text characters.");return result;}
  static void nativeText(String value,int max,boolean allowEmpty){if(value==null || value.length()>max || (!allowEmpty&&value.isBlank()) || !value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl))throw invalid("Source fields must be bounded exact text without control characters or surrounding whitespace.");}
  private static String blank(String value){return value==null||value.isEmpty()?null:value;}
  static ApiException invalid(String message){return new ApiException(422,"INVALID_DISCOVERY_INPUT",message);}
  static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
  private <T>T retry(java.util.function.Supplier<T> work){for(int i=0;i<3;i++){try{return work.get();}catch(DataIntegrityViolationException ex){if(i==2)throw new ApiException(409,"DISCOVERY_CONCURRENT_CHANGE","Concurrent discovery command conflict; retry with the same idempotency key.");}}throw new IllegalStateException();}
}
