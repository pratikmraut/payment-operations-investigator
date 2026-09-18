import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import {
  PaymentAmount,
  paymentCurrency,
  validateEvidenceCurrency,
} from "./PaymentAmount";

const hint = {
  currency: "INR",
  evidenceId: "EVD-CURRENCY-FIXTURE",
  version: 1,
  sourceKind: "JSON",
};
const source = {
  amount: "123456789012345678901.0007",
  currency: null,
  evidenceCurrency: hint,
};

describe("saved payment amount currency provenance", () => {
  it("preserves the exact source amount and identifies the supporting evidence version", () => {
    render(<PaymentAmount item={source} />);
    expect(
      screen.getByText("123456789012345678901.0007 INR · from evidence v1"),
    ).toHaveAttribute(
      "title",
      "Currency from JSON PAYMENT evidence EVD-CURRENCY-FIXTURE, version 1. The original discovery amount is unchanged.",
    );
    expect(source.currency).toBeNull();
    expect(source.amount).toBe("123456789012345678901.0007");
  });
  it("uses the actual evidence currency and version instead of defaulting to INR", () => {
    render(
      <PaymentAmount
        item={{
          ...source,
          evidenceCurrency: {
            ...hint,
            currency: "USD",
            version: 3,
            sourceKind: "BANK_API",
          },
        }}
        stacked
      />,
    );
    expect(screen.getByText(source.amount)).toBeVisible();
    expect(screen.getByText("USD · from evidence v3")).toHaveAttribute(
      "title",
      expect.stringContaining("BANK_API PAYMENT evidence"),
    );
    expect(screen.queryByText(/INR/)).not.toBeInTheDocument();
  });
  it("keeps the original discovery currency in preference to any evidence hint", () => {
    const original = { ...source, currency: "EUR" };
    render(<PaymentAmount item={original} />);
    expect(screen.getByText(`${source.amount} EUR`)).not.toHaveAttribute(
      "title",
    );
    expect(screen.queryByText(/from evidence/)).not.toBeInTheDocument();
    expect(paymentCurrency(original)).toEqual({ label: "EUR", evidence: null });
  });
  it.each([
    undefined,
    null,
    [],
    {},
    { ...hint, currency: "inr" },
    { ...hint, currency: "₹" },
    { ...hint, currency: 123 },
    { ...hint, evidenceId: "" },
    { ...hint, evidenceId: "bad identity" },
    { ...hint, version: 0 },
    { ...hint, version: 1.5 },
    { ...hint, version: "1" },
    { ...hint, version: Number.MAX_SAFE_INTEGER + 1 },
    { ...hint, sourceKind: "UNKNOWN" },
    { ...hint, sourceKind: ["JSON"] },
  ])(
    "ignores an absent or malformed optional hint without inventing currency: %j",
    (evidenceCurrency) => {
      expect(validateEvidenceCurrency(evidenceCurrency)).toBeNull();
      render(<PaymentAmount item={{ ...source, evidenceCurrency }} />);
      expect(
        screen.getByText(`${source.amount} · Currency not supplied`),
      ).toBeVisible();
      expect(screen.queryByText(/INR|from evidence/)).not.toBeInTheDocument();
    },
  );
});
