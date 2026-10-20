# Plan 4d: Helpdesk demo, publishing and release workflow

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make v0.1 shippable: a runnable helpdesk demo that produces realistic traffic, Maven Central-ready
publications for the six library modules, and a tag-triggered release workflow, without releasing anything yet.

**Architecture:** Publishing uses the `com.vanniktech.maven.publish` plugin, applied only to an explicit list of
library projects from the root build; a `verifyPublication` task publishes to a local staging repository and checks
it the way Central would. The demo is a separate, never-published Gradle project (`demo/`) with a simulated chat
model and an offline embedding model, so it runs with no API key, and every turn still goes through the real
Spring AI tool, retrieval and observation paths that the starter captures.

**Tech Stack:** Gradle 9 Groovy DSL, vanniktech maven-publish 0.37.0 (from Maven Central), Spring Boot 4.1.1,
Spring AI 2.0.1 (`spring-ai-vector-store-advisor` for `QuestionAnswerAdvisor`, `SimpleVectorStore`), Spring
Security, GitHub Actions, actionlint.

**Spec:** `design.md` §10 (deliverables: demo "helpdesk" assistant with 3 tools + a knowledge base that generates
realistic traffic for screenshots; CI; Maven Central release), §2 line 66 (Apache 2.0, published to Maven Central),
§11 v0.1 row.

## Global Constraints

- Group `io.github.springaiinsights`, version from `gradle.properties` (`0.1.0-SNAPSHOT`). The user did not answer
  the group-ID question; the default stands and is reported back.
- Published modules, exactly: `spring-ai-insights-core`, `-mongodb`, `-bedrock`, `-web`, `-autoconfigure`,
  `-starter`. Never published: `spring-ai-insights-observability`, `spring-ai-insights-demo`, core's test fixtures.
- License Apache-2.0 (`LICENSE` at the root). Project URL in one place: `gradle.properties` `projectUrl`
  (`https://github.com/springaiinsights/spring-ai-insights`; no git remote exists yet — report this).
- No release happens in this plan: no credentials are read at configuration time, signing is only enabled when
  `signingInMemoryKey` is present, and no task contacts Central unless explicitly invoked.
