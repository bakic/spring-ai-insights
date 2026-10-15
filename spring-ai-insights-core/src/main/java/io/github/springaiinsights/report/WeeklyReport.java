package io.github.springaiinsights.report;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

import io.github.springaiinsights.analytics.ToolUsage;
import org.jspecify.annotations.Nullable;

/**
 * The weekly management report of one application, for all tenants.
 *
 * @param firstDay first day of the week, in the report's zone
 * @param lastDay last day of the week, included
 * @param previous the 7 days before {@code firstDay}, for week-over-week changes
 * @param topTools the most called tools of the week, at most {@link WeeklyReports#TOP_TOOLS}
 * @param projection the cost of the month of {@code lastDay}
 * @param dashboardUrl where the report links to, if configured
 */
public record WeeklyReport(String application, String currency, LocalDate firstDay, LocalDate lastDay,
        ReportFigures current, ReportFigures previous, List<ToolUsage> topTools, MonthProjection projection,
        @Nullable URI dashboardUrl) {

    public WeeklyReport {
        topTools = List.copyOf(topTools);
    }
}
