# Synthetic dataset card

All fixtures and runbooks are original, generated for this application. No employer code, customer logs, bank credentials, live financial records or copyrighted operational manuals are included.

## Generation
tools/generate_data.py uses a fixed seed and clock to produce operational fixtures, a knowledge corpus and separately stored labels. Default dataset: 60 cases across six outcome families; 30 development cases and 30 test cases. Two synthetic tenants test isolation. IDs and timestamps are reproducible. Variants alter amounts, reference IDs, timing and missing facts.

## Separation
- data/fixtures/cases.json contains observable records only.
- data/knowledge/runbooks.json contains original, versioned simulated policy.
- data/evaluation/labels.json contains expected conclusions, split, required evidence and expected differences.
Only the evaluation harness reads labels. Labels are not mounted into the worker container, returned by APIs, or embedded into operational metadata.

## Scenario families
Timeout after success; duplicate callback; callback ordering; missing refund; insufficient evidence; confirmed provider failure. Operational descriptions describe symptoms. Classification must follow provider/event/ledger/webhook evidence.

## Amounts
Amounts are integer paise with currency INR. CAPTURE is positive; FEE and REFUND entries are negative. Provider fee/refund fields are nonnegative. Compute known net as capture minus fee minus refund. Never let generated prose determine arithmetic.

## Corpus
Runbooks identify source as original simulated policy, retain stable document IDs and versions, and carry tenant/effective-date metadata. Expired and other-tenant versions enable negative retrieval checks. These are not representations of real bank or payment-network procedures.

## Limitations
Synthetic accuracy is not field accuracy. A small hand-designed set can underrepresent real incidents. Evaluate held-out variants, publish failure cases and disclose which modes and retrieval methods were actually measured.

