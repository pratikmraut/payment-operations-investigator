# Setup and operation

Open the existing `payment-operations-investigator` folder in VS Code, or open `Payment Operations Investigator.code-workspace` inside it. This is the main editable delivery. See [VS Code setup and tasks](VSCODE.md); no ZIP extraction or duplicate checkout is needed.

## Default Intel GPU website

Run `.\tools\start.ps1`, then open [the sign-in page](http://127.0.0.1:5178/). Select **Analyst · Northstar** and enter **demo-pass-local**. Normal login opens [Case queue](http://127.0.0.1:5178/cases); [Evidence Q&A](http://127.0.0.1:5178/evidences) is available from the sidebar. The GPU is the default on this prepared Windows laptop. The former experimental password `gpu-demo-local` is obsolete. Use [native setup and flow](NATIVE_GPU_SETUP.md) for installation, startup and troubleshooting.

The original CPU model remains available separately at [port 5180](http://127.0.0.1:5180/), with [Evidence Q&A at `/evidences`](http://127.0.0.1:5180/evidences). `compose.override.yaml` changes only its web port; include this file whenever using explicit Compose `-f` options. Plain `docker compose` loads it automatically. Stop the default GPU profile with `.\tools\stop.ps1`; choose `-Mode CPU` or `-Mode All` explicitly when needed.

All current page links use clean paths; sign-in remains `/`. Direct requests and refreshes of `/cases`, `/cases/:id`, `/payment-cases/:id`, `/evidences`, `/knowledge` and `/system` must return the SPA entry HTML. API requests under `/api/` retain their existing routing and must not fall through to HTML. See [the routing map and fallback requirement](validation/clean-page-urls-2026-09-14.md).

## Preserved CPU container path
Requires Docker with Linux containers. The checked-in fixture files are sufficient; no external data or paid API is needed.

From this project directory:
```powershell
if (-not (Test-Path -LiteralPath .env)) { Copy-Item .env.example .env }
.\tools\start.ps1 -Mode CPU
```

If an existing .env is present, keep its configured values rather than copying over it. Open [the workbench](http://127.0.0.1:5180). Default local password: `demo-pass-local`. Northstar identities: `analyst`, `reviewer`, `viewer`. The `other` analyst belongs to Silverline.

The PowerShell launcher waits for the static web page, API proxy and worker health endpoints before reporting readiness. These checks establish application response, not model availability or database failover readiness.

For actual local AI inference, use `.\tools\start.ps1 -Mode CPU -WithAI`. The launcher reads `OLLAMA_MODEL` and `OLLAMA_EMBED_MODEL` from the configured worker container, reuses installed models and downloads missing ones. This respects `.env` model overrides and avoids refreshing an installed model on every launch. Initial runtime/model downloads can take time. Select Ollama after setup finishes. Replay with lexical retrieval requires no model; hybrid retrieval still requires its embedding model. A failed Ollama request remains a failure with no implicit replay substitution.

Equivalent commands on another operating system:
```sh
docker compose up -d --build
docker compose --profile ai up -d ollama
docker compose exec -T ollama ollama pull qwen3:4b-instruct
docker compose exec -T ollama ollama pull nomic-embed-text:v1.5
```

The default retrieval mode is lexical. Set `POI_RETRIEVAL_MODE=hybrid` only after the embedding model is downloaded, then recreate the investigator service. Configuration and implementation support do not replace a verified inference or retrieval result.

The worker uses the restricted `poi_vectors` role in the `poi_knowledge` schema. If you override `POI_VECTOR_DB_PASSWORD`, also update `POI_VECTOR_DB_URL` to use the matching URI-encoded password. The API database password is separate. Bootstrap scripts run only when PostgreSQL initializes a fresh volume; changing environment values does not rotate credentials in an existing database.

## Verify a running application
```sh
python tools/generate_data.py --check
python tools/acceptance.py --mode replay
python tools/evaluate.py --split test --mode replay
```
The acceptance harness changes only synthetic case workflow records. It can run repeatedly and writes evidence to docs/validation. It checks authentication, tenant access, CSRF, investigation, reviewer separation, stale versions, durable idempotency, export and audit.

Service-specific build and unit-test commands are documented in each service README. Start scripts and container builds are part of the release criteria; inspect STATUS.md for what has actually been verified.

The Java Docker build runs Maven tests before producing its runtime image. If this Windows host's native compiler cannot resolve its own freshly compiled classes despite a correct classpath, use the Linux container build for validation; do not skip tests or change project dependencies to hide the host issue.

## Fresh-volume rehearsal
Run `.\tools\rehearse.ps1` with Docker Compose 2.24.4 or later and Python available. Optional `-Python` selects a Python executable; `-AcceptanceReport docs/validation/acceptance-clean-compose-final.json` preserves a separately named acceptance run. It builds current source in a generated, isolated Compose project on ports 15178, 15438, 18088 and 18091; creates fresh PostgreSQL/checkpoint volumes; checks the production React entry point; and runs replay acceptance through nginx. It saves the acceptance JSON and diagnostic logs, then removes only that disposable project's containers and volumes. The main application and its records remain available. This is a fresh-data startup rehearsal, not a remote Git clone or live-model benchmark.

## Ports and persistence
| Service | Loopback URL / port | Persistent data |
| --- | --- | --- |
| React workbench | http://127.0.0.1:5180 | Session in browser |
| Java API | http://127.0.0.1:8088/api/health | PostgreSQL in Compose; H2 in native development |
| Worker | http://127.0.0.1:8091/health | SQLite checkpoint volume |
| Mock NEFT inquiry | http://127.0.0.1:8092/health | Original fixed JSON fixtures mounted read-only; no database |
| PostgreSQL | 127.0.0.1:5438 | Dedicated postgres-data volume |
| Ollama | http://127.0.0.1:11438 | Dedicated ollama-models volume |

`.\tools\stop.ps1 -Mode CPU` stops only this Compose project and preserves its volumes. Do not use global Docker pruning or remove unrelated application containers. Do not start Compose while native development servers occupy the same ports. Stop the tracked native project processes first.

## Troubleshooting
- Connection refused: inspect `docker compose ps` and `docker compose logs --tail 80 api investigator web`.
- Unauthorized: sign in again; sessions expire. Do not send tenant or role headers.
- Forbidden review: use a reviewer distinct from the investigation creator.
- Conflict: refresh the case; proposals are tied to a case version.
- Ollama unavailable: confirm both runtime and selected model are present. Replay remains an explicit separate choice.
- Hybrid failure: confirm the embedding model and configured vector storage are available; there is no silent lexical fallback.
- Slow first model run: loading and CPU inference can take longer than replay. Consult measured validation evidence for this laptop.
- Native Java on this Windows host: a scoped JVM option may be required for the host's Unix-domain-socket issue. See the API README for the verified command; no machine-wide JVM configuration is changed.

All credentials here are local demo defaults, and all records are original synthetic examples. Public deployment and external identity integration are outside this local release.
