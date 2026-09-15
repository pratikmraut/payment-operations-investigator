# Local inference options for the current laptop

Research checked 2026-09-13. This note uses official project documentation, vendor documentation and original model cards. It records recommendations, not installations or benchmark results. No runtime, driver, model, application code or existing receipt was changed during this research.

**Current decision: preserve the existing approximately ten-minute model and code for the showcase.** Its model, UAT endpoint, context, keep-alive and other runtime settings remain unchanged. All options below are deferred research for a separate, explicitly selectable experimental profile; they are not a replacement plan or work scheduled to run now.

## Recommendation

**For a later experimental profile, first test native Windows Ollama with its Vulkan backend, using the same model and evidence as the preserved baseline.** This offers the smallest potential client change: the experiment can retain ChatOllama and the existing structured generation contract. The first question is whether the Arc 140V is actually discovered and receives model layers. A native process avoids the current container's unverified Intel GPU access path; moving a CPU workload out of a container alone is not a promised acceleration.

Ollama's **v0.34.0** documentation says Vulkan supports Windows/Linux and is enabled by default when its backend is installed. Therefore older instructions describing Vulkan only as an experimental opt-in are not an accurate description of this release. Driver/backend discovery still needs checking on this machine. [Version-pinned Ollama hardware documentation](https://github.com/ollama/ollama/blob/v0.34.0/docs/gpu.mdx), [current GPU selection guidance](https://docs.ollama.com/gpu).

If that experiment fails or is slower, compare native **llama.cpp Vulkan and SYCL** before evaluating NPU serving. OpenVINO GPU is another credible route; each remains an isolated experimental option with its own model/export/context constraints. None replaces the showcase profile.

## Known baseline and what it implies

The root task supplied this measured run: qwen3:8b, Ollama 0.34, Rancher/WSL container, CPU only, 4 inference threads, 32K configured context, approximately 10 GB loaded model/runtime memory. These figures are task-provided observations, not a new benchmark performed for this note.

| Stage | Supplied measurement | Calculated rate/share |
|---|---:|---:|
| Prompt evaluation | 6,474 tokens in 415.8 s | 15.57 tokens/s; 64.94% of total |
| Generation | 460 tokens in 212.2 s | 2.17 tokens/s; 33.14% of total |
| Total | 640.3 s | About 10 min 40 s |
| Remaining time | 12.3 s by subtraction | Includes any other measured overhead; not attributed to a specific cause |

Inference: prompt processing and decoding both need improvement. Network/UI optimization cannot remove the measured model-compute time. Faster decoding alone leaves the larger prompt-processing component.

