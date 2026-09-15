package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Validates the normalized synthetic boundary; it makes no bank-side status inference. */
public final class ObpmEvidenceValidator {
  private ObpmEvidenceValidator() {}

  public static ObjectNode validate(JsonNode input) {
    fields(input, "snapshot", "schemaVersion", "dataClassification", "snapshotId", "mappingVersion",
        "extractedAt", "source", "payment", "queueRecords", "externalRequestAttempts", "messages",
        "accountingEntries", "sourceCoverage");
    equal(input, "schemaVersion", "obpm-evidence-v1");
    equal(input, "dataClassification", "SYNTHETIC");
    identifier(input, "snapshotId");
    identifier(input, "mappingVersion");
    Instant cutoff = time(input, "extractedAt", false);
    JsonNode source = input.path("source"), payment = input.path("payment");
    fields(source, "source", "deploymentId", "releaseFamily", "exactMaintenanceRelease", "hostCode", "branchCode");
    equal(source, "deploymentId", "SYNTHETIC-OBPM");
    equal(source, "releaseFamily", "14.7");
    equal(source, "hostCode", "DEMO-HOST");
    equal(source, "branchCode", "DEMO-BRANCH");
    nullableText(source, "exactMaintenanceRelease", true);
    limit(source, "exactMaintenanceRelease", 200);
    fields(payment, "payment", "sourcePaymentId", "rail", "direction", "sourceAmountDecimal", "amountMinor",
        "currency", "activationDate", "createdAt", "nativeTransactionStatus", "statusUnavailableReason");
    String paymentId = identifier(payment, "sourcePaymentId");
    equal(payment, "rail", "NEFT");
    equal(payment, "direction", "OUTBOUND");
    equal(payment, "currency", "INR");
    String decimal = text(payment, "sourceAmountDecimal");
    long amount = JsonSupport.minor(payment, "amountMinor");
    try {
      if (!decimal.matches("(0|[1-9][0-9]{0,13})(\\.[0-9]{1,2})?") || amount < 0
          || new BigDecimal(decimal).movePointRight(2).longValueExact() != amount)
        fail("sourceAmountDecimal must exactly match non-negative INR amountMinor.");
    } catch (ArithmeticException ex) { fail("Source amount exceeds the supported range."); }
    try {
      if (!text(payment, "activationDate").matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) fail("activationDate must use YYYY-MM-DD.");
      if (LocalDate.parse(text(payment, "activationDate")).getYear() < 1) fail("activationDate year must be between 1 and 9999.");
    }
    catch (java.time.DateTimeException ex) { fail("activationDate must be an ISO date."); }
    Instant created = time(payment, "createdAt", false);
    before(created, cutoff, "Payment creation exceeds extraction time.");
    nullableText(payment, "nativeTransactionStatus", true);
    limit(payment, "nativeTransactionStatus", 80);
    nullableText(payment, "statusUnavailableReason", false);
    if (payment.path("nativeTransactionStatus").isNull()) text(payment, "statusUnavailableReason");
    Set<String> evidenceIds = new HashSet<>();
    Map<String, Instant> attempts = new HashMap<>();
    for (JsonNode attempt : array(input, "externalRequestAttempts")) {
      fields(attempt, "external request", "evidenceId", "requestAttemptId", "sourcePaymentId", "requestType",
          "requestedAt", "timeoutRecordedAt", "externalSystemFinalOutcome");
      unique(evidenceIds, identifier(attempt, "evidenceId"));
      String attemptId = identifier(attempt, "requestAttemptId");
      equal(attempt, "sourcePaymentId", paymentId);
      equal(attempt, "requestType", "ECA");
      Instant requested = time(attempt, "requestedAt", false);
      before(created, requested, "Request precedes payment creation.");
      before(requested, cutoff, "Request exceeds extraction time.");
      if (attempts.putIfAbsent(attemptId, requested) != null) fail("Duplicate requestAttemptId.");
      Instant timeout = time(attempt, "timeoutRecordedAt", true);
      if (timeout != null) {
        before(requested, timeout, "Timeout precedes request.");
        before(timeout, cutoff, "Timeout exceeds extraction time.");
      }
      nullableText(attempt, "externalSystemFinalOutcome", true);
      limit(attempt, "externalSystemFinalOutcome", 80);
    }
    Set<String> queueReferences = new HashSet<>();
    for (JsonNode queue : array(input, "queueRecords")) {
      fields(queue, "queue record", "evidenceId", "sourcePaymentId", "queueReference", "requestAttemptId",
          "nativeQueueCode", "nativeResponseStatus", "enteredAt", "exitedAt", "isCurrentQueueRecord", "observedAt");
      unique(evidenceIds, identifier(queue, "evidenceId"));
      unique(queueReferences, identifier(queue, "queueReference"));
      equal(queue, "sourcePaymentId", paymentId);
      String attemptId = identifier(queue, "requestAttemptId");
      if (!attempts.containsKey(attemptId)) fail("Queue requestAttemptId must reference a supplied ECA attempt.");
      text(queue, "nativeQueueCode");
      text(queue, "nativeResponseStatus");
      limit(queue, "nativeQueueCode", 80);
      limit(queue, "nativeResponseStatus", 80);
      if (!queue.path("isCurrentQueueRecord").isBoolean()) fail("isCurrentQueueRecord must be boolean.");
      Instant entered = time(queue, "enteredAt", true), exited = time(queue, "exitedAt", true);
      Instant observed = time(queue, "observedAt", false);
      before(created, observed, "Queue observation precedes payment creation.");
      before(observed, cutoff, "Queue observation exceeds extraction time.");
      if (entered != null) {
        before(attempts.get(attemptId), entered, "Queue entry precedes its request.");
        before(entered, observed, "Queue entry exceeds observation time.");
      }
      if (exited != null) {
        if (entered == null) fail("Queue exit requires queue entry time.");
        before(entered, exited, "Queue exit precedes entry.");
        before(exited, observed, "Queue exit exceeds observation time.");
        if (queue.path("isCurrentQueueRecord").booleanValue()) fail("An exited queue record cannot be current.");
      }
    }
    for (String unsupported : List.of("messages", "accountingEntries"))
      if (!array(input, unsupported).isEmpty())
        throw new ApiException(422, "UNSUPPORTED_EVIDENCE", unsupported + " requires a later typed mapping; it cannot be silently ignored.");
    JsonNode coverage = input.path("sourceCoverage");
    fields(coverage, "sourceCoverage", "queueRecords", "messages", "externalCoreResponses", "accountingEntries");
    for (String name : List.of("queueRecords", "messages", "externalCoreResponses", "accountingEntries")) {
      JsonNode group = coverage.path(name);
      fields(group, name + " coverage", "status", "scope", "asOf", "paginationComplete", "reason");
      String status = text(group, "status");
      if (!Set.of("COMPLETE", "PARTIAL", "UNAVAILABLE", "NOT_REQUESTED").contains(status)) fail("Unsupported coverage status.");
      if (List.of("messages", "accountingEntries").contains(name)
          && !Set.of("UNAVAILABLE", "NOT_REQUESTED").contains(status)) fail(name + " is not supported by this contract.");
      if (status.equals("COMPLETE")) {
        text(group, "scope");
        if (!group.path("paginationComplete").isBoolean() || !group.path("paginationComplete").booleanValue())
          fail("Complete coverage requires paginationComplete:true.");
        time(group, "asOf", false);
      } else { text(group, "reason"); }
      if (group.hasNonNull("asOf")) before(time(group, "asOf", false), cutoff, "Coverage exceeds extraction time.");
      if (group.has("paginationComplete") && !group.path("paginationComplete").isBoolean()) fail("paginationComplete must be boolean.");
      nullableText(group, "scope", false);
      nullableText(group, "reason", false);
    }
    return ((ObjectNode) input).deepCopy();
  }

