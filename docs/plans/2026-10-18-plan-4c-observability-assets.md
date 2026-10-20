# Plan 4c: Grafana dashboard, alert rules and OpenTelemetry guide — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a Grafana dashboard, Prometheus alert rules and an OpenTelemetry backend guide that are proven
against the metrics the starter really exports, so an engineer can plug the library into their own observability
stack in minutes.

**Architecture:** Assets live in a top-level `observability/` directory that is also a test-only Gradle project
(`spring-ai-insights-observability`, never published). Its tests generate a real Prometheus scrape from
`InsightsMetrics`, run `promtool` on the rules, load rules and dashboard into real Prometheus and Grafana containers
that scrape a test endpoint, and check every property the guide mentions against the Spring configuration metadata
on the classpath. Nothing in the assets is accepted on reading alone.

**Tech Stack:** Micrometer + `micrometer-registry-prometheus`, Prometheus `prom/prometheus:v3.5.0` (promtool
3.5.0), Grafana `grafana/grafana:12.1.0`, Testcontainers, JUnit 5, JsonPath, SnakeYAML.

**Spec:** `design.md` §8.10 ("Grafana dashboard JSON and alert rules (cost spike, tool error rate, empty-retrieval
rate, TTFT p95) in the repo; a short guide for Langfuse / Phoenix / SigNoz / Datadog via OTLP"), principle 8
(backend-agnostic), §11 ("not tied to Grafana").

## Global Constraints

- Metric names are the Prometheus forms of the existing meters (`ai.insights.turns` → `ai_insights_turns_total`,
  timer `ai.insights.ttft` → `ai_insights_ttft_seconds_*`); no meter is renamed and no tag is added.
- Every dashboard query and every alert filters or groups by `application`; none uses user, conversation or turn
  IDs (they are never tags).
- Dashboard: uid `ai-insights`, title `Spring AI Insights`, a `datasource` variable of type `prometheus` (no
  hard-coded datasource UID), an `application` variable from `label_values(ai_insights_turns_total, application)`
  (multi-value, include All); every target uses `${datasource}` and `application=~"$application"`.
- Alert rules are plain Prometheus rule files (they load in Prometheus, Mimir, Thanos, VictoriaMetrics and as
  Grafana data-source-managed rules); every alert has `severity` and `summary`/`description` annotations naming
  `{{ $labels.application }}`.
- The guide uses Spring Boot 4.1 property names: traces `management.opentelemetry.tracing.export.otlp.endpoint` and
  `.headers.*`; metrics `management.otlp.metrics.export.url` and `.headers.*`; sampling
  `management.tracing.sampling.probability`; dependencies `io.micrometer:micrometer-tracing-bridge-otel`,
  `io.opentelemetry:opentelemetry-exporter-otlp`, `io.micrometer:micrometer-registry-otlp` (no
  `spring-boot-starter-opentelemetry` exists in 4.1).
- The guide states that Spring AI's `spring.ai.chat.observations.log-prompt` / `log-completion` write the text to
  application logs only, never into spans (Spring AI 2.0 `ChatModelPromptContentObservationHandler`), and does not
  recommend enabling them by default.
- Backend facts (endpoints, headers) are those of the research ledger entry for this plan, each with its source URL
  in the guide; an unconfirmed fact is either left out or marked "check your region in the vendor's docs".
- Container images are pinned exactly: `prom/prometheus:v3.5.0`, `grafana/grafana:12.1.0`.

## Review Focus

1. No traffic at all (fresh deploy, night) must fire no alert and show "No data"/0, never NaN alerts — Task 3
   promtool case `noTrafficNoAlert`.
2. A brand-new application with no history must not fire the cost-spike alert from the first cent — Task 3 promtool
   case `costSpikeNeedsAFloor`.
3. One failing call out of two must not page anyone (minimum-volume guard on ratios) — Task 3 promtool cases
   `toolErrorsNeedVolume`, `emptyRetrievalsNeedVolume`.
4. A counter reset (application restart) must not create a spike — Task 3 promtool case `restartIsNotASpike`.
5. A panel whose query is syntactically valid but names a metric that does not exist shows nothing silently — Task 4
   `everyPanelReturnsDataAgainstARealScrape` for the panels listed there.

---

### Task 1: TTFT histogram

**Files:**
- Modify: `spring-ai-insights-core/src/main/java/io/github/springaiinsights/metrics/InsightsMetrics.java`
- Test: `spring-ai-insights-core/src/test/java/io/github/springaiinsights/metrics/InsightsMetricsTest.java` (create if
  absent)

