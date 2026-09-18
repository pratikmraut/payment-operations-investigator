package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Deterministic selection of whole original row documents, never row summaries. */
final class CaseEvidenceSelection {
  static final String ID = "CASE-EVIDENCE-SELECTION";
  static final String METHOD = "question-ranked-whole-rows-v1";
  // A deliberately conservative preliminary envelope. The worker checks its
  // actual prompt, grammar, output reserve and configured context independently.
  static final int SOURCE_BYTES = 20_000;
  private static final Pattern ROW = Pattern.compile("(PAYMENT|HOST|HISTORY|STATUS)-ROW-([1-9][0-9]*)");
  private static final Pattern MENTION = Pattern.compile("(?i)(?<![A-Za-z0-9_-])(?:PAYMENT|HOST|HISTORY|STATUS)-ROW-[0-9]+(?![A-Za-z0-9_-])");
  private final ObjectMapper mapper;

  CaseEvidenceSelection(ObjectMapper mapper) { this.mapper = mapper; }

  record Selection(ArrayNode documents, ObjectNode metadata) { }

  Selection select(ArrayNode sources, ArrayNode reservedGuidance, String question) {
    return select(sources, reservedGuidance, question, SOURCE_BYTES);
  }

  Selection select(ArrayNode sources, ArrayNode reservedGuidance, String question, int sourceBytes) {
    if (sourceBytes < 1 || sourceBytes > 50_000 || question == null || question.isBlank() || question.length() > 2000) throw tooLarge();
    LinkedHashMap<String, ObjectNode> rows = new LinkedHashMap<>();
    ArrayNode mandatory = mapper.createArrayNode();
    for (JsonNode source : sources) {
      String id = source.path("id").asText();
      if (ROW.matcher(id).matches()) rows.put(id, (ObjectNode)source);
      else mandatory.add(source);
    }
    if (rows.isEmpty()) throw new ApiException(422, "CASE_EVIDENCE_EMPTY", "Attach at least one source row before investigating.");
    LinkedHashSet<String> required = new LinkedHashSet<>();
    var mention = MENTION.matcher(question);
    while (mention.find()) {
      String id = mention.group().toUpperCase(Locale.ROOT);
      if (!rows.containsKey(id)) throw new ApiException(422, "CASE_EVIDENCE_ROW_NOT_FOUND", "The question names a source row that is not in this evidence version. Check its row ID.");
      required.add(id);
    }
    // Ensure every supplied group is represented. These are exported-row
    // anchors only, never inferred first/latest operational events.
    for (String group : CaseEvidenceSchema.COLUMNS.keySet()) {
      List<String> groupRows = rows.keySet().stream().filter(id -> id.startsWith(group + "-ROW-")).toList();
      if (!groupRows.isEmpty()) required.add(groupRows.get(0));
      if (group.equals("HISTORY") && groupRows.size() > 1) required.add(groupRows.get(groupRows.size()-1));
    }
    LinkedHashSet<String> selected = new LinkedHashSet<>(required);
    if (!fits(mandatory, rows, selected, reservedGuidance, sourceBytes))
      throw new ApiException(422, "CASE_INVESTIGATION_EVIDENCE_LIMIT", "Required source rows, coverage and interpretation guidance exceed the investigation capacity. Use a narrower question or smaller source export; no fields were shortened and no model call was made.");
    // Preserve all original rows in their original order whenever they fit.
    LinkedHashSet<String> all = new LinkedHashSet<>(rows.keySet());
    if (fits(mandatory, rows, all, reservedGuidance, sourceBytes)) selected = all;
    else {
      Set<String> terms = terms(question);
      List<String> ranked = new ArrayList<>(rows.keySet());
      Map<String, Integer> ordinal = new HashMap<>(), scores = new HashMap<>();
      for (int index=0; index<ranked.size(); index++) {
        String id = ranked.get(index); ordinal.put(id, index); scores.put(id, score(rows.get(id), terms));
      }
      ranked.sort(Comparator.comparingInt((String id) -> scores.get(id)).reversed().thenComparingInt(ordinal::get));
      for (String id : ranked) {
        if (selected.contains(id)) continue;
        selected.add(id);
        if (!fits(mandatory, rows, selected, reservedGuidance, sourceBytes)) selected.remove(id);
      }
    }
    ArrayNode documents = materialize(mandatory, rows, selected);
    ObjectNode metadata = metadata(rows, selected);
    documents.add(receipt(metadata, mandatory));
    return new Selection(documents, metadata);
  }

