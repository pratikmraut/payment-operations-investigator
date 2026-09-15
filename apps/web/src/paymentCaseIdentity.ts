type CaseIdentity = { caseNumber?: string; id?: string; caseId?: string };

// Keep case numbers as text so their date prefix and padded sequence stay exact.
export function paymentCaseNumber(item: CaseIdentity): string {
  return item.caseNumber || item.id || item.caseId || "";
}

export function paymentCasePath(item: CaseIdentity): string {
  return `/payment-cases/${encodeURIComponent(paymentCaseNumber(item))}`;
}
