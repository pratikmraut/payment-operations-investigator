package dev.pratik.poi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

/** Regression boundaries shared by the Java importer and the Python OBPM request schema. */
class ObpmReviewTest {
  private final ObjectMapper mapper = new ObjectMapper();

  private ObjectNode sample() throws Exception {
    return (ObjectNode)
        mapper.readTree(
            """
            {
              "schemaVersion": "obpm-evidence-v1", "dataClassification": "SYNTHETIC",
              "snapshotId": "REVIEW-SNAPSHOT-1", "mappingVersion": "synthetic-v1",
              "extractedAt": "2026-09-12T05:20:00Z",
              "source": {
                "deploymentId": "SYNTHETIC-OBPM", "releaseFamily": "14.7",
                "exactMaintenanceRelease": null, "hostCode": "DEMO-HOST", "branchCode": "DEMO-BRANCH"
              },
              "payment": {
                "sourcePaymentId": "REVIEW-DEMO-1", "rail": "NEFT", "direction": "OUTBOUND",
                "sourceAmountDecimal": "12500.00", "amountMinor": 1250000, "currency": "INR",
                "activationDate": "2026-09-12", "createdAt": "2026-09-12T05:00:00Z",
                "nativeTransactionStatus": null, "statusUnavailableReason": "Not supplied"
              },
              "queueRecords": [{
                "evidenceId": "REVIEW-Q-1", "sourcePaymentId": "REVIEW-DEMO-1",
                "queueReference": "REVIEW-QUEUE-1", "requestAttemptId": "REVIEW-REQ-1",
                "nativeQueueCode": "EC", "nativeResponseStatus": "T",
                "enteredAt": "2026-09-12T05:01:00Z", "exitedAt": null,
                "isCurrentQueueRecord": true, "observedAt": "2026-09-12T05:20:00Z"
              }],
              "externalRequestAttempts": [{
                "evidenceId": "REVIEW-ECA-1", "requestAttemptId": "REVIEW-REQ-1",
                "sourcePaymentId": "REVIEW-DEMO-1", "requestType": "ECA",
                "requestedAt": "2026-09-12T05:00:30Z", "timeoutRecordedAt": "2026-09-12T05:01:00Z",
                "externalSystemFinalOutcome": null
              }],
              "messages": [], "accountingEntries": [],
              "sourceCoverage": {
                "queueRecords": {
                  "status": "COMPLETE", "scope": "REVIEW-DEMO-1 queue records",
                  "asOf": "2026-09-12T05:20:00Z", "paginationComplete": true
                },
                "externalCoreResponses": {"status": "UNAVAILABLE", "reason": "Not connected"},
                "messages": {"status": "NOT_REQUESTED", "reason": "Not supported"},
                "accountingEntries": {"status": "UNAVAILABLE", "reason": "Not connected"}
              }
            }
            """);
  }

  private void rejected(ObjectNode input) {
    assertThatThrownBy(() -> ObpmEvidenceValidator.validate(input))
        .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.status).isEqualTo(422));
  }

  @Test
  void supportedSnapshotIsAcceptedWithoutMutatingItsEvidence() throws Exception {
    ObjectNode input = sample();
    ObjectNode original = input.deepCopy();
    assertThat(ObpmEvidenceValidator.validate(input)).isEqualTo(original).isNotSameAs(input);
    assertThat(input).isEqualTo(original);
  }

  @Test
  void nonCanonicalLeadingZeroAmountCannotBecomeAnUninvestigableStoredCase() throws Exception {
    ObjectNode input = sample();
    ((ObjectNode) input.path("payment")).put("sourceAmountDecimal", "012500.00");
    rejected(input);
  }

  @Test
  void nativeCodesRespectTheWorkersEightyCharacterBoundary() throws Exception {
    for (String pointer :
        List.of(
            "/queueRecords/0/nativeQueueCode",
            "/queueRecords/0/nativeResponseStatus",
            "/payment/nativeTransactionStatus",
            "/externalRequestAttempts/0/externalSystemFinalOutcome")) {
      ObjectNode input = sample();
      int separator = pointer.lastIndexOf('/');
      ObjectNode parent = (ObjectNode) input.at(pointer.substring(0, separator));
      parent.put(pointer.substring(separator + 1), "X".repeat(81));
      rejected(input);
    }
  }

  @Test
  void maintenanceReleaseRespectsTheWorkersTwoHundredCharacterBoundary() throws Exception {
    ObjectNode input = sample();
    ((ObjectNode) input.path("source")).put("exactMaintenanceRelease", "X".repeat(201));
    rejected(input);
  }

  @Test
  void activationDateMustFitTheWorkersSupportedCalendar() throws Exception {
    for (String value : List.of("+10000-01-01", "0000-01-01")) {
      ObjectNode input = sample();
      ((ObjectNode) input.path("payment")).put("activationDate", value);
      rejected(input);
    }
  }

  @Test
  void extractionTimestampMustFitTheWorkersSupportedCalendar() throws Exception {
    for (String value :
        List.of(
            "+10000-01-01T00:00:00Z",
            "2026-09-12T24:00:00Z",
            "2026-09-12T05:20:60Z")) {
      ObjectNode input = sample();
      input.put("extractedAt", value);
      rejected(input);
    }
  }

  @Test
  void creationTimestampCannotUseYearZero() throws Exception {
    ObjectNode input = sample();
    ((ObjectNode) input.path("payment")).put("createdAt", "0000-01-01T00:00:00Z");
    rejected(input);
  }

  @Test
  void emptyOrJsonNullBodiesReachAControlled422Boundary() {
    // Invalid input must fail before the importer needs any database collaborator.
    ObpmImportService service =
        new ObpmImportService(null, null, null, mapper, null, "unused-synthetic-samples");
    ObpmController controller = new ObpmController(service, mapper);
    var auth = UsernamePasswordAuthenticationToken.authenticated("analyst", null, List.of());
    for (String body : List.of("", "  ", "null", "[]")) {
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.setContent(body.getBytes(StandardCharsets.UTF_8));
      assertThatThrownBy(() -> controller.ingest(request, auth))
          .isInstanceOfSatisfying(
              ApiException.class, failure -> assertThat(failure.status).isEqualTo(422));
    }
  }
}
