# Seven-day delivery plan

Delivery window: 11 September 2026 23:38 IST through 18 September 2026 23:38 IST. The days below are delivery milestones, not imposed waiting periods. Move ahead whenever acceptance passes.

Codex owns implementation, original data, tests and documentation. The user supplies product feedback if desired; no manual coding or dataset preparation is expected.

## Current milestone status

Updated 12 September 2026. The original generic-provider portfolio release completed before the end of the delivery window; its receipts remain historical evidence for that release. The subsequent **synthetic OBPM 14.7 outbound NEFT milestone is implemented and deployed locally**, with 52 Java, 280 worker and 44 React tests passed; 14 actual HTTP Replay checks; 14 actual HTTP Ollama checks; and 17 generic regression checks. The live NEFT suite used three investigations and four actual `qwen3:4b-instruct` chat calls in 46.845 seconds. No Oracle connection was used. The [milestone record](validation/obpm-milestone.md) links revision-specific component, HTTP, model and browser evidence.

| Protocol step | Current status | Evidence or remaining work |
| --- | --- | --- |
| Original input and guidance | Complete | Three original snapshots, two original runbooks; deterministic generation and validation |
| Import into the application | Complete | Session-derived tenant, bounded strict JSON, exact INR amount conversion, stable payment identity and import receipts |
| Preserve changing evidence | Complete | Immutable snapshots and investigation inputs, later-cutoff/version checks, duplicate handling and stale-review rejection |
| Investigate with tools and RAG | Complete in Replay and the bounded live sample | Real LangGraph branch, four scoped LangChain OBPM tools, original runbooks filtered by tenant/date/domain/rail/direction/release family |
| Review and inspect results | Complete for the recorded scope | React bank evidence/source coverage/version views; independent Java review, durable decisions, audit and export. Sixteen browser workflow checks plus a separate three-check label/bundle/state recheck |
| Verify the live OBPM model contract | Complete for three synthetic investigations | Model orders all four mandatory tools; service supplies case identity. Complete timeout selects the authorized fact with a second call; two insufficient cases use one call each and no findings. Initial omitted-tool failure is preserved |
| Preserve generic behavior and tested artifacts | Complete for the recorded scope | Seventeen generic HTTP regression checks; selected source/tested/running-artifact correspondence in the component receipt, with local-build limitations |
| Connect an approved Oracle sandbox | Future boundary | Confirm exact maintenance release, approved inquiry/view mapping and an authorized non-production environment with local runbooks |

The tested outcome is a recorded ECA timeout supported by correlated `EC` / `T` evidence, followed by an evidence request. It is not a funds, posting or settlement diagnosis. Current pending or incomplete/unknown evidence yields insufficient evidence. Every OBPM case is prohibited from `RESOLVE_CASE`, and no application action executes an Oracle recovery operation. Dashboard counts describe imported cases. The [live HTTP receipt](validation/obpm-http-ollama.json), [preserved initial failure](validation/obpm-ollama-initial-failure.json), [browser revisions](validation/obpm-browser.json), [generic regression](validation/obpm-legacy-regression.json) and [component correspondence](validation/obpm-components.json) retain the relevant scope; passing them is not independent AI accuracy or a business-impact benchmark.

The user has confirmed the 14.7 family and will create a bank-side inquiry API that may query FCR, FCUBS and OBPM; our application will consume that API. Read-only inspection of older FLEXCUBE source definitions and a 2022 Confluence mapping-spreadsheet preview informed a local metadata reference. Those sources do not establish the installed patch or native OBPM schema. Proprietary source bodies and customer records were not imported into application fixtures. See the [inquiry API plan](OBPM_INQUIRY_API.md) for the remaining mapping work and API-consumer milestone.

Use [the step-by-step walkthrough](OBPM_STEP_BY_STEP.md) to operate the deployed synthetic flow and [the architecture](ARCHITECTURE.md) for its implemented versus future boundaries. Passing rule outcomes or HTTP assertions is not independent AI accuracy or measured operational benefit.

## Original delivery milestones

The subsequent mock inquiry milestone is complete for the existing ECA slice: four original dummy source responses, a local read-only HTTP API, Java consumer/mapper and React fetch/import workflow. [Four actual Replay investigations and 14 HTTP checks](validation/obpm-inquiry-http.json) passed. Follow the [mock walkthrough](OBPM_MOCK_INQUIRY.md). This additional user-requested work does not restart the original delivery automation or establish a real Oracle connection.

The table below preserves the original plan. Current evidence and limitations are recorded above and in [STATUS](STATUS.md), rather than inferred from a planned day or acceptance target.

| Day | Deliverable | Code and data | Documents and flows | Acceptance evidence |
| --- | --- | --- | --- | --- |
| 1 | Product foundation and walking skeleton | React app, Spring API, FastAPI worker, configuration, original fixture generator, local service layout | Product spec, architecture, API contract, ER/state diagrams, dataset card, decision log | All services start; original data regenerates; authenticated case list/detail is usable |
| 2 | Deterministic payments evidence | Durable cases/events/ledger/webhooks, exact reconciliation, dashboard, tenant/role checks, exports | Domain rules, evidence lifecycle, auth flow, API examples | Money and ordering invariants; cross-tenant denials; deterministic seed checks |
| 3 | Versioned retrieval | Original runbooks, ingestion/filtering, lexical baseline, real semantic/hybrid path, source citations | RAG ingestion/retrieval flow, corpus manifest, data provenance | Relevant passage retrieval; date/tenant filters; zero ground-truth leakage |
| 4 | Controlled agent investigation | LangChain typed tools, LangGraph checkpoints, replay and live local-model modes, structured findings, abstention | Agent state flow, tool contracts, error/time-budget handling | Golden cases; provider failure surfaced; invalid citations rejected; genuine local-model run recorded |
| 5 | Reviewable enterprise workflow | Polished React evidence workspace, reviewer decisions, concurrency, idempotency, immutable investigation/audit records | Approval sequence, role matrix, UI flows, accessibility notes | Two-person review; double-submit/replay; stale-version rejection; browser workflow checks |
| 6 | Reliability and evaluation | Frozen development/test split, comparable baselines, fault injection, bounded load checks, injection/isolation tests | Evaluation report, threat model, failure catalogue, operational runbook | Measured accuracy/grounding/abstention; latency/calls; restart/resume; baseline parity |
| 7 | Portfolio release candidate | Integration fixes, clean-start scripts, Docker Compose, versioned release evidence, screenshots/demo scenarios | Final architecture, setup/troubleshooting, demo script, honest resume bullets, release checklist | Fresh-start rehearsal; all required checks; tested feature/limitation matrix; reproducible demo |

## Priority when scope competes with time
Preserve complete investigation, correct money, tenant isolation, citations, review, replay safety and honest evaluation. Defer extra providers, graph databases, fine-tuning, Kubernetes, real payment integration and cloud deployment.

## Documentation required at every major step
Update STATUS.md with implementation and checks actually completed. Record material choices in DECISIONS.md. Update the corresponding Mermaid flow and API contract. Add validation commands, result counts and limitations to a dated entry under docs/validation. Keep a short next-action entry so another session can resume without rebuilding context.

## Completion and continuation
The original delivery continuation was paused after the generic release completed. The subsequent user-authorized synthetic OBPM milestone now has its component, Replay, live-model and browser evidence recorded; this plan does not create or restart an automation. The next integration boundary is the exact installed release, approved mapping and authorized non-production environment. Avoid duplicate services and preserve existing histories when demonstrating the app. Public publishing, purchases and transferring employer/customer records are outside this synthetic milestone.
