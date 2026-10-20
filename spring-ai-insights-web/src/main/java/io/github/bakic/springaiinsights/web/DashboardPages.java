package io.github.bakic.springaiinsights.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import io.github.bakic.springaiinsights.analytics.Granularity;
import io.github.bakic.springaiinsights.analytics.InsightsAnalytics;
import io.github.bakic.springaiinsights.analytics.InsightsFilter;
import io.github.bakic.springaiinsights.analytics.ModelUsage;
import io.github.bakic.springaiinsights.analytics.TenantUsage;
import io.github.bakic.springaiinsights.analytics.TimeBucket;
import io.github.bakic.springaiinsights.analytics.ToolUsage;
import io.github.bakic.springaiinsights.analytics.UsageSummary;
import io.github.bakic.springaiinsights.feedback.Rating;
import io.github.bakic.springaiinsights.knowledge.KnowledgeCorpusCatalog;
import io.github.bakic.springaiinsights.model.ConversationTurn;
import io.github.bakic.springaiinsights.store.InMemoryEventStore;
import io.github.bakic.springaiinsights.store.InsightsEventStore;
import io.github.bakic.springaiinsights.store.TurnQuery;
import io.github.bakic.springaiinsights.web.pages.ConversationPage;
import io.github.bakic.springaiinsights.web.pages.CorpusCoverage;
import io.github.bakic.springaiinsights.web.pages.CostPage;
import io.github.bakic.springaiinsights.web.pages.DashboardConfig;
import io.github.bakic.springaiinsights.web.pages.KnowledgePage;
import io.github.bakic.springaiinsights.web.pages.OverviewPage;
import io.github.bakic.springaiinsights.web.pages.QualityPage;
import io.github.bakic.springaiinsights.web.pages.TenantsPage;
import io.github.bakic.springaiinsights.web.pages.ToolsPage;
import io.github.bakic.springaiinsights.web.pages.TurnRow;
import io.github.bakic.springaiinsights.web.pages.UnpricedModel;
import io.github.bakic.springaiinsights.web.pages.UsagePage;
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

    static final int TENANTS = 200;

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

    /** Tenants of the application and period, whatever tenant the filter selects, so the list offers all of them. */
    public TenantsPage tenants(InsightsFilter filter) {
        List<TenantUsage> tenants = analytics.tenants(
                new InsightsFilter(filter.application(), filter.from(), filter.to(), null, filter.zone()));
        return new TenantsPage(tenants.stream().limit(TENANTS).toList(), tenants.size() > TENANTS);
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
        List<ModelUsage> models = analytics.models(filter);
        return new CostPage(currency, summary.cost(), perConversation, monthToDate, projection,
                summary.unpricedTurns(), analytics.timeline(filter, Granularity.DAY), models,
                analytics.tenants(filter), analytics.topUsers(filter, COST_TOP_USERS), unpriced(models),
                options.configuredModels());
    }

    private List<UnpricedModel> unpriced(List<ModelUsage> models) {
        return models.stream()
            .filter(model -> model.unpricedCalls() > 0)
            .map(model -> new UnpricedModel(model.model(), model.unpricedCalls(),
                    similarConfigured(model.model(), options.configuredModels())))
            .toList();
    }

    /** The longest configured model whose name contains, or is contained in, the reported one (ignoring case). */
    static @Nullable String similarConfigured(String reported, List<String> configured) {
        String lower = reported.toLowerCase(Locale.ROOT);
        String best = null;
        for (String candidate : configured) {
            String other = candidate.toLowerCase(Locale.ROOT);
            if ((lower.contains(other) || other.contains(lower)) && (best == null || candidate.length() > best.length())) {
                best = candidate;
            }
        }
        return best;
    }

    public KnowledgePage knowledge(InsightsFilter filter) {
        UsageSummary summary = analytics.summary(filter);
        return new KnowledgePage(summary.turns(), summary.retrievalTurns(), summary.retrievals(),
                summary.emptyRetrievals(), analytics.retrievals(filter), analytics.documents(filter, TOP_DOCUMENTS),
                analytics.leastDocuments(filter, LEAST_DOCUMENTS), coverage(filter));
    }

    public ToolsPage tools(InsightsFilter filter) {
        List<ToolUsage> tools = analytics.tools(filter);
        List<String> offered = analytics.offeredTools(filter);
        Set<String> neverCalled = new TreeSet<>(offered);
        tools.forEach(tool -> neverCalled.remove(tool.tool()));
        return new ToolsPage(tools, List.copyOf(neverCalled), !offered.isEmpty());
    }

    public QualityPage quality(InsightsFilter filter) {
        Instant idle = clock.instant().minus(options.abandonedAfter());
        Instant idleBefore = idle.isBefore(filter.to()) ? idle : filter.to();
        return new QualityPage(analytics.summary(filter), analytics.quality(filter),
                analytics.recentFeedback(filter, Rating.DOWN, RECENT_NEGATIVE),
                analytics.abandonedConversations(filter, idleBefore, RECENT_ABANDONED));
    }

    public ConversationPage conversation(String application, String conversationId) {
        List<ConversationTurn> turns = analytics.conversation(application, conversationId);
        long total = Math.max(turns.size(), analytics.conversationTurns(application, conversationId));
        return new ConversationPage(turns, analytics.conversationFeedback(application, conversationId), total);
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
