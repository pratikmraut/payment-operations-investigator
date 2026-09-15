import { defineConfig, type ProxyOptions } from "vite";
import react from "@vitejs/plugin-react";
import { experimentalSessionCookie } from "./native-gpu/session-cookie.ts";

type ProxyResponse = {
  headers: Record<string, string | string[] | undefined>;
};

// Run from apps/web. The default GPU website uses its separate native backend.
const experimentalApi: ProxyOptions = {
  target: "http://127.0.0.1:8089",
  changeOrigin: true,
  proxyTimeout: 960_000,
  configure(proxy) {
    // This workspace has no @types/node; declare only the EventEmitter surface used here.
    const events = proxy as typeof proxy & {
      on(event: "proxyRes", listener: (response: ProxyResponse) => void): void;
    };
    events.on("proxyRes", (response) => {
      const cookies = response.headers["set-cookie"];
      if (Array.isArray(cookies)) {
        response.headers["set-cookie"] = cookies.map(experimentalSessionCookie);
      } else if (typeof cookies === "string") {
        response.headers["set-cookie"] = experimentalSessionCookie(cookies);
      }
    });
  },
};

export default defineConfig({
  root: "native-gpu",
  publicDir: "../public",
  plugins: [react()],
  server: {
    host: "127.0.0.1",
    port: 5178,
    strictPort: true,
    proxy: { "/api": experimentalApi },
  },
  preview: {
    host: "127.0.0.1",
    port: 5178,
    strictPort: true,
    proxy: { "/api": experimentalApi },
  },
  build: {
    outDir: "../../../runtime/native-gpu/web-dist",
    emptyOutDir: true,
  },
});
