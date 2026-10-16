# Automatic Amazon Bedrock pricing — design

## Intent

Today every model price is typed by hand in `ai-insights.pricing.models`. When AWS changes a price, or a
team switches model and forgets to update the YAML, the recorded cost is silently wrong (a new model is at
least flagged as unpriced; a changed price is not flagged at all).

A live check on 2026-10-16 showed the hand-typed approach is already wrong for common setups: in
`eu-west-1`, Claude Sonnet 4.5/4.6 cost **3.30 / 16.50** per 1M input/output tokens through an `eu.`
(geographic) inference profile, but **3.00 / 15.00** through a `global.` profile. The README example uses
3.00 / 15.00 and substring matching applies it to `eu.` model IDs, understating cost by 10%.

Goal: for Amazon Bedrock models, take prices from the AWS Price List API automatically and keep them fresh,
so cost stays right without anyone maintaining a price table.

## Goals

- Opt-in AWS price source for Bedrock models, refreshed periodically, no restart needed.
- Correct price per inference-profile scope: in-region / geographic (`eu.`, `us.`, `apac.`, …) vs `global.`.
- Input, output, cache-read and cache-write (5-minute) prices.
- Never break the application: AWS unreachable, missing IAM permission or unknown model only degrades to
  the YAML price (or "unpriced"), with a warning.

## Non-goals

- Negotiated / EDP / private-offer discounts. The Price List API returns public list prices only; the
  Cost and Usage Report is the source of truth for invoiced amounts. (Decided: AWS list price wins over
  YAML, so discounts are not represented while AWS pricing is on.)
- Non-Bedrock providers (OpenAI, Azure OpenAI, Anthropic API, …): no public pricing API; they keep YAML.
- Batch, provisioned throughput / reserved TPM, 1-hour / 30-minute cache writes, long-context tiers,
  priority/flex tiers.
- Non-token models (embeddings priced per image/second/request, video models).
- Re-pricing turns already stored. Cost is computed at capture time and stored per model call; a refreshed
  price applies to calls captured after the refresh. History keeps the price that was in effect.
- Dashboard changes (e.g. showing where a price came from).

## Anti-goals (would make this a failure even if it works)

- Startup fails or slows down because AWS pricing is unreachable.
- A Pricing API call on the request path of a chat call.
- Silently mispricing: a model must never get a price from the wrong model or wrong profile scope.

## Decisions taken with the user

| Topic | Decision |
|---|---|
| Activation | Opt-in: `ai-insights.pricing.aws.enabled=true` (default `false`). |
| Packaging | Inside the existing `spring-ai-insights-bedrock` module (already AWS-SDK based, opt-in by dependency). |
| Precedence | **AWS wins.** YAML is used only for models AWS has no price for, or before the first successful load / while AWS is unreachable. |
| Long-context tiers | Ignored; standard price always used; limitation documented. |

## Calls made by the designer (check these)

- **Custom `PriceCatalog` bean wins over everything.** If the application defines its own `PriceCatalog`
  bean, AWS pricing is not applied (standard Spring Boot back-off), and a startup info log says so when
  `aws.enabled=true`. The AWS source replaces only the default YAML-built catalog, wrapping it as fallback.
- **Fail fast on misconfiguration.** `aws.enabled=true` without the `spring-ai-insights-bedrock` module or
  the `pricing`/`bedrock` SDK jars on the classpath fails startup with a message naming the missing
  dependency (same style as the existing `MONGODB_MISSING` check), rather than silently using stale YAML.
  Same for a non-USD `currency`.
- **Classify by attributes, not usagetype names** (see below), because AWS uses two naming schemes.

## How it works

### Data sources (verified live against `eu-west-1`)

1. **`bedrock:ListFoundationModels`** (control plane, the configured Bedrock region) maps a base model ID to
   its `modelName`, e.g. `anthropic.claude-sonnet-4-6` → `Claude Sonnet 4.6`.
2. **`pricing:GetProducts`** with `ServiceCode=AmazonBedrockFoundationModels` and
   `regionCode=<bedrock region>` returns one product per price dimension. Relevant attributes:
   `model` (= `modelName` above, e.g. `Claude Sonnet 4.6`), `servicename`
   (`Claude Sonnet 4.6 (Amazon Bedrock Edition)`), `usagetype`, and the on-demand price with its unit
   (observed: `1M tokens`). One region returned 280 products, so one paginated call per refresh is enough.

