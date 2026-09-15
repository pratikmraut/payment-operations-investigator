import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import {
  EvidenceProvenance,
  isSourceNull,
  isSourceOmitted,
  validateEvidenceUpstream,
} from "./EvidenceProvenance";

const sections = {
  PAYMENT: {
    rows: [
      { N10_MSGID: "", IDMSGREFERENCE_2020: "", NUMAMOUNT_4038: "125.005" },
    ],
  },
  HOST: { rows: [] },
  HISTORY: { rows: [] },
  STATUS: { rows: [] },
};
const provenance = () => ({
  schemaVersion: "flexcube-neft-evidence-v1" as const,
  receivedAt: "2026-09-14T10:10:00.123456Z",
  request: {
    args0: {
      serviceCode: "PO02",
      externalReferenceNo: "ORIGINAL-CORRELATION",
      userId: "PRIVATE-SERVICE-USER",
    },
    args1: { referenceTransactionNumber: "ORIGINAL-FIXTURE" },
  },
  rawResponse: {
    privateWireMessage: "DO-NOT-DISPLAY-RAW",
    neftPaymentEvidenceDetails: [{ n10MsgId: null, idMsgReference2020: "" }],
  },
  nullFields: [{ group: "PAYMENT" as const, rowIndex: 1, field: "N10_MSGID" }],
});
const sparseProvenance = () => ({
  ...provenance(),
  schemaVersion: "flexcube-neft-evidence-v2" as const,
  rawResponse: { neftPaymentEvidenceDetails: [{ n10MsgId: null }] },
  omittedFields: [
    { group: "PAYMENT" as const, rowIndex: 1, field: "IDMSGREFERENCE_2020" },
  ],
});

