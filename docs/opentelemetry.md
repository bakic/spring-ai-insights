# Sending Spring AI Insights to an observability backend

spring-ai-insights keeps the full record of every conversation in your own store (MongoDB or JDBC)
and serves it through the built-in dashboard. On top of that, it publishes **Micrometer metrics**
that any monitoring backend can collect. Spring AI itself publishes **traces** for every model call.
This guide shows how to send both to the tools you already use.

All the configuration below uses Spring Boot 4 property names. Every property in this guide is
checked by a test against the metadata of the real Spring Boot and Spring AI jars.

## What leaves your application

| Signal | Produced by | Contains | Contains no |
|---|---|---|---|
| Metrics `ai.insights.*` | this starter | turns, tokens, cost, tool calls, retrievals, feedback, time to first token, data health | user IDs, conversation IDs, prompt or answer text |
| Spans | Spring AI | model calls: model, token usage (`gen_ai.usage.*`), finish reasons, request options. Vector-store searches: **the search query**. Tool calls: arguments and results, only if `spring.ai.tools.observations.include-content` is on | prompt or answer text of model calls |
| Conversations | this starter | the turns, with content only if `ai-insights.capture-content` is on | – they stay in your store |

Metric tags are low-cardinality by design: `application`, `model`, `type`, `outcome`, `tool`,
`store`, `rating`, `currency`. The `tenant` tag is added to cost only when
`ai-insights.metrics.tenant-tag` is on.

Spring AI keeps prompts and answers off its model-call spans; its `spring.ai.chat.observations.log-prompt`
and `log-completion` switches write them to the **application log** at INFO level instead. Leave them
off in production unless your log pipeline is cleared for that data.

Two kinds of span can still carry user text:

- Every vector-store search span has a `db.vector.query.content` attribute holding the search query,
  and Spring AI has no switch to turn it off. With `QuestionAnswerAdvisor` or any retrieval-augmented
  setup, that query is usually the user's question.
- Tool-call spans carry the tool arguments and results when `spring.ai.tools.observations.include-content`
  is on (it is off by default).

Before sending traces to a third-party service, drop or redact `db.vector.query.content`: with an
`ObservationFilter` bean in the application, or with an attribute processor in an OpenTelemetry
Collector placed between the application and the backend.

When Micrometer Tracing is on the classpath, every turn stored by spring-ai-insights carries the
trace ID of its request. Set `ai-insights.dashboard.trace-url` to jump from a conversation in the
dashboard to its trace in your backend.

## Prometheus and Grafana

This is the path the shipped assets are built for. The starter already brings Spring Boot
Actuator; add the Prometheus registry and expose its endpoint:

```kotlin
runtimeOnly("io.micrometer:micrometer-registry-prometheus")
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
```

Then point Prometheus at `/actuator/prometheus` and load the two assets:

- [`observability/grafana/ai-insights-dashboard.json`](../observability/grafana/ai-insights-dashboard.json):
  import it in Grafana (*Dashboards → New → Import*) and pick your Prometheus data source. It has
  an `application` variable, so one dashboard covers all your applications.
- [`observability/prometheus/ai-insights-alerts.yml`](../observability/prometheus/ai-insights-alerts.yml):
  add it to `rule_files` in `prometheus.yml`. It contains five alerts:

| Alert | Fires when |
|---|---|
| `AiInsightsCostSpike` | the last hour cost more than 3× the hourly average of the previous 7 days (and more than 1 unit); applications with less than 7 days of history are not judged |
| `AiInsightsToolErrorRate` | more than 10% of a tool's calls failed over 15 minutes (at least 10 calls) |
| `AiInsightsEmptyRetrievalRate` | more than 30% of a knowledge base's searches returned nothing over an hour (at least 20 searches) |
| `AiInsightsSlowFirstToken` | the 95th percentile time to first token was above 5 seconds over 10 minutes (at least 10 streamed answers) |
| `AiInsightsEventsLost` | turns were dropped (queue full) or could not be written to the store |

Both assets are tested against a real Prometheus and Grafana: every panel query must return data
and every alert has a `promtool` unit test. They expect the metric names that
`micrometer-registry-prometheus` produces (for example `ai_insights_cost_total`). If your metrics
reach Prometheus through OTLP instead, the names may differ and the queries need adjusting.

## OpenTelemetry (OTLP)

For any OTLP backend, add Spring Boot's OpenTelemetry starter. It brings Micrometer Tracing with the
OpenTelemetry bridge, the OTLP span exporter and the OTLP metrics registry:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
```

Tracing samples 10% of requests by default. While you set things up, sample everything:

```yaml
management:
  tracing:
    sampling:
      probability: 1.0
  opentelemetry:
    resource-attributes:
      deployment.environment: production
