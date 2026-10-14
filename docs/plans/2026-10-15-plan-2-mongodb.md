# spring-ai-insights — Plan 2: Analytics queries and MongoDB store

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist turns in the application's own MongoDB (auto-selected, TTL retention, safe
across instances) and answer the questions the dashboard and weekly report will ask — who uses
the assistant, what it costs, which tools and knowledge bases are used — with identical results
from the in-memory and MongoDB stores.

**Architecture:** A store-agnostic query SPI `InsightsAnalytics` (summary, timeline, models,
tools, retrievals, users, tenants, conversation, applications) is implemented by
`InMemoryEventStore` and by a new `MongoEventStore` (explicit BSON mapping, aggregation pipeline,
lazy index creation). One contract test in core's test fixtures runs against both, so the two
can never disagree. Auto-configuration picks MongoDB when a `MongoTemplate` exists. Plan 1's
deferred minor (a turn whose model call never reports back is lost silently) is closed first.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring AI 2.0.1, Spring Data MongoDB (Boot BOM,
driver 5.8), Testcontainers 2.0.5 (`org.testcontainers.mongodb.MongoDBContainer`), JUnit 5, AssertJ.

**Spec:** `../../design.md` (sections 5, 8.3, 8.5, 8.6, 9, 13). Plan 1: `2026-10-14-plan-1-core.md`.

**Plan series:** 1 Core capture (done) · 2 Analytics + MongoDB (this) · 3 Dashboard + feedback ·
4 Weekly report, Bedrock KB catalog, OTel naming, Grafana assets, demo app, Maven Central release.

## Global Constraints

- All Plan 1 Global Constraints still apply (Java 21, Boot 4.1.1, Spring AI 2.0.1, prefix
  `ai-insights`, never break or slow the chat, no IDs as metric tags, records, no Lombok).
- MongoDB server floor **5.0** (`$dateTrunc`). Tests run against `mongo:5.0`.
- Boot 4 Mongo coordinates: `spring-boot-data-mongodb`; auto-config
  `org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration`; URI property
  `spring.mongodb.uri`.
- Spring Data MongoDB is **never** a transitive dependency of the starter: the mongodb module
  declares it `compileOnly`; it is used only when the application already has it.
- Insights must never stop the host app from starting because MongoDB is unreachable: no Mongo
  I/O at startup; indexes are created lazily by the writer thread.
- Costs stored as `Decimal128`; aggregated costs are the sum of **priced model calls**
  (unpriced calls contribute nothing and are counted separately) — a single unpriced model must
  not erase known spend.
- Time ranges are half-open `[from, to)`; day/hour buckets are computed in the filter's `ZoneId`.
- Stored timestamps have millisecond precision (BSON date).
- New property defaults: `ai-insights.store` = `auto` (`memory` | `mongodb` | `auto`),
  `ai-insights.retention` = `90d`, `ai-insights.mongodb.collection-prefix` = `ai_insights_`,
  `ai-insights.capture.stall-timeout` = `30s`.
- Missing pseudonymisation secret with any store other than `InMemoryEventStore` → startup
  failure with exactly: `ai-insights.identity.secret must be set when turns are stored persistently: pseudonymised user IDs must stay stable across restarts`.

## Review Focus

1. **MongoDB down at startup or at runtime** → the app starts, chats answer, failures are counted
   in `ai.insights.store.errors`. *(Task 5 `chatWorksWhenMongoIsUnreachable`)*
2. **Retention changed between deployments** → the TTL index is updated (`collMod`), not a startup
   or write failure. *(Task 3 `retentionChangeUpdatesTtlIndex`)*
3. **A turn with an unpriced model** → known cost still summed; turn counted in
   `unpricedTurns`. *(Task 2 contract `summaryCountsEverything`, `modelsSplitPricedAndUnpriced`)*
4. **Day boundaries in the user's time zone** → a turn at 23:30 UTC lands on the next day for
   `Europe/Paris`; a turn at exactly `from` is included, one millisecond before is not.
   *(Task 2 contract `timelineBucketsByDayInZone`, `rangeIsHalfOpen`)*
5. **Two app instances writing the same turn ID twice (retry)** → one document, last write wins.
   *(Task 3 `saveIsIdempotent`)*

