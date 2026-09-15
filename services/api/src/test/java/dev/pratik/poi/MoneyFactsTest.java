package dev.pratik.poi;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class MoneyFactsTest {
  ObjectNode base() {
    ObjectNode c = new ObjectMapper().createObjectNode();
    c.put("currency", "INR");
    c.putArray("ledgerEntries");
    c.putObject("provider").put("status", "UNKNOWN");
    return c;
  }

  void entry(ObjectNode c, String type, long amount, String currency) {
    c.withArray("ledgerEntries")
        .addObject()
        .put("type", type)
        .put("amountMinor", amount)
        .put("currency", currency);
  }

  @Test
  void checkedJavaReconciliationFindsMissingRefund() {
    ObjectNode c = base();
    entry(c, "CAPTURE", 100000, "INR");
    entry(c, "FEE", -3000, "INR");
    ((ObjectNode) c.path("provider")).put("status", "SUCCEEDED").put("payoutMinor", 77000);
    ObjectNode facts = MoneyFacts.calculate(c);
    assertThat(facts.path("calculatedBy").asText()).isEqualTo("java-api");
    assertThat(facts.path("ledgerNetMinor").asLong()).isEqualTo(97000);
    assertThat(facts.path("discrepancyMinor").asLong()).isEqualTo(20000);
    entry(c, "REFUND", -20000, "INR");
    facts = MoneyFacts.calculate(c);
    assertThat(facts.path("refundMinor").asLong()).isEqualTo(20000);
    assertThat(facts.path("discrepancyMinor").asLong()).isZero();
  }

  @Test
  void normalizedLedgerAliasesShareTotalsAndPreserveSignedAdjustments() {
    ObjectNode c = base();
    entry(c, "CAPTURE", 10000, "INR");
    entry(c, "payment.captured", 5000, "INR");
    entry(c, "sale", 1000, "INR");
    entry(c, "refund.posted", -1200, "INR");
    entry(c, "refund", -800, "INR");
    entry(c, "FEE", -300, "INR");
    entry(c, "ADJUSTMENT", 250, "INR");
    entry(c, "ADJUSTMENT", -50, "INR");
    ((ObjectNode) c.path("provider")).put("status", "succeeded").put("payoutMinor", 13900);
    ObjectNode facts = MoneyFacts.calculate(c);
    assertThat(facts.path("validMoney").asBoolean()).isTrue();
    assertThat(facts.path("captureCount").asInt()).isEqualTo(3);
    assertThat(facts.path("captureMinor").asLong()).isEqualTo(16000);
    assertThat(facts.path("refundMinor").asLong()).isEqualTo(2000);
    assertThat(facts.path("ledgerNetMinor").asLong()).isEqualTo(13900);
    assertThat(facts.path("discrepancyMinor").asLong()).isZero();
  }

  @Test
  void postedRefundAliasIsNotReportedAsAnAbsentPosting() {
    ObjectNode c = base();
    entry(c, "CAPTURE", 100000, "INR");
    entry(c, "FEE", -3000, "INR");
    entry(c, "REFUND_POSTED", -10000, "INR");
    ((ObjectNode) c.path("provider"))
        .put("status", "SUCCEEDED")
        .put("refundMinor", 10000)
        .put("payoutMinor", 87000);
    ObjectNode facts = MoneyFacts.calculate(c);
    assertThat(facts.path("refundMinor").asLong()).isEqualTo(10000);
    assertThat(facts.path("ledgerNetMinor").asLong()).isEqualTo(87000);
    assertThat(facts.path("discrepancyMinor").asLong()).isZero();
  }

  @Test
  void zeroAndOppositeSignsCannotProduceAuthoritativeValidMoney() {
    for (String type : new String[] {"capture", "PAYMENT-CAPTURED", "sale"}) {
      for (long amount : new long[] {0, -1}) {
        ObjectNode c = base();
        entry(c, type, amount, "INR");
        assertThatThrownBy(() -> MoneyFacts.calculate(c))
            .isInstanceOfSatisfying(
                ApiException.class,
                error -> {
                  assertThat(error.status).isEqualTo(422);
                  assertThat(error.code).isEqualTo("INVALID_AMOUNT");
                  assertThat(error.getMessage()).contains("positive");
                });
      }
    }
    for (String type : new String[] {"refund", "refund.posted"}) {
      for (long amount : new long[] {0, 1}) {
        ObjectNode c = base();
        entry(c, type, amount, "INR");
        assertThatThrownBy(() -> MoneyFacts.calculate(c))
            .isInstanceOfSatisfying(
                ApiException.class,
                error -> {
                  assertThat(error.status).isEqualTo(422);
                  assertThat(error.code).isEqualTo("INVALID_AMOUNT");
                  assertThat(error.getMessage()).contains("negative");
                });
      }
    }
    ObjectNode fee = base();
    entry(fee, "fee", 1, "INR");
    assertThatThrownBy(() -> MoneyFacts.calculate(fee))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> {
              assertThat(error.status).isEqualTo(422);
              assertThat(error.code).isEqualTo("INVALID_AMOUNT");
              assertThat(error.getMessage()).contains("non-positive");
            });
    ((ObjectNode) fee.path("ledgerEntries").get(0)).put("amountMinor", 0);
    assertThat(MoneyFacts.calculate(fee).path("validMoney").asBoolean()).isTrue();
  }

  @Test
  void unknownAndUnavailableProviderStatesNeverMakeZeroPayoutEvidence() {
    for (String status :
        new String[] {"UNKNOWN", "unknown", "UnKnOwN", "UNAVAILABLE", "unavailable", "UnAvAiLaBlE", "", " \t"}) {
      ObjectNode c = base();
      ((ObjectNode) c.path("provider")).put("status", status).put("payoutMinor", 0);
      ObjectNode facts = MoneyFacts.calculate(c);
      assertThat(facts.path("providerAvailable").asBoolean()).as(status).isFalse();
      assertThat(facts.path("providerPayoutMinor").isNull()).isTrue();
      assertThat(facts.path("discrepancyMinor").isNull()).isTrue();
    }
    ObjectNode known = base();
    ((ObjectNode) known.path("provider")).put("status", "suCceEded").put("payoutMinor", 0);
    ObjectNode facts = MoneyFacts.calculate(known);
    assertThat(facts.path("providerAvailable").asBoolean()).isTrue();
    assertThat(facts.path("providerPayoutMinor").asLong()).isZero();
    assertThat(facts.path("discrepancyMinor").asLong()).isZero();
  }

  @Test
  void unknownProviderDoesNotTurnZeroIntoEvidence() {
    ObjectNode c = base();
    entry(c, "CAPTURE", 100000, "INR");
    ((ObjectNode) c.path("provider")).put("payoutMinor", 0);
    ObjectNode facts = MoneyFacts.calculate(c);
    assertThat(facts.path("providerAvailable").asBoolean()).isFalse();
    assertThat(facts.path("providerPayoutMinor").isNull()).isTrue();
    assertThat(facts.path("discrepancyMinor").isNull()).isTrue();
  }

  @Test
  void overflowFailsInsteadOfWrapping() {
    ObjectNode c = base();
    entry(c, "CAPTURE", Long.MAX_VALUE, "INR");
    entry(c, "CAPTURE", 1, "INR");
    assertThatThrownBy(() -> MoneyFacts.calculate(c))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("range");
  }

  @Test
  void signedJsonSafeBoundariesAreExactAndAdjacentValuesAreRejected() {
    for (long value : new long[] {JsonSupport.MAX_SAFE_INTEGER, -JsonSupport.MAX_SAFE_INTEGER}) {
      ObjectNode c = base();
      entry(c, "ADJUSTMENT", value, "INR");
      assertThat(MoneyFacts.calculate(c).path("ledgerNetMinor").longValue()).isEqualTo(value);
    }
    for (long value :
        new long[] {JsonSupport.MAX_SAFE_INTEGER + 1, -JsonSupport.MAX_SAFE_INTEGER - 1}) {
      ObjectNode c = base();
      entry(c, "ADJUSTMENT", value, "INR");
      assertThatThrownBy(() -> MoneyFacts.calculate(c))
          .isInstanceOf(ApiException.class)
          .hasMessageContaining("JSON-safe");
    }
  }

  @Test
  void safeInputsCannotProduceUnsafeReconciliationTotals() {
    ObjectNode c = base();
    entry(c, "CAPTURE", JsonSupport.MAX_SAFE_INTEGER, "INR");
    entry(c, "CAPTURE", 1, "INR");
    assertThatThrownBy(() -> MoneyFacts.calculate(c))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("ledgerNetMinor");

    ObjectNode negative = base();
    entry(negative, "ADJUSTMENT", -JsonSupport.MAX_SAFE_INTEGER, "INR");
    entry(negative, "ADJUSTMENT", -1, "INR");
    assertThatThrownBy(() -> MoneyFacts.calculate(negative))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("ledgerNetMinor");

    ObjectNode discrepancy = base();
    entry(discrepancy, "CAPTURE", JsonSupport.MAX_SAFE_INTEGER, "INR");
    ((ObjectNode) discrepancy.path("provider")).put("status", "SUCCEEDED").put("payoutMinor", -1);
    assertThatThrownBy(() -> MoneyFacts.calculate(discrepancy))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("discrepancyMinor");
  }

  @Test
  void mixedCurrenciesCannotBeReconciled() {
    ObjectNode c = base();
    entry(c, "CAPTURE", 100, "USD");
    assertThatThrownBy(() -> MoneyFacts.calculate(c))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("currency");
  }
}
