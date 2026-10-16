# Plan 5: Automatic Amazon Bedrock pricing

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Price Amazon Bedrock model calls from the AWS Price List API, refreshed periodically, with YAML prices as fallback.

**Architecture:** `PriceCatalog` becomes an interface; today's YAML class becomes `ConfiguredPriceCatalog`. The
`spring-ai-insights-bedrock` module gains `AwsPriceCatalog`, which holds an immutable snapshot built from
`bedrock:ListFoundationModels` + `pricing:GetProducts` and falls back to the YAML catalog. A new
`InsightsBedrockPricingAutoConfiguration`, ordered before `InsightsAutoConfiguration`, registers it when
`ai-insights.pricing.aws.enabled=true`; the YAML bean backs off via its existing `@ConditionalOnMissingBean`.

**Tech Stack:** Java 21, Spring Boot 4.1, AWS SDK v2 (`pricing`, `bedrock`; JSON via
`software.amazon.awssdk.protocols.jsoncore.JsonNode` from `json-utils`, already transitive), Micrometer, JUnit 5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-10-16-aws-pricing-design.md`

## Global Constraints

- Gradle must run on JDK 21: every `./gradlew …` below means `./gradlew -Dorg.gradle.java.home=$HOME/.sdkman/candidates/java/21.0.11-amzn …`.
- AWS SDK versions come from the root `awsSdkVersion` BOM; never pin versions in module build files.
- Opt-in: `ai-insights.pricing.aws.enabled` defaults to `false`.
- AWS price wins over YAML; YAML is the fallback for models AWS does not price, before the first successful load, and while AWS is unreachable.
- Never fail or block startup because AWS is unreachable; never call AWS on the chat path.
- `aws.enabled=true` with `ai-insights.pricing.currency` other than `USD` fails startup; `aws.enabled=true` without the bedrock module or the `pricing`/`bedrock` SDK jars fails startup (unless the app defines its own `PriceCatalog` bean).
- A user-defined `PriceCatalog` bean wins over everything; AWS pricing is then not applied and one info log says so.
- `refresh` default `24h`, minimum `1h`. `endpoint-region` default `us-east-1`.
- Ignored dimensions: batch, long-context, 1h/30m cache writes, reserved/provisioned. Long-context is ignored by user decision; the README states very long prompts on such models are understated.
- Tests never call AWS. Parser tests use fixtures recorded from real AWS (Task 2), not hand-written ones.
- Commit messages end with `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

## Review Focus

1. Application inference profile ARN as model ID (`arn:aws:bedrock:…:application-inference-profile/…`) → YAML price, no exception. Test in Task 3.
2. First refresh fails (expired credentials, or a proxy returning an HTML 403 page) → `find` returns the YAML price, refresh failure counter = 1, nothing escapes. Tests in Tasks 2 and 3.
3. Model known to Bedrock but absent from the price list → YAML price. Test in Task 3.
4. A YAML substring entry such as `claude-sonnet-4` also matching `eu.anthropic.claude-sonnet-4-6` → AWS price used, one warning naming the model. Test in Task 3.
5. Price changes between two refreshes → the next `find` returns the new price without restart. Test in Task 3.

---

### Task 1: Turn `PriceCatalog` into an interface

**Files:**
- Modify: `spring-ai-insights-core/src/main/java/io/github/springaiinsights/pricing/PriceCatalog.java`
- Create: `spring-ai-insights-core/src/main/java/io/github/springaiinsights/pricing/ConfiguredPriceCatalog.java`
- Modify: `spring-ai-insights-autoconfigure/src/main/java/io/github/springaiinsights/autoconfigure/InsightsAutoConfiguration.java:83-88`
- Modify: every `new PriceCatalog(` (find with `grep -rn "new PriceCatalog(" --include=*.java .`) → `new ConfiguredPriceCatalog(`
- Test: `spring-ai-insights-core/src/test/java/io/github/springaiinsights/pricing/ConfiguredPriceCatalogTest.java`

**Interfaces:**
- Produces:
  - `public interface PriceCatalog { String currency(); Optional<ModelPrice> find(String modelId); }`
  - `public final class ConfiguredPriceCatalog implements PriceCatalog`, `public ConfiguredPriceCatalog(String currency, List<ModelPrice> prices)` — the current class body, unchanged behaviour (exact match, else longest contained key).
  - In `InsightsAutoConfiguration`: package-private `static ConfiguredPriceCatalog configuredPriceCatalog(InsightsProperties properties)`, used by the existing `insightsPriceCatalog` bean and by Task 4.

