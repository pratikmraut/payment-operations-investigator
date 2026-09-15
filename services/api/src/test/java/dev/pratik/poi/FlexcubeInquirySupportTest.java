package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlexcubeInquirySupportTest {
  final ObjectMapper mapper=new ObjectMapper();
  FlexcubeInquirySupport support() { return new FlexcubeInquirySupport(mapper,"TESTUSER","API","20260915","EXPLICIT","Asia/Kolkata","UNKNOWN"); }
  ObjectNode args1() { return mapper.createObjectNode().put("originatingBankCode",999).put("originatingBranchCode",2468); }
  static ObjectNode success(ObjectMapper mapper,ObjectNode request) {
    ObjectNode response=mapper.createObjectNode().put("postingDate",request.path("args0").path("postingDateText").asText());
    response.putObject("transactionStatus").put("errorCode","0").put("replyCode",0).put("spReturnValue",0)
        .put("isOverriden",false).put("isServiceChargeApplied",false).put("FCYHangeHandlingApplied",false)
        .put("externalReferenceNo",request.path("args0").path("externalReferenceNo").asText());
    return response;
  }
  @Test void generatesIndependentServerContextMatchingEachScopeAndPostingDate() {
    ObjectNode first=support().request("PO01","999","2468",args1());
    ObjectNode next=support().request("PO02","999","2468",args1());
    assertThat(first.path("args0").path("bankCode").isInt()).isTrue();
    assertThat(first.path("args0").path("bankCode").intValue()).isEqualTo(999);
    assertThat(first.path("args0").path("transactionBranch").textValue()).isEqualTo("2468");
    assertThat(first.path("args0").path("postingDateText").textValue()).isEqualTo("20260915");
    assertThat(first.path("args0").path("externalReferenceNo").textValue()).matches("PO01[0-9a-f]{24}");
    assertThat(next.path("args0").path("externalReferenceNo")).isNotEqualTo(first.path("args0").path("externalReferenceNo"));
    assertThatThrownBy(()->support().request("PO01","999","1352",args1())).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void businessDateAndServiceUserMustBeConfiguredBeforeAnyCall() {
    for(var values:List.of(List.of("","20260915"),List.of("TESTUSER",""))) {
      var support=new FlexcubeInquirySupport(mapper,values.get(0),"API",values.get(1),"EXPLICIT","Asia/Kolkata","UNKNOWN");
      assertThatThrownBy(()->support.request("PO01","999","2468",args1())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(503));
    }
    for(String date:List.of("20260230","2026-09-15","20260915junk"))
      assertThatThrownBy(()->new FlexcubeInquirySupport(mapper,"TEST","API",date,"EXPLICIT","Asia/Kolkata","UNKNOWN")).isInstanceOf(RuntimeException.class);
  }
  @Test void scopeNeverTruncatesOrSilentlyRemovesIdentifierPadding() {
    for(String code:List.of("2147483648","9999999999","-1","0760","1.0",""))
      assertThatThrownBy(()->FlexcubeInquirySupport.code(code)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(422));
    assertThat(FlexcubeInquirySupport.code("2147483647")).isEqualTo(Integer.MAX_VALUE);
  }
  @Test void rejectsHttpSuccessWithApplicationFailureOrUnrelatedResponse() {
    ObjectNode request=support().request("PO02","999","2468",args1());
    support().validateResponse(success(mapper,request),request);
    for(String field:List.of("errorCode","replyCode","spReturnValue","externalReferenceNo")) {
      ObjectNode response=success(mapper,request);((ObjectNode)response.path("transactionStatus")).put(field,"unexpected");
      assertThatThrownBy(()->support().validateResponse(response,request)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(502));
    }
    ObjectNode wrongDate=success(mapper,request).put("postingDate","20260914");
    assertThatThrownBy(()->support().validateResponse(wrongDate,request)).isInstanceOf(ApiException.class);
    ObjectNode override=success(mapper,request);((ObjectNode)override.path("transactionStatus")).put("isOverriden",true);
    assertThatThrownBy(()->support().validateResponse(override,request)).isInstanceOf(ApiException.class);
  }
}
