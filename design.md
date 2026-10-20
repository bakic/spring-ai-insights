# `spring-ai-insights` — design

*Status: draft for approval · 2026-10-14 · expands project #7 of `career-projects.md`*

## 1. Intent

A **standalone Spring Boot starter** that any Spring AI application adds with one dependency to
answer five questions about its assistant: **who uses it, what it costs, what it relies on
(knowledge base, tools), what it does well, and where it fails.**

Why: teams ship Spring AI assistants and then fly blind. Spring AI's built-in observability
gives low-level signals (tokens, timers) but not the answers the people around the assistant ask.
The author will use it first in his own Spring AI projects (MongoDB-backed), then publish it as
open source (clean-room, no client code, names or data).

## 2. Audiences — one data model, three views

| Reader | Question | Where they read it |
|---|---|---|
| **Engineer** | Is it healthy? Why did this fail? | Built-in dashboard + their own observability backend; alerts |
| **Product owner** | Is it useful? What do we improve next? | Built-in dashboard (usage, tools, knowledge base, quality) |
| **Management** | Is it worth what it costs? | Weekly report pushed to them; nobody has to open a dashboard |

## 3. Goals

1. **Usage:** active users (day/week/month), retention, messages per user, turns per
   conversation, conversations per day, peak hours.
2. **Cost:** cost computed from tokens × a configured price catalog (cached input tokens priced
   separately); per user, tenant, conversation, model, and over time; **cost per conversation**;
   month-to-date and month-end projection.
3. **Knowledge base:** number of retrievals, share of turns that used retrieval, similarity-score
   distribution, **empty retrievals**, most-retrieved documents, and **documents never retrieved**
   over a period.
4. **Tools:** calls, error rate and p95 latency per tool; most and least used; **tools never
   called** over a period; repeated calls to the same tool within one turn (loops).
5. **Quality:** explicit user feedback (thumbs up/down + optional comment), finish reasons
   (e.g. truncated by max tokens), errors, abandoned conversations (configurable inactivity).
6. **Operations:** total latency, **time to first token** for streaming, model errors and throttling.
7. **Zero-infrastructure start:** dependency only → working dashboard at `/ai-insights`.
8. **Backend-agnostic:** standard OpenTelemetry / Micrometer output usable with Grafana,
   Langfuse, Arize Phoenix, SigNoz, Datadog, New Relic, CloudWatch — no vendor is required.
9. **Weekly management report.**

## 4. Non-goals (v0.1)

