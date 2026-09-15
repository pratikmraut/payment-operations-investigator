# Original synthetic NEFT evidence

These files contain original demonstration records for the `obpm-evidence-v1` contract in [the implementation contract](../../docs/OBPM_IMPLEMENTATION_CONTRACT.md). They are not Oracle exports, a direct-table mapping or evidence of a real payment. No employer or customer data was used.

Only `samples/*.json` contains catalog payloads. Each file is a raw snapshot; the server derives catalog metadata and assigns tenant identity from the authenticated user. The sample source is always `SYNTHETIC-OBPM` / `DEMO-HOST` / `DEMO-BRANCH`, with release family `14.7` and an explicitly unknown exact maintenance release.

| File | Source payment | Evidence provided |
| --- | --- | --- |
| [eca-timeout.json](samples/eca-timeout.json) | `DEMO-NEFT-0001` | One current `EC` / `T` queue record matched to its ECA request, with complete queue coverage through 05:20 UTC |
| [evidence-gaps.json](samples/evidence-gaps.json) | `DEMO-NEFT-0002` | Partial queue coverage, unknown entry time and deliberately unmapped synthetic response code `DEMO_UNMAPPED` |
| [eca-timeout-updated.json](samples/eca-timeout-updated.json) | `DEMO-NEFT-0001` | Later 05:40 UTC snapshot: original timeout is historical; a distinct current ECA attempt is `P` / pending |

`DEMO_UNMAPPED` is an invented unsupported test value, not a documented Oracle code. All three snapshots leave native payment status null. Messages are not requested; external-core and accounting evidence are unavailable. A recorded ECA timeout does not establish funds availability, blocking, posting, beneficiary credit or settlement. The later snapshot records a second request but does not establish which user, system operation or business decision caused it.

Use the first sample before its update when demonstrating evidence versions. Reimporting an older changed snapshot after the update should be rejected; do not erase history to replay the sequence. The [walkthrough](../../docs/OBPM_STEP_BY_STEP.md) explains safe repetition.

The two original scoped runbooks live in [obpm-runbooks.json](../knowledge/obpm-runbooks.json). Expected application outcomes are described in documentation, not embedded as labels in these runtime payloads. Neither this generator nor these samples reads the original evaluation labels or changes the original case/runbook files and manifest.

Regenerate with Python 3:

```powershell
python tools/generate_obpm_samples.py
python tools/generate_obpm_samples.py --check
```

With the project's existing Windows worker environment, use `& services/investigator/.venv/Scripts/python.exe` in place of `python`. Run from the project root. The generator validates exact amounts, identity correlation, chronology, coverage and the snapshot relationship; `--check` compares all four generated files without writing. It does not call the application, Oracle, a model or an embedding provider.