---

## File structure

```
spring-ai-insights-core/src/main/java/io/github/springaiinsights/
  capture/TurnTimeouts.java                     (new, Task 1)
  analytics/InsightsAnalytics.java, InsightsFilter.java, Granularity.java,
            UsageSummary.java, TimeBucket.java, ModelUsage.java, ToolUsage.java,
            RetrievalUsage.java, UserUsage.java, TenantUsage.java          (new, Task 2)
  store/InMemoryEventStore.java                 (implements InsightsAnalytics, Task 2)
spring-ai-insights-core/src/testFixtures/java/io/github/springaiinsights/analytics/
  InsightsAnalyticsContract.java, ContractTurns.java                       (new, Task 2)
spring-ai-insights-mongodb/                                                (new module, Tasks 3-4)
  build.gradle.kts
  src/main/java/io/github/springaiinsights/mongodb/{MongoEventStore,TurnDocuments,MongoIndexes}.java
  src/test/java/io/github/springaiinsights/mongodb/{MongoEventStoreTest,MongoAnalyticsContractTest}.java
spring-ai-insights-autoconfigure/…/{InsightsMongoAutoConfiguration,MongoStoreCondition}.java (Task 5)
spring-ai-insights-starter/build.gradle.kts     (adds the mongodb module, Task 5)
README.md                                       (Task 6)
```

---

### Task 1: Stalled turns are finished and counted

Closes Plan 1's deferred minor: if a model observation never stops, the turn waits forever.

**Files:**
- Create: `core/.../capture/TurnTimeouts.java`
- Modify: `core/.../capture/{TurnRecorder,InsightsAdvisor}.java`, `core/.../metrics/InsightsMetrics.java`,
  `autoconfigure/.../{InsightsProperties,InsightsAutoConfiguration}.java`, `core/src/test/.../ScriptedChatModel.java` (and its autoconfigure copy)
- Test: `core/.../capture/{TurnRecorderTest,InsightsAdvisorTest}.java`

**Interfaces:**
- Produces:
  - `TurnRecorder.finishWhenIdle(...)` now returns `boolean` — `true` when the finish was deferred.
  - `TurnRecorder.forceFinish(): boolean` — runs a pending finish now (ignoring open model calls);
    `true` if one ran. A later `endModelCall()` is a no-op.
  - `final class TurnTimeouts implements AutoCloseable` — `TurnTimeouts(Duration stallTimeout)`,
    `void schedule(Runnable task)` (runs after `stallTimeout` on one daemon thread named
    `ai-insights-timeouts`, created lazily), `close()` cancels pending tasks.
  - `InsightsMetrics.turnStalled()` → counter `ai.insights.turns.stalled` (tag `application`).
  - `InsightsAdvisor` constructor gains a last parameter `TurnTimeouts timeouts`. When
    `finishWhenIdle` returns `true`, it schedules `if (recorder.forceFinish()) metrics.turnStalled()`.
  - Property `ai-insights.capture.stall-timeout` (`Duration`, default `30s`) → `TurnTimeouts` bean
    (`destroyMethod = "close"`).
  - `ScriptedChatModel.neverStop()` — the stream observation is never stopped.

- [ ] **Step 1: Write the failing tests**
```java
// TurnRecorderTest
@Test void forceFinishRunsPendingFinishOnce() {
    // beginModelCall(); finishWhenIdle(SUCCESS, …, sink) returns true, sink not called;
    // forceFinish() == true, sink called once; endModelCall() → sink still called once; forceFinish() == false
}
@Test void finishWhenIdleReturnsFalseWhenNothingOpen() { /* returns false, sink called once */ }

// InsightsAdvisorTest (advisor built with new TurnTimeouts(Duration.ofMillis(200)))
@Test void streamWhoseModelNeverReportsIsFinishedAfterStallTimeout() {
    // ScriptedChatModel …neverStop(); stream "hi" to completion
    // → turn recorded within 2 s, outcome SUCCESS; meter ai.insights.turns.stalled == 1
}
```
- [ ] **Step 2:** Run `./gradlew :spring-ai-insights-core:test --tests '*TurnRecorderTest' --tests '*InsightsAdvisorTest'` → FAIL (compile).
- [ ] **Step 3:** Implement. Update every `new InsightsAdvisor(...)` call site and the auto-configuration.
- [ ] **Step 4:** Run `./gradlew build` → PASS.
- [ ] **Step 5:** Commit `feat(core): finish and count stalled turns`.

