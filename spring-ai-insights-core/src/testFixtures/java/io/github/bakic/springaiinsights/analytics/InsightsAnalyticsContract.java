package io.github.bakic.springaiinsights.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import io.github.bakic.springaiinsights.feedback.Feedback;
import io.github.bakic.springaiinsights.feedback.FeedbackStore;
import io.github.bakic.springaiinsights.feedback.Rating;
import io.github.bakic.springaiinsights.model.ConversationTurn;
import io.github.bakic.springaiinsights.model.ToolCallRecord;
import io.github.bakic.springaiinsights.model.ToolOutcome;
import io.github.bakic.springaiinsights.store.InsightsEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.github.bakic.springaiinsights.analytics.ContractTurns.FROM;
import static io.github.bakic.springaiinsights.analytics.ContractTurns.HELP;
import static io.github.bakic.springaiinsights.analytics.ContractTurns.PARIS;
import static io.github.bakic.springaiinsights.analytics.ContractTurns.SHOP;
import static io.github.bakic.springaiinsights.analytics.ContractTurns.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

/**
 * Behaviour every {@link InsightsAnalytics} implementation shares. Subclasses return a fresh, empty store per test;
 * the dataset is {@link ContractTurns}.
 */
public abstract class InsightsAnalyticsContract {

    protected abstract InsightsEventStore store();

    protected abstract InsightsAnalytics analytics();

    protected abstract FeedbackStore feedbackStore();

    @BeforeEach
    void saveDataset() {
        ContractTurns.all().forEach(store()::save);
        ContractTurns.help().forEach(store()::save);
        ContractTurns.feedback().forEach(feedbackStore()::saveFeedback);
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
        assertThat(s.retrievalTurns()).isEqualTo(2);
    }

    @Test
    void retrievalTurnsCountEmptyRetrievals() {
        assertThat(analytics().summary(HELP).retrievalTurns()).isEqualTo(3);
    }

    @Test
    void newUsersFirstSeenInRange() {
        assertThat(analytics().newUsers(SHOP)).isEqualTo(2);
        assertThat(analytics().newUsers(HELP)).isEqualTo(2);
        assertThat(analytics().newUsers(new InsightsFilter("help", FROM, TO, "globex", PARIS))).isEqualTo(1);
        var later = new InsightsFilter("shop", Instant.parse("2026-10-01T22:00:00Z"), TO, null, PARIS);
        assertThat(analytics().newUsers(later)).isEqualTo(1);
    }

    @Test
    void documentsMostHitFirst() {
        assertThat(analytics().documents(HELP, 10))
            .extracting(DocumentUsage::store, DocumentUsage::documentId, DocumentUsage::hits)
            .containsExactly(tuple("kb", "d1", 2L), tuple("faq", "q1", 1L), tuple("kb", "d2", 1L));
        assertThat(analytics().documents(HELP, 2)).hasSize(2);
    }

    @Test
    void quality() {
        QualitySummary q = analytics().quality(HELP);
        assertThat(q.positiveFeedback()).isEqualTo(2);
        assertThat(q.negativeFeedback()).isEqualTo(1);
        assertThat(q.finishReasons()).containsExactly(new KeyCount("STOP", 2), new KeyCount("LENGTH", 1));
        assertThat(q.errorTypes()).containsExactly(new KeyCount("TimeoutException", 2));
        QualitySummary acme = analytics().quality(new InsightsFilter("help", FROM, TO, "acme", PARIS));
        assertThat(acme.positiveFeedback()).isEqualTo(1);
        assertThat(acme.negativeFeedback()).isEqualTo(1);
        assertThat(acme.errorTypes()).isEmpty();
    }

    @Test
    void recentFeedbackNewestFirst() {
        assertThat(analytics().recentFeedback(HELP, null, 10)).extracting(Feedback::turnId)
            .containsExactly("H5", "H2", "H1");
        assertThat(analytics().recentFeedback(HELP, Rating.DOWN, 10)).containsExactly(ContractTurns.F2);
        assertThat(analytics().recentFeedback(HELP, null, 1)).extracting(Feedback::turnId).containsExactly("H5");
    }

