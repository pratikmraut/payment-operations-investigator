package dev.pratik.poi;

import static dev.pratik.poi.UatTestData.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Synthetic contract checks; does not treat a passed quoted-field check as proof of a conclusion. */
class CaseAnswerValidatorTest {
  ObjectNode input() {
    ObjectNode input=MAPPER.createObjectNode().put("question","What do the source records establish?")
        .put("snapshotId",ID).put("evidenceHash","a".repeat(64));
    ArrayNode docs=input.putArray("documents");
    ObjectNode row=(ObjectNode)bundle().path("documents").get(0).deepCopy();
    row.put("id","PAYMENT-ROW-1").put("content","{\"CODSTATUS\":\"2\",\"N10_STATUS\":\"\",\"NUMAMOUNT_4038\":\"123.007\"}");
    docs.add(row);docs.add(bundle().path("documents").get(1).deepCopy());return input;
  }
  ObjectNode answer(ObjectNode input) {
    ObjectNode answer=UatTestData.response(input);
    answer.put("answer","PAYMENT-ROW-1 records CODSTATUS 2; this is a raw value.");
    answer.putArray("claims").addObject().put("text",answer.path("answer").asText()).putArray("evidenceIds").add("PAYMENT-ROW-1");
    answer.putArray("citations").add(input.path("documents").get(0).deepCopy());
    answer.withObject("/retrieval").putArray("documentIds").add("PAYMENT-ROW-1").add("K1");
    ObjectNode rag=answer.putObject("rag").put("pipeline",CaseAnswerValidator.PIPELINE).put("promptHash","b".repeat(64));
    rag.set("checks",MAPPER.valueToTree(CaseAnswerValidator.CHECKS));
    rag.putArray("claimSupports").addObject().put("claimIndex",0).put("claimType","observation")
        .putArray("fields").addObject().put("documentId","PAYMENT-ROW-1").put("field","CODSTATUS").put("value","2");
    return answer;
  }
  ObjectNode support(ObjectNode answer){return (ObjectNode)answer.path("rag").path("claimSupports").get(0);}
  ObjectNode field(ObjectNode answer){return (ObjectNode)support(answer).path("fields").get(0);}
  void text(ObjectNode answer,String text){answer.put("answer",text);((ObjectNode)answer.path("claims").get(0)).put("text",text);}
  void rejects(ObjectNode answer,ObjectNode input) {
    assertThatThrownBy(()->CaseAnswerValidator.validate(answer,input)).isInstanceOfSatisfying(ApiException.class,
        failure->{assertThat(failure.status).isEqualTo(502);assertThat(failure.code).isEqualTo("INVALID_UAT_ANSWER");});
  }

  @Test void exactRawFieldsPassWithoutMutatingTheResponseOrOriginalBaselineValidator() {
    ObjectNode input=input(),answer=answer(input),before=answer.deepCopy();
    CaseAnswerValidator.validate(answer,input);assertThat(answer).isEqualTo(before);
    assertThatThrownBy(()->UatService.validateAnswer(answer,input)).isInstanceOf(ApiException.class);
    ObjectNode baseline=before.deepCopy();baseline.remove("rag");UatService.validateAnswer(baseline,input);
  }

  @Test void interpretationNeedsBothItsNativeFieldAndApplicableGuidanceCitation() {
    ObjectNode input=input(),answer=answer(input);support(answer).put("claimType","interpretation");
    rejects(answer,input);
    ((ArrayNode)answer.path("claims").get(0).path("evidenceIds")).add("K1");
    ((ArrayNode)answer.path("citations")).add(input.path("documents").get(1).deepCopy());
    CaseAnswerValidator.validate(answer,input);
  }

  @Test void aLimitationCanHaveNoFieldsButStillNeedsEvidenceAndRequiredFollowUp() {
    ObjectNode input=input(),answer=answer(input);support(answer).put("claimType","limitation").putArray("fields");
    text(answer,"These supplied rows do not establish the final outcome.");CaseAnswerValidator.validate(answer,input);
    for(String key:List.of("unknowns","nextChecks")){
      ObjectNode missing=answer.deepCopy();missing.putArray(key);rejects(missing,input);
    }
  }

