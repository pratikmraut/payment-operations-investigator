package dev.pratik.poi;

import java.util.*;

/** Strict public query contract. SQL identifiers and sorting are never taken from user text. */
record CaseSearchQuery(String lifecycle, String search, String work, int page, int pageSize,
    String sort, String bank, String branch, String reference) {
  private static final Set<String> KEYS = Set.of("lifecycle", "search", "work", "page", "pageSize", "sort", "bank", "branch", "reference");
  private static final Set<String> WORK = Set.of("ALL", "MINE", "OPEN", "INVESTIGATING", "AWAITING_EVIDENCE", "AWAITING_REVIEW", "RESOLVED");
  private static final Map<String,String> SORT = Map.of(
      "CREATED_DESC", "s.created_at DESC,s.created_nanos DESC,s.case_id ASC",
      "CREATED_ASC", "s.created_at ASC,s.created_nanos ASC,s.case_id ASC",
      "UPDATED_DESC", "s.updated_at DESC,s.updated_nanos DESC,s.case_id ASC",
      "CASE_NUMBER_ASC", "CASE WHEN s.case_number IS NULL THEN 1 ELSE 0 END,s.case_number ASC,s.case_id ASC",
      "CASE_NUMBER_DESC", "CASE WHEN s.case_number IS NULL THEN 1 ELSE 0 END,s.case_number DESC,s.case_id ASC",
      "PRIORITY_DESC", "s.priority_rank DESC,s.created_at DESC,s.created_nanos DESC,s.case_id ASC");

  static CaseSearchQuery parse(Map<String,String[]> parameters) {
    if (parameters == null) throw CaseSearchIndex.invalid();
    for (var entry : parameters.entrySet())
      if (!KEYS.contains(entry.getKey()) || entry.getValue() == null || entry.getValue().length != 1 || entry.getValue()[0] == null)
        throw CaseSearchIndex.invalid();
    String lifecycle = value(parameters, "lifecycle", "ACTIVE"), work = value(parameters, "work", "ALL"), sort = value(parameters, "sort", "CREATED_DESC");
    if (!Set.of("ACTIVE", "ARCHIVED", "ALL").contains(lifecycle) || !WORK.contains(work) || !SORT.containsKey(sort)) throw CaseSearchIndex.invalid();
    String search = CaseSearchIndex.normalizeSearch(value(parameters, "search", ""));
    int page = integer(value(parameters, "page", "1")), size = integer(value(parameters, "pageSize", "10"));
    if (size > 50) throw CaseSearchIndex.invalid();
    String bank = value(parameters, "bank", null), branch = value(parameters, "branch", null), reference = value(parameters, "reference", null);
    if ((bank != null && !bank.matches("[0-9]{1,10}")) || (branch != null && !branch.matches("[0-9]{1,10}"))) throw CaseSearchIndex.invalid();
    if (reference != null && (reference.isBlank() || reference.length() > 200 || !reference.equals(reference.strip()) || reference.codePoints().anyMatch(Character::isISOControl))) throw CaseSearchIndex.invalid();
    return new CaseSearchQuery(lifecycle, search, work, page, size, sort, bank, branch, reference);
  }

  CaseSearchIndex.SqlWhere where(Actor actor, List<PaymentDiscoveryService.Scope> scopes) {
    CaseSearchIndex.SqlWhere result = CaseSearchIndex.scope(actor, scopes, bank, branch);
    if (!lifecycle.equals("ALL")) result = result.and("s.lifecycle_state=?", lifecycle);
    if (work.equals("MINE")) result = result.and("s.owner_id=?", actor.id());
    else if (!work.equals("ALL")) result = result.and("s.workflow_status=?", work);
    if (reference != null) result = result.and("s.payment_reference=?", reference);
    return CaseSearchIndex.addTerms(result, search, "s.search_text");
  }
  String orderBy() { return SORT.get(sort); }
  private static String value(Map<String,String[]> input, String key, String fallback) { return input.containsKey(key) ? input.get(key)[0] : fallback; }
  private static int integer(String text) {
    if (!text.matches("[1-9][0-9]{0,9}")) throw CaseSearchIndex.invalid();
    try { return Integer.parseInt(text); } catch (NumberFormatException failure) { throw CaseSearchIndex.invalid(); }
  }
}
