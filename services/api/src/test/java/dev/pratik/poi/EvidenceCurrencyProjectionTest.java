package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class EvidenceCurrencyProjectionTest {
  final ObjectMapper mapper=new ObjectMapper();
  final Actor analyst=Actor.knownActors().get(0), viewer=Actor.knownActors().get(2), other=Actor.knownActors().get(3);
  static final String CASE="C-CURRENCY", AMOUNT="900719925474099312345.007";
  DriverManagerDataSource source; JdbcTemplate db; TransactionTemplate tx; PaymentDiscoveryService cases;
  CaseEvidenceService evidence; ObjectNode original;

  @BeforeEach void setup() {
    source=new DriverManagerDataSource("jdbc:h2:mem:currency-"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
    db=spy(new JdbcTemplate(source));tx=new TransactionTemplate(new DataSourceTransactionManager(source));
    var client=new PaymentDiscoveryClient(mapper,"DISABLED",false,"","TEST",1,(u,b,t)->{throw new AssertionError("No bank calls");});
    cases=new PaymentDiscoveryService(mapper,db,tx,client,"northstar:1352:760,silverline:2468:760");
    evidence=new CaseEvidenceService(mapper,db,tx,cases,new CaseEvidenceClient(mapper,false,"",15,"",(u,b,h,t)->{throw new AssertionError("No bank calls");}));
    original=mapper.createObjectNode().put("id",CASE).put("reference","0001234567890123456789").put("utr","UTR-CURRENCY")
        .put("orgBank","760").put("orgBranch","1352").put("reason","Review synthetic payment")
        .put("createdAt","2026-09-17T12:00:00Z").put("updatedAt","2026-09-17T12:00:00Z")
        .put("createdBy","analyst").put("priority","MEDIUM").put("status","OPEN").put("evidenceStatus","DISCOVERY_ONLY")
        .put("amount",AMOUNT).putNull("currency");
    db.update("INSERT INTO fcr_payment_case(id,tenant_id,identity_hash,created_at,body) VALUES(?,?,?,?,?)",CASE,"northstar",PaymentDiscoveryService.hash(CASE),original.path("createdAt").asText(),original.toString());
    refresh();
  }
  void refresh() { tx.executeWithoutResult(status->cases.refreshSearch("northstar",CASE)); }
  ObjectNode payload(String... currencies) {
    ObjectNode value=evidence.template(original);ArrayNode rows=(ArrayNode)value.path("sections").path("PAYMENT").path("rows");
    for(String currency:currencies) {
      ObjectNode row=rows.addObject();CaseEvidenceSchema.COLUMNS.get("PAYMENT").forEach(column->row.put(column,""));
      row.put("SOURCE_TABLE","PM_NEFT_TXN_LOG").put("QUERY_OBSERVED_AT","2026-09-17T12:30:00+05:30")
          .put("SCOPE_ROW_COUNT",Integer.toString(currencies.length)).put("REFTXNNUMBER",original.path("reference").asText())
          .put("NUMAMOUNT_4038",AMOUNT+"0").put("CODCURR",currency).put("UTR_REF_NO",original.path("utr").asText());
    }
    return value;
  }
  ObjectNode save(ObjectNode payload) { return evidence.submit(analyst,CASE,payload.toString().getBytes(StandardCharsets.UTF_8),"JSON","currency-test-"+UUID.randomUUID()); }
  ObjectNode save(String... currencies) { return save(payload(currencies)); }
  JsonNode page() { return cases.searchCases(analyst,Map.of()).path("items").get(0); }
  ObjectNode summary(ObjectNode snapshot) { ObjectNode value=snapshot.deepCopy();value.remove(List.of("payload","upstream"));return value; }
  ObjectNode paymentRow(ObjectNode value) { return (ObjectNode)value.path("payload").path("sections").path("PAYMENT").path("rows").get(0); }
  void replaceSnapshot(ObjectNode value,boolean updateSummary) {
    if(updateSummary)db.update("UPDATE fcr_case_evidence SET body=?,summary=? WHERE id=?",value.toString(),summary(value).toString(),value.path("id").asText());
    else db.update("UPDATE fcr_case_evidence SET body=? WHERE case_id=?",value.toString(),CASE);
    refresh();
  }
  void noHint() { assertThat(page().has("evidenceCurrency")).isFalse();assertThat(cases.caseDisplayDetail(analyst,CASE).has("evidenceCurrency")).isFalse(); }

  @Test void derivesProvenanceWithoutReplacingOriginalCurrencyAmountOrInternalCaseDetail() {
    ObjectNode snapshot=save("INR","INR");
    ObjectNode expected=mapper.createObjectNode().put("currency","INR").put("evidenceId",snapshot.path("id").asText()).put("version",1).put("sourceKind","JSON");
    assertThat(page().path("evidenceCurrency")).isEqualTo(expected);
    assertThat(cases.caseDisplayDetail(viewer,CASE).path("evidenceCurrency")).isEqualTo(expected);
    assertThat(new EvidenceLibraryService(mapper,db,cases).index(analyst,Map.of()).path("items").get(0).path("evidenceCurrency")).isEqualTo(expected);
    assertThat(page().path("currency").isNull()).isTrue();assertThat(page().path("amount").asText()).isEqualTo(AMOUNT);
    assertThat(cases.caseDetail(analyst,CASE).has("evidenceCurrency")).isFalse(); // Frozen report/investigation inputs stay original.
    String stored=db.queryForObject("SELECT body FROM fcr_payment_case WHERE id=?",String.class,CASE);
    assertThat(stored).contains("\"currency\":null","\"amount\":\""+AMOUNT+"\"").doesNotContain("evidenceCurrency");
    assertThat(db.queryForObject("SELECT body FROM fcr_case_evidence WHERE id=?",String.class,snapshot.path("id").asText())).isEqualTo(snapshot.toString());
    assertThatThrownBy(()->cases.caseDisplayDetail(other,CASE)).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.status).isEqualTo(404));
  }

  @Test void discoveryCurrencyAlwaysTakesPrecedence() {
    original.put("currency","EUR");db.update("UPDATE fcr_payment_case SET body=? WHERE id=?",original.toString(),CASE);refresh();
    save("INR");noHint();assertThat(page().path("currency").asText()).isEqualTo("EUR");
  }

  @Test void latestVersionReplacesOrWithholdsHintInsteadOfFallingBackToOlderCurrency() {
    save("INR");assertThat(page().path("evidenceCurrency").path("version").asInt()).isEqualTo(1);
    save("");noHint();
    save("USD");assertThat(page().path("evidenceCurrency").path("currency").asText()).isEqualTo("USD");
    assertThat(page().path("evidenceCurrency").path("version").asInt()).isEqualTo(3);
    save();noHint();
  }

  @Test void conflictingBlankLowercaseOrDifferentAmountRowsWithholdCurrency() {
    for(String[] currencies:List.of(new String[]{"INR","USD"},new String[]{"INR",""},new String[]{"inr"},new String[]{" INR "})) {
      save(currencies);noHint();
    }
    ObjectNode value=payload("INR");((ObjectNode)value.path("sections").path("PAYMENT").path("rows").get(0)).put("NUMAMOUNT_4038","900719925474099312345.008");
    save(value);noHint();
    ((ObjectNode)value.path("sections").path("PAYMENT").path("rows").get(0)).put("NUMAMOUNT_4038","");
    save(value);noHint();
  }

  @Test void verifiesFingerprintAndEverySnapshotIdentityBeforeDeriving() {
    ObjectNode valid=save("INR");
    ObjectNode tampered=valid.deepCopy();paymentRow(tampered).put("CODCURR","USD");replaceSnapshot(tampered,false);noHint();
    List<Consumer<ObjectNode>> mutations=List.of(
        value->((ObjectNode)value.path("payload").path("payment")).put("orgBank","761"),
        value->((ObjectNode)value.path("payload").path("payment")).put("orgBranch","2468"),
        value->((ObjectNode)value.path("payload").path("payment")).put("reference","another-payment"),
        value->paymentRow(value).put("REFTXNNUMBER","another-payment"),
        value->paymentRow(value).put("SOURCE_TABLE","PM_TXN_LOG"),
        value->((ObjectNode)value.path("payload")).put("schemaVersion","unknown-schema"));
    for(Consumer<ObjectNode> mutation:mutations) {
      ObjectNode changed=valid.deepCopy();mutation.accept(changed);changed.put("evidenceHash",CaseEvidenceService.fingerprint(changed));
      replaceSnapshot(changed,true);noHint();
    }
    for(String field:List.of("id","caseId","sourceKind")) {
      ObjectNode changed=valid.deepCopy();changed.put(field,"wrong-value");replaceSnapshot(changed,false);noHint();
    }
    ObjectNode changed=valid.deepCopy().put("version",2);replaceSnapshot(changed,false);noHint();
    ObjectNode oversizedVersion=valid.deepCopy().put("version",4294967297L);
    assertThat(EvidenceCurrencyProjection.derive(mapper,original,summary(oversizedVersion),oversizedVersion.toString())).isNull();
    db.update("UPDATE fcr_case_evidence SET body='malformed snapshot' WHERE case_id=?",CASE);refresh();noHint();
    // Optional currency corruption must not change the library's existing summary-integrity contract.
    assertThat(new EvidenceLibraryService(mapper,db,cases).index(analyst,Map.of()).path("items")).hasSize(1);
  }

  @Test void refreshReadsOnlyLatestSnapshotAndPagesUseOnlySavedProjection() {
    save("INR");ObjectNode latest=save("USD");clearInvocations(db);refresh();
    // A JdbcTemplate spy also observes its internal query/execute overload delegation. Count the
    // service's queryForList(String, Class, Object...) boundary, including its exact scoped parameters.
    var queries=mockingDetails(db).getInvocations().stream()
        .filter(call->call.getMethod().getName().equals("queryForList")
            && Arrays.equals(call.getMethod().getParameterTypes(),new Class<?>[]{String.class,Class.class,Object[].class}))
        .filter(call->((String)call.getArgument(0)).startsWith("SELECT body FROM fcr_case_evidence")).toList();
    assertThat(queries).hasSize(1);
    assertThat((String)queries.get(0).getArgument(0)).contains("tenant_id=? AND case_id=? AND id=? AND version=? LIMIT 1");
    assertThat((Object[])queries.get(0).getRawArguments()[2]).containsExactly("northstar",CASE,latest.path("id").asText(),2);
    clearInvocations(db);
    assertThat(page().path("evidenceCurrency").path("evidenceId").asText()).isEqualTo(latest.path("id").asText());
    cases.caseDisplayDetail(analyst,CASE);new EvidenceLibraryService(mapper,db,cases).index(analyst,Map.of());
    assertThat(mockingDetails(db).getInvocations().stream().filter(call->call.getArguments().length>0&&call.getArgument(0) instanceof String)
        .map(call->(String)call.getArgument(0)).filter(sql->sql.startsWith("SELECT body FROM fcr_case_evidence"))).isEmpty();
  }

  @Test void deployedSchemaGainsNullableColumnIdempotentlyAndBackfillKeepsRecordsUnchanged() {
    ObjectNode snapshot=save("INR");String originalBody=db.queryForObject("SELECT body FROM fcr_payment_case WHERE id=?",String.class,CASE);
    db.execute("ALTER TABLE fcr_case_search DROP COLUMN evidence_currency");
    var migration=new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));migration.execute(source);migration.execute(source);
    assertThat(db.queryForObject("SELECT evidence_currency FROM fcr_case_search WHERE case_id=?",String.class,CASE)).isNull();
    cases.rebuildSearch();assertThat(page().path("evidenceCurrency").path("currency").asText()).isEqualTo("INR");
    assertThat(db.queryForObject("SELECT body FROM fcr_payment_case WHERE id=?",String.class,CASE)).isEqualTo(originalBody);
    assertThat(db.queryForObject("SELECT body FROM fcr_case_evidence WHERE id=?",String.class,snapshot.path("id").asText())).isEqualTo(snapshot.toString());
  }
}
