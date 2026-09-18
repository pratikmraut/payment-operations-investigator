import { useState } from "react";
import { afterEach, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { DraftTextControls } from "./DraftTextControls";

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
function Form() {
  const [value, setValue] = useState("Original note");
  return (
    <>
      <textarea
        aria-label="Note"
        value={value}
        onChange={(event) => setValue(event.target.value)}
      />
      <DraftTextControls
        scope="CASE-FIXTURE:note"
        value={value}
        onRestore={setValue}
        maxLength={100}
      />
    </>
  );
}
function upload(value: unknown) {
  fireEvent.change(screen.getByLabelText("Restore text draft"), {
    target: {
      files: [
        new File([JSON.stringify(value)], "note.json", {
          type: "application/json",
        }),
      ],
    },
  });
}
it("validates the exact case/field and preserves input when replacement is canceled", async () => {
  render(<Form />);
  const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
  upload({
    draftVersion: "case-text-draft-v1",
    scope: "ANOTHER-CASE:note",
    value: "Other case",
  });
  await screen.findByRole("alert");
  expect(screen.getByLabelText("Note")).toHaveValue("Original note");
  expect(confirm).not.toHaveBeenCalled();
  upload({
    draftVersion: "case-text-draft-v1",
    scope: "CASE-FIXTURE:note",
    value: "New note",
  });
  await waitFor(() => expect(confirm).toHaveBeenCalledOnce());
  expect(screen.getByLabelText("Note")).toHaveValue("Original note");
  confirm.mockReturnValue(true);
  upload({
    draftVersion: "case-text-draft-v1",
    scope: "CASE-FIXTURE:note",
    value: "New note",
  });
  await waitFor(() =>
    expect(screen.getByLabelText("Note")).toHaveValue("New note"),
  );
});
it("rejects overlong text rather than truncating it", async () => {
  render(<Form />);
  upload({
    draftVersion: "case-text-draft-v1",
    scope: "CASE-FIXTURE:note",
    value: "x".repeat(101),
  });
  await screen.findByRole("alert");
  expect(screen.getByLabelText("Note")).toHaveValue("Original note");
});
it("downloads only on explicit action and does not write browser storage", async () => {
  let content: Blob | undefined;
  const storage = vi.spyOn(Storage.prototype, "setItem");
  const browserUrl = URL;
  vi.stubGlobal(
    "URL",
    class extends browserUrl {
      static createObjectURL(value: Blob) {
        content = value;
        return "blob:note";
      }
      static revokeObjectURL() {}
    },
  );
  const click = vi
    .spyOn(HTMLAnchorElement.prototype, "click")
    .mockImplementation(() => {});
  render(<Form />);
  expect(content).toBeUndefined();
  fireEvent.click(screen.getByRole("button", { name: "Download text draft" }));
  expect(click).toHaveBeenCalledOnce();
  const text = await new Promise<string>((resolve) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.readAsText(content!);
  });
  expect(JSON.parse(text)).toEqual({
    draftVersion: "case-text-draft-v1",
    scope: "CASE-FIXTURE:note",
    value: "Original note",
  });
  expect(storage).not.toHaveBeenCalled();
});
