# Payment workspace frontend refresh — 16 September 2026

The refreshed frontend is deployed at `http://127.0.0.1:5178/cases`. This implements the user's request for an attractive, readable and consistent interface throughout the payment workflow. It does not change the bank APIs, local model, RAG guidance or saved answer content.

## Delivered

- Shared typography, colors, spacing, readable cards, consistent buttons and visible keyboard focus across both frontend entry points.
- Labeled desktop and mobile navigation, skip-to-content, and an explanatory four-stage payment workflow.
- Clear discovery-method cards, aligned bank/branch/search fields, readable saved-case rows and accessible primary actions.
- Case section shortcuts for management, evidence collection and investigation, including keyboard focus movement without changing the route.
- Better evidence upload forms, version coverage, saved investigations, source citations, management forms and report selection.
- Aligned Knowledge library filters, source cards and a coverage meter driven by the existing embedding inventory.
- Scope initialization now derives defaults without a delayed effect overwriting operator edits. Explicitly cleared bank/branch inputs stay cleared; a regression test covers first-render defaults and user overrides.

See [design rules and implementation](../FRONTEND_DESIGN.md).

## Verification

| Check | Result |
| --- | --- |
| Full frontend suite | 400 passed across 24 files |
| Main TypeScript | Passed |
| Native frontend TypeScript | Passed |
| Native production build | Passed |
| Isolated Chromium browser review | 27 views passed, plus desktop/mobile sign-in screenshots |
| Responsive widths | 1440px, 768px and 390px |
| Role views | Analyst, Reviewer, Viewer and Administrator |
| Browser exceptions / horizontal page overflow | None in the checked views |
| Visible primary/secondary button height | At least 44px in the checked views |
| Case section shortcut | Evidence heading receives focus |
| Served frontend | Index and asset hashes exactly match the tested build |

The first full test run found the scope-initialization issue; the subsequent complete suite passed after correction. A final CSS polish removed nested search-input borders, followed by a fresh production build and repeat browser layout review. No Java/backend logic changed during this refresh.

Browser checks used a disposable headless browser and the local API's test identities. Page API requests were restricted to GET; no bank inquiry, model generation, evidence save, report preview save or case-management mutation was submitted. Following the user's preference, final verification used isolated browser automation and project files, without desktop control or full-laptop access.

## Preservation and deployment

Exact before/after API comparisons preserve **six cases, three evidence versions, six investigations, one original export snapshot and three original export answers**, including case management/lifecycle and saved source/answer bodies. These counts are the baseline captured for this refresh. Protected API artifact, model/runtime configuration, bank resolver and knowledge/index fingerprints are unchanged.

The frontend was backed up and published by copying hashed assets before atomically replacing its index. Old assets remain available for already-open browser sessions. No service was restarted.

| Served file | SHA-256 |
| --- | --- |
| `index.html` | `1224cac2a2e779f38df8db38955ab0b5d7a5fd881f2476cf642d42cb282bb578` |
| `index-BRwpmXIg.js` | `0752411a295448d0c83b4a63e84459dc030203f2b5e0881b990d9f545a388af6` |
| `index-DbGSwRrx.css` | `c3f49acbd52638a6f856e2c33facd5b9812f76e46e4bbe9763f3e5b687e7b27f` |

Private screenshots, test logs, preservation receipts and the prior frontend backup are in ignored `runtime/frontend-refresh-2026-09-16/`.

## Limits

This validates the changed interface and scoped regression coverage; it is not exhaustive accessibility certification, a new bank-integration test or a payment-answer accuracy claim. Historical failed investigation jobs remain unchanged and visible. The previously documented bank-side PO01 exact-reference redeployment requirement remains separate from this frontend refresh.
