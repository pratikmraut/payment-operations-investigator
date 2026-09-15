# Hosted inference options: research notes

Source access date: **2026-09-13**. Read-only research; no account, deployment, purchase, model invocation or export upload was performed. This note covers four alternatives to laptop inference. Oracle options are researched separately.

The private FCR UAT exports are currently authorized for local processing only. A hosted benchmark can start with our original synthetic records. Using the real exports with any provider needs authorization for that specific destination and configuration.

## Decision for this project

**Research recommendation, not a measured result:** first compare a paid Gemini Flash-Lite endpoint with Groq's production GPT-OSS endpoint on synthetic questions using the same source schema and validator. For an approved internal banking deployment, prefer the organization's approved cloud account and private connectivity: Google Cloud's enterprise route, Azure Foundry or AWS Bedrock. Provider choice is separate from factual validation; faster generation and valid JSON do not establish that an answer is supported by a record.

The current integration would need a provider adapter, service credentials, provider-specific structured-output settings, model provenance and error handling. Keep retrieval, source hashes, exact field values and final answer validation in our application. Hosted inference does not require moving the Oracle database or replacing the React dashboard.

## 1. Google Gemini: fast benchmark candidate, two different service routes

`gemini-3.1-flash-lite` is listed as stable, with structured outputs, configurable thinking and a May 2026 model update. It is a sensible low-cost benchmark candidate for bounded questions; its performance on NEFT operational evidence is untested here. [Official model specification](https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-lite)

