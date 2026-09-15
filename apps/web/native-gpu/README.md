# Default Intel GPU web entry

This entry renders the shared React application and styles directly, with the original appearance. GPU inference is a backend configuration: there is no extra header, mode picker, login banner or layout wrapper. The default website opens at `http://127.0.0.1:5178/` for sign-in and at `/cases` after normal login. Answers continue through the actual existing API and model workflow; this entry contains no payment answers or model replay. Answer quality remains experimental. The preserved CPU demo is stopped during normal GPU operation and can be started explicitly using `tools/start.ps1 -Mode CPU -WithAI` from the project root.

This entry alone does not start an API, worker or inference server, and does not verify GPU usage. Use the separately configured native experimental stack and inspect its benchmark/offload receipt. Do not point the preserved API at the experimental runtime.

For the complete setup and startup flow, follow [the native GPU setup guide](../../../docs/NATIVE_GPU_SETUP.md). After starting the full stack, open [the sign-in page](http://127.0.0.1:5178/), choose **Analyst · Northstar**, enter **`demo-pass-local`**, and sign in. Both local demos use this password. Open [Evidence library](http://127.0.0.1:5178/evidences) from the sidebar to inspect case evidence and open its case workbench for questions. The [Export Q&A tab](http://127.0.0.1:5178/evidences/exports) retains the earlier standalone snapshot questions and saved answers. Generated answers still require factual review against their evidence.

The login screen displays `/`. Normal login and authenticated root visits open `/cases`; an explicitly opened internal deep link is restored after login. Successful logout returns to `/`. All page links use clean paths: `/cases`, `/cases/:id`, `/payment-cases/:id`, `/evidences`, `/evidences/exports`, `/knowledge` and `/system`. Old hash links and `/uat-evidence` normalize to their current paths. Both frontend builds share this behavior; direct navigation or refresh requires the server to serve the SPA entry for these page paths. See the [routing map](../../../docs/validation/clean-page-urls-2026-09-14.md).

Run these commands from `apps/web`:

```powershell
npm.cmd exec tsc -- --project native-gpu/tsconfig.json
npm.cmd exec vite -- build --config vite.gpu.config.ts
npm.cmd exec vite -- preview --config vite.gpu.config.ts
```

For development instead of a built preview:

```powershell
npm.cmd exec vite -- --config vite.gpu.config.ts
```

Both servers bind to `127.0.0.1:5178` with strict port selection and proxy `/api` to `127.0.0.1:8089`, allowing up to 960 seconds for a response. Build output is the ignored project directory `runtime/native-gpu/web-dist`; the existing `apps/web/dist` is not modified. The CPU website must be mapped to port 5180 before starting the GPU website on 5178; this entry does not move or stop services.

For the isolated launcher, use the built **preview** command above. The reused application may show an old startup hint naming API port 8088 if its API is unavailable; for the default GPU mode inspect website port 5178, API port 8089 and the native stack logs.

The native Java service must use a different cookie name, `POI_GPU_SESSION`, because cookies are shared across ports on the same host. It must also use separate saved-answer, database and worker-checkpoint locations. The preserved CPU baseline is available at `http://127.0.0.1:5180/` when explicitly started; its model and inference configuration remain unchanged.

The experimental proxy rewrites only a `Set-Cookie` header beginning with the exact legacy name `POI_SESSION=` to `POI_GPU_SESSION=`. Shared Java logout currently emits the legacy name; this hook prevents experimental logout from clearing the baseline cookie. Cookie values, flags and other cookie names remain untouched. Use the experimental web origin for login/logout rather than calling its API directly from a browser.

No GPU performance or payment-answer accuracy is implied by the profile name. Keep factual-review warnings visible and record actual device/offload, model digest, timings and semantic validation in the benchmark evidence.