---

### Task 2: Analytics SPI, in-memory implementation, shared contract

**Files:**
- Create: `core/.../analytics/*.java` (see file structure), `core/src/testFixtures/.../analytics/{InsightsAnalyticsContract,ContractTurns}.java`
- Modify: `core/build.gradle.kts` (apply `java-test-fixtures`; `testFixturesApi` junit-jupiter-api, assertj-core),
  `core/.../store/InMemoryEventStore.java`
- Test: `core/src/test/.../store/InMemoryAnalyticsContractTest.java`

**Interfaces:**
- Produces (package `io.github.springaiinsights.analytics`; all records, lists immutable):
```java
public record InsightsFilter(@Nullable String application, Instant from, Instant to,
        @Nullable String tenantId, ZoneId zone) { /* from < to, else IllegalArgumentException; application null = all */ }
public enum Granularity { HOUR, DAY }
public record UsageSummary(long turns, long conversations, long activeUsers, long anonymousTurns,
        long errorTurns, long cancelledTurns, long inputTokens, long outputTokens, long cacheReadTokens,
        long cacheWriteTokens, BigDecimal cost, long unpricedTurns, double avgLatencyMs,
        @Nullable Double avgTimeToFirstTokenMs, long toolCalls, long retrievals, long emptyRetrievals) {}
public record TimeBucket(Instant start, long turns, long conversations, long activeUsers, BigDecimal cost) {}
public record ModelUsage(String model, long calls, long inputTokens, long outputTokens,
        long cacheReadTokens, long cacheWriteTokens, BigDecimal cost, long unpricedCalls) {}
public record ToolUsage(String tool, long calls, long errors, long turns, double avgDurationMs, long repeatedCalls) {}
public record RetrievalUsage(String store, long queries, long emptyQueries, double avgResultCount,
        @Nullable Double avgTopScore) {}
public record UserUsage(String userId, long turns, long conversations, BigDecimal cost, Instant lastSeen) {}
public record TenantUsage(String tenantId, long turns, long activeUsers, BigDecimal cost) {}

public interface InsightsAnalytics {
    UsageSummary summary(InsightsFilter filter);
    List<TimeBucket> timeline(InsightsFilter filter, Granularity granularity); // non-empty buckets, ascending
    List<ModelUsage> models(InsightsFilter filter);          // calls desc, model asc
    List<ToolUsage> tools(InsightsFilter filter);            // calls desc, tool asc
    List<RetrievalUsage> retrievals(InsightsFilter filter);  // queries desc, store asc
    List<UserUsage> topUsers(InsightsFilter filter, int limit); // turns desc, userId asc; null users excluded
    List<TenantUsage> tenants(InsightsFilter filter);        // turns desc, tenantId asc; null tenants excluded
    List<ConversationTurn> conversation(String application, String conversationId); // startedAt asc, max 500
    List<String> applications();                             // sorted
}
```
Definitions every implementation follows: `conversations` = distinct conversation IDs;
`activeUsers` = distinct non-null user IDs; `cost` = sum of non-null `ModelCall.cost` (zero when
none, never null); `unpricedTurns` = turns with at least one model call whose cost is null;
`unpricedCalls` likewise per model; `avgLatencyMs` = 0 when no turns; `avgTimeToFirstTokenMs` =
mean of non-null values, null when none; `emptyRetrievals`/`emptyQueries` = `resultCount == 0`;
`repeatedCalls` = tool calls with `repeatIndex > 0`; `ToolUsage.turns` = distinct turns using the
tool; `avgTopScore` = mean over queries that have scores of the max score, null when none;
`TimeBucket.start` = start of the hour/day in the filter zone, as an `Instant`.
- `InMemoryEventStore implements InsightsEventStore, InsightsAnalytics` (stream computations over the held turns).
- Test fixture: `abstract class InsightsAnalyticsContract { protected abstract InsightsEventStore store(); protected abstract InsightsAnalytics analytics(); }`
  — `@BeforeEach` saves `ContractTurns.all()` into `store()`; subclasses give a fresh, empty store per test.

