# spring-ai-insights

You shipped a Spring AI assistant. Now you need to know who uses it, what it costs, which tools and
knowledge bases it relies on, and where it fails. Spring AI's built-in observations give you spans
and token counters, but no per-user view, no cost, and no single record per question.

`spring-ai-insights` is a Spring Boot starter that records **one turn per ChatClient call** with
everything that happened inside it. Design and roadmap: [design.md](design.md).

## What you get (v0.1, core)

Every `ChatClient` call (blocking or streaming) becomes a `ConversationTurn`:

- **who**: user ID (pseudonymised by default) and tenant, from Spring Security or advisor params
- **conversation**: conversation ID, outcome (`SUCCESS`, `ERROR`, `CANCELLED`), latency, time to first token, finish reasons
- **model calls**: provider, model, input / output / cache-read / cache-write tokens, cost per call
- **tool calls**: name, duration, outcome; **retrievals**: vector store, top-k, result count, scores, document IDs
- **cost**: total cost of the turn in your configured currency
- **your attributes**: any key you pass as an advisor param (feature, channel, A/B variant…)

Turns go to a bounded in-memory store through an asynchronous writer: recording never blocks
or breaks a chat. Micrometer metrics (all tagged with `application`):

| Metric | Extra tags |
|---|---|
| `ai.insights.turns` | `outcome`, `streaming` |
| `ai.insights.tokens` | `model`, `type` (`input`, `output`, `cache_read`, `cache_write`) |
| `ai.insights.cost` | `model`, `currency` (and `tenant` if enabled) |
| `ai.insights.tool.calls` | `tool`, `outcome` |
| `ai.insights.retrievals` | `store`, `result` (`hit`, `empty`) |
| `ai.insights.ttft` (timer) | – |
| `ai.insights.events.dropped`, `ai.insights.store.errors`, `ai.insights.internal.errors` | – |
| `ai.insights.unpriced.models` | `model` |

User and conversation IDs are **never** metric tags (unbounded cardinality); they live in the turn records.

## Quickstart (5 minutes)

Requirements: Java 21, Spring Boot 4.1, Spring AI 2.0.

1. Add the starter. It is not on Maven Central yet; until the first release, clone this repository
   and use a Gradle composite build:

   ```kotlin
   // settings.gradle.kts
   includeBuild("../spring-ai-insights")
   // build.gradle.kts
   implementation("io.github.springaiinsights:spring-ai-insights-starter:0.1.0-SNAPSHOT")
   ```

2. Set a pseudonymisation secret (from your secret store, not from Git):

   ```yaml
   ai-insights:
     identity:
       secret: ${AI_INSIGHTS_SECRET}
   ```

   Without it, user IDs are pseudonymised with a random key that changes on every restart, and a warning is logged.

3. Declare your model prices (per 1,000,000 tokens). `model` matches the reported model ID exactly,
   or as a substring (the longest match wins), so one entry covers regional prefixes such as `eu.`:

   ```yaml
   ai-insights:
     pricing:
       currency: USD
       models:
         - model: anthropic.claude-sonnet-4
           input: 3.00
           output: 15.00
           cache-read: 0.30
           cache-write: 3.75
         - model: gpt-4o
           input: 2.50
           output: 10.00
           cache-read: 1.25
           input-includes-cached: true   # OpenAI counts cached tokens inside input tokens
   ```

   Models without a price are still recorded, with a `null` cost, one warning, and the `ai.insights.unpriced.models` counter.

4. Use the auto-configured `ChatClient.Builder` as usual. The advisor is added for you:

   ```java
   @Service
   class Assistant {
       private final ChatClient chat;
       Assistant(ChatClient.Builder builder) { this.chat = builder.build(); }
   }
   ```

## Passing context

```java
ChatClientResponse response = chat.prompt()
    .user(question)
    .advisors(a -> a
        .param(ChatMemory.CONVERSATION_ID, conversationId)          // groups turns into a conversation
        .param(InsightsContextKeys.ATTRIBUTE_PREFIX + "feature", "refunds")   // becomes attribute feature=refunds
        .param(InsightsContextKeys.USER_ID, userId)                  // overrides the resolved user
        .param(InsightsContextKeys.TENANT_ID, tenantId))             // overrides the resolved tenant
    .call()
    .chatClientResponse();

String turnId = (String) response.context().get(InsightsContextKeys.TURN_ID);   // link feedback or logs to this turn
```

Without a conversation ID, each turn gets a random one and is flagged `conversationIdMissing`.
With streaming, every `ChatClientResponse` chunk carries the turn ID in its context.

**Identity.** With Spring Security on the classpath, the user is `Authentication.getName()` and
the tenant is the `ai-insights.identity.tenant-claim` claim when the principal is a `Jwt`. Unauthenticated
and anonymous requests are recorded without a user. Advisor params always win. In WebFlux apps, the
security context lives in the Reactor context, not on the thread: pass `USER_ID` / `TENANT_ID`
as params or provide your own `InsightsIdentityResolver` bean.

## ChatClients you build yourself

Clients from the auto-configured `ChatClient.Builder` are covered. For a client you build by hand,
add the advisor and **pass the `ObservationRegistry`**, so model, tool and retrieval calls are attributed to the turn:

```java
ChatClient chat = ChatClient.builder(chatModel, observationRegistry, null, null)
    .defaultAdvisors(insightsAdvisor)      // inject InsightsAdvisor
    .build();
```

`ChatClient.create(chatModel)` has no observation registry: turns are still recorded, but only with
the token usage of the final response, provider `unknown`, and no tool or retrieval details.

## Configuration

| Property | Default | |
|---|---|---|
| `ai-insights.enabled` | `true` | |
| `ai-insights.application` | `spring.application.name`, else `default` | `application` tag and turn field |
| `ai-insights.store` | `auto` | `auto` or `memory`; both mean in-memory in v0.1 |
| `ai-insights.capture-content` | `false` | record prompt and answer text |
| `ai-insights.identity.pseudonymise` | `true` | HMAC-SHA256 user IDs |
| `ai-insights.identity.secret` | – | HMAC key |
| `ai-insights.identity.tenant-claim` | `tenant_id` | JWT claim holding the tenant |
| `ai-insights.pricing.currency` | `USD` | |
| `ai-insights.pricing.models[]` | – | `model`, `input`, `output`, `cache-read`, `cache-write`, `input-includes-cached` |
| `ai-insights.metrics.tenant-tag` | `false` | tag `ai.insights.cost` with tenant (only for a few tenants) |
| `ai-insights.writer.queue-capacity` | `10000` | turns waiting to be stored; overflow is dropped and counted |
| `ai-insights.memory.max-turns` | `10000` | in-memory store size; oldest evicted first |

Every bean is `@ConditionalOnMissingBean`: replace the store (`InsightsEventStore`), the identity
resolver (`InsightsIdentityResolver`), or the pricing (`PriceCatalog`) with your own.

## Privacy defaults

- User IDs are pseudonymised (HMAC-SHA256) before they are stored. Set a stable secret to keep them stable across restarts.
- Prompt and answer text is **not** recorded unless `ai-insights.capture-content=true`.
- No user or conversation ID ever becomes a metric tag.

## Roadmap

- **Plan 2: MongoDB store**, with TTL retention and aggregation queries (the app's own database).
- **Plan 3: built-in dashboard and feedback**, `/ai-insights` UI for usage, cost, tools, knowledge base and quality, plus thumbs up/down linked by turn ID.
- **Plan 4: weekly report, knowledge-base insights, Grafana dashboards and release**, an email/Slack summary for managers, Bedrock Knowledge Base coverage, and the first Maven Central release.