```

The backends below differ in what they accept. Langfuse and Phoenix only take traces, so turn the
OTLP metrics export off for them.

### Langfuse

Langfuse turns Spring AI's spans into LLM generations with model, tokens and cost.
Sources: [OpenTelemetry endpoint](https://langfuse.com/integrations/native/opentelemetry),
[Spring AI integration](https://langfuse.com/integrations/frameworks/spring-ai).

```yaml
management:
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: https://cloud.langfuse.com/api/public/otel/v1/traces
          headers:
            Authorization: Basic ${LANGFUSE_AUTH}
            x-langfuse-ingestion-version: "4"
  otlp:
    metrics:
      export:
        enabled: false
```

- `LANGFUSE_AUTH` is `base64(public-key:secret-key)`, for example
  `echo -n "pk-lf-…:sk-lf-…" | base64`.
- The host above is the EU region. Use `us.cloud.langfuse.com` or `jp.cloud.langfuse.com` for the
  other regions, or your own host for a self-hosted Langfuse (v3.22.0 or later).
- Langfuse accepts OTLP over HTTP only, which is the starter's default transport.

### Arize Phoenix

Source: [Phoenix tracing setup](https://arize.com/docs/phoenix/tracing/how-to-tracing/setup-tracing/setup-using-phoenix-otel).

```yaml
management:
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: http://localhost:6006/v1/traces
          headers:
            Authorization: Bearer ${PHOENIX_API_KEY}
  otlp:
    metrics:
      export:
        enabled: false
```

- Remove the `Authorization` header if authentication is off on your Phoenix instance.
- Phoenix is built around the OpenInference conventions, while Spring AI emits the OpenTelemetry
  GenAI conventions (`gen_ai.*`). Spans arrive and can be explored, but check how much of the
  LLM-specific view Phoenix fills in for them before relying on it.

### SigNoz

SigNoz takes both traces and metrics, so the starter's metrics can feed SigNoz dashboards and
alerts too. Source: [SigNoz Cloud ingestion](https://signoz.io/docs/ingestion/signoz-cloud/overview/).

```yaml
management:
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: https://ingest.${SIGNOZ_REGION}.signoz.cloud:443/v1/traces
          headers:
            signoz-ingestion-key: ${SIGNOZ_INGESTION_KEY}
  otlp:
    metrics:
      export:
        url: https://ingest.${SIGNOZ_REGION}.signoz.cloud:443/v1/metrics
        headers:
          signoz-ingestion-key: ${SIGNOZ_INGESTION_KEY}
```

- Your region and key are under *Settings → Ingestion* in SigNoz.
- For a self-hosted SigNoz, use `http://<host>:4318/v1/traces` and `http://<host>:4318/v1/metrics`
  and drop the headers; ingestion keys only exist in SigNoz Cloud.

### Datadog

The simplest route is the OTLP receiver of the Datadog Agent running next to your application. It
takes traces and metrics and needs no API key in the application.
Sources: [OTLP ingestion by the Agent](https://docs.datadoghq.com/opentelemetry/setup/otlp_ingest_in_the_agent/),
[LLM traces over OpenTelemetry](https://docs.datadoghq.com/llm_observability/instrumentation/otel_instrumentation/).

```yaml
management:
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: http://localhost:4318/v1/traces
  otlp:
    metrics:
      export:
        url: http://localhost:4318/v1/metrics
        aggregation-temporality: delta
```

- OTLP ingestion is off by default in the Agent: enable the HTTP receiver on port 4318 (for
  example with `DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_HTTP_ENDPOINT=0.0.0.0:4318`).
- Datadog [works best with delta temporality](https://docs.datadoghq.com/opentelemetry/guide/otlp_delta_temporality/)
  for counters and histograms, hence `aggregation-temporality: delta`.
- Spans and metrics land in Datadog APM and metrics. Datadog's LLM Observability product only
  reads spans that follow the OpenTelemetry GenAI conventions 1.37 or later. Spring AI 2.0.1 emits
  an earlier version of them (for example `gen_ai.system` instead of `gen_ai.provider.name`), so do
  not expect its spans to appear there yet.

## When vendor docs do not match

Vendor guides often show `OTEL_EXPORTER_OTLP_*` environment variables, which configure the
OpenTelemetry SDK directly, or Spring Boot 3 property names such as `management.otlp.tracing.*`,
which Spring Boot 4 no longer reads: they are silently ignored. Translate them to the properties above:

| You see | Use |
|---|---|
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `management.otlp.tracing.endpoint` | `management.opentelemetry.tracing.export.otlp.endpoint` |
| `OTEL_EXPORTER_OTLP_TRACES_HEADERS`, `management.otlp.tracing.headers` | `management.opentelemetry.tracing.export.otlp.headers` |
| `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` | `management.otlp.metrics.export.url` |
| `OTEL_EXPORTER_OTLP_METRICS_HEADERS` | `management.otlp.metrics.export.headers` |
| `OTEL_RESOURCE_ATTRIBUTES` | `management.opentelemetry.resource-attributes` |

Endpoints in these properties are full URLs including `/v1/traces` or `/v1/metrics`. The SDK
variable `OTEL_EXPORTER_OTLP_ENDPOINT` appends those paths for you; Spring Boot does not.