**Dataset `ContractTurns`** (all instants millisecond precision; models `m1` priced, `m2` unpriced (cost null)):

| id | app | startedAt (UTC) | conv | user | tenant | outcome | latency | ttft | model calls (model in/out/cacheRead cost) | tools (name ms outcome repeat) | retrievals (store topK results scores) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| T1 | shop | 2026-10-01T08:00:00.123Z | c1 | u1 | acme | SUCCESS | 1000 | 200 | m1 100/50/10 0.0002 | lookup 30 SUCCESS 0; lookup 50 SUCCESS 1 | kb 4 2 [0.9,0.5] |
| T2 | shop | 2026-10-01T09:00:00Z | c1 | u1 | acme | SUCCESS | 3000 | – | m1 200/100/0 0.0004 | refund 10 ERROR 0 (`IllegalStateException`) | kb 4 0 [] |
| T3 | shop | 2026-10-01T23:30:00Z | c2 | u2 | globex | ERROR | 500 | – | m2 50/0/0 null | – | – |
| T4 | shop | 2026-10-02T10:00:00Z | c3 | – | – | CANCELLED | 1500 | 400 | m1 10/5/0 0.00002 | – | – |
| T5 | shop | 2026-10-02T11:00:00Z | c4 | u3 | acme | SUCCESS | 2000 | – | m1 300/200/0 0.0007; m2 20/10/0 null | – | – |
| T6 | crm | 2026-10-01T12:00:00Z | c9 | u1 | acme | SUCCESS | 100 | – | m1 1/1/0 0.000003 | – | – |
| T7 | shop | 2026-09-30T21:59:59.999Z | c6 | u1 | acme | SUCCESS | 100 | – | m1 1/1/0 0.000003 | – | – |
| T8 | shop | 2026-09-30T22:00:00Z | c5 | u2 | globex | SUCCESS | 1000 | – | m1 0/0/0 0 | – | – |

`ContractTurns.SHOP` = filter `("shop", 2026-09-30T22:00Z, 2026-10-02T22:00Z, null, Europe/Paris)`.
Build turns with `TestTurns` (Plan 1); `totalCost` follows Plan 1's rule (null if any call unpriced).

- [ ] **Step 1: Write the failing contract tests** (in `InsightsAnalyticsContract`)
```java
@Test void summaryCountsEverything() {
    // summary(SHOP): turns 6, conversations 5, activeUsers 3, anonymousTurns 1, errorTurns 1, cancelledTurns 1,
    // inputTokens 680, outputTokens 365, cacheReadTokens 10, cacheWriteTokens 0, cost 0.00132, unpricedTurns 2,
    // avgLatencyMs 1500.0, avgTimeToFirstTokenMs 300.0, toolCalls 3, retrievals 2, emptyRetrievals 1
}
@Test void rangeIsHalfOpen()            { /* filter shop [T7.startedAt, T8.startedAt) → summary.turns == 1 (T7 in, T8 out); SHOP excludes T7 (turns == 6) */ }
@Test void tenantFilter()               { /* SHOP with tenant acme → turns 3, activeUsers 2, cost 0.0013 */ }
@Test void allApplicationsWhenNull()    { /* application null, same range → turns 7 */ }
@Test void emptyRangeGivesZeros()       { /* 2020 range → turns 0, cost 0, avgLatencyMs 0.0, avgTimeToFirstTokenMs null; lists empty */ }
@Test void timelineBucketsByDayInZone() {
    // DAY → [ (2026-09-30T22:00Z, turns 3, conversations 2, activeUsers 2, cost 0.0006),
    //         (2026-10-01T22:00Z, turns 3, conversations 3, activeUsers 2, cost 0.00072) ]
}
@Test void timelineByHour()             { /* HOUR → 6 buckets, first start 2026-09-30T22:00Z, ascending */ }
@Test void modelsSplitPricedAndUnpriced() {
    // [ m1: calls 5, in 610, out 355, cacheRead 10, cost 0.00132, unpricedCalls 0 ;
    //   m2: calls 2, in 70, out 10, cost 0, unpricedCalls 2 ]
}
@Test void toolsMostUsedFirst()         { /* [ lookup: calls 2, errors 0, turns 1, avgDurationMs 40.0, repeatedCalls 1 ;
                                             refund: calls 1, errors 1, turns 1, avgDurationMs 10.0, repeatedCalls 0 ] */ }
@Test void retrievalsByStore()          { /* [ kb: queries 2, emptyQueries 1, avgResultCount 1.0, avgTopScore 0.9 ] */ }
@Test void topUsers()                   { /* limit 10 → u1 (2 turns, 1 conv, 0.0006, lastSeen T2), u2 (2, 2, 0, lastSeen T3), u3 (1, 1, 0.0007);
                                             limit 2 → [u1, u2] */ }
@Test void tenants()                    { /* [ acme: turns 3, activeUsers 2, cost 0.0013 ; globex: turns 2, activeUsers 1, cost 0 ] */ }
@Test void conversationInOrder()        { /* conversation("shop","c1") → [T1, T2] equal to the saved turns; ("crm","c1") → [] */ }
@Test void applicationsSorted()         { /* ["crm", "shop"] */ }
@Test void storedTurnRoundTrips()       { /* findTurn("T1") equals ContractTurns T1 (records equal; BigDecimals compared with compareTo via a helper) */ }
```
Compare `BigDecimal`s with `isEqualByComparingTo`, doubles with `isCloseTo(x, within(1e-9))`.
- [ ] **Step 2:** `InMemoryAnalyticsContractTest extends InsightsAnalyticsContract` with `new InMemoryEventStore(1000)`; run → FAIL.
- [ ] **Step 3:** Implement the records, interface and `InMemoryEventStore` methods.
- [ ] **Step 4:** Run `./gradlew :spring-ai-insights-core:test` → PASS.
- [ ] **Step 5:** Commit `feat(core): analytics queries with in-memory implementation`.

