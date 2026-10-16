# Product

<!-- impeccable:product-schema 1 -->

> Facts marked *(inferred)* come from `design.md` and the code, not from an interview; confirm or correct them.

## Platform

web

## Users

- **Engineer** who built a Spring AI assistant: is it healthy, why did a turn fail, what does a conversation look like step by step.
- **Product owner** of that assistant: is it used, what does it cost, which knowledge-base documents and tools matter, where does it fail.
- Management does not open the dashboard; it reads the weekly report (Slack + email).
- *(inferred)* Both readers open the dashboard in a working session at a desk, on a laptop or external screen, usually during a weekly review or while investigating a reported problem.

## Product Purpose

`spring-ai-insights` is a Spring Boot starter. One dependency gives a Spring AI application a built-in dashboard at `/ai-insights` that answers five questions about its assistant: who uses it, what it costs, what it relies on (knowledge base, tools), what it does well, and where it fails. Success: a team stops "flying blind" without adding infrastructure.

## Positioning

It lives inside the application itself: no extra server, no vendor, no collector. The dashboard measures *turns* (one outermost `ChatClient` call, with its model calls, searches and tool calls folded in), priced with the team's own price catalog, with user IDs pseudonymised by default.

## Operating Context

- Served by the application under `/ai-insights`, behind Spring Security role `AI_INSIGHTS`.
- Data from the in-memory store (one instance, since restart) or MongoDB (all instances, 90-day retention).
- Filters: application, period (24 h / 7 / 30 / 90 days), tenant. Times shown in the browser's zone.
- Pages: Overview, Usage, Cost, Knowledge base, Tools, Quality, and one Conversation view.
- The same data also flows to Prometheus/OpenTelemetry; Grafana assets ship separately.

## Capabilities and Constraints

- No consumer build step: plain HTML, CSS and JavaScript packaged in the jar (`spring-ai-insights-web`).
- *(inferred)* No external requests from the browser (corporate networks, CSP): no font or script CDNs. Bundled assets are allowed.
- Charts are drawn by the dashboard's own code from JSON endpoints under `/ai-insights/api/*`.
- Money in the configured currency; tokens, latency, scores as measured. No message content stored by default.
- Terminology: turn, conversation, active user, tenant, search, empty search, tool call, failed turn, feedback.

## Evidence on Hand

- Demo app (`demo/`) generating realistic helpdesk traffic; screenshots in `docs/images/demo-*.png`.
- No customers, testimonials or benchmarks exist; never invent them.

## Product Principles

1. Answer a reader's question before showing a metric.
2. Every number is traceable to turns and, from there, to one conversation.
3. Honest about coverage: say when data is partial (in-memory store, missing prices, empty periods).
4. Privacy by default: pseudonymised users, no content.
5. Zero friction: works the moment the dependency is added.

## Accessibility & Inclusion

*(inferred)* WCAG 2.2 AA: keyboard navigation, visible focus, sufficient contrast, charts with text equivalents, reduced motion respected.
