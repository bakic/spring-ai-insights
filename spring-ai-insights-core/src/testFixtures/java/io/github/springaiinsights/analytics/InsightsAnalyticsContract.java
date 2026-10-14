package io.github.springaiinsights.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;

import io.github.springaiinsights.model.ConversationTurn;
import io.github.springaiinsights.store.InsightsEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.github.springaiinsights.analytics.ContractTurns.FROM;
import static io.github.springaiinsights.analytics.ContractTurns.PARIS;
import static io.github.springaiinsights.analytics.ContractTurns.SHOP;
import static io.github.springaiinsights.analytics.ContractTurns.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Behaviour every {@link InsightsAnalytics} implementation shares. Subclasses return a fresh, empty store per test;
 * the dataset is {@link ContractTurns}.
 */
public abstract class InsightsAnalyticsContract {

    protected abstract InsightsEventStore store();

    protected abstract InsightsAnalytics analytics();

    @BeforeEach
    void saveDataset() {
        ContractTurns.all().forEach(store()::save);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void summaryCountsEverything() {
        UsageSummary s = analytics().summary(SHOP);
        assertThat(s.turns()).isEqualTo(6);
        assertThat(s.conversations()).isEqualTo(5);
        assertThat(s.activeUsers()).isEqualTo(3);
        assertThat(s.anonymousTurns()).isEqualTo(1);
        assertThat(s.errorTurns()).isEqualTo(1);
        assertThat(s.cancelledTurns()).isEqualTo(1);
        assertThat(s.inputTokens()).isEqualTo(680);
        assertThat(s.outputTokens()).isEqualTo(365);
        assertThat(s.cacheReadTokens()).isEqualTo(10);
        assertThat(s.cacheWriteTokens()).isZero();
        assertThat(s.cost()).isEqualByComparingTo(bd("0.00132"));
        assertThat(s.unpricedTurns()).isEqualTo(2);
        assertThat(s.avgLatencyMs()).isCloseTo(1500.0, within(1e-9));
        assertThat(s.avgTimeToFirstTokenMs()).isCloseTo(300.0, within(1e-9));
        assertThat(s.toolCalls()).isEqualTo(3);
        assertThat(s.retrievals()).isEqualTo(2);
        assertThat(s.emptyRetrievals()).isEqualTo(1);
    }

    @Test
    void rangeIsHalfOpen() {
        var edge = new InsightsFilter("shop", ContractTurns.T7.startedAt(), ContractTurns.T8.startedAt(), null, PARIS);
        assertThat(analytics().summary(edge).turns()).isEqualTo(1);
        assertThat(analytics().topUsers(edge, 10)).extracting(UserUsage::userId).containsExactly("u1");
    }

    @Test
    void tenantFilter() {
        UsageSummary s = analytics().summary(new InsightsFilter("shop", FROM, TO, "acme", PARIS));
        assertThat(s.turns()).isEqualTo(3);
        assertThat(s.activeUsers()).isEqualTo(2);
        assertThat(s.cost()).isEqualByComparingTo(bd("0.0013"));
    }

    @Test
    void allApplicationsWhenNull() {
        assertThat(analytics().summary(new InsightsFilter(null, FROM, TO, null, PARIS)).turns()).isEqualTo(7);
    }

    @Test
    void emptyRangeGivesZeros() {
        var empty = new InsightsFilter("shop", Instant.parse("2020-01-01T00:00:00Z"),
                Instant.parse("2020-01-02T00:00:00Z"), null, PARIS);
        UsageSummary s = analytics().summary(empty);
        assertThat(s.turns()).isZero();
        assertThat(s.conversations()).isZero();
        assertThat(s.cost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(s.avgLatencyMs()).isZero();
        assertThat(s.avgTimeToFirstTokenMs()).isNull();
        assertThat(analytics().timeline(empty, Granularity.DAY)).isEmpty();
        assertThat(analytics().models(empty)).isEmpty();
        assertThat(analytics().tools(empty)).isEmpty();
        assertThat(analytics().retrievals(empty)).isEmpty();
        assertThat(analytics().topUsers(empty, 10)).isEmpty();
        assertThat(analytics().tenants(empty)).isEmpty();
    }

    @Test
    void timelineBucketsByDayInZone() {
        var days = analytics().timeline(SHOP, Granularity.DAY);
        assertThat(days).hasSize(2);
        assertThat(days.get(0).start()).isEqualTo(Instant.parse("2026-09-30T22:00:00Z"));
        assertThat(days.get(0).turns()).isEqualTo(3);
        assertThat(days.get(0).conversations()).isEqualTo(2);
        assertThat(days.get(0).activeUsers()).isEqualTo(2);
        assertThat(days.get(0).cost()).isEqualByComparingTo(bd("0.0006"));
        assertThat(days.get(1).start()).isEqualTo(Instant.parse("2026-10-01T22:00:00Z"));
        assertThat(days.get(1).turns()).isEqualTo(3);
        assertThat(days.get(1).conversations()).isEqualTo(3);
        assertThat(days.get(1).activeUsers()).isEqualTo(2);
        assertThat(days.get(1).cost()).isEqualByComparingTo(bd("0.00072"));
    }

    @Test
    void timelineByHour() {
        var hours = analytics().timeline(SHOP, Granularity.HOUR);
        assertThat(hours).extracting(TimeBucket::start)
            .containsExactly(Instant.parse("2026-09-30T22:00:00Z"), Instant.parse("2026-10-01T08:00:00Z"),
                    Instant.parse("2026-10-01T09:00:00Z"), Instant.parse("2026-10-01T23:00:00Z"),
                    Instant.parse("2026-10-02T10:00:00Z"), Instant.parse("2026-10-02T11:00:00Z"));
        assertThat(hours).allSatisfy(h -> assertThat(h.turns()).isEqualTo(1));
    }

    @Test
    void timelineHourBucketsFollowZoneOffset() {
        var kolkata = new InsightsFilter("shop", FROM, TO, null, java.time.ZoneId.of("Asia/Kolkata"));
        assertThat(analytics().timeline(kolkata, Granularity.HOUR).get(0).start())
            .isEqualTo(Instant.parse("2026-09-30T21:30:00Z"));
    }

    @Test
    void modelsSplitPricedAndUnpriced() {
        var models = analytics().models(SHOP);
        assertThat(models).extracting(ModelUsage::model).containsExactly("m1", "m2");
        ModelUsage m1 = models.get(0);
        assertThat(m1.calls()).isEqualTo(5);
        assertThat(m1.inputTokens()).isEqualTo(610);
        assertThat(m1.outputTokens()).isEqualTo(355);
        assertThat(m1.cacheReadTokens()).isEqualTo(10);
        assertThat(m1.cacheWriteTokens()).isZero();
        assertThat(m1.cost()).isEqualByComparingTo(bd("0.00132"));
        assertThat(m1.unpricedCalls()).isZero();
        ModelUsage m2 = models.get(1);
        assertThat(m2.calls()).isEqualTo(2);
        assertThat(m2.inputTokens()).isEqualTo(70);
        assertThat(m2.outputTokens()).isEqualTo(10);
        assertThat(m2.cost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(m2.unpricedCalls()).isEqualTo(2);
    }

    @Test
    void toolsMostUsedFirst() {
        var tools = analytics().tools(SHOP);
        assertThat(tools).extracting(ToolUsage::tool).containsExactly("lookup", "refund");
        assertThat(tools.get(0)).satisfies(t -> {
            assertThat(t.calls()).isEqualTo(2);
            assertThat(t.errors()).isZero();
            assertThat(t.turns()).isEqualTo(1);
            assertThat(t.avgDurationMs()).isCloseTo(40.0, within(1e-9));
            assertThat(t.repeatedCalls()).isEqualTo(1);
        });
        assertThat(tools.get(1)).satisfies(t -> {
            assertThat(t.calls()).isEqualTo(1);
            assertThat(t.errors()).isEqualTo(1);
            assertThat(t.turns()).isEqualTo(1);
            assertThat(t.avgDurationMs()).isCloseTo(10.0, within(1e-9));
            assertThat(t.repeatedCalls()).isZero();
        });
    }

    @Test
    void retrievalsByStore() {
        assertThat(analytics().retrievals(SHOP)).singleElement().satisfies(r -> {
            assertThat(r.store()).isEqualTo("kb");
            assertThat(r.queries()).isEqualTo(2);
            assertThat(r.emptyQueries()).isEqualTo(1);
            assertThat(r.avgResultCount()).isCloseTo(1.0, within(1e-9));
            assertThat(r.avgTopScore()).isCloseTo(0.9, within(1e-9));
        });
    }

    @Test
    void topUsers() {
        var users = analytics().topUsers(SHOP, 10);
        assertThat(users).extracting(UserUsage::userId).containsExactly("u1", "u2", "u3");
        assertThat(users.get(0)).satisfies(u -> {
            assertThat(u.turns()).isEqualTo(2);
            assertThat(u.conversations()).isEqualTo(1);
            assertThat(u.cost()).isEqualByComparingTo(bd("0.0006"));
            assertThat(u.lastSeen()).isEqualTo(ContractTurns.T2.startedAt());
        });
        assertThat(users.get(1)).satisfies(u -> {
            assertThat(u.turns()).isEqualTo(2);
            assertThat(u.conversations()).isEqualTo(2);
            assertThat(u.cost()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(u.lastSeen()).isEqualTo(ContractTurns.T3.startedAt());
        });
        assertThat(users.get(2).cost()).isEqualByComparingTo(bd("0.0007"));
        assertThat(analytics().topUsers(SHOP, 2)).extracting(UserUsage::userId).containsExactly("u1", "u2");
    }

    @Test
    void tenants() {
        var tenants = analytics().tenants(SHOP);
        assertThat(tenants).extracting(TenantUsage::tenantId).containsExactly("acme", "globex");
        assertThat(tenants.get(0)).satisfies(t -> {
            assertThat(t.turns()).isEqualTo(3);
            assertThat(t.activeUsers()).isEqualTo(2);
            assertThat(t.cost()).isEqualByComparingTo(bd("0.0013"));
        });
        assertThat(tenants.get(1)).satisfies(t -> {
            assertThat(t.turns()).isEqualTo(2);
            assertThat(t.activeUsers()).isEqualTo(1);
            assertThat(t.cost()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    @Test
    void conversationInOrder() {
        assertThat(analytics().conversation("shop", "c1")).extracting(ConversationTurn::turnId)
            .containsExactly("T1", "T2");
        assertThat(analytics().conversation("crm", "c1")).isEmpty();
    }

    @Test
    void applicationsSorted() {
        assertThat(analytics().applications()).containsExactly("crm", "shop");
    }

    @Test
    void storedTurnRoundTrips() {
        for (ConversationTurn expected : ContractTurns.all()) {
            assertThat(store().findTurn(expected.turnId())).get()
                .usingRecursiveComparison()
                .withComparatorForType(Comparator.naturalOrder(), BigDecimal.class)
                .isEqualTo(expected);
        }
    }
}