  private static void fields(JsonNode node, String label, String... allowed) {
    if (node == null || !node.isObject()) fail(label + " must be an object.");
    Set<String> names = Set.of(allowed);
    node.fieldNames().forEachRemaining(name -> { if (!names.contains(name)) fail("Unsupported field in " + label + ": " + name); });
  }
  private static JsonNode array(JsonNode node, String name) {
    JsonNode value = node.path(name);
    if (!value.isArray() || value.size() > 100) fail(name + " must be an array of at most 100 records.");
    return value;
  }
  private static String text(JsonNode node, String name) {
    String value = JsonSupport.requiredText(node, name);
    if (value.length() > 500 || value.chars().anyMatch(Character::isISOControl)) fail(name + " is too long or contains control characters.");
    return value;
  }
  private static String identifier(JsonNode node, String name) {
    String value = text(node, name);
    if (!value.matches("[A-Za-z0-9._:-]{1,100}")) fail(name + " is not a supported identifier.");
    return value;
  }
  private static void nullableText(JsonNode node, String name, boolean required) {
    if (!node.has(name)) { if (required) fail(name + " must be present; use null when unknown."); return; }
    if (!node.path(name).isNull()) text(node, name);
  }
  private static void limit(JsonNode node, String name, int maximum) {
    if (node.hasNonNull(name) && node.path(name).asText().length() > maximum) fail(name + " exceeds " + maximum + " characters.");
  }
  private static void equal(JsonNode node, String name, String expected) {
    if (!text(node, name).equals(expected)) fail("Unsupported or inconsistent " + name + ".");
  }
  private static Instant time(JsonNode node, String name, boolean optional) {
    if (optional && !node.hasNonNull(name)) return null;
    String value = text(node, name);
    try {
      if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?Z")) fail(name + " must be a UTC timestamp ending in Z (maximum microsecond precision).");
      var parsed = java.time.OffsetDateTime.parse(value);
      if (parsed.getYear() < 1) fail(name + " year must be between 1 and 9999.");
      return parsed.toInstant();
    } catch (java.time.DateTimeException ex) { fail(name + " must be a UTC ISO timestamp."); return null; }
  }
  private static void before(Instant first, Instant second, String message) {
    if (first.isAfter(second)) fail(message);
  }
  private static void unique(Set<String> values, String id) { if (!values.add(id)) fail("Duplicate evidence or queue identifier."); }
  private static void fail(String message) { throw new ApiException(422, "INVALID_OBPM_EVIDENCE", message); }
}
