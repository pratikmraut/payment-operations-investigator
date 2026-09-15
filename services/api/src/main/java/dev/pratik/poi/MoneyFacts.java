package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.Set;

public final class MoneyFacts {
  private static final Set<String> CAPTURE_TYPES = Set.of("CAPTURE", "PAYMENT_CAPTURED", "SALE");
  private static final Set<String> REFUND_TYPES = Set.of("REFUND", "REFUND_POSTED");

  private MoneyFacts() {}

  public static ObjectNode calculate(ObjectNode record) {
    ObjectNode result = record.objectNode();
    String currency = JsonSupport.requiredText(record, "currency");
    JsonSupport.validateMoneyFields(record, currency);
    if (record.path("domain").asText().equals("OBPM_NEFT")) {
      result.put("validMoney", true).put("providerAvailable", false).put("currency", currency)
          .put("calculatedBy", "java-api").put("scope", "OBPM_EVIDENCE");
      for (String field : java.util.List.of("captureCount", "captureMinor", "refundMinor", "ledgerNetMinor", "providerPayoutMinor", "discrepancyMinor"))
        result.putNull(field);
      return result;
    }
    long net = 0, capture = 0, refund = 0;
    int captureCount = 0;
    try {
      for (JsonNode entry : record.path("ledgerEntries")) {
        if (!currency.equals(entry.path("currency").asText())) {
          throw new ApiException(
              422, "CURRENCY_MISMATCH", "Ledger currency differs from case currency.");
        }
        long amount = JsonSupport.minor(entry, "amountMinor");
        String type = code(entry.path("type"));
        boolean isCapture = CAPTURE_TYPES.contains(type);
        boolean isRefund = REFUND_TYPES.contains(type);
        if (isCapture && amount <= 0)
          throw new ApiException(422, "INVALID_AMOUNT", "Capture ledger amounts must be positive.");
        if (isRefund && amount >= 0)
          throw new ApiException(422, "INVALID_AMOUNT", "Refund ledger amounts must be negative.");
        if (type.equals("FEE") && amount > 0)
          throw new ApiException(422, "INVALID_AMOUNT", "Fee ledger amounts must be non-positive.");
        net = JsonSupport.safeInteger(Math.addExact(net, amount), "ledgerNetMinor");
        if (isCapture) {
          capture = JsonSupport.safeInteger(Math.addExact(capture, amount), "captureMinor");
          captureCount++;
        }
        if (isRefund) {
          refund =
              JsonSupport.safeInteger(
                  Math.addExact(refund, Math.negateExact(amount)), "refundMinor");
        }
      }
      JsonNode provider = record.path("provider");
      String providerStatus = code(provider.path("status"));
      boolean available =
          provider.isObject()
              && !providerStatus.isBlank()
              && !Set.of("UNKNOWN", "UNAVAILABLE").contains(providerStatus)
              && provider.hasNonNull("payoutMinor");
      result
          .put("validMoney", true)
          .put("captureCount", captureCount)
          .put("captureMinor", capture)
          .put("refundMinor", refund)
          .put("ledgerNetMinor", net)
          .put("providerAvailable", available)
          .put("currency", currency)
          .put("calculatedBy", "java-api");
      if (available) {
        long payout = JsonSupport.minor(provider, "payoutMinor");
        result
            .put("providerPayoutMinor", payout)
            .put(
                "discrepancyMinor",
                JsonSupport.safeInteger(Math.subtractExact(net, payout), "discrepancyMinor"));
      } else {
        result.putNull("providerPayoutMinor").putNull("discrepancyMinor");
      }
      return result;
    } catch (ArithmeticException e) {
      throw new ApiException(
          422, "AMOUNT_OVERFLOW", "Reconciliation exceeds the supported signed minor-unit range.");
    }
  }

  private static String code(JsonNode value) {
    return value.asText("").toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
  }
}