`usagetype` names come in **two schemes** (both observed in eu-west-1, after a region prefix such as
`EU-MP:EU_`): older models use `InputTokenCount-Units` / `InputTokenCount_Global-Units` /
`CacheWrite1hInputTokenCount…`; newer models (Sonnet 5, Opus 4.8+, Haiku 5.5) use
`input_tokens_standard-Units` / `input_tokens_global_standard-Units` / `cache_write_tokens_1h_…` /
`…_long_ctx_…`. So classification relies primarily on the stable attributes, not on the usagetype name
*(builder's call, recorded here)*:

- keep only `feature = "On-demand Inference"` and price unit `1M tokens` (or `1K tokens`, normalised);
- dimension from `inferenceType`: `Input tokens` → input, `Output tokens` → output,
  `Prompt cache read input tokens` → cache read, `Prompt cache write input tokens` → cache write;
- scope: usagetype contains `global` (case-insensitive) → GLOBAL, else GEO;
- drop usagetypes containing (case-insensitive) `batch`, `long_ctx`, `1h`, `30m`, `tpm`, `provisioned`;
  cache write is therefore the default (5-minute) write price.

If, after this filtering, a model/scope still has two different prices for the same dimension, treat that
model/scope as having no AWS price (fall back to YAML) and warn once — never pick one arbitrarily.

**Long-context tiers** (`long_ctx`, e.g. Haiku 5.5, GPT-6 Astra) are ignored: the standard price is always
used. Decided with the user. The threshold is not in the price list, and very long prompts on those models
are therefore understated; the README must say so.

### Resolving a captured model ID

1. If the ID is an ARN or otherwise not a plain model / system-defined profile ID (e.g. an application
   inference profile ARN), AWS pricing does not apply → fall back to YAML.
2. Strip a profile prefix (`global.` → scope GLOBAL; `eu.`, `us.`, `apac.` and any other short
   `xx.` geo prefix → scope GEO; no prefix → scope GEO, the in-region price, which uses the same
   non-`_Global` usagetypes).
3. Base model ID → `modelName` via the ListFoundationModels map → price dimensions for that name and scope.
4. A price is used only if **input and output** are both found; missing cache dimensions default to 0
   (Anthropic models are expected to have them). Unit is normalised to per-1M tokens (`1K tokens` × 1000).
5. Otherwise fall back to the YAML catalog (existing exact/substring rules), then "unpriced" as today.

`inputIncludesCached` is `false` for Bedrock (Bedrock reports cached tokens separately).

### Lifecycle

- Loaded in the background right after startup, then every `refresh` (default `24h`, minimum `1h`).
- A failed load or refresh logs one warning and keeps the last good table (or none yet → YAML).
- Lookups read an immutable in-memory snapshot (no I/O on the chat path).
- AWS clients: reuse a unique `BedrockClient` / `PricingClient` bean if present, else build and close own
  ones, created lazily at first refresh (same pattern as `InsightsBedrockAutoConfiguration`).

### Observability

- Info log after each successful load: region, number of models priced.
- Warn once per model when a YAML entry exists for a model AWS also prices with a different value
  ("YAML price ignored, AWS list price used"), so stale YAML is visible.
- Metric `ai.insights.pricing.refresh` (counter, tag `outcome=success|failure`). Existing
  `ai.insights.unpriced.models` keeps working for models neither source prices.

## Configuration

```yaml
ai-insights:
  pricing:
    currency: USD               # must be USD when aws.enabled=true
    aws:
      enabled: true             # default false
      region: eu-west-1         # Bedrock region to price; default spring.ai.bedrock.aws.region, else SDK default
      endpoint-region: us-east-1  # Price List API endpoint: us-east-1 (default), eu-central-1 or ap-south-1
      refresh: 24h              # ≥ 1h
    models: []                  # still used for non-Bedrock models and as fallback
```

`aws.enabled=true` with `currency` other than `USD` fails startup with a clear message (the Price List API
is USD only; mixing currencies would corrupt totals).

Requirements, documented in the README:
- IAM: `pricing:GetProducts`, `bedrock:ListFoundationModels`.
- Egress to `api.pricing.<endpoint-region>.amazonaws.com` (corporate proxies such as Zscaler may block it;
  this was observed while designing) and to the Bedrock control-plane endpoint.
- Dependencies `software.amazon.awssdk:pricing` and `software.amazon.awssdk:bedrock`.

## Constraints

- Java 21, Spring Boot 4.1, AWS SDK v2 versions from the root `awsSdkVersion` BOM.
- Existing `PriceCatalog` users who define their own bean (`@ConditionalOnMissingBean`) must keep working.
- Tests must not call AWS: use recorded `GetProducts` / `ListFoundationModels` responses as fixtures.
  Record them from real AWS (eu-west-1, `ServiceCode=AmazonBedrockFoundationModels`; trimmed to a handful
  of models covering both usagetype schemes, a `global.`/`eu.` pair, a long-context model and a non-token
  model). Locally: `AWS_CA_BUNDLE=$HOME/.aws/ca/combined-ca-bundle.pem AWS_PROFILE=default aws pricing
  get-products --region us-east-1 …` works; other profiles had expired credentials on 2026-10-16.
  Hand-written fixtures are not acceptable for the parser tests.
- Follow the existing autoconfig conventions (`ai-insights.enabled` gate, `@ConditionalOnClass` on the
  module class and SDK clients).

## Left to the builder

- How the AWS source plugs into pricing: e.g. turn `PriceCatalog` into an interface / add a lookup
  abstraction, or compose a new catalog that wraps the YAML one. The library is unreleased (0.1.0-SNAPSHOT),
  so changing the `PriceCatalog` type is acceptable if the override-the-bean path still works.
- Whether the SDK dependencies are `api` or `compileOnly` (prefer whatever keeps knowledge-base-only users
  from pulling extra jars without making pricing setup awkward; document it). Either way, the fail-fast
  check above must fire when `aws.enabled=true` and they are missing.
- Exact metric/log wording, class names, scheduler implementation.
- Confirm which model ID Spring AI's Bedrock Converse integration reports in response metadata (expected:
  the configured ID, e.g. `eu.anthropic.claude-sonnet-4-6`); adapt step 1–2 of the resolution if it differs.
- README: new "Automatic Bedrock pricing" subsection, properties table rows, and fix the existing pricing
  example to call out geo vs global prices.
