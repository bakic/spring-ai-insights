# Plan 4b — Weekly Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Push a weekly management report (spec §8.9) to Slack and email, once per application even with several
instances, and serve the same report as JSON and HTML behind the dashboard's access check.

**Architecture:** A pure-Java `report` package in core builds a `WeeklyReport` from `InsightsAnalytics` and renders it
as Slack text, plain text and HTML. Senders (Slack through the JDK `HttpClient`, email through Spring Mail) sit
behind a `ReportSender` SPI. A `WeeklyReportJob` claims the week in a `ReportLock` (MongoDB collection, or in memory)
before sending, so one instance sends. The autoconfigure module schedules the job with a cron trigger on its own
daemon thread; the web module adds `{path}/api/report` and `{path}/report`.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring AI 2.0.1, JDK `java.net.http.HttpClient`, Spring Framework
`CronExpression`/`CronTrigger`/`ThreadPoolTaskScheduler`, Spring Mail (`spring-context-support`, Jakarta Mail),
MongoDB Java driver (floor 5.0), JUnit 5, AssertJ, Testcontainers, JDK `com.sun.net.httpserver.HttpServer` in tests.

**Spec:** `design.md` (§3 goal 9, §8.5 "the scheduled report takes a lock in the store", §8.9, §9 `report:` shape,
§13 "report sending is locked to one instance; per-application reports").

## Global Constraints

- Properties under `ai-insights.report`: `enabled` (default `true`), `cron` (default `0 0 8 * * MON`, Spring cron,
  validated at bind time), `zone` (default the JVM default zone), `slack-webhook` (absolute http(s) URI),
  `email-to` (list), `email-from` (optional), `dashboard-url` (optional absolute http(s) URI, linked from the report).
- Report week: the 7 calendar days before today in `report.zone`, `[today − 7d, today)` as local dates; the previous
  week is the 7 days before that. Built with `LocalDate.atStartOfDay(zone)` so DST weeks stay 7 calendar days.
- Each instance reports its own application (`InsightsAutoConfiguration.application(...)`), for all tenants.
- Lock key: `weekly-report:{application}:{firstDay}`; the claim expires 35 days after it is taken.
- The job never throws: store, rendering and sender failures are logged at WARN; one failing sender does not stop
  the others. The Slack webhook URL is a secret and never appears in logs or exception messages.
- Scheduling only when `report.enabled` and at least one channel is configured. `email-to` without a
  `JavaMailSender` bean fails startup with a message naming `spring-boot-starter-mail`.
- The starter's runtime classpath must not gain `spring-context-support`, `jakarta.mail`, `spring-webmvc`,
  `spring-security`, `spring-data-mongodb` or `bedrockagent` (mail is opt-in, like the Bedrock module).
- Every text taken from data (application, tool names) is escaped: HTML entities in HTML, `&`, `<`, `>` in Slack.
- Endpoints use the dashboard's role check; the HTML page gets its own CSP:
  `default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`.
