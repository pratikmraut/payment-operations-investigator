const groups = ["PAYMENT", "HOST", "HISTORY", "STATUS"] as const;
type Group = (typeof groups)[number];
type Sections = Record<Group, { rows: Record<string, string>[] }>;
type SourceField = { group: Group; rowIndex: number; field: string };

export type EvidenceUpstream = {
  receivedAt: string;
  request: {
    args0: { serviceCode: string; externalReferenceNo: string };
    args1: Record<string, unknown>;
  };
  rawResponse: Record<string, unknown>;
  nullFields: SourceField[];
} & (
  | { schemaVersion: "flexcube-neft-evidence-v1"; omittedFields?: never }
  | { schemaVersion: "flexcube-neft-evidence-v2"; omittedFields: SourceField[] }
);
const record = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === "object" && !Array.isArray(value);

export function validateEvidenceUpstream(
  value: unknown,
  sections: Sections,
): asserts value is EvidenceUpstream | undefined {
  if (value === undefined) return;
  const error = () => {
    throw new Error(
      "The saved API provenance does not match its normalized evidence rows. Refresh this version before inspecting its source values.",
    );
  };
  if (
    !record(value) ||
    (value.schemaVersion !== "flexcube-neft-evidence-v1" &&
      value.schemaVersion !== "flexcube-neft-evidence-v2") ||
    typeof value.receivedAt !== "string" ||
    !/^\d{4}-\d{2}-\d{2}T.+Z$/.test(value.receivedAt) ||
    !Number.isFinite(Date.parse(value.receivedAt)) ||
    !record(value.request) ||
    !record(value.request.args0) ||
    value.request.args0.serviceCode !== "PO02" ||
    typeof value.request.args0.externalReferenceNo !== "string" ||
    !value.request.args0.externalReferenceNo.trim() ||
    value.request.args0.externalReferenceNo.length > 4000 ||
    !record(value.request.args1) ||
    !record(value.rawResponse) ||
    !Array.isArray(value.nullFields)
  )
    return error();

  const omittedFields =
    value.schemaVersion === "flexcube-neft-evidence-v2"
      ? value.omittedFields
      : [];
  if (
    !Array.isArray(omittedFields) ||
    (value.schemaVersion === "flexcube-neft-evidence-v1" &&
      Object.hasOwn(value, "omittedFields"))
  )
    return error();

  const paths = new Set<string>();
  for (const entry of [...value.nullFields, ...omittedFields]) {
    if (
      !record(entry) ||
      Object.keys(entry).length !== 3 ||
      !groups.includes(entry.group as Group) ||
      !Number.isSafeInteger(entry.rowIndex) ||
      Number(entry.rowIndex) < 1 ||
      typeof entry.field !== "string"
    )
      return error();
    const row = sections[entry.group as Group].rows[Number(entry.rowIndex) - 1];
    const path = JSON.stringify([entry.group, entry.rowIndex, entry.field]);
    if (
      !row ||
      !Object.hasOwn(row, entry.field) ||
      row[entry.field] !== "" ||
      paths.has(path)
    )
      return error();
    paths.add(path);
  }
}

export function isSourceNull(
  upstream: EvidenceUpstream | undefined,
  group: Group,
  rowIndex: number,
  field: string,
) {
  return (
    upstream?.nullFields.some(
      (entry) =>
        entry.group === group &&
        entry.rowIndex === rowIndex + 1 &&
        entry.field === field,
    ) ?? false
  );
}

export function isSourceOmitted(
  upstream: EvidenceUpstream | undefined,
  group: Group,
  rowIndex: number,
  field: string,
) {
  return (
    upstream?.schemaVersion === "flexcube-neft-evidence-v2" &&
    upstream.omittedFields.some(
      (entry) =>
        entry.group === group &&
        entry.rowIndex === rowIndex + 1 &&
        entry.field === field,
    )
  );
}

export function EvidenceProvenance({
  upstream,
}: {
  upstream?: EvidenceUpstream;
}) {
  if (!upstream) return null;
  return (
    <details className="evidence-library-fingerprint">
      <summary>API source provenance</summary>
      <dl>
        <div>
          <dt>Received by this app</dt>
          <dd>{upstream.receivedAt}</dd>
        </div>
        <div>
          <dt>Service code</dt>
          <dd>{upstream.request.args0.serviceCode}</dd>
        </div>
        <div>
          <dt>Correlation reference</dt>
          <dd>{upstream.request.args0.externalReferenceNo}</dd>
        </div>
        <div>
          <dt>Source null fields</dt>
          <dd>{upstream.nullFields.length}</dd>
        </div>
        {upstream.schemaVersion === "flexcube-neft-evidence-v2" && (
          <div>
            <dt>Fields not supplied</dt>
            <dd>{upstream.omittedFields.length}</dd>
          </div>
        )}
      </dl>
      <p className="muted">
        {upstream.schemaVersion === "flexcube-neft-evidence-v2" &&
          "Fields missing from the API response are labeled Not supplied. "}
        Source nulls are retained in the saved API response and labeled
        separately from blank text below. Receipt time does not establish a
        payment event, source timezone or query completion.
      </p>
    </details>
  );
}