The March 3 launch report claims 2.5 times faster time to first answer token and 45% faster output than Gemini 2.5 Flash, citing an external benchmark. These are vendor-reported comparisons, **not our application latency** and not proof of payment-domain accuracy. [Google launch report](https://blog.google/innovation-and-ai/models-and-research/gemini-models/gemini-3-1-flash-lite/)

Verified standard Developer API text prices, USD per **1 million tokens**, excluding caching, tools, tax and additional reasoning consumption:

| Model ID | Input | Output, including thinking |
| --- | ---: | ---: |
| `gemini-2.5-flash-lite` | $0.10 | $0.40 |
| `gemini-2.5-flash` | $0.30 | $2.50 |
| `gemini-3.1-flash-lite` | $0.25 | $1.50 |

These are Developer API prices, not a Vertex/enterprise quote. Free access has limits and different data terms. The pricing page was updated September 11, 2026; rates labeled for January 2027 were excluded. [Google pricing](https://ai.google.dev/gemini-api/docs/pricing)

**Developer API:** the terms effective March 23, 2026 distinguish unpaid use from API access through a cloud project with active billing. Unpaid prompts/responses can be used for product improvement and human review; the terms prohibit submitting sensitive, confidential or personal information there. Paid API prompts/responses are not used for product improvement, but limited abuse-monitoring logging and transient storage across countries remain possible. Paid does not mean zero retention or India-only processing. [Gemini API terms](https://ai.google.dev/gemini-api/terms)

**Vertex/Google Cloud enterprise route:** the former Vertex data-governance URL now redirects to Gemini Enterprise Agent Platform documentation. It states that managed-model data is not used for training without permission/instruction, while achieving zero retention requires attention to abuse monitoring, caching and other enabled features. Select the approved endpoint, region and contractual settings before real data use; do not copy Developer API privacy assumptions across products. [Google Cloud data governance, updated September 9, 2026](https://docs.cloud.google.com/gemini-enterprise-agent-platform/resources/zero-data-retention)

## 2. Groq: useful speed comparison with production structured output

Groq's production model table lists these options; the speeds below are **vendor-listed output rates**, not measured end-to-end latency for our 6,500-token context. Prompt processing, network time, reasoning and service load also matter. [Groq production models](https://console.groq.com/docs/models)

| Hosted model ID | USD / 1M input tokens | USD / 1M output tokens | Listed tokens/second |
| --- | ---: | ---: | ---: |
| `openai/gpt-oss-20b` | $0.075 | $0.30 | 1,000 |
| `openai/gpt-oss-120b` | $0.15 | $0.60 | 500 |

These are Groq-hosted open-weight models, not calls to the OpenAI API. Both support strict JSON-schema constrained decoding. Groq currently documents that structured output cannot be combined with streaming or tool use; our final answer call can remain separate from retrieval. A schema can constrain fields and types, but our source-value and semantic checks are still necessary. [Groq structured output](https://console.groq.com/docs/structured-outputs)

Free-plan limits listed for both models include 30 requests/minute, 1,000 requests/day, 8,000 tokens/minute and 200,000 tokens/day. A context of about 6,500 input tokens plus output leaves little free-plan throughput headroom. Actual account limits must be checked; throttling returns 429 and is not a reason to silently switch to fixed answers. [Groq rate limits](https://console.groq.com/docs/rate-limits)

Groq says inference content is not retained by default, with reliability/abuse exceptions that can retain it for up to 30 days. Organization admins can enable zero-data-retention controls, disabling features that require persistence. Usage metadata remains; retained customer content is stored in US GCP buckets. This is a concrete configuration and data-location decision, not a blanket suitability guarantee for bank exports. [Groq data handling](https://console.groq.com/docs/your-data)

## 3. Microsoft Azure Foundry: approved enterprise environment option

For **Models sold by Azure**, Microsoft documents no sharing of prompts/completions with other customers or model providers and no base-model training without permission. Processing geography depends on deployment type: Global may process outside the selected geography, whereas DataZone has its own boundary. Abuse monitoring and stateful features require separate review. This policy must not be generalized to every third-party offer in the catalog. [Foundry data privacy, updated May 19, 2026](https://learn.microsoft.com/en-us/azure/foundry/responsible-ai/openai/data-privacy)

Private endpoints allow public access to be disabled and traffic to use the organization's VNet; on-premises access then needs suitable VPN/ExpressRoute and DNS. This is useful when the bank already operates an approved Azure environment. [Foundry network isolation, updated August 14, 2026](https://learn.microsoft.com/en-us/azure/foundry/how-to/configure-private-link)

Candidate families include Microsoft Phi, Meta Llama and Mistral. Choose an actually available model/deployment whose structured-output behavior passes our contract. Serverless token billing and dedicated GPU hosting are different cost models. No exact Azure model/region price was verified here: the pricing URL returned an error, so this note makes no monthly-cost or free-tier claim. [Official serverless model/inference overview](https://learn.microsoft.com/en-us/azure/ai-foundry/concepts/models-featured)

## 4. Amazon Bedrock: approved AWS environment option

Bedrock provides private VPC access through AWS PrivateLink. Traffic from a VPC can reach the service without public IPs or an internet gateway; an on-premises laptop still needs a connection into that VPC. [Bedrock private endpoints](https://docs.aws.amazon.com/bedrock/latest/userguide/vpc-interface-endpoints.html)

AWS documents latency-optimized inference for specific model/region combinations, including `amazon.nova-pro-v1:0`, Claude 3.5 Haiku and Llama 3.1 variants, using cross-region inference. This is not a universal acceleration switch and does not establish India-only processing or our response time. Check model lifecycle, endpoint availability and the approved geography before selecting a deployment. [Bedrock latency optimization](https://docs.aws.amazon.com/bedrock/latest/userguide/latency-optimized-inference.html)

Structured outputs need supported models and a supported JSON Schema subset. A new schema can take a few minutes to compile; identical schemas are cached for 24 hours. This makes fixed-schema prewarming relevant. Native Anthropic citations cannot be combined with that mode, but our application-owned evidence IDs are a separate response-field design. [Bedrock structured output](https://docs.aws.amazon.com/bedrock/latest/userguide/structured-output.html)

Avoid the blanket statement that every Bedrock request has zero retention. Current documentation makes retention depend on the model, endpoint and effective retention mode; `store=false` alone is insufficient for all models. Verify the chosen model's allowed mode and account policy before use. No Bedrock model-specific price was captured from the dynamic price tables; quote the selected region/model/service tier before deployment. [Bedrock retention](https://docs.aws.amazon.com/bedrock/latest/userguide/data-retention.html), [Bedrock pricing](https://aws.amazon.com/bedrock/pricing/)

## Illustrative cost arithmetic and benchmark acceptance

For **6,500 input + 500 billable output tokens** per answer, the listed standard text rates imply approximately $0.00085 for Gemini 2.5 Flash-Lite, $0.00320 for Gemini 2.5 Flash, $0.002375 for Gemini 3.1 Flash-Lite, $0.0006375 for Groq GPT-OSS 20B or $0.001275 for Groq GPT-OSS 120B. These are arithmetic examples, not invoices: provider tokenizers, reasoning tokens, retries, caching, tools and pricing changes affect the result. Rate sources are the Google and Groq tables above.

Use the same original synthetic source bundles and question set for each candidate. Measure completed-answer p50/p95, time to first token if applicable, timeout/429 rates, total billable tokens, cost, exact field-value correctness, unsupported conclusions and correct uncertainty. Include warm and cold/schema-first requests. A fast provider only qualifies after these accuracy and failure-handling checks pass.

## Research limits

No service was benchmarked or integrated. No guarantee of any account's model availability, production quota, latency, price, retention configuration or bank approval is made. Newer catalog entries whose availability or cross-page consistency was uncertain were not selected. Older launch evidence is labeled with its publication date; current pricing and model specifications were checked separately. This note does not investigate every provider or claim that a low-cost model is the most accurate.