- One commit per task; trailer `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

## Review Focus

1. An empty week, or an empty previous week, must render "–" and "new"/"no change", never divide by zero, NaN or
   "Infinity%" — Task 2 `emptyWeekHasNoRates`, Task 3 `changeAgainstZero`.
2. Two instances firing at the same second must send one report — Task 1 `concurrentClaimsHaveOneWinner` (both
   stores).
3. A failing Slack call must not leak the webhook URL into logs and must not stop the email — Task 4
   `failureMessageHidesWebhook`, `oneFailingSenderDoesNotStopOthers`.
4. Tool or application names containing `<script>` or `<!channel>` must render inert — Task 3
   `htmlEscapesData`, `slackEscapesData`.
5. The week around the end of DST (Europe/Paris, 2026-10-25) must cover 7 calendar days, and the month projection
   on the 1st must equal the month's actual cost — Task 2 `dstWeekIsSevenCalendarDays`, `projectionOnFirstOfMonth`.

---

### Task 1: Report lock (SPI, in memory, MongoDB)

**Files:**
- Create: `spring-ai-insights-core/src/main/java/io/github/springaiinsights/report/ReportLock.java`
- Create: `spring-ai-insights-core/src/main/java/io/github/springaiinsights/report/InMemoryReportLock.java`
- Create: `spring-ai-insights-core/src/testFixtures/java/io/github/springaiinsights/report/ReportLockContract.java`
- Create: `spring-ai-insights-core/src/test/java/io/github/springaiinsights/report/InMemoryReportLockTest.java`
- Modify: `spring-ai-insights-mongodb/.../MongoEventStore.java` (implements `ReportLock`, collection `{prefix}locks`)
- Modify: `spring-ai-insights-mongodb/.../MongoIndexes.java` (TTL index `ttl_expiresAt` on `expiresAt`,
  `expireAfterSeconds` 0)
- Create: `spring-ai-insights-mongodb/src/test/java/io/github/springaiinsights/mongodb/MongoReportLockTest.java`

**Interfaces:**
- Produces: `interface ReportLock { boolean claim(String key, Instant expiresAt); }` — true for the first claim of a
  key that is not expired, false otherwise; thread-safe; may throw on store failure.
- Produces: `final class InMemoryReportLock implements ReportLock` (`Clock` constructor + no-arg using UTC system
  clock; expired keys are claimable again).
- Produces: `MongoEventStore implements ReportLock`; `String locksCollectionName()`.

- [ ] **Step 1: Write the contract** — `abstract ReportLock lock()`; tests:
  `firstClaimWins` (claim "k" → true, again → false), `keysAreIndependent` ("a" and "b" both true),
  `concurrentClaimsHaveOneWinner` (16 threads, a `CountDownLatch` start, exactly one true).
  `InMemoryReportLockTest` extends it and adds `expiredClaimCanBeTakenAgain` (mutable clock past `expiresAt`).
  `MongoReportLockTest` extends it with a fresh template and adds `claimHasTtlIndex` (index `ttl_expiresAt` with
  `expireAfterSeconds` 0 on `ai_insights_locks`).
- [ ] **Step 2: Run** `./gradlew -q :spring-ai-insights-core:test :spring-ai-insights-mongodb:test` — FAIL to compile.
- [ ] **Step 3: Implement.** In memory: `ConcurrentHashMap.compute`, replacing an entry whose expiry is not after now.
  MongoDB: `insertOne({_id: key, expiresAt: Date})`, `DUPLICATE_KEY` write error → false; indexes ensured like
  turns. `MongoIndexes.ensure` gains the locks collection.
- [ ] **Step 4: Run** the same command, plus `-PmongoImage=mongo:7.0 --rerun-tasks` for the Mongo module — PASS.
- [ ] **Step 5: Commit** `feat(report): report lock in memory and in MongoDB`.

### Task 2: Weekly report model and builder

**Files:**
- Create in `spring-ai-insights-core/src/main/java/io/github/springaiinsights/report/`: `WeeklyReport.java`,
  `ReportFigures.java`, `MonthProjection.java`, `WeeklyReports.java`
- Test: `spring-ai-insights-core/src/test/java/io/github/springaiinsights/report/WeeklyReportsTest.java`
  (uses `InMemoryEventStore` and `ContractTurns`-style turns)

**Interfaces:**
- Consumes: `InsightsAnalytics.summary`, `.quality`, `.tools`.
- Produces:
  - `record ReportFigures(long activeUsers, long conversations, long turns, BigDecimal cost,
    @Nullable BigDecimal costPerConversation, @Nullable Double positiveFeedbackRate,
    @Nullable Double emptyRetrievalRate, long unpricedTurns)` with
    `static ReportFigures of(UsageSummary, QualitySummary)`: cost per conversation scale 4 HALF_UP, null without
    conversations; positive rate = positive / (positive + negative), null without feedback; empty rate =
    emptyRetrievals / retrievals, null without retrievals.
  - `record MonthProjection(YearMonth month, BigDecimal costToDate, int daysElapsed, BigDecimal projectedCost)`.
  - `record WeeklyReport(String application, String currency, LocalDate firstDay, LocalDate lastDay,
    ReportFigures current, ReportFigures previous, List<ToolUsage> topTools, MonthProjection projection,
    @Nullable URI dashboardUrl)`.
  - `final class WeeklyReports(InsightsAnalytics analytics, String currency, ZoneId zone, Clock clock,
    @Nullable URI dashboardUrl)`: `static final int TOP_TOOLS = 5`; `ZoneId zone()`;
    `LocalDate lastWeekStart()` (= today in zone − 7 days); `WeeklyReport latest(String application)`;
    `WeeklyReport forWeek(String application, LocalDate firstDay)`.
- Projection: month of `lastDay`; cost over `[first of that month, lastDay + 1)`; `daysElapsed` = days in that range;
  `projectedCost = costToDate / daysElapsed × lengthOfMonth`, scale 2 HALF_UP.

- [ ] **Step 1: Write the failing tests:** `latestIsTheSevenDaysBeforeToday` (clock 2026-10-19T08:00 Paris →
  2026-10-12..2026-10-18), `figuresAndDeltasSource` (turns in both weeks: current/previous active users,
  conversations, cost, rates), `topToolsAreTheFiveMostCalled`, `emptyWeekHasNoRates` (all nullable figures null,
  counts 0, cost 0), `dstWeekIsSevenCalendarDays` (week 2026-10-19..25 Paris; a turn at 2026-10-25T23:30 Paris is
  in, one at 2026-10-26T00:30 Paris is out), `projectionScalesMonthToDate` (cost 10 over Oct 1–10 → projected
  31.00), `projectionOnFirstOfMonth` (report week ending 2026-10-31, projection equals October's cost),
  `unpricedTurnsAreCounted`.
- [ ] **Step 2: Run** `./gradlew -q :spring-ai-insights-core:test --tests '*WeeklyReportsTest'` — FAIL.
- [ ] **Step 3: Implement** the records and `WeeklyReports` (filters with tenant null, the report's zone).
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(report): weekly report figures, deltas and month projection`.

