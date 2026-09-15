import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5178,
    strictPort: true,
    proxy: {
      "/api/uat/": {
        target: "http://127.0.0.1:8088",
        changeOrigin: true,
        proxyTimeout: 960_000,
      },
      "/api": {
        target: "http://127.0.0.1:8088",
        changeOrigin: true,
        proxyTimeout: 420_000,
      },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test-setup.ts"],
    restoreMocks: true,
  },
});
