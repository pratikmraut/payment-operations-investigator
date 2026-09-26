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
    expect(screen.getByText("123456789012345678901.0007 INR")).toHaveAttribute(
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
    expect(screen.getByText("USD")).toHaveAttribute(
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
    "uses the INR display default when no valid source currency is available: %j",
    (evidenceCurrency) => {
      expect(validateEvidenceCurrency(evidenceCurrency)).toBeNull();
      const item = Object.freeze({ ...source, evidenceCurrency });
      render(<PaymentAmount item={item} />);
      expect(screen.getByText(`${source.amount} INR`)).toHaveAttribute(
        "title",
        expect.stringContaining("display default"),
      );
      expect(screen.queryByText(/from evidence/)).not.toBeInTheDocument();
      expect(item.currency).toBeNull();
    },
  );
  it.each([null, "", "   "])(
    "shows Indian rupees for missing currency in stacked discovery results: %j",
    (currency) => {
      render(
        <PaymentAmount item={{ amount: "2000.0000", currency }} stacked />,
      );
      expect(screen.getByText("2000.0000")).toBeVisible();
      expect(screen.getByText("INR")).toHaveAttribute(
        "title",
        expect.stringContaining("Indian rupees"),
      );
    },
  );
});
