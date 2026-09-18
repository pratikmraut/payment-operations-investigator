import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  globalTeardown: "./e2e/support/shutdown.mjs",
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: "http://127.0.0.1:19091",
    browserName: "chromium",
    viewport: { width: 1440, height: 1000 },
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: [
    {
      command: "node e2e/support/api-server.mjs",
      url: "http://127.0.0.1:19092/api/health",
      reuseExistingServer: false,
      timeout: 120_000,
    },
    {
      command: "node node_modules/vite/bin/vite.js --config vite.e2e.config.ts",
      url: "http://127.0.0.1:19091",
      reuseExistingServer: false,
      timeout: 60_000,
    },
  ],
});
