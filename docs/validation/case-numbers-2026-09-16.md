# Case numbering validation, 16 September 2026

The user requested shorter saved case numbers and then selected a five-digit daily sequence. The deployed format is `YYYYMMDD` plus five digits, as text, using Asia/Kolkata creation dates. Implementation and compatibility are described in [CASE_NUMBERS](../CASE_NUMBERS.md).

- Backend: clean offline Maven build, **379 tests passed**. Nine numbering tests cover stable backfill, midnight rollover, concurrent allocation/backfill, global uniqueness, rollback, capacity, authorization and create/resume/idempotency behavior. Controller tests exercise number aliases for evidence, notes, investigations and reports, preserving canonical stored bindings. Eight affected controller checks were rerun successfully after the final test cleanup updates.
- Frontend: **374 tests passed**, both TypeScript configurations checked, native GPU bundle staged and deployed. Coverage includes search, number display, alias resolution, legacy URL replacement, report/evidence selections and canonical child requests. The full suite reports two existing jsdom `scrollTo` messages; tests pass.
- Deployment: backed up the stopped database, previous API artifact and web bundle; deployed the tested package and staged web assets; native services restarted successfully. No configuration, bank request or model generation change was required.
- Live preservation: all **seven cases, four evidence versions and seven investigations** match the pre-deployment API baseline except the additive `caseNumber`. Management responses, original export snapshot/three answers, model and knowledge/index/configuration hashes remain unchanged.
- Live aliases: all seven number GETs match their internal-ID GETs; cross-tenant requests return 404; library number searches return exactly the correct case; numbered page routes return HTTP 200. **28 child GET comparisons** confirm equivalent workbench, evidence list, management and evidence configuration across both URL forms.

The user's selected case is now `2026091500002`, retaining its original 15 September creation date. New cases created on 16 September use the `20260916` prefix. The active frontend bundle is `index-DUHvj4Si.js`; API artifact SHA-256 is `eaca21a5ac0a7c31584efdddaefecdde70bc94343cc68f6fb881d22c1d7877b4`.

Private baselines, closed database/application backups, deployment manifest and receipts remain in ignored `runtime/case-number-2026-09-16/`. Live checks used authenticated HTTP, not a new visual browser session. No test case, evidence or model job was created in the active database.
