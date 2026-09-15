package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlexcubeEvidenceAdapterTest {
  final ObjectMapper mapper = new ObjectMapper();
  final FlexcubeEvidenceAdapter adapter = new FlexcubeEvidenceAdapter(mapper);
  final ObjectNode request = FlexcubeEvidenceFixtures.request(mapper, "00000012345678901234567890", "760", "1352");
  final Instant receipt = Instant.parse("2026-09-14T05:00:00Z");

  @Test void mapsAllColumnsExplicitlyPreservingExactSourceTextAndNullProvenance() throws Exception {
    ObjectNode response = FlexcubeEvidenceFixtures.response(mapper, request);
    ObjectNode original = response.deepCopy();
    var result = adapter.adapt(response, request, "UNKNOWN", receipt);
    assertThat(result.upstream().path("rawResponse")).isEqualTo(original);
    assertThat(result.upstream().path("request")).isEqualTo(request);
    assertThat(response).isEqualTo(original);
    assertThat(result.payload().path("sourceTimezone").asText()).isEqualTo("UNKNOWN");
    for(String group:CaseEvidenceSchema.COLUMNS.keySet()) {
      ObjectNode row=(ObjectNode)result.payload().path("sections").path(group).path("rows").get(0);
      Set<String> fields=new HashSet<>();row.fieldNames().forEachRemaining(fields::add);
      assertThat(fields).containsExactlyInAnyOrderElementsOf(CaseEvidenceSchema.COLUMNS.get(group));
    }
    ObjectNode payment=(ObjectNode)result.payload().path("sections").path("PAYMENT").path("rows").get(0);
    assertThat(payment.path("REFTXNNUMBER").asText()).isEqualTo("00000012345678901234567890");
    assertThat(payment.path("NUMAMOUNT_4038").asText()).isEqualTo("123.007");
    assertThat(payment.path("IDRELATEDREF_2006").asText()).isEmpty();
    assertThat(result.upstream().path("nullFields")).hasSize(2);
    ObjectNode snapshot=mapper.createObjectNode();snapshot.set("upstream",result.upstream());
    ObjectNode cited=FlexcubeEvidenceAdapter.sourceRow(snapshot,"PAYMENT",1,payment);
    assertThat(cited.path("IDRELATEDREF_2006").isNull()).isTrue();
    assertThat(cited.path("N10_STATUS").isTextual()).isTrue();
    assertThat(cited.path("N10_STATUS").asText()).isEmpty();
    assertThat(result.payload().has("acquisition")).isFalse();
  }

  @Test void refusesAbsentNullWrongArraysUnsupportedOrMissingRequiredFieldsAndNonTextCells() {
    List<java.util.function.Consumer<ObjectNode>> changes=List.of(
        node->node.remove("neftHostEvidenceDetails"), node->node.putNull("neftStatusEvidenceDetails"),
        node->node.putObject("neftPaymentEvidenceDetails"), node->node.put("extra", "unexpected"),
        node->((ObjectNode)node.path("neftPaymentEvidenceDetails").get(0)).remove("refTxnNumber"),
        node->((ObjectNode)node.path("neftPaymentEvidenceDetails").get(0)).put("numAmount4038",123.007),
        node->((ObjectNode)node.path("neftPaymentEvidenceDetails").get(0)).put("refTxnNumber",123),
        node->((ObjectNode)node.path("neftHostEvidenceDetails").get(0)).put("extra","unexpected"));
    for(var change:changes) {
      ObjectNode response=FlexcubeEvidenceFixtures.response(mapper,request);change.accept(response);
      assertThatThrownBy(()->adapter.adapt(response,request,"UNKNOWN",receipt))
          .isInstanceOfSatisfying(ApiException.class,failure->assertThat(failure.status).isEqualTo(502));
    }
  }

  @Test void explicitEmptyArraysRemainEmptyWithoutInventedRowsOrCompletion() {
    ObjectNode response=FlexcubeEvidenceFixtures.response(mapper,request);
    FlexcubeEvidenceAdapter.ARRAYS.values().forEach(response::putArray);
    var result=adapter.adapt(response,request,"UNKNOWN",receipt);
    for(String group:CaseEvidenceSchema.COLUMNS.keySet())assertThat(result.payload().path("sections").path(group).path("rows")).isEmpty();
    assertThat(result.upstream().path("nullFields")).isEmpty();
    assertThat(result.payload().has("acquisition")).isFalse();
  }

  @Test void mappingVerificationRejectsAlteredNormalizationOrNullPathsEvenWithRecomputedFingerprint() {
    var adapted=adapter.adapt(FlexcubeEvidenceFixtures.response(mapper,request),request,"UNKNOWN",receipt);
    ObjectNode snapshot=mapper.createObjectNode().put("sourceKind","BANK_API");
    snapshot.set("payload",adapted.payload());snapshot.set("upstream",adapted.upstream());snapshot.putObject("coverage");
    snapshot.put("evidenceHash",CaseEvidenceService.fingerprint(snapshot));
    CaseEvidenceService.verifyUpstream(mapper,snapshot);
    ((ObjectNode)snapshot.path("upstream").path("nullFields").get(0)).put("rowIndex",2);
    snapshot.put("evidenceHash",CaseEvidenceService.fingerprint(snapshot));
    assertThatThrownBy(()->CaseEvidenceService.verifyUpstream(mapper,snapshot)).isInstanceOf(ApiException.class);
  }

  @Test void omittedNullableColumnsRemainDistinctFromNullAndEmptyTextInRawAndCitedRows() {
    ObjectNode response=FlexcubeEvidenceFixtures.sparseResponse(mapper,request);
    ((ObjectNode)response.path("neftPaymentEvidenceDetails").get(0)).putNull("n10Status").put("idUserReference2020", "");
    ObjectNode original=response.deepCopy();
    var adapted=adapter.adapt(response,request,"UNKNOWN",receipt);
    assertThat(adapted.upstream().path("schemaVersion").asText()).isEqualTo("flexcube-neft-evidence-v2");
    assertThat(adapted.upstream().path("rawResponse")).isEqualTo(original);
    assertThat(response).isEqualTo(original);
    assertThat(adapted.upstream().path("omittedFields")).hasSize(22);
    assertThat(adapted.upstream().path("nullFields")).hasSize(1);
    ObjectNode snapshot=snapshot(adapted);
    CaseEvidenceService.verifyUpstream(mapper,snapshot);
    ObjectNode payment=(ObjectNode)adapted.payload().path("sections").path("PAYMENT").path("rows").get(0);
    assertThat(payment.path("IDRELATEDREF_2006").asText()).isEmpty();
    ObjectNode cited=FlexcubeEvidenceAdapter.sourceRow(snapshot,"PAYMENT",1,payment);
    assertThat(cited.has("IDRELATEDREF_2006")).isFalse();
    assertThat(cited.path("N10_STATUS").isNull()).isTrue();
    assertThat(cited.path("IDUSERREFERENCE_2020").isTextual()).isTrue();
    assertThat(cited.path("IDUSERREFERENCE_2020").asText()).isEmpty();
    ObjectNode history=(ObjectNode)adapted.payload().path("sections").path("HISTORY").path("rows").get(0);
    assertThat(FlexcubeEvidenceAdapter.sourceRow(snapshot,"HISTORY",1,history).has("TXN_STAT")).isFalse();
    assertThat(adapted.payload().path("sections").path("STATUS").path("rows")).isEmpty();
  }

  @Test void supportedOptionalColumnsMayBeOmittedWithoutPermittingUnknownColumnsOrMissingIdentity() {
    for(String group:CaseEvidenceSchema.COLUMNS.keySet()) {
      for(String field:FlexcubeEvidenceAdapter.WIRE_COLUMNS.get(group)) {
        ObjectNode response=FlexcubeEvidenceFixtures.response(mapper,request);
        ObjectNode row=(ObjectNode)response.path(FlexcubeEvidenceAdapter.ARRAYS.get(group)).get(0);
        row.remove(field);
        if(FlexcubeEvidenceAdapter.REQUIRED_COLUMNS.get(group).contains(field)) {
          assertThatThrownBy(()->adapter.adapt(response,request,"UNKNOWN",receipt))
              .as(group+"."+field+" remains required").isInstanceOf(ApiException.class);
          row.putNull(field);
          assertThatThrownBy(()->adapter.adapt(response,request,"UNKNOWN",receipt)).isInstanceOf(ApiException.class);
          row.put(field, "");
          assertThatThrownBy(()->adapter.adapt(response,request,"UNKNOWN",receipt)).isInstanceOf(ApiException.class);
        } else {
          var adapted=adapter.adapt(response,request,"UNKNOWN",receipt);
          int column=FlexcubeEvidenceAdapter.WIRE_COLUMNS.get(group).indexOf(field);
          assertThat(adapted.upstream().path("omittedFields")).isEqualTo(mapper.createArrayNode().add(
              mapper.createObjectNode().put("group",group).put("rowIndex",1).put("field",CaseEvidenceSchema.COLUMNS.get(group).get(column))));
          row.put(field, 0);
          assertThatThrownBy(()->adapter.adapt(response,request,"UNKNOWN",receipt))
              .as(group+"."+field+" never number-coerces an optional source value").isInstanceOf(ApiException.class);
        }
      }
    }
  }

  @Test void omittedPathsAndNormalizationAreHashBoundAndReconstructedBeforeUse() {
    var adapted=adapter.adapt(FlexcubeEvidenceFixtures.sparseResponse(mapper,request),request,"UNKNOWN",receipt);
    ObjectNode snapshot=snapshot(adapted);
    for(var change:List.<java.util.function.Consumer<ObjectNode>>of(
        node->((ObjectNode)node.path("upstream").path("omittedFields").get(0)).put("rowIndex",2),
        node->((ObjectNode)node.path("upstream")).remove("omittedFields"),
        node->((ObjectNode)node.path("payload").path("sections").path("HISTORY").path("rows").get(0)).put("TXN_STAT","0"),
        node->((ObjectNode)node.path("upstream").path("rawResponse").path("neftPaymentEvidenceDetails").get(0)).putNull("idRelatedRef2006"))) {
      ObjectNode corrupt=snapshot.deepCopy();change.accept(corrupt);
      corrupt.put("evidenceHash",CaseEvidenceService.fingerprint(corrupt));
      assertThatThrownBy(()->CaseEvidenceService.verifyUpstream(mapper,corrupt)).isInstanceOf(ApiException.class);
    }
  }

  @Test void legacyPo02SnapshotsKeepTheirOriginalNormalizationProvenanceAndFingerprint() {
    var adapted=adapter.adapt(FlexcubeEvidenceFixtures.response(mapper,request),request,"UNKNOWN",receipt);
    adapted.upstream().put("schemaVersion","flexcube-neft-evidence-v1").remove("omittedFields");
    ObjectNode snapshot=snapshot(adapted), original=snapshot.deepCopy();
    String fingerprint=snapshot.path("evidenceHash").asText();
    CaseEvidenceService.verifyUpstream(mapper,snapshot);
    assertThat(snapshot).isEqualTo(original);
    assertThat(CaseEvidenceService.fingerprint(snapshot)).isEqualTo(fingerprint);
    ((ObjectNode)snapshot.path("upstream").path("rawResponse").path("neftPaymentEvidenceDetails").get(0)).remove("idRelatedRef2006");
    snapshot.put("evidenceHash",CaseEvidenceService.fingerprint(snapshot));
    assertThatThrownBy(()->CaseEvidenceService.verifyUpstream(mapper,snapshot)).isInstanceOf(ApiException.class);
  }

  ObjectNode snapshot(FlexcubeEvidenceAdapter.Adapted adapted) {
    ObjectNode snapshot=mapper.createObjectNode().put("sourceKind","BANK_API");
    snapshot.set("payload",adapted.payload());snapshot.set("upstream",adapted.upstream());snapshot.putObject("coverage");
    snapshot.put("evidenceHash",CaseEvidenceService.fingerprint(snapshot));
    return snapshot;
  }
}