- [ ] **Step 1: Write the failing test** `ConfiguredPriceCatalogTest`

```java
@Test
void exactMatchWinsThenLongestContainedKey() {
    PriceCatalog catalog = new ConfiguredPriceCatalog("USD", List.of(price("gpt-4o"), price("gpt-4o-mini")));
    assertThat(catalog.find("gpt-4o-mini-2024").orElseThrow().model()).isEqualTo("gpt-4o-mini");
    assertThat(catalog.find("gpt-4o").orElseThrow().model()).isEqualTo("gpt-4o");
    assertThat(catalog.find("claude")).isEmpty();
    assertThat(catalog.currency()).isEqualTo("USD");
}
```

- [ ] **Step 2: Run it, expect a compile failure** (`ConfiguredPriceCatalog` missing)

Run: `./gradlew :spring-ai-insights-core:test --tests '*ConfiguredPriceCatalogTest'`

- [ ] **Step 3: Implement** — move the body to `ConfiguredPriceCatalog`, make `PriceCatalog` the interface, extract `configuredPriceCatalog(...)`, update call sites.

- [ ] **Step 4: Run all tests, expect PASS**

Run: `./gradlew test`

- [ ] **Step 5: Commit** — `refactor: make PriceCatalog an interface`

---

### Task 2: Parse the Bedrock price list and model IDs

**Files:**
- Modify: `spring-ai-insights-bedrock/build.gradle.kts` (add `api("software.amazon.awssdk:pricing")`, `api("software.amazon.awssdk:bedrock")`; update `description` to mention pricing)
- Create: `spring-ai-insights-bedrock/src/main/java/io/github/springaiinsights/bedrock/pricing/PriceScope.java`
- Create: `…/bedrock/pricing/BedrockModelIds.java`
- Create: `…/bedrock/pricing/BedrockPriceList.java`
- Create (recorded): `spring-ai-insights-bedrock/src/test/resources/bedrock-pricing/eu-west-1-products.jsonl`
- Test: `…/bedrock/pricing/BedrockModelIdsTest.java`, `…/bedrock/pricing/BedrockPriceListTest.java`

**Interfaces:**
- Produces:
  - `public enum PriceScope { GEO, GLOBAL }`
  - `public record ScopedModelId(String baseModelId, PriceScope scope)`
  - `public static Optional<ScopedModelId> BedrockModelIds.resolve(String modelId)`
  - `public record PriceKey(String modelName, PriceScope scope)`
  - `public record ParsedPrices(Map<PriceKey, ModelPrice> prices, Set<PriceKey> ambiguous)`
  - `public static ParsedPrices BedrockPriceList.parse(List<String> products)` — `products` are the raw JSON strings of `GetProductsResponse.priceList()`; `ModelPrice.model()` = model name, `inputIncludesCached` = `false`.

- [ ] **Step 1: Record the fixture from real AWS** (the `default` profile worked on 2026-10-16)

```bash
AWS_CA_BUNDLE=$HOME/.aws/ca/combined-ca-bundle.pem AWS_PROFILE=default aws pricing get-products \
  --region us-east-1 --service-code AmazonBedrockFoundationModels \
  --filters Type=TERM_MATCH,Field=regionCode,Value=eu-west-1 --output json \
| python3 -c "import json,sys
keep={'Claude Sonnet 4.6','Claude Haiku 5.5','Claude Opus 5','Embed v4'}
for s in json.load(sys.stdin)['PriceList']:
    if json.loads(s)['product']['attributes'].get('model') in keep: print(s)" \
> spring-ai-insights-bedrock/src/test/resources/bedrock-pricing/eu-west-1-products.jsonl
```

Expected: one JSON product per line, covering both usagetype schemes (`InputTokenCount…` for Sonnet 4.6,
`input_tokens_…` for Haiku 5.5 / Opus 5), plus `long_ctx`, `1h`, `Batch`, `Reserved` lines and a non-chat model.

- [ ] **Step 2: Write the failing tests**

