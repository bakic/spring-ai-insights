# spring-ai-insights — Plan 3: Feedback and built-in dashboard

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Users rate answers (thumbs up/down + comment, only on their own turns), and the people
around the assistant open `/ai-insights` in the app itself to see usage, cost, knowledge base,
tools, quality and single conversations — with no Node, no extra server and no CDN.

**Architecture:** Feedback is a core concept (`Feedback`, `FeedbackStore` SPI, `InsightsFeedback`
service) stored by both bundled stores and covered by the shared analytics contract. A new module
`spring-ai-insights-web` holds the HTTP layer as Spring MVC functional routes: the feedback
endpoint, a JSON page API composed by `DashboardPages`, and a static vanilla-JS UI with SVG charts
served from the jar. Access to the dashboard is checked per request against the **app's own**
authentication (`HttpServletRequest.isUserInRole`), so it reuses whatever login the app already has.

**Tech Stack:** Java 21, Spring Boot 4.1.1 (Spring MVC functional endpoints, Jackson 3.1),
Spring Security 7.1 (tests only), Spring Data MongoDB, Testcontainers, JUnit 5, AssertJ,
plain HTML/CSS/JS (ES2020, no build step), headless Chrome for visual checks.

**Spec:** `../../design.md` (sections 3, 5, 8.2, 8.5, 8.6, 8.7, 8.8, 9, 13). Plans 1–2 are merged.

**Plan series:** 1 Core capture (done) · 2 Analytics + MongoDB (done) · 3 Feedback + dashboard
(this) · 4 Weekly report, catalogs, OTel naming, Grafana assets, demo app, Maven Central release.

## Global Constraints

- All Plan 1 and Plan 2 Global Constraints still apply (Java 21, Boot 4.1.1, Spring AI 2.0.1,
  prefix `ai-insights`, never break or slow the chat, no IDs as metric tags, records, no Lombok,
  half-open ranges, costs = sum of priced calls).
- The starter must not add a web stack: `spring-webmvc` and the Servlet API are `compileOnly` in
  `spring-ai-insights-web`. Verify `./gradlew :spring-ai-insights-starter:dependencies --configuration runtimeClasspath | grep -E "spring-webmvc|spring-security"` prints nothing.
- Dashboard and feedback HTTP endpoints exist only in **servlet** web applications
  (`@ConditionalOnWebApplication(type = SERVLET)`). WebFlux apps use the `InsightsFeedback` bean
  and the `InsightsAnalytics` API directly (README says so).
- New properties and defaults: `ai-insights.dashboard.enabled` = `true`,
  `ai-insights.dashboard.path` = `/ai-insights`, `ai-insights.dashboard.role` = `AI_INSIGHTS`,
  `ai-insights.dashboard.allow-anonymous` = `false`, `ai-insights.feedback.enabled` = `true`.
- Feedback endpoint: `POST {dashboard.path}/feedback`. Body `{"turnId": "...", "rating": "up"|"down", "comment": "..."}`
  (rating case-insensitive, comment optional, at most **1000** characters after trimming; blank → none).
  Responses: `204` stored · `400` invalid body · `401` no authenticated principal · `404` turn
  unknown **or not the caller's** (same body, so a turn's existence never leaks) · `503` store failure.
- One rating per turn: a new rating replaces the previous one (same turn ID = same record).
- Metric `ai.insights.feedback` (counter), tags `application`, `rating` (`up` | `down`).
- The UI loads nothing from outside the jar (no CDN, no web fonts) and renders every data value
  with `textContent` / `setAttribute`, never `innerHTML`.
- Dashboard responses carry `Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`,
  `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`; API responses also
  `Cache-Control: no-store`.
- Dashboard query parameters: `app` (default: this application's name), `from`, `to` (ISO-8601
  instants; default `to` = now, `from` = `to` − 7 days), `tenant` (optional), `zone` (IANA ID,
  default `UTC`). Invalid values, `from >= to`, or a range over **366 days** → `400` with
  `{"error": "<message>"}` — never a `500`.
- Money: amounts computed by the dashboard (per conversation, projection) use scale 6, `HALF_UP`.

## Spec deviation (for the author to confirm)

