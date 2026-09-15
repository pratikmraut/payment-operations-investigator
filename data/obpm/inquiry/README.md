# Original synthetic inquiry records

Four deterministic JSON records support the local `services/mock-inquiry` service. They model our proposed `neft-inquiry-v1` API response, not Oracle physical tables or an existing Oracle endpoint. They contain no employer/client/customer data, proprietary source bodies, credentials, account identifiers or hidden evaluation labels.

The generator is `tools/generate_obpm_inquiry_samples.py`. It constructs these records independently of existing imported snapshots and evaluation files. Run it with `--check` to verify exact committed bytes. Amounts are decimal strings; Java owns conversion to exact INR minor units.

| File/reference | Amount (INR) | Evidence variation |
| --- | --- | --- |
| `MOCK-NEFT-1001.json` | `18450.75` | Uniquely current recorded ECA timeout |
| `MOCK-NEFT-1002.json` | `2750.00` | Old timeout followed by a separate current P attempt |
| `MOCK-NEFT-1003.json` | `925.25` | Partial coverage and unknown response code |
| `MOCK-NEFT-1004.json` | `6040.50` | Two current records preserve unresolved source ambiguity |

Every file explicitly identifies `dataClassification: SYNTHETIC`, fixed demo deployment/host/branch, release family 14.7 with unknown exact maintenance release, and a fixed observation cutoff. Current-record markers are supplied synthetic facts, not inferred by sorting timestamps. The files do not assert funds availability, a posted debit, beneficiary credit or settlement. Empty message/accounting collections have explicit source-coverage reasons.
