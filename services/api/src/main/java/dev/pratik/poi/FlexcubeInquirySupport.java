package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Server-owned FLEXCUBE session context shared by the two read-only inquiries. */
@Component
public class FlexcubeInquirySupport {
  private final ObjectMapper mapper;
  private final String userId, channel, postingDate, postingDatePolicy, sourceTimezone;
  private final ZoneId postingZone;

  public FlexcubeInquirySupport(ObjectMapper mapper,
      @Value("${poi.flexcube.user-id:}") String userId,
      @Value("${poi.flexcube.channel:API}") String channel,
      @Value("${poi.flexcube.posting-date:}") String postingDate,
      @Value("${poi.flexcube.posting-date-policy:EXPLICIT}") String postingDatePolicy,
      @Value("${poi.flexcube.posting-zone:Asia/Kolkata}") String postingZone,
      @Value("${poi.flexcube.source-timezone:UNKNOWN}") String sourceTimezone) {
    this.mapper=mapper; this.userId=userId; this.channel=channel; this.postingDate=postingDate;
    this.postingDatePolicy=postingDatePolicy; this.sourceTimezone=sourceTimezone;
    if (!Set.of("EXPLICIT","CALENDAR").contains(postingDatePolicy)) throw new IllegalArgumentException("Invalid FLEXCUBE posting-date policy.");
    this.postingZone=ZoneId.of(postingZone);
    if (!userId.isEmpty() && !userId.matches("[A-Za-z0-9_.@-]{1,100}")) throw new IllegalArgumentException("Invalid FLEXCUBE service user.");
    if (!channel.matches("[A-Za-z0-9_-]{1,30}")) throw new IllegalArgumentException("Invalid FLEXCUBE channel.");
    if (!postingDate.isEmpty()) parsePostingDate(postingDate);
    if (!sourceTimezone.equals("UNKNOWN")) ZoneId.of(sourceTimezone);
  }

  ObjectNode request(String serviceCode,String orgBank,String orgBranch,ObjectNode args1) {
    if (!Set.of("PO01","PO02").contains(serviceCode)) throw new IllegalArgumentException("Unsupported inquiry service.");
    if (userId.isEmpty() || postingDatePolicy.equals("EXPLICIT") && postingDate.isEmpty())
      throw new ApiException(503,"FLEXCUBE_CONTEXT_NOT_CONFIGURED","Configure the FLEXCUBE service user and bank posting date before calling the inquiry.");
    int bank=code(orgBank), branch=code(orgBranch);
    if (!args1.path("originatingBankCode").isInt() || args1.path("originatingBankCode").intValue()!=bank
        || !args1.path("originatingBranchCode").isInt() || args1.path("originatingBranchCode").intValue()!=branch)
      throw new IllegalArgumentException("FLEXCUBE session context must match the authorized inquiry scope.");
    String date=postingDatePolicy.equals("EXPLICIT")?postingDate:LocalDate.now(postingZone).format(DateTimeFormatter.BASIC_ISO_DATE);
    ObjectNode body=mapper.createObjectNode();
    body.putObject("args0").put("bankCode",bank).put("transactionBranch",orgBranch)
        .put("externalReferenceNo",serviceCode+UUID.randomUUID().toString().replace("-", "").substring(0,24))
        .put("postingDateText",date).put("channel",channel).put("serviceCode",serviceCode).put("userId",userId);
    body.set("args1",args1.deepCopy());
    return body;
  }

  /** HTTP 200 alone is not a successful FLEXCUBE inquiry. Never exposes upstream error prose. */
  void validateResponse(ObjectNode response,ObjectNode request) {
    JsonNode status=response.path("transactionStatus");
    if (!status.isObject() || !status.path("errorCode").isTextual()
        || !"0".equals(status.path("errorCode").textValue())
        || !zero(status.path("replyCode")) || !zero(status.path("spReturnValue")))
      throw new ApiException(502,"FLEXCUBE_INQUIRY_FAILED","FLEXCUBE did not report a successful inquiry; no records were imported.");
    for (String field:Set.of("isOverriden","isServiceChargeApplied","FCYHangeHandlingApplied")) {
      JsonNode flag=status.get(field);
      if (flag!=null && (!flag.isBoolean() || flag.booleanValue())) throw invalid();
    }
    if (!status.path("externalReferenceNo").isTextual()
        || !status.path("externalReferenceNo").equals(request.path("args0").path("externalReferenceNo"))
        || !response.path("postingDate").isTextual()
        || !response.path("postingDate").equals(request.path("args0").path("postingDateText"))) throw invalid();
  }

  String sourceTimezone() { return sourceTimezone; }
  static int code(String value) {
    try {
      if (value==null || !value.matches("0|[1-9][0-9]{0,9}")) throw new NumberFormatException();
      return Integer.parseInt(value);
    } catch (NumberFormatException error) {
      throw new ApiException(422,"INVALID_FLEXCUBE_SCOPE","FLEXCUBE bank and branch codes must be unpadded integers from 0 through 2147483647.");
    }
  }
  private static boolean zero(JsonNode value) { return value.isIntegralNumber() && value.canConvertToInt() && value.intValue()==0; }
  private static void parsePostingDate(String value) {
    if (!value.matches("[0-9]{8}")) throw new IllegalArgumentException("FLEXCUBE posting date must be yyyyMMdd.");
    LocalDate.parse(value,DateTimeFormatter.BASIC_ISO_DATE);
  }
  private static ApiException invalid() { return new ApiException(502,"INVALID_FLEXCUBE_RESPONSE","FLEXCUBE response context does not match this inquiry, or its status flags are unsupported; no records were imported."); }
}