§8.7 says the starter contributes its own `SecurityFilterChain` for the dashboard. A spike on
Boot 4.1.1 / Security 7.1 showed two problems with that: a separate chain cannot reuse the app's
authentication (JWT resource server, OAuth2 login, form login, and its login redirect all live in
the app's chain), and declaring any `SecurityFilterChain` bean switches off Boot's default chain
for apps that rely on it. This plan therefore **checks access inside the dashboard routes**:
`allow-anonymous || request.isUserInRole(role)`, otherwise `401` (no principal) or `403`. The
spike confirmed that `isUserInRole` and `principal()` see the app's authentication, and that an
anonymous browser gets the app's own login redirect. The check fails closed: if no security
chain processed the request, there is no role, so access is denied. The dashboard is still
disabled without Spring Security unless `allow-anonymous=true`, as §8.7 requires. If the author
prefers the separate chain, only Task 5's access filter and its tests change.

## Not in this plan (explicitly deferred)

- "Tools never called" and "documents never retrieved": these need the catalog SPIs of Plan 4. The dashboard shows "least used" (lists are sorted).
- Tool latency p95: MongoDB 5.0 has no `$percentile`. Plan 4 decides between computing it in Java and raising the floor to 7.0.
- Abandoned conversations: the spec does not define "abandoned" precisely (inactivity after which outcome?). Plan 4 defines it with the author.
- Trace links on the conversation page: turns don't record a trace ID yet; that comes with OTel naming in Plan 4.

## Review Focus

1. **Feedback for someone else's turn, or a guessed turn ID** → `404`, identical to an unknown
   turn; nothing is stored. *(Task 1 `othersTurnIsNotFound`, Task 4 `othersTurnGives404`)*
2. **Thumbs clicked right after the answer, before the async writer saved the turn** → accepted.
   *(Task 1 `turnStillInWriterQueueAcceptsFeedback`, Task 4 `feedbackRightAfterChat`)*
3. **App with Spring Security and Boot's default chain adds the starter** → its other endpoints
   stay protected; anonymous dashboard access gets the app's login; a user without the role gets
   `403`. *(Task 5 `defaultChainStillProtectsApp`, `userWithoutRoleIsForbidden`)*
4. **HTML in a comment, user ID or tool name** (`<img src=x onerror=alert(1)>`) → shown as text.
   *(Task 6 `scriptsNeverUseInnerHtml`, `cspHeaderOnEveryDashboardResponse`)*
5. **Month-end projection on the 1st of the month and in a DST month** → `null` until a full day
   has elapsed; exact elapsed seconds otherwise (October in Paris lasts 31 days + 1 hour).
   *(Task 5 `projectionUsesExactMonthLength`, `noProjectionInFirstDay`)*

---

## File structure

```
spring-ai-insights-core/src/main/java/io/github/springaiinsights/
  feedback/{Rating,Feedback,FeedbackStore,InsightsFeedback}.java          (new, Task 1)
  store/AsyncEventWriter.java              (pending(turnId), Task 1)
  store/InMemoryEventStore.java            (implements FeedbackStore, Tasks 1-2)
  metrics/InsightsMetrics.java             (feedback(rating), Task 1)
  analytics/{DocumentUsage,KeyCount,QualitySummary}.java                   (new, Task 2)
  analytics/{InsightsAnalytics,UsageSummary}.java                          (extended, Task 2)
spring-ai-insights-core/src/testFixtures/…/analytics/{ContractTurns,InsightsAnalyticsContract}.java (Task 2)
spring-ai-insights-mongodb/…/{MongoEventStore,FeedbackDocuments,MongoIndexes}.java (Task 3)
spring-ai-insights-web/                                                   (new module, Tasks 4-6)
  src/main/java/io/github/springaiinsights/web/
    FeedbackRoutes.java, ErrorBody.java                                    (Task 4)
    DashboardQuery.java, DashboardPages.java, DashboardRoutes.java, pages/*.java (Task 5)
  src/main/resources/ai-insights-dashboard/{index.html,app.js,app.css}     (Task 6)
spring-ai-insights-autoconfigure/…/{InsightsFeedbackAutoConfiguration,InsightsWebAutoConfiguration,DashboardAccessCondition}.java
spring-ai-insights-starter/build.gradle.kts                                (adds web module, Task 4)
README.md                                                                   (Task 7)
```

---

### Task 1: Feedback in core

**Files:**
- Create: `spring-ai-insights-core/src/main/java/io/github/springaiinsights/feedback/{Rating,Feedback,FeedbackStore,InsightsFeedback}.java`
- Modify: `store/AsyncEventWriter.java`, `store/InMemoryEventStore.java`, `metrics/InsightsMetrics.java`
- Test: `spring-ai-insights-core/src/test/java/io/github/springaiinsights/feedback/InsightsFeedbackTest.java`, `store/AsyncEventWriterTest.java`

**Interfaces:**
- Produces:
  - `enum Rating { UP, DOWN }`
  - `record Feedback(String turnId, String application, String conversationId, @Nullable String userId, @Nullable String tenantId, Rating rating, @Nullable String comment, Instant createdAt)`
  - `interface FeedbackStore { void saveFeedback(Feedback feedback); }`: a save with an existing `turnId` replaces it.
  - `final class InsightsFeedback`:
    - `static final int MAX_COMMENT_LENGTH = 1000`
    - `InsightsFeedback(InsightsEventStore turns, FeedbackStore store, AsyncEventWriter writer, @Nullable Pseudonymiser pseudonymiser, InsightsMetrics metrics, Clock clock)`
    - `boolean submit(String turnId, @Nullable String userId, Rating rating, @Nullable String comment)`: `userId` is the **raw** user ID, the same value the identity resolver saw. It returns `false` when the turn is unknown or not owned by that user. It throws `IllegalArgumentException` for a comment that is too long. Store exceptions propagate.
  - `Optional<ConversationTurn> AsyncEventWriter.pending(String turnId)`: a turn that was accepted but not yet saved.
  - `void InsightsMetrics.feedback(Rating rating)`
  - `InMemoryEventStore implements FeedbackStore`: feedback is kept in insertion order and bounded by `maxTurns`, oldest first out.

- [ ] **Step 1: Write the failing tests** in `InsightsFeedbackTest`. Use a stored turn `T` (user `"alice"` pseudonymised with secret `"k"`, application `"shop"`, conversation `"c1"`, tenant `"acme"`) and `Clock.fixed(2026-10-01T10:00:00Z)`.
  - `ownTurnIsRated`: `submit(T, "alice", UP, " great ")` returns `true`. The stored `Feedback` has the same turnId, application, conversationId, userId (pseudonym), tenant and createdAt, and comment `"great"`. The counter `ai.insights.feedback{rating=up}` is 1.
  - `othersTurnIsNotFound`: `submit(T, "bob", UP, null)` returns `false`, nothing is stored and the counter is not created.
  - `unknownTurnIsNotFound`: `submit("nope", "alice", UP, null)` returns `false`.
  - `anonymousTurnAcceptsAnonymousCaller`: a turn with `userId == null` accepts `submit(id, null, DOWN, null)`, and rejects `"alice"`.
  - `newRatingReplacesOld`: UP then DOWN on the same turn leaves one record, rated `DOWN`.
  - `commentLimits`: a blank comment is stored as `null`. A comment of 1000 chars is accepted. 1001 chars throws `IllegalArgumentException`.
  - `rawIdsWhenPseudonymiserNull`: with no pseudonymiser, the turn's raw `"alice"` matches `"alice"`.
  - `turnStillInWriterQueueAcceptsFeedback`: the turn is not in the store but `writer.pending(id)` returns it, so `submit` returns `true`.
- [ ] **Step 2: Add `AsyncEventWriterTest.pendingUntilSaved`.** The store blocks on a latch, so `pending(id)` is present while the save is in progress. It is empty after the save, and also after a failed save.
- [ ] **Step 3: Run `./gradlew :spring-ai-insights-core:test`** and confirm it fails to compile (RED).
- [ ] **Step 4: Implement.**
  - `pending`: a `ConcurrentHashMap<String, ConversationTurn>`. Put the turn before `queue.offer`, remove it if the offer fails, and remove it in `save`'s `finally`.
  - Ownership: `expected = (pseudonymiser == null || userId == null) ? userId : pseudonymiser.pseudonymise(userId)`, compared with `Objects.equals(turn.userId(), expected)`.
  - Increment the metric only after a successful save.
- [ ] **Step 5: Run the core tests** and confirm they all pass.
- [ ] **Step 6: Commit** `feat(core): user feedback on turns`.

### Task 2: Analytics for the dashboard (in memory + contract)

**Files:**
- Create: `analytics/{DocumentUsage,KeyCount,QualitySummary}.java`
- Modify: `analytics/InsightsAnalytics.java`, `analytics/UsageSummary.java`, `store/InMemoryEventStore.java`, the mongodb module (stubs only, so the build compiles: throw `UnsupportedOperationException`, replaced in Task 3), `testFixtures/…/ContractTurns.java`, `InsightsAnalyticsContract.java`

**Interfaces:**
- Produces:
  - `UsageSummary` gains a **last** component, `long retrievalTurns`: turns with at least one retrieval, empty ones included.
  - `record DocumentUsage(String store, String documentId, long hits)`: `hits` counts occurrences in retrieval results.
  - `record KeyCount(String key, long count)`
  - `record QualitySummary(long positiveFeedback, long negativeFeedback, List<KeyCount> finishReasons, List<KeyCount> errorTypes)`
  - New `InsightsAnalytics` methods:
    - `long newUsers(InsightsFilter f)`: users active in the range whose first turn ever (same application and tenant filter) is at or after `from`.
    - `List<DocumentUsage> documents(InsightsFilter f, int limit)`: most hits first, then by store, then by documentId.
    - `QualitySummary quality(InsightsFilter f)`:
      - Feedback is filtered by `createdAt`, application and tenant.
      - `finishReasons` counts **turns** per distinct reason.
      - `errorTypes` counts turns with a non-null `errorType`.
      - Both lists are sorted by count descending, then by key.
    - `List<Feedback> recentFeedback(InsightsFilter f, @Nullable Rating rating, int limit)`: newest `createdAt` first, then by turnId.
    - `List<Feedback> conversationFeedback(String application, String conversationId)`: oldest first.
- Contract dataset additions in `ContractTurns`. The application is `"help"`, the filter is `HELP = new InsightsFilter("help", FROM, TO, null, PARIS)`, and all turns are SUCCESS unless stated:

| Turn | startedAt (UTC) | conv | user | tenant | outcome / errorType | finishReasons | retrievals (store: docs) |
|---|---|---|---|---|---|---|---|
| H1 | 2026-10-01T10:00 | h1 | v1 | acme | SUCCESS | STOP | kb: d1,d2 · faq: q1 |
| H2 | 2026-10-01T11:00 | h1 | v1 | acme | SUCCESS | LENGTH, LENGTH | kb: d1 |
| H3 | 2026-10-02T09:00 | h2 | v2 | globex | ERROR TimeoutException | – | – |
| H4 | 2026-10-02T12:00 | h3 | – | – | ERROR TimeoutException | – | kb: (empty) |
| H5 | 2026-10-02T14:00 | h4 | v2 | globex | SUCCESS | STOP | – |

  Feedback (`ContractTurns.feedback()`):

| Feedback | turn | rating | comment | createdAt (UTC) |
|---|---|---|---|---|
| F1 | H1 | UP | – | 2026-10-01T10:05 |
| F2 | H2 | DOWN | `too long` | 2026-10-01T11:05 |
| F3 | H5 | UP | – | 2026-10-02T14:05 |
| F4 | H3 | DOWN | – | 2026-10-02T22:00 (= TO, excluded) |
| F5 | T1 (shop) | UP | – | 2026-10-01T08:05 |

  Each feedback copies application, conversation, user and tenant from its turn.

- [ ] **Step 1: Extend the contract.**
  - Add `protected abstract FeedbackStore feedbackStore();`.
  - `@BeforeEach` also saves `ContractTurns.help()` and `feedback()`.
  - Update `allApplicationsWhenNull` to **12** turns and `applicationsSorted` to `["crm", "help", "shop"]`.
  - `storedTurnRoundTrips` also covers `help()`.
  - Add these tests:
    - `retrievalTurns`: SHOP gives 2 (T1, T2) and HELP gives 3.
    - `newUsers`: SHOP gives **2** (u2 first seen at T8 = FROM, u3 new, u1 seen earlier at T7). HELP gives 2. The HELP filter with tenant `globex` gives 1.
    - `documentsMostHitFirst`: HELP with limit 10 gives `(kb,d1,2), (faq,q1,1), (kb,d2,1)`; limit 2 gives the first two.
    - `quality`: HELP gives positive 2, negative 1, finishReasons `[STOP 2, LENGTH 1]`, errorTypes `[TimeoutException 2]`. HELP with tenant `acme` gives positive 1, negative 1.
    - `recentFeedback`: HELP gives turnIds `[H5, H2, H1]`. With rating DOWN it gives `[H2]`. With limit 1 it gives `[H5]`.
    - `conversationFeedback`: `("help", "h1")` gives `[H1, H2]`.
- [ ] **Step 2: Run** `./gradlew :spring-ai-insights-core:test --tests '*InMemoryAnalyticsContractTest'` and confirm it is RED.
- [ ] **Step 3: Implement the methods in `InMemoryEventStore`.** Feedback is filtered and sorted as specified. `newUsers` scans every stored turn of the application and tenant before `to`.
- [ ] **Step 4: Run** the core tests and confirm they pass. Run `./gradlew build -x :spring-ai-insights-mongodb:test` and confirm it compiles.
- [ ] **Step 5: Commit** `feat(core): quality, documents and new-user analytics`.

### Task 3: MongoDB feedback and analytics

**Files:**
- Create: `spring-ai-insights-mongodb/src/main/java/io/github/springaiinsights/mongodb/FeedbackDocuments.java`
- Modify: `MongoEventStore.java` (`implements FeedbackStore`), `MongoIndexes.java`
- Test: `MongoEventStoreTest.java`, `MongoAnalyticsContractTest.java` (`feedbackStore()`), `MongoReadWriteUserTest.java`

**Interfaces:**
- Consumes: Task 1 `FeedbackStore` and `Feedback`; Task 2 analytics signatures.
- Produces:
  - Collection `{prefix}feedback`. Documents look like `{_id: turnId, application, conversationId, userId?, tenantId?, rating: "UP"|"DOWN", comment?, createdAt: Date, schemaVersion: 1}`.
  - Indexes `application_createdAt` (application asc, createdAt desc) and `application_conversationId` (application, conversationId). TTL index `ttl_createdAt` with the same `retention`. The collMod-then-drop/recreate fallback is shared with the turns TTL.
  - `String MongoEventStore.feedbackCollectionName()`

- [ ] **Step 1: Write the failing tests.**
  - Wire `feedbackStore()` in the contract test.
  - In `MongoEventStoreTest`, add:
    - `feedbackIsUpserted`: two saves of the same turnId leave one document holding the last rating.
    - `feedbackIndexesCreated`: the three index names exist after the first `saveFeedback`.
  - Extend `MongoReadWriteUserTest` so a retention change also updates `ttl_createdAt`.
- [ ] **Step 2: Run** `./gradlew :spring-ai-insights-mongodb:test` and confirm it is RED.
- [ ] **Step 3: Implement.**
  - `retrievalTurns`: add `$sum: {$cond: [{$gt: ["$_retrievals", 0]}, 1, 0]}` to the summary totals.
  - `newUsers`:
    - Match the application, the tenant and `userId != null`, with `startedAt < to`.
    - Group by `userId` with `$min: startedAt` and `$max: startedAt`.
    - Keep users with `max >= from` and `min >= from`, then `$count`.
  - `documents`: `$unwind` retrievals, then `$unwind` `retrievals.documentIds`, group by `{store, doc}`, sort, limit.
  - `quality`:
    - Feedback counts come from a grouped query on the feedback collection.
    - Turn counts come from a `$facet` with two branches:
      - finish reasons: `$setUnion: ["$finishReasons", []]`, then `$unwind`, then group;
      - error types: `errorType != null`, then group.
  - Feedback reads use `find` with sort and limit.
- [ ] **Step 4: Run** the module tests on `mongo:5.0` and on `-PmongoImage=mongo:7.0 --rerun-tasks`. All must pass on both.
- [ ] **Step 5: Commit** `feat(mongodb): feedback and dashboard analytics`.

### Task 4: Web module and feedback endpoint

**Files:**
- Create: `spring-ai-insights-web/build.gradle.kts`, `web/FeedbackRoutes.java`, `web/ErrorBody.java`
- Create: `autoconfigure/…/InsightsFeedbackAutoConfiguration.java`, `InsightsWebAutoConfiguration.java`
- Modify: `settings.gradle.kts`, `InsightsAutoConfiguration.java`, `InsightsMongoAutoConfiguration.java`, `InsightsProperties.java`, `AutoConfiguration.imports`, `autoconfigure/build.gradle.kts`, `starter/build.gradle.kts`
- Test: `spring-ai-insights-web/src/test/java/…/FeedbackRoutesTest.java`, `autoconfigure/…/InsightsWebEndToEndTest.java`, `InsightsAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `InsightsFeedback.submit` (Task 1).
- Produces:
  - `record ErrorBody(String error)`
  - `final class FeedbackRoutes`, with `static RouterFunction<ServerResponse> create(String path, InsightsFeedback feedback)`, which maps `POST {path}/feedback`. The user is `request.principal().map(Principal::getName)`.
  - The store bean methods return concrete types: `InMemoryEventStore` with `@ConditionalOnMissingBean(InsightsEventStore.class)`, and `MongoEventStore`. This lets `@ConditionalOnBean(FeedbackStore.class / InsightsAnalytics.class)` see the interfaces.
  - `InsightsFeedbackAutoConfiguration` runs after `InsightsAutoConfiguration` and `InsightsMongoAutoConfiguration`. It is `@ConditionalOnBean(FeedbackStore.class)` and declares the bean `InsightsFeedback insightsFeedback(...)` (`@ConditionalOnMissingBean`, `Clock.systemUTC()`).
  - `InsightsWebAutoConfiguration` runs after `InsightsFeedbackAutoConfiguration`. It is `@ConditionalOnWebApplication(type = SERVLET)` and `@ConditionalOnClass(name = {"org.springframework.web.servlet.function.RouterFunction", "io.github.springaiinsights.web.FeedbackRoutes"})`. It declares the bean `RouterFunction<ServerResponse> insightsFeedbackRoutes`, with `@ConditionalOnBean(InsightsFeedback.class)` and `@ConditionalOnBooleanProperty("ai-insights.feedback.enabled", matchIfMissing = true)`.
  - Properties `Dashboard { enabled, path, role, allowAnonymous }` and `Feedback { enabled }`. The path must match `^(/[A-Za-z0-9._~-]+)+$`; anything else fails startup with `ai-insights.dashboard.path must look like /ai-insights`.
- Module build:
  - `api(project(":spring-ai-insights-core"))`
  - `compileOnly("org.springframework:spring-webmvc")` and `compileOnly("jakarta.servlet:jakarta.servlet-api")`
  - Tests: `spring-boot-starter-webmvc-test` and `testFixtures(core)`.
  - The autoconfigure module gets `compileOnly(project(":spring-ai-insights-web"))`, `compileOnly("org.springframework:spring-webmvc")`, plus the test dependencies `spring-boot-starter-webmvc`, `spring-boot-starter-webmvc-test`, `spring-boot-starter-security` and `spring-boot-starter-security-test`. All of these were verified in a spike: the existing autoconfigure tests stay green with them on the classpath.

- [ ] **Step 1: Write the failing tests.**
  - `FeedbackRoutesTest`: `MockMvcBuilders.routerFunctions(...)`, with an in-memory store and writer, and a principal set with `.principal(() -> "alice")`.
    - `ratesOwnTurn`: 204, and the feedback is stored.
    - `othersTurnGives404`: Bob gets 404 with body `{"error":"turn not found"}`, the same body as for an unknown ID.
    - `noPrincipalGives401`
    - `badRatingGives400`: body `{"error":"rating must be up or down"}`.
    - `missingTurnIdGives400`
    - `malformedJsonGives400`
    - `tooLongCommentGives400`
    - `storeFailureGives503`: the store throws on `saveFeedback`.
  - `InsightsWebEndToEndTest`: `@SpringBootTest` + `@AutoConfigureMockMvc`, with Spring Security, an app chain of `anyRequest().authenticated()` plus httpBasic and CSRF disabled for the API, a `ScriptedChatModel`, and a controller `GET /chat` that returns the turn ID from `chatClientResponse().context()`.
    - `feedbackRightAfterChat`: calling `/chat` as `alice` and then immediately `POST /ai-insights/feedback` gives 204.
    - `bobCannotRateAlicesTurn`: 404.
  - In `InsightsAutoConfigurationTest`, add:
    - `feedbackBeanWithBundledStores`
    - `noFeedbackBeanForCustomStoreWithoutFeedbackStore`
    - `feedbackRoutesOffWhenDisabled`
    - `noRoutesOutsideServletApps`: use the plain `ApplicationContextRunner`.
    - `invalidDashboardPathFails`: `ai-insights` without a leading slash, `/`, and `/a/` all fail.
- [ ] **Step 2: Run** them and confirm they are RED.
- [ ] **Step 3: Implement.**
  - Map `IllegalArgumentException` and `HttpMessageNotReadableException` to 400, and any other `RuntimeException` from the store to 503 (log at WARN without the comment, count `ai.insights.store.errors`).
  - Add the starter dependency `api(project(":spring-ai-insights-web"))`.
- [ ] **Step 4: Run `./gradlew build`.** Run the starter runtimeClasspath check from Global Constraints and confirm it prints nothing.
- [ ] **Step 5: Commit** `feat(web): feedback endpoint`.

### Task 5: Dashboard JSON API

**Files:**
- Create: `web/DashboardQuery.java`, `web/DashboardPages.java`, `web/DashboardRoutes.java`, and `web/pages/{DashboardConfig,TurnRow,OverviewPage,UsagePage,CostPage,KnowledgePage,ToolsPage,QualityPage,ConversationPage}.java`
- Create: `autoconfigure/…/DashboardAccessCondition.java`
- Modify: `InsightsWebAutoConfiguration.java`
- Test: `spring-ai-insights-web/src/test/java/…/{DashboardQueryTest,DashboardPagesTest,DashboardRoutesTest}.java`, `autoconfigure/…/InsightsDashboardSecurityTest.java`

**Interfaces:**
- Consumes: the `InsightsAnalytics` methods from Plan 2 and Task 2, and `InsightsEventStore.findTurns`.
- Produces:
  - `record DashboardConfig(String application, List<String> applications, String currency, String store, boolean perInstance)`: `store` is `"memory"`, `"mongodb"`, or the store's simple class name. `perInstance` is `store instanceof InMemoryEventStore`.
  - `record TurnRow(String turnId, String conversationId, Instant startedAt, @Nullable String userId, @Nullable String tenantId, TurnOutcome outcome, long latencyMs, @Nullable BigDecimal cost, int toolCalls, int retrievals)`
  - `record OverviewPage(UsageSummary current, UsageSummary previous, List<TimeBucket> days, List<TurnRow> recentTurns)`:
    - `previous` covers `[from - (to - from), from)`.
    - `recentTurns` is the newest 50 from `findTurns`.
  - `record UsagePage(long dailyActiveUsers, long weeklyActiveUsers, long monthlyActiveUsers, long newUsers, double turnsPerUser, double turnsPerConversation, List<TimeBucket> days, long[] turnsByHourOfDay, List<UserUsage> topUsers)`:
    - The DAU/WAU/MAU windows are `[to - 1d|7d|30d, to)`.
    - `turnsPerUser = (turns - anonymousTurns) / activeUsers`, and `turnsPerConversation = turns / conversations`; both are 0 when the divisor is 0.
    - `turnsByHourOfDay` has 24 entries summed from `timeline(HOUR)`, keyed by the hour of `start` in the zone.
    - `topUsers` is limited to 20.
  - `record CostPage(String currency, BigDecimal total, @Nullable BigDecimal perConversation, BigDecimal monthToDate, @Nullable BigDecimal monthEndProjection, long unpricedTurns, List<TimeBucket> days, List<ModelUsage> models, List<TenantUsage> tenants, List<UserUsage> topUsers)`:
    - The month is the current month (from the clock) in the query's zone, with the application and tenant filters applied.
    - `projection = monthToDate × monthSeconds / elapsedSeconds`, or `null` when `elapsedSeconds < 86 400`.
    - `topUsers` is limited to 10.
  - `record KnowledgePage(long turns, long retrievalTurns, long retrievals, long emptyRetrievals, List<RetrievalUsage> stores, List<DocumentUsage> topDocuments)`: 20 documents.
  - `record ToolsPage(List<ToolUsage> tools)`
  - `record QualityPage(UsageSummary summary, QualitySummary quality, List<Feedback> recentNegative)`: 20 entries, rating DOWN.
  - `record ConversationPage(List<ConversationTurn> turns, List<Feedback> feedback)`
  - `final class DashboardQuery`:
    - `static InsightsFilter parse(ServerRequest request, String defaultApplication, Clock clock)`
    - It throws `IllegalArgumentException` with these messages: `invalid zone: X`, `invalid from: X`, `invalid to: X`, `from must be before to`, `range must not exceed 366 days`.
  - `final class DashboardPages`:
    - `DashboardPages(InsightsAnalytics analytics, InsightsEventStore store, String application, String currency, Clock clock)`
    - Methods `config()`, `overview(f)`, `usage(f)`, `cost(f)`, `knowledge(f)`, `tools(f)`, `quality(f)`, `conversation(String app, String id)`.
  - `final class DashboardRoutes`, with `static RouterFunction<ServerResponse> create(String path, String role, boolean allowAnonymous, DashboardPages pages, Clock clock)`:
    - `GET {path}/api/{config|overview|usage|cost|knowledge|tools|quality}` and `GET {path}/api/conversation?app=&id=` (`id` is required, otherwise 400).
    - It has an access filter and a header filter. The static routes are added in Task 6.
  - Bean `RouterFunction<ServerResponse> insightsDashboardRoutes`:
    - `@ConditionalOnBean(InsightsAnalytics.class)`
    - `@ConditionalOnBooleanProperty("ai-insights.dashboard.enabled", matchIfMissing = true)`
    - `@Conditional(DashboardAccessCondition.class)`: matches when `org.springframework.security.web.SecurityFilterChain` is present **or** `allow-anonymous=true`.

- [ ] **Step 1: Write the failing tests.**
  - `DashboardPagesTest` uses an in-memory store loaded with `ContractTurns.all()`, `help()` and `feedback()`, with the clock fixed at `2026-10-02T22:00:00Z` (= TO), zone Paris, filter SHOP:
    - `overviewComparesWithPreviousPeriod`: current turns 6, previous turns 1 (T7), 2 days, and recent turns `[T5, T4, T3, T2, T1, T8]`.
    - `usage`: DAU 2 (u2, u3), WAU 3, MAU 3, newUsers 2, turnsPerUser 5/3, turnsPerConversation 1.2. `turnsByHourOfDay` has 1 at hours 0, 1, 10, 11, 12, 13 and 0 elsewhere.
    - `projectionUsesExactMonthLength`: total 0.00132, perConversation 0.000264, monthToDate 0.00132, projection **0.020488**. That is 0.00132 × 2 682 000 s / 172 800 s, because October in Paris has 31 days + 1 hour.
    - `noProjectionInFirstDay`: with the clock at FROM + 1 h, the projection is `null`.
    - `knowledge`: retrievalTurns 2, retrievals 2, empty 1, topDocuments `[(kb,d1,1), (kb,d2,1)]`.
    - `qualityListsRecentNegative`: with the HELP filter, recentNegative is `[H2]` with comment `too long`.
    - `configFlagsPerInstanceMemoryStore`
  - `DashboardQueryTest`: defaults (`to` = clock, `from` = `to` − 7 d, zone UTC, the default application), and each 400 message from the Interfaces block.
  - `DashboardRoutesTest`:
    - `allowAnonymousServesJson`
    - `jsonShapes`: `$.current.turns` is 6 and `$.days[0].start` is an ISO string.
    - `badQueryGives400`
    - `missingConversationIdGives400`
    - `apiResponsesAreNotCached`
  - `InsightsDashboardSecurityTest`: Spring Security with **no app chain**, so Boot's default chain applies, and in-memory users `boss` (role AI_INSIGHTS) and `joe`:
    - `bossSeesDashboard`: 200.
    - `userWithoutRoleIsForbidden`: 403.
    - `anonymousGetsAppLogin`: 401 or a redirect from the app's chain, never 200.
    - `defaultChainStillProtectsApp`: another app endpoint, requested anonymously, is still rejected.
    - `customRole`: `ai-insights.dashboard.role=OPS`.
    - `noSecurityNoDashboard`: the condition evaluated without the Spring Security class (`FilteredClassLoader`) means no route bean, unless `allow-anonymous=true`.
- [ ] **Step 2: Run** them and confirm they are RED.
- [ ] **Step 3: Implement.** The access filter returns 401 when `principal()` is empty and 403 otherwise, both with an `ErrorBody`.
- [ ] **Step 4: Run** `./gradlew build` and confirm it passes.
- [ ] **Step 5: Commit** `feat(web): dashboard API`.

### Task 6: Dashboard UI

**Files:**
- Create: `spring-ai-insights-web/src/main/resources/ai-insights-dashboard/{index.html,app.js,app.css}`
- Create: `spring-ai-insights-web/src/test/java/…/DashboardPreview.java` (test-only `main`) and the Gradle task `previewDashboard` (JavaExec on the test runtime classpath).
- Modify: `DashboardRoutes.java` (static routes)
- Test: `DashboardUiTest.java`

**Interfaces:**
- Consumes: the Task 5 JSON API.
- Routes:
  - `GET {path}` redirects (302) to `{path}/`.
  - `GET {path}/` serves `index.html`.
  - `GET {path}/assets/**` serves files from `classpath:ai-insights-dashboard/`. This is not under `META-INF/resources` or `static/`, so the app's own static handler never exposes it.
  - All of these go through the same access and header filters.
- UI requirements:
  - **Relative URLs only** (`assets/app.js`, `api/overview?...`), so a custom path and a servlet context path work.
  - Hash navigation: `#overview`, `#usage`, `#cost`, `#knowledge`, `#tools`, `#quality`, `#conversation/{app}/{id}`.
  - Filter bar: an application select (from `config.applications`), a period of 24 h / 7 d / 30 d / 90 d, a tenant text field, and the zone from `Intl.DateTimeFormat().resolvedOptions().timeZone`.
  - A banner when `perInstance` is set: "In-memory store: this page shows this instance only, since its last restart."
  - Overview: KPI tiles with the delta against the previous period, an SVG bar chart of daily turns and cost, and a recent-turns table whose conversation IDs link to `#conversation/...`.
  - Usage: DAU/WAU/MAU, new vs returning users, turns per user and per conversation, an hour-of-day bar chart, and top users.
  - Cost: total, per conversation, month to date, projection (or "available after the first day"), unpriced turns, and tables per model, tenant and user.
  - Knowledge base: share of turns with retrieval, the empty rate, per-store stats, and top documents.
  - Tools: a table sorted by calls (least used at the bottom), with the error rate, average duration and repeated calls.
  - Quality: thumbs up/down and positive rate, finish reasons, error types, and recent negative comments linking to their conversation.
  - Conversation: a timeline of turns showing model calls, tools, retrievals, cost, feedback, and the messages when they were captured.
  - Money uses `Intl.NumberFormat` with the configured currency and up to 6 decimals. Times are shown in the browser's zone.
  - Errors from the API are shown in a visible message (an `ErrorBody`), never left as a blank page.

- [ ] **Step 1: Write the failing tests** in `DashboardUiTest`:
  - `rootRedirectsToSlash`
  - `indexServedWithCsp`: the CSP is exactly the value from Global Constraints.
  - `assetsServed`: `app.js` comes back as `text/javascript` and `app.css` as `text/css`.
  - `cspHeaderOnEveryDashboardResponse`: covers index, assets and API.
  - `assetsNeedAccess`: anonymous access is rejected when `allowAnonymous=false`.
  - `scriptsNeverUseInnerHtml`: `app.js` contains none of `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `document.write` or `eval(`.
  - `noExternalUrls`: no `http://` or `https://` in index.html, app.js or app.css.
- [ ] **Step 2: Run** them and confirm they are RED.
- [ ] **Step 3: Implement the routes and the static files.**
- [ ] **Step 4: Visual check.**
  - Run `./gradlew :spring-ai-insights-web:previewDashboard`. It starts on port 8089 with `allow-anonymous=true` and an in-memory store seeded with about 2 000 generated turns over 30 days (several users, tenants, tools, stores, feedback and errors).
  - Screenshot each page with `"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless --window-size=1440,1000 --screenshot=… http://localhost:8089/ai-insights/#<page>`.
  - Inspect the screenshots, and fix layout or empty states until every page reads well, including an empty application.
- [ ] **Step 5: Run** `./gradlew build` and confirm it passes.
- [ ] **Step 6: Commit** `feat(web): dashboard UI`.

### Task 7: README

**Files:** Modify `README.md`

- [ ] **Step 1: Add the "Dashboard" section.**
  - Explain the access model: the app's own login plus the role, `allow-anonymous` for local use, and how to grant the role with JWT (map a claim to `ROLE_AI_INSIGHTS`).
  - Cover the path, the "per instance" note for the in-memory store, and the WebFlux note.
  - Add one screenshot from Task 6, saved under `docs/images/`.
- [ ] **Step 2: Add the "Feedback" section.**
  - Show how to get the turn ID (`chatClientResponse().context()`, and for streams the first element) and send it to the front end.
  - Give a `fetch` example of the endpoint and list its status codes.
  - Show the Java `InsightsFeedback` API for apps whose user ID is not the principal name (a custom identity resolver), and the CSRF note.
- [ ] **Step 3: Update the rest.**
  - Add the new properties to the configuration table, checked against the generated metadata, and the `ai.insights.feedback` metric.
  - Remove Plan 3 from the roadmap and list this plan's deferrals under Plan 4.
- [ ] **Step 4: Commit** `docs: dashboard and feedback`.