    @Test
    void conversationFeedbackOldestFirst() {
        assertThat(analytics().conversationFeedback("help", "h1")).containsExactly(ContractTurns.F1,
                ContractTurns.F2);
        assertThat(analytics().conversationFeedback("shop", "h1")).isEmpty();
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
        assertThat(analytics().summary(new InsightsFilter(null, FROM, TO, null, PARIS)).turns()).isEqualTo(12);
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
        assertThat(s.retrievalTurns()).isZero();
        assertThat(analytics().newUsers(empty)).isZero();
        assertThat(analytics().documents(empty, 10)).isEmpty();
        assertThat(analytics().quality(empty)).isEqualTo(new QualitySummary(0, 0, java.util.List.of(),
                java.util.List.of()));
        assertThat(analytics().recentFeedback(empty, null, 10)).isEmpty();
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
    void timelineInUtcOffset() {
        var utc = new InsightsFilter("shop", FROM, TO, null, java.time.ZoneOffset.UTC);
        assertThat(analytics().timeline(utc, Granularity.DAY)).extracting(TimeBucket::start, TimeBucket::turns)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(Instant.parse("2026-09-30T00:00:00Z"), 1L),
                    org.assertj.core.groups.Tuple.tuple(Instant.parse("2026-10-01T00:00:00Z"), 3L),
                    org.assertj.core.groups.Tuple.tuple(Instant.parse("2026-10-02T00:00:00Z"), 2L));
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
            assertThat(t.p95DurationMs()).isEqualTo(50);
            assertThat(t.repeatedCalls()).isEqualTo(1);
        });
        assertThat(tools.get(1)).satisfies(t -> {
            assertThat(t.calls()).isEqualTo(1);
            assertThat(t.errors()).isEqualTo(1);
            assertThat(t.turns()).isEqualTo(1);
            assertThat(t.avgDurationMs()).isCloseTo(10.0, within(1e-9));
            assertThat(t.p95DurationMs()).isEqualTo(10);
            assertThat(t.repeatedCalls()).isZero();
        });
    }

    @Test
    void toolP95IsNearestRank() {
        List<ToolCallRecord> calls = new ArrayList<>();
        for (int duration = 20; duration >= 1; duration--) {
            calls.add(new ToolCallRecord("t", duration, ToolOutcome.SUCCESS, null, 0));
        }
        store().save(ContractTurns.toolTurn("P1", "perf", "2026-10-01T10:00:00Z", "p1", calls));
        assertThat(analytics().tools(new InsightsFilter("perf", FROM, TO, null, PARIS))).singleElement()
            .satisfies(t -> assertThat(t.p95DurationMs()).isEqualTo(19));
    }

    @Test
    void offeredToolsDistinctSorted() {
        assertThat(analytics().offeredTools(SHOP)).containsExactly("lookup", "refund", "search");
        assertThat(analytics().offeredTools(HELP)).isEmpty();
    }

    @Test
    void leastDocumentsFewestFirst() {
        assertThat(analytics().leastDocuments(HELP, 2)).containsExactly(new DocumentUsage("faq", "q1", 1),
                new DocumentUsage("kb", "d2", 1));
        assertThat(analytics().leastDocuments(HELP, 0)).isEmpty();
    }

    @Test
    void retrievedDocumentIdsPerStore() {
        assertThat(analytics().retrievedDocumentIds(HELP, "kb")).containsExactlyInAnyOrder("d1", "d2");
        assertThat(analytics().retrievedDocumentIds(HELP, "faq")).containsExactly("q1");
        assertThat(analytics().retrievedDocumentIds(HELP, "other")).isEmpty();
    }

    @Test
    void abandonedConversations() {
        AbandonedConversations help = analytics().abandonedConversations(HELP, TO, 2);
        assertThat(help.count()).isEqualTo(3);
        assertThat(help.recent()).containsExactly(
                new AbandonedConversation("h3", null, "H4", Instant.parse("2026-10-02T12:00:00Z"), AbandonReason.ERROR),
                new AbandonedConversation("h2", "v2", "H3", Instant.parse("2026-10-02T09:00:00Z"), AbandonReason.ERROR));
        assertThat(analytics().abandonedConversations(HELP, TO, 10).recent()).last()
            .isEqualTo(new AbandonedConversation("h1", "v1", "H2", Instant.parse("2026-10-01T11:00:00Z"),
                    AbandonReason.NEGATIVE_FEEDBACK));
        AbandonedConversations shop = analytics().abandonedConversations(SHOP, TO, 10);
        assertThat(shop.count()).isEqualTo(2);
        assertThat(shop.recent()).extracting(AbandonedConversation::conversationId, AbandonedConversation::reason)
            .containsExactly(tuple("c3", AbandonReason.CANCELLED), tuple("c2", AbandonReason.ERROR));
    }

    @Test
    void stillActiveIsNotAbandoned() {
        AbandonedConversations help = analytics().abandonedConversations(HELP, Instant.parse("2026-10-02T11:00:00Z"), 10);
        assertThat(help.count()).isEqualTo(2);
        assertThat(help.recent()).extracting(AbandonedConversation::conversationId).containsExactly("h2", "h1");
    }

    @Test
    void laterTurnOutsideRangeMeansNotAbandoned() {
        store().save(ContractTurns.toolTurn("H6", "help", "2026-10-03T08:00:00Z", "h2", List.of()));
        assertThat(analytics().abandonedConversations(HELP, TO, 10).recent())
            .extracting(AbandonedConversation::conversationId)
            .containsExactly("h3", "h1");
    }

    @Test
    void abandonedRespectsTenant() {
        AbandonedConversations globex = analytics()
            .abandonedConversations(new InsightsFilter("help", FROM, TO, "globex", PARIS), TO, 10);
        assertThat(globex.count()).isEqualTo(1);
        assertThat(globex.recent()).extracting(AbandonedConversation::conversationId).containsExactly("h2");
    }

    @Test
    void idleCutoffBeforeRangeGivesNothing() {
        AbandonedConversations none = analytics().abandonedConversations(HELP, FROM, 10);
        assertThat(none.count()).isZero();
        assertThat(none.recent()).isEmpty();
    }

    @Test
    void abandonedLimitZeroStillCounts() {
        AbandonedConversations help = analytics().abandonedConversations(HELP, TO, 0);
        assertThat(help.count()).isEqualTo(3);
        assertThat(help.recent()).isEmpty();
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
        assertThat(analytics().applications()).containsExactly("crm", "help", "shop");
    }

    @Test
    void storedTurnRoundTrips() {
        for (ConversationTurn expected : Stream.concat(ContractTurns.all().stream(), ContractTurns.help().stream())
            .toList()) {
            assertThat(store().findTurn(expected.turnId())).get()
                .usingRecursiveComparison()
                .withComparatorForType(Comparator.naturalOrder(), BigDecimal.class)
                .isEqualTo(expected);
        }
    }
}