### Task 3: Rendering (Slack, plain text, HTML)

**Files:**
- Create: `spring-ai-insights-core/src/main/java/io/github/springaiinsights/report/ReportFormat.java`
- Test: `spring-ai-insights-core/src/test/java/io/github/springaiinsights/report/ReportFormatTest.java`

**Interfaces:**
- Produces: `final class ReportFormat` with static `String subject(WeeklyReport)`, `String slack(WeeklyReport)`,
  `String text(WeeklyReport)`, `String html(WeeklyReport)`, and package-visible `String change(@Nullable Number
  current, @Nullable Number previous)`, `String pointsChange(@Nullable Double current, @Nullable Double previous)`.
- Copy (plain text; Slack bolds the title with `*…*`, italicises the note with `_…_`, links as `<url|…>`; HTML
  renders the same lines as a table with inline styles and an anchor):

```
AI assistant weekly report: {application}, {12 Oct} – {18 Oct 2026}
Active users: 42 (+10% vs previous week)
Conversations: 310 (−3%)
Cost: USD 128.40 (+12%), USD 0.41 per conversation (+15%)
Positive feedback: 82% (+3 pts)
Knowledge base queries with no result: 7% (−1 pt)
Top tools: search_orders (520 calls), create_ticket (210 calls)
Projected cost for October 2026: USD 540.00 (USD 300.10 so far)
Cost excludes 3 turns with unpriced models.          ← only when unpricedTurns > 0
Open the dashboard: {dashboardUrl}                   ← only when set
```

- `subject` = `AI assistant weekly report: {application}, {12 Oct} – {18 Oct 2026}`. Dates `d MMM` /
  `d MMM yyyy`, `Locale.ENGLISH`. Money `{currency} {amount scale 2 HALF_UP}`; null → `–`. Percent rounded to an
  integer. `change`: both null → ""; previous 0 or null → current 0 → "no change", else "new"; else signed integer
  percent (`+12%`, `−3%`, `0%` → "no change"). `pointsChange`: either null → ""; signed integer points, `pt` when
  ±1, "no change" at 0. No tool called → `Top tools: none`.

