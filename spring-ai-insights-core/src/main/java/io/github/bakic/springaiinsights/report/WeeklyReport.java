package io.github.bakic.springaiinsights.report;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

import io.github.bakic.springaiinsights.analytics.ToolUsage;
import io.github.bakic.springaiinsights.pricing.UnpricedModel;
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
 * @param unpricedModels the models of the week that have no price, with the configured model each resembles
 * @param configuredModels the models that have a configured price, to compare with {@code unpricedModels}
 */
public record WeeklyReport(String application, String currency, LocalDate firstDay, LocalDate lastDay,
        ReportFigures current, ReportFigures previous, List<ToolUsage> topTools, MonthProjection projection,
        @Nullable URI dashboardUrl, List<UnpricedModel> unpricedModels, List<String> configuredModels) {

    public WeeklyReport(String application, String currency, LocalDate firstDay, LocalDate lastDay,
            ReportFigures current, ReportFigures previous, List<ToolUsage> topTools, MonthProjection projection,
            @Nullable URI dashboardUrl) {
        this(application, currency, firstDay, lastDay, current, previous, topTools, projection, dashboardUrl,
                List.of(), List.of());
    }

    public WeeklyReport {
        topTools = List.copyOf(topTools);
        unpricedModels = List.copyOf(unpricedModels);
        configuredModels = List.copyOf(configuredModels);
    }
}