`BedrockModelIdsTest`:

```java
@ParameterizedTest
@CsvSource({
    "eu.anthropic.claude-sonnet-4-6, anthropic.claude-sonnet-4-6, GEO",
    "global.anthropic.claude-sonnet-4-6, anthropic.claude-sonnet-4-6, GLOBAL",
    "us.anthropic.claude-sonnet-4-5-20250929-v1:0, anthropic.claude-sonnet-4-5-20250929-v1:0, GEO",
    "apac.anthropic.claude-haiku-5-5, anthropic.claude-haiku-5-5, GEO",
    "anthropic.claude-opus-5, anthropic.claude-opus-5, GEO" })
void resolvesProfilePrefix(String id, String base, PriceScope scope) {
    assertThat(BedrockModelIds.resolve(id)).contains(new ScopedModelId(base, scope));
}

@ParameterizedTest
@ValueSource(strings = { "arn:aws:bedrock:eu-west-1:123456789012:application-inference-profile/abc", "gpt-4o", "" })
void notABedrockModelId(String id) {
    assertThat(BedrockModelIds.resolve(id)).isEmpty();
}
```

Rule: starts with `arn:` → empty. A leading segment of 2–6 lowercase letters followed by `.` where the rest
still contains a `.` is a profile prefix (`global` → GLOBAL, otherwise GEO). What remains must contain `.`
(`provider.model`), else empty.

`BedrockPriceListTest` (loads the fixture lines; values per 1M tokens; compare with `isEqualByComparingTo`):

```java
@Test void oldSchemeGeoAndGlobal() {                       // Claude Sonnet 4.6
    assertPrice(GEO,    "Claude Sonnet 4.6", "3.30", "16.50", "0.33", "4.125");
    assertPrice(GLOBAL, "Claude Sonnet 4.6", "3.00", "15.00", "0.30", "3.75");
}
@Test void newSchemeIgnoresLongContextAndOneHourCache() {  // Claude Haiku 5.5
    assertPrice(GEO,    "Claude Haiku 5.5", "0.11", "0.55", "0.011", "0.1375");
    assertPrice(GLOBAL, "Claude Haiku 5.5", "0.10", "0.50", "0.01",  "0.125");
}
@Test void newSchemeOpus() {                               // Claude Opus 5
    assertPrice(GEO,    "Claude Opus 5", "5.50", "27.50", "0.55", "6.875");
    assertPrice(GLOBAL, "Claude Opus 5", "5.00", "25.00", "0.50", "6.25");
}
@Test void modelsWithoutInputAndOutputPricesAreSkipped() {
    assertThat(parsed.prices().keySet()).extracting(PriceKey::modelName).doesNotContain("Embed v4");
}
@Test void conflictingPricesMakeTheKeyAmbiguous() {
    // fixture + a copy of the Sonnet 4.6 global input line with 3.0000000000 replaced by 9.0000000000
    ParsedPrices p = BedrockPriceList.parse(withConflictingSonnetGlobalInput());
    assertThat(p.prices()).doesNotContainKey(new PriceKey("Claude Sonnet 4.6", GLOBAL));
    assertThat(p.ambiguous()).containsExactly(new PriceKey("Claude Sonnet 4.6", GLOBAL));
    assertThat(p.prices()).containsKey(new PriceKey("Claude Sonnet 4.6", GEO));
}
@Test void malformedProductIsSkipped() {
    assertThat(BedrockPriceList.parse(List.of("<html>403</html>")).prices()).isEmpty();
}
```

- [ ] **Step 3: Run them, expect compile failures**

Run: `./gradlew :spring-ai-insights-bedrock:test --tests 'io.github.springaiinsights.bedrock.pricing.*'`

- [ ] **Step 4: Implement `BedrockModelIds.resolve` and `BedrockPriceList.parse`**

Per product:
- keep only `attributes.feature == "On-demand Inference"`;
- usagetype lower-cased containing `batch`, `long_ctx`, `1h`, `30m`, `tpm` or `provisioned` → skip;
- dimension from `attributes.inferenceType`: `Input tokens`, `Output tokens`, `Prompt cache read input tokens`, `Prompt cache write input tokens`; else skip;
- scope: usagetype lower-cased contains `global` → GLOBAL, else GEO;
- price: the first `terms.OnDemand.*.priceDimensions.*`; unit `1M tokens` as is, `1K tokens` × 1000, other units → skip; amount `pricePerUnit.USD` as `BigDecimal`;
- a second, *different* value for the same (model, scope, dimension) → key goes to `ambiguous` and out of `prices`; equal duplicates are fine;
- emit a `ModelPrice` only when input and output exist; missing cache dimensions → `BigDecimal.ZERO`;
- unparseable JSON → skip that product.