describe("API evidence provenance", () => {
  it("keeps legacy snapshots readable and distinguishes a source null from empty text", () => {
    expect(() => validateEvidenceUpstream(undefined, sections)).not.toThrow();
    const upstream = provenance();
    expect(() => validateEvidenceUpstream(upstream, sections)).not.toThrow();
    expect(isSourceNull(upstream, "PAYMENT", 0, "N10_MSGID")).toBe(true);
    expect(isSourceNull(upstream, "PAYMENT", 0, "IDMSGREFERENCE_2020")).toBe(
      false,
    );
    expect(isSourceNull(undefined, "PAYMENT", 0, "N10_MSGID")).toBe(false);
  });

  it.each([
    { group: "PAYMENT", rowIndex: 0, field: "N10_MSGID" },
    { group: "HOST", rowIndex: 1, field: "N10_MSGID" },
    { group: "PAYMENT", rowIndex: 1, field: "NUMAMOUNT_4038" },
    { group: "PAYMENT", rowIndex: 1, field: "MISSING_COLUMN" },
  ])(
    "rejects a null marker inconsistent with its saved row: $group/$rowIndex/$field",
    (marker) => {
      expect(() =>
        validateEvidenceUpstream(
          { ...provenance(), nullFields: [marker] },
          sections,
        ),
      ).toThrow(/provenance does not match/);
    },
  );

  it("rejects duplicate null paths and malformed receipt metadata", () => {
    const upstream = provenance();
    expect(() =>
      validateEvidenceUpstream(
        {
          ...upstream,
          nullFields: [...upstream.nullFields, ...upstream.nullFields],
        },
        sections,
      ),
    ).toThrow();
    expect(() =>
      validateEvidenceUpstream(
        { ...upstream, receivedAt: "yesterday" },
        sections,
      ),
    ).toThrow();
  });

  it("reads v2 omissions separately from nulls and keeps v1 omission-free", () => {
    const upstream = sparseProvenance();
    expect(() => validateEvidenceUpstream(upstream, sections)).not.toThrow();
    expect(isSourceOmitted(upstream, "PAYMENT", 0, "IDMSGREFERENCE_2020")).toBe(
      true,
    );
    expect(isSourceNull(upstream, "PAYMENT", 0, "IDMSGREFERENCE_2020")).toBe(
      false,
    );
    expect(isSourceNull(upstream, "PAYMENT", 0, "N10_MSGID")).toBe(true);
    expect(isSourceOmitted(upstream, "PAYMENT", 0, "N10_MSGID")).toBe(false);
    expect(isSourceOmitted(upstream, "PAYMENT", 1, "IDMSGREFERENCE_2020")).toBe(
      false,
    );
    expect(
      isSourceOmitted(provenance(), "PAYMENT", 0, "IDMSGREFERENCE_2020"),
    ).toBe(false);
    expect(
      isSourceOmitted(undefined, "PAYMENT", 0, "IDMSGREFERENCE_2020"),
    ).toBe(false);
    expect(() =>
      validateEvidenceUpstream(
        { ...provenance(), omittedFields: [] },
        sections,
      ),
    ).toThrow();
    expect(() =>
      validateEvidenceUpstream(
        { ...provenance(), schemaVersion: "flexcube-neft-evidence-v2" },
        sections,
      ),
    ).toThrow();
  });

  it.each([
    [{ group: "PAYMENT", rowIndex: 1, field: "N10_MSGID" }],
    [{ group: "PAYMENT", rowIndex: 1, field: "NUMAMOUNT_4038" }],
    [{ group: "HOST", rowIndex: 1, field: "IDMSGREFERENCE_2020" }],
    [{ group: "PAYMENT", rowIndex: 0, field: "IDMSGREFERENCE_2020" }],
    [{ group: "PAYMENT", rowIndex: 1.5, field: "IDMSGREFERENCE_2020" }],
    [{ group: "PAYMENT", rowIndex: 1, field: "MISSING_COLUMN" }],
    [
      { group: "PAYMENT", rowIndex: 1, field: "IDMSGREFERENCE_2020" },
      { group: "PAYMENT", rowIndex: 1, field: "IDMSGREFERENCE_2020" },
    ],
  ])(
    "rejects overlapping, duplicate or invalid omission paths (%#)",
    (...markers) => {
      expect(() =>
        validateEvidenceUpstream(
          { ...sparseProvenance(), omittedFields: markers },
          sections,
        ),
      ).toThrow();
    },
  );

  it("requires an omission array in v2 even when no fields were omitted", () => {
    for (const schemaVersion of [
      null,
      ["flexcube-neft-evidence-v2"],
      "unknown",
    ])
      expect(() =>
        validateEvidenceUpstream(
          { ...sparseProvenance(), schemaVersion },
          sections,
        ),
      ).toThrow();
    expect(() =>
      validateEvidenceUpstream(
        { ...sparseProvenance(), omittedFields: [] },
        sections,
      ),
    ).not.toThrow();
    for (const omittedFields of [null, undefined, {}, ""])
      expect(() =>
        validateEvidenceUpstream(
          { ...sparseProvenance(), omittedFields },
          sections,
        ),
      ).toThrow();
  });

  it("shows an omission count without displaying raw upstream details", () => {
    render(<EvidenceProvenance upstream={sparseProvenance()} />);
    expect(
      screen.getByText("Fields not supplied").parentElement,
    ).toHaveTextContent("1");
    expect(
      screen.getByText(
        /Fields missing from the API response are labeled Not supplied/,
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText("PRIVATE-SERVICE-USER")).not.toBeInTheDocument();
  });

  it("shows only collapsed receipt metadata without exposing service user or the raw response", () => {
    render(<EvidenceProvenance upstream={provenance()} />);
    expect(
      screen.getByText("API source provenance").closest("details"),
    ).not.toHaveAttribute("open");
    expect(screen.getByText("ORIGINAL-CORRELATION")).toBeInTheDocument();
    expect(screen.getByText("PO02")).toBeInTheDocument();
    expect(
      screen.getByText(/Receipt time does not establish a payment event/),
    ).toBeInTheDocument();
    expect(screen.queryByText("PRIVATE-SERVICE-USER")).not.toBeInTheDocument();
    expect(screen.queryByText("DO-NOT-DISPLAY-RAW")).not.toBeInTheDocument();
  });
});
