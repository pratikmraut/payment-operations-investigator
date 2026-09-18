import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dedicated browser acceptance ports; never reuse the operator's native site.
export default defineConfig({
  plugins: [
    react(),
    {
      name: "isolated-browser-test-shutdown",
      configureServer(server) {
        server.middlewares.use((request, response, next) => {
          if (
            request.url !== "/__e2e_shutdown" ||
            request.method !== "POST" ||
            request.headers["x-test-key"] !== "isolated-browser-acceptance-only"
          )
            return next();
          response.end("{}");
          setImmediate(
            () => void server.close().finally(() => process.exit(0)),
          );
        });
      },
    },
  ],
  server: {
    host: "127.0.0.1",
    port: 19091,
    strictPort: true,
    proxy: { "/api": "http://127.0.0.1:19092" },
  },
});