- The starter's runtime classpath must stay free of `spring-webmvc|spring-security|spring-data-mongodb|bedrockagent|
  micrometer-tracing|spring-context-support|jakarta.mail|angus` (existing check, unchanged).
- The demo depends on the starter exactly as a user would (`implementation(project(":spring-ai-insights-starter"))`)
  and uses only public API documented in the README.
- The demo must state plainly, in its startup log and README section, that answers are simulated.
- JDK 21 toolchain; Gradle configuration cache stays on.

## Review Focus

1. **A user runs the demo with no configuration and no network to an LLM** → it starts, the dashboard opens after
   login, traffic appears within seconds. Pinned by `HelpdeskDemoTest.dashboardShowsTrafficAfterStartup` (Task 3).
2. **The demo is started twice or the traffic generator throws** → one failed simulated turn is logged and the
   generator keeps going; it never kills the app. Pinned by `TrafficGeneratorTest.oneFailingTurnDoesNotStopTraffic`
   (Task 3).
3. **A Central-invalid POM** (missing name, description, url, license, developer, scm, or a dependency without a
   version) → `verifyPublication` fails before any upload. Pinned in Task 1 by mutating the POM config.
4. **The release workflow runs on a SNAPSHOT version or a tag that does not match the version** → it fails before
   publishing. Pinned by the `Check version` step and its local dry run (Task 2).
5. **Secrets leak into logs or the repository** → the workflow passes secrets only as `ORG_GRADLE_PROJECT_*` env
   on the publish step; `RELEASING.md` tells the maintainer to store them as GitHub secrets. Checked by actionlint
   and review (Task 2).

---

### Task 1: Publication setup and `verifyPublication`

**Files:**
- Modify: `settings.gradle` (add `pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }`;
  the 0.37.0 plugin marker is only on Maven Central)
- Modify: `build.gradle` (root)
- Modify: `gradle.properties` (`projectUrl=…`)

**Interfaces:**
- Produces: root `val publishedModules = listOf(...)` (the six names above); task `verifyPublication` (group
  `verification`) depending on `publishAllPublicationsToStagingRepository` of each published module; staging repo
  at `build/staging-repo`.

- [ ] **Step 1: Write the check first.** Register `verifyPublication` in the root build. For each published module
  it reads `build/staging-repo/io/github/springaiinsights/<module>/<version>/` and fails (collecting all errors in
  one message) unless:
  - `<module>-<version>.jar`, `-sources.jar`, `-javadoc.jar`, `.pom` and `.module` exist (for SNAPSHOT versions
    the files are timestamped: match `<module>-<version-without-SNAPSHOT>-*.jar` etc.);
  - the POM (parsed with `javax.xml.parsers.DocumentBuilderFactory`) has non-blank `name`, `description`, `url`,
    `licenses/license/name` = `The Apache License, Version 2.0`, `developers/developer/id`, `scm/url`,
    `scm/connection`;
  - every `dependencies/dependency` has a `version` or is covered by an imported BOM in `dependencyManagement`;
  - no file name contains `test-fixtures`.
  It also fails if `build/staging-repo/io/github/springaiinsights/` contains any directory other than the six
  modules (catches `observability` and `demo`).
- [ ] **Step 2: Run it, expect FAIL** — `./gradlew verifyPublication` fails because no publish task exists.
- [ ] **Step 3: Apply the plugin.** Root `plugins { id("com.vanniktech.maven.publish") version "0.37.0" apply false }`;
  in a `configure(subprojects.filter { it.name in publishedModules })` block apply it and configure
  `mavenPublishing { publishToMavenCentral(); if (providers.gradleProperty("signingInMemoryKey").isPresent)
  signAllPublications(); coordinates(group, name, version); pom { … } }`. POM: `name` = project name,
  `description` = a per-module one-liner (set `description` in each module's build file), `url` = `projectUrl`,
  license Apache 2.0 with `https://www.apache.org/licenses/LICENSE-2.0.txt`, developer id `springaiinsights` /
  name `Spring AI Insights contributors`, scm url/connection/developerConnection derived from `projectUrl`.
  Add `publishing { repositories { maven { name = "staging"; url = rootProject.layout.buildDirectory.dir("staging-repo") } } }`.
  Skip core's test-fixtures variants (`components["java"] as AdhocComponentWithVariants`, `withVariantsFromConfiguration`
  for `testFixturesApiElements` and `testFixturesRuntimeElements` → `skip()`). Remove `withSourcesJar()/withJavadocJar()`
  from the shared `subprojects` block only if the plugin then complains about duplicates; otherwise keep them.
  Check the 0.37.0 docs for the exact DSL (`publishToMavenCentral()` targets the Central Portal by default).
- [ ] **Step 4: Run `./gradlew verifyPublication` → PASS**, and `./gradlew -q build` still green with the
  configuration cache.
- [ ] **Step 5: Mutation check.** Temporarily remove the `scm` block → FAIL naming `scm/url` for all six; temporarily
  add `spring-ai-insights-observability` to `publishedModules` → FAIL naming the unexpected directory. Revert.
- [ ] **Step 6: Commit** `build: Maven Central publication for the six library modules`.

### Task 2: CI and release workflow

**Files:**
- Modify: `.github/workflows/ci.yml`
- Create: `.github/workflows/release.yml`
- Create: `RELEASING.md`

**Interfaces:**
- Consumes: `verifyPublication` (Task 1); vanniktech task `publishAndReleaseToMavenCentral`; Gradle properties
  `mavenCentralUsername`, `mavenCentralPassword`, `signingInMemoryKey`, `signingInMemoryKeyPassword`.