---

### Task 3: MongoDB module — mapping, writes, indexes

**Files:**
- Create: `spring-ai-insights-mongodb/build.gradle.kts`, `…/mongodb/{MongoEventStore,TurnDocuments,MongoIndexes}.java`
- Modify: `settings.gradle.kts` (include the module)
- Test: `…/mongodb/MongoEventStoreTest.java`

**Interfaces:**
- Consumes: `InsightsEventStore`, `TurnQuery`, `ConversationTurn` (Plan 1).
- Produces:
  - `build.gradle.kts`: `api(project(":spring-ai-insights-core"))`, `compileOnly("org.springframework.data:spring-data-mongodb")`;
    tests: `spring-data-mongodb`, `org.testcontainers:testcontainers-junit-jupiter`, `org.testcontainers:testcontainers-mongodb`, `testFixtures(project(":spring-ai-insights-core"))`.
  - `public final class MongoEventStore implements InsightsEventStore, InsightsAnalytics` —
    `MongoEventStore(MongoOperations mongo, String collectionPrefix, Duration retention)`; collection
    `collectionPrefix + "turns"`; `public void ensureIndexes()`.
  - `final class TurnDocuments` — `static Document toDocument(ConversationTurn)`, `static ConversationTurn fromDocument(Document)`.
    Schema: `_id`=turnId, `schemaVersion`=1, `startedAt` as BSON date, costs as `Decimal128` (null kept as null),
    `outcome`/`ToolOutcome` as enum names, `attributes` as an array of `{key, value}` (keys may contain `.` or `$`),
    `userMessage`/`assistantMessage` omitted when null. Every `ConversationTurn` field is mapped.
  - Indexes (`MongoIndexes.ensure(MongoCollection<Document>, Duration retention)`):
    `{application:1, startedAt:-1}`, `{application:1, conversationId:1, startedAt:1}`,
    `{application:1, tenantId:1, startedAt:-1}`, TTL `{startedAt:1}` named `ttl_startedAt` with
    `expireAfterSeconds = retention.toSeconds()`. On `MongoCommandException` code 85/86
    (index options conflict) for the TTL index, run `collMod` with the new `expireAfterSeconds`.
  - `save` = `replaceOne({_id}, doc, upsert)`. Before the first successful save, `save` calls
    `ensureIndexes()`; on failure it rethrows (the writer counts it) and retries no sooner than 60 s later.
    `retention` must be ≥ 1 minute (constructor `IllegalArgumentException`).

