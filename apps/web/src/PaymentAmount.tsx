export type EvidenceCurrency = {
  currency: string;
  evidenceId: string;
  version: number;
  sourceKind: "BANK_API" | "EXCEL" | "JSON" | "MANUAL";
};
export type PaymentAmountSource = {
  amount: string;
  currency: string | null;
  // Optional read metadata is validated here; it never replaces discovery data.
  evidenceCurrency?: unknown;
};
export function validateEvidenceCurrency(
  value: unknown,
): EvidenceCurrency | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const hint = value as Record<string, unknown>;
  if (
    typeof hint.currency !== "string" ||
    !/^[A-Z]{3}$/.test(hint.currency) ||
    typeof hint.evidenceId !== "string" ||
    !/^[A-Za-z0-9][A-Za-z0-9_-]{0,199}$/.test(hint.evidenceId) ||
    !Number.isSafeInteger(hint.version) ||
    (hint.version as number) < 1 ||
    typeof hint.sourceKind !== "string" ||
    !["BANK_API", "EXCEL", "JSON", "MANUAL"].includes(hint.sourceKind)
  )
    return null;
  return hint as EvidenceCurrency;
}

export function paymentCurrency(item: PaymentAmountSource) {
  if (typeof item.currency === "string" && item.currency.trim())
    return { label: item.currency, evidence: null };
  const evidence = validateEvidenceCurrency(item.evidenceCurrency);
  return {
    label: evidence?.currency ?? "INR",
    evidence,
  };
}

export function PaymentAmount({
  item,
  stacked = false,
}: {
  item: PaymentAmountSource;
  stacked?: boolean;
}) {
  const { label, evidence } = paymentCurrency(item);
  const title = evidence
    ? `Currency from ${evidence.sourceKind} PAYMENT evidence ${evidence.evidenceId}, version ${evidence.version}. The original discovery amount is unchanged.`
    : typeof item.currency !== "string" || !item.currency.trim()
      ? "Indian rupees (INR): this project's display default when currency is not supplied."
      : undefined;
  if (stacked)
    return (
      <>
        <span className="payment-source-value">{item.amount}</span>
        <small title={title}>{label}</small>
      </>
    );
  return (
    <span title={title}>
      {item.amount}
      {` ${label}`}
    </span>
  );
}
