import { afterEach, describe, expect, it, vi } from "vitest";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { CaseEvidence } from "./CaseEvidence";
import { setCsrfToken } from "./api";

const groups = ["PAYMENT", "HOST", "HISTORY", "STATUS"] as const;
type Group = (typeof groups)[number];
const columns = {
  PAYMENT: [
    "REFTXNNUMBER",
    "COD_ORG_BANK",
    "COD_ORG_BRN",
    "AMOUNT",
    "STATUS_VALUE",
  ],
  HOST: ["REF_TXN_NO", "COD_ORG_BANK", "COD_ORG_BRN", "MSG_STAT"],
  HISTORY: ["REF_TXN_NO", "COD_ORG_BANK", "COD_ORG_BRN", "DAT_TXN", "MSG_STAT"],
  STATUS: ["REF_TXN_NO", "COD_ORG_BANK", "COD_ORG_BRN", "STATUS_DESCRIPTION"],
};
function config(reference = "ORIGINAL-FIXTURE-001") {
  return {
    schemaVersion: "fcr-case-evidence-v1",
    api: { enabled: false, mode: "DISABLED" },
    limits: { maxRowsPerSection: 500, maxFileBytes: 5242880 },
    groups: groups.map((key) => ({
      key,
      functionName: `FIXTURE_${key}_INQUIRY`,
      columns: columns[key],
    })),
    template: {
      schemaVersion: "fcr-case-evidence-v1",
      payment: { reference, orgBank: "009", orgBranch: "0012" },
      sourceTimezone: "UNKNOWN",
      sections: Object.fromEntries(
        groups.map((key) => [
          key,
          { rows: [] as Record<string, string>[], note: "" },
        ]),
      ) as Record<Group, { rows: Record<string, string>[]; note: string }>,
    },
  };
}
function payload(reference = "ORIGINAL-FIXTURE-001") {
  const value = config(reference).template;
  value.sections.PAYMENT.rows = [
    {
      REFTXNNUMBER: reference,
      COD_ORG_BANK: "009",
      COD_ORG_BRN: "0012",
      AMOUNT: "123456789012345678901.0007",
      STATUS_VALUE: "00",
    },
  ];
  return value;
}
function snapshot(version = 1, caseId = "CASE-ORIGINAL-A", value = payload()) {
  return {
    id: `EVIDENCE-ORIGINAL-${caseId}-${version}`,
    version,
    caseId,
    sourceKind: "MANUAL",
    createdAt: `2026-09-14T10:00:0${version}Z`,
    createdBy: "Original fixture analyst",
    evidenceHash: `original-evidence-hash-${version}`,
    warnings: ["Original fixture: source completion is unverified."],
    payload: value,
    coverage: Object.fromEntries(
      groups.map((key) => [
        key,
        { rowCount: value.sections[key].rows.length, completion: "UNVERIFIED" },
      ]),
    ),
  };
}

