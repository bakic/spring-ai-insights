package io.github.springaiinsights.web;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import io.github.springaiinsights.analytics.ContractTurns;
import io.github.springaiinsights.analytics.DocumentUsage;
import io.github.springaiinsights.feedback.Feedback;
import io.github.springaiinsights.model.ConversationTurn;
import io.github.springaiinsights.store.InMemoryEventStore;
import io.github.springaiinsights.store.InsightsEventStore;
import io.github.springaiinsights.store.TurnQuery;
import io.github.springaiinsights.web.pages.CostPage;
import io.github.springaiinsights.web.pages.DashboardConfig;
import io.github.springaiinsights.web.pages.KnowledgePage;
import io.github.springaiinsights.web.pages.OverviewPage;
import io.github.springaiinsights.web.pages.QualityPage;
import io.github.springaiinsights.web.pages.TurnRow;
import io.github.springaiinsights.web.pages.UsagePage;
import org.junit.jupiter.api.Test;

import static io.github.springaiinsights.analytics.ContractTurns.FROM;
import static io.github.springaiinsights.analytics.ContractTurns.HELP;
import static io.github.springaiinsights.analytics.ContractTurns.SHOP;
import static io.github.springaiinsights.analytics.ContractTurns.TO;
import static org.assertj.core.api.Assertions.assertThat;

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
        CostPage page = pages().cost(new io.github.springaiinsights.analytics.InsightsFilter("nobody", FROM, TO,
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
    void conversation() {
        var page = pages().conversation("help", "h1");

        assertThat(page.turns()).extracting(ConversationTurn::turnId).containsExactly("H1", "H2");
        assertThat(page.feedback()).extracting(Feedback::turnId).containsExactly("H1", "H2");
    }

    @Test
    void configFlagsPerInstanceMemoryStore() {
        DashboardConfig config = pages().config();

        assertThat(config).isEqualTo(new DashboardConfig("shop", List.of("crm", "help", "shop"), "USD", "memory", true));
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