  private boolean fits(ArrayNode mandatory, LinkedHashMap<String,ObjectNode> rows, Set<String> selected, ArrayNode guides, int sourceBytes) {
    if (mandatory.size() + selected.size() + guides.size() + 1 > 100) return false;
    ArrayNode candidate = materialize(mandatory, rows, selected);
    candidate.add(receipt(metadata(rows, selected), mandatory)); candidate.addAll(guides);
    try { UatService.documentMap(candidate, tooLarge()); }
    catch (ApiException oversized) { return false; }
    return candidate.toString().getBytes(StandardCharsets.UTF_8).length <= sourceBytes;
  }

  private ArrayNode materialize(ArrayNode mandatory, LinkedHashMap<String,ObjectNode> rows, Set<String> selected) {
    ArrayNode result = mandatory.deepCopy();
    rows.forEach((id, document) -> { if (selected.contains(id)) result.add(document.deepCopy()); });
    return result;
  }

  private ObjectNode metadata(LinkedHashMap<String,ObjectNode> rows, Set<String> selected) {
    ObjectNode result = mapper.createObjectNode().put("schemaVersion", "case-evidence-selection-v1")
        .put("method", METHOD).put("mode", selected.size() == rows.size() ? "ALL" : "SELECTED")
        .put("totalRows", rows.size()).put("selectedRows", selected.size()).put("omittedRows", rows.size()-selected.size());
    ArrayNode identifiers = result.putArray("selectedRowIds");
    rows.keySet().forEach(id -> { if (selected.contains(id)) identifiers.add(id); });
    ObjectNode groups = result.putObject("groups");
    for (String group : CaseEvidenceSchema.COLUMNS.keySet()) {
      long total = rows.keySet().stream().filter(id -> id.startsWith(group + "-ROW-")).count();
      long count = selected.stream().filter(id -> id.startsWith(group + "-ROW-")).count();
      groups.putObject(group).put("suppliedRows", total).put("selectedRows", count).put("omittedRows", total-count);
    }
    result.put("meaning", "Only selected whole source rows are supplied to this question. Omitted rows remain in the saved evidence and may contain relevant or conflicting facts. Selection is not source completeness, chronology or payment outcome proof. First/last anchors refer only to exported row order.");
    return result;
  }

  private ObjectNode receipt(ObjectNode metadata, ArrayNode mandatory) {
    String file = mandatory.isEmpty() ? "saved-case-evidence" : mandatory.get(0).path("source").path("file").asText();
    ObjectNode result = mapper.createObjectNode().put("id", ID).put("kind", "evidence")
        .put("title", "Source rows selected for this investigation").put("content", metadata.toString());
    result.putObject("source").put("file", file).put("locator", "Deterministic question selection; original row IDs and source bodies preserved");
    return result;
  }

  private static Set<String> terms(String question) {
    Set<String> result = new HashSet<>();
    var matcher = Pattern.compile("[A-Za-z0-9_]+", Pattern.UNICODE_CHARACTER_CLASS).matcher(question.toLowerCase(Locale.ROOT));
    while (matcher.find()) if (matcher.group().length() > 1) result.add(matcher.group());
    if (!Collections.disjoint(result, Set.of("history", "changed", "change"))) result.addAll(List.of("history", "txn_stat", "acct_stat", "msg_stat", "dat_txn"));
    if (!Collections.disjoint(result, Set.of("beneficiary", "credited", "credit"))) result.addAll(List.of("n10_status", "n10_datetime", "acctstatus", "acct_stat"));
    if (!Collections.disjoint(result, Set.of("obpm", "accepted", "accept"))) result.addAll(List.of("host", "msg_stat", "ref_network_no", "cod_reply"));
    if (!Collections.disjoint(result, Set.of("status", "code", "codes"))) result.addAll(List.of("codstatus", "acctstatus", "msgstatus", "neftcodstatus"));
    return result;
  }

  private static int score(ObjectNode row, Set<String> terms) {
    String text = (row.path("id").asText() + " " + row.path("title").asText() + " " + row.path("content").asText()).toLowerCase(Locale.ROOT);
    Set<String> words = new HashSet<>(Arrays.asList(text.split("[^a-z0-9_]+")));
    return (int)terms.stream().filter(words::contains).count();
  }
  private static ApiException tooLarge() { return new ApiException(422, "CASE_INVESTIGATION_EVIDENCE_LIMIT", "The required source selection exceeds the unchanged model input limits."); }
}
