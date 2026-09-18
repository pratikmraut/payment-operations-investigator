import { useEffect, useId, useRef, useState, type ChangeEvent } from "react";
import { Download } from "lucide-react";
import { parseEvidenceJson } from "./evidenceJson";

/** Explicit, case-bound draft files. No browser storage or API writes. */
export function DraftTextControls({
  scope,
  value,
  onRestore,
  maxLength,
  disabled = false,
}: {
  scope: string;
  value: string;
  onRestore: (value: string) => void;
  maxLength: number;
  disabled?: boolean;
}) {
  const inputId = useId();
  const [error, setError] = useState("");
  const current = useRef({ scope, value, disabled });
  current.current = { scope, value, disabled };
  const readerRef = useRef<FileReader | null>(null);
  useEffect(() => () => readerRef.current?.abort(), []);
  function download() {
    const url = URL.createObjectURL(
      new Blob(
        [
          JSON.stringify(
            { draftVersion: "case-text-draft-v1", scope, value },
            null,
            2,
          ),
        ],
        { type: "application/json" },
      ),
    );
    const link = document.createElement("a");
    link.href = url;
    link.download = "case-text-draft.json";
    link.click();
    URL.revokeObjectURL(url);
  }
  async function restore(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file || disabled) return;
    setError("");
    try {
      if (!file.name.toLowerCase().endsWith(".json") || file.size > 131072)
        throw new Error(
          "Choose a text draft JSON file no larger than 128 KiB.",
        );
      const content = await new Promise<string>((resolve, reject) => {
        readerRef.current?.abort();
        const reader = new FileReader();
        readerRef.current = reader;
        reader.onload = () => resolve(String(reader.result));
        reader.onerror = () =>
          reject(new Error("The draft could not be read."));
        reader.onabort = () =>
          reject(new DOMException("Draft reading canceled", "AbortError"));
        reader.readAsText(file, "UTF-8");
      });
      if (current.current.scope !== scope || current.current.disabled) return;
      const draft = parseEvidenceJson(content) as Record<string, unknown>;
      if (
        !draft ||
        typeof draft !== "object" ||
        Array.isArray(draft) ||
        Object.keys(draft).sort().join(",") !== "draftVersion,scope,value" ||
        draft.draftVersion !== "case-text-draft-v1" ||
        draft.scope !== scope ||
        typeof draft.value !== "string" ||
        draft.value.length > maxLength
      )
        throw new Error(
          "This draft does not match this case and field, or exceeds its text limit. Your input was preserved.",
        );
      if (
        current.current.value &&
        current.current.value !== draft.value &&
        !window.confirm(
          "Replace the unsaved text with this draft? Cancel to keep your current text.",
        )
      )
        return;
      onRestore(draft.value);
    } catch (failure) {
      if (failure instanceof DOMException && failure.name === "AbortError")
        return;
      setError(
        failure instanceof Error
          ? failure.message
          : "The draft could not be read.",
      );
    }
  }
  return (
    <details className="draft-text-controls">
      <summary>Keep or restore a text draft</summary>
      <p className="muted">
        Download your text before leaving, then restore it to this case. It
        remains unsubmitted. The file contains your entered text.
      </p>
      <button
        type="button"
        className="secondary"
        disabled={disabled || !value}
        onClick={download}
      >
        <Download size={14} /> Download text draft
      </button>
      <label htmlFor={inputId}>Restore text draft</label>
      <input
        id={inputId}
        type="file"
        accept=".json,application/json"
        disabled={disabled}
        onChange={restore}
      />
      {error && (
        <p role="alert" className="notice danger">
          {error}
        </p>
      )}
    </details>
  );
}
