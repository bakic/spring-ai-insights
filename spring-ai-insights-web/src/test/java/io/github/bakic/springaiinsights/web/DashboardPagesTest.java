package io.github.bakic.springaiinsights.web;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.github.bakic.springaiinsights.analytics.ContractTurns;
import io.github.bakic.springaiinsights.analytics.AbandonedConversation;
import io.github.bakic.springaiinsights.analytics.DocumentUsage;
import io.github.bakic.springaiinsights.analytics.InsightsAnalytics;
import io.github.bakic.springaiinsights.analytics.InsightsFilter;
import io.github.bakic.springaiinsights.analytics.TenantUsage;
import io.github.bakic.springaiinsights.feedback.Feedback;
import io.github.bakic.springaiinsights.knowledge.KnowledgeCorpusCatalog;
import io.github.bakic.springaiinsights.model.ConversationTurn;
import io.github.bakic.springaiinsights.store.InMemoryEventStore;
import io.github.bakic.springaiinsights.store.InsightsEventStore;
import io.github.bakic.springaiinsights.store.TurnQuery;
import io.github.bakic.springaiinsights.web.pages.CorpusCoverage;
import io.github.bakic.springaiinsights.web.pages.CostPage;
import io.github.bakic.springaiinsights.web.pages.DashboardConfig;
import io.github.bakic.springaiinsights.web.pages.KnowledgePage;
import io.github.bakic.springaiinsights.web.pages.OverviewPage;
import io.github.bakic.springaiinsights.web.pages.QualityPage;
import io.github.bakic.springaiinsights.web.pages.TenantsPage;
import io.github.bakic.springaiinsights.web.pages.TurnRow;
import io.github.bakic.springaiinsights.web.pages.UnpricedModel;
import io.github.bakic.springaiinsights.web.pages.UsagePage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static io.github.bakic.springaiinsights.analytics.ContractTurns.FROM;
import static io.github.bakic.springaiinsights.analytics.ContractTurns.HELP;
import static io.github.bakic.springaiinsights.analytics.ContractTurns.SHOP;
import static io.github.bakic.springaiinsights.analytics.ContractTurns.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class DashboardPagesTest {

    private final InMemoryEventStore store = loaded();

    private static InMemoryEventStore loaded() {
        InMemoryEventStore store = new InMemoryEventStore(1000);
        ContractTurns.all().forEach(store::save);
        ContractTurns.help().forEach(store::save);
        ContractTurns.feedback().forEach(store::saveFeedback);
        return store;
    }

    private DashboardPages pages(Instant now) {
        return new DashboardPages(store, store, "shop", "USD", Clock.fixed(now, ZoneOffset.UTC));
    }

    private DashboardPages pages() {
        return pages(TO);
    }

    @Test
    void overviewComparesWithPreviousPeriod() {
        OverviewPage page = pages().overview(SHOP);

        assertThat(page.current().turns()).isEqualTo(6);
        assertThat(page.previous().turns()).isEqualTo(1);
        assertThat(page.days()).hasSize(2);
        assertThat(page.recentTurns()).extracting(TurnRow::turnId)
            .containsExactly("T5", "T4", "T3", "T2", "T1", "T8");
        assertThat(page.recentTurns().get(4)).satisfies(t1 -> {
            assertThat(t1.toolCalls()).isEqualTo(2);
            assertThat(t1.retrievals()).isEqualTo(1);
            assertThat(t1.cost()).isEqualByComparingTo("0.0002");
            assertThat(t1.userId()).isEqualTo("u1");
        });
    }

    @Test
    void usage() {
        UsagePage page = pages().usage(SHOP);

        assertThat(page.dailyActiveUsers()).isEqualTo(2);
        assertThat(page.weeklyActiveUsers()).isEqualTo(3);
        assertThat(page.monthlyActiveUsers()).isEqualTo(3);
        assertThat(page.activeUsers()).isEqualTo(3);
        assertThat(page.newUsers()).isEqualTo(2);
        assertThat(page.turnsPerUser()).isEqualTo(5.0 / 3);
        assertThat(page.turnsPerConversation()).isEqualTo(1.2);
        assertThat(page.days()).hasSize(2);
        long[] expected = new long[24];
        for (int hour : new int[] { 0, 1, 10, 11, 12, 13 }) {
            expected[hour] = 1;
        }
        assertThat(page.turnsByHourOfDay()).containsExactly(expected);
        assertThat(page.topUsers()).hasSize(3);
    }

    @Test
    void projectionUsesExactMonthLength() {
        CostPage page = pages().cost(SHOP);

        assertThat(page.currency()).isEqualTo("USD");
        assertThat(page.total()).isEqualByComparingTo("0.00132");
        assertThat(page.perConversation()).isEqualTo(new BigDecimal("0.000264"));
        assertThat(page.monthToDate()).isEqualByComparingTo("0.00132");
        assertThat(page.monthEndProjection()).isEqualTo(new BigDecimal("0.020488"));
        assertThat(page.unpricedTurns()).isEqualTo(2);
        assertThat(page.models()).isNotEmpty();
        assertThat(page.tenants()).isNotEmpty();
        assertThat(page.topUsers()).hasSize(3);
    }

    @Test
    void tenantsListsEveryTenantOfThePeriodWhateverTenantIsSelected() {
        InsightsFilter acmeSelected = new InsightsFilter("shop", FROM, TO, "acme", ContractTurns.PARIS);

        TenantsPage page = pages().tenants(acmeSelected);

        assertThat(page.tenants()).extracting(TenantUsage::tenantId).containsExactly("acme", "globex");
        assertThat(page.tenants().get(0).turns()).isPositive();
        assertThat(page.truncated()).isFalse();
    }

    @Test
    void tenantsAreCappedToTheBusiest() {
        InMemoryEventStore many = new InMemoryEventStore(1000);
        for (int i = 0; i < DashboardPages.TENANTS + 1; i++) {
            many.save(withTenant(ContractTurns.T1, "T" + i, "tenant-%03d".formatted(i)));
        }
        many.save(withTenant(ContractTurns.T1, "busy", "tenant-200"));

        TenantsPage page = new DashboardPages(many, many, "shop", "USD", Clock.fixed(TO, ZoneOffset.UTC))
            .tenants(SHOP);

        assertThat(page.tenants()).hasSize(DashboardPages.TENANTS);
        assertThat(page.tenants().get(0).tenantId()).isEqualTo("tenant-200");
        assertThat(page.truncated()).isTrue();
    }

    private static ConversationTurn withTenant(ConversationTurn t, String turnId, String tenantId) {
        return new ConversationTurn(turnId, t.application(), t.conversationId(), t.conversationIdMissing(),
                t.startedAt(), t.userId(), tenantId, t.streaming(), t.outcome(), t.errorType(), t.latencyMs(),
                t.timeToFirstTokenMs(), t.finishReasons(), t.modelCalls(), t.toolCalls(), t.retrievals(),
                t.totalCost(), t.currency(), t.attributes(), t.userMessage(), t.assistantMessage(), t.offeredTools(),
                t.traceId());
    }

    @Test
    void noProjectionInFirstDay() {
        CostPage page = pages(FROM.plus(Duration.ofHours(1))).cost(SHOP);

        assertThat(page.monthEndProjection()).isNull();
        assertThat(page.monthToDate()).isEqualByComparingTo("0");
    }

    @Test
    void monthToDateIsZeroAtTheStartOfTheMonth() {
        CostPage page = pages(FROM).cost(SHOP);

        assertThat(page.monthToDate()).isEqualByComparingTo("0");
        assertThat(page.monthEndProjection()).isNull();
    }

    @Test
    void noCostPerConversationWithoutConversations() {
        CostPage page = pages().cost(new io.github.bakic.springaiinsights.analytics.InsightsFilter("nobody", FROM, TO,
                null, ContractTurns.PARIS));

        assertThat(page.perConversation()).isNull();
        assertThat(page.total()).isEqualByComparingTo("0");
    }

    @Test
    void knowledge() {
        KnowledgePage page = pages().knowledge(SHOP);

        assertThat(page.turns()).isEqualTo(6);
        assertThat(page.retrievalTurns()).isEqualTo(2);
        assertThat(page.retrievals()).isEqualTo(2);
        assertThat(page.emptyRetrievals()).isEqualTo(1);
        assertThat(page.stores()).hasSize(1);
        assertThat(page.topDocuments()).containsExactly(new DocumentUsage("kb", "d1", 1),
                new DocumentUsage("kb", "d2", 1));
    }

    @Test
    void tools() {
        assertThat(pages().tools(SHOP).tools()).extracting(t -> t.tool()).containsExactly("lookup", "refund");
    }

    @Test
    void qualityListsRecentNegative() {
        QualityPage page = pages().quality(HELP);

        assertThat(page.summary().turns()).isEqualTo(5);
        assertThat(page.quality().negativeFeedback()).isEqualTo(1);
        assertThat(page.recentNegative()).extracting(Feedback::turnId, Feedback::comment)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("H2", "too long"));
    }

    @Test
    void toolsNeverCalled() {
        assertThat(pages().tools(SHOP).neverCalled()).containsExactly("search");
        assertThat(pages().tools(SHOP).offeredRecorded()).isTrue();
    }

    @Test
    void knowledgeHasLeastDocuments() {
        assertThat(pages().knowledge(HELP).leastDocuments()).first().isEqualTo(new DocumentUsage("faq", "q1", 1));
    }

    @Test
    void knowledgeCoverage() {
        KnowledgePage page = pages(TO, catalog("kb", Set.of("d1", "d2", "d3", "d4"))).knowledge(HELP);

        assertThat(page.coverage()).containsExactly(new CorpusCoverage("kb", 4, 2, List.of("d3", "d4")));
    }

    @Test
    void neverRetrievedSampleIsCapped() {
        Set<String> ids = IntStream.range(0, 60).mapToObj(i -> "x%02d".formatted(i)).collect(Collectors.toSet());

        CorpusCoverage coverage = pages(TO, catalog("kb", ids)).knowledge(HELP).coverage().get(0);

        assertThat(coverage.documents()).isEqualTo(60);
        assertThat(coverage.neverRetrieved()).isEqualTo(60);
        assertThat(coverage.neverRetrievedSample()).hasSize(50).startsWith("x00", "x01").endsWith("x49");
    }

    @Test
    void unloadedCatalogReportsUnknownSize() {
        KnowledgePage page = pages(TO, catalog("kb", null)).knowledge(HELP);

        assertThat(page.coverage()).containsExactly(new CorpusCoverage("kb", null, 0, List.of()));
    }

    @Test
    void qualityHasAbandoned() {
        Instant now = Instant.parse("2026-10-02T12:10:00Z");
        QualityPage page = pages(now).quality(HELP);

        assertThat(page.abandoned().count()).isEqualTo(2);
        assertThat(page.abandoned().recent()).extracting(AbandonedConversation::conversationId)
            .containsExactly("h2", "h1");
    }

    @Test
    void configCarriesTraceUrl() {
        DashboardOptions options = new DashboardOptions(List.of(), Duration.ofMinutes(30),
                "https://tempo.example/trace/{traceId}");

        assertThat(new DashboardPages(store, store, "shop", "USD", Clock.systemUTC(), options).config().traceUrl())
            .isEqualTo("https://tempo.example/trace/{traceId}");
        assertThat(pages().config().traceUrl()).isNull();
    }

    @Test
    void optionsAreValidated() {
        org.assertj.core.api.Assertions
            .assertThatIllegalArgumentException()
            .isThrownBy(() -> new DashboardOptions(List.of(), Duration.ofSeconds(59), null));
        org.assertj.core.api.Assertions
            .assertThatIllegalArgumentException()
            .isThrownBy(() -> new DashboardOptions(List.of(), Duration.ofMinutes(30), "javascript:alert({traceId})"));
        org.assertj.core.api.Assertions
            .assertThatIllegalArgumentException()
            .isThrownBy(() -> new DashboardOptions(List.of(), Duration.ofMinutes(30), "https://tempo.example.com/"));
    }

    private DashboardPages pages(Instant now, KnowledgeCorpusCatalog... catalogs) {
        return new DashboardPages(store, store, "shop", "USD", Clock.fixed(now, ZoneOffset.UTC),
                new DashboardOptions(List.of(catalogs), Duration.ofMinutes(30), null));
    }

    private static KnowledgeCorpusCatalog catalog(String store, @Nullable Set<String> ids) {
        return new KnowledgeCorpusCatalog() {
            @Override
            public String store() {
                return store;
            }

            @Override
            public @Nullable Set<String> documentIds() {
                return ids;
            }
        };
    }

    @Test
    void conversation() {
        var page = pages().conversation("help", "h1");

        assertThat(page.turns()).extracting(ConversationTurn::turnId).containsExactly("H1", "H2");
        assertThat(page.feedback()).extracting(Feedback::turnId).containsExactly("H1", "H2");
        assertThat(page.totalTurns()).isEqualTo(2);
    }

    @Test
    void longConversationShowsTheLatestTurnsAndTheTotal() {
        int total = InsightsAnalytics.MAX_CONVERSATION_TURNS + 7;
        InMemoryEventStore big = new InMemoryEventStore(total + 10);
        ContractTurns.longConversation("shop", "long", total).forEach(big::save);

        var page = new DashboardPages(big, big, "shop", "USD", Clock.systemUTC()).conversation("shop", "long");

        assertThat(page.turns()).hasSize(InsightsAnalytics.MAX_CONVERSATION_TURNS);
        assertThat(page.turns().get(0).turnId()).isEqualTo("L0007");
        assertThat(page.turns().get(page.turns().size() - 1).turnId()).isEqualTo(String.format("L%04d", total - 1));
        assertThat(page.totalTurns()).isEqualTo(total);
    }

    @Test
    void costNamesTheModelsWithoutAPriceAndTheConfiguredOnesTheyResemble() {
        DashboardOptions options = new DashboardOptions(List.of(), Duration.ofMinutes(30), null,
                List.of("m1", "gpt-4o"));

        CostPage cost = new DashboardPages(store, store, "shop", "USD", Clock.systemUTC(), options)
            .cost(SHOP);

        assertThat(cost.configuredModels()).containsExactly("m1", "gpt-4o");
        assertThat(cost.unpricedModels()).extracting(UnpricedModel::model, UnpricedModel::calls)
            .containsExactly(tuple("m2", 2L));
        assertThat(cost.unpricedModels().get(0).similar()).isNull();
    }

    @Test
    void similarConfiguredModelIsTheLongestOneContainingOrContainedInTheReportedName() {
        List<String> configured = List.of("gpt-4o", "gpt-4o-mini", "claude");
        assertThat(DashboardPages.similarConfigured("gpt-4o-mini-2024-07-18", configured)).isEqualTo("gpt-4o-mini");
        assertThat(DashboardPages.similarConfigured("GPT-4O-2024", configured)).isEqualTo("gpt-4o");
        assertThat(DashboardPages.similarConfigured("gpt", configured)).isEqualTo("gpt-4o-mini");
        assertThat(DashboardPages.similarConfigured("llama", configured)).isNull();
        assertThat(DashboardPages.similarConfigured("llama", List.of())).isNull();
    }

    @Test
    void configFlagsPerInstanceMemoryStore() {
        DashboardConfig config = pages().config();

        assertThat(config).isEqualTo(new DashboardConfig("shop", List.of("crm", "help", "shop"), "USD", "memory", true, null));
    }

    @Test
    void configNamesCustomStores() {
        InsightsEventStore custom = new CustomStore();
        DashboardConfig config = new DashboardPages(store, custom, "shop", "EUR", Clock.systemUTC()).config();

        assertThat(config.store()).isEqualTo("CustomStore");
        assertThat(config.perInstance()).isFalse();
        assertThat(config.currency()).isEqualTo("EUR");
    }

    static final class CustomStore implements InsightsEventStore {

        @Override
        public void save(ConversationTurn turn) {
        }

        @Override
        public Optional<ConversationTurn> findTurn(String turnId) {
            return Optional.empty();
        }

        @Override
        public List<ConversationTurn> findTurns(TurnQuery query) {
            return List.of();
        }
    }
}