- [ ] **Step 1: Write the failing tests** (`@Testcontainers`, `static MongoDBContainer mongo = new MongoDBContainer("mongo:5.0")`, fresh database per test)
```java
@Test void everyFieldRoundTrips()        { /* a turn with all fields set (content, attributes "a.b"="1", "$x"="2", errorType, ttft) → findTurn equals it */ }
@Test void nullsRoundTrip()              { /* userId, tenantId, ttft, totalCost, content, cost of a call all null → equal after reload */ }
@Test void costIsStoredAsDecimal128()    { /* raw document: totalCost and modelCalls[0].cost are Decimal128 */ }
@Test void saveIsIdempotent()            { /* save T1 twice with a changed latency → 1 document, latest latency */ }
@Test void findTurnsFiltersAndSorts()    { /* application, [from,to), tenant, newest first, limit */ }
@Test void indexesCreatedOnFirstSave()   { /* listIndexes has the 3 compound indexes and ttl_startedAt with expireAfterSeconds 7776000 */ }
@Test void retentionChangeUpdatesTtlIndex() { /* store(90d).save; new store(30d).save → ttl_startedAt expireAfterSeconds 2592000, no exception */ }
@Test void saveFailsWhenMongoUnreachable() { /* MongoTemplate on mongodb://localhost:1/?serverSelectionTimeoutMS=300 → save throws, no hang beyond 2 s */ }
```
- [ ] **Step 2:** Run `./gradlew :spring-ai-insights-mongodb:test` → FAIL.
- [ ] **Step 3:** Implement (analytics methods throw `UnsupportedOperationException` until Task 4).
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(mongodb): MongoDB event store`.

---

### Task 4: MongoDB analytics via aggregation

**Files:**
- Modify: `…/mongodb/MongoEventStore.java`
- Test: `…/mongodb/MongoAnalyticsContractTest.java`

**Interfaces:**
- Consumes: `InsightsAnalytics` and the contract (Task 2).
- Produces: every `InsightsAnalytics` method answered by one aggregation pipeline on the turns
  collection (driver `Aggregates`/`Accumulators`, `allowDiskUse(true)`); buckets with
  `$dateTrunc {unit, timezone: zone.getId()}`; `$match` first on `application`/`startedAt`/`tenantId`
  so the indexes are used. `conversation` and `applications` use `find`/`distinct`.

- [ ] **Step 1:** `MongoAnalyticsContractTest extends InsightsAnalyticsContract` on `mongo:5.0` (fresh database per test).
- [ ] **Step 2:** Run `./gradlew :spring-ai-insights-mongodb:test` → FAIL (`UnsupportedOperationException`).
- [ ] **Step 3:** Implement the pipelines.
- [ ] **Step 4:** Run → PASS; also run once with `mongo:7.0` (temporarily change the image) → PASS; restore 5.0.
- [ ] **Step 5:** Commit `feat(mongodb): analytics aggregations`.

---

### Task 5: Store selection, retention and fail-fast secret

**Files:**
- Create: `autoconfigure/.../{InsightsMongoAutoConfiguration,MongoStoreCondition}.java`, test `InsightsMongoEndToEndTest.java`
- Modify: `autoconfigure/.../{InsightsProperties,InsightsAutoConfiguration}.java`, `AutoConfiguration.imports`,
  `autoconfigure/build.gradle.kts` (`compileOnly` mongodb module + `spring-data-mongodb`; tests:
  `spring-boot-data-mongodb`, `spring-boot-testcontainers`, `testcontainers-mongodb`, `testcontainers-junit-jupiter`),
  `starter/build.gradle.kts` (`api(project(":spring-ai-insights-mongodb"))`), `InsightsAutoConfigurationTest.java`

**Interfaces:**
- Properties: `StoreType { AUTO, MEMORY, MONGODB }`; `retention: Duration = 90d`;
  `mongodb.collectionPrefix = "ai_insights_"`.
- `InsightsMongoAutoConfiguration`: `@AutoConfiguration(after = DataMongoAutoConfiguration.class, before = InsightsAutoConfiguration.class)`,
  `@ConditionalOnClass(name = {"org.springframework.data.mongodb.core.MongoTemplate", "io.github.springaiinsights.mongodb.MongoEventStore"})`,
  `@ConditionalOnBooleanProperty("ai-insights.enabled", matchIfMissing)`, `@Conditional(MongoStoreCondition.class)`
  (store absent, `auto` or `mongodb`), `@ConditionalOnBean(MongoTemplate.class)`; bean
  `@ConditionalOnMissingBean(InsightsEventStore.class) MongoEventStore insightsEventStore(MongoTemplate, InsightsProperties)`.
- `InsightsAutoConfiguration` changes:
  - in-memory bean method returns `InMemoryEventStore`; if `store == MONGODB` it throws
    `IllegalStateException("ai-insights.store=mongodb requires Spring Data MongoDB and a MongoTemplate bean")`.
  - `Pseudonymiser` bean takes the `InsightsEventStore`; no secret + store not `InMemoryEventStore` →
    `IllegalStateException` with the Global Constraints text; in-memory keeps the Plan 1 warning.
  - `InsightsAnalytics` is the store bean itself (both stores implement it); no separate bean.

- [ ] **Step 1: Write the failing tests**
```java
// InsightsAutoConfigurationTest (ApplicationContextRunner)
@Test void mongoStoreChosenWhenMongoTemplatePresent()  { /* withBean(MongoTemplate mock-free: new MongoTemplate(MongoClients.create("mongodb://localhost:1"), "db")) + secret → MongoEventStore, getBean(InsightsAnalytics) is it */ }
@Test void memoryStoreWhenForced()                     { /* store=memory + MongoTemplate → InMemoryEventStore */ }
@Test void mongodbForcedWithoutMongoFails()            { /* store=mongodb, no MongoTemplate → failure message above */ }
@Test void memoryWhenSpringDataMongoAbsent()           { /* FilteredClassLoader(MongoTemplate.class) → InMemoryEventStore */ }
@Test void persistentStoreWithoutSecretFails()         { /* MongoTemplate, no secret → failure with the exact text */ }
@Test void customStoreWithoutSecretFails()             { /* user InsightsEventStore bean, no secret → same failure; userBeansWin now sets a secret */ }
@Test void retentionAndPrefixBind()                    { /* retention=30d, mongodb.collection-prefix=x_ → store writes to x_turns (assert via store getter or collection name) */ }

