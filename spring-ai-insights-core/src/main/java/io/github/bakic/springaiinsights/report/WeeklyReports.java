package io.github.bakic.springaiinsights.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import io.github.bakic.springaiinsights.analytics.InsightsAnalytics;
import io.github.bakic.springaiinsights.analytics.InsightsFilter;
import io.github.bakic.springaiinsights.analytics.ToolUsage;
import io.github.bakic.springaiinsights.pricing.UnpricedModel;
import org.jspecify.annotations.Nullable;

/** Builds weekly reports. A week is 7 calendar days in the report's zone, so DST weeks stay 7 days. */
public final class WeeklyReports {

    /** How many of the most called tools a report lists. */
    public static final int TOP_TOOLS = 5;

    private final InsightsAnalytics analytics;

    private final String currency;

    private final ZoneId zone;

    private final Clock clock;

    private final @Nullable URI dashboardUrl;

    private final List<String> configuredModels;

    public WeeklyReports(InsightsAnalytics analytics, String currency, ZoneId zone, Clock clock,
            @Nullable URI dashboardUrl) {
        this(analytics, currency, zone, clock, dashboardUrl, List.of());
    }

    /** @param configuredModels the models that have a configured price, listed next to a model that has none */
    public WeeklyReports(InsightsAnalytics analytics, String currency, ZoneId zone, Clock clock,
            @Nullable URI dashboardUrl, List<String> configuredModels) {
        this.configuredModels = List.copyOf(configuredModels);
        this.analytics = analytics;
        this.currency = currency;
        this.zone = zone;
        this.clock = clock;
        this.dashboardUrl = dashboardUrl;
    }

    public ZoneId zone() {
        return zone;
    }

    /** The first day of the last complete week: 7 days before today. */
    public LocalDate lastWeekStart() {
        return LocalDate.now(clock.withZone(zone)).minusDays(7);
    }

    /** The report of the last complete week. */
    public WeeklyReport latest(String application) {
        return forWeek(application, lastWeekStart());
    }

    public WeeklyReport forWeek(String application, LocalDate firstDay) {
        LocalDate end = firstDay.plusDays(7);
        InsightsFilter week = filter(application, firstDay, end);
        InsightsFilter previous = filter(application, firstDay.minusDays(7), firstDay);
        List<ToolUsage> tools = analytics.tools(week);
        ReportFigures current = figures(week);
        return new WeeklyReport(application, currency, firstDay, end.minusDays(1), current, figures(previous),
                tools.subList(0, Math.min(TOP_TOOLS, tools.size())), projection(application, end), dashboardUrl,
                current.unpricedTurns() == 0 ? List.of() : UnpricedModel.of(analytics.models(week), configuredModels),
                configuredModels);
    }

    private ReportFigures figures(InsightsFilter filter) {
        return ReportFigures.of(analytics.summary(filter), analytics.quality(filter));
    }

    private MonthProjection projection(String application, LocalDate end) {
        YearMonth month = YearMonth.from(end.minusDays(1));
        LocalDate monthStart = month.atDay(1);
        int days = (int) ChronoUnit.DAYS.between(monthStart, end);
        BigDecimal cost = analytics.summary(filter(application, monthStart, end)).cost();
        BigDecimal projected = cost.multiply(BigDecimal.valueOf(month.lengthOfMonth()))
            .divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
        return new MonthProjection(month, cost, days, projected);
    }

    private InsightsFilter filter(String application, LocalDate from, LocalDate to) {
        Instant start = from.atStartOfDay(zone).toInstant();
        return new InsightsFilter(application, start, to.atStartOfDay(zone).toInstant(), null, zone);
    }
}
