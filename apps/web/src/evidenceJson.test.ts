import { describe, expect, it } from "vitest";
import { parseEvidenceJson } from "./evidenceJson";

describe("lossless evidence JSON reading", () => {
  it("keeps exact text, empty groups, escaped characters and separate same-named fields", () => {
    const original = {
      payment: { reference: "000123456789012345678901234" },
      amount: "12.3400",
      escaped: 'quote" brace} slash\\ unicode水',
      nested: [{ amount: "00" }, { amount: "" }],
      empty: [],
    };
    expect(parseEvidenceJson(JSON.stringify(original))).toEqual(original);
  });
  it.each([
    '{"payment":{"reference":"wrong","reference":"right"}}',
    '{"payment":{"reference":"wrong","ref\\u0065rence":"right"}}',
    '{"sections":{"PAYMENT":{"rows":[{"AMOUNT":"1","AMOUNT":"2"}]}}}',
  ])("rejects duplicate decoded fields before import: %s", (text) => {
    expect(() => parseEvidenceJson(text)).toThrow(/Duplicate JSON field/);
  });
  it("bounds nesting and preserves native syntax errors instead of repairing files", () => {
    expect(() =>
      parseEvidenceJson("[".repeat(66) + "0" + "]".repeat(66)),
    ).toThrow(/nested too deeply/);
    expect(() => parseEvidenceJson('{"amount":"1",}')).toThrow(SyntaxError);
  });
});
