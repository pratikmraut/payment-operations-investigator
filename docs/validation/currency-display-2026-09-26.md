# Payment currency display — 26 September 2026

The user requested INR as the frontend fallback for missing currency and removal of the inline evidence-version suffix beside amounts. This supersedes the missing-currency presentation described in the earlier evidence-currency validation receipt.

Discovery results now use the same `PaymentAmount` component as the saved-case queue, case details, Evidence library and Evidence Q&A. Currency precedence is the original supplied currency, a validated evidence currency, then the INR display default. The visible label contains only the currency code. The tooltip retains either source/version attribution or the Indian-rupee display-default explanation. Amount strings retain their original precision; API payloads, saved evidence, model inputs and backend reports are unchanged.

Validation completed:

- 110 tests passed across the five affected frontend suites. Checks include exact amount preservation, immutable original null currency, explicit EUR/USD precedence, malformed optional hints, missing/blank currency, discovery results, saved cases, case details, Evidence library and Evidence Q&A.
- Main and native TypeScript checks and both production builds passed. The existing bundle-size advisory remains.
- The existing end-to-end scenario's currency assertions were updated to match the new labels; that full scenario was not rerun for this presentation-only change.
- The native frontend was backed up and its tested assets published locally, with the HTML switched after copying assets. Served HTML, JavaScript and CSS match the candidate build.
- A read-only browser check confirmed the running saved-case queue displays plain INR for both evidence-backed and discovery-only cases, without either removed phrase. No bank inquiry, case mutation or model inference was initiated for verification.
- No service restart was required. The private API configuration and process receipt remain unchanged, preserving the persistent branch authorization mapping.

Private deployment records and the previous frontend remain under ignored `runtime/currency-display-2026-09-26/`. No private bank mappings, endpoints or payment records were added to tracked source. This receipt records local validation before publication; it does not claim a GitHub CI result.