- [ ] **Step 5: Run the tests, expect PASS**

- [ ] **Step 6: Commit** — `feat(bedrock): parse the Bedrock price list and inference profile IDs`

---

### Task 3: `AwsPriceCatalog`

**Files:**
- Create: `spring-ai-insights-bedrock/src/main/java/io/github/springaiinsights/bedrock/pricing/AwsPriceCatalog.java`
- Test: `…/bedrock/pricing/AwsPriceCatalogTest.java`

**Interfaces:**
- Consumes: `PriceCatalog`, `ModelPrice`, `ConfiguredPriceCatalog` (Task 1); `BedrockModelIds`, `BedrockPriceList`, `PriceKey`, `PriceScope` (Task 2).
- Produces:
  - `public final class AwsPriceCatalog implements PriceCatalog, AutoCloseable`
  - `public AwsPriceCatalog(Supplier<PricingClient> pricing, Supplier<BedrockClient> bedrock, Supplier<String> region, Duration refresh, PriceCatalog fallback, MeterRegistry registry)` — `refresh` < 1h → `IllegalArgumentException`.
  - `public void start()` — runs `refresh()` immediately, then every `refresh`, on one daemon thread named `ai-insights-pricing`.
  - `void refresh()` — package-private, synchronous, never throws.
  - `public String currency()` → `"USD"`; `public Optional<ModelPrice> find(String modelId)`; `public void close()`.
  - Counter `ai.insights.pricing.refresh`, tag `outcome` = `success` | `failure`.

`refresh()`: resolve `region.get()`; page `bedrock.get().listFoundationModels(...)` into `baseModelId → modelName`;
page `pricing.get().getProducts(...)` (service code `AmazonBedrockFoundationModels`, `TERM_MATCH regionCode=<region>`)
manually with `nextToken`; `BedrockPriceList.parse`; publish one immutable snapshot in a volatile field. On any
exception: keep the previous snapshot, log one warning with the message, count `failure`; suppliers are called
again next time. On success: info `ai-insights: loaded Amazon Bedrock list prices for <n> models in <region>`,
warn once per ambiguous key, count `success`.

`find(modelId)`: `BedrockModelIds.resolve` → model name from the snapshot → `PriceKey(name, scope)` → AWS price.
If found and `fallback.find(modelId)` is present with any of input/output/cacheRead/cacheWrite different
(`compareTo != 0`), warn once per `modelId`:
`ai-insights: YAML price for '<modelId>' ignored, the AWS list price is used`. Return the AWS price with
`model()` = the base model ID. Otherwise return `fallback.find(modelId)`.

- [ ] **Step 1: Write the failing tests** (fake `PricingClient`/`BedrockClient` as anonymous classes like `BedrockKnowledgeBaseCatalogTest`; products from the Task 2 fixture; `SimpleMeterRegistry`; fallback = `ConfiguredPriceCatalog`)

| Test | Setup → assertion |
|---|---|
| `awsPriceForGeoAndGlobalProfiles` | refresh → `eu.anthropic.claude-sonnet-4-6` input 3.30, `global.…` input 3.00 |
| `yamlPriceBeforeFirstRefresh` | no refresh; YAML `claude-sonnet-4` @ 3.00 → 3.00 |
| `failedFirstRefreshKeepsYaml` | bedrock client throws `SdkClientException` → YAML price; `failure` 1.0, `success` 0 |
| `failedLaterRefreshKeepsLastGoodPrices` | refresh ok, then pricing throws, refresh → still 3.30 |
| `applicationInferenceProfileArnUsesYaml` | YAML entry for `arn:aws:bedrock:eu-west-1:1:application-inference-profile/x` → that price |
| `modelMissingFromPriceListUsesYaml` | Bedrock lists `anthropic.claude-new-9`/`Claude New 9`, no products → YAML, or empty without YAML |
| `awsWinsOverYamlAndWarnsOnce` (`OutputCaptureExtension`) | YAML `claude-sonnet-4` @ 3.00, find `eu.` twice → 3.30; warning text appears exactly once |
| `refreshPicksUpPriceChanges` | second refresh returns the Sonnet 4.6 GEO input line at 3.60 → 3.60 |
| `paginatesBothApis` | both fakes return two pages via `nextToken` → a model only on page 2 is priced |
| `rejectsRefreshBelowOneHour` | `Duration.ofMinutes(59)` → `IllegalArgumentException` |

