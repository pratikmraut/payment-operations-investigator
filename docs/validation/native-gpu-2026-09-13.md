# Intel GPU implementation and measured latency

**Current routing changed afterward:** GPU is now the default at port 5178 with `demo-pass-local`; the CPU website moved to 5180. See [the login/default repair](gpu-default-2026-09-13.md). The measurements and port references below describe the original isolated benchmark stage.

On 13 September 2026, the same full-evidence question completed in **101.750 seconds on native Windows Ollama/Vulkan**, compared with the preserved historical CPU response in **640.306 seconds**. This is **84.11% less waiting**, approximately **6.29 times faster**, saving about **8 minutes 59 seconds** on this comparison. These are actual local calls, not an estimated hardware speedup.

**The complete website flow also succeeded:** React → Java → Python → native Ollama returned and saved a newly generated answer. Its displayed model duration was **109.101 seconds (1 minute 49 seconds)**, one actual call, 6,474 input tokens and 475 output tokens. Compared with the historical CPU duration, this is **82.96% less waiting**, about **5.87 times faster**. The UI value measures worker model-generation duration; browser round-trip time was not separately instrumented. This second run used the website's unload-after-answer setting.

The original Docker service, model settings, worker implementation and React UAT page were preserved. The benchmark-stage addresses were `http://127.0.0.1:5179/#/uat-evidence` for GPU with the former `gpu-demo-local` password, and `http://127.0.0.1:5178/#/uat-evidence` for CPU. These are historical receipt details, not current launch instructions. Today, open [the GPU evidence page](http://127.0.0.1:5178/evidences) or explicitly start [the preserved CPU evidence page](http://127.0.0.1:5180/evidences), using `demo-pass-local`. See [setup and lifecycle instructions](../NATIVE_GPU_SETUP.md).

## What was measured

| Measurement | Historical CPU | Native Intel GPU |
| --- | ---: | ---: |
| Full request / model wall time | 640.306 s | 101.750 s |
| Input tokens | 6,474 | 6,474 |
| Generated tokens | 460 | 475 |
| Prompt evaluation | 415.769 s | 37.906 s |
| Output generation | 212.236 s | 55.130 s |
| GPU model load | — | 8.672 s |

The [full GPU receipt](native-gpu-cold-full-2026-09-13.json) verifies that the prepared request canonically matches the retained CPU request. Both used `qwen3:8b`, Q4_K_M, Ollama 0.34.0, a 32,768-token context, four CPU threads, a 1,400-token maximum output, temperature zero, no thinking and non-streamed structured output. The same existing LangChain serialization, evidence preparation, system prompt and response schema were used. Output prose and token counts differ across backends.

The baseline is the [earlier website CPU measurement](uat-qa-timeout-fix-2026-09-13.md), not a fresh simultaneous CPU run. The GPU measurement is a direct Ollama call with the exact prepared worker request; its full harness including metadata checks took 103.495 seconds. Both measurements had an unloaded model at the start, but filesystem/driver caches, OS load and thermal conditions were not controlled. This single comparison is not a median, p95, throughput or production latency guarantee.

Two original synthetic controls checked that generation responds to changed evidence. The first unloaded-model request took [28.784 seconds](native-gpu-cold-control-2026-09-13.json); the changed-input warm request took [6.243 seconds](native-gpu-changed-control-2026-09-13.json). These much shorter prompts are not comparable with the full evidence request. The warm result reused cached prompt work, so dividing its entire input token count by prompt time does not measure uncached prompt throughput. No replay or prepared answer was substituted.

## Hardware proof and implementation

The laptop has an Intel Core Ultra 7 268V, integrated Intel Arc 140V, approximately 31.57 GiB system RAM and graphics driver 32.0.101.8724. Native Ollama identified `library=Vulkan`, `name=Vulkan0`, and `type=iGPU`. Subsequent inference logs recorded `offloaded 37/37 layers to GPU`; model, KV and compute buffers were allocated on Vulkan0. The model status endpoint reported 9,952,873,676 GPU-allocated bytes, approximately 9.27 GiB. This is integrated shared memory, not proof of dedicated 16 GB VRAM.

The portable Windows ZIP was downloaded from the [official Ollama 0.34.0 release](https://github.com/ollama/ollama/releases/tag/v0.34.0), with its published SHA-256 verified before extraction. The original Docker model manifest and referenced blobs were copied into a separate model store and verified. Native startup needs both `OLLAMA_VULKAN=1` and `OLLAMA_IGPU_ENABLE=1` here: the first discovery attempt explicitly excluded the integrated GPU without the second flag. The flag is defined in [the pinned Ollama configuration source](https://github.com/ollama/ollama/blob/v0.34.0/envconfig/config.go).

New scripts set these variables only for the native child process. They preserve the original Docker services and do not change global environment variables, drivers or startup services. Both servers bind to loopback. Local UAT evidence is sent only to the local model. Raw inputs, generated answers and detailed runtime logs remain in ignored private/runtime directories; linked receipts contain hashes, timings and validation outcomes only.

## Accuracy result

**The full GPU answer failed semantic review.** It misattributed two field names, did not directly establish the acceptance limitation, and suggested a lookup using an unverified cross-system reference assumption. Structural and citation-membership checks passed, but those checks do not prove that claims follow from the sources. Both small synthetic controls passed their reviewed claims. See [the independent source-by-source review](native-gpu-semantic-review-2026-09-13.json).

Acceleration preserves the ability to generate a dynamic response; it does not repair the model's reasoning or field attribution. The website retains the experimental-answer warning. Improving this grounding requires a separately reviewed change and evaluation; it must not silently alter the user's preserved CPU demonstration.

## Runtime validation

The benchmark harness passed 32 focused offline tests. The separate React entry passed its TypeScript check, production build and six cookie-isolation tests. All 40 lifecycle guards passed in both PowerShell 7.6.5 and Windows PowerShell 5.1. They cover process identity, loopback ownership, exited-process races, socket path length and fractional UTC timestamps. These counts are separate suites, not a newly executed whole-project regression.

The actual browser check confirmed login, authorized export selection, the pending timer and disabled submit button, a newly generated response, exact model timing/token metadata, source citations, saved history and the retained experimental warning. The native model status endpoint was empty after the answer, confirming that the website released the loaded model. All four native listeners bound to `127.0.0.1`; original CPU website/API/worker health checks returned HTTP 200. SHA-256 comparisons of nine protected original implementation/configuration files found zero changes.

[Actual HTTP session checks](native-gpu-auth-2026-09-13.json) passed all 11 assertions with eight local requests and zero model calls. A fresh shared cookie jar authenticated both sites; GPU logout expired only `POI_GPU_SESSION`, leaving the CPU session authenticated. Neither existing browser cookies nor cookie values were read or published. The website's claims, uncertainty, suggested checks and citation objects exactly match the reviewed benchmark answer; [semantic review](native-gpu-semantic-review-2026-09-13.json) records this comparison.

The start command correctly returned `already-running` for the verified native stack. An actual stop removed all three new website/API/worker listeners and preserved the independently started native Ollama server and original CPU website. Restart returned `ready`; all four native listeners were again loopback-only, the saved answer's SHA-256 was unchanged, and no model remained loaded. Browser login after restart showed the updated GPU login hint and the preserved saved answer in history. The new website is left running. The [sanitized aggregate receipt](native-gpu-2026-09-13.json) records model provenance, measured timings, validation scopes and protected baseline hashes.

An initial native Java API startup failed in JDK 17's local selector socket creation. A minimal `Selector.open()` / `HttpClient.newHttpClient()` probe reproduced the error with the inherited temporary-directory selection and succeeded with a project-local `jdk.net.unixdomain.tmpdir`. The workaround changes only this API process. [Oracle Java 17 networking documentation](https://docs.oracle.com/en/java/javase/17/core/java-networking.html) defines the property; [OpenJDK's Windows socket discussion](https://mail.openjdk.org/pipermail/nio-dev/2023-March/013297.html) describes alternate directories for filesystem remapping issues. The exact OS remapping cause on this laptop is not established.