- [ ] **Step 1: CI.** `ci.yml` gains `permissions: contents: read`, a job matrix `mongo: ['mongo:5.0', 'mongo:7.0']`
  (5.0 is the floor in `MongoTestSupport.IMAGE`) passing `-PmongoImage=${{ matrix.mongo }}`, and runs
  `./gradlew build verifyPublication`. Upload `**/build/reports/tests/` as an artifact on failure.
- [ ] **Step 2: Release.** `release.yml`, `on: push: tags: ['v*']`, `permissions: contents: read`, one job:
  checkout, setup-java 21 temurin, setup-gradle, then a `Check version` step that fails unless
  `gradle.properties`' `version` equals the tag without `v` and does not end with `-SNAPSHOT`; then
  `./gradlew build verifyPublication`; then `./gradlew publishAndReleaseToMavenCentral --no-configuration-cache`
  with env `ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.MAVEN_CENTRAL_USERNAME }}`, `…Password`,
  `ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.SIGNING_KEY }}`, `…KeyPassword: ${{ secrets.SIGNING_KEY_PASSWORD }}`.
  Secrets appear only on that step.
- [ ] **Step 3: Verify.** `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:latest` → no output, exit 0.
  Extract the `Check version` script and run it locally with `GITHUB_REF_NAME=v0.1.0` → fails on the SNAPSHOT
  version; with a temporary `version=0.1.0` → passes; with `GITHUB_REF_NAME=v0.2.0` → fails.
- [ ] **Step 4: `RELEASING.md`.** Prerequisites (Central Portal account, verified namespace — `io.github.<name>`
  requires owning GitHub user/org `<name>`, GPG key published to a key server), the four GitHub secrets, the
  steps (set `version`, commit, tag `vX.Y.Z`, push tag; bump to next SNAPSHOT), and how to dry-run
  (`./gradlew verifyPublication`). Link it from the README roadmap.
- [ ] **Step 5: Commit** `ci: Mongo matrix, publication check and tag-triggered release`.

### Task 3: Helpdesk demo application

**Files (all under `demo/`, project `spring-ai-insights-demo`, wired in `settings.gradle` like `observability`):**
- Create: `demo/build.gradle` (`application` plugin, `mainClass = io.github.springaiinsights.demo.HelpdeskApplication`;
  deps: starter, `spring-boot-starter-webmvc`, `spring-boot-starter-security`, `spring-ai-client-chat`,
  `spring-ai-vector-store`, `spring-ai-vector-store-advisor`, `micrometer-registry-prometheus`; test: starter-test,
  `spring-boot-starter-webmvc-test`, `spring-security-test`, awaitility)