- [ ] **Step 2: Run them, expect compile failure**

Run: `./gradlew :spring-ai-insights-bedrock:test --tests '*AwsPriceCatalogTest'`

- [ ] **Step 3: Implement `AwsPriceCatalog`**

- [ ] **Step 4: Run the bedrock module tests, expect PASS**

Run: `./gradlew :spring-ai-insights-bedrock:test`

- [ ] **Step 5: Commit** — `feat(bedrock): AwsPriceCatalog with periodic refresh and YAML fallback`

---

### Task 4: Auto-configuration, properties and README

**Files:**
- Modify: `spring-ai-insights-autoconfigure/src/main/java/io/github/springaiinsights/autoconfigure/InsightsProperties.java` (`Pricing` gains `Aws aws`)
- Create: `…/autoconfigure/InsightsBedrockPricingAutoConfiguration.java`
- Modify: `…/autoconfigure/InsightsAutoConfiguration.java`
- Modify: `spring-ai-insights-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: `spring-ai-insights-autoconfigure/build.gradle.kts` (`compileOnly` + `testImplementation` for `software.amazon.awssdk:pricing` and `:bedrock`)
- Modify: `README.md`
- Test: `…/autoconfigure/InsightsBedrockPricingAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `AwsPriceCatalog` constructor and `start()` (Task 3); `InsightsAutoConfiguration.configuredPriceCatalog(InsightsProperties)` (Task 1).
- Produces:
  - `InsightsProperties.Pricing.Aws`: `boolean enabled = false`, `@Nullable String region`, `String endpointRegion = "us-east-1"`, `Duration refresh = Duration.ofHours(24)`, with javadoc for configuration metadata.
  - Bean `insightsAwsPriceCatalog` (`AwsPriceCatalog`, `destroyMethod = "close"`, `@ConditionalOnMissingBean(PriceCatalog.class)`).
  - Constants in `InsightsAutoConfiguration`:
    - `AWS_PRICING_MISSING = "ai-insights.pricing.aws.enabled=true requires spring-ai-insights-bedrock and the AWS SDK modules software.amazon.awssdk:pricing and software.amazon.awssdk:bedrock"`
    - `AWS_PRICING_CURRENCY = "ai-insights.pricing.aws.enabled=true requires ai-insights.pricing.currency=USD (AWS list prices are in USD)"`

Wiring:
- `InsightsBedrockPricingAutoConfiguration`: `@AutoConfiguration(before = InsightsAutoConfiguration.class)`; `@ConditionalOnClass(name = { "io.github.springaiinsights.bedrock.pricing.AwsPriceCatalog", "software.amazon.awssdk.services.pricing.PricingClient", "software.amazon.awssdk.services.bedrock.BedrockClient" })`; gated by `ai-insights.enabled` (matchIfMissing) and `ai-insights.pricing.aws.enabled`. Throws `IllegalStateException(AWS_PRICING_CURRENCY)` unless the currency is `USD` (case-insensitive). Region supplier: `aws.region`, else `spring.ai.bedrock.aws.region`, else `DefaultAwsRegionProviderChain().getRegion().id()`, evaluated at refresh time. Clients: a unique `PricingClient`/`BedrockClient` bean if present, else lazily built own ones (pricing in `endpoint-region`, bedrock in the resolved region), closed on destroy, same pattern as `InsightsBedrockAutoConfiguration`. Calls `start()`. Listed before `InsightsAutoConfiguration` in the imports file.
- `InsightsAutoConfiguration.insightsPriceCatalog`: when `ai-insights.pricing.aws.enabled=true`, throw `IllegalStateException(AWS_PRICING_MISSING)` (this method only runs when neither the AWS bean nor a user bean exists).
- `InsightsAutoConfiguration`: a `SmartInitializingSingleton` bean gated by `ai-insights.pricing.aws.enabled=true` that logs at info `ai-insights: a custom PriceCatalog bean is defined, so ai-insights.pricing.aws is not applied` when the context has no bean named `insightsAwsPriceCatalog`.

