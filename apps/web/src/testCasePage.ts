// Synthetic test server: returns one bounded page, never the full matching list.
export function casePageFixture(
  url: string,
  source: Record<string, unknown>[],
  userId = "operator",
) {
  const q = new URL(url, "http://fixture.test").searchParams;
  const lifecycle = q.get("lifecycle") ?? "ACTIVE";
  const work = q.get("work") ?? "ALL";
  const search = (q.get("search") ?? "").trim();
  const sort = q.get("sort") ?? "CREATED_DESC";
  const pageSize = Number(q.get("pageSize") ?? 10);
  const fields = [
    "id",
    "caseNumber",
    "reference",
    "utr",
    "reason",
    "priority",
    "status",
  ];
  const matching = source.filter((item) => {
    const owner = item.owner as { id?: string; name?: string } | undefined;
    const text = [
      ...fields.map((key) => String(item[key] ?? "").replaceAll("_", " ")),
      owner?.id,
      owner?.name,
    ]
      .join(" ")
      .toLowerCase();
    return (
      (lifecycle === "ALL" ||
        (item.lifecycleState ?? "ACTIVE") === lifecycle) &&
      (work === "ALL" ||
        (work === "MINE" ? owner?.id === userId : item.status === work)) &&
      search
        .toLowerCase()
        .split(/\s+/)
        .filter(Boolean)
        .every((word) => text.includes(word)) &&
      [
        ["bank", "orgBank"],
        ["branch", "orgBranch"],
        ["reference", "reference"],
      ].every(([query, field]) => !q.has(query) || q.get(query) === item[field])
    );
  });
  const value = (item: Record<string, unknown>, field: string) =>
    String(item[field] ?? "");
  matching.sort((a, b) => {
    if (sort === "CREATED_ASC")
      return value(a, "createdAt").localeCompare(value(b, "createdAt"));
    if (sort === "UPDATED_DESC")
      return value(b, "updatedAt").localeCompare(value(a, "updatedAt"));
    if (sort.startsWith("CASE_NUMBER"))
      return (
        (sort.endsWith("ASC") ? 1 : -1) *
        value(a, "caseNumber").localeCompare(value(b, "caseNumber"))
      );
    if (sort === "PRIORITY_DESC")
      return (
        ["LOW", "MEDIUM", "HIGH", "CRITICAL"].indexOf(String(b.priority)) -
        ["LOW", "MEDIUM", "HIGH", "CRITICAL"].indexOf(String(a.priority))
      );
    return value(b, "createdAt").localeCompare(value(a, "createdAt"));
  });
  const totalPages = Math.max(1, Math.ceil(matching.length / pageSize));
  const page = Math.min(totalPages, Math.max(1, Number(q.get("page") ?? 1)));
  return {
    items: matching.slice((page - 1) * pageSize, page * pageSize),
    total: matching.length,
    page,
    pageSize,
    totalPages,
    sort,
    search,
    work,
    lifecycle,
  };
}