- Evaluating answer correctness / LLM-as-judge (that is project #2 `junit-llm-evals`).
- Semantic analysis of message content (intent clustering, rephrase detection) — v0.2+.
- Being a general APM or replacing the user's observability backend.

> Two earlier non-goals shipped during implementation and are no longer true: instrumenting
> Bedrock Knowledge Base calls made outside Spring AI, and fetching live provider prices. See
> README's "Amazon Bedrock Knowledge Base retrieval" and "Automatic Amazon Bedrock pricing"
> sections.

## 5. Anti-goals — failure even if it "works"

- **Slowing down or breaking the chat path.** Capture is asynchronous; any insights failure is
  logged and counted, never propagated to the user's request.
- **Leaking personal data.** No message content stored by default; user IDs pseudonymised by
  default; the dashboard is never publicly reachable by default.
- **Requiring infrastructure to start**, or locking users into a vendor.
- **Metric cardinality explosions** (user or conversation IDs as metric tags).
- Duplicating what Spring AI already emits instead of building on it.

## 6. Constraints

- Java 21+, Spring Boot 4.x, Spring AI 2.x (verified against Spring AI 2.0.1 docs).
- Must work with `ChatClient.call()` **and** `.stream()`, with and without Spring Security.
- License: Apache 2.0. Published to Maven Central.
- Clean-room: no code, names or data from any client.

## 7. What Spring AI 2.0.1 already provides (verified) and the gaps

**Provided** (Micrometer observations; low-cardinality keys go to metrics, high-cardinality keys
to traces only):

- `spring.ai.chat.client` — ChatClient call/stream; `spring.ai.chat.client.conversation.id`
  (trace only).
- `spring.ai.advisor` — each advisor's duration.
- `gen_ai.client.operation` + metric `gen_ai.client.token.usage` — per model call; input/output
  tokens; cache read/creation tokens and finish reasons on traces.
- `spring.ai.tool` — per tool call; `spring.ai.tool.definition.name` is low-cardinality, so
  per-tool timers exist. Arguments/results only if opted in.
- `db.vector.client.operation` — every `VectorStore` implementation, including the
  **Amazon Bedrock Knowledge Base** vector store; query/topK/filter on traces.
- Prompt/completion/tool content logging off by default.

**Gaps this project fills:**

| Gap | Fill |
|---|---|
| No cost anywhere (also absent from the OTel GenAI conventions) | Price catalog + cost on events and metrics |
| No user / tenant attribution | Identity resolver SPI |
| No conversation-level record | Turn event model + store |
| No retrieval quality (scores, empty results, unused documents) | Retrieval capture |
| No tool outcome analytics beyond timers (loops, never-used tools) | Tool capture + aggregation |
| No time to first token, no feedback signal | Stream capture + feedback API |
| Spring AI uses `gen_ai.system`; current OTel GenAI conventions use `gen_ai.provider.name`, and define `gen_ai.execute_tool.duration`, `gen_ai.invoke_agent.*`, `gen_ai.server.time_to_first_token` | Emit the current names (opt-in), then propose upstream |

Gaps that are general enough (cost hook, semconv alignment, TTFT) become **upstream pull
requests to Spring AI**.

## 8. How it works

### 8.1 Capture

- An **`InsightsAdvisor`** (call + stream) registered automatically on every auto-configured
  `ChatClient.Builder`. It opens a *turn*, resolves identity and conversation ID, measures
  latency and time to first token, and closes the turn with usage and finish reason.
- **`ObservationHandler`s** for `gen_ai.client.operation`, `spring.ai.tool` and
  `db.vector.client.operation` attach model calls, tool calls (name, duration, outcome, error
  type) and retrievals (store, topK, result count, scores, document IDs) to the current turn.
- Correlation of handler data to the turn must survive streaming thread hops (Spring AI's docs
  note a context loss on some providers' async streaming paths). The builder picks the mechanism
  (observation parent chain, Reactor context, explicit turn ID) and **must cover call and stream
  with tests**.
- Retrieval data comes from the vector-store observation context or, if insufficient, a
  `VectorStore` decorator — builder's choice. The author's projects query Bedrock Knowledge Base
  **through Spring AI's `VectorStore`**, so this path is the one that must work on day one.

**Corpus catalog (for "documents never retrieved").** A `VectorStore` cannot list its documents,
so an optional SPI `KnowledgeCorpusCatalog` supplies the full document list. v0.1 ships a
Bedrock Knowledge Base implementation based on the Bedrock Agent API's knowledge-base document
listing (cached, refreshed on a schedule); the builder maps its document identifiers (S3
locations) to the identifiers seen in retrieval results. Without a catalog, the dashboard shows
"least retrieved" instead of "never retrieved".

**Definition of a turn.** One turn = one **outermost** `ChatClient` call/stream within a request.
Nested `ChatClient` calls made during that turn (query rewriting, routing, sub-agents) are
attached to it as additional model calls, not counted as new turns. If no conversation ID is set,
the turn gets a generated one-turn conversation ID and is flagged `conversationIdMissing` so the
dashboard can warn. Clients built outside the auto-configured builder (e.g. `ChatClient.create`)
are not instrumented automatically; the README documents adding the `InsightsAdvisor` bean by hand.

### 8.2 Event model

One **`ConversationTurn`** document per user message → assistant answer:

- `turnId`, `conversationId`, `timestamp`, `userId` (pseudonymised), `tenantId`, `application`
- `models[]` with input / output / cache-read / cache-write tokens and cost per call
- `totalCost`, `currency`, `latencyMs`, `timeToFirstTokenMs`, `streaming`, `finishReasons`, `error`
- `toolCalls[]`: name, durationMs, outcome (`success`/`error`), errorType, repeatIndex
- `retrievals[]`: store, topK, resultCount, scores, documentIds
- `attributes`: free-form low-volume tags set by the app (e.g. feature name)

A separate **`Feedback`** event: `turnId`, `rating` (up/down), optional comment, timestamp.
Message content is **not** stored unless `capture-content=true`.

### 8.3 Identity

SPI `InsightsIdentityResolver` → `(userId, tenantId)`. Default: Spring Security principal name;
tenant from a configurable JWT claim; none if no security. User IDs pseudonymised with
HMAC-SHA256 and a configured secret (`pseudonymise=false` to keep raw IDs). Without a secret:
the in-memory store uses a random per-start secret and logs a warning; a persistent store
(MongoDB) **fails at startup**, because IDs must stay stable across restarts.

### 8.4 Cost

`PriceCatalog` from properties: per model ID → input, output, cache-read, cache-write prices per
1M tokens, currency. A model ID is priced only on an exact match: a name that merely contains or is
contained in a configured one (snapshot, regional prefix, variant) may cost differently, so a wrong cost is
worse than none. Unknown model → cost `null`, a warning log once, an `unpriced-model` counter, and a
dashboard warning that names the model, the similar configured entry and the configured entries. A sample catalog for common Bedrock models ships in the docs (prices
vary by region and change, so users own their values).

### 8.5 Storage

SPI `InsightsEventStore` (write + the aggregation queries the dashboard and report need):

- **In-memory** (default, bounded, for dev/demo).
- **MongoDB** — auto-selected when Spring Data MongoDB is on the classpath; uses the app's
  existing connection; collection prefix configurable; TTL index for retention (default 90 days);
  aggregations via the aggregation pipeline. **First-class in v0.1** (the author's stack).
- JDBC and ClickHouse — later; the SPI must not assume MongoDB.

Writes go through a bounded async queue; on overflow events are dropped and counted.

**Multiple instances and applications.** Multi-instance apps are supported with a persistent
store (MongoDB); the in-memory store is per instance and the dashboard says so. Several
applications may share one store, separated by `application`; the dashboard has an application
filter and the weekly report is sent per application. The scheduled report takes a lock in the
store so only one instance sends it.

### 8.6 Metrics (low cardinality only)

Micrometer meters for: turns, cost (by model, application; tenant only if
`metrics.tenant-tag=true` because tenant count may be unbounded), tool outcomes by tool,
empty retrievals, feedback by rating, time to first token, dropped events. Optional emission of
current OTel GenAI metric names. No user or conversation IDs as tags — ever.

### 8.7 Built-in dashboard — `/ai-insights`

Served by the app itself; no Node or extra build step for consumers. Pages:
**Overview** (users, conversations, cost, trend) · **Usage** · **Cost** · **Knowledge base** ·
**Tools** · **Quality** · **Conversation detail** (one turn timeline: model calls, tools,
retrievals, cost; links to the trace ID if tracing is on). Period selector and tenant filter.

Access: the dashboard does **not** add its own `SecurityFilterChain`. It relies entirely on the
application's own authentication (form login, OAuth2, JWT resource server, …) and requires a
configurable role (default `AI_INSIGHTS`, checked with `request.isUserInRole`). Without Spring
Security, the dashboard is disabled unless `dashboard.allow-anonymous=true` is set — the
quickstart sets it for local use. Goal 7 therefore reads: *dependency + one property → dashboard*.

Tenant filter is a convenience filter: in v0.1 all dashboard readers are internal and see all
tenants. Per-tenant access restriction is out of scope for v0.1. The filter suggests the tenants
active in the selected application and period, busiest first, capped at 200 (`/api/tenants`);
any other tenant can still be typed.

### 8.8 Feedback API

`POST /ai-insights/feedback` and a Java `InsightsFeedback` bean, so the app's UI can send
thumbs up/down for a `turnId`. The turn ID is exposed to the app (response metadata / advisor
context) so it can be returned to the front end.

The feedback endpoint is registered under the dashboard path, so it is protected by whatever
security the application (or `dashboard.allow-anonymous`) applies to that route — there is no
separate protection layer. The caller must resolve to a `userId`, and a rating is only accepted
for a turn whose `userId` matches the caller's resolved identity (otherwise 404). One rating per
turn; a new rating replaces the previous one.

### 8.9 Weekly report

Scheduled (cron configurable, default Monday 08:00). Content: active users, conversations,
total cost, cost per conversation, positive-feedback rate, top tools, knowledge-base empty-rate,
week-over-week deltas, month-end cost projection. Delivery: Slack incoming webhook and email
(Spring Mail) in v0.1; also available as an HTML/JSON endpoint.

### 8.10 Ready-made assets for external backends

Grafana dashboard JSON and alert rules (cost spike, tool error rate, empty-retrieval rate,
TTFT p95) in the repo; a short guide for Langfuse / Phoenix / SigNoz / Datadog via OTLP.

## 9. Configuration surface (shape, not final names)

The prefix is `ai-insights`, not `spring.ai.insights`: Spring Boot asks third-party starters not
to use the `spring.*` namespace. Model prices are a list (model IDs contain dots and colons,
which are awkward as map keys).

```yaml
ai-insights:
  enabled: true
  application: helpdesk-assistant
  store: auto            # memory | mongodb | auto
  retention: 90d
  capture-content: false
  identity:
    pseudonymise: true
    secret: ${INSIGHTS_SECRET}
    tenant-claim: tenant_id
  pricing:
    currency: USD
    models:
      - { model: anthropic.claude-sonnet-x, input: 3.00, output: 15.00, cache-read: 0.30, cache-write: 3.75 }
  dashboard: { enabled: true, path: /ai-insights, role: AI_INSIGHTS }
  report:   { cron: "0 0 8 * * MON", slack-webhook: ..., email-to: [...] }
  metrics:  { tenant-tag: false, otel-genai-latest-names: false }
```

## 10. Deliverables

- Maven modules (builder decides final split): core, autoconfigure, MongoDB store, dashboard UI,
  starter.
- A demo Spring AI app (sample "helpdesk" assistant with 3 tools + a knowledge base) that also
  generates realistic traffic for screenshots.
- Tests: unit + Testcontainers (MongoDB); call and stream paths; failure isolation (store down
  must not break chat).
- README first: problem, architecture diagram, 5-minute quickstart, screenshots, privacy section.
- Grafana dashboards + alert rules; CI; Maven Central release.
- Content: 1 article, 3–4 posts, 1 talk (*"What is your AI assistant really doing — and costing?"*).

## 11. Milestones

| Version | Scope | Effort (~5 h/week) |
|---|---|---|
| **v0.1** | Capture, event model, identity, pricing, in-memory + MongoDB stores, metrics, dashboard, feedback API, weekly report (Slack + email), Bedrock KB corpus catalog, Grafana assets, demo app, Maven Central release — **scope is fixed, the date moves** (author's decision) | 10–12 weeks |
| v0.2 | JDBC store, alert presets, rephrase / "can't answer" detection, cost budgets per tenant | 3–4 weeks |
| Upstream | PRs to Spring AI: OTel GenAI semconv alignment, TTFT, cost hook | ongoing |

## 12. Decided by the author

- Standalone starter, usable in any Spring AI project.
- Audiences: engineer, product owner and management.
- Not tied to Grafana; works with any OpenTelemetry backend.
- Easy integration is the priority; built-in dashboard with no extra infrastructure.
- MongoDB support first (his current stack).
- v0.1 keeps the full scope; the timeline extends to 10–12 weeks.
- Knowledge base is accessed through Spring AI's Bedrock KB `VectorStore`.

## 13. Calls made by the designer (check these)

- Name `spring-ai-insights`; Apache 2.0; Java 21 / Boot 4 / Spring AI 2 baseline.
- No message content stored by default; HMAC-pseudonymised user IDs by default.
- Default store in-memory; MongoDB auto-selected when present; 90-day retention.
- Dashboard protected by role `AI_INSIGHTS`; disabled without Spring Security unless enabled.
- Weekly report via Slack webhook + email in v0.1.
- Prices are user-configured, never fetched.
- ClickHouse and JDBC deferred.
- A turn = outermost `ChatClient` call; nested calls fold into it.
- Feedback restricted to the turn's own user; dashboard has its own security chain.
- Multi-instance needs MongoDB; report sending is locked to one instance; per-application reports.
- Tenant filter is not an access control in v0.1.
- Missing pseudonymisation secret: warning in memory mode, startup failure with MongoDB.

## 14. Left to the builder

Module split, dashboard UI technology (within "no consumer build step"), turn-correlation
mechanism, exact property names, MongoDB schema/indexes, Maven group ID.