- [ ] **Step 1: Write the failing tests** (`ApplicationContextRunner` with both auto-configurations; fake `PricingClient`/`BedrockClient` beans that throw, so nothing reaches AWS)

| Test | Setup → assertion |
|---|---|
| `disabledByDefault` | no properties → `PriceCatalog` is `ConfiguredPriceCatalog` |
| `enabledRegistersAwsCatalog` | `aws.enabled=true`, `aws.region=eu-west-1` → `PriceCatalog` is `AwsPriceCatalog`; context starts although clients throw |
| `nonUsdCurrencyFailsStartup` | `currency=EUR` → startup failure contains `AWS_PRICING_CURRENCY` |
| `missingSdkFailsStartup` | `FilteredClassLoader(PricingClient.class)` → startup failure contains `AWS_PRICING_MISSING` |
| `customCatalogWins` | user `PriceCatalog` bean → it is used, the info log is present, no failure |
| `awsCatalogFallsBackToYaml` | `models[0]` `claude-sonnet-4` @ 3.00 → `find("eu.anthropic.claude-sonnet-4-6")` input 3.00 |
| `refreshBelowOneHourFailsStartup` | `aws.refresh=30m` → startup failure |

- [ ] **Step 2: Run them, expect failure**

Run: `./gradlew :spring-ai-insights-autoconfigure:test --tests '*InsightsBedrockPricingAutoConfigurationTest'`

- [ ] **Step 3: Implement the properties, the auto-configuration, the checks and the imports entry**

- [ ] **Step 4: Run the auto-configure tests, then the full build, expect PASS**

Run: `./gradlew :spring-ai-insights-autoconfigure:test` then `./gradlew build`

- [ ] **Step 5: Update `README.md`**
  - Pricing step 3: note that Bedrock geographic (`eu.`, `us.`, …) and `global.` profiles are priced differently (eu-west-1 Sonnet 4.6: 3.30/16.50 vs 3.00/15.00), so one substring entry can't price both; link to the new subsection.
  - New subsection "Automatic Amazon Bedrock pricing": the YAML from the spec's Configuration section; AWS wins, YAML fallback; refresh and failure behaviour; IAM `pricing:GetProducts` and `bedrock:ListFoundationModels`; egress to `api.pricing.<endpoint-region>.amazonaws.com` (corporate proxies may block it); list prices only (no negotiated discounts); long-context tiers ignored, so very long prompts on such models are understated; application inference profile ARNs use YAML; a custom `PriceCatalog` bean disables it; metric `ai.insights.pricing.refresh`.
  - Properties table: four `ai-insights.pricing.aws.*` rows with defaults.

- [ ] **Step 6: Commit** — `feat: opt-in automatic Amazon Bedrock pricing`

---

### Task 5: Live smoke check (no commit)

- [ ] **Step 0:** Confirm the model ID that reaches `CostCalculator` for Bedrock: read Spring AI's `BedrockProxyChatModel` (sources jar of `spring-ai-bedrock-converse` at the project's `springAiVersion`) and check what `ChatResponseMetadata.getModel()` holds, and the fallback in `InsightsObservationHandler.java:129-131`. If it is not the configured ID (e.g. `eu.anthropic.claude-sonnet-4-6`), stop and report before going further.

- [ ] **Step 1:** In a throwaway test in `spring-ai-insights-bedrock`, build `AwsPriceCatalog` with real clients (env `AWS_CA_BUNDLE=$HOME/.aws/ca/combined-ca-bundle.pem AWS_PROFILE=default`, region `eu-west-1`), call `refresh()`, print `find("eu.anthropic.claude-sonnet-4-6")` and `find("global.anthropic.claude-sonnet-4-6")`.

Expected: 3.30 / 16.50 and 3.00 / 15.00 (unless AWS changed them since 2026-10-16), `success` counter 1.0.

- [ ] **Step 2:** Delete the throwaway test; `git status` shows a clean tree.
