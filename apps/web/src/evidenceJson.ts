// JSON.parse alone discards duplicate keys before the API can reject them.
export function parseEvidenceJson(text: string): unknown {
  const value: unknown = JSON.parse(text);
  let offset = 0;
  const whitespace = () => {
    while (/\s/.test(text[offset] ?? "") && offset < text.length) offset++;
  };
  function quoted(): string {
    const start = offset++;
    while (offset < text.length) {
      const char = text[offset++];
      if (char === "\\") offset++;
      else if (char === '"') break;
    }
    return JSON.parse(text.slice(start, offset)) as string;
  }
  function scan(depth: number, location: string) {
    if (depth > 64)
      throw new Error(
        "The JSON file is nested too deeply. Use the downloaded evidence template.",
      );
    whitespace();
    if (text[offset] === "{") {
      offset++;
      whitespace();
      const names = new Set<string>();
      while (text[offset] !== "}") {
        const key = quoted();
        if (names.has(key))
          throw new Error(
            `Duplicate JSON field at ${location}.${key.slice(0, 100)}. Keep each key once; the existing form was preserved.`,
          );
        names.add(key);
        whitespace();
        offset++;
        scan(depth + 1, `${location}.${key.slice(0, 100)}`);
        whitespace();
        if (text[offset] !== ",") break;
        offset++;
        whitespace();
      }
      offset++;
    } else if (text[offset] === "[") {
      offset++;
      whitespace();
      let index = 0;
      while (text[offset] !== "]") {
        scan(depth + 1, `${location}[${index++}]`);
        whitespace();
        if (text[offset] !== ",") break;
        offset++;
        whitespace();
      }
      offset++;
    } else if (text[offset] === '"') quoted();
    else
      while (offset < text.length && !/[\s,}\]]/.test(text[offset])) offset++;
  }
  scan(0, "evidence");
  return value;
}
