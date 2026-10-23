# Payment Operations Investigator — narration transcript

15-minute edited interview walkthrough. English narration was synthesized locally with Microsoft Zira. The images are genuine captured application screens; selected recorded interactions play at their original speed, with held captures and short cuts for readability. Waiting is condensed. Private identifiers are masked.

Chapter timestamps refer to the video. Spoken narration starts 0.35 seconds after each chapter boundary. Technical spellings are normalized for reading; spoken content is unchanged. This is the completed HQ edition recorded in September 2026; the later consistent-zoom retake was not completed.

## 00:00:00 — 1. A focused investigation workspace

This walkthrough shows Pratik Raut’s Payment Operations Investigator, a local prototype for examining payment records, retaining evidence, and handing an investigation to an independent reviewer.

The example follows a payment where beneficiary receipt needs clarification. The aim is to establish what the available records support, identify what is missing, and preserve the reasoning for another person to inspect.

The React interface brings that work together. A Java API controls access, case state and review commands. A separate Python worker handles the model workflow using local Ollama inference and retrieved guidance.

The demonstration begins as an Analyst, then moves through Reviewer, Viewer and Administrator. Each role has a defined responsibility, and none can execute a payment.

## 00:01:01 — 2. Finding the work that needs attention

The queue gives an operational view of saved investigations. Its counts describe cases and review progress; they do not classify the underlying payments as successful or failed.

An analyst can focus on assigned work, look for investigations awaiting evidence or review, and sort cases by date, case number or priority. Active and archived records have separate views, while search helps return to a particular investigation.

The evidence follow-up panel turns requests into visible work. It shows responsibility, due dates and overdue items, with links back to the relevant case. An empty view simply means there are no matching requests.

These controls help someone resume the right task without confusing a workflow label with a banking outcome.

## 00:02:00 — 3. From a payment inquiry to a saved case

There are three ways to locate a payment. Reference lookup accepts an exact payment reference or UTR. Excel upload supports an existing transaction list, with a template to keep identifiers and dates consistent. The date inquiry screen provides controls for an authorized bank, branch, source date and record limit.

In this recording, the exact-reference lookup finds the selected payment through the configured inquiry source. The date controls are shown without claiming a completed date query. Before opening a case, the analyst checks the returned transaction, amount and source information.

The investigation reason explains the question being asked. Opening the case saves that discovery context. If the same payment already has a case, the application can resume it without replacing the original reason.

Finding a payment is the starting point; its discovery record is not a completed analysis.

## 00:03:10 — 4. Responsibility before interpretation

The case begins with a clear owner, priority and investigation status. Changes require a reason so another person can understand why the work was assigned or moved forward.

Notes preserve observations as an accumulating record. Here, the important distinction is between a reported concern about beneficiary receipt and what the source evidence actually demonstrates.

An evidence request makes the next action concrete. The first request is to attach the four inquiry groups for this payment. That request can later be fulfilled by linking the saved version that contains those records. A separate request for beneficiary-credit confirmation would need its own supporting evidence.

The management tabs also expose review, activity and lifecycle controls. Their purpose is to keep responsibility, evidence collection and decisions visible alongside the technical investigation.

## 00:04:16 — 5. Preserving exactly what was supplied

Evidence collection supports a configured inquiry API, four Excel files, or manual and JSON input. These options converge on the same saved evidence structure.

The Excel route keeps a file for each source group. Manual and JSON input allow records to be prepared and inspected before an explicit save. Loading a form or downloading a draft does not attach evidence to the case.

For this investigation, the configured inquiry supplies the payment, host, history and status groups. A successful save creates a version with its source details and fingerprint. Later collection creates another version rather than rewriting this one.

The coverage display shows returned rows and any limitations. Blank values, source nulls and omitted fields remain distinguishable. Rows in every group do not, by themselves, prove complete evidence or beneficiary credit.

## 00:05:24 — 6. A deliberate question over a defined version

The investigation workbench starts with the selected evidence version. The timeline presents source observations, while raw fields and document links let the analyst inspect what each entry actually contains. This is not automatically a complete payment lifecycle.

Saved investigations and the audit trail retain earlier questions and recorded actions. A suggested question can be edited to fit the case, and text drafts can be kept before submission.

Readiness checks examine saved sources, capacity and knowledge freshness without calling the language model. Coverage shows how many whole source rows will be selected, and how many remain outside the question’s context.

Only an explicit run starts inference. The request becomes a saved job with a visible phase and timer, so the work can be followed after leaving this screen.

## 00:06:27 — 7. Guidance with source and version information

The Knowledge library shows the guidance available to the investigation pipeline. Search and category filters separate interpretation limits, investigation guidance and field-specific status definitions.

Each document retains source and version information. Where the guidance came from a workbook, the displayed sheet and cell references help locate its basis. The index details also identify the embedding model and the version information used to check compatibility.

