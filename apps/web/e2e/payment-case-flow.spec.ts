import { test, expect, type Page } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

// All records below come from the public synthetic workbook. The Java API is
// real; the provider boundary is an explicit test double, not an AI evaluation.
const reference = (suffix: string) => `DEMO-90002026091400000000000${suffix}`;
const question = "What do the supplied synthetic source records establish?";
const generatedText =
  "The supplied test records leave the payment outcome unknown.";

async function login(page: Page, user = "analyst") {
  await page.goto("/");
  await page.getByLabel("Workspace identity").selectOption(user);
  await page.getByLabel("Password").fill("demo-pass-local");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await expect(page).toHaveURL(/\/cases$/);
}
async function json(page: Page, path: string) {
  const response = await page.request.get(`/api${path}`);
  expect(response.ok(), `${path}: ${await response.text()}`).toBeTruthy();
  return response.json();
}
async function createCase(page: Page, suffix: string, omitCurrency = false) {
  await page.getByRole("button", { name: "Excel upload", exact: true }).click();
  await page.getByLabel("Authorized bank", { exact: true }).fill("760");
  await page.getByLabel("Authorized branch", { exact: true }).fill("1352");
  await page
    .getByLabel("Discovery Excel file")
    .setInputFiles(
      resolve(
        process.cwd(),
        omitCurrency
          ? "e2e/fixtures/payment-discovery-no-currency.xlsx"
          : "public/payment-discovery-template.xlsx",
      ),
    );
  await page.getByRole("button", { name: "Read Excel records" }).click();
  await page
    .getByRole("radio", {
      name: new RegExp(`Select ${reference(suffix)} branch`),
    })
    .check();
  await page
    .getByLabel("Investigation reason", { exact: true })
    .fill(`Browser acceptance ${suffix}: inspect supplied synthetic records.`);
  const saved = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/payment-cases") &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Open or resume case" }).click();
  const response = await saved;
  expect(response.ok(), await response.text()).toBeTruthy();
  await expect(page).toHaveURL(/\/payment-cases\/\d{13}$/);
  const caseNumber = new URL(page.url()).pathname.split("/").pop()!;
  const item = await json(page, `/payment-cases/${caseNumber}`);
  await expect(
    page.getByRole("heading", { name: "Case evidence", exact: true }),
  ).toBeVisible();
  return {
    id: item.id as string,
    number: caseNumber,
    path: `/payment-cases/${caseNumber}`,
  };
}
async function saveEvidence(page: Page, id: string) {
  const discovery = await json(page, `/payment-cases/${id}`);
  const config = await json(page, `/payment-cases/${id}/evidence/config`);
  const value = config.template;
  value.sourceTimezone = "UTC";
  const row: Record<string, string> = Object.fromEntries(
    config.groups
      .find((group: { key: string }) => group.key === "PAYMENT")
      .columns.map((column: string) => [column, ""]),
  );
  Object.assign(row, {
    SOURCE_TABLE: "PM_NEFT_TXN_LOG",
    QUERY_OBSERVED_AT: "2026-09-14T10:00:00Z",
    SCOPE_ROW_COUNT: "1",
    REFTXNNUMBER: value.payment.reference,
    NUMAMOUNT_4038: discovery.amount,
    CODCURR: "INR",
    DATINITIATION: "2026-09-14T09:15:00",
    DIRECTION: "OUT",
  });
  // Only configured columns are included; the template and scope come from Java.
  value.sections.PAYMENT.rows = [
    Object.fromEntries(
      config.groups
        .find((group: { key: string }) => group.key === "PAYMENT")
        .columns.map((column: string) => [column, row[column]]),
    ),
  ];
  await page.getByRole("tab", { name: "Manual + JSON", exact: true }).click();
  await page.getByLabel("Upload JSON to fill the form").setInputFiles({
    name: "synthetic-case-evidence.json",
    mimeType: "application/json",
    buffer: Buffer.from(JSON.stringify(value)),
  });
  await expect(
    page.getByText(/Loaded synthetic-case-evidence.json/),
  ).toBeVisible();
  await page.getByRole("button", { name: "Save new evidence version" }).click();
  await expect(
    page.getByText(
      "Evidence version 1 saved. Previous versions remain unchanged.",
    ),
  ).toBeVisible();
}
async function status(page: Page, target: string, reason: string) {
  await page.getByRole("tab", { name: "Overview", exact: true }).click();
  await page.getByLabel("Next investigation status").selectOption(target);
  await page.getByLabel("Reason for status change").fill(reason);
  if (target === "RESOLVED")
    await page
      .getByLabel("Reviewer conclusion for latest evidence")
      .selectOption({ index: 1 });
  const changed = page.waitForResponse(
    (response) =>
      response.url().endsWith("/workflow") &&
      response.request().method() === "POST",
  );
  await page
    .getByRole("button", {
      name:
        target === "INVESTIGATING" &&
        (await page
          .getByRole("heading", { name: "Reopen investigation", exact: true })
          .count())
          ? "Reopen investigation"
          : "Save investigation status",
      exact: true,
    })
    .click();
  const response = await changed;
  expect(response.ok(), await response.text()).toBeTruthy();
  expect((await response.json()).status).toBe(target);
  await expect(page.getByLabel("Reason for status change")).toHaveValue("");
}