it("reads PO02 saved evidence with unverified coverage and distinguishes source nulls from blanks", async () => {
  const value = payload();
  value.sections.PAYMENT.rows[0].STATUS_VALUE = "";
  value.sections.PAYMENT.rows[0].AMOUNT = "";
  const saved = {
    ...snapshot(1, "CASE-ORIGINAL-A", value),
    sourceKind: "BANK_API",
    upstream: {
      schemaVersion: "flexcube-neft-evidence-v1",
      receivedAt: "2026-09-14T10:10:00Z",
      request: {
        args0: {
          serviceCode: "PO02",
          externalReferenceNo: "ORIGINAL-PO02-CORRELATION",
        },
        args1: {},
      },
      rawResponse: {
        neftPaymentEvidenceDetails: [{ statusValue: null, amount: "" }],
      },
      nullFields: [{ group: "PAYMENT", rowIndex: 1, field: "STATUS_VALUE" }],
    },
  };
  const { fetcher } = mockApi({ history: [saved] });
  await openPage(false);
  await screen.findByText("(source null)");
  expect(screen.getByText("(blank)")).toBeInTheDocument();
  expect(screen.getAllByText("Query fetch unverified")).toHaveLength(4);
  expect(screen.getByText("ORIGINAL-PO02-CORRELATION")).toBeInTheDocument();
  expect(posts(fetcher)).toHaveLength(0);
});
it("renders a sparse PO02 v2 saved response with absent, null and blank source fields", async () => {
  const row = {
    SOURCE_TABLE: "PM_NEFT_TXN_LOG",
    QUERY_OBSERVED_AT: "2026-09-14T15:40:00+05:30",
    SCOPE_ROW_COUNT: "1",
    REFTXNNUMBER: "ORIGINAL-FIXTURE-001",
    NUMAMOUNT_4038: "125.005",
    N10_MSGID: "",
    IDRELATEDREF_2006: "",
    IDMSGREFERENCE_2020: "",
  };
  const value = config().template;
  value.sections.PAYMENT.rows = [row];
  const configured = config();
  configured.groups.find((group) => group.key === "PAYMENT")!.columns =
    Object.keys(row);
  const saved = {
    ...snapshot(1, "CASE-ORIGINAL-A", value),
    sourceKind: "BANK_API",
    upstream: {
      schemaVersion: "flexcube-neft-evidence-v2",
      receivedAt: "2026-09-14T10:10:01Z",
      request: {
        args0: {
          serviceCode: "PO02",
          externalReferenceNo: "ORIGINAL-SPARSE-PO02",
        },
        args1: { referenceTransactionNumber: "ORIGINAL-FIXTURE-001" },
      },
      rawResponse: {
        neftPaymentEvidenceDetails: [
          {
            sourceTable: row.SOURCE_TABLE,
            queryObservedAt: row.QUERY_OBSERVED_AT,
            scopeRowCount: "1",
            refTxnNumber: row.REFTXNNUMBER,
            numAmount4038: "125.005",
            idRelatedRef2006: null,
            idMsgReference2020: "",
          },
        ],
        neftHostEvidenceDetails: [],
        neftHistoryEvidenceDetails: [],
        neftStatusEvidenceDetails: [],
      },
      nullFields: [
        { group: "PAYMENT", rowIndex: 1, field: "IDRELATEDREF_2006" },
      ],
      omittedFields: [{ group: "PAYMENT", rowIndex: 1, field: "N10_MSGID" }],
    },
  };
  const { fetcher } = mockApi({ history: [saved], configured });
  await openPage(false);
  await screen.findByText("Not supplied");
  expect(screen.getByText("(source null)")).toBeInTheDocument();
  expect(screen.getByText("(blank)")).toBeInTheDocument();
  expect(screen.getByText("125.005")).toBeInTheDocument();
  expect(
    screen.getByText("Fields not supplied").parentElement,
  ).toHaveTextContent("1");
  expect(
    screen.queryByText(/provenance does not match/),
  ).not.toBeInTheDocument();
  expect(posts(fetcher)).toHaveLength(0);
});
function summary(value: ReturnType<typeof snapshot>) {
  const { payload: _payload, ...result } = value;
  return result;
}
const reply = (body: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(body), { status }));
type Options = {
  holdHistoryAfterSave?: boolean;
  historyFailuresAfterSave?: number;
  holdHistoryRetry?: boolean;
  matches?: Record<string, unknown>[];
  history?: ReturnType<typeof snapshot>[];
  configured?: ReturnType<typeof config>;
  failures?: number;
  hold?: boolean;
  holdDetail?: string;
  detailFailures?: number;
};
function mockApi(options: Options = {}) {
  let items = options.history ?? [];
  let failed = 0;
  let failedDetails = 0;
  let successfulSaves = 0;
  let historyRefreshes = 0;
  let pending: (() => void) | undefined;
  let pendingDetail: (() => void) | undefined;
  let pendingHistory: (() => void) | undefined;
  const fetcher = vi.fn((url: string, request: RequestInit) => {
    if (url === "/api/payment-cases")
      return reply({ items: options.matches ?? [] });
    const path =
      /^\/api\/payment-cases\/(CASE-ORIGINAL-[AB])\/evidence(.*)$/.exec(url);
    if (!path) throw new Error(`Unexpected endpoint ${url}`);
    const [, caseId, suffix] = path;
    const reference =
      caseId === "CASE-ORIGINAL-A"
        ? "ORIGINAL-FIXTURE-001"
        : "ORIGINAL-FIXTURE-002";
    if (request.method === "POST") {
      if (failed++ < (options.failures ?? 0))
        return reply(
          {
            message: "Fixture transport failed.",
            requestId: "original-request-failure",
          },
          503,
        );
      const body =
        typeof request.body === "string" && suffix !== "/inquiry"
          ? JSON.parse(request.body)
          : payload(reference);
      const saved = {
        ...snapshot(items.length + 1, caseId, body),
        sourceKind: suffix.slice(1).toUpperCase(),
      };
      const finish = () => {
        items = [saved, ...items];
        successfulSaves++;
        return new Response(JSON.stringify(saved));
      };
      if (options.hold)
        return new Promise<Response>((resolve) => {
          pending = () => resolve(finish());
        });
      return Promise.resolve(finish());
    }
    if (suffix === "/config")
      return reply(options.configured ?? config(reference));
    if (suffix === "" && successfulSaves) {
      historyRefreshes++;
      if (options.holdHistoryAfterSave)
        return new Promise<Response>((_resolve, reject) => {
          request.signal?.addEventListener(
            "abort",
            () => reject(new DOMException("Aborted", "AbortError")),
            { once: true },
          );
        });
      if (historyRefreshes <= (options.historyFailuresAfterSave ?? 0))
        return reply(
          { message: "Fixture saved versions could not be refreshed." },
          503,
        );
      if (options.holdHistoryRetry && historyRefreshes === 2) {
        const result = {
          items: items.filter((item) => item.caseId === caseId).map(summary),
        };
        return new Promise<Response>((resolve) => {
          pendingHistory = () => resolve(new Response(JSON.stringify(result)));
        });
      }
    }
    if (suffix === "")
      return reply({
        items: items.filter((item) => item.caseId === caseId).map(summary),
      });
    const item = items.find((item) => item.id === suffix.slice(1));
    if (!item) throw new Error(`Unknown fixture version ${suffix}`);
    if (failedDetails++ < (options.detailFailures ?? 0))
      return reply(
        { message: "Fixture saved version could not be read." },
        503,
      );
    if (options.holdDetail === item.id)
      return new Promise<Response>((resolve) => {
        pendingDetail = () => resolve(new Response(JSON.stringify(item)));
      });
    return reply(item);
  });
  vi.stubGlobal("fetch", fetcher);
  return {
    fetcher,
    finish: () => pending?.(),
    finishDetail: () => pendingDetail?.(),
    finishHistory: () => pendingHistory?.(),
  };
}
async function openPage(canWrite = true, onSaved = vi.fn()) {
  const view = render(
    <CaseEvidence
      caseId="CASE-ORIGINAL-A"
      canWrite={canWrite}
      onSaved={onSaved}
    />,
  );
  await screen.findByRole("heading", { name: "Case evidence" });
  return { ...view, onSaved };
}
const posts = (fetcher: ReturnType<typeof mockApi>["fetcher"]) =>
  fetcher.mock.calls.filter(([, request]) => request.method === "POST");
