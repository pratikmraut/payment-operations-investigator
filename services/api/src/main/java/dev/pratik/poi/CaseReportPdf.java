package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

/** Plain-text, offline presentation of the server's frozen report bundle. */
@Component
public final class CaseReportPdf {
  private static final int MAX_PAGES = 200;
  private static final Color INK = new Color(30, 45, 63);
  private static final Color MUTED = new Color(84, 99, 118);
  private static final Color BLUE = new Color(32, 87, 153);
  private static final Color RULE = new Color(214, 222, 232);
  private static final String LEGEND = "Reading exact values: (not supplied) means a missing field; (null) is an explicit null; "
      + "(empty string) is supplied blank text. These markers are presentation labels. Unsupported glyphs and control characters "
      + "are shown as [U+HEX] escapes; literal supported characters are retained. Source line breaks and layout wrapping are not transaction events. "
      + "Dates and amounts are source strings; no timezone or monetary conversion is applied.";

  public byte[] render(ObjectNode bundle) {
    if (bundle == null || !"payment-case-report-v1".equals(bundle.path("schemaVersion").asText())
        || !bundle.path("case").isObject())
      throw new ApiException(422, "CASE_REPORT_INVALID", "A valid frozen payment case report bundle is required.");
    boolean summary = "SUMMARY".equals(bundle.path("scope").path("reportMode").asText());
    if (summary && (bundle.path("scope").path("includeEvidenceRows").asBoolean()
        || bundle.path("investigations").size() > 2))
      throw new ApiException(422, "CASE_REPORT_INVALID", "A case summary supports at most two investigations and no raw evidence appendix. Use the detailed report for a larger selection.");
    try (PDDocument document = new PDDocument();
         InputStream fontStream = CaseReportPdf.class.getResourceAsStream("/fonts/DejaVuSans.ttf")) {
      if (fontStream == null) throw new IOException("The bundled report font is unavailable.");
      try (RandomAccessReadBuffer fontBuffer = new RandomAccessReadBuffer(fontStream);
           TrueTypeFont trueTypeFont = new TTFParser().parse(fontBuffer)) {
        // Disable substitution before PDFBox initializes its cmap; copied source values must stay character-exact.
        trueTypeFont.setEnableGsub(false);
        PDType0Font font = PDType0Font.load(document, trueTypeFont, true);
        PDDocumentInformation information = new PDDocumentInformation();
        information.setTitle("Payment case investigation report");
        information.setAuthor("Payment Operations Investigator");
        information.setCreator("Payment Operations Investigator - offline case report renderer");
        document.setDocumentInformation(information);
        try (Layout layout = new Layout(document, font, summary ? bundle : null)) {
          if (summary) writeSummary(layout, bundle);
          else writeReport(layout, bundle);
          layout.finish();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.save(output);
        return output.toByteArray();
      }
    } catch (IOException failure) {
      throw new ApiException(500, "CASE_REPORT_RENDER_FAILED", "The PDF report could not be rendered. Retry or contact the local administrator.");
    }
  }

  private static void writeSummary(Layout out, ObjectNode bundle) throws IOException {
    JsonNode payment = bundle.path("case"), management = bundle.path("management"), evidence = bundle.path("evidence");
    out.text("Payment case summary", 21, INK, 0);
    out.text("Generated " + summaryValue(bundle.get("generatedAt")) + " · Prepared by "
        + summaryValue(bundle.path("generatedBy").get("name")), 8, MUTED, 0);
    out.text("Private / read-only · AI output is experimental; verify it against the source records.", 8, MUTED, 0);
    out.compactHeading("Case details");
    if (payment.hasNonNull("caseNumber")) out.pair("Case number", payment.get("caseNumber"));
    out.pair("Payment reference", payment.get("reference"));
    out.pair("Saved UTR", summaryValue(payment.get("utr")));
    out.compactPair("Bank / branch", summaryValue(payment.get("orgBank")) + " / " + summaryValue(payment.get("orgBranch")),
        "Saved amount", summaryValue(payment.get("amount")) + " " + summaryValue(payment.get("currency")));
    JsonNode owner = management.has("owner") ? management.get("owner") : payment.get("owner");
    if (owner != null && owner.isObject()) owner = owner.get("name");
    out.compactPair("Owner", owner == null || owner.isNull() ? "Unassigned" : summaryValue(owner),
        "Priority / status", summaryValue(management.has("priority") ? management.get("priority") : payment.get("priority"))
            + " / " + summaryValue(payment.get("status")));
    summaryExcerpt(out, "Reason", payment.get("reason"), 350);

    out.compactHeading("Reviewer conclusion");
    JsonNode review = bundle.path("review"), conclusion = review.path("conclusion");
    if ("RECORDED".equals(review.path("status").asText()) && conclusion.isObject()) {
      out.text("RECORDED · " + summaryValue(conclusion.get("createdByName")) + " · "
          + summaryValue(conclusion.get("createdAt")), 8, BLUE, 0);
      summaryExcerpt(out, "Recorded conclusion", conclusion.get("conclusion"), 700);
    } else out.text("PENDING · No matching reviewer conclusion is recorded for this evidence and selected investigation set.", 9, INK, 0);

    out.compactHeading("Evidence selected");
    if (evidence.isObject()) {
      out.text("Version " + summaryValue(evidence.get("version")) + " · " + summaryValue(evidence.get("sourceKind"))
          + " · Source timezone: " + summaryValue(evidence.get("sourceTimezone")), 9, INK, 0);
      List<String> counts = new ArrayList<>();
      for (String group : List.of("PAYMENT", "HOST", "HISTORY", "STATUS"))
        counts.add(group + " " + summaryValue(evidence.path("coverage").path(group).get("rowCount")));
      out.text("Supplied rows: " + String.join(" · ", counts), 8.5f, INK, 0);
      out.text("Row counts do not establish query completion, beneficiary credit or settlement.", 8, MUTED, 0);
    } else out.text("No evidence version selected.", 9, INK, 0);

    JsonNode jobs = bundle.path("investigations");
    List<String> cautions = new ArrayList<>();
    for (JsonNode warning : evidence.path("warnings"))
      if (warning.isTextual() && !warning.asText().isBlank() && !cautions.contains(warning.asText())) cautions.add(warning.asText());
    for (JsonNode job : jobs) for (JsonNode warning : job.path("warnings"))
      if (warning.isTextual() && !warning.asText().isBlank() && !cautions.contains(warning.asText())) cautions.add(warning.asText());
    cautions.sort(java.util.Comparator.comparingInt(value ->
        value.contains("differs from the saved discovery") || value.contains("multiple matching rows") ? 0 : 1));
    if (!cautions.isEmpty()) {
      out.compactHeading("Source cautions");
      for (int index = 0; index < Math.min(3, cautions.size()); index++) {
        String value = cautions.get(index);
        out.text(prefix(value, 220), 8, INK, 0);
        if (value.codePointCount(0, value.length()) > 220)
          out.text("Warning excerpt: further text omitted; see the detailed report.", 7.5f, MUTED, 0);
      }
      if (cautions.size() > 3) out.text((cautions.size() - 3) + " further source caution(s) omitted; see the detailed report.", 7.5f, MUTED, 0);
    }
    List<JsonNode> sources = new ArrayList<>();
    out.compactHeading("Selected investigation" + (jobs.size() == 1 ? "" : "s"));
    if (!jobs.isArray() || jobs.isEmpty()) out.text("No investigation selected.", 9, INK, 0);
    int number = 0;
    for (JsonNode job : jobs) {
      out.ensure(70);
      out.text("Question " + (++number) + " · " + summaryValue(job.get("status")) + " · Evidence v"
          + summaryValue(job.get("evidenceVersion")), 9, BLUE, 0);
      if (!job.path("evidenceId").asText().equals(evidence.path("id").asText()))
        out.text("This answer uses a different evidence version from the selection above.", 8, MUTED, 0);
      summaryExcerpt(out, "Question", job.get("question"), 400);
      JsonNode answer = job.path("answer");
      if (!answer.isObject()) {
        out.text("No completed answer is stored for this investigation.", 9, INK, 0);
        if (job.path("error").isObject()) summaryExcerpt(out, "Recorded failure", job.path("error").get("message"), 200);
        continue;
      }
      summaryExcerpt(out, "Saved model answer", answer.get("answer"), 1400);
      List<String> cited = new ArrayList<>();
      int omitted = 0;
      for (JsonNode citation : answer.path("citations")) {
        int index = sources.indexOf(citation);
        if (index < 0 && sources.size() < 8) { sources.add(citation); index = sources.size() - 1; }
        if (index < 0) omitted++;
        else if (!cited.contains("S" + (index + 1))) cited.add("S" + (index + 1));
      }
      out.text("Cited sources: " + (cited.isEmpty() ? "None shown" : String.join(", ", cited)), 8, MUTED, 0);
      if (omitted > 0) out.text(omitted + " further cited source label(s) omitted; see the detailed report.", 8, MUTED, 0);
      summaryFirst(out, "Unknown / unanswered point", answer.path("unknowns"));
      summaryFirst(out, "Next check", answer.path("nextChecks"));
    }

    JsonNode notes = management.path("notes");
    List<JsonNode> openRequests = new ArrayList<>();
    for (JsonNode request : management.path("evidenceRequests"))
      if ("OPEN".equals(request.path("status").asText())) openRequests.add(request);
    if (!notes.isEmpty() || !openRequests.isEmpty()) {
      out.compactHeading("Current follow-up");
      if (notes.isArray() && !notes.isEmpty()) {
        // The authoritative management view orders notes newest first by event version.
        summaryExcerpt(out, "Latest note", notes.get(0).get("text"), 300);
        if (notes.size() > 1) out.text((notes.size() - 1) + " earlier note(s) omitted.", 8, MUTED, 0);
      }
      for (int index = 0; index < Math.min(2, openRequests.size()); index++) {
        JsonNode request = openRequests.get(index);
        summaryExcerpt(out, "Open request " + (index + 1), request.get("title"), 160);
        if (request.hasNonNull("dueDate")) out.text("Due: " + summaryValue(request.get("dueDate")), 8, MUTED, 0);
      }
      if (openRequests.size() > 2) out.text((openRequests.size() - 2) + " further open request(s) omitted.", 8, MUTED, 0);
    }
    if (!sources.isEmpty()) {
      out.compactHeading("Source references");
      for (int index = 0; index < sources.size(); index++) {
        JsonNode source = sources.get(index), provenance = source.path("source");
        String label = summaryValue(source.get("title")) + " · " + summaryValue(provenance.get("file"));
        for (String key : List.of("sheet", "range", "locator"))
          if (provenance.hasNonNull(key) && !provenance.path(key).asText().isEmpty()) label += " · " + provenance.path(key).asText();
        out.ensure(32);
        out.text("S" + (index + 1) + " · " + summaryValue(source.get("id")), 8, BLUE, 0);
        out.text(prefix(label, 180) + (label.codePointCount(0, label.length()) > 180 ? " [label excerpt]" : ""), 7.5f, MUTED, 0);
      }
    }
    out.compactHeading("Summary scope");
    out.text("Saved wording is quoted, not rewritten. Excerpts are identified; the reviewer record applies to the full selected investigation set, not only these excerpts. "
        + "Full answers, limitations, sources and history are available in the detailed report; marked excerpts omit text. "
        + "Raw rows can be included there. The report fingerprint identifies this frozen bundle; it is not a digital signature.", 7.5f, MUTED, 0);
    out.text("Source amounts and dates are unchanged. Unsupported glyphs appear as [U+HEX] escapes.", 7.5f, MUTED, 0);
  }

  private static String summaryValue(JsonNode node) {
    return node == null || node.isMissingNode() || node.isNull() ? "Not supplied" : Layout.value(node);
  }

  private static String prefix(String value, int maximum) {
    return value.codePointCount(0, value.length()) <= maximum ? value : value.substring(0, value.offsetByCodePoints(0, maximum));
  }

  private static void summaryExcerpt(Layout out, String label, JsonNode node, int maximum) throws IOException {
    String value = summaryValue(node);
    out.ensure(32);
    out.text(label, 8, MUTED, 0);
    out.text(prefix(value, maximum), 9, INK, 0);
    int omitted = value.codePointCount(0, value.length()) - maximum;
    if (omitted > 0) out.text("Excerpt: " + omitted + " further character(s) omitted. Read the full text in the detailed report.", 7.5f, MUTED, 0);
  }

  private static void summaryFirst(Layout out, String label, JsonNode entries) throws IOException {
    if (entries.isArray() && !entries.isEmpty()) {
      summaryExcerpt(out, label, entries.get(0), 350);
      if (entries.size() > 1) out.text((entries.size() - 1) + " further item(s) omitted from " + label.toLowerCase(java.util.Locale.ROOT)
          + "; see the detailed report.", 7.5f, MUTED, 0);
    } else out.text(label + ": None recorded.", 8, MUTED, 0);
  }

  private static void writeReport(Layout out, ObjectNode bundle) throws IOException {
    out.title("Payment case report", "Evidence, investigation and reviewer record");
    out.notice("PRIVATE CASE REPORT", "Read-only record. This report does not execute or authorize a payment operation.");
    out.pair("Generated at", bundle.get("generatedAt"));
    out.pair("Prepared by", bundle.path("generatedBy").get("name"));

    JsonNode payment = bundle.path("case");
    JsonNode management = bundle.path("management");
    out.section("01 / Payment overview");
    if (payment.hasNonNull("caseNumber")) out.pair("Case number", payment.get("caseNumber"));
    out.pair("Payment reference", payment.get("reference"));
    out.pair("UTR", payment.get("utr"));
    out.pair("Bank / branch", Layout.value(payment.get("orgBank")) + " / " + Layout.value(payment.get("orgBranch")));
    out.pair("Source amount", payment.get("amount"));
    out.pair("Source currency", payment.get("currency"));
    out.pair("Case status", payment.get("status"));
    JsonNode owner = management.has("owner") ? management.get("owner") : payment.get("owner");
    out.pair("Case owner", owner != null && owner.isObject() ? owner.get("name") : owner);
    out.pair("Priority", management.has("priority") ? management.get("priority") : payment.get("priority"));
    out.field("Investigation reason", payment.get("reason"));

    out.section("02 / Reviewer conclusion");
    JsonNode review = bundle.path("review");
    if ("RECORDED".equals(review.path("status").asText())) {
      out.notice("RECORDED REVIEW", "The recorded reviewer conclusion is bound to the report's selected evidence and investigation set.");
      out.field("Reviewer conclusion", review.path("conclusion").get("conclusion"), 11);
      out.pair("Recorded by", review.path("conclusion").get("createdByName"));
      out.pair("Recorded at", review.path("conclusion").get("createdAt"));
    } else {
      out.notice("REVIEW PENDING", "No matching recorded reviewer conclusion is included for this selected evidence and investigation set.");
    }
    out.text("Experimental AI output: verify field values and conclusions against the cited source records. Citation membership alone does not establish factual accuracy.", 9, MUTED, 0);

    out.section("03 / Case management");
    out.text("Case notes, evidence requests, reviewer records and audit entries are retained as recorded. Their text is not a banking command.", 9, MUTED, 0);
    writeManagement(out, management);
    out.subheading("Evidence and investigation activity");
    JsonNode caseActivity = bundle.get("caseActivity");
    if (caseActivity == null || !caseActivity.isArray() || caseActivity.isEmpty()) out.text(Layout.value(caseActivity), 9, MUTED, 0);
    else for (JsonNode event : caseActivity) {
      out.pair("Activity / at", Layout.value(event.get("action")) + " / " + Layout.value(event.get("occurredAt")));
      out.pair("Actor", event.has("actorName") ? event.get("actorName") : event.get("actor"));
      out.field("Activity detail", event.get("detail"));
    }

    out.section("04 / Selected evidence version");
    JsonNode evidence = bundle.get("evidence");
    if (evidence == null || evidence.isNull()) {
      out.field("Selected evidence", evidence);
      out.text("No selected evidence version is included. Individual investigations below retain their own evidence identifiers.", 9, MUTED, 0);
    } else {
      out.pair("Selected version", evidence.get("version"));
      out.pair("Evidence source", evidence.get("sourceKind"));
      out.pair("Saved at", evidence.get("createdAt"));
      out.pair("Source timezone", evidence.has("sourceTimezone") ? evidence.get("sourceTimezone") : evidence.path("payload").get("sourceTimezone"));
      for (String group : List.of("PAYMENT", "HOST", "HISTORY", "STATUS")) {
        JsonNode coverage = evidence.path("coverage").path(group);
        out.pair(group + " rows / fetch", Layout.value(coverage.get("rowCount")) + " / " + Layout.value(coverage.get("completion")));
      }
      out.list("Source limitations", evidence.get("warnings"));
      out.text("Coverage records supplied rows and query metadata. It does not establish source completeness, beneficiary credit or settlement.", 9, MUTED, 0);
      out.text(bundle.path("scope").path("includeEvidenceRows").asBoolean()
          ? "The selected version's raw source rows are included in the evidence appendix."
          : "Raw selected evidence rows were omitted by report scope. Complete cited source content remains included with each answered investigation.", 9, MUTED, 0);
    }
    out.list("Report limitations", bundle.get("warnings"));

    out.section("05 / Selected investigations");
    JsonNode jobs = bundle.get("investigations");
    if (jobs == null || jobs.isNull() || !jobs.isArray() || jobs.isEmpty()) {
      out.field("Investigations", jobs);
    } else {
      int number = 0;
      for (JsonNode job : jobs) writeInvestigation(out, job, ++number);
    }

    out.section("06 / Complete cited source appendix");
    out.text("Sources are grouped under the question that cited them. Each source is the original cited document from that answer, including answers based on an earlier evidence version.", 9, MUTED, 0);
    if (jobs != null && jobs.isArray() && !jobs.isEmpty()) {
      int number = 0;
      for (JsonNode job : jobs) writeCitations(out, job, ++number);
    } else out.text("No investigation sources are included in this report scope.", 9, MUTED, 0);

    if (bundle.path("scope").path("includeEvidenceRows").asBoolean()) {
      out.section("07 / Selected evidence appendix");
      if (evidence == null || evidence.isNull() || !evidence.has("payload")) {
        out.field("Raw evidence payload", evidence == null ? null : evidence.get("payload"));
      } else {
        out.text("Exact native fields from the selected immutable version. Blank values, unknown values and missing source rows remain distinct.", 9, MUTED, 0);
        JsonNode displayedPayload = evidence.get("payload");
        if (evidence instanceof ObjectNode snapshot && snapshot.has("upstream")) {
          CaseEvidenceService.verifyUpstream(new com.fasterxml.jackson.databind.ObjectMapper(), snapshot);
          ObjectNode restored = ((ObjectNode) displayedPayload).deepCopy();
          for (String group : CaseEvidenceSchema.COLUMNS.keySet()) {
            var rows = (com.fasterxml.jackson.databind.node.ArrayNode) restored.path("sections").path(group).path("rows");
            for (int index = 0; index < rows.size(); index++)
              rows.set(index, FlexcubeEvidenceAdapter.sourceRow(snapshot, group, index + 1, (ObjectNode) rows.get(index)));
          }
          displayedPayload = restored;
        }
        out.tree("Evidence payload", displayedPayload, 0);
      }
    }
    out.section("08 / Provenance and report integrity");
    out.field("Report ID", bundle.get("reportId"));
    out.field("Report fingerprint", bundle.get("reportHash"));
    out.text("The fingerprint identifies the frozen report bundle. It is not a digital signature or independent verification of the source data. All report content is passive text; external resources are not loaded.", 9, MUTED, 0);
    out.text(LEGEND, 8, MUTED, 0);
    out.tree("Prepared by / identity", bundle.get("generatedBy"), 0);
    out.tree("Report scope", bundle.get("scope"), 0);
    out.subheading("Payment and management provenance");
    out.fields(payment, Set.of("reference", "utr", "orgBank", "orgBranch", "amount", "currency", "status", "priority", "reason"), 0);
    out.tree("Case management records", bundle.get("management"), 0);
    out.tree("Selected case activity provenance", bundle.get("caseActivity"), 0);
    out.tree("Bound reviewer record", bundle.get("review"), 0);
    if (evidence != null && evidence.isObject()) {
      out.subheading("Selected evidence provenance");
      out.fields(evidence, Set.of("payload", "coverage", "warnings", "upstream"), 0);
      if (evidence.has("upstream")) {
        out.pair("API receipt time", evidence.path("upstream").get("receivedAt"));
        out.text("The original API response is preserved in the frozen report snapshot. Native source rows retain original null values; receipt time does not establish source query time or completion.", 8, MUTED, 0);
      }
    }
    if (jobs != null && jobs.isArray()) {
      int number = 0;
      for (JsonNode job : jobs) {
        out.subheading("Question " + (++number) + " / execution provenance");
        out.fields(job, Set.of("question", "answer", "documents"), 0);
        JsonNode answer = job.get("answer");
        if (answer != null && answer.isObject()) out.fields(answer, Set.of("question", "answer", "claims", "unknowns", "nextChecks", "citations"), 0);
      }
    }
  }

  private static void writeManagement(Layout out, JsonNode management) throws IOException {
    out.subheading("Notes");
    JsonNode notes = management.get("notes");
    if (notes == null || !notes.isArray() || notes.isEmpty()) out.text(Layout.value(notes), 9, MUTED, 0);
    else for (JsonNode note : notes) {
      out.field("Note", note.get("text"));
      out.pair("Recorded by / at", Layout.value(note.get("createdByName")) + " / " + Layout.value(note.get("createdAt")));
    }
    out.subheading("Evidence requests");
    JsonNode requests = management.get("evidenceRequests");
    if (requests == null || !requests.isArray() || requests.isEmpty()) out.text(Layout.value(requests), 9, MUTED, 0);
    else for (JsonNode request : requests) {
      out.field("Requested evidence", request.get("title"), 10);
      out.pair("Request status", request.get("status"));
      out.pair("Due date", request.get("dueDate"));
      out.field("Request detail", request.get("detail"));
      if (request.path("updates").isArray()) for (JsonNode update : request.path("updates")) {
        out.pair("Updated status / at", Layout.value(update.get("status")) + " / " + Layout.value(update.get("createdAt")));
        out.field("Update note", update.get("note"));
      }
    }
    out.subheading("Activity");
    JsonNode audit = management.get("audit");
    if (audit == null || !audit.isArray() || audit.isEmpty()) out.text(Layout.value(audit), 9, MUTED, 0);
    else for (JsonNode event : audit) {
      out.pair("Activity / at", Layout.value(event.get("action")) + " / " + Layout.value(event.get("occurredAt")));
      out.pair("Actor", event.has("actorName") ? event.get("actorName") : event.get("actor"));
      out.field("Activity detail", event.get("detail"));
    }
  }

  private static void writeInvestigation(Layout out, JsonNode job, int number) throws IOException {
    out.subheading("Question " + number);
    out.field("Question", job.get("question"), 11);
    out.pair("Saved status", job.get("status"));
    out.pair("Answer's evidence version", job.get("evidenceVersion"));
    out.pair("Requested at", job.get("createdAt"));
    out.text("This question's original evidence version may differ from the selected evidence appendix. Exact version identifiers and fingerprints are retained in the provenance appendix.", 8, MUTED, 0);
    JsonNode answer = job.get("answer");
    if (answer == null || answer.isNull()) {
      out.field("Stored answer", answer);
      if (job.has("error")) out.tree("Recorded failure", job.get("error"), 0);
      out.text("No completed answer is included for this investigation. Its recorded status and error remain authoritative.", 9, MUTED, 0);
      return;
    }
    out.subheading("Model-generated answer / Question " + number);
    out.text("Experimental answer - review claims against their cited records.", 9, MUTED, 0);
    JsonNode claims = answer.path("claims");
    StringBuilder joined = new StringBuilder();
    if (claims.isArray()) for (JsonNode claim : claims) {
      if (!joined.isEmpty()) joined.append("\n\n");
      joined.append(claim.path("text").asText());
    }
    boolean sameParagraphs = "joined-model-claims".equals(answer.path("answerComposition").asText())
        && answer.path("answer").isTextual() && answer.path("answer").textValue().equals(joined.toString());
    if (!sameParagraphs) out.field("Stored answer text", answer.get("answer"));
    if (claims.isArray() && !claims.isEmpty()) {
      int index = 0;
      for (JsonNode claim : claims) {
        out.field("Claim " + (++index), claim.get("text"));
        out.list("Cited source IDs", claim.get("evidenceIds"));
        out.fields(claim, Set.of("text", "evidenceIds"), 0);
      }
    } else out.field("Cited claims", answer.get("claims"));
    out.list("Unknowns / unanswered points", answer.get("unknowns"));
    out.list("Next checks", answer.get("nextChecks"));
    out.pair("Model", answer.path("model").get("name"));
    out.pair("Actual model calls", answer.path("model").get("actualCalls"));
    out.pair("Duration (milliseconds)", answer.path("model").get("durationMs"));
  }

  private static void writeCitations(Layout out, JsonNode job, int number) throws IOException {
    out.subheading("Question " + number + " / original cited sources");
    out.field("Question", job.get("question"));
    out.pair("Original evidence version", job.get("evidenceVersion"));
    JsonNode answer = job.path("answer");
    JsonNode citations = answer.get("citations");
    if (citations == null || !citations.isArray() || citations.isEmpty()) {
      out.field("Citations", citations);
      return;
    }
    int citationNumber = 0;
    for (JsonNode citation : citations) {
      out.subheading("Question " + number + " / Source " + (++citationNumber));
      out.fields(citation, Set.of("content"), 0);
      out.field("Exact cited content", citation.get("content"));
    }
  }

  private static final class Layout implements AutoCloseable {
    private static final float WIDTH = PDRectangle.A4.getWidth();
    private static final float HEIGHT = PDRectangle.A4.getHeight();
    private static final float LEFT = 44;
    private static final float RIGHT = WIDTH - 44;
    private static final float TOP = HEIGHT - 75;
    private static final float BOTTOM = 55;
    private final PDDocument document;
    private final PDType0Font font;
    private final ObjectNode summaryBundle;
    private final Map<Integer, String> characterCache = new HashMap<>();
    private final Map<Integer, Float> widthCache = new HashMap<>();
    private PDPageContentStream stream;
    private float y;

    Layout(PDDocument document, PDType0Font font, ObjectNode summaryBundle) throws IOException {
      this.document = document;
      this.font = font;
      this.summaryBundle = summaryBundle;
      newPage();
    }
    void newPage() throws IOException {
      if (summaryBundle != null && document.getNumberOfPages() >= 3)
        throw new ApiException(413, "CASE_REPORT_TOO_LARGE", "This case summary exceeds 3 pages. Select fewer investigations or use the detailed report.");
      if (document.getNumberOfPages() >= MAX_PAGES)
        throw new ApiException(413, "CASE_REPORT_TOO_LARGE", "This report exceeds 200 pages. Choose fewer investigations or omit the raw evidence appendix.");
      close();
      PDPage page = new PDPage(PDRectangle.A4);
      document.addPage(page);
      stream = new PDPageContentStream(document, page);
      draw("PAYMENT OPERATIONS", LEFT, HEIGHT - 33, 9, BLUE);
      String label = summaryBundle == null ? "PRIVATE / CASE REPORT" : "PRIVATE / CASE SUMMARY";
      draw(label, RIGHT - font.getStringWidth(label) * 8 / 1000, HEIGHT - 33, 8, MUTED);
      stream.setStrokingColor(RULE);
      stream.setLineWidth(0.7f);
      stream.moveTo(LEFT, HEIGHT - 46);
      stream.lineTo(RIGHT, HEIGHT - 46);
      stream.stroke();
      y = TOP;
    }
    void ensure(float points) throws IOException { if (y - points < (summaryBundle == null ? BOTTOM : 65)) newPage(); }
    void compactHeading(String title) throws IOException {
      ensure(45); y -= 7; text(title, 11, BLUE, 0); y -= 2;
    }
    void compactPair(String firstLabel, String firstValue, String secondLabel, String secondValue) throws IOException {
      ensure(32);
      draw(firstLabel, LEFT, y, 8, MUTED); draw(secondLabel, LEFT + 260, y, 8, MUTED); y -= 12;
      List<String> first = wrap(safe(firstValue), 9, 242), second = wrap(safe(secondValue), 9, RIGHT - LEFT - 260);
      for (int i = 0; i < Math.max(first.size(), second.size()); i++) {
        ensure(13);
        if (i < first.size()) draw(first.get(i), LEFT, y, 9, INK);
        if (i < second.size()) draw(second.get(i), LEFT + 260, y, 9, INK);
        y -= 13;
      }
      y -= 3;
    }
    void title(String title, String subtitle) throws IOException {
      text(title, 24, INK, 0);
      text(subtitle, 11, MUTED, 0);
      y -= 9;
    }
    void section(String title) throws IOException {
      ensure(92);
      y -= 13;
      text(title, 14, BLUE, 0);
      stream.setStrokingColor(RULE);
      stream.moveTo(LEFT, y + 2);
      stream.lineTo(RIGHT, y + 2);
      stream.stroke();
      y -= 10;
    }
    void subheading(String title) throws IOException {
      ensure(72);
      y -= 8;
      text(title, 11, BLUE, 0);
    }
    void notice(String label, String message) throws IOException {
      ensure(48);
      text(label, 9, BLUE, 0);
      text(message, 9, MUTED, 0);
      y -= 5;
    }
    void field(String label, JsonNode value) throws IOException { field(label, value, 9.5f); }
    void pair(String label, JsonNode value) throws IOException { pair(label, value(value)); }
    void pair(String label, String value) throws IOException {
      List<String> labels = wrap(safe(label), 8, 136);
      List<String> values = wrap(safe(value), 9.5f, RIGHT - LEFT - 145);
      int lines = Math.max(labels.size(), values.size());
      for (int line = 0; line < lines; line++) {
        ensure(14);
        if (line < labels.size()) draw(labels.get(line), LEFT, y, 8, MUTED);
        if (line < values.size()) draw(values.get(line), LEFT + 145, y, 9.5f, INK);
        y -= 14;
      }
      y -= 4;
    }
    void list(String label, JsonNode values) throws IOException {
      ensure(45);
      text(label, 9, BLUE, 0);
      if (values == null || !values.isArray() || values.isEmpty()) text(value(values), 9, MUTED, 0);
      else {
        int index = 0;
        for (JsonNode entry : values) text((++index) + ". " + value(entry), 9.5f, INK, 0);
      }
      y -= 4;
    }
    void field(String label, JsonNode value, float size) throws IOException {
      ensure(22 + size * 1.42f);
      text(label, 8, MUTED, 0);
      text(value(value), size, INK, 0);
      y -= 5;
    }
    void fields(JsonNode object, Set<String> excluded, int depth) throws IOException {
      if (!object.isObject()) { text(value(object), 9.5f, INK, depth * 8); return; }
      Iterator<Map.Entry<String, JsonNode>> iterator = object.fields();
      while (iterator.hasNext()) {
        Map.Entry<String, JsonNode> field = iterator.next();
        if (!excluded.contains(field.getKey())) tree(field.getKey(), field.getValue(), depth);
      }
    }
    void tree(String label, JsonNode node, int depth) throws IOException {
      int inset = Math.min(depth, 5) * 8;
      if (node != null && (node.isArray() || node.isObject())) {
        ensure(55);
        text(label, 9, BLUE, inset);
        if (node.isEmpty()) {
          text(node.isArray() ? "(empty list)" : "(empty object)", 9, MUTED, inset);
          y -= 4;
        } else if (node.isArray()) {
          int index = 0;
          for (JsonNode child : node) tree("Item " + (++index), child, depth + 1);
        } else fields(node, Set.of(), depth + 1);
      } else {
        ensure(31);
        text(label, 8, MUTED, inset);
        text(value(node), 9.5f, INK, inset);
        y -= 4;
      }
    }
    private static String value(JsonNode node) {
      if (node == null || node.isMissingNode()) return "(not supplied)";
      if (node.isNull()) return "(null)";
      if (node.isTextual()) return node.textValue().isEmpty() ? "(empty string)" : node.textValue();
      if (node.isArray() && node.isEmpty()) return "(empty list)";
      if (node.isObject() && node.isEmpty()) return "(empty object)";
      return node.toString();
    }
    private String safe(String input) throws IOException {
      StringBuilder result = new StringBuilder();
      for (int offset = 0; offset < input.length();) {
        int point = input.codePointAt(offset);
        offset += Character.charCount(point);
        if (point == '\n') { result.append('\n'); continue; }
        String encoded = characterCache.get(point);
        if (encoded == null) {
          encoded = new String(Character.toChars(point));
          boolean escape = Character.isISOControl(point) || Character.getType(point) == Character.FORMAT
              || point >= 0xD800 && point <= 0xDFFF;
          if (!escape) {
            try { font.encode(encoded); } catch (IllegalArgumentException unsupported) { escape = true; }
          }
          if (escape) encoded = String.format("[U+%04X]", point);
          characterCache.put(point, encoded);
        }
        result.append(encoded);
      }
      return result.toString();
    }
    private float width(String input, float size) throws IOException {
      float units = 0;
      for (int offset = 0; offset < input.length();) {
        int point = input.codePointAt(offset);
        offset += Character.charCount(point);
        Float cached = widthCache.get(point);
        if (cached == null) {
          cached = font.getStringWidth(new String(Character.toChars(point)));
          widthCache.put(point, cached);
        }
        units += cached;
      }
      return units * size / 1000;
    }
    private List<String> wrap(String input, float size, float available) throws IOException {
      List<String> lines = new ArrayList<>();
      for (String paragraph : input.split("\n", -1)) {
        StringBuilder line = new StringBuilder();
        float used = 0;
        for (int offset = 0; offset < paragraph.length();) {
          int point = paragraph.codePointAt(offset);
          offset += Character.charCount(point);
          String character = new String(Character.toChars(point));
          float next = width(character, size);
          while (!line.isEmpty() && used + next > available) {
            int cut = -1;
            for (int i = line.length() - 1; i >= 0; i--)
              if (Character.isWhitespace(line.charAt(i))) { cut = i + 1; break; }
            if (cut <= 0) cut = line.length();
            lines.add(line.substring(0, cut));
            line.delete(0, cut);
            used = width(line.toString(), size);
          }
          line.append(character);
          used += next;
        }
        lines.add(line.toString());
      }
      return lines;
    }
    void text(String input, float size, Color color, int inset) throws IOException {
      float leading = size * (summaryBundle == null ? 1.42f : 1.30f);
      for (String line : wrap(safe(input), size, RIGHT - LEFT - inset)) {
        ensure(leading);
        draw(line, LEFT + inset, y, size, color);
        y -= leading;
      }
      y -= summaryBundle == null ? 3 : 2;
    }
    void draw(String text, float x, float baseline, float size, Color color) throws IOException {
      stream.beginText();
      stream.setFont(font, size);
      stream.setNonStrokingColor(color);
      stream.newLineAtOffset(x, baseline);
      stream.showText(text);
      stream.endText();
    }
    void finish() throws IOException {
      close();
      int total = document.getNumberOfPages();
      for (int i = 0; i < total; i++) {
        stream = new PDPageContentStream(document, document.getPage(i), PDPageContentStream.AppendMode.APPEND, true, true);
        stream.setStrokingColor(RULE);
        stream.moveTo(LEFT, summaryBundle == null ? 44 : 55);
        stream.lineTo(RIGHT, summaryBundle == null ? 44 : 55);
        stream.stroke();
        if (summaryBundle != null) {
          draw(safe("Report: " + summaryValue(summaryBundle.get("reportId"))), LEFT, 44, 6.5f, MUTED);
          draw(safe("Fingerprint: " + summaryValue(summaryBundle.get("reportHash"))), LEFT, 34, 6.5f, MUTED);
        }
        draw("Private evidence / read-only report", LEFT, summaryBundle == null ? 30 : 21, 7.5f, MUTED);
        String pages = "Page " + (i + 1) + " of " + total;
        draw(pages, RIGHT - width(pages, 7.5f), summaryBundle == null ? 30 : 21, 7.5f, MUTED);
        close();
      }
    }
    @Override public void close() throws IOException {
      if (stream != null) { stream.close(); stream = null; }
    }
  }
}