test("current case flow: Excel discovery, evidence, question, independent review, PDF history and lifecycle", async ({
  page,
  browser,
}) => {
  test.setTimeout(180_000);
  await login(page);
  const item = await createCase(page, "1", true);
  const original = await json(page, `/payment-cases/${item.id}`);
  expect(original.currency).toBeNull();
  await expect(
    page.getByText(`${original.amount} INR`, {
      exact: true,
    }),
  ).toBeVisible();
  await saveEvidence(page, item.id);
  await expect(
    page.getByText(`${original.amount} INR`, {
      exact: true,
    }),
  ).toBeVisible();
  const enriched = await json(page, `/payment-cases/${item.id}`);
  expect(enriched.currency).toBeNull();
  expect(enriched.amount).toBe(original.amount);
  expect(enriched.evidenceCurrency).toMatchObject({
    currency: "INR",
    version: 1,
    sourceKind: "JSON",
  });
  await page.locator(".payment-case-detail").screenshot({ path: resolve(process.cwd(), "../../runtime/implementation-wave2-2026-09-18/selected-payment.png") });
  await page
    .getByRole("link", { name: "Back to payment cases", exact: true })
    .click();
  await page.getByLabel("Search saved payment cases").fill(item.number);
  const savedCase = page.getByRole("region", {
    name: "Saved payment cases",
    exact: true,
  });
  await expect(
    savedCase.getByText("INR", { exact: true }),
  ).toBeVisible();
  await savedCase.screenshot({ path: resolve(process.cwd(), "../../runtime/implementation-wave2-2026-09-18/saved-case-currency.png") });
  await page
    .getByRole("button", { name: "Refresh queue", exact: true })
    .click();
  await expect(
    savedCase.getByText("INR", { exact: true }),
  ).toBeVisible();
  await page.goto("/evidences");
  await page.getByLabel("Search cases and payments").fill(item.number);
  await page.getByText("Case context", { exact: true }).click();
  await expect(
    page.getByText(`${original.amount} INR`, {
      exact: true,
    }),
  ).toBeVisible();
  await page.goto(`/evidences/questions/${item.number}`);
  await page
    .getByText("Payment details and investigation reason", { exact: true })
    .click();
  await expect(
    page.getByText(`${original.amount} INR`, {
      exact: true,
    }),
  ).toBeVisible();
  await page.goto(item.path);
  await expect(
    page.getByText(`${original.amount} INR`, {
      exact: true,
    }),
  ).toBeVisible();
  await page.getByLabel("Your case question").fill(question);
  await page
    .getByRole("button", { name: "Run investigation", exact: true })
    .click();
  await expect(
    page
      .getByRole("region", { name: "Selected investigation", exact: true })
      .getByText(generatedText, { exact: true }),
  ).toBeVisible({ timeout: 45_000 });
  await page.getByRole("tab", { name: "Notes", exact: true }).click();
  await page
    .getByLabel("New case note")
    .fill(
      "Synthetic browser acceptance: source records inspected; outcome remains unknown.",
    );
  await page.getByRole("button", { name: "Add note", exact: true }).click();
  await expect(page.getByLabel("New case note")).toHaveValue("");
  await status(page, "INVESTIGATING", "Begin source evidence review.");
  await status(
    page,
    "AWAITING_REVIEW",
    "Source inspection complete; independent review requested.",
  );
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await login(page, "reviewer");
  await page.goto(item.path);
  await page
    .getByRole("tab", { name: "Reviewer conclusion", exact: true })
    .click();
  await page.getByLabel("Review evidence version").selectOption({ index: 1 });
  await page
    .getByRole("checkbox", {
      name: new RegExp(question.replace(/[?]/g, "\\?")),
    })
    .check();
  await page
    .getByRole("textbox", { name: "Reviewer conclusion", exact: true })
    .fill(
      "Reviewed the synthetic evidence and cited answer independently. Payment outcome remains unknown; this investigation review is complete.",
    );
  await page
    .getByRole("button", { name: "Record reviewer conclusion" })
    .click();
  await expect(
    page.getByRole("textbox", { name: "Reviewer conclusion", exact: true }),
  ).toHaveValue("");
  await status(
    page,
    "RESOLVED",
    "Independent review recorded for the latest evidence.",
  );
  await expect(
    page.getByText(
      /This case is resolved. Reopen it in Case management before adding evidence/,
    ),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Run investigation", exact: true }),
  ).toHaveCount(0);

  await page.getByRole("button", { name: "Export PDF", exact: true }).click();
  const report = page.getByRole("region", {
    name: "Export case report",
    exact: true,
  });
  await report
    .getByRole("button", { name: "Preview report", exact: true })
    .click();
  await expect(
    report.getByRole("heading", { name: "Saved report preview" }),
  ).toBeVisible();
  await expect(
    report.getByText("Recorded for this snapshot", { exact: false }),
  ).toBeVisible();
  const list = await json(page, `/payment-cases/${item.id}/reports`);
  expect(list.items).toHaveLength(1);
  const frozen = list.items[0];
  expect(frozen.reviewStatus).toBe("RECORDED");
  const downloaded = page.waitForEvent("download");
  await report
    .getByRole("button", {
      name: `Download saved report ${frozen.reportId}`,
      exact: true,
    })
    .click();
  const bytes = await readFile((await (await downloaded).path())!);
  expect(bytes.subarray(0, 5).toString()).toBe("%PDF-");
  expect(bytes.length).toBeGreaterThan(1000);
  await report
    .getByRole("button", { name: "Close report", exact: true })
    .click();
  await status(
    page,
    "INVESTIGATING",
    "Reopened to demonstrate controlled case follow-up.",
  );
  await page.getByRole("tab", { name: "Case lifecycle", exact: true }).click();
  await page
    .getByLabel("Reason for archiving")
    .fill("Archive synthetic browser acceptance case.");
  await page.getByRole("button", { name: "Archive case", exact: true }).click();
  await expect(
    page.getByText(new RegExp(`Case ${item.number} archived`)),
  ).toBeVisible();
  await page
    .getByLabel("Reason for restoring")
    .fill("Restore synthetic browser acceptance case.");
  await page.getByRole("button", { name: "Restore case", exact: true }).click();
  await expect(
    page.getByText(new RegExp(`Case ${item.number} restored`)),
  ).toBeVisible();
  const savedAgain = await json(page, `/payment-cases/${item.id}/reports`);
  expect(savedAgain.items[0].reportHash).toBe(frozen.reportHash);
  expect(savedAgain.items[0].reviewStatus).toBe("RECORDED");
  const workbench = await json(page, `/payment-cases/${item.id}/workbench`);
  expect(workbench.status).toBe("INVESTIGATING");
  expect(workbench.evidence).toHaveLength(1);
  expect(workbench.investigations).toHaveLength(1);

  // Independent read-only session, direct clean-path load and server protection.
  const viewerContext = await browser.newContext({
    baseURL: "http://127.0.0.1:19091",
  });
  const viewer = await viewerContext.newPage();
  await login(viewer, "viewer");
  await viewer.goto(item.path);
  await expect(viewer.getByLabel("Your case question")).toHaveAttribute(
    "readonly",
    "",
  );
  await expect(
    viewer.getByRole("button", { name: "Run investigation", exact: true }),
  ).toHaveCount(0);
  const session = await json(viewer, "/auth/me");
  const rejected = await viewer.request.post(
    `/api/payment-cases/${item.id}/notes`,
    {
      headers: {
        "X-CSRF-Token": session.csrfToken,
        "Idempotency-Key": "browser-viewer-note-rejection",
      },
      data: { expectedVersion: 0, text: "Unauthorized fixture command" },
    },
  );
  expect(rejected.status()).toBe(403);
  await viewerContext.close();
});

