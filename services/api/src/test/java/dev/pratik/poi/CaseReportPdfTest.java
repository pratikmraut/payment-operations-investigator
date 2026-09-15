package dev.pratik.poi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

class CaseReportPdfTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String REFERENCE = "000123456789012345678901234567890";
  private static final String AMOUNT = "123456789012345678901234.0007";
  private final CaseReportPdf renderer = new CaseReportPdf();

  @Test void rendersExactCaseNumberInSummaryAndDetailedReportsWithoutChangingPaymentReference() throws Exception {
    for (String mode : List.of("SUMMARY", "DETAILED")) {
      ObjectNode report = fixture(false);
      ((ObjectNode) report.path("case")).put("caseNumber", "2026091600001");
      ((ObjectNode) report.path("scope")).put("reportMode", mode);
      ObjectNode before = report.deepCopy();
      try (PDDocument document = Loader.loadPDF(renderer.render(report))) {
        String text = new PDFTextStripper().getText(document);
        assertThat(text).contains("Case number", "2026091600001", REFERENCE);
        if (mode.equals("SUMMARY")) assertThat(document.getNumberOfPages()).isLessThanOrEqualTo(3);
      }
      assertThat(report).isEqualTo(before);
    }
  }

  @Test void po02NativeAppendixRestoresSourceNullsWithoutDumpingServiceContext() throws Exception {
    ObjectNode report=fixture(true);
    ObjectNode request=FlexcubeEvidenceFixtures.request(JSON,REFERENCE,"760","1352");
    var adapted=new FlexcubeEvidenceAdapter(JSON).adapt(FlexcubeEvidenceFixtures.response(JSON,request),request,"UNKNOWN",java.time.Instant.parse("2026-09-14T05:00:00Z"));
    ObjectNode evidence=(ObjectNode)report.path("evidence");evidence.put("sourceKind","BANK_API");
    evidence.set("payload",adapted.payload());evidence.set("upstream",adapted.upstream());
    evidence.put("evidenceHash",CaseEvidenceService.fingerprint(evidence));
    try(PDDocument document=Loader.loadPDF(renderer.render(report))) {
      String text=new PDFTextStripper().getText(document).replace("\r\n", "\n");
      assertThat(text).contains("IDRELATEDREF_2006\n(null)","N10_STATUS\n(empty string)","API receipt time")
          .doesNotContain("TESTUSER", "PO02-ORIGINAL-TEST");
    }
    assertThat(evidence.path("payload").path("sections").path("PAYMENT").path("rows").get(0).path("IDRELATEDREF_2006").isTextual()).isTrue();
  }

  static ObjectNode fixture(boolean raw) {
    ObjectNode report = JSON.createObjectNode();
    report.put("schemaVersion", "payment-case-report-v1").put("reportId", "REPORT-ORIGINAL-FIXTURE")
        .put("reportHash", "a1234567890123456789012345678901234567890123456789012345678901234")
        .put("generatedAt", "2026-09-14T12:00:00+05:30");
    report.putObject("generatedBy").put("id", "original-analyst").put("name", "Zoë Original").put("role", "ANALYST");
    report.putObject("case").put("id", "CASE-ORIGINAL-REPORT").put("reference", REFERENCE)
        .putNull("utr").put("orgBank", "009").put("orgBranch", "0012").put("amount", AMOUNT).put("currency", "INR ₹")
        .put("reason", "Original report fixture only. Investigate the supplied source records.")
        .put("status", "OPEN").put("priority", "HIGH").put("owner", "original-analyst")
        .put("initiatedAt", "2026-09-13T23:59:00").put("evidenceStatus", "EVIDENCE_ATTACHED");
    ObjectNode management = report.putObject("management").put("version", 3).put("owner", "original-analyst").put("priority", "HIGH");
    management.putArray("notes").addObject().put("text", "Retain the exact source observations; do not infer settlement.").put("createdByName", "Zoë Original").put("createdAt", "2026-09-14T10:15:00Z");
    management.putArray("evidenceRequests").addObject().put("title", "Beneficiary-side confirmation").put("detail", "Obtain the beneficiary-side record.").put("status", "OPEN").putNull("dueDate");
    management.putArray("reviewerConclusions");
    management.putArray("audit").addObject().put("action", "OWNER_CHANGED").put("occurredAt", "2026-09-14T10:00:00Z").put("actorName", "Zoë Original").put("detail", "Assigned the original fixture case to its investigating analyst.");
    report.putObject("review").put("status", "PENDING").putNull("conclusion");
    ObjectNode evidence = report.putObject("evidence").put("id", "EVIDENCE-ORIGINAL-2").put("caseId", "CASE-ORIGINAL-REPORT")
        .put("version", 2).put("sourceKind", "JSON").put("createdAt", "2026-09-14T11:00:00Z")
        .put("createdBy", "original-analyst").put("evidenceHash", "original-evidence-hash-2").put("sourceTimezone", "UNKNOWN");
    ObjectNode coverage = evidence.putObject("coverage");
    for (String group : List.of("PAYMENT", "HOST", "HISTORY", "STATUS"))
      coverage.putObject(group).put("rowCount", group.equals("PAYMENT") ? 1 : 0).put("completion", "UNVERIFIED");
    evidence.putArray("warnings").add("Original fixture: missing rows do not prove query completion.");
    if (raw) {
      ObjectNode payload = evidence.putObject("payload").put("schemaVersion", "fcr-case-evidence-v1").put("sourceTimezone", "UNKNOWN");
      payload.putObject("payment").put("reference", REFERENCE).put("orgBank", "009").put("orgBranch", "0012");
      ObjectNode sections = payload.putObject("sections");
      sections.putObject("PAYMENT").put("note", "Original raw source row.").putArray("rows").addObject()
          .put("REFTXNNUMBER", REFERENCE).put("AMOUNT", AMOUNT).put("NATIVE_STATUS", "00").put("RAW_ONLY_SENTINEL", "EXACT_RAW_SOURCE_VALUE")
          .put("BLANK_SOURCE", "").putNull("NULL_SOURCE").put("UNICODE_SOURCE", "Zoë ₹ 甲 \u0001");
      for (String group : List.of("HOST", "HISTORY", "STATUS")) sections.putObject(group).put("note", "").putArray("rows");
    }
    report.putObject("scope").put("evidenceId", "EVIDENCE-ORIGINAL-2").put("includeEvidenceRows", raw)
        .putArray("investigationIds").add("JOB-ORIGINAL-1").add("JOB-ORIGINAL-2");
    report.putArray("warnings").add("Private original synthetic validation fixture; no bank or model calls.");
    report.putArray("caseActivity").addObject().put("action", "EVIDENCE_ATTACHED").put("occurredAt", "2026-09-14T11:00:00Z")
        .put("actor", "original-analyst").put("detail", "The selected original evidence version was attached.");
    ArrayNode jobs = report.putArray("investigations");
    ObjectNode job = jobs.addObject().put("id", "JOB-ORIGINAL-1").put("caseId", "CASE-ORIGINAL-REPORT")
        .put("question", "What can the supplied original records establish?").put("status", "COMPLETED")
        .put("evidenceId", "EVIDENCE-ORIGINAL-1").put("evidenceVersion", 1).put("evidenceHash", "original-evidence-hash-1")
        .put("createdBy", "original-analyst").put("createdAt", "2026-09-14T11:01:00Z");
    String claim = "The original record reports native status 00. Its final payment outcome is not established by this fixture.";
    ObjectNode answer = job.putObject("answer").put("answerId", "ANSWER-ORIGINAL-1").put("snapshotId", "EVIDENCE-ORIGINAL-1")
        .put("evidenceHash", "original-evidence-hash-1").put("question", job.path("question").asText())
        .put("mode", "model-generated").put("answerComposition", "joined-model-claims").put("answer", claim)
        .put("generatedAt", "2026-09-14T11:05:00Z");
    answer.putArray("claims").addObject().put("text", claim).putArray("evidenceIds").add("DOC-ORIGINAL-1");
    answer.putArray("unknowns").add("Beneficiary credit remains unverified in these original records.");
    answer.putArray("nextChecks").add("Inspect the separate beneficiary-side record.");
    answer.putObject("model").put("provider", "ollama").put("name", "original-fixture-model").put("actualCalls", 1)
        .put("durationMs", 1200).putNull("promptTokens").put("completionTokens", 45);
    answer.putObject("retrieval").put("method", "original-fixture-retrieval").putArray("documentIds").add("DOC-ORIGINAL-1");
    ObjectNode cited = answer.putArray("citations").addObject().put("id", "DOC-ORIGINAL-1").put("kind", "evidence")
        .put("title", "Original source record").put("content", "Original source text: NATIVE_STATUS=00; amount=" + AMOUNT + "; source date 2026-09-13T23:59:00. <script>do-not-execute</script>");
    cited.putObject("source").put("file", "original-fixture.json").put("sheet", "PAYMENT").putNull("range").put("locator", "sections.PAYMENT.rows[0]");
    jobs.addObject().put("id", "JOB-ORIGINAL-2").put("question", "Second original question still failed.").put("status", "FAILED")
        .put("evidenceId", "EVIDENCE-ORIGINAL-2").put("evidenceVersion", 2).put("evidenceHash", "original-evidence-hash-2")
        .putObject("error").put("code", "ORIGINAL_PROVIDER_FAILURE").put("message", "Original model fixture unavailable.");
    return report;
  }

  @Test void preservesExactTextMixedVersionsCitationsAndPassiveUnicode() throws Exception {
    ObjectNode input = fixture(true);
    ObjectNode untouched = input.deepCopy();
    try (PDDocument pdf = Loader.loadPDF(renderer.render(input))) {
      String text = new PDFTextStripper().getText(pdf);
      assertThat(text).contains(REFERENCE, AMOUNT, "Zoë", "₹", "[U+7532]", "[U+0001]", "(null)", "(empty string)", "(not supplied)",
          "REVIEW PENDING", "UNKNOWN", "original-evidence-hash-1", "original-evidence-hash-2", "JOB-ORIGINAL-1", "JOB-ORIGINAL-2",
          "ORIGINAL_PROVIDER_FAILURE", "original-fixture.json", "sections.PAYMENT.rows[0]", "do-not-execute", "EXACT_RAW_SOURCE_VALUE");
      assertThat(text.replaceAll("\\s+", " ")).contains("Beneficiary credit remains unverified", "Experimental AI output", "Report fingerprint");
      assertThat(pdf.getNumberOfPages()).isGreaterThan(1).isLessThan(20);
      assertThat(pdf.getDocumentCatalog().getOpenAction()).isNull();
      assertThat(pdf.getDocumentCatalog().getNames()).isNull();
      for (var page : pdf.getPages()) {
        assertThat(page.getAnnotations()).isEmpty();
        for (var name : page.getResources().getFontNames()) {
          PDFont font = page.getResources().getFont(name);
          assertThat(font.isEmbedded()).isTrue();
        }
      }
    }
    assertThat(input).isEqualTo(untouched);
  }

  @Test void omitsRawAppendixByScopeButRetainsCompleteCitedSourceAndRecordedConclusion() throws Exception {
    ObjectNode input = fixture(false);
    ObjectNode review = (ObjectNode) input.path("review");
    review.put("status", "RECORDED");
    review.putObject("conclusion").put("id", "REVIEW-ORIGINAL-1").put("conclusion", "Original reviewer: obtain the missing record before concluding.")
        .put("evidenceId", "EVIDENCE-ORIGINAL-2").put("evidenceHash", "original-evidence-hash-2").put("createdByName", "Original reviewer");
    try (PDDocument pdf = Loader.loadPDF(renderer.render(input))) {
      String text = new PDFTextStripper().getText(pdf);
      assertThat(text).contains("RECORDED REVIEW", "Original reviewer", "Original source text", "do-not-execute", "original-evidence-hash-2");
      assertThat(text).doesNotContain("EXACT_RAW_SOURCE_VALUE", "07 / Selected evidence appendix", "REVIEW PENDING");
      assertThat(text.replaceAll("\\s+", " ")).contains("Raw selected evidence rows were omitted by report scope.");
    }
  }

  @Test void wrapsLongUnbrokenSourceValuesWithoutClippingOrLosingTheirTail() throws Exception {
    ObjectNode input = fixture(false);
    String unbroken = "ORIGINAL_" + "W09".repeat(600) + "_EXACT_END";
    ObjectNode citation = (ObjectNode) input.path("investigations").path(0).path("answer").path("citations").path(0);
    citation.put("content", "START\n" + unbroken + "\nEND_OF_COMPLETE_SOURCE");
    byte[] bytes = renderer.render(input);
    try (PDDocument pdf = Loader.loadPDF(bytes)) {
      PositionChecker checker = new PositionChecker();
      String text = checker.getText(pdf);
      // Examine source-body glyphs across page breaks, excluding only the fixed header/footer regions.
      assertThat(checker.body.toString().replaceAll("\\s+", "")).contains(unbroken, "END_OF_COMPLETE_SOURCE");
      assertThat(checker.outside).isEmpty();
      for (int page = 1; page <= pdf.getNumberOfPages(); page++) assertThat(text).contains("Page " + page + " of " + pdf.getNumberOfPages());
      String directory = System.getProperty("poi.reportPdfValidationDir");
      if (directory != null) { Files.createDirectories(Path.of(directory)); Files.write(Path.of(directory).resolve("original-long-source-report.pdf"), bytes); }
    }
  }

  @Test void refusesMoreThanTwoHundredPagesWithoutReturningATruncatedReport() {
    ObjectNode input = fixture(false);
    ((ObjectNode) input.path("case")).put("reason", "Original full page bound fixture.\n".repeat(15000));
    ApiException error = assertThrows(ApiException.class, () -> renderer.render(input));
    assertThat(error.status).isEqualTo(413);
    assertThat(error.code).isEqualTo("CASE_REPORT_TOO_LARGE");
    assertThat(error.getMessage()).contains("200 pages", "fewer investigations", "omit the raw evidence appendix");
  }

  @Test void rejectsUnknownReportSchema() {
    ApiException error = assertThrows(ApiException.class, () -> renderer.render(JSON.createObjectNode()));
    assertThat(error.status).isEqualTo(422);
  }

  @Test void preservesFiFfAndFingerprintAsExactUnicodeCharactersWithoutLigatureSubstitution() throws Exception {
    ObjectNode input = fixture(false);
    String fingerprint = "ff01".repeat(16);
    String words = "fi ff ffi office affinity confirmation";
    input.put("reportHash", fingerprint);
    ((ObjectNode) input.path("case")).put("reason", words);
    ((ObjectNode) input.path("investigations").path(0).path("answer").path("citations").path(0)).put("content", words);
    try (PDDocument pdf = Loader.loadPDF(renderer.render(input))) {
      // PDFTextStripper's final text may normalize ligatures; inspect its original glyph mappings instead.
      PositionChecker checker = new PositionChecker();
      checker.getText(pdf);
      assertThat(checker.body.toString()).contains(fingerprint, words).doesNotContain("\uFB00", "\uFB01", "\uFB03");
    }
  }

  @Test void rendersEveryPageOfOriginalVisualFixture() throws Exception {
    String directory = System.getProperty("poi.reportPdfValidationDir");
    byte[] bytes = renderer.render(fixture(true));
    try (PDDocument pdf = Loader.loadPDF(bytes)) {
      PDFRenderer rendering = new PDFRenderer(pdf);
      for (int i = 0; i < pdf.getNumberOfPages(); i++) {
        BufferedImage page = rendering.renderImageWithDPI(i, 96);
        assertThat(page.getWidth()).isGreaterThan(700);
        assertThat(page.getHeight()).isGreaterThan(1000);
        if (directory != null) {
          Path output = Path.of(directory); Files.createDirectories(output);
          ImageIO.write(page, "png", output.resolve("original-case-report-page-" + (i + 1) + ".png").toFile());
        }
      }
      if (directory != null) Files.write(Path.of(directory).resolve("original-case-report.pdf"), bytes);
    }
  }

  @Test void summaryFitsTwoCompletedQuestionsKeepsExactEssentialsAndDeduplicatesSourceLabels() throws Exception {
    ObjectNode input = fixture(false);
    ((ObjectNode) input.path("scope")).put("reportMode", "SUMMARY");
    ((ObjectNode) input.path("management")).putNull("owner");
    ObjectNode first = (ObjectNode) input.path("investigations").path(0);
    String exact = "The original office record has native status 00; beneficiary credit remains unverified. ".repeat(6);
    ((ObjectNode) first.path("answer")).put("answer", exact);
    ObjectNode second = first.deepCopy().put("id", "JOB-ORIGINAL-SECOND-COMPLETED")
        .put("question", "Which original confirmation is still absent?").put("evidenceId", "EVIDENCE-ORIGINAL-2").put("evidenceVersion", 2);
    ((ArrayNode) input.path("investigations")).set(1, second);
    input.put("reportHash", "ff01".repeat(16));
    ObjectNode untouched = input.deepCopy();
    byte[] bytes = renderer.render(input);
    try (PDDocument pdf = Loader.loadPDF(bytes)) {
      String text = new PDFTextStripper().getText(pdf);
      String normalized = text.replaceAll("\\s+", " ");
      assertThat(pdf.getNumberOfPages()).isBetween(1, 3);
      assertThat(normalized).contains(REFERENCE, AMOUNT, "Unassigned", "HIGH / OPEN", "PENDING", "PAYMENT 1", "HOST 0", "HISTORY 0", "STATUS 0",
          "Which original confirmation is still absent?", "beneficiary credit remains unverified", "original-fixture.json", "DOC-ORIGINAL-1", "ff01".repeat(16));
      assertThat(normalized).contains(exact.strip()).doesNotContain("Original source text:", "OWNER_CHANGED", "original-fixture-model", "execution provenance", "Selected evidence appendix");
      assertThat(text.split("DOC-ORIGINAL-1", -1)).hasSize(2);
      PositionChecker positions = new PositionChecker(); positions.getText(pdf);
      assertThat(positions.outside).isEmpty();
      assertThat(positions.body.toString()).doesNotContain("\uFB00", "\uFB01", "\uFB03");
      String directory = System.getProperty("poi.reportPdfValidationDir");
      if (directory != null) {
        Path output = Path.of(directory); Files.createDirectories(output);
        Files.write(output.resolve("original-case-summary.pdf"), bytes);
        PDFRenderer rendering = new PDFRenderer(pdf);
        for (int i = 0; i < pdf.getNumberOfPages(); i++)
          ImageIO.write(rendering.renderImageWithDPI(i, 96), "png", output.resolve("original-case-summary-page-" + (i + 1) + ".png").toFile());
      }
    }
    assertThat(input).isEqualTo(untouched);
  }

  @Test void summaryMarksExactExcerptsAndFurtherUnknownsWithoutShowingUnrelatedHistory() throws Exception {
    ObjectNode input = fixture(false);
    ((ObjectNode) input.path("scope")).put("reportMode", "SUMMARY");
    ((ArrayNode) input.path("investigations")).remove(1);
    ObjectNode answer = (ObjectNode) input.path("investigations").path(0).path("answer");
    String full = "Exact original observation. ".repeat(65) + "OMITTED_ANSWER_TAIL";
    String unknown = "Unverified original fact. ".repeat(18) + "OMITTED_UNKNOWN_TAIL";
    answer.put("answer", full);
    answer.putArray("unknowns").add(unknown).add("SECOND_UNKNOWN_OMITTED").add("THIRD_UNKNOWN_OMITTED");
    answer.putArray("nextChecks").add("Inspect the exact original confirmation.").add("SECOND_CHECK_OMITTED");
    ObjectNode management = (ObjectNode) input.path("management");
    management.putArray("notes").addObject().put("text", "LATEST_NOTE_VISIBLE");
    ((ArrayNode) management.path("notes")).addObject().put("text", "EARLIER_NOTE_OMITTED");
    ArrayNode requests = management.putArray("evidenceRequests");
    for (int i = 1; i <= 3; i++) requests.addObject().put("status", "OPEN").put("title", "OPEN_REQUEST_" + i);
    requests.addObject().put("status", "CLOSED").put("title", "CLOSED_REQUEST_OMITTED");
    try (PDDocument pdf = Loader.loadPDF(renderer.render(input))) {
      String text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
      assertThat(text).contains(full.substring(0, 1400).strip(), unknown.substring(0, 350).strip(), "Excerpt:", "further character(s) omitted",
          "2 further item(s) omitted from unknown", "1 further item(s) omitted from next check", "LATEST_NOTE_VISIBLE", "1 earlier note(s) omitted",
          "OPEN_REQUEST_1", "OPEN_REQUEST_2", "1 further open request(s) omitted", "Full answers, limitations, sources and history");
      assertThat(text).doesNotContain("OMITTED_ANSWER_TAIL", "OMITTED_UNKNOWN_TAIL", "SECOND_UNKNOWN_OMITTED", "THIRD_UNKNOWN_OMITTED",
          "SECOND_CHECK_OMITTED", "EARLIER_NOTE_OMITTED", "OPEN_REQUEST_3", "CLOSED_REQUEST_OMITTED");
      assertThat(pdf.getNumberOfPages()).isLessThanOrEqualTo(3);
    }
  }

  @Test void summaryUsesOnlyDedicatedBoundReviewerConclusionAndOmitsEmptyManagementSections() throws Exception {
    ObjectNode input = fixture(false);
    ((ObjectNode) input.path("scope")).put("reportMode", "SUMMARY");
    ObjectNode management = (ObjectNode) input.path("management");
    management.putArray("notes"); management.putArray("evidenceRequests");
    management.putArray("reviewerConclusions").addObject().put("conclusion", "UNRELATED_REVIEW_MUST_NOT_APPEAR");
    try (PDDocument pdf = Loader.loadPDF(renderer.render(input))) {
      String text = new PDFTextStripper().getText(pdf);
      assertThat(text).contains("PENDING").doesNotContain("UNRELATED_REVIEW_MUST_NOT_APPEAR", "Current follow-up", "Latest note");
    }
    ObjectNode review = (ObjectNode) input.path("review"); review.put("status", "RECORDED");
    review.putObject("conclusion").put("conclusion", "BOUND_REVIEW_ONLY: obtain the original confirmation.")
        .put("createdByName", "Original Reviewer").put("createdAt", "2026-09-14T10:00:00Z");
    try (PDDocument pdf = Loader.loadPDF(renderer.render(input))) {
      String text = new PDFTextStripper().getText(pdf);
      assertThat(text).contains("RECORDED", "BOUND_REVIEW_ONLY", "Original Reviewer").doesNotContain("PENDING", "UNRELATED_REVIEW_MUST_NOT_APPEAR");
    }
  }

  @Test void summaryRejectsRawRowsAndMoreThanTwoQuestionsWithoutChangingLegacyDetailedMode() {
    ObjectNode raw = fixture(true); ((ObjectNode) raw.path("scope")).put("reportMode", "SUMMARY");
    assertThat(assertThrows(ApiException.class, () -> renderer.render(raw)).status).isEqualTo(422);
    ObjectNode many = fixture(false); ((ObjectNode) many.path("scope")).put("reportMode", "SUMMARY");
    ((ArrayNode) many.path("investigations")).add(many.path("investigations").path(0).deepCopy());
    assertThat(assertThrows(ApiException.class, () -> renderer.render(many)).getMessage()).contains("at most two");
  }

  @Test void summaryPrioritizesAndDeduplicatesDiscoveryConflictsBeforeGenericCautions() throws Exception {
    ObjectNode input = fixture(false);
    ((ObjectNode) input.path("scope")).put("reportMode", "SUMMARY");
    String amount = "PAYMENT amount differs from the saved discovery amount; both observations are preserved.";
    String utr = "PAYMENT UTR differs from the saved discovery UTR; verify the source observations.";
    String matching = "The original evidence contains multiple matching rows; inspect each source row.";
    ((ObjectNode) input.path("evidence")).putArray("warnings").add("GENERIC_STATUS_CAUTION_OMITTED").add("OTHER_CAUTION_OMITTED").add(amount);
    ((ObjectNode) input.path("investigations").path(0)).putArray("warnings").add(amount).add(utr).add(matching);
    ((ObjectNode) input.path("investigations").path(1)).putArray("warnings").add(utr).add(matching);
    try (PDDocument pdf = Loader.loadPDF(renderer.render(input))) {
      String text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
      assertThat(text).contains("Saved amount", "Saved UTR", "Source cautions", amount, utr, matching, "2 further source caution(s) omitted");
      assertThat(text).doesNotContain("GENERIC_STATUS_CAUTION_OMITTED", "OTHER_CAUTION_OMITTED", "recorded warning entries");
      assertThat(text.split(java.util.regex.Pattern.quote(amount), -1)).hasSize(2);
      assertThat(pdf.getNumberOfPages()).isLessThanOrEqualTo(3);
    }
  }

  private static class PositionChecker extends PDFTextStripper {
    final List<String> outside = new ArrayList<>();
    final StringBuilder body = new StringBuilder();
    PositionChecker() throws java.io.IOException { super(); }
    @Override protected void processTextPosition(TextPosition position) {
      if (position.getYDirAdj() > 46 && position.getYDirAdj() < 790) body.append(position.getUnicode());
      if (position.getXDirAdj() < 40 || position.getXDirAdj() + position.getWidthDirAdj() > 553
          || position.getYDirAdj() < 20 || position.getYDirAdj() > 825) outside.add(position.getUnicode());
      super.processTextPosition(position);
    }
  }
}
