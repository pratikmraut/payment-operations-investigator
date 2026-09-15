# OCI inference options: research notes

Checked 13 September 2026 against public primary sources. Research only: no cloud inference, provisioning, purchase or transfer of private exports occurred. Recommendations below are hypotheses to benchmark, not measured performance for this application.

## Recommendation

Evaluate **OCI Generative AI on-demand inference as an additional selectable mode**, retaining the existing React/Java/LangGraph application and current local Ollama implementation. The user explicitly requested preserving the approximately ten-minute local baseline and its code for showcasing; future optimization must not overwrite that baseline. Prefer **Cohere Command A** as the first Oracle-hosted India candidate, and **gpt-oss-20b** as a low-cost comparison where the permitted region supports on-demand access. For synthetic demonstrations, also compare Gemini Flash and Flash-Lite. Oracle supplies the managed service; these are foundation models from Cohere, OpenAI and Google, not evidence of an Oracle-trained payment model. The model still needs our field definitions and evidence. [1–5]

Shortlist model identifiers and intended comparison:

| Model ID | Why evaluate it |
| --- | --- |
| `cohere.command-a-03-2025` | Oracle documents RAG, tools and enterprise tasks; 256K context and a 4K on-demand response cap. Large context capacity is not a reason to send every document. [2] |
| `openai.gpt-oss-20b` | Open-weight text/reasoning model; an inexpensive hosted candidate. Do not assume its 21B total size implies laptop suitability or its cloud latency. [3] |
| `openai.gpt-oss-120b` | Higher-capacity accuracy comparator using the same OCI family; run only if the smaller model misses evidence checks. [1,6] |
| `google.gemini-2.5-flash` | Google model offered through OCI, intended to balance speed and capability. [4] |
| `google.gemini-2.5-flash-lite` | Google model offered through OCI, positioned for low-latency, less complex tasks; structured output listed. [5] |

## Region and hosting boundary

Mumbai is absent from the current model-region matrix. Hyderabad supports Command A on-demand; gpt-oss 20B/120B there require dedicated clusters. Osaka, Frankfurt and Chicago support on-demand gpt-oss. Hyderabad exposes Gemini Flash, but Flash-Lite is unavailable there and in Osaka; Frankfurt and US regions offer Flash-Lite. [7]

Gemini uses external Google hosting. Oracle specifically says Hyderabad/Osaka Flash processing can occur globally; an Indian OCI endpoint therefore does not establish India-only processing. Grok also uses external xAI hosting. [7]

Oracle's general data-handling FAQ says inference inputs/outputs are not retained and not shared with model providers. That broad statement conflicts with the specific external-call routing disclosures. Apply the model-specific hosting disclosure and verify contractual processing terms for the chosen deployment; do not promise that every OCI model keeps data inside Oracle or India. [7,8]

For this project, begin any paid-provider benchmark with newly generated synthetic evidence. The existing private-UAT authorization covers local processing; it is not a new authorization to transmit employer records to cloud inference.

## Published price illustrations

The Oracle global USD price list currently retrieved has 127 pages. Relevant printed pages: 9 (Cohere), 11 (Gemini), 13 (gpt-oss). The dynamic web list exposed units but blank prices to the text reader; the PDF supplies numbers. PDF screenshot retrieval failed, so these values were verified from extracted PDF text and its SKU ordering, not visual screenshots. Pricing can change and actual tenancy billing, taxes, currency and additional services must be checked before spending. [6]

| Model | Input USD / 1M tokens | Output USD / 1M tokens | Calculated 6,500 input + 500 output tokens |
| --- | ---: | ---: | ---: |
| gpt-oss-20b | 0.07 | 0.30 | $0.000605 |
| gpt-oss-120b | 0.15 | 0.60 | $0.001275 |
| Gemini 2.5 Flash | 0.30 | 2.50 | $0.003200 |
| Gemini 2.5 Flash-Lite | 0.10 | 0.40 | $0.000850 |

These are arithmetic examples, not actual billing or a tokenizer comparison. Models tokenize differently; reasoning, retries and other chargeable output can increase usage. [6]

Command A maps to **Large Cohere: $0.0156 per 10,000 transactions**. Here a transaction means a **character**, not a payment or a model request; input and output characters are charged. For an illustrative 30,000 total characters the calculation is `$0.0156 × 3 = $0.0468`. Do not compare this unit directly with token prices. [2,6,9]

On-demand avoids provisioning a dedicated cluster. Dedicated inference is a separate unit-hour commitment; use its shape/unit calculator rather than reading `$1 per AI unit-hour` as a complete GPU-server price. It is not the first choice for this portfolio experiment. [3,6]

## Integration and safeguards

The current LangChain integration documents `langchain-oci` plus `oci`, importing `ChatOCIGenAI` from `langchain_oci`. Configure model ID, regional service endpoint and compartment OCID. OCI config signing, session tokens and instance principals are documented; keep credentials in backend configuration. The integration advertises structured output, token usage, streaming and async calls, but model-specific behavior still needs contract tests. Add a separate OCI adapter and explicit mode selection when implementation is authorized, preserving the existing local baseline and its saved results. [10]