- Create: `src/main/java/io/github/springaiinsights/demo/`
  - `HelpdeskApplication.java` — `@SpringBootApplication @EnableScheduling`; logs one WARN line at startup:
    `Spring AI Insights demo: answers are simulated, no model provider is called. Dashboard: http://localhost:<port>/ai-insights/ (admin / admin)`.
  - `SimulatedChatModel.java` — `implements ChatModel`; emits `ChatModelObservationContext` observations exactly
    like `spring-ai-insights-autoconfigure/src/test/java/io/github/springaiinsights/ScriptedChatModel.java` (call:
    `observe()`; stream: parent from the Reactor context, stop in `doFinally`), provider name `simulated`.
  - `HelpdeskScript.java` — pure function `List<ChatResponse> respond(Prompt prompt)`: if the prompt has no TOOL
    message yet, the user text decides the tool call (`order`/`#` + digits → `lookupOrder`, `down`/`status`/`slow`
    → `serviceStatus`, `human`/`agent`/`complaint` → `openTicket`); otherwise a text answer built from the tool
    result or the retrieved context. Model: `gpt-4o` when a tool is called, `gpt-4o-mini` otherwise. Usage:
    input tokens = prompt characters / 4, output = answer characters / 4 (both ≥ 1). Streams split the answer into
    words with 15–40 ms delays.
  - `HelpdeskTools.java` — three `@Tool` methods: `lookupOrder(String orderNumber)` (throws
    `IllegalArgumentException("Unknown order " + n)` for numbers not in a fixed map of 5 orders),
    `serviceStatus(String service)` (sleeps 50–400 ms; returns a status line), `openTicket(String summary)`
    (returns `"TCK-" + 4 digits`).
  - `HashingEmbeddingModel.java` — `extends AbstractEmbeddingModel`; lowercase word tokens hashed into a
    256-dimension L2-normalised vector; `dimensions()` = 256. Offline and deterministic.
  - `HelpCenter.java` — `@Bean SimpleVectorStore` loaded at startup from `classpath:help-center/*.md` (8 short
    articles: refunds, shipping times, password reset, invoices, cancel subscription, change address, warranty,
    contact hours), document id = file name.
  - `HelpdeskAssistant.java` — `ChatClient` from the auto-configured builder with default system prompt, tools and
    `QuestionAnswerAdvisor.builder(store).searchRequest(SearchRequest.builder().topK(3).similarityThreshold(0.35).build())`;
    `Answer ask(String userId, String conversationId, String feature, String question)` and
    `Flux<String> stream(...)` with the same parameters; both pass `CONVERSATION_ID`, `USER_ID` and attribute
    `feature`; `Answer` is `record Answer(String text, String turnId)`.
  - `ChatController.java` — `POST /api/chat` `{conversationId, question}` → `Answer`, as the signed-in user.
  - `SecurityConfig.java` — form login + HTTP basic; in-memory users `admin` (roles `AI_INSIGHTS`) and `alice`;
    passwords from `demo.users.admin-password` / `demo.users.alice-password` (defaults `admin` / `alice`,
    `{noop}`); CSRF ignored for `/api/chat` only.
  - `TrafficGenerator.java` — `@Scheduled(fixedDelayString = "${demo.traffic.interval:2s}")`, active when
    `demo.traffic.enabled=true` (default true in `application.yml`). Each tick continues or starts one of up to 6
    open conversations for 12 personas (`user-01`…`user-12`, features `orders`, `account`, `billing`, `outage`),
    picks a question from a fixed bank of ~40 (incl. off-topic ones that retrieve nothing and unknown order
    numbers that make `lookupOrder` fail), streams 30% of turns, and rates ~40% of answers through
    `InsightsFeedback.submit(turnId, userId, rating, comment)` (down when the tool failed or nothing was retrieved,
    otherwise up 85% of the time). Uses a `Random` seeded from `demo.traffic.seed` (default `42`). Any exception
    in a tick is logged at WARN and swallowed.
- Create: `src/main/resources/application.yml` — `spring.application.name: helpdesk-demo`, `server.port: 8080`,
  `ai-insights.identity.secret: demo-only-secret`, `ai-insights.capture-content: true` (demo data is synthetic),
  pricing for `gpt-4o` (2.50 / 10.00, cache-read 1.25, `input-includes-cached: true`) and `gpt-4o-mini`
  (0.15 / 0.60, cache-read 0.075, `input-includes-cached: true`) — check the README pricing example for the
  current key names; `management.endpoints.web.exposure.include: health,prometheus`.
