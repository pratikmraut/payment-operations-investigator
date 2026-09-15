# React payment workbench

React, TypeScript and Vite provide the current payment-case interface. All case data, source records and commands come from the Java API. Operational bank endpoints and credentials belong to the backend's private configuration, never the frontend bundle.

## Local development

From `apps/web`, with the Java API listening on loopback port 8088:

```sh
npm ci
npm run dev
```

Open [the local workbench](http://127.0.0.1:5178/cases). Vite binds loopback and uses strict port selection. It proxies `/api` to the Java backend. Local usernames/password and permissions are documented in the [project README](../../README.md).

```sh
npm test
npm run build
```

The production Docker image serves the build with nginx and proxies `/api` to `api:8088`. Use the root README's explicit base-Compose command for a clean checkout. The optional `vite.gpu.config.ts`/`native-gpu/` entry uses the same application and styles with the separately prepared native API; its private runtime and build artifacts are excluded from Git.

## Pages and operator flow

| Page | What the user does |
| --- | --- |
| `/cases` | Find/import a payment, save an investigation reason, search and paginate saved cases |
| `/payment-cases/:id` | Manage the case, collect evidence, inspect its workbench and export PDF |
| `/evidences` | Find cases with supporting records and inspect their saved evidence versions |
| `/evidences/questions` | Select an authorized case and evidence version, then ask or review a saved question |
| `/knowledge` | Search applicable guidance/status sources and inspect embedding freshness |
| `/system` | Inspect service health and configured runtime capabilities |

The sign-in page is `/`; normal sign-in opens `/cases`, while explicitly opened deep links can be restored. Navigation uses browser-history paths, without hash fragments. The server must return the SPA entry for page routes and preserve JSON/error handling for `/api/`; nginx and Vite are configured accordingly. Old routes exist only for compatibility, not additional visible demo tabs.

The payment form has three methods: exact Reference/UTR and date Inquiry API use the configured inquiry source; Excel upload imports the selected file. The method label follows the selected tab. Bank/branch text entry does not bypass backend scope checks.

Within a case, section navigation leads to management, evidence acquisition and investigation. Evidence can come from an inquiry service, four Excel files or manual/JSON entry. Uploading JSON fills the form; explicit save creates a version. Selecting a tab or suggestion never calls the bank or model automatically.

The workbench presents source observations, raw evidence, saved questions and audit activity. A suggestion fills the editable question field. Running an investigation submits a job for the selected evidence version, shows elapsed time and retains older answers as separate saved jobs. Source timestamps are displayed as supplied; the timeline does not certify an operational payment lifecycle.

## Management and PDF reports

Authorized writers assign owners, change priority, add notes and track evidence requests. An independent reviewer can record a conclusion tied to selected evidence and completed questions. Archive/restore and administrator removal follow separate permissions and explicit confirmation rules.

**Export PDF** opens report choices. The default compact summary supports up to two questions and a three-page limit; the detailed option can include complete citations and raw evidence. Preview fixes the report scope on the server before download. Excerpts are identified; neither mode rewrites saved answers or converts them into approved payment outcomes.

See [Case management](../../docs/CASE_MANAGEMENT.md), [Lifecycle](../../docs/CASE_LIFECYCLE.md), and [PDF reports](../../docs/PAYMENT_CASE_REPORTS.md).

## Interaction and trust boundaries

- HttpOnly cookies carry sessions; the API-returned CSRF token stays in memory and accompanies mutations. The UI supplies no authoritative tenant or role headers.
- Role-aware controls explain read-only access. The server enforces permissions independently of those controls.
- Failed reads preserve existing content where possible, and retries do not silently overwrite operator drafts. Stale asynchronous responses must not replace another case/session's state.
- Navigating away stops browser observation where supported; it does not promise cancellation of accepted backend/model work. Saved jobs can be inspected after returning.
- Evidence versions, cited source documents and reviewer conclusions retain their original binding. New evidence does not silently update an old answer or report.
- The Knowledge library distinguishes current sources from missing/stale embedding coverage. It does not embed, approve or edit knowledge when a page loads.
- Shared styles provide labeled controls, visible keyboard focus, responsive case rows and reduced-motion support. Small-screen layouts preserve source values rather than hiding them through truncation.

Model-written answers are labeled and require factual review. Displayed citations confirm which source was selected, not whether every statement follows from that source.

## Verification

Vitest/Testing Library cover routing, forms, role boundaries, upload handling, errors/retries, saved versions, question timing, management and report choices. TypeScript and Vite check/build the production bundle. These controlled-response tests do not establish live-bank connectivity or model accuracy.

Use an isolated browser context and test data for interaction checks. Do not submit live inquiry, investigation, management or removal commands merely to inspect styling. Shared visual rules are described in [Frontend design](../../docs/FRONTEND_DESIGN.md).
