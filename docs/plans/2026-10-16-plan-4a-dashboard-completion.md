# Plan 4a — Dashboard Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Bedrock Knowledge Base path actually recorded, then complete the dashboard goals of spec §3 that
Plan 3 deferred: tools never called, tool p95, documents never/least retrieved (corpus catalog), abandoned
conversations and trace links.

**Architecture:** Capture gains three facts per turn: stable document keys, the tools offered to the model, and the
trace ID. Analytics gains five store-parity queries proven by the shared `InsightsAnalyticsContract`. A new optional
module `spring-ai-insights-bedrock` lists Knowledge Base documents through the Bedrock Agent API. The dashboard joins
these into the Tools, Knowledge, Quality and Conversation pages.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring AI 2.0.1, Micrometer Observation/Tracing, MongoDB Java driver
(floor 5.0), AWS SDK v2 `bedrockagent` 2.51.2 (the version Spring AI 2.0.1's Bedrock KB store uses), JUnit 5,
AssertJ, Mockito, Testcontainers.

**Spec:** `design.md` (§3 goals 3–5 and 7, §8.1 retrieval capture and corpus catalog, §8.7 dashboard).

## Global Constraints

- Prefix `ai-insights`; new properties: `ai-insights.knowledge.document-key` (default `source`),
  `ai-insights.knowledge.observe-stores` (default `true`), `ai-insights.knowledge.bedrock.knowledge-base-ids`,
  `ai-insights.knowledge.bedrock.region`, `ai-insights.knowledge.bedrock.refresh` (default `1h`, at least `1m`),
  `ai-insights.quality.abandoned-after` (default `30m`, at least `1m`), `ai-insights.dashboard.trace-url`.
- "Telemetry must never break the app": every capture addition is caught and degrades to "unknown", never a lost
  turn or a failed chat call (spec §5).
- MongoDB floor stays 5.0: no `$percentile`, `$median` or `$top`; percentiles are computed with sort + skip.
- The starter's runtime classpath must not gain `bedrockagent`, `spring-webmvc`, `spring-security` or
  `spring-data-mongodb`; `spring-ai-insights-bedrock` is a separate opt-in dependency.
- Both bundled stores answer every new query identically (`InsightsAnalyticsContract`), run on Mongo 5.0 and 7.0.
- Dashboard JS keeps the Plan 3 rules: DOM only through `el()`/`svg()`, `textContent`, `setAttribute`; CSP
  `style-src 'self'`; no new runtime dependencies.
- One commit per task; trailer `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

## Review Focus

1. A store that already emits observations (extends `AbstractObservationVectorStore`) must not be wrapped, or
   every query would be counted twice — Task 2 `alreadyObservedStoreIsNotWrapped`.
2. A failing `TraceIdSupplier` or a tool callback whose definition throws must still record the turn, with a null
   trace ID or no offered tools — Task 3 `traceAndToolFailuresStillRecordTurn`.
3. The Bedrock catalog failing on first load (no credentials, wrong region, throttling) must not fail startup or
   the dashboard; the Knowledge page reports the catalog as not loaded — Task 7 `firstLoadFailureLeavesCatalogUnloaded`,
   Task 8 `unloadedCatalogReportsUnknownSize`.
4. `ai-insights.dashboard.trace-url` set to a `javascript:` URL or without `{traceId}` must be rejected at startup,
   so the dashboard can never render a script link — Task 8 `traceUrlMustBeHttpTemplate`.
5. An inactivity window longer than the selected range (idle cutoff before `from`) must yield zero abandoned
   conversations, not an error — Task 6 `idleCutoffBeforeRangeGivesNothing`.

---

### Task 1: Stable document keys

The Bedrock KB store assigns every result `UUID.randomUUID()` as its ID and puts the S3 URI in metadata `source`,
so today's "most retrieved documents" counts random IDs. Use a metadata key as the document key, falling back to
the ID; dedupe keys within one retrieval so `hits` counts retrievals, not chunks.

**Files:**
- Modify: `spring-ai-insights-core/src/main/java/io/github/springaiinsights/capture/InsightsObservationHandler.java`
- Modify: `spring-ai-insights-core/src/main/java/io/github/springaiinsights/analytics/DocumentUsage.java` (javadoc only)
- Modify: `spring-ai-insights-autoconfigure/src/main/java/io/github/springaiinsights/autoconfigure/{InsightsProperties,InsightsAutoConfiguration}.java`
- Test: `spring-ai-insights-core/src/test/java/io/github/springaiinsights/capture/InsightsObservationHandlerTest.java`, `spring-ai-insights-autoconfigure/src/test/java/io/github/springaiinsights/autoconfigure/InsightsAutoConfigurationTest.java`

**Interfaces:**
- Produces: `InsightsObservationHandler(InsightsMetrics metrics, @Nullable String documentKey)`; blank or null
  means "use `Document.getId()`". `InsightsProperties.Knowledge` with `documentKey` (default `"source"`) and
  `observeStores` (default `true`, used by Task 2).

- [ ] **Step 1: Write the failing tests** in `InsightsObservationHandlerTest`:
  - `retrievalUsesMetadataKeyDeduplicated`: handler with key `"source"`; query response `c1{source=s3://kb/a.pdf}`,
    `c2{source=s3://kb/a.pdf}`, `c3{}` → `documentIds == ["s3://kb/a.pdf", "c3"]`, `resultCount == 3`, three scores.
  - `blankKeyUsesDocumentIds`: key `""`, same docs → `["c1", "c2", "c3"]`.
  - `nonStringMetadataIsStringified`: `c1{source=42}` → `["42"]`.
  In `InsightsAutoConfigurationTest`: `documentKeyDefaultsToSource` and `documentKeyIsConfigurable`
  (`ai-insights.knowledge.document-key=file_name`), asserting through a recorded retrieval or a package-visible getter.
- [ ] **Step 2: Run** `./gradlew -q :spring-ai-insights-core:test --tests '*InsightsObservationHandlerTest' :spring-ai-insights-autoconfigure:test --tests '*InsightsAutoConfigurationTest'` — expect compile failure.
- [ ] **Step 3: Implement.** Key = `String.valueOf(metadata.get(documentKey))` when present and non-blank, else
  `getId()`; keep first-seen order (`LinkedHashSet`). Update `DocumentUsage` javadoc: "`hits` counts retrievals that
  returned the document". Wire the property in `InsightsAutoConfiguration`.
- [ ] **Step 4: Run** the same command — PASS; then `./gradlew -q build` — PASS.
- [ ] **Step 5: Commit** `feat(capture): stable document keys from metadata`.

### Task 2: Observe stores that emit no observations

`BedrockKnowledgeBaseVectorStore` is `final` and implements `VectorStore` directly, so it emits no observation and
the spec's day-one path records nothing. Wrap such beans in a decorator that emits Spring AI's own vector-store
observation, so Insights, metrics and traces all see it.

**Files:**
- Create: `spring-ai-insights-core/src/main/java/io/github/springaiinsights/knowledge/ObservedVectorStore.java`
- Create: `spring-ai-insights-autoconfigure/src/main/java/io/github/springaiinsights/autoconfigure/ObservingVectorStorePostProcessor.java`
- Modify: `InsightsAutoConfiguration.java` (static `@Bean`, `@ConditionalOnProperty(prefix = "ai-insights.knowledge", name = "observe-stores", matchIfMissing = true)`), `README.md`
- Test: `spring-ai-insights-core/src/test/java/io/github/springaiinsights/knowledge/ObservedVectorStoreTest.java`, `InsightsAutoConfigurationTest.java`, `InsightsEndToEndTest.java`

**Interfaces:**
- Produces: `public final class ObservedVectorStore implements VectorStore` with
  `ObservedVectorStore(VectorStore delegate, String system, ObservationRegistry registry)`,
  `static String systemName(VectorStore store)`, `VectorStore delegate()`. `systemName` strips a trailing
  `VectorStore` from the simple class name and converts it to lower snake case
  (`BedrockKnowledgeBaseVectorStore` → `bedrock_knowledge_base`); an empty simple name gives `vector_store`.
  Task 7 relies on `"bedrock_knowledge_base"`.

- [ ] **Step 1: Write the failing tests.** `ObservedVectorStoreTest` (with `TestObservationRegistry`):
  - `queryIsObserved`: the context is a `VectorStoreObservationContext` with operation `query`, database system
    `"fake"`, the same `SearchRequest`, and a query response equal to the delegate's documents.
  - `failureIsObservedAndRethrown`: the delegate throws `IllegalStateException` → same exception; the observation
    has that error.
  - `writesAndNameDelegate`: `add`, `delete(List)`, `delete(Filter.Expression)`, `getName`, `getNativeClient` reach
    the delegate.
  - `systemNames`: a nested `class BedrockKnowledgeBaseVectorStore implements VectorStore` → `bedrock_knowledge_base`;
    `class FakeKnowledgeBase` → `fake_knowledge_base`; an anonymous class → `vector_store`.
  In `InsightsAutoConfigurationTest`: `plainStoreIsWrapped`; `alreadyObservedStoreIsNotWrapped` (`SimpleVectorStore`
  stays the same instance); `wrappingCanBeDisabled` (`observe-stores=false`).
  In `InsightsEndToEndTest`, add a bean `knowledgeBase` of class `FakeKnowledgeBase implements VectorStore`. It
  returns two chunks with `source=s3://kb/refunds.pdf`. Add a tool `kb` that queries it through
  `@Qualifier("knowledgeBase") VectorStore`. Test `unobservedKnowledgeBaseIsRecorded`: the turn has one retrieval
  with store `fake_knowledge_base`, `resultCount == 2` and `documentIds == ["s3://kb/refunds.pdf"]`.
- [ ] **Step 2: Run** `./gradlew -q :spring-ai-insights-core:test --tests '*ObservedVectorStoreTest' :spring-ai-insights-autoconfigure:test` — expect failure.
- [ ] **Step 3: Implement.** In `similaritySearch`, build the context with
  `VectorStoreObservationContext.builder(system, Operation.QUERY).queryRequest(request)`. Observe it through
  `VectorStoreObservationDocumentation.AI_VECTOR_STORE.observation(null, new DefaultVectorStoreObservationConvention(), () -> context, registry)`,
  calling `setQueryResponse` before returning. The post-processor wraps in `postProcessAfterInitialization` any
  `VectorStore` that is neither an `AbstractObservationVectorStore` nor an `ObservedVectorStore`. It resolves the
  registry from `ObjectProvider<ObservationRegistry>` at wrap time (`getIfUnique(() -> ObservationRegistry.NOOP)`).
  README: a "Knowledge bases" subsection explains the wrapping, that stores must be injected as `VectorStore`, and
  the opt-out.
- [ ] **Step 4: Run** the same commands — PASS; `./gradlew -q build` — PASS.
- [ ] **Step 5: Commit** `feat(knowledge): observe vector stores that emit no observations`.

### Task 3: Offered tools and trace ID on turns

**Files:**
- Create: `spring-ai-insights-core/src/main/java/io/github/springaiinsights/capture/TraceIdSupplier.java`
- Modify: `core/.../model/ConversationTurn.java`, `core/.../capture/{TurnRecorder,InsightsAdvisor}.java`, `mongodb/.../TurnDocuments.java`, `core/src/testFixtures/.../analytics/ContractTurns.java`, all `new ConversationTurn(` call sites (`TestTurns`, `PreviewData`, `MongoEventStoreTest`), `InsightsAutoConfiguration.java` (pass `TraceIdSupplier.NONE` until Task 4)
- Test: `InsightsAdvisorTest.java`, `InsightsAnalyticsContract.storedTurnRoundTrips`, `MongoEventStoreTest.java`

**Interfaces:**
- Produces:
  - `ConversationTurn` gains two trailing components, `List<String> offeredTools` (copied, sorted, distinct) and
    `@Nullable String traceId`.
  - `@FunctionalInterface TraceIdSupplier { @Nullable String traceId(Observation.@Nullable ContextView current); TraceIdSupplier NONE = current -> null; }`
  - `TurnRecorder.offeredTools(Collection<String> names)` and `TurnRecorder.traceId(@Nullable String traceId)`.
  - The `InsightsAdvisor` constructor gains a trailing `TraceIdSupplier traceIds`.

- [ ] **Step 1: Write the failing tests.** `InsightsAdvisorTest`:
  - `recordsOfferedToolsAndTraceId`: a prompt with tool callbacks `refund`, `lookup` and supplier `c -> "abc123"` →
    `offeredTools == ["lookup", "refund"]`, `traceId == "abc123"`, for call and stream.
  - `traceAndToolFailuresStillRecordTurn`: the supplier throws, and one callback's `getToolDefinition()` throws →
    the turn is recorded with `traceId == null` and `offeredTools == []`; `metrics.internalError` is incremented.
  - `noToolsGivesEmptyList`.
  Set `T1` in `ContractTurns` to `offeredTools = ["lookup", "refund", "search"]`, `traceId = "4bf92f3577b34da6a3ce929d0e0e4736"`
  and `T2` to `["lookup", "refund"]`; `storedTurnRoundTrips` covers both stores. `MongoEventStoreTest.readsTurnWithoutNewFields`:
  a raw document without the two fields reads back as `[]` and `null`.
- [ ] **Step 2: Run** `./gradlew -q :spring-ai-insights-core:test :spring-ai-insights-mongodb:test` — expect compile failure.
- [ ] **Step 3: Implement.** The advisor reads names in `open()` when
  `request.prompt().getOptions() instanceof ToolCallingChatOptions` (Spring AI 2.0.1 merges `.tools(...)` and
  providers into these options before advisors run). Read them in their own try/catch, and the trace ID in another,
  each degrading to empty/null with `metrics.internalError()`. Only the outermost turn records them. In Mongo, store
  the fields `offeredTools` and `traceId`; omit `traceId` when null.
- [ ] **Step 4: Run** the same command — PASS; `./gradlew -q build` — PASS.
- [ ] **Step 5: Commit** `feat(capture): record offered tools and trace ID`.

### Task 4: Micrometer trace ID supplier

**Files:**
- Create: `spring-ai-insights-autoconfigure/src/main/java/io/github/springaiinsights/autoconfigure/MicrometerTraceIdSupplier.java`
- Modify: `InsightsAutoConfiguration.java` (`TraceIdSupplier` bean: nested `@ConditionalOnClass(name = "io.micrometer.tracing.Tracer")` config returning `MicrometerTraceIdSupplier` when `ObjectProvider<Tracer>.getIfUnique()` is non-null, else `NONE`; outer `@ConditionalOnMissingBean` fallback `NONE`), `spring-ai-insights-autoconfigure/build.gradle` (`compileOnly("io.micrometer:micrometer-tracing")`, `testImplementation("io.micrometer:micrometer-tracing-test")`)
- Test: `MicrometerTraceIdSupplierTest.java`, `InsightsAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `TraceIdSupplier` (Task 3).
- Produces: `MicrometerTraceIdSupplier(Tracer tracer) implements TraceIdSupplier`. It walks `current` and its
  parents for a `TracingObservationHandler.TracingContext` with a span, then falls back to `tracer.currentSpan()`.
  The parent walk covers streaming, where no span is in scope on the subscribing thread.

- [ ] **Step 1: Write the failing tests** (`SimpleTracer` plus `DefaultTracingObservationHandler`):
  `traceIdOfObservationSpan`, `parentSpanWhenChildHasNone`, `currentSpanWhenNoContext`, `nullWithoutAnySpan`.
  In `InsightsAutoConfigurationTest`: `tracerGivesMicrometerSupplier`, `noTracerGivesNone`,
  `tracingAbsentFromClasspathGivesNone` (`FilteredClassLoader(Tracer.class)`).
- [ ] **Step 2: Run** `./gradlew -q :spring-ai-insights-autoconfigure:test` — expect failure.
- [ ] **Step 3: Implement** as specified in Interfaces.
- [ ] **Step 4: Run** — PASS; the starter classpath check prints nothing.
- [ ] **Step 5: Commit** `feat(autoconfigure): trace IDs from Micrometer Tracing`.

### Task 5: Tool p95 and offered tools analytics

**Files:**
- Modify: `core/.../analytics/{ToolUsage,InsightsAnalytics}.java`, `core/.../store/InMemoryEventStore.java`, `mongodb/.../MongoEventStore.java`, `ContractTurns`/`InsightsAnalyticsContract`
- Test: `InsightsAnalyticsContract.java` (runs for both stores)

**Interfaces:**
- Produces: `ToolUsage(String tool, long calls, long errors, long turns, double avgDurationMs, long p95DurationMs, long repeatedCalls)`.
  p95 is nearest-rank: ascending durations, element `ceil(0.95 × n) − 1`.
  `List<String> InsightsAnalytics.offeredTools(InsightsFilter filter)`: distinct, sorted names offered on turns in
  the filter.

- [ ] **Step 1: Write the failing tests.** `toolsMostUsedFirst` adds `p95DurationMs` 50 (lookup) and 10 (refund).
  - `toolP95IsNearestRank`: save one turn in app `"perf"` inside the range, with 20 calls of `t` lasting 1..20 ms →
    p95 = 19.
  - `offeredToolsDistinctSorted`: SHOP → `["lookup", "refund", "search"]`; HELP → `[]`.
- [ ] **Step 2: Run** `./gradlew -q :spring-ai-insights-core:test :spring-ai-insights-mongodb:test` — expect failure.
- [ ] **Step 3: Implement.**
  - **Mongo p95:** after the existing grouping, run one query per tool: `$match` filter, `$unwind` toolCalls,
    `$match` name, `$sort` durationMs, `$skip` rank, `$limit 1`, with `allowDiskUse`.
  - **Mongo offered tools:** `$unwind offeredTools`, then `$group`, then `$sort`.
  - **In-memory:** sort the durations.
- [ ] **Step 4: Run** — PASS on Mongo 5.0, then `-PmongoImage=mongo:7.0 --rerun-tasks` — PASS.
- [ ] **Step 5: Commit** `feat(analytics): tool p95 and offered tools`.

### Task 6: Least-retrieved documents and abandoned conversations

**Files:**
- Create: `core/.../analytics/{AbandonedConversation,AbandonedConversations,AbandonReason}.java`
- Modify: `InsightsAnalytics.java`, `InMemoryEventStore.java`, `MongoEventStore.java`
- Test: `InsightsAnalyticsContract.java`

**Interfaces:**
- Produces (on `InsightsAnalytics`):
  - `List<DocumentUsage> leastDocuments(InsightsFilter filter, int limit)`: fewest hits first, then store, then
    document ID; only documents retrieved at least once.
  - `Set<String> retrievedDocumentIds(InsightsFilter filter, String store)`.
  - `AbandonedConversations abandonedConversations(InsightsFilter filter, Instant idleBefore, int limit)`.
  - `enum AbandonReason { ERROR, CANCELLED, NEGATIVE_FEEDBACK }`.
  - `record AbandonedConversation(String conversationId, @Nullable String userId, String lastTurnId, Instant lastTurnAt, AbandonReason reason)`.
  - `record AbandonedConversations(long count, List<AbandonedConversation> recent)`: `recent` is newest
    `lastTurnAt` first, then conversation ID, at most `limit`.
- Definition (javadoc on the method): the conversation's last turn is computed over turns of the filter's
  application and tenant from `filter.from()` onwards, with no upper bound. That turn must start before both
  `filter.to()` and `idleBefore`. It must be ERROR or CANCELLED (reported as such), or otherwise carry DOWN feedback
  (NEGATIVE_FEEDBACK).

- [ ] **Step 1: Write the failing tests:**
  - `leastDocumentsFewestFirst`: HELP, limit 2 → `(faq, q1, 1), (kb, d2, 1)`.
  - `retrievedDocumentIdsPerStore`: HELP kb → `{d1, d2}`, faq → `{q1}`, `"other"` → `{}`.
  - `abandonedConversations`: HELP, idleBefore `TO`, limit 2 → count 3; recent = `h3` (`H4`, ERROR, user null),
    `h2` (`H3`, ERROR, `v2`). With limit 10 the third is `h1` (`H2`, NEGATIVE_FEEDBACK, `v1`). SHOP → count 2:
    `c3` (CANCELLED), `c2` (ERROR).
  - `stillActiveIsNotAbandoned`: HELP, idleBefore `2026-10-02T11:00:00Z` → count 2 (`h2`, `h1`).
  - `laterTurnOutsideRangeMeansNotAbandoned`: save a SUCCESS turn in `h2` at `2026-10-03T08:00:00Z` → HELP count 2.
  - `abandonedRespectsTenant`: HELP, tenant `globex` → count 1 (`h2`).
  - `idleCutoffBeforeRangeGivesNothing`: idleBefore = `FROM` → count 0, `recent` empty.
- [ ] **Step 2: Run** `./gradlew -q :spring-ai-insights-core:test :spring-ai-insights-mongodb:test` — expect failure.
- [ ] **Step 3: Implement.** The Mongo pipeline for abandoned conversations:
  1. `$match` application/tenant, `startedAt ≥ from`.
  2. `$sort {conversationId: 1, startedAt: -1}`, then `$group` on conversationId with `$first` fields.
  3. `$match lastTurnAt < min(to, idleBefore)`.
  4. `$lookup` the feedback collection (`localField` lastTurnId, `foreignField` `_id`).
  5. `$match` outcome ∈ {ERROR, CANCELLED} or rating DOWN.
  6. `$facet` into a count and a sorted, limited list. Use `allowDiskUse`.
- [ ] **Step 4: Run** — PASS on Mongo 5.0 and 7.0.
- [ ] **Step 5: Commit** `feat(analytics): least-retrieved documents and abandoned conversations`.

### Task 7: Knowledge corpus catalog and Bedrock implementation

**Files:**
- Create: `core/.../knowledge/KnowledgeCorpusCatalog.java`
- Create module `spring-ai-insights-bedrock`: `build.gradle` (`api(project(":spring-ai-insights-core"))`, `api(platform("software.amazon.awssdk:bom:${awsSdkVersion}"))`, `api("software.amazon.awssdk:bedrockagent")`), `src/main/java/io/github/springaiinsights/bedrock/BedrockKnowledgeBaseCatalog.java`
- Modify: `settings.gradle`, `gradle.properties` (`awsSdkVersion=2.51.2`), `InsightsProperties` (`Knowledge.Bedrock`: `knowledgeBaseIds`, `region`, `refresh`)
- Create: `autoconfigure/.../InsightsBedrockAutoConfiguration.java` (registered in `AutoConfiguration.imports`; `@ConditionalOnClass({BedrockKnowledgeBaseCatalog.class, BedrockAgentClient.class})`, `@ConditionalOnProperty(prefix = "ai-insights.knowledge.bedrock", name = "knowledge-base-ids[0]")`; uses a unique `BedrockAgentClient` bean, else builds one with the optional region and closes it on shutdown); autoconfigure `compileOnly` on the module and `bedrockagent`
- Test: `spring-ai-insights-bedrock/src/test/java/io/github/springaiinsights/bedrock/BedrockKnowledgeBaseCatalogTest.java` (Mockito mock of `BedrockAgentClient`), `InsightsBedrockAutoConfigurationTest.java`

**Interfaces:**
- Produces:
  - `interface KnowledgeCorpusCatalog { String store(); @Nullable Set<String> documentIds(); }`. Its IDs are in
    the same form as recorded retrieval keys; `null` means "not loaded yet".
  - `BedrockKnowledgeBaseCatalog(BedrockAgentClient client, List<String> knowledgeBaseIds, Duration refresh) implements KnowledgeCorpusCatalog, AutoCloseable`
    with `store() == "bedrock_knowledge_base"`, `void refresh()` (synchronous, for tests) and `void start()` (a
    daemon single-thread scheduler, first run immediately, then a fixed delay of `refresh`).
- Listing: for each knowledge base, page `listDataSources` by `nextToken`; for each data source, page
  `listKnowledgeBaseDocuments` (`maxResults` 1000). Keep documents whose status is `INDEXED`, `PARTIALLY_INDEXED`,
  `METADATA_PARTIALLY_INDEXED` or `METADATA_UPDATE_FAILED`. The ID is `identifier().s3().uri()`, else
  `identifier().custom().id()`. These equal the `source` metadata the Bedrock KB store sets (verified in
  `BedrockKnowledgeBaseVectorStore.extractLocationMetadata`, 2.0.1).

- [ ] **Step 1: Write the failing tests:**
  - `listsAllPagesOfAllDataSources`: two data sources over two `listDataSources` pages; documents over two pages with
    s3 and custom identifiers → `{s3://kb/a.pdf, s3://kb/b.pdf, doc-42}`.
  - `skipsDocumentsNotRetrievable`: `FAILED`, `DELETING`, `PENDING` are left out.
  - `firstLoadFailureLeavesCatalogUnloaded`: the client throws `SdkClientException` → `documentIds() == null`, and
    no exception escapes `refresh()`.
  - `failedRefreshKeepsPreviousSnapshot`.
  Autoconfiguration: `catalogWhenKnowledgeBaseConfigured` (mock client bean, store name), `noCatalogWithoutProperty`,
  `noCatalogWithoutSdk` (`FilteredClassLoader(BedrockAgentClient.class)`), `refreshBelowOneMinuteIsRejected`.
- [ ] **Step 2: Run** `./gradlew -q :spring-ai-insights-bedrock:test :spring-ai-insights-autoconfigure:test` — expect failure.
- [ ] **Step 3: Implement.** Replace the snapshot atomically (`volatile Set<String>`, `Set.copyOf`). Log refresh
  failures at WARN with the knowledge base ID, never the stack trace at every refresh.
- [ ] **Step 4: Run** — PASS; `./gradlew -q build` — PASS; the starter classpath check plus
  `| grep bedrockagent` prints nothing.
- [ ] **Step 5: Commit** `feat(bedrock): Knowledge Base corpus catalog`.

### Task 8: Dashboard pages, UI and docs

**Files:**
- Create: `web/.../pages/CorpusCoverage.java`, `web/.../DashboardOptions.java`
- Modify: `web/.../DashboardPages.java`, `pages/{ToolsPage,KnowledgePage,QualityPage,DashboardConfig}.java`, `ai-insights-dashboard/{app.js,app.css}`, `autoconfigure/.../{InsightsProperties,InsightsWebAutoConfiguration}.java`, `web/src/test/.../{PreviewData,DashboardPreview}.java`, `README.md`
- Test: `DashboardPagesTest.java`, `DashboardRoutesTest.java`, `InsightsWebAutoConfigurationTest.java`

**Interfaces:**
- Consumes: Tasks 5–7 analytics and `KnowledgeCorpusCatalog`.
- Produces:
  - `record DashboardOptions(List<KnowledgeCorpusCatalog> catalogs, Duration abandonedAfter, @Nullable String traceUrl)`
    with `DEFAULTS` (no catalogs, 30 min, null). A new `DashboardPages` constructor takes it as a trailing
    parameter; the 5-argument constructor uses `DEFAULTS`.
  - `ToolsPage(List<ToolUsage> tools, List<String> neverCalled)`: offered tools minus called ones, sorted.
  - `KnowledgePage(..., List<DocumentUsage> topDocuments, List<DocumentUsage> leastDocuments, List<CorpusCoverage> coverage)`.
  - `CorpusCoverage(String store, @Nullable Integer documents, int neverRetrieved, List<String> neverRetrievedSample)`:
    the sample is sorted and capped at `NEVER_RETRIEVED_SAMPLE = 50`; it is `null`/0/empty while not loaded.
  - `QualityPage(..., AbandonedConversations abandoned)`: idleBefore = `min(filter.to(), now − abandonedAfter)`,
    limit `RECENT_ABANDONED = 20`.
  - `DashboardConfig(..., @Nullable String traceUrl)`.

- [ ] **Step 1: Write the failing tests.** `DashboardPagesTest`:
  - `toolsNeverCalled`: SHOP → `["search"]`.
  - `knowledgeCoverage`: catalog `kb = {d1, d2, d3, d4}`, HELP → documents 4, neverRetrieved 2, sample `[d3, d4]`.
  - `neverRetrievedSampleIsCapped`: 60 uncatalogued IDs → sample size 50, count 60.
  - `unloadedCatalogReportsUnknownSize`: `documentIds() == null` → `documents == null`, 0, `[]`.
  - `qualityHasAbandoned`: with `now` 10 minutes after H4 and `abandonedAfter` 30m, `h3` is excluded.
  - `configCarriesTraceUrl`.
  `DashboardRoutesTest`: the JSON of `/api/tools` contains `neverCalled`, `/api/knowledge` contains
  `leastDocuments` and `coverage`, and `/api/quality` contains `abandoned`. `InsightsWebAutoConfigurationTest`:
  - `traceUrlMustBeHttpTemplate`: `javascript:alert(1)/{traceId}` and `https://x/` (no placeholder) fail startup.
  - `abandonedAfterBelowOneMinuteIsRejected`.
  - `catalogsReachDashboard`.
- [ ] **Step 2: Run** `./gradlew -q :spring-ai-insights-web:test :spring-ai-insights-autoconfigure:test` — expect failure.
- [ ] **Step 3: Implement pages and wiring.** Add properties `ai-insights.quality.abandoned-after` and
  `ai-insights.dashboard.trace-url`; validate both in their setters.
- [ ] **Step 4: Implement the UI:**
  - Tools: a p95 column and a "Never called in this period" list, or "All offered tools were called".
  - Knowledge: a "Least retrieved" table. Per catalog: "N of M documents never retrieved", followed by the sample.
    An unloaded catalog shows "Catalog not loaded yet". With no catalog, show a hint naming
    `ai-insights.knowledge.bedrock.knowledge-base-ids`.
  - Quality: an "Abandoned conversations" tile with the count and the rate over `summary.conversations`, plus a
    table linking to the conversation page with the reason.
  - Conversation: each turn shows its trace ID. When `traceUrl` is set it is an `<a>` whose `href` replaces
    `{traceId}` with `encodeURIComponent(traceId)`, with `rel="noopener noreferrer"` and `target="_blank"`.
  - Extend `PreviewData` with offered tools, trace IDs, abandoned conversations and an in-memory catalog. Run
    `previewDashboard`, screenshot the Tools, Knowledge, Quality and Conversation pages at 1280 and 500 width, and
    fix any layout defect.
- [ ] **Step 5: README.** Add the new configuration rows (defaults copied from `InsightsProperties`), the Bedrock
  catalog setup (dependency, the IAM actions `bedrock:ListDataSources` and `bedrock:ListKnowledgeBaseDocuments`,
  and the properties), the definition of "abandoned", and trace links. Remove the delivered items from the roadmap.
- [ ] **Step 6: Run** `./gradlew -q build` and the Mongo 7.0 run — PASS; the starter classpath check prints nothing.
- [ ] **Step 7: Commit** `feat(web): tools never called, corpus coverage, abandoned conversations, trace links`.

## Not in this plan

- Plan 4b: weekly report (Slack, email, endpoint, store lock).
- Plan 4c: Grafana dashboard JSON, alert rules, OTLP backend guide.
- Plan 4d: demo helpdesk app, CI, Maven Central release (needs the group ID decision).
- Catalogs for stores other than Bedrock KB: users implement `KnowledgeCorpusCatalog`.