- Test: `demo/src/test/java/io/github/springaiinsights/demo/`
  - `HelpdeskScriptTest` — `orderQuestionCallsLookupOrder`, `outageQuestionCallsServiceStatus`,
    `humanRequestOpensTicket`, `afterAToolResultItAnswers`, `usageIsDerivedFromLength`.
  - `HashingEmbeddingModelTest` — `sameTextSameVector`, `vectorsAreUnitLength`, `refundQuestionIsClosestToRefundArticle`
    (search the loaded store for "how do I get my money back for a refund" → top doc `refunds.md`),
    `offTopicQuestionRetrievesNothing` ("what is the capital of Peru" → empty at threshold 0.35).
  - `TrafficGeneratorTest` — `oneFailingTurnDoesNotStopTraffic` (assistant mock throws once, next tick still
    asks), `sameSeedSameQuestions`.
  - `HelpdeskDemoTest` — `@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "demo.traffic.interval=100ms")`:
    `dashboardShowsTrafficAfterStartup` — awaits (≤ 20 s) `InsightsAnalytics` reporting ≥ 10 turns, ≥ 1 call
    of each of the three tools, ≥ 1 failed `lookupOrder`, ≥ 1 retrieval hit and ≥ 1 empty retrieval, cost > 0,
    and ≥ 1 feedback of each rating; `chatEndpointAnswersSignedInUser` (basic auth `alice`, `POST /api/chat` → 200
    with a non-blank `turnId`); `dashboardRequiresTheRole` (`alice` → 403 on `/ai-insights/`, `admin` → 200);
    `prometheusShowsToolCalls` (`/actuator/prometheus` contains `ai_insights_tool_calls_total`).

**Interfaces:**
- Consumes: starter public API (`InsightsContextKeys`, `InsightsFeedback`, `InsightsAnalytics`), Task 1's
  `publishedModules` (the demo must not be in it).

- [ ] **Step 1:** Write `HelpdeskScriptTest` and `HashingEmbeddingModelTest`; run → FAIL (classes missing).
- [ ] **Step 2:** Implement `HelpdeskScript`, `HashingEmbeddingModel`, `HelpCenter` and the articles; run → PASS.
  If `offTopicQuestionRetrievesNothing` and `refundQuestionIsClosestToRefundArticle` cannot both pass at 0.35, tune
  the threshold, use the tuned value everywhere, and log it in the ledger.
- [ ] **Step 3:** Write `TrafficGeneratorTest` and `HelpdeskDemoTest`; run → FAIL.
- [ ] **Step 4:** Implement the remaining classes and `application.yml`; run `./gradlew :spring-ai-insights-demo:test` → PASS.
- [ ] **Step 5:** Run `./gradlew -q build verifyPublication` and the starter classpath check → green, unchanged.
- [ ] **Step 6: Commit** `feat: helpdesk demo with simulated model and live traffic`.

### Task 4: Demo screenshots and README

**Files:**
- Create: `docs/images/demo-*.png` (overview, conversation timeline, tools, knowledge bases — whatever views the
  dashboard has; check `spring-ai-insights-web`)
- Modify: `README.md` — new section "Try the demo" after the Quickstart:
  `./gradlew :spring-ai-insights-demo:run`, open `http://localhost:8080/ai-insights/`, sign in as `admin`/`admin`,
  `curl -u alice:alice -H 'Content-Type: application/json' -d '{"conversationId":"c1","question":"Where is order #1002?"}' localhost:8080/api/chat`;
  state that answers are simulated and how to plug a real model (add a provider starter and delete the
  `SimulatedChatModel` bean). Replace the Quickstart's composite-build note only if the release happened (it did
  not: keep it). Roadmap: v0.1 remaining item is the first release (link `RELEASING.md`).

- [ ] **Step 1:** Start the demo (async, attached) and let it run ~2 minutes. In the session `files/` folder (not
  the repo), drive the local Chrome with a short `puppeteer-core` script: sign in through the form as `admin`, then
  screenshot each dashboard view at 1440×900. Inspect every image; stop the demo.
- [ ] **Step 2:** Write the README section; check that every relative link resolves (`GuidePropertiesTest`
  covers the README) and run `./gradlew :spring-ai-insights-observability:test --tests '*GuidePropertiesTest'` → PASS.
- [ ] **Step 3: Commit** `docs: demo section and screenshots`.

### After the tasks

Full `./gradlew build verifyPublication`, Mongo 7 run, starter classpath check; whole-branch review with the
code-reviewer agent; one fix pass; merge `--no-ff` into main; delete the branch. Report to the user: group ID and
project URL assumptions, that nothing was released, and the deferred items from earlier plans.