Current embeddings mean the saved vectors match the current guidance. That does not mean the language model is loaded, or that every interpretation will be correct.

This is retrieval-augmented generation: relevant guidance accompanies the selected evidence. It changes the material supplied to the question; it does not fine-tune the model’s weights.

## 00:07:30 — 8. One evidence store across two views

The Evidence library offers a broader view of saved cases and their evidence. Filters narrow the list by row coverage, source and authorized scope. The preview then opens a particular version, group and row, with field search for closer inspection.

Library browsing reads saved records. It does not silently contact the bank or start model inference.

Evidence Q&A provides another entry into the same case workbench. Selecting the payment and evidence version brings back the saved questions, their results and their original sources. It is not a second answer store.

This connection lets an analyst move from an overview of available evidence to a focused question while keeping the case identity and version visible.

## 00:08:26 — 9. The actual model result and its citation limitation

The new question completed with one local Qwen model call. The server recorded about one hundred and eleven seconds from request to completion. This edited walkthrough condenses that wait.

The saved answer maps three recorded status fields to released, debit, and dispatch in progress. These are source observations. The answer explicitly says they do not establish beneficiary credit.

A citation opens the exact source retained with the answer. That makes review possible, but a valid citation is not a guarantee that every claim is correct. Here, the first claim names Indian rupees while citing case context whose original currency is blank. The payment source row contains the currency. The reviewer records that citation limitation instead of silently rewriting the model's answer.

The preserved unknowns and next checks identify missing status evidence and the unspecified source timezone. An independent beneficiary-side confirmation is still needed. The conclusion remains limited to this saved evidence version.

## 00:09:44 — 10. An independent human decision

The Reviewer signs in separately and opens the case awaiting review. The review is tied to a specific evidence version and, when selected, completed questions for that same version.

The Java service enforces independence. A reviewer cannot review a case they created, evidence they saved, or selected questions they authored. This check belongs to the API as well as the visible controls.

Recording a conclusion preserves the reviewer’s assessment. It does not automatically resolve the case. Resolution is a separate workflow action requiring the latest evidence, a matching independent conclusion, no open evidence requests and no active investigation jobs.

Even when that action is appropriate, it closes the defined investigation scope. It does not confirm that funds reached a beneficiary. If the evidence needs further work, the case can remain open instead.

## 00:10:52 — 11. A report that can be reproduced

Reporting turns the reviewed material into a portable record. The summary format starts with the latest completed question for the selected evidence and keeps the presentation concise. The detailed format includes full selected answers and cited documents, with an optional appendix of raw evidence rows.

Previewing a report creates a saved snapshot. The PDF download uses that same snapshot, including its selected evidence, questions and review status. It does not ask the model to rewrite the answer or make another bank inquiry.

Saved report history allows that original record to be downloaded again after the case changes. A later review or evidence version appears only in a newly prepared report.

This makes the export a traceable record of a particular investigation scope.

## 00:11:52 — 12. Inspection without investigative write access

The Viewer role shows how the same case can be shared for inspection. A viewer can read the source versions, saved answers, notes, requests, reviewer conclusion and activity history.

The interface does not offer the viewer new evidence collection, model-question submission or management changes. Archive and restore are also outside this role.

Reports remain available. A viewer can inspect report options, prepare an allowed snapshot or download a report already saved with the case. The distinction is between reading and exporting the investigation record, and changing the investigation itself.

Evidence Q&A keeps that boundary consistent: existing questions and their sources remain visible, while starting a new question requires an Analyst or Reviewer.

## 00:12:52 — 13. Retention and administrator boundaries

The Administrator demonstrates a different responsibility: case lifecycle management. This role does not inherit the normal powers to collect evidence, ask model questions or record a reviewer conclusion.

Archiving removes a case from the active queue while preserving its number, evidence, answers, notes and reports. Opening it through the archived view keeps those records readable and makes further investigation changes unavailable until restoration.

Permanent removal is a separate administrator action. The form requires a reason and an exact case-number confirmation, and it is available only for an archived case without active jobs. In this walkthrough, the form is opened and cancelled; no case is deleted.

Restoring brings the same record back into active use, with its history intact.

## 00:13:53 — 14. Keeping the remaining question visible

Back as the Analyst, a resolved investigation can be explicitly reopened with a reason when further evidence is needed. For this example, any outstanding request for beneficiary-credit confirmation should remain open until supporting material is actually supplied.

System and health provides a final operational check. It reports the business API and investigation worker, while clearly separating service health from model readiness or bank-endpoint reachability. Retained synthetic dataset counts are also distinguished from the saved payment cases used in this walkthrough.

The result is a connected prototype workflow: discover a payment, preserve source evidence, ask a bounded question, inspect citations, obtain independent review and retain an export. What remains unknown stays visible, and the application leaves the banking decision with the appropriate evidence and people.