test("dirty evidence survives canceled navigation and browser Back; explicit draft restores after leaving", async ({
  page,
}) => {
  await login(page);
  const item = await createCase(page, "2");
  await page.getByRole("tab", { name: "Manual + JSON", exact: true }).click();
  await page.getByLabel("Source timezone", { exact: true }).fill("UTC+05:30");
  const downloaded = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download form draft" }).click();
  const download = await downloaded;
  const draftBytes = await readFile((await download.path())!);
  page.once("dialog", (dialog) => dialog.dismiss());
  await page
    .getByRole("link", { name: "Knowledge library", exact: true })
    .click();
  await expect(page).toHaveURL(new RegExp(`${item.path}$`));
  await expect(page.getByLabel("Source timezone", { exact: true })).toHaveValue(
    "UTC+05:30",
  );
  const backDialog = page.waitForEvent("dialog");
  await page.evaluate(() => window.history.back());
  await (await backDialog).dismiss();
  await expect(page).toHaveURL(new RegExp(`${item.path}$`));
  await expect(page.getByLabel("Source timezone", { exact: true })).toHaveValue(
    "UTC+05:30",
  );
  page.once("dialog", (dialog) => dialog.accept());
  await page
    .getByRole("link", { name: "Knowledge library", exact: true })
    .click();
  await expect(page).toHaveURL(/\/knowledge$/);
  await page.goto(item.path);
  await page.getByRole("tab", { name: "Manual + JSON", exact: true }).click();
  await expect(page.getByLabel("Source timezone", { exact: true })).toHaveValue(
    "UNKNOWN",
  );
  await page.getByLabel("Upload JSON to fill the form").setInputFiles({
    name: download.suggestedFilename(),
    mimeType: "application/json",
    buffer: draftBytes,
  });
  await expect(page.getByLabel("Source timezone", { exact: true })).toHaveValue(
    "UTC+05:30",
  );
  const evidence = await json(page, `/payment-cases/${item.id}/evidence`);
  expect(evidence.items).toHaveLength(0);
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Open workspace" }),
  ).toBeVisible();
});

