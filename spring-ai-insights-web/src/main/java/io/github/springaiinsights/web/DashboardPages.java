package io.github.springaiinsights.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import io.github.springaiinsights.analytics.Granularity;
import io.github.springaiinsights.analytics.InsightsAnalytics;
import io.github.springaiinsights.analytics.InsightsFilter;
import io.github.springaiinsights.analytics.TimeBucket;
import io.github.springaiinsights.analytics.ToolUsage;
import io.github.springaiinsights.analytics.UsageSummary;
import io.github.springaiinsights.feedback.Rating;
import io.github.springaiinsights.knowledge.KnowledgeCorpusCatalog;
import io.github.springaiinsights.store.InMemoryEventStore;
import io.github.springaiinsights.store.InsightsEventStore;
import io.github.springaiinsights.store.TurnQuery;
import io.github.springaiinsights.web.pages.ConversationPage;
import io.github.springaiinsights.web.pages.CorpusCoverage;
import io.github.springaiinsights.web.pages.CostPage;
import io.github.springaiinsights.web.pages.DashboardConfig;
import io.github.springaiinsights.web.pages.KnowledgePage;
import io.github.springaiinsights.web.pages.OverviewPage;
import io.github.springaiinsights.web.pages.QualityPage;
import io.github.springaiinsights.web.pages.ToolsPage;
import io.github.springaiinsights.web.pages.TurnRow;
import io.github.springaiinsights.web.pages.UsagePage;
import org.jspecify.annotations.Nullable;

/** Builds the dashboard pages from {@link InsightsAnalytics}; one method per page. */
public final class DashboardPages {

    static final int RECENT_TURNS = 50;

    static final int USAGE_TOP_USERS = 20;

    static final int COST_TOP_USERS = 10;

    static final int TOP_DOCUMENTS = 20;

    static final int LEAST_DOCUMENTS = 20;

    static final int RECENT_NEGATIVE = 20;

    static final int RECENT_ABANDONED = 20;

    static final int NEVER_RETRIEVED_SAMPLE = 50;

    private static final int MONEY_SCALE = 6;

    private static final long MIN_PROJECTION_SECONDS = Duration.ofDays(1).toSeconds();

    private final InsightsAnalytics analytics;

    private final InsightsEventStore store;

    private final String application;

    private final String currency;

    private final Clock clock;

    private final DashboardOptions options;

    public DashboardPages(InsightsAnalytics analytics, InsightsEventStore store, String application, String currency,
            Clock clock) {
        this(analytics, store, application, currency, clock, DashboardOptions.DEFAULTS);
    }

    public DashboardPages(InsightsAnalytics analytics, InsightsEventStore store, String application, String currency,
            Clock clock, DashboardOptions options) {
        this.options = options;
        this.analytics = analytics;
        this.store = store;
        this.application = application;
        this.currency = currency;
        this.clock = clock;
    }

    public String application() {
        return application;
    }

    public DashboardConfig config() {
        return new DashboardConfig(application, analytics.applications(), currency, storeName(store),
                store instanceof InMemoryEventStore, options.traceUrl());
    }

    public OverviewPage overview(InsightsFilter filter) {
        Duration length = Duration.between(filter.from(), filter.to());
        InsightsFilter previous = new InsightsFilter(filter.application(), filter.from().minus(length), filter.from(),
                filter.tenantId(), filter.zone());
        List<TurnRow> recent = store
            .findTurns(new TurnQuery(application(filter), filter.from(), filter.to(), filter.tenantId(),
                    RECENT_TURNS))
            .stream()
            .map(TurnRow::of)
            .toList();
        return new OverviewPage(analytics.summary(filter), analytics.summary(previous),
                analytics.timeline(filter, Granularity.DAY), recent);
    }

    public UsagePage usage(InsightsFilter filter) {
        UsageSummary summary = analytics.summary(filter);
        long[] byHour = new long[24];
        for (TimeBucket bucket : analytics.timeline(filter, Granularity.HOUR)) {
            byHour[bucket.start().atZone(filter.zone()).getHour()] += bucket.turns();
        }
        double turnsPerUser = summary.activeUsers() == 0 ? 0
                : (double) (summary.turns() - summary.anonymousTurns()) / summary.activeUsers();
        double turnsPerConversation = summary.conversations() == 0 ? 0
                : (double) summary.turns() / summary.conversations();
        return new UsagePage(activeUsers(filter, 1), activeUsers(filter, 7), activeUsers(filter, 30),
                summary.activeUsers(), analytics.newUsers(filter), turnsPerUser, turnsPerConversation,
                analytics.timeline(filter, Granularity.DAY), byHour, analytics.topUsers(filter, USAGE_TOP_USERS));
    }