OCI also supports service-specific bearer API keys, distinct from IAM signing-key pairs. Its API-key page lists Meta, xAI and gpt-oss for Chat Completions and narrower Responses support; it does not establish this bearer path for Cohere. A newer OpenAI-compatible endpoint page documents `/openai/v1`, while the API-key page still illustrates `/20231130/actions/v1`. Confirm endpoint/model compatibility against the selected SDK and tenancy rather than mixing the two examples. OCI credentials are used, not an OpenAI account key. [11,12]

Use chat-only, compartment-scoped IAM permission and a model-ID allowlist when supported. Other broader cumulative IAM policies can defeat the restriction. No new key or policy was created in this research. [13]

OCI SDK exposes Cohere response formatting and generic response-format/reasoning controls. The generic API describes reduced reasoning effort as potentially faster and counts reasoning tokens toward completion limits. Validate exact model support before enabling a parameter. JSON-schema conformance cannot establish that a cited field exists or that a payment was credited; keep exact field/value checks and uncertainty evaluation. [14,15]

Recommended experiment: identical original synthetic questions/evidence across candidates, strict evidence validation, one model call per answer, concise output, reduced relevant context, then record first-token latency, completed-answer p50/p95, invalid-field count, correct abstentions, token usage and actual cost. Do not treat streaming alone as faster completed inference, or add an agent/tool round trip to a question already answered by the supplied snapshot.

## Lifecycle and performance limits

All shortlist models are currently listed as not retired. Command A and gpt-oss use a replacement-linked retirement policy; Gemini 2.5 entries say not before 28 January 2027. Older Command R/R+ August 2024 models retired on 30 July 2026, so old tutorials are not a good default. Some older embedding offerings retire on 30 September 2026. [16]

Oracle publishes dedicated gpt-oss-20b H100 benchmarks (for example, random-length workload, concurrency one: approximately 1.10 seconds/request with mean 480 input and 300 output tokens). That is evidence that accelerated inference can be fast, **not** an on-demand SLA, a 6,500-token test, network-inclusive browser timing or a promise for our application. Benchmark locally against our acceptance suite before setting a product target. [17]

## Sources

1. [Oracle pretrained model catalog](https://docs.oracle.com/en-us/iaas/Content/generative-ai/pretrained-models.htm).
2. [Oracle: Cohere Command A](https://docs.oracle.com/en-us/iaas/Content/generative-ai/cohere-command-a-03-2025.htm).
3. [Oracle: gpt-oss-20b](https://docs.oracle.com/en-us/iaas/Content/generative-ai/openai-gpt-oss-20b.htm).
4. [Oracle: Gemini 2.5 Flash](https://docs.oracle.com/en-us/iaas/Content/generative-ai/google-gemini-2-5-flash.htm).
5. [Oracle: Gemini 2.5 Flash-Lite](https://docs.oracle.com/en-us/iaas/Content/generative-ai/google-gemini-2-5-flash-lite.htm).
6. [Oracle global USD price list, 127-page version](https://www.oracle.com/middleeast-ar/a/ocom/docs/corporate/pricing/oracle-paas-and-iaas-global-price-list.pdf).
7. [Oracle model regions and external-call disclosures](https://docs.oracle.com/en-us/iaas/Content/generative-ai/model-endpoint-regions.htm).
8. [Oracle inference data handling](https://docs.oracle.com/en-us/iaas/Content/generative-ai/data-handling.htm).
9. [Oracle on-demand billing units](https://docs.oracle.com/en-us/iaas/Content/generative-ai/pay-on-demand.htm).
10. [LangChain: OCI integration](https://docs.langchain.com/oss/python/integrations/chat/oci_generative_ai).
11. [Oracle Generative AI API keys](https://docs.oracle.com/en-us/iaas/Content/generative-ai/api-keys.htm).
12. [Oracle OpenAI-compatible endpoints](https://docs.oracle.com/en-us/iaas/Content/generative-ai/openai-compatible-api.htm).
13. [Oracle model-level IAM restrictions](https://docs.oracle.com/en-us/iaas/Content/generative-ai/limit-model-access.htm).
14. [OCI Python SDK: CohereChatRequest](https://docs.oracle.com/en-us/iaas/tools/python/latest/api/generative_ai_inference/models/oci.generative_ai_inference.models.CohereChatRequest.html).
15. [OCI Python SDK: GenericChatRequest](https://docs.oracle.com/en-us/iaas/tools/python/latest/api/generative_ai_inference/models/oci.generative_ai_inference.models.GenericChatRequest.html).
16. [Oracle on-demand retirement dates](https://docs.oracle.com/en-us/iaas/Content/generative-ai/deprecating-on-demand.htm).
17. [Oracle dedicated gpt-oss-20b benchmark](https://docs.oracle.com/en-us/iaas/Content/generative-ai/benchmark-openai-gpt-oss-20b.htm).