  @Test void forgedValuesFieldsAndCrossDocumentSupportsFailEvenWhenTheyAreCited() {
    ObjectNode input=input();
    for(Consumer<ObjectNode> edit:List.<Consumer<ObjectNode>>of(
        a->field(a).put("value","3"),a->field(a).put("field","MSGSTATUS"),
        a->field(a).put("documentId","HOST-ROW-1"),a->field(a).put("documentId","K1"),
        a->field(a).put("value",2),a->field(a).put("field","CODSTATUS\0"),
        a->((ArrayNode)support(a).path("fields")).add(field(a).deepCopy()),
        a->text(a,"CODSTATUS records a different value."),
        a->support(a).putArray("fields"))) {
      ObjectNode answer=answer(input);edit.accept(answer);rejects(answer,input);
    }
  }

  @Test void supportsUseLiteralTextAndPreserveEmptyValuesAndExactDecimalStrings() {
    ObjectNode input=input(),answer=answer(input);
    field(answer).put("field","N10_STATUS").put("value","");text(answer,"N10_STATUS is blank in PAYMENT-ROW-1.");
    CaseAnswerValidator.validate(answer,input);
    field(answer).put("field","NUMAMOUNT_4038").put("value","123.007");text(answer,"NUMAMOUNT_4038 records 123.007.");
    CaseAnswerValidator.validate(answer,input);
    field(answer).put("value","123.01");text(answer,"NUMAMOUNT_4038 records 123.01.");rejects(answer,input);
  }

  @Test void readableFieldAliasesAreAllowedButExactSupportedValuesRemainRequired() {
    ObjectNode input=input(),answer=answer(input);
    field(answer).put("field","NUMAMOUNT_4038").put("value","123.007");
    text(answer,"The recorded amount is 123.007.");CaseAnswerValidator.validate(answer,input);
    text(answer,"The recorded amount is 123.01.");rejects(answer,input);
    text(answer,"The source contains an amount.");rejects(answer,input);
    // A readable alias does not change the source field or permit a forged value.
    text(answer,"The recorded amount is 123.007.");field(answer).put("field","OTHER_AMOUNT");rejects(answer,input);
  }

  @Test void oneBoundedModelCorrectionIsCountedWithoutPermittingFurtherCalls() {
    ObjectNode input=input(),answer=answer(input);
    answer.withObject("/model").put("actualCalls",2);CaseAnswerValidator.validate(answer,input);
    answer.withObject("/model").put("actualCalls",3);rejects(answer,input);
    ObjectNode baseline=answer.deepCopy();baseline.remove("rag");UatService.validateAnswer(baseline,input);
    answer.withObject("/model").put("actualCalls",0);rejects(answer,input);
  }

  @Test void rawRowsMustBeEvidenceWithStrictJsonObjectAndTextualTopLevelFields() {
    for(String content:List.of("[]","{\"CODSTATUS\":2}","{\"row\":{\"CODSTATUS\":\"2\"}}",
        "{\"CODSTATUS\":\"2\",\"CODSTATUS\":\"2\"}","{\"CODSTATUS\":\"2\"} {}")) {
      ObjectNode input=input();((ObjectNode)input.path("documents").get(0)).put("content",content);
      rejects(answer(input),input);
    }
    ObjectNode input=input();((ObjectNode)input.path("documents").get(0)).put("kind","knowledge");
    // Keep a separate evidence document so the base supplied-document check itself remains valid.
    ObjectNode context=(ObjectNode)bundle().path("documents").get(0).deepCopy();context.put("id","CASE-CONTEXT");
    ((ArrayNode)input.path("documents")).add(context);ObjectNode answer=answer(input);
    ((ArrayNode)answer.path("retrieval").path("documentIds")).add("CASE-CONTEXT");
    ((ArrayNode)answer.path("claims").get(0).path("evidenceIds")).add("CASE-CONTEXT");
    ((ArrayNode)answer.path("citations")).add(context.deepCopy());rejects(answer,input);
  }

  @Test void knowledgeOnlyClaimsCannotPassAsCaseEvidence() {
    ObjectNode input=input(),answer=answer(input);
    ((ObjectNode)answer.path("claims").get(0)).putArray("evidenceIds").add("K1");
    answer.putArray("citations").add(input.path("documents").get(1).deepCopy());
    support(answer).put("claimType","limitation").putArray("fields");rejects(answer,input);
  }