    public CostPage cost(InsightsFilter filter) {
        UsageSummary summary = analytics.summary(filter);
        BigDecimal perConversation = summary.conversations() == 0 ? null
                : summary.cost().divide(BigDecimal.valueOf(summary.conversations()), MONEY_SCALE,
                        RoundingMode.HALF_UP);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, filter.zone());
        Instant monthStart = today.withDayOfMonth(1).atStartOfDay(filter.zone()).toInstant();
        Instant nextMonthStart = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(filter.zone()).toInstant();
        BigDecimal monthToDate = BigDecimal.ZERO;
        if (monthStart.isBefore(now)) {
            monthToDate = analytics
                .summary(new InsightsFilter(filter.application(), monthStart, now, filter.tenantId(), filter.zone()))
                .cost();
        }
        long elapsed = Duration.between(monthStart, now).toSeconds();
        BigDecimal projection = elapsed < MIN_PROJECTION_SECONDS ? null
                : monthToDate.multiply(BigDecimal.valueOf(Duration.between(monthStart, nextMonthStart).toSeconds()))
                    .divide(BigDecimal.valueOf(elapsed), MONEY_SCALE, RoundingMode.HALF_UP);
        return new CostPage(currency, summary.cost(), perConversation, monthToDate, projection,
                summary.unpricedTurns(), analytics.timeline(filter, Granularity.DAY), analytics.models(filter),
                analytics.tenants(filter), analytics.topUsers(filter, COST_TOP_USERS));
    }

    public KnowledgePage knowledge(InsightsFilter filter) {
        UsageSummary summary = analytics.summary(filter);
        return new KnowledgePage(summary.turns(), summary.retrievalTurns(), summary.retrievals(),
                summary.emptyRetrievals(), analytics.retrievals(filter), analytics.documents(filter, TOP_DOCUMENTS),
                analytics.leastDocuments(filter, LEAST_DOCUMENTS), coverage(filter));
    }

    public ToolsPage tools(InsightsFilter filter) {
        List<ToolUsage> tools = analytics.tools(filter);
        Set<String> neverCalled = new TreeSet<>(analytics.offeredTools(filter));
        tools.forEach(tool -> neverCalled.remove(tool.tool()));
        return new ToolsPage(tools, List.copyOf(neverCalled));
    }

    public QualityPage quality(InsightsFilter filter) {
        Instant idle = clock.instant().minus(options.abandonedAfter());
        Instant idleBefore = idle.isBefore(filter.to()) ? idle : filter.to();
        return new QualityPage(analytics.summary(filter), analytics.quality(filter),
                analytics.recentFeedback(filter, Rating.DOWN, RECENT_NEGATIVE),
                analytics.abandonedConversations(filter, idleBefore, RECENT_ABANDONED));
    }

    public ConversationPage conversation(String application, String conversationId) {
        return new ConversationPage(analytics.conversation(application, conversationId),
                analytics.conversationFeedback(application, conversationId));
    }

    private List<CorpusCoverage> coverage(InsightsFilter filter) {
        List<CorpusCoverage> coverage = new ArrayList<>();
        for (KnowledgeCorpusCatalog catalog : options.catalogs()) {
            Set<String> documents = catalog.documentIds();
            if (documents == null) {
                coverage.add(new CorpusCoverage(catalog.store(), null, 0, List.of()));
                continue;
            }
            Set<String> retrieved = analytics.retrievedDocumentIds(filter, catalog.store());
            TreeSet<String> never = new TreeSet<>();
            for (String document : documents) {
                if (!retrieved.contains(document)) {
                    never.add(document);
                }
            }
            coverage.add(new CorpusCoverage(catalog.store(), documents.size(), never.size(),
                    never.stream().limit(NEVER_RETRIEVED_SAMPLE).toList()));
        }
        return coverage;
    }

    private long activeUsers(InsightsFilter filter, int days) {
        InsightsFilter window = new InsightsFilter(filter.application(), filter.to().minus(Duration.ofDays(days)),
                filter.to(), filter.tenantId(), filter.zone());
        return analytics.summary(window).activeUsers();
    }

    private String application(InsightsFilter filter) {
        return Objects.requireNonNullElse(filter.application(), application);
    }

    private static String storeName(@Nullable InsightsEventStore store) {
        if (store instanceof InMemoryEventStore) {
            return "memory";
        }
        String name = store == null ? "none" : store.getClass().getSimpleName();
        return name.equals("MongoEventStore") ? "mongodb" : name;
    }
}