test("assigned overdue evidence requests appear in the current user's follow-up queue", async ({
  page,
}) => {
  await login(page);
  const item = await createCase(page, "3");
  await page
    .getByRole("tab", { name: "Evidence requests", exact: true })
    .click();
  await page
    .getByLabel("Evidence request title")
    .fill("Synthetic source confirmation request");
  await page
    .getByLabel("Evidence needed and reason")
    .fill("Obtain the source confirmation for this isolated browser fixture.");
  await page.getByLabel("Due date (optional)").fill("2020-01-01");
  await page.getByLabel("Evidence request assignee").selectOption("analyst");
  await page
    .getByRole("button", { name: "Add evidence request", exact: true })
    .click();
  await expect(page.getByLabel("Evidence request title")).toHaveValue("");
  await page
    .getByRole("link", { name: "Back to payment cases", exact: true })
    .click();
  await page.getByText("Evidence follow-ups", { exact: false }).click();
  await page.getByLabel("Follow-up view").selectOption("MINE");
  await expect(
    page.getByRole("cell", {
      name: "Synthetic source confirmation request",
      exact: true,
    }),
  ).toBeVisible();
  await expect(page.getByText(/1 evidence request is overdue/)).toBeVisible();
  const mine = await json(
    page,
    "/payment-case-work?view=MINE&offset=0&limit=10",
  );
  expect(mine.items).toHaveLength(1);
  expect(mine.items[0]).toMatchObject({
    caseId: item.id,
    assignee: { id: "analyst" },
  });
  expect(mine.items[0].daysOverdue).toBeGreaterThan(0);
  await page.getByLabel("Follow-up view").selectOption("OVERDUE");
  await expect(
    page.getByRole("cell", {
      name: "Synthetic source confirmation request",
      exact: true,
    }),
  ).toBeVisible();
});

