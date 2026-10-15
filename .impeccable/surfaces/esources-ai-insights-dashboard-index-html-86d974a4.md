---
version: 1
slug: "esources-ai-insights-dashboard-index-html-86d974a4"
primary_target: "spring-ai-insights-web/src/main/resources/ai-insights-dashboard/index.html"
related_targets: []
---

## Scope

The built-in dashboard at `/ai-insights` (all pages: Statement/Overview, Usage, Cost, Knowledge base, Tools, Quality, Conversation). Mode: operate.

## Audience and task

Engineer and product owner of a Spring AI assistant, at a desk, in a weekly review or while investigating a reported problem. They compare this period with the last and trace any number to one conversation.

## Direction contract

THESIS: The assistant is metered like a utility. Every page reads as a consumption statement: this period against the previous one, priced with the team's own tariff, itemised down to one turn. It refuses the KPI-card grid with line charts that every observability dashboard ships.

OWN-WORLD: Statement-ultramarine rail (#1f2f86) with white active tab; cool sheet ground (#f2f4f7) and white ruled sheets with 1px hairlines (#d9dde6), 8px radius, no shadows; ink #141a2e. Sodium yellow (#f4c21f) marks only "this period"; grey-blue (#a9b3cf) marks "previous period"; red (#c23a2b) only for failures, green (#1d7a46) only for improvement. Public Sans, self-hosted, tabular figures everywhere.

STORY: The reader learns in one sentence what happened, sees the meter readings against last period, then follows any line to the itemised turns and one conversation.

FIRST VIEWPORT: Ultramarine rail left (brand, application, page tabs). Filter bar on top. A statement sheet split in two: left the amount for the period (56px figure), per-turn price, month-to-date and estimate notes; right a plain-language answer sentence and the meter-reading table (reading, this period, previous, change, paired bars). Below: paired daily bars (sodium vs grey-blue, one shared scale) and the tariff applied.

FORM: Consumption Statement, candidate 4 of 7 on my grounded list. Raises: fixed tabular cells (split-flap), conversation as one ordered line of stops (transit), one plain-language answer leads each page (feed), hairline module density (dense web), one shared scale for every period chart (folio). seed: none (unavailable)

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