  @Test void sourceCitedProseMayUseGuidanceWithoutPretendingToBeFieldVerified() {
    ObjectNode input=input(),answer=answer(input);
    ((ObjectNode)answer.path("claims").get(0)).putArray("evidenceIds").add("K1");
    answer.putArray("citations").add(input.path("documents").get(1).deepCopy());
    support(answer).put("claimType","source-cited").putArray("fields");
    text(answer,"The supplied guidance requires a field-specific status lookup.");
    CaseAnswerValidator.validate(answer,input);
    support(answer).put("claimType","observation");rejects(answer,input);
    support(answer).put("claimType","source-cited");
    support(answer).withArray("/fields").addObject().put("documentId","PAYMENT-ROW-1").put("field","CODSTATUS").put("value","3");
    rejects(answer,input);
  }

  @Test void metadataMustUseTheExactPipelineKeysChecksAndOrderedSupportShape() {
    ObjectNode input=input();
    for(Consumer<ObjectNode> edit:List.<Consumer<ObjectNode>>of(
        a->a.remove("rag"),a->a.putNull("rag"),a->a.withObject("/rag").put("unexpected",true),
        a->a.withObject("/rag").put("pipeline","future-pipeline"),
        a->a.withObject("/rag").put("promptHash","A".repeat(64)),
        a->a.withObject("/rag").put("promptHash","b".repeat(63)),
        a->a.withObject("/rag").putArray("checks").add("source-membership"),
        a->((ArrayNode)a.path("rag").path("checks")).set(0,MAPPER.getNodeFactory().textNode("exact-field-values")),
        a->a.withObject("/rag").putArray("claimSupports"),
        a->support(a).put("claimIndex",1),a->support(a).put("claimIndex","0"),
        a->support(a).put("claimType","conclusion"),a->support(a).put("extra",true),
        a->field(a).put("extra",true),a->field(a).remove("value"))) {
      ObjectNode answer=answer(input);edit.accept(answer);rejects(answer,input);
    }
  }

  @Test void multipleClaimsMustEachKeepOneSupportInOrderAndTheSixFieldBound() {
    ObjectNode input=input(),answer=answer(input);
    JsonNode secondClaim=answer.path("claims").get(0).deepCopy();((ArrayNode)answer.path("claims")).add(secondClaim);
    answer.put("answer",answer.path("answer").asText()+"\n\n"+secondClaim.path("text").asText());
    ObjectNode second=support(answer).deepCopy();second.put("claimIndex",1);
    ((ArrayNode)answer.path("rag").path("claimSupports")).add(second);CaseAnswerValidator.validate(answer,input);
    second.put("claimIndex",0);rejects(answer,input);
    answer=answer(input);ArrayNode fields=(ArrayNode)support(answer).path("fields");
    for(int index=0;index<6;index++)fields.add(field(answer).deepCopy());rejects(answer,input);
  }

  @Test void newCaseBoundsMatchWorkerAndUiWithoutBroadeningTheExportContract() {
    ObjectNode input=input(),answer=answer(input);
    for(int index=1;index<5;index++) {
      JsonNode claim=answer.path("claims").get(0).deepCopy();((ArrayNode)answer.path("claims")).add(claim);
      answer.put("answer",answer.path("answer").asText()+"\n\n"+claim.path("text").asText());
      ObjectNode support=support(answer).deepCopy();support.put("claimIndex",index);
      ((ArrayNode)answer.path("rag").path("claimSupports")).add(support);
      if(index<4)CaseAnswerValidator.validate(answer,input);
    }
    rejects(answer,input);
    for(String key:List.of("unknowns","nextChecks")) {
      answer=answer(input);answer.putArray(key).add("First original check.").add("Second original check.").add("Third original check.");
      CaseAnswerValidator.validate(answer,input);
      ((ArrayNode)answer.path(key)).add("Fourth original check.");rejects(answer,input);
      answer=answer(input);answer.putArray(key).add("x".repeat(2001));rejects(answer,input);
    }
    answer=answer(input);text(answer,"CODSTATUS 2 "+"x".repeat(2000));rejects(answer,input);
    // Field bounds must reject even when the long strings exactly match a cited native row.
    for(String key:List.of("field","value")) {
      input=input();answer=answer(input);
      String name=key.equals("field")?"F".repeat(201):"CODSTATUS";
      String value=key.equals("value")?" ".repeat(5001):"2";
      ((ObjectNode)input.path("documents").get(0)).put("content",MAPPER.createObjectNode().put(name,value).toString());
      answer.putArray("citations").add(input.path("documents").get(0).deepCopy());
      field(answer).put("field",name).put("value",value);
      text(answer,name+" "+(key.equals("value")?"long source value":value));rejects(answer,input);
    }
  }
}
