/** Keep the separate API's legacy logout cookie inside its own session scope. */
export function experimentalSessionCookie(header: string): string {
  return header.replace(/^POI_SESSION=/, "POI_GPU_SESSION=");
}
