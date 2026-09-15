# Saved case search and pagination

The user requested one search box for case/payment reference or investigation reason and ten records per page. The Saved payment cases section remains below Find payment. It now filters its authorized records as the operator types, ignores letter case, supports partial references and AND matching of whitespace-separated terms across case ID/payment reference/UTR/reason, and shows an explicit no-match result. Search does not match bank, branch or amount.

Pagination operates after filtering. It shows ten rows per page, a matching-record range, page numbers and Previous/Next controls. Changing or clearing the query resets to page 1, as does Refresh queue. The overall saved count and dashboard cards remain unfiltered. No cases are deleted or created by this feature.

Validation completed before deployment:

- 29 focused tests passed across PaymentDiscovery.test.tsx and PaymentSavedCases.test.tsx.
- A separate synthetic 23-record fixture verifies pages of 10, 10 and 3 records, distinct case links and page controls.
- Field-search tests cover case ID, payment reference, UTR, reason, mixed case, multiple whitespace-separated words across fields and a record originally on page 3.
- No-match, clear-search, query page reset and a refresh shrinking 23 records to five are covered. Search/page changes issue no network requests.
- Main and native frontend TypeScript checks passed.

The native production build passed and was served by the existing local website without restarting the backend. Live browser checks over the seven saved cases confirmed that a mixed-case reason search returns only the two matching test cases, a partial payment-reference search returns one matching case, an unrelated query shows no rows, and clearing search restores all seven. The overall count remains seven while filtering. The section stays below Find payment. Since the live dataset has fewer than eleven cases, multiple-page navigation was verified with the separate 23-case test fixture; no extra live cases were created for testing.

Authorization remains in Java's existing saved-list/detail endpoints. The local frontend receives all authorized records and performs display pagination; this is not a server-side query limit or large-dataset scalability claim. Search does not query the bank provider, call the model or substitute for Reference / UTR inquiry.