- [ ] **Step 1: Write the failing tests:** `textHasEveryFigure` (the exact copy above for a fixed report),
  `changeAgainstZero`, `pointsChangeSingular`, `slackEscapesData` (application `<!channel> & co` →
  `&lt;!channel&gt; &amp; co`), `htmlEscapesData` (tool `<script>alert(1)</script>` → no `<script>`),
  `htmlIsACompleteDocument` (starts `<!doctype html>`, has `<meta charset="utf-8">`), `emptyReportRendersDashes`,
  `optionalLinesOnlyWhenRelevant`.
- [ ] **Step 2: Run** `./gradlew -q :spring-ai-insights-core:test --tests '*ReportFormatTest'` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(report): render the weekly report for Slack, email and the web`.

### Task 4: Senders and the job

**Files:**
- Create in core `report/`: `ReportSender.java`, `SlackReportSender.java`, `MailReportSender.java`,
  `WeeklyReportJob.java`
- Modify: `spring-ai-insights-core/build.gradle.kts` — `compileOnly` and `testImplementation`
  `org.springframework:spring-context-support`, `jakarta.mail:jakarta.mail-api`; `testRuntimeOnly`
  `org.eclipse.angus:angus-mail`
- Tests: `SlackReportSenderTest.java` (JDK `HttpServer` on port 0), `MailReportSenderTest.java` (a
  `JavaMailSenderImpl` subclass capturing `send(MimeMessage...)`), `WeeklyReportJobTest.java`

**Interfaces:**
- Produces: `interface ReportSender { String name(); void send(WeeklyReport report) throws Exception; }`
- Produces: `SlackReportSender(URI webhook, HttpClient client)` (name `slack`): POST
  `{"text": slack(report), "unfurl_links": false}` as `application/json; charset=utf-8`, 10 s request timeout; a
  non-2xx status throws `IllegalStateException("Slack webhook answered HTTP " + status)`; an `IOException` is
  rethrown as `IllegalStateException` whose message has the webhook replaced by `<slack-webhook>`, without cause.
- Produces: `MailReportSender(JavaMailSender mail, @Nullable String from, List<String> to)` (name `email`):
  multipart alternative, `text(report)` and `html(report)`, subject `subject(report)`; `to` must not be empty.
- Produces: `WeeklyReportJob(WeeklyReports reports, ReportLock lock, List<ReportSender> senders, String application,
  Clock clock) implements Runnable`; `run()` claims `weekly-report:{application}:{lastWeekStart}` until now + 35
  days, then builds `forWeek` once and calls each sender; logs INFO on success per sender, WARN on each failure.

- [ ] **Step 1: Write the failing tests:** Slack — `postsTextAsJson` (body JSON-parsed, `text` equals
  `ReportFormat.slack`), `non2xxFails`, `failureMessageHidesWebhook` (server stopped → message lacks the URI path).
  Mail — `sendsHtmlAndTextAlternatives` (subject, recipients, both parts), `requiresRecipients`.
  Job — `sendsOncePerWeek` (two jobs sharing one `InMemoryReportLock` → one send),
  `oneFailingSenderDoesNotStopOthers`, `lockFailureIsLoggedNotThrown`, `storeFailureIsLoggedNotThrown`.
- [ ] **Step 2: Run** `./gradlew -q :spring-ai-insights-core:test --tests '*Report*'` — FAIL.
- [ ] **Step 3: Implement.** JSON string escaping by hand (`"` `\` and control characters) — core has no JSON
  library contract.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(report): Slack and email senders, locked weekly job`.

### Task 5: Report endpoints

**Files:**
- Modify: `spring-ai-insights-web/src/main/java/io/github/springaiinsights/web/DashboardRoutes.java`
- Test: `spring-ai-insights-web/src/test/java/io/github/springaiinsights/web/DashboardRoutesTest.java`

**Interfaces:**
- Consumes: `WeeklyReports.latest(String)`, `ReportFormat.html`.
- Produces: `DashboardRoutes.create(String path, String role, boolean allowAnonymous, DashboardPages pages,
  Clock clock, @Nullable WeeklyReports reports)`; the 5-argument overload delegates with `null`. With reports:
  `GET {path}/api/report[?app=]` → JSON `WeeklyReport`; `GET {path}/report[?app=]` → HTML, CSP
  `REPORT_CONTENT_SECURITY_POLICY`. `app` defaults to the dashboard's application, max 200 characters (400 above).

- [ ] **Step 1: Write the failing tests:** `reportJson` (`$.application`, `$.firstDay` as `yyyy-MM-dd`,
  `$.current.conversations`), `reportHtml` (content type `text/html`, CSP header), `reportNeedsRole` (401/403 like
  the other routes), `reportRoutesAbsentWithoutReports` (404).
- [ ] **Step 2: Run** `./gradlew -q :spring-ai-insights-web:test` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(web): weekly report as JSON and HTML`.

