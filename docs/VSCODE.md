# Open and work in VS Code

The **payment-operations-investigator** folder is the main editable project. Open `Payment Operations Investigator.code-workspace` from that folder, or use **File → Open Folder** and choose the folder itself. No ZIP extraction or second copy is needed.

The saved workspace opens one project root using a relative path, so it remains usable if the folder is moved. It follows the [VS Code workspace format](https://code.visualstudio.com/docs/editing/workspaces/multi-root-workspaces). You can also open it with the [VS Code CLI](https://code.visualstudio.com/docs/configure/command-line):

```powershell
code ".\Payment Operations Investigator.code-workspace"
```

## Where the code lives

| Folder | Purpose |
| --- | --- |
| `apps/web/src` | React and TypeScript frontend |
| `services/api/src` | Java 17 / Spring Boot business API and tests |
| `services/investigator/investigator` | Python RAG, tools, agent graph and fact catalog |
| `services/investigator/tests` | Worker tests |
| `data` | Original payment fixtures, runbooks and isolated evaluation labels |
| `docs` | Architecture, flows, API contract, demo and validation evidence |
| `infra` | Container/database configuration |
| `tools` | Start/stop, data generation and validation commands |

Generated dependencies, build outputs and runtime logs are hidden from VS Code Explorer and search. They remain on disk. Source, original datasets and documentation remain visible.

## Run the application

The default Intel GPU demo is available at http://127.0.0.1:5178. From **Terminal → Run Task**, choose **POI: Start application (GPU default)**. Sign in with Analyst · Northstar and `demo-pass-local`. **POI: Stop GPU application (keep data)** stops the native website/API/worker and retains saved records. Use **POI: Start preserved CPU demo** for the original Docker model at http://127.0.0.1:5180; **POI: CPU service status** inspects its containers.

The default start task reuses the native installation and model already prepared on this laptop. On a fresh computer, follow [native GPU setup](NATIVE_GPU_SETUP.md). CPU setup uses `tools/start.ps1 -Mode CPU -WithAI` and downloads missing configured models. No task starts automatically when the workspace opens.

Use **POI: Build React** for the production frontend build. **POI: Check generated data** uses the worker's existing virtual environment; a new computer must create that environment using the [worker instructions](../services/investigator/README.md).

## Language support

Workspace recommendations include Microsoft's Python extension and the Java extension pack. They are recommendations, not automatic installations. The Python interpreter points to `services/investigator/.venv`, and pytest runs from that service folder with a project-local temporary directory. See the [official Python settings reference](https://code.visualstudio.com/docs/python/settings-reference).

The Java project target remains Java 17 in `services/api/pom.xml`. The Java language server has its own tooling runtime; this workspace does not override it. Prefer the Docker build on this Windows host because its native Maven limitation is documented in [Setup](SETUP.md). Editor integration and application test results are separate checks.

## Historical packages

The redundant ZIP copies and generated packaging probe were removed at the user's request. Previous package hashes, manifests and review receipts are preserved under `docs/validation/package-history`; they describe the earlier snapshot. The VS Code files and this guide were added afterward. [The source-folder delivery record](validation/source-folder-delivery.json) records the cleanup and the current editable entry point.