**Interfaces:**
- Produces: `ai.insights.ttft` is registered with `Timer.builder(...).publishPercentileHistogram()
  .minimumExpectedValue(Duration.ofMillis(50)).maximumExpectedValue(Duration.ofSeconds(60))`, tags unchanged — so
  Prometheus exposes `ai_insights_ttft_seconds_bucket` with a bounded bucket set.

- [ ] **Step 1: Write the failing test** `ttftPublishesABoundedHistogram`: record a turn with
  `timeToFirstTokenMs = 800` on a `SimpleMeterRegistry`; `timer.takeSnapshot().histogramCounts()` is non-empty, its
  first bucket ≥ 0.05 s and last ≤ 60 s (in seconds), and the bucket containing 0.8 s counts 1.
- [ ] **Step 2: Run** `./gradlew -q :spring-ai-insights-core:test --tests '*InsightsMetricsTest'` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(metrics): publish a TTFT histogram for p95 queries`.

### Task 2: Observability project and the real scrape

**Files:**
- Modify: `settings.gradle` (include `spring-ai-insights-observability`, `projectDir = file("observability")`)
- Create: `observability/build.gradle` — test-only: `testImplementation(project(":spring-ai-insights-core"))`,
  `micrometer-registry-prometheus`, `testcontainers-junit-jupiter`, `org.yaml:snakeyaml`, `com.jayway.jsonpath:json-path`;
  no `java-library` publication (Plan 4d must not publish it)
- Create: `observability/src/test/java/io/github/springaiinsights/observability/SampleScrape.java`
- Test: `observability/src/test/java/io/github/springaiinsights/observability/SampleScrapeTest.java`

**Interfaces:**
- Produces: `final class SampleScrape` with `static PrometheusMeterRegistry registry()` (a new registry fed through
  `InsightsMetrics(registry, "shop", false)` with: 3 SUCCESS turns, 1 ERROR, 1 CANCELLED; model `gpt-4o` with all
  four token types and a cost; tools `lookup` SUCCESS×3 and ERROR×1; retrievals `docs` hit×2 and empty×1; TTFT
  800 ms; feedback UP and DOWN; one each of dropped event, store error, stalled turn, internal error, unpriced model
  `mystery`), `static String text()` (its scrape) and `static Set<String> metricNames()` (sample names, i.e. the
  token before `{` or space, comments excluded).
- Produces: `static Set<String> metricNamesIn(String promql)` in `SampleScrape` — identifiers matching
  `ai_insights_[a-z_]+` in an expression.

- [ ] **Step 1: Write the failing test** `exportedNamesArePinned`: `metricNames()` contains exactly
  `ai_insights_turns_total, ai_insights_tokens_total, ai_insights_cost_total, ai_insights_tool_calls_total,
  ai_insights_retrievals_total, ai_insights_feedback_total, ai_insights_events_dropped_total,
  ai_insights_store_errors_total, ai_insights_turns_stalled_total, ai_insights_internal_errors_total,
  ai_insights_unpriced_models_total, ai_insights_ttft_seconds_bucket, ai_insights_ttft_seconds_count,
  ai_insights_ttft_seconds_sum, ai_insights_ttft_seconds_max`. If the first real run shows a different but
  correct Prometheus naming, record the observed set in the ledger and pin that — the assets follow the scrape.
- [ ] **Step 2: Run** `./gradlew -q :spring-ai-insights-observability:test` — FAIL (class missing).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `test(observability): pin the exported Prometheus metric names`.

### Task 3: Alert rules

**Files:**
- Create: `observability/prometheus/ai-insights-alerts.yml` (group `ai-insights`)
- Create: `observability/prometheus/ai-insights-alerts.test.yml` (promtool unit tests)
- Test: `observability/src/test/java/io/github/springaiinsights/observability/AlertRulesTest.java`

**Interfaces:**
- Consumes: `SampleScrape.metricNames()`, `metricNamesIn(String)`.
- Produces: alerts (all `by (application, …)`, label `severity`):

| Alert | Condition | for | severity |
|---|---|---|---|
| `AiInsightsCostSpike` | `increase(ai_insights_cost_total[1h])` per application, currency > 3 × the hourly average of the previous 7 days (`increase(...[7d] offset 1h) / 168`) **and** > 1 (currency unit) | 15m | warning |
| `AiInsightsToolErrorRate` | error share of `ai_insights_tool_calls_total` per application, tool over 15m > 0.10 **and** ≥ 10 calls in 15m | 10m | warning |
| `AiInsightsEmptyRetrievalRate` | `result="empty"` share of `ai_insights_retrievals_total` per application, store over 1h > 0.30 **and** ≥ 20 retrievals in 1h | 30m | warning |
| `AiInsightsSlowFirstToken` | `histogram_quantile(0.95, sum by (application, le) (rate(ai_insights_ttft_seconds_bucket[10m])))` > 5 (seconds) | 10m | warning |
| `AiInsightsEventsLost` | `increase(ai_insights_events_dropped_total[10m])` or `increase(ai_insights_store_errors_total[10m])` > 0 | 0m | critical |

  The file header comment says the thresholds are starting points to edit.

- [ ] **Step 1: Write the promtool tests** (`interval: 1m`), one `rule_files` entry, cases:
  `noTrafficNoAlert` (no series at all → `exp_alerts: []` for every alert at 2h);
  `costSpikeFires` (7 days at 0.10/h then 1 h at 5.00 → fires with labels application, currency);
  `costSpikeNeedsAFloor` (history 0 then 0.50 in an hour → no alert);
  `restartIsNotASpike` (steady 0.10/h with a counter reset to 0 → no alert);
  `toolErrorsFire` (20 calls/15m, 5 errors → fires with application, tool);
  `toolErrorsNeedVolume` (2 calls, 1 error → no alert);
  `emptyRetrievalsFire` / `emptyRetrievalsNeedVolume` (60 vs 4 retrievals/h, 50 % empty);
  `slowFirstTokenFires` (all observations in the `+Inf`/>5 s buckets → fires);
  `eventsLostFires` (dropped counter +1 → fires immediately, severity critical).
- [ ] **Step 2: Write `AlertRulesTest`:**
  `promtoolAcceptsTheRules` — Testcontainers `GenericContainer("prom/prometheus:v3.5.0")` with entrypoint
  `promtool`, the two files copied in, command `check rules /rules/ai-insights-alerts.yml`, exit code 0;
  `promtoolUnitTestsPass` — command `test rules /rules/ai-insights-alerts.test.yml`, exit code 0 (on failure the
  assertion message is the container log);
  `everyAlertUsesExportedMetrics` — SnakeYAML-load the rules; for each `expr`, `metricNamesIn(expr)` ⊆
  `metricNames()` and non-empty;
  `everyAlertIsLabelledAndAnnotated` — `labels.severity` ∈ {warning, critical}, annotations `summary` and
  `description` contain `{{ $labels.application }}`.
- [ ] **Step 3: Run** `./gradlew -q :spring-ai-insights-observability:test` — FAIL (files missing), then write the
  rules.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(observability): Prometheus alert rules with promtool tests`.

### Task 4: Grafana dashboard

**Files:**
- Create: `observability/grafana/ai-insights-dashboard.json`
- Test: `observability/src/test/java/io/github/springaiinsights/observability/DashboardTest.java`,
  `observability/src/test/java/io/github/springaiinsights/observability/LiveStackTest.java`

**Interfaces:**
- Consumes: `SampleScrape`, the rules file of Task 3.
- Produces: rows and panels (ids unique; time series unless stated):
  - **Usage** — turns per minute by outcome; error + cancelled share (stat, %); streaming share (stat, %).
  - **Cost** — cost over the dashboard range by model (stat, `increase(...[$__range])`, unit from the `currency`
    label in the legend); cost per hour by model; tokens per minute by type.
  - **Tools** — calls per minute by tool; error share by tool (table, sorted descending).
  - **Knowledge** — retrievals per minute by store; empty share by store.
  - **Quality** — TTFT p50 and p95 (from `_bucket`); positive feedback share (stat, `rating="up"` / all).
  - **Health** — dropped events, store errors, stalled turns, internal errors, unpriced models (by `model`).
  Rate windows use `$__rate_interval`.

- [ ] **Step 1: Write `DashboardTest`** (JsonPath, no container):
  `identity` (uid `ai-insights`, title `Spring AI Insights`, variables `datasource` type `datasource` with query
  `prometheus`, `application` query `label_values(ai_insights_turns_total, application)`, `multi` and `includeAll`
  true); `panelIdsAreUnique`; `everyTargetUsesTheVariables` (datasource uid `${datasource}`, expr contains
  `application=~"$application"`); `everyQueryUsesExportedMetrics` (`metricNamesIn` ⊆ `metricNames()`, non-empty).
- [ ] **Step 2: Write `LiveStackTest`** (`@Testcontainers`, one network): the test JVM serves `SampleScrape.text()`
  on `/metrics` through a JDK `HttpServer` on a free port, exposed with `Testcontainers.exposeHostPorts`;
  Prometheus v3.5.0 scrapes `host.testcontainers.internal:<port>` every 1 s and loads the rules file; Grafana
  12.1.0 provisions a Prometheus datasource (uid `prometheus`, url `http://prometheus:9090`) and the dashboard from
  files. Between scrapes the test feeds more turns into the registry so rates are non-zero. Tests:
  `prometheusLoadsTheRules` (`/api/v1/rules` — every rule `health` is `ok`, 5 alerts);
  `everyPanelQueryIsValid` (each expr with `$application`→`.*`, `${datasource}` removed, `$__rate_interval`→`1m`,
  `$__range`→`5m`, evaluated with `/api/v1/query` → `status` `success`);
  `everyPanelReturnsDataAgainstARealScrape` — after ≥ 5 s of scrapes, non-empty results for: turns by outcome,
  cost by model, tokens by type, tool calls, tool error share, retrievals, empty share, TTFT p95, feedback share,
  unpriced models;
  `grafanaLoadsTheDashboard` (`GET /api/dashboards/uid/ai-insights` with admin basic auth → 200, title
  `Spring AI Insights`, panel count equal to the file's).
- [ ] **Step 3: Run** `./gradlew -q :spring-ai-insights-observability:test` — FAIL, then write the dashboard.
- [ ] **Step 4: Run** — PASS. Take a screenshot of the provisioned dashboard if the environment allows and check it
  by eye (layout, units, legends); fix what reads badly.
- [ ] **Step 5: Commit** `feat(observability): Grafana dashboard tested against Prometheus and Grafana`.

### Task 5: OpenTelemetry guide and README

**Files:**
- Create: `docs/opentelemetry.md`
- Modify: `README.md` (an "Observability backends" section linking the dashboard, the rules and the guide; roadmap)
- Modify: `observability/build.gradle` (test classpath: the Spring Boot modules containing
  `OtlpTracingProperties`, `OtlpMetricsProperties`, `TracingProperties`; Spring AI's chat observation
  auto-configuration; `:spring-ai-insights-autoconfigure`)
- Test: `observability/src/test/java/io/github/springaiinsights/observability/GuidePropertiesTest.java`

**Interfaces:**
- Consumes: Tasks 3–4 file paths.
- Produces: guide sections — what is exported (metrics from this starter; spans from Spring AI's observations;
  turns stay in your store), dependencies, Prometheus + Grafana (the assets), then Langfuse, Phoenix, SigNoz,
  Datadog: each with the endpoint, the auth header, metrics yes/no, how it renders `gen_ai.*` spans, and its
  source link. Facts:
  - Langfuse: `https://cloud.langfuse.com/api/public/otel/v1/traces` (EU), `us.` and `jp.` variants, self-hosted
    `/api/public/otel/v1/traces` (≥ v3.22.0); `Authorization: Basic base64(public:secret)`; HTTP only, no OTLP
    metrics; maps `gen_ai.*` to generations; prompt/answer text appears only with an `ObservationFilter`, as its
    Spring AI page explains.
  - Phoenix: self-hosted `http://<host>:6006/v1/traces`; `authorization: Bearer <key>` when auth is on; traces
    only; native convention is OpenInference, so raw `gen_ai.*` spans arrive with reduced UI; for full fidelity
    use its OpenInference Spring AI instrumentation (link).
  - SigNoz: Cloud `https://ingest.<region>.signoz.cloud:443` with `signoz-ingestion-key`; self-hosted
    `http://<host>:4318/v1/traces` and `/v1/metrics`; traces and metrics; GenAI conventions supported.
  - Datadog: through the Datadog Agent OTLP receiver (`http://<agent>:4318`, enabled in the Agent; the Agent holds
    the API key); traces and metrics; Agent Observability ingests OTel GenAI semantic conventions 1.37+; lists
    Spring AI (Micrometer) as tested.
- [ ] **Step 1: Write the failing test** `GuidePropertiesTest`:
  `everyPropertyInTheGuideExists` — SnakeYAML-load each ```` ```yaml ```` block of `docs/opentelemetry.md`, flatten
  keys; every key under `management.`, `spring.ai.` or `ai-insights.` is a property in some
  `META-INF/spring-configuration-metadata.json` on the test classpath, or extends a property of `java.util.Map`
  type (`...headers.Authorization`);
  `noBoot3PropertyNames` — the guide contains neither `management.otlp.tracing.` nor
  `spring-boot-starter-opentelemetry`;
  `guideLinksExist` — every relative link in the guide and the README's new section resolves to a file in the repo.
- [ ] **Step 2: Run** — FAIL (guide missing).
- [ ] **Step 3: Write the guide and the README section.** Plain, short, one YAML block per backend; secrets as
  `${LANGFUSE_AUTH}`-style placeholders.
- [ ] **Step 4: Run** `./gradlew -q build` and the starter classpath check — PASS, nothing printed.
- [ ] **Step 5: Commit** `docs: OpenTelemetry backends guide and observability assets in the README`.