async function uploadJson(value: unknown) {
  fireEvent.click(screen.getByRole("tab", { name: "Manual + JSON" }));
  fireEvent.change(screen.getByLabelText("Upload JSON to fill the form"), {
    target: {
      files: [
        new File([JSON.stringify(value)], "original-fixture.json", {
          type: "application/json",
        }),
      ],
    },
  });
  await waitFor(() =>
    expect(
      screen.queryByRole("button", { name: "Reading JSON…" }),
    ).not.toBeInTheDocument(),
  );
}
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  setCsrfToken(null);
});

describe("case evidence collection", () => {
  it("notifies the case immediately after save and releases the form if the subsequent history refresh hangs", async () => {
    const { fetcher } = mockApi({ holdHistoryAfterSave: true });
    const { onSaved } = await openPage();
    await uploadJson(payload());
    vi.useFakeTimers();
    await act(async () => {
      fireEvent.click(
        screen.getByRole("button", { name: "Save new evidence version" }),
      );
    });
    expect(onSaved).toHaveBeenCalledTimes(1);
    expect(screen.getByText(/Evidence version 1 saved/)).toBeVisible();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30000);
    });
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Reading saved evidence took longer than 30 seconds",
    );
    expect(
      screen.getByRole("button", { name: "Save new evidence version" }),
    ).toBeEnabled();
    expect(screen.getByLabelText("PAYMENT row 1 AMOUNT")).toHaveValue(
      "123456789012345678901.0007",
    );
    expect(posts(fetcher)).toHaveLength(1);
  });
  it("explains a different-case file and offers only an authorized exact identity match without overwriting the form", async () => {
    const imported = payload("ORIGINAL-FIXTURE-002");
    const { fetcher } = mockApi({
      matches: [
        { id: "CASE-ORIGINAL-B", ...imported.payment },
        { id: "WRONG-BANK", ...imported.payment, orgBank: "010" },
        { id: "WRONG-BRANCH", ...imported.payment, orgBranch: "9999" },
      ],
    });
    await openPage();
    await uploadJson(payload());
    await uploadJson(imported);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "different payment case",
    );
    expect(screen.getByText("Payment in the file")).toBeVisible();
    expect(screen.getByText("Currently open case")).toBeVisible();
    expect(
      await screen.findByRole("link", { name: "Open matching payment case" }),
    ).toHaveAttribute("href", "/payment-cases/CASE-ORIGINAL-B");
    expect(screen.queryByText("WRONG-BANK")).not.toBeInTheDocument();
    expect(screen.getByLabelText("PAYMENT row 1 REFTXNNUMBER")).toHaveValue(
      "ORIGINAL-FIXTURE-001",
    );
    expect(posts(fetcher)).toHaveLength(0);
    expect(screen.getByText(/upload this file again/)).toBeVisible();
  });

  it("identifies the precise missing column or non-text value and permits selecting the corrected file again", async () => {
    const { fetcher } = mockApi();
    await openPage();
    const wrong = payload() as any;
    delete wrong.sections.PAYMENT.rows[0].AMOUNT;
    await uploadJson(wrong);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "PAYMENT row 1: missing fields: AMOUNT",
    );
    const numeric = payload() as any;
    numeric.sections.PAYMENT.rows[0].AMOUNT = 12.34;
    await uploadJson(numeric);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "PAYMENT row 1.AMOUNT must be quoted text",
    );
    await uploadJson(payload());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByLabelText("PAYMENT row 1 AMOUNT")).toHaveValue(
      "123456789012345678901.0007",
    );
    expect(posts(fetcher)).toHaveLength(0);
  });

  it("rejects duplicate JSON columns rather than silently submitting the last value", async () => {
    const { fetcher } = mockApi();
    await openPage();
    await uploadJson(payload());
    const text = JSON.stringify(payload()).replace(
      '"STATUS_VALUE":"00"',
      '"STATUS_VALUE":"99","STATUS_VALUE":"00"',
    );
    fireEvent.change(screen.getByLabelText("Upload JSON to fill the form"), {
      target: { files: [new File([text], "duplicate.json")] },
    });
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Duplicate JSON field",
    );
    expect(screen.getByLabelText("PAYMENT row 1 STATUS_VALUE")).toHaveValue(
      "00",
    );
    expect(posts(fetcher)).toHaveLength(0);
  });

  it("keeps inquiry disabled until configured and never issues a fabricated inquiry", async () => {
    const { fetcher } = mockApi();
    await openPage();
    expect(screen.getByRole("tab", { name: "Inquiry API" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(
      screen.getByText(/inquiry API is disabled pending deployment/),
    ).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Fetch and save evidence" }),
    ).toBeDisabled();
    fireEvent.click(
      screen.getByRole("button", { name: "Fetch and save evidence" }),
    );
    expect(posts(fetcher)).toHaveLength(0);
  });

  it("fills exact string values from JSON without saving, then preserves JSON provenance and CSRF", async () => {
    const { fetcher } = mockApi();
    setCsrfToken("original-csrf-token");
    const { onSaved } = await openPage();
    await uploadJson(payload());
    expect(screen.getByLabelText("PAYMENT row 1 AMOUNT")).toHaveValue(
      "123456789012345678901.0007",
    );
    expect(screen.getByLabelText("PAYMENT row 1 COD_ORG_BANK")).toHaveValue(
      "009",
    );
    expect(screen.getByLabelText("PAYMENT row 1 STATUS_VALUE")).toHaveValue(
      "00",
    );
    expect(posts(fetcher)).toHaveLength(0);
    fireEvent.click(
      screen.getByRole("button", { name: "Save new evidence version" }),
    );
    await screen.findByText(
      "Evidence version 1 saved. Previous versions remain unchanged.",
    );
    expect(posts(fetcher)[0][0]).toBe(
      "/api/payment-cases/CASE-ORIGINAL-A/evidence/json",
    );
    expect(JSON.parse(posts(fetcher)[0][1].body as string)).toEqual(payload());
    expect((posts(fetcher)[0][1].headers as Headers).get("X-CSRF-Token")).toBe(
      "original-csrf-token",
    );
    expect(
      (posts(fetcher)[0][1].headers as Headers).get("Idempotency-Key"),
    ).toBeTruthy();
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
    const saved = within(
      screen.getByRole("region", { name: "Saved evidence versions" }),
    );
    expect(saved.getByText("123456789012345678901.0007")).toBeVisible();
    expect(
      saved.getByText("Original fixture: source completion is unverified."),
    ).toBeVisible();
  });

  it("records manual provenance after editing an imported JSON value", async () => {
    const { fetcher } = mockApi();
    await openPage();
    await uploadJson(payload());
    fireEvent.change(screen.getByLabelText("PAYMENT row 1 STATUS_VALUE"), {
      target: { value: "09" },
    });
    fireEvent.click(
      screen.getByRole("button", { name: "Save new evidence version" }),
    );
    await screen.findByText(/Evidence version 1 saved/);
    expect(posts(fetcher)[0][0]).toMatch(/\/manual$/);
    expect(
      JSON.parse(posts(fetcher)[0][1].body as string).sections.PAYMENT.rows[0]
        .STATUS_VALUE,
    ).toBe("09");
  });

  it.each([
    "identity",
    "columns",
    "number",
    "rows",
    "length",
    "schema",
    "timezone",
  ])("rejects invalid JSON %s before replacing the form", async (kind) => {
    const { fetcher } = mockApi();
    await openPage();
    await uploadJson(payload());
    const invalid = structuredClone(payload()) as any;
    if (kind === "identity") invalid.payment.orgBank = "OTHER-BANK";
    if (kind === "columns")
      invalid.sections.PAYMENT.rows[0].UNEXPECTED_COLUMN = "x";
    if (kind === "number") invalid.sections.PAYMENT.rows[0].AMOUNT = 123;
    if (kind === "rows")
      invalid.sections.PAYMENT.rows = Array.from(
        { length: 501 },
        () => invalid.sections.PAYMENT.rows[0],
      );
    if (kind === "length") invalid.sections.PAYMENT.note = "x".repeat(4001);
    if (kind === "schema") invalid.schemaVersion = "not-the-contract";
    if (kind === "timezone") invalid.sourceTimezone = "x".repeat(101);
    await uploadJson(invalid);
    expect(await screen.findByRole("alert")).toBeVisible();
    expect(screen.getByLabelText("PAYMENT row 1 AMOUNT")).toHaveValue(
      "123456789012345678901.0007",
    );
    expect(posts(fetcher)).toHaveLength(0);
  });

  it("rejects an oversized JSON file before reading it", async () => {
    const { fetcher } = mockApi();
    await openPage();
    fireEvent.click(screen.getByRole("tab", { name: "Manual + JSON" }));
    const file = new File(["{}"], "too-large.json");
    Object.defineProperty(file, "size", { value: 5242881 });
    fireEvent.change(screen.getByLabelText("Upload JSON to fill the form"), {
      target: { files: [file] },
    });
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "no larger than 5 MiB",
    );
    expect(posts(fetcher)).toHaveLength(0);
  });

  it("uses configured headers for rows and only prefills case identity", async () => {
    mockApi();
    await openPage();
    fireEvent.click(screen.getByRole("tab", { name: "Manual + JSON" }));
    fireEvent.click(screen.getByRole("button", { name: "Add PAYMENT row" }));
    expect(screen.getByLabelText("PAYMENT row 1 REFTXNNUMBER")).toHaveValue(
      "ORIGINAL-FIXTURE-001",
    );
    expect(screen.getByLabelText("PAYMENT row 1 COD_ORG_BRN")).toHaveValue(
      "0012",
    );
    expect(screen.getByLabelText("PAYMENT row 1 AMOUNT")).toHaveValue("");
    expect(screen.getByLabelText("PAYMENT row 1 STATUS_VALUE")).toHaveValue("");
    fireEvent.change(screen.getByLabelText("Evidence group"), {
      target: { value: "HOST" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add HOST row" }));
    expect(screen.getByLabelText("HOST row 1 REF_TXN_NO")).toHaveValue(
      "ORIGINAL-FIXTURE-001",
    );
    expect(
      screen.queryByLabelText("PAYMENT row 1 AMOUNT"),
    ).not.toBeInTheDocument();
    fireEvent.click(
      screen.getByRole("button", { name: "Remove selected row" }),
    );
    expect(screen.getByText(/No HOST rows entered/)).toBeVisible();
  });

  it("preserves a failed draft and reuses the idempotency key only for an unchanged retry", async () => {
    const { fetcher } = mockApi({ failures: 2 });
    await openPage();
    await uploadJson(payload());
    fireEvent.click(
      screen.getByRole("button", { name: "Save new evidence version" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "original-request-failure",
    );
    expect(screen.getByLabelText("PAYMENT row 1 AMOUNT")).toHaveValue(
      "123456789012345678901.0007",
    );
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Save new evidence version" }),
      ).toBeEnabled(),
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Save new evidence version" }),
    );
    await waitFor(() => expect(posts(fetcher)).toHaveLength(2));
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Save new evidence version" }),
      ).toBeEnabled(),
    );
    const first = (posts(fetcher)[0][1].headers as Headers).get(
      "Idempotency-Key",
    );
    expect(
      (posts(fetcher)[1][1].headers as Headers).get("Idempotency-Key"),
    ).toBe(first);
    fireEvent.change(screen.getByLabelText("PAYMENT row 1 STATUS_VALUE"), {
      target: { value: "07" },
    });
    fireEvent.click(
      screen.getByRole("button", { name: "Save new evidence version" }),
    );
    await screen.findByText(/Evidence version 1 saved/);
    expect(
      (posts(fetcher)[2][1].headers as Headers).get("Idempotency-Key"),
    ).not.toBe(first);
  });

  it("blocks duplicate saves and aborts stale work when the case changes", async () => {
    const { fetcher, finish } = mockApi({ hold: true });
    const view = await openPage();
    await uploadJson(payload());
    fireEvent.click(
      screen.getByRole("button", { name: "Save new evidence version" }),
    );
    fireEvent.click(screen.getByRole("button", { name: "Saving evidence…" }));
    expect(posts(fetcher)).toHaveLength(1);
    expect(
      screen.getByRole("tab", { name: "Four Excel files" }),
    ).toBeDisabled();
    const signal = posts(fetcher)[0][1].signal!;
    view.rerender(<CaseEvidence caseId="CASE-ORIGINAL-B" canWrite />);
    await screen.findByText("ORIGINAL-FIXTURE-002");
    expect(signal.aborted).toBe(true);
    finish();
    await waitFor(() =>
      expect(
        screen.queryByText(/Evidence version 1 saved/),
      ).not.toBeInTheDocument(),
    );
    expect(
      screen.queryByText("Original fixture: source completion is unverified."),
    ).not.toBeInTheDocument();
  });

  it("lets viewers load immutable versions on demand and suppresses stale version responses", async () => {
    const old = snapshot(1);
    const recent = snapshot(2);
    recent.payload.sections.PAYMENT.rows[0].STATUS_VALUE = "12";
    const { fetcher, finishDetail } = mockApi({
      history: [recent, old],
      holdDetail: recent.id,
    });
    await openPage(false);
    expect(screen.getByRole("tab", { name: "Inquiry API" })).toBeDisabled();
    expect(screen.getByRole("tab", { name: "Inquiry API" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByRole("tab", { name: "Manual + JSON" })).toBeDisabled();
    expect(screen.getByRole("tab", { name: "Manual + JSON" })).toHaveAttribute(
      "aria-selected",
      "false",
    );
    expect(
      screen.queryByRole("button", { name: "Save new evidence version" }),
    ).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Evidence version"), {
      target: { value: old.id },
    });
    await screen.findByText("123456789012345678901.0007");
    finishDetail();
    const saved = within(
      screen.getByRole("region", { name: "Saved evidence versions" }),
    );
    await waitFor(() => expect(saved.getByText("00")).toBeVisible());
    expect(saved.queryByText("12")).not.toBeInTheDocument();
    expect(posts(fetcher)).toHaveLength(0);
    expect(fetcher.mock.calls.some(([url]) => url.endsWith(`/${old.id}`))).toBe(
      true,
    );
  });

  it("retries a failed saved version without discarding the current draft", async () => {
    const saved = snapshot();
    const { fetcher } = mockApi({ history: [saved], detailFailures: 1 });
    await openPage();
    await screen.findByText("Fixture saved version could not be read.");
    await uploadJson(payload());
    fireEvent.change(screen.getByLabelText("PAYMENT row 1 STATUS_VALUE"), {
      target: { value: "09" },
    });

    fireEvent.click(
      screen.getByRole("button", { name: "Retry saved version" }),
    );

    const history = within(
      screen.getByRole("region", { name: "Saved evidence versions" }),
    );
    expect(
      await history.findByText("123456789012345678901.0007"),
    ).toBeVisible();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByLabelText("PAYMENT row 1 STATUS_VALUE")).toHaveValue(
      "09",
    );
    expect(posts(fetcher)).toHaveLength(0);
    expect(
      fetcher.mock.calls.filter(([url]) => url.endsWith(`/${saved.id}`)),
    ).toHaveLength(2);
  });

  it("retries the saved version list without saving or replacing the draft and selected version", async () => {
    const old = snapshot();
    const { fetcher } = mockApi({
      history: [old],
      historyFailuresAfterSave: 1,
    });
    await openPage();
    await uploadJson(payload());
    fireEvent.click(
      screen.getByRole("button", { name: "Save new evidence version" }),
    );
    await screen.findByText("Fixture saved versions could not be refreshed.");
    fireEvent.change(screen.getByLabelText("PAYMENT row 1 STATUS_VALUE"), {
      target: { value: "09" },
    });
    fireEvent.change(screen.getByLabelText("Evidence version"), {
      target: { value: old.id },
    });

    fireEvent.click(
      screen.getByRole("button", { name: "Retry saved versions" }),
    );

    await waitFor(() =>
      expect(
        screen.queryByRole("button", { name: "Retry saved versions" }),
      ).not.toBeInTheDocument(),
    );
    expect(screen.getByLabelText("PAYMENT row 1 STATUS_VALUE")).toHaveValue(
      "09",
    );
    expect(screen.getByLabelText("Evidence version")).toHaveValue(old.id);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(posts(fetcher)).toHaveLength(1);
    expect(
      fetcher.mock.calls.filter(
        ([url, request]) =>
          url.endsWith("/evidence") && request.method !== "POST",
      ),
    ).toHaveLength(3);
  });

  it.each(["saving again", "switching case"])(
    "suppresses a stale saved-list retry after %s",
    async (action) => {
      const { fetcher, finishHistory } = mockApi({
        historyFailuresAfterSave: 1,
        holdHistoryRetry: true,
      });
      const view = await openPage();
      await uploadJson(payload());
      fireEvent.click(
        screen.getByRole("button", { name: "Save new evidence version" }),
      );
      await screen.findByText("Fixture saved versions could not be refreshed.");
      fireEvent.click(
        screen.getByRole("button", { name: "Retry saved versions" }),
      );
      const retry = fetcher.mock.calls.at(-1)!;
      expect(retry[0]).toBe("/api/payment-cases/CASE-ORIGINAL-A/evidence");
      expect(
        screen.getByRole("button", { name: "Retry saved versions" }),
      ).toBeDisabled();

      if (action === "saving again") {
        fireEvent.click(
          screen.getByRole("button", { name: "Save new evidence version" }),
        );
        await screen.findByText(/Evidence version 2 saved/);
        await waitFor(() =>
          expect(
            screen.queryByText("Refreshing saved versions…"),
          ).not.toBeInTheDocument(),
        );
      } else {
        view.rerender(<CaseEvidence caseId="CASE-ORIGINAL-B" canWrite />);
        await screen.findByText("ORIGINAL-FIXTURE-002");
      }
      expect(retry[1].signal!.aborted).toBe(true);
      await act(async () => finishHistory());

      if (action === "saving again") {
        expect(
          screen.getByRole("option", { name: /Version 2/ }),
        ).toBeInTheDocument();
        expect(screen.getByLabelText("Evidence version")).toHaveValue(
          "EVIDENCE-ORIGINAL-CASE-ORIGINAL-A-2",
        );
        expect(posts(fetcher)).toHaveLength(2);
      } else {
        expect(
          screen.getByText(
            "No evidence versions have been saved for this case.",
          ),
        ).toBeVisible();
        expect(
          screen.queryByLabelText("Evidence version"),
        ).not.toBeInTheDocument();
        expect(posts(fetcher)).toHaveLength(1);
      }
      expect(
        screen.queryByText("Fixture saved versions could not be refreshed."),
      ).not.toBeInTheDocument();
    },
  );

  it.each([
    { label: "role becomes read-only", canWrite: false, archived: false },
    { label: "case is archived", canWrite: true, archived: true },
  ])(
    "cancels pending saves when the $label and permits a later retry",
    async ({ canWrite, archived }) => {
      const { fetcher, finish } = mockApi({ hold: true });
      const view = await openPage();
      await uploadJson(payload());
      fireEvent.click(
        screen.getByRole("button", { name: "Save new evidence version" }),
      );
      const signal = posts(fetcher)[0][1].signal!;

      view.rerender(
        <CaseEvidence
          caseId="CASE-ORIGINAL-A"
          canWrite={canWrite}
          archived={archived}
          onSaved={view.onSaved}
        />,
      );
      expect(signal.aborted).toBe(true);
      expect(screen.getByRole("tab", { name: "Manual + JSON" })).toBeDisabled();
      expect(
        screen.queryByRole("button", { name: "Saving evidence…" }),
      ).not.toBeInTheDocument();
      expect(
        screen.getByText(
          archived
            ? /This case is archived/
            : /Your role can read saved evidence/,
        ),
      ).toBeVisible();

      view.rerender(
        <CaseEvidence
          caseId="CASE-ORIGINAL-A"
          canWrite
          onSaved={view.onSaved}
        />,
      );
      expect(
        screen.getByRole("button", { name: "Save new evidence version" }),
      ).toBeEnabled();
      expect(screen.getByLabelText("PAYMENT row 1 AMOUNT")).toHaveValue(
        "123456789012345678901.0007",
      );
      await act(async () => finish());
      expect(view.onSaved).not.toHaveBeenCalled();
      expect(
        screen.queryByText(/Evidence version 1 saved/),
      ).not.toBeInTheDocument();
    },
  );

  it("requires four Excel files and submits them with the declared timezone", async () => {
    const originalCrypto = globalThis.crypto;
    vi.stubGlobal("crypto", {
      randomUUID: () => originalCrypto.randomUUID(),
      subtle: {
        digest: async (_algorithm: string, bytes: ArrayBuffer) => bytes,
      },
    });
    const { fetcher } = mockApi();
    await openPage();
    fireEvent.click(screen.getByRole("tab", { name: "Four Excel files" }));
    fireEvent.click(
      screen.getByRole("button", { name: "Save new evidence version" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Choose all four Excel files",
    );
    for (const key of groups) {
      fireEvent.change(
        screen.getByLabelText(new RegExp(`^${key} Excel file`)),
        {
          target: {
            files: [new File([`original-${key}`], `${key}-fixture.xlsx`)],
          },
        },
      );
      expect(
        screen.getByRole("link", { name: `Download ${key} headers` }),
      ).toHaveAttribute(
        "href",
        `/api/payment-cases/CASE-ORIGINAL-A/evidence/template/${key}.xlsx`,
      );
    }
    fireEvent.change(screen.getByLabelText("Excel source timezone"), {
      target: { value: "UNKNOWN" },
    });
    fireEvent.click(
      screen.getByRole("button", { name: "Save new evidence version" }),
    );
    await screen.findByText(/Evidence version 1 saved/);
    const [url, request] = posts(fetcher)[0];
    expect(url).toMatch(/\/excel$/);
    const form = request.body as FormData;
    expect([...form.keys()]).toEqual([...groups, "sourceTimezone"]);
    expect(form.get("sourceTimezone")).toBe("UNKNOWN");
    for (const key of groups)
      expect((form.get(key) as File).name).toBe(`${key}-fixture.xlsx`);
    expect((request.headers as Headers).get("Content-Type")).toBeNull();
  });

  it("calls only the explicit configured inquiry action with an empty body", async () => {
    const configured = config();
    configured.api = { enabled: true, mode: "BANK_API" };
    const { fetcher } = mockApi({ configured });
    await openPage();
    expect(posts(fetcher)).toHaveLength(0);
    expect(screen.getByRole("tab", { name: "Inquiry API" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Fetch and save evidence" }),
    );
    await screen.findByText(/Evidence version 1 saved/);
    expect(posts(fetcher)[0][0]).toMatch(/\/inquiry$/);
    expect(posts(fetcher)[0][1].body).toBe("{}");
  });

  it("downloads a fillable JSON template with every configured column and only case identity populated", async () => {
    let contents: Blob | undefined;
    const browserUrl = URL;
    const revoke = vi.fn();
    vi.stubGlobal(
      "URL",
      class extends browserUrl {
        static createObjectURL(value: Blob) {
          contents = value;
          return "blob:original-fixture-template";
        }
        static revokeObjectURL = revoke;
      },
    );
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => {});
    mockApi();
    await openPage();
    fireEvent.click(screen.getByRole("tab", { name: "Manual + JSON" }));
    fireEvent.click(
      screen.getByRole("button", { name: "Download JSON template" }),
    );
    expect(click).toHaveBeenCalledOnce();
    expect(revoke).toHaveBeenCalledWith("blob:original-fixture-template");
    const text = await new Promise<string>((resolve) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result as string);
      reader.readAsText(contents!);
    });
    const template = JSON.parse(text);
    expect(template.payment).toEqual(config().template.payment);
    expect(template.sourceTimezone).toBe("UNKNOWN");
    for (const key of groups) {
      expect(template.sections[key].rows).toHaveLength(1);
      expect(Object.keys(template.sections[key].rows[0])).toEqual(columns[key]);
      for (const [column, value] of Object.entries(
        template.sections[key].rows[0],
      )) {
        const expected =
          column === "REFTXNNUMBER" || column === "REF_TXN_NO"
            ? "ORIGINAL-FIXTURE-001"
            : column === "COD_ORG_BANK"
              ? "009"
              : column === "COD_ORG_BRN"
                ? "0012"
                : "";
        expect(value).toBe(expected);
      }
    }
    expect(screen.getByText(/No PAYMENT rows entered/)).toBeVisible();
  });
});