test("database case search pages and sorts beyond ten records; Q&A keeps a deep-selected draft", async ({
  page,
}) => {
  test.setTimeout(120_000);
  await login(page);
  const session = await json(page, "/auth/me");
  const config = await json(page, "/payment-discovery/config");
  const scope = config.scopes[0];
  const created: { id: string; caseNumber: string; reference: string }[] = [];
  // Controlled setup uses the isolated server's original synthetic catalog.
  // No bank API, model, native database or private record is contacted.
  for (const daysAgo of [1, 2]) {
    const date = new Date(`${config.today}T12:00:00Z`);
    date.setUTCDate(date.getUTCDate() - daysAgo);
    const batchResponse = await page.request.post(
      "/api/payment-discovery/search",
      {
        headers: { "X-CSRF-Token": session.csrfToken },
        data: {
          orgBank: scope.orgBank,
          orgBranch: scope.orgBranch,
          inquiryDate: date.toISOString().slice(0, 10),
          recordCount: 20,
        },
      },
    );
    expect(batchResponse.ok(), await batchResponse.text()).toBeTruthy();
    const batch = await batchResponse.json();
    expect(batch.items).toHaveLength(6);
    for (const candidate of batch.items) {
      const response = await page.request.post("/api/payment-cases", {
        headers: {
          "X-CSRF-Token": session.csrfToken,
          "Idempotency-Key": `browser-pagination-${daysAgo}-${created.length}`,
        },
        data: {
          candidateId: candidate.candidateId,
          reason: `Pagination acceptance record ${created.length + 1}: synthetic query fixture.`,
        },
      });
      expect(response.ok(), await response.text()).toBeTruthy();
      created.push((await response.json()).item);
    }
  }
  const queue = page.getByRole("region", {
    name: "Saved payment cases",
    exact: true,
  });
  await page.getByRole("button", { name: "Refresh queue" }).click();
  await page
    .getByLabel("Search saved payment cases")
    .fill("Pagination acceptance");
  await expect(
    queue.getByText("Showing 1–10 of 12 matching cases", { exact: true }),
  ).toBeVisible();
  await expect(queue.getByRole("row")).toHaveCount(11);
  await queue.screenshot({
    path: resolve(
      process.cwd(),
      "../../runtime/implementation-wave2-2026-09-18/queue-pagination.png",
    ),
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByLabel("Sort cases")).toBeVisible();
  await expect(page.getByLabel("Search saved payment cases")).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth + 1,
    ),
  ).toBe(true);
  await queue.screenshot({
    path: resolve(
      process.cwd(),
      "../../runtime/implementation-wave2-2026-09-18/queue-pagination-mobile.png",
    ),
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await queue.getByRole("button", { name: "Next page", exact: true }).click();
  await expect(
    queue.getByText("Showing 11–12 of 12 matching cases", { exact: true }),
  ).toBeVisible();
  await expect(queue.getByRole("row")).toHaveCount(3);
  const sortResponse = page.waitForResponse(
    (response) =>
      response.url().includes("/api/payment-cases?") &&
      response.url().includes("sort=CASE_NUMBER_ASC"),
  );
  await page.getByLabel("Sort cases").selectOption("CASE_NUMBER_ASC");
  const sorted = await (await sortResponse).json();
  expect(sorted).toMatchObject({
    page: 1,
    pageSize: 10,
    total: 12,
    totalPages: 2,
    sort: "CASE_NUMBER_ASC",
  });
  expect(sorted.items).toHaveLength(10);
  expect(sorted.items[0].id).toBe(created[0].id);
  await expect(queue.getByRole("row").nth(1)).toContainText(
    created[0].caseNumber,
  );
  await page
    .getByLabel("Search saved payment cases")
    .fill(created[11].reference);
  await expect(
    queue.getByText("Showing 1–1 of 1 matching cases", { exact: true }),
  ).toBeVisible();
  await expect(queue.getByRole("row")).toHaveCount(2);
  await page.getByLabel("Search saved payment cases").fill("literal_%_absent");
  await expect(
    queue.getByRole("heading", { name: "No matching cases", exact: true }),
  ).toBeVisible();

  const selected = created[0];
  await page.goto(`/payment-cases/${selected.caseNumber}`);
  await saveEvidence(page, selected.id);
  await page.goto(`/evidences/questions/${selected.caseNumber}`);
  await page
    .getByLabel("Your case question")
    .fill("Keep this unfinished question while I search other cases.");
  await expect(page.getByLabel("Payment case", { exact: true })).toHaveValue(
    selected.caseNumber,
  );
  await expect(
    page.getByRole("option", { name: /Current selection outside this page/ }),
  ).toHaveCount(1);
  await page
    .getByLabel("Search saved cases", { exact: true })
    .fill("Pagination acceptance");
  await expect(
    page.getByText("Page 1 of 2 · 10 per page", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Next cases", exact: true }).click();
  await expect(
    page.getByText("Page 2 of 2 · 10 per page", { exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("Your case question")).toHaveValue(
    "Keep this unfinished question while I search other cases.",
  );
  page.once("dialog", (dialog) => dialog.dismiss());
  await page
    .getByLabel("Payment case", { exact: true })
    .selectOption(created[1].caseNumber);
  await expect(page).toHaveURL(
    new RegExp(`/evidences/questions/${selected.caseNumber}(?:/[^/]+)?$`),
  );
  await expect(page.getByLabel("Your case question")).toHaveValue(
    "Keep this unfinished question while I search other cases.",
  );
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Open workspace" }),
  ).toBeVisible();
});

test("older evidence stays selected across history pages, readiness is read-only, and cancellation survives reload", async ({ page }) => {
  test.setTimeout(150_000);
  await login(page);
  const session = await json(page, "/auth/me");
  const config = await json(page, "/payment-discovery/config");
  const scope = config.scopes[0];
  const date = new Date(`${config.today}T12:00:00Z`);
  date.setUTCDate(date.getUTCDate() - 3);
  const batchResponse = await page.request.post("/api/payment-discovery/search", {
    headers: { "X-CSRF-Token": session.csrfToken },
    data: { orgBank: scope.orgBank, orgBranch: scope.orgBranch, inquiryDate: date.toISOString().slice(0, 10), recordCount: 20 },
  });
  expect(batchResponse.ok(), await batchResponse.text()).toBeTruthy();
  const batch = await batchResponse.json();
  const created = await page.request.post("/api/payment-cases", {
    headers: { "X-CSRF-Token": session.csrfToken, "Idempotency-Key": "browser-durable-history-case" },
    data: { candidateId: batch.items[0].candidateId, reason: "Synthetic durable job and history acceptance." },
  });
  expect(created.ok(), await created.text()).toBeTruthy();
  const item = (await created.json()).item;
  const base = `/payment-cases/${item.id}`;
  await page.goto(`/payment-cases/${item.caseNumber}`);
  await saveEvidence(page, item.id);
  const first = (await json(page, `${base}/evidence`)).items[0];
  const original = await json(page, `${base}/evidence/${first.id}`);
  for (let version = 2; version <= 12; version++) {
    const response = await page.request.post(`/api${base}/evidence/manual`, {
      headers: { "X-CSRF-Token": session.csrfToken, "Idempotency-Key": `browser-history-version-${version}` },
      data: original.payload,
    });
    expect(response.ok(), await response.text()).toBeTruthy();
  }
  const versions = await json(page, `${base}/evidence`);
  expect(versions.items).toHaveLength(10);
  expect(versions.total).toBe(12);
  expect(versions.items.some((entry: { id: string }) => entry.id === first.id)).toBe(false);
  await page.goto(`/evidences/questions/${item.caseNumber}/${first.id}`);
  await expect(page.getByLabel("Investigation evidence version", { exact: true })).toHaveValue(first.id);
  const draft = "Keep this synthetic job waiting so I can cancel it explicitly.";
  await page.getByLabel("Your case question").fill(draft);
  const checked = page.waitForResponse(response => response.url().endsWith("/investigations/readiness"));
  await page.getByRole("button", { name: "Check readiness", exact: true }).click();
  const readiness = await (await checked).json();
  expect(readiness).toMatchObject({ ready: true, evidenceId: first.id, modelAvailabilityChecked: false, modelContextChecked: false });
  expect((await json(page, `${base}/investigations`)).total).toBe(0);
  await page.getByRole("button", { name: "Load more evidence versions", exact: true }).click();
  await expect(page.getByLabel("Investigation evidence version", { exact: true })).toHaveValue(first.id);
  await expect(page.getByLabel("Your case question")).toHaveValue(draft);
  const submitted = page.waitForResponse(response => response.url().endsWith("/investigations") && response.request().method() === "POST");
  await page.getByRole("button", { name: "Run investigation", exact: true }).click();
  const job = await (await submitted).json();
  await expect.poll(async () => (await json(page, `${base}/investigations/${job.id}`)).phase).toBe("GENERATING");
  await page.reload();
  await expect(page.getByRole("button", { name: "Cancel investigation", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Cancel investigation", exact: true }).click();
  await expect.poll(async () => (await json(page, `${base}/investigations/${job.id}`)).status).toBe("CANCELLED");
  const cancelled = await json(page, `${base}/investigations/${job.id}`);
  expect(cancelled.answer).toBeUndefined();
  expect(cancelled.evidenceId).toBe(first.id);
  expect((await json(page, `${base}/evidence/${first.id}`))).toEqual(original);
  await expect(page.getByText("Cancelled", { exact: true }).first()).toBeVisible();
  await expect(page.getByRole("button", { name: "Cancellation requested", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Cancel investigation", exact: true })).toHaveCount(0);
  await page.locator(".case-investigation").screenshot({ path: resolve(process.cwd(), "../../runtime/implementation-wave2-2026-09-18/history-cancel.png") });
});