Intel specifies the 268V as Lunar Lake / Core Ultra Series 2, with **4 performance cores + 4 low-power efficient cores, 8 threads, Arc 140V graphics and a 48-TOPS INT8 NPU**. The task reports 31.6 GiB physical RAM. The actual free memory, driver revision, power mode and sustainable thermal limit remain unmeasured. TOPS is not a prediction of text-generation tokens per second. [Intel 268V specifications](https://www.intel.com/content/www/us/en/products/sku/240958/intel-core-ultra-7-processor-268v-12m-cache-up-to-5-00-ghz/specifications.html).

## Practical runtime choices

| Option | Why it is relevant | Work and limits | Priority |
|---|---|---|---|
| Native Windows Ollama + Vulkan | Keeps the existing Ollama API/client and model format. | Verify backend presence, current Intel driver, GPU detection and actual offload. No Arc 140V speed guarantee. | First hardware experiment |
| Native llama.cpp + Vulkan | Official Windows builds exist; explicit device/offload controls make diagnosis easier. | New serving/client adapter and honest provider metadata are required. Retest the exact schema and chat template. | First fallback |
| Native llama.cpp + SYCL | Its Intel-oriented backend explicitly lists integrated Arc in Lunar Lake as supported. | Select a compatible binary/runtime/driver set. Compare numerical behavior and performance, not only successful loading. | Parallel alternative after a basic baseline |
| OpenVINO GenAI / Model Server on GPU | OpenVINO supports Intel Arc and Windows. Optimum Intel provides model export/compression. | Model preparation and serving integration; verify model and schema support. | Next local runtime option |
| OpenVINO on NPU | Uses the laptop's dedicated accelerator. | Context sizing, compilation, supported model/export format and quality must be evaluated. | Later experiment |
| llama.cpp OpenVINO backend | Can reuse GGUF through an OpenVINO execution backend. | Official documentation still labels performance, accuracy and broader model coverage as work in progress. | Exploratory fallback |
| CPU-only tuning | Requires less deployment work and remains useful if GPU drivers fail. | Compare 4/6/8 threads, memory pressure and prompt size. More threads are not automatically faster. | Small controlled baseline |
| Historical IPEX-LLM forks | Old tutorials promise Intel Ollama/LLM acceleration. | The Intel repository is archived and explicitly reports known security issues and no maintenance. | Do not choose for this new integration |

The ordering above is a suggested sequence for deferred experiments, not a vendor ranking or exhaustive list of local products. Priority labels do not authorize installation or modification of the current showcase profile.

### Native llama.cpp details

The project publishes Windows Vulkan and SYCL packages. Its build guide documents Vulkan device detection and GPU offload. Use a release binary before taking on a toolchain build; record its exact release/hash. [Official releases](https://github.com/ggml-org/llama.cpp/releases/), [build guide](https://github.com/ggml-org/llama.cpp/blob/master/docs/build.md).

The SYCL guide lists Windows 11 and Lunar Lake integrated Arc. Windows release packages include dependent SYCL runtime DLLs, avoiding a full oneAPI installation for that route. Building from source requires the documented Intel compiler/libraries. Some recommended-version tables contain older known-good releases alongside newer backend changes, so do not assume every latest binary is validated on this exact laptop. [SYCL backend guide](https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/SYCL.md).

llama-server supplies OpenAI-style chat and schema-constrained output. Its prompt cache can reuse a common prefix; speculative decoding is also available. These are potential experiments, not drop-in Ollama configuration switches. Preserve provider identity, token counts, citation checks and truncation handling when adding an adapter. [Server API, caching and generation controls](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md).

### OpenVINO / Optimum Intel details

OpenVINO's current system requirements list Intel Arc GPUs, Windows and NPU support with separate hardware drivers. Optimum Intel exports model topology/weights into OpenVINO IR and offers lower-precision export. This requires a matching model, tokenizer and runtime version; do not mix arbitrary latest package versions. [OpenVINO system requirements](https://docs.openvino.ai/2026/about-openvino/release-notes-openvino/system-requirements.html), [Optimum Intel export documentation](https://huggingface.co/docs/optimum-intel/openvino/export).

OpenVINO Model Server documents a Windows executable and JSON-schema response formatting. Its own examples use an OpenAI-style `/v3/chat/completions` endpoint, not Ollama `/api/chat`. Therefore the current ChatOllama transport and hard-coded provider label need an explicit adapter, not just a base-URL substitution. [Structured-response documentation](https://docs.openvino.ai/2026/model-server/ovms_structured_output.html), [chat API](https://docs.openvino.ai/2026/model-server/ovms_docs_rest_api_chat.html).

OVMS also has a GGUF loading route, but the retrieved supported-model list names Qwen2.5, several Llama versions and DeepSeek-R1-Distill-Qwen; it does not establish that the project's exact Qwen3 artifact works unchanged. [OVMS GGUF support](https://docs.openvino.ai/2026/model-server/ovms_demos_gguf.html).

The separate llama.cpp OpenVINO backend lists Core Ultra Series 1/2 validation and CPU/GPU/NPU targets. It warns that accuracy/performance and model/quantization coverage remain under development. Its NPU quantization handling differs from CPU/GPU, including runtime tensor conversions. Do not treat “accepts GGUF” as equivalent to “same numerical behavior on every device.” [llama.cpp OpenVINO backend](https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/OPENVINO.md).

### NPU limitations relevant to this workload

OpenVINO's NPU guide requires specific symmetric 4-bit export settings. Its documented defaults are 1,024 prompt tokens and 128 minimum-response capacity, configurable through `MAX_PROMPT_LEN` / `MIN_RESPONSE_LEN`; this is not an immutable 1K context ceiling. Dynamic prefill arrived in 2025.3. Larger configured contexts and compilation modes affect memory, first-token latency and startup; compiled-model caching can reduce repeated initialization. The guide warns that Series 2 systems may require more than 16 GB RAM for longer prompts with larger models. A 6,474-token evidence input therefore needs deliberate configuration and cannot be assumed to fit or perform well under defaults. [OpenVINO GenAI on NPU](https://docs.openvino.ai/2026/openvino-workflow-generative/inference-with-genai/inference-with-genai-on-npu.html).

Recommendation: evaluate a small supported NPU model first with synthetic structured-output requests, then the actual-sized context. Keep GPU as the initial target for this particular long-prompt assistant. Do not infer that the NPU can transparently accelerate ordinary Ollama, or that advertised TOPS establishes lower latency.

### Maintenance status: avoid obsolete Intel recipes

Intel archived `ipex-llm` on **2026-01-28**. Its README explicitly says development/support are not guaranteed, patches are no longer accepted, and known security issues exist. An archived Intel-specific Ollama distribution is not the preferred foundation for new work. [Intel IPEX-LLM repository](https://github.com/intel/ipex-llm).

Optimum Intel is different: its v2.0 release removed the older IPEX/Intel Neural Compressor integrations and made OpenVINO/NNCF default dependencies. Follow current OpenVINO-oriented documentation rather than combining legacy IPEX instructions with current releases. [Optimum Intel v2.0.0 release notes](https://github.com/huggingface/optimum-intel/releases/tag/v2.0.0).

## Native Windows versus WSL/container GPU access

Native Windows Ollama provides a local HTTP API and has its own Windows logs/model location. This permits a later isolated test on a separate port without redirecting the current application. [Ollama Windows guide](https://docs.ollama.com/windows).

An Intel GPU visible in Windows does not prove it is available inside the Rancher-managed Linux VM or its container. Docker Desktop's published Windows GPU path specifically lists NVIDIA prerequisites; it is not proof of Intel Arc passthrough in Rancher Desktop. [Docker Desktop GPU requirements](https://docs.docker.com/desktop/features/gpu/).

Rancher's 2.0 **alpha announcement**, scoped to that redesign, says GPU support is not yet built. Its older Open WebUI tutorial installs/uses a host Ollama process. These documents do not establish a working Intel GPU path for the project's existing container. Do not upgrade Rancher merely on the assumption that it fixes GPU access. [Rancher 2.0 announcement](https://docs.rancherdesktop.io/blog/welcome-to-rancher-desktop-2/), [host-Ollama tutorial](https://docs.rancherdesktop.io/1.17/tutorials/working-with-llms/).

Intel does document WSL2 GPU workflows with host drivers plus guest runtime components; OpenVINO has WSL/container deployment guidance. Thus “Intel never works in WSL” would also be incorrect. A WSL path needs device/runtime validation at each layer, and the exact Rancher combination is unverified here. [Intel WSL2 setup](https://www.intel.com/content/www/us/en/docs/oneapi/installation-guide-linux/2024-2/configure-wsl-2-for-gpu.html), [OpenVINO container guidance](https://docs.openvino.ai/2026/get-started/install-openvino/install-openvino-docker-linux.html).

## Deferred model, context and caching experiments

Every change in this section belongs only to the future experimental profile. The existing model, prompt, context, keep-alive and endpoint remain the showcase baseline.

1. **Measure quantization before changing it.** Record the model digest, parameter size and `quantization_level` from `/api/show` or `/api/tags`. An “8B” name does not establish the weight format. [Ollama model details](https://docs.ollama.com/api-reference/show-model-details), [model listing](https://docs.ollama.com/api/tags).
2. **Compare a compact instruct model after the runtime test.** Qwen3-4B-Instruct-2507 is explicitly non-thinking; Phi-4-mini-instruct is another original-vendor candidate. Neither model card establishes payment-domain accuracy. Retest observed polarity, field-domain and citation failures on any smaller model instead of assuming a smaller model is sufficient. [Qwen model card](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507), [Microsoft model card](https://huggingface.co/microsoft/Phi-4-mini-instruct).
3. **Quantization is a quality tradeoff.** A supported Q4 artifact is a reasonable comparison point, not an automatic improvement over an existing quantized model. Avoid repeated lossy re-quantization of unknown artifacts. [Ollama import/quantization guide](https://docs.ollama.com/import), [Optimum Intel optimization](https://huggingface.co/docs/optimum-intel/openvino/optimization).
4. **Reduce excessive context allocation only with a verified token budget.** Larger context increases memory demand. The application's conservative UTF-8 bound intentionally overestimates tokens; merely changing 32K to 8K could cause pre-call rejection, while bypassing the guard risks truncating evidence. A future exact tokenizer/template-aware budget could safely test 12K/16K for this workload, retaining every necessary record and caveat. Do not assume this particular prompt fits 8K from one old count. [Ollama context documentation](https://docs.ollama.com/context-length).
5. **Control model warmth and concurrency in the experimental profile.** Record its cold and warm timings under a separately documented, bounded keep-alive; preserve the showcase profile's existing keep-alive. Flash Attention and Q8 KV-cache are separate memory experiments when supported by the selected backend; Q8 uses less cache memory than F16 and can change numerical behavior. Validate logs and accuracy. [Ollama memory, concurrency and KV-cache guidance](https://docs.ollama.com/faq).
6. **Preserve a stable evidence prefix.** The project already places the question after documents. Query-dependent knowledge ordering still changes part of that prefix. A future deterministic ordering or dependency-aware retrieval experiment can improve reuse, but must retain caveats for every included field. Do not treat cached KV reuse as a cached answer, or claim a new model call for reused answer text. [llama.cpp cache semantics](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md).
7. **Reduce repeated serialization and unnecessary generation.** Keep original records/citations stored exactly, but avoid redundant field lists, repeated metadata and duplicated summaries in model context when a lossless representation suffices. Retain changed-field comparisons, scope limitations and unmapped domains. Claims-only output already removes the redundant generated summary field; it did not remove a separate model call. These are project design recommendations, not measured speedups.
8. **Benchmark CPU settings instead of guessing.** Compare 4, 6 and 8 threads with the same prompt and output limit; the CPU has heterogeneous cores. llama-bench supports separate prompt/decode and thread/batch sweeps. CPU BLAS may improve prompt processing but its build guide says it does not improve generation. [Benchmark tool](https://github.com/ggml-org/llama.cpp/blob/master/tools/llama-bench/README.md), [CPU/BLAS documentation](https://github.com/ggml-org/llama.cpp/blob/master/docs/build.md).

Speculative decoding is a later option: its extra draft/verification work and memory may or may not help this laptop, and it does not by itself remove the measured prefill bottleneck. Streaming can show progress earlier but does not reduce inference computation; incomplete JSON should not be presented as a validated answer. [llama.cpp generation controls](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md), [Ollama streaming chat API](https://docs.ollama.com/api/chat).

## Concrete deferred experiment, not executed

**Goal for later authorization:** determine whether native Windows Vulkan materially improves the same model workload in an isolated experimental profile. Preserve the current showcase model, code and serving configuration throughout; compare generated answer quality because identical weights and prompts do not guarantee identical outputs across backends.

1. Record the existing Windows GPU driver, Ollama release, model digest/quantization, context, output cap and baseline receipt. Prepare the same model locally; model download/setup may require internet, but subsequent inference can remain local.
2. Start an isolated native Windows Ollama instance on loopback port **11435**, leaving the current service on 11434 untouched. Use a separate temporary process configuration, `OLLAMA_NO_CLOUD=1`, one parallel request and one loaded model. Vulkan should be available under the documented backend; do not treat setting `OLLAMA_VULKAN=1` alone as evidence it is being used. [Local-only/configuration guidance](https://docs.ollama.com/faq).
3. Schedule the experiment when no showcase request is running and enough memory is free. Record other resident models; leave the showcase model's loading and keep-alive behavior alone. If memory pressure prevents an isolated measurement, defer that run. Run one small original synthetic request. Inspect native logs for the actual Arc/Vulkan device and offloaded layer counts, then inspect `ollama ps` / `/api/ps`. “The laptop has a GPU” and a Task Manager graph are not sufficient identification of the inference backend. [Running-model API](https://docs.ollama.com/api/ps).
4. If GPU discovery succeeds, run the same frozen synthetic structured-output case cold and warm, followed by one actual-sized, locally authorized evidence question. Keep model, prompt, schema, seed/temperature, context and output cap constant against the baseline. Initially retain the known-safe 32K allocation; context reduction is a separate variable.
5. Bound the experiment to at most three short controls and one full-context run, with a **360-second wall-clock cap per run**. Record timeout/OOM/fallback as a failed configuration; do not keep extending the timeout to hide poor responsiveness. A successful short request is not proof of long-context viability.
6. Capture total, load, prefill and decode times; input/output token counts; memory/offload; model/backend versions; schema validation; and human review of unsupported outcomes, exact field mappings and citations. Require acceptable evidence quality as well as latency improvement. No numerical speed target is promised in advance.
7. Keep the experimental service separately addressable. Any later UI integration would add an explicit experimental-profile selection and retain the current showcase profile and endpoint. A container cannot generally use its own `127.0.0.1` to reach a Windows process; verify the actual Rancher host route and bind scope for the experimental profile. Do not expose an unauthenticated inference port to the LAN merely to make connectivity work. This research does not authorize implementing that selector or changing the baseline connection.

When this deferred work is resumed, if native Ollama does not expose usable Vulkan acceleration, repeat a smaller backend-detection test using an official native llama.cpp Vulkan package, then SYCL. Do not install all runtimes simultaneously or change model, quantization, retrieval and hardware backend in the same comparison. Stop the experimental service after testing; the showcase profile remains available with its original configuration.

## What this research cannot establish

- It does not establish the installed Intel driver, free GPU memory, device visibility, sustained power state or actual performance of any proposed backend.
- It does not establish that a new backend cures hallucination. The project has observed incorrect outcome polarity even with a larger model; hardware acceleration addresses latency.
- A valid JSON object and a valid citation ID do not prove that the citation supports the claim. Preserve that distinction in evaluation and UI.
- Generic published performance figures from other CPUs/GPUs are not estimates for this laptop. All proposed alternatives remain unbenchmarked here.
- These are practical local classes of solution. The note does not claim to enumerate every runtime, future driver, external accelerator or remote-serving option.