### Task 6: Auto-configuration and README

**Files:**
- Modify: `InsightsProperties.java` — `Report` class with the Global Constraints properties and validation
  (`CronExpression.isValidExpression`, absolute http(s) URIs)
- Create: `autoconfigure/.../InsightsReportAutoConfiguration.java`, `autoconfigure/.../WeeklyReportSchedule.java`
- Modify: `InsightsWebAutoConfiguration.java` (passes `ObjectProvider<WeeklyReports>.getIfAvailable()`; ordered after
  the report auto-configuration), `AutoConfiguration.imports`, autoconfigure `build.gradle.kts` (`compileOnly` +
  `testImplementation` `spring-context-support`, `jakarta.mail-api`; `testRuntimeOnly` `angus-mail`)
- Test: `InsightsReportAutoConfigurationTest.java`
- Modify: `README.md` (Weekly report section, config rows, roadmap)

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces: beans `WeeklyReports insightsWeeklyReports` (always, with `InsightsAnalytics` and `PriceCatalog`);
  `SlackReportSender insightsSlackReportSender` (when `slack-webhook` set; JDK `HttpClient` with 10 s connect
  timeout); `MailReportSender insightsMailReportSender` (when `email-to` set and `JavaMailSender` is on the
  classpath; no unique `JavaMailSender` bean → `IllegalStateException`); `WeeklyReportSchedule
  insightsWeeklyReportSchedule` (destroy `close`) when enabled and a channel is configured — `email-to` set but no
  `email` sender (mail classes absent) → `IllegalStateException` naming `spring-boot-starter-mail`.
- Lock choice: a unique `ReportLock` bean, else the store if it implements `ReportLock`, else
  `InMemoryReportLock` — with a WARN when the store is neither in memory nor a `ReportLock` ("each instance will
  send the report").
- `WeeklyReportSchedule(Runnable job, String cron, ZoneId zone)`: one daemon `ThreadPoolTaskScheduler` thread
  `ai-insights-report-`, `CronTrigger(cron, zone)`; `close()` shuts it down.

- [ ] **Step 1: Write the failing tests:** `reportsBeanAlwaysPresent`, `noScheduleWithoutChannel`,
  `scheduleWithSlackWebhook`, `scheduleDisabled` (`enabled=false`), `invalidCronFailsStartup`,
  `webhookMustBeHttp`, `emailWithoutMailSenderFailsStartup`, `emailWithMailSenderSchedules`,
  `mongoStoreIsTheLock` (store bean implementing `ReportLock` is used), `reportEndpointServed` (MockMvc on the
  dashboard route bean, `/ai-insights/api/report`).
- [ ] **Step 2: Run** `./gradlew -q :spring-ai-insights-autoconfigure:test` — FAIL.
- [ ] **Step 3: Implement**, then the README section: what the report contains, YAML for Slack and email, the
  one-instance guarantee (MongoDB) and its in-memory limit, the endpoints, and that the webhook is a secret
  (`${SLACK_WEBHOOK}`).
- [ ] **Step 4: Run** `./gradlew -q build`, the Mongo 7 run and the starter classpath check (adding
  `spring-context-support|jakarta.mail` to the grep) — PASS, nothing printed.
- [ ] **Step 5: Commit** `feat(autoconfigure): schedule and deliver the weekly report`.