// InsightsMongoEndToEndTest (@SpringBootTest, @Testcontainers, @ServiceConnection MongoDBContainer("mongo:5.0"), ScriptedChatModel, secret set)
@Test void chatTurnLandsInMongo()          { /* call → turn readable from MongoEventStore.findTurn; summary(today).turns == 1 */ }
@Test void streamTurnLandsInMongo()        { /* stream → turn with streaming=true in Mongo */ }
// separate @SpringBootTest class or nested with spring.mongodb.uri=mongodb://localhost:1/x?serverSelectionTimeoutMS=300:
@Test void chatWorksWhenMongoIsUnreachable() { /* context starts; call returns "answer" within 2 s; ai.insights.store.errors >= 1 eventually */ }
```
- [ ] **Step 2:** Run `./gradlew :spring-ai-insights-autoconfigure:test` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `./gradlew build` → PASS. Check the starter's runtime classpath has no `spring-data-mongodb`:
  `./gradlew :spring-ai-insights-starter:dependencies --configuration runtimeClasspath | grep spring-data-mongodb` → no output.
- [ ] **Step 5:** Commit `feat: MongoDB store auto-configuration`.

---

### Task 6: README

**Files:** Modify `README.md`.

- [ ] **Step 1:** Add: "Storage" section (auto-selection rules, MongoDB ≥ 5.0, collection name, indexes,
  retention/TTL, multi-instance needs MongoDB, the secret becomes mandatory with MongoDB), the analytics API
  (`InsightsAnalytics` with one example: top tools last 7 days), the stalled-turn metric, the three new
  properties in the configuration table, and move MongoDB from the roadmap to "done".
- [ ] **Step 2:** Verify every `ai-insights.*` key in the README is in the generated configuration metadata; `./gradlew build` → PASS.
- [ ] **Step 3:** Commit `docs: storage and analytics`.
