package io.github.bakic.springaiinsights.report;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.github.bakic.springaiinsights.analytics.InsightsAnalytics;
import io.github.bakic.springaiinsights.analytics.InsightsFilter;
import io.github.bakic.springaiinsights.analytics.QualitySummary;
import io.github.bakic.springaiinsights.analytics.ToolUsage;
import io.github.bakic.springaiinsights.analytics.UsageSummary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeeklyReportsTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final InsightsAnalytics analytics = mock(InsightsAnalytics.class);

    private final List<InsightsFilter> summaryFilters = new ArrayList<>();

    private static UsageSummary summary(long conversations, long activeUsers, String cost, long retrievals,
            long empty, long unpriced) {
        return new UsageSummary(conversations * 2, conversations, activeUsers, 0, 0, 0, 0, 0, 0, 0,
                new BigDecimal(cost), unpriced, 0, null, 0, retrievals, empty, retrievals);
    }

    private static QualitySummary quality(long up, long down) {
        return new QualitySummary(up, down, List.of(), List.of());
    }

    private void answer(Function<InsightsFilter, UsageSummary> summaries,
            Function<InsightsFilter, QualitySummary> qualities) {
        when(analytics.summary(any())).thenAnswer(i -> {
            InsightsFilter filter = i.getArgument(0);
            summaryFilters.add(filter);
            return summaries.apply(filter);
        });
        when(analytics.quality(any())).thenAnswer(i -> qualities.apply(i.getArgument(0)));
        when(analytics.tools(any())).thenReturn(List.of());
    }

    private WeeklyReports reports(String now) {
        return new WeeklyReports(analytics, "USD", PARIS, Clock.fixed(Instant.parse(now), PARIS),
                URI.create("https://example.com/ai-insights/"));
    }

    private static Instant paris(String localDate) {
        return LocalDate.parse(localDate).atStartOfDay(PARIS).toInstant();
    }

    @Test
    void latestIsTheSevenDaysBeforeToday() {
        answer(f -> summary(0, 0, "0", 0, 0, 0), f -> quality(0, 0));
        WeeklyReports reports = reports("2026-10-19T06:00:00Z");
        assertThat(reports.zone()).isEqualTo(PARIS);
        assertThat(reports.lastWeekStart()).isEqualTo(LocalDate.parse("2026-10-12"));
        WeeklyReport report = reports.latest("helpdesk");
        assertThat(report.application()).isEqualTo("helpdesk");
        assertThat(report.currency()).isEqualTo("USD");
        assertThat(report.firstDay()).isEqualTo(LocalDate.parse("2026-10-12"));
        assertThat(report.lastDay()).isEqualTo(LocalDate.parse("2026-10-18"));
        assertThat(report.dashboardUrl()).isEqualTo(URI.create("https://example.com/ai-insights/"));
        assertThat(summaryFilters).anySatisfy(f -> {
            assertThat(f.application()).isEqualTo("helpdesk");
            assertThat(f.tenantId()).isNull();
            assertThat(f.zone()).isEqualTo(PARIS);
            assertThat(f.from()).isEqualTo(paris("2026-10-12"));
            assertThat(f.to()).isEqualTo(paris("2026-10-19"));
        });
        assertThat(summaryFilters).anySatisfy(f -> {
            assertThat(f.from()).isEqualTo(paris("2026-10-05"));
            assertThat(f.to()).isEqualTo(paris("2026-10-12"));
        });
    }

    @Test
    void figuresForBothWeeks() {
        Map<Instant, UsageSummary> byFrom = Map.of(paris("2026-10-12"), summary(4, 3, "10.00", 20, 5, 0),
                paris("2026-10-05"), summary(2, 2, "4.00", 0, 0, 0));
        answer(f -> byFrom.getOrDefault(f.from(), summary(0, 0, "0", 0, 0, 0)),
                f -> f.from().equals(paris("2026-10-12")) ? quality(3, 1) : quality(0, 0));
        WeeklyReport report = reports("2026-10-19T06:00:00Z").latest("helpdesk");
        assertThat(report.current()).isEqualTo(new ReportFigures(3, 4, 8, new BigDecimal("10.00"),
                new BigDecimal("2.5000"), 0.75, 0.25, 0));
        assertThat(report.previous()).isEqualTo(new ReportFigures(2, 2, 4, new BigDecimal("4.00"),
                new BigDecimal("2.0000"), null, null, 0));
    }

    @Test
    void topToolsAreTheFiveMostCalled() {
        answer(f -> summary(0, 0, "0", 0, 0, 0), f -> quality(0, 0));
        List<ToolUsage> tools = new ArrayList<>();
        for (int i = 7; i > 0; i--) {
            tools.add(new ToolUsage("tool" + i, i, 0, i, 1, 1, 0));
        }
        when(analytics.tools(any())).thenReturn(tools);
        assertThat(reports("2026-10-19T06:00:00Z").latest("helpdesk").topTools()).extracting(ToolUsage::tool)
            .containsExactly("tool7", "tool6", "tool5", "tool4", "tool3");
    }

    @Test
    void emptyWeekHasNoRates() {
        answer(f -> summary(0, 0, "0", 0, 0, 0), f -> quality(0, 0));
        ReportFigures figures = reports("2026-10-19T06:00:00Z").latest("helpdesk").current();
        assertThat(figures.conversations()).isZero();
        assertThat(figures.activeUsers()).isZero();
        assertThat(figures.cost()).isEqualByComparingTo("0");
        assertThat(figures.costPerConversation()).isNull();
        assertThat(figures.positiveFeedbackRate()).isNull();
        assertThat(figures.emptyRetrievalRate()).isNull();
    }

    @Test
    void dstWeekIsSevenCalendarDays() {
        answer(f -> summary(0, 0, "0", 0, 0, 0), f -> quality(0, 0));
        WeeklyReport report = reports("2026-10-26T07:00:00Z").latest("helpdesk");
        assertThat(report.firstDay()).isEqualTo(LocalDate.parse("2026-10-19"));
        assertThat(report.lastDay()).isEqualTo(LocalDate.parse("2026-10-25"));
        assertThat(summaryFilters).anySatisfy(f -> {
            assertThat(f.from()).isEqualTo(Instant.parse("2026-10-18T22:00:00Z"));
            assertThat(f.to()).isEqualTo(Instant.parse("2026-10-25T23:00:00Z"));
        });
    }

    @Test
    void projectionScalesMonthToDate() {
        answer(f -> f.from().equals(paris("2026-10-01")) && f.to().equals(paris("2026-10-11"))
                ? summary(1, 1, "10", 0, 0, 0) : summary(0, 0, "0", 0, 0, 0), f -> quality(0, 0));
        MonthProjection projection = reports("2026-10-11T06:00:00Z").forWeek("helpdesk", LocalDate.parse("2026-10-04"))
            .projection();
        assertThat(projection).isEqualTo(new MonthProjection(YearMonth.of(2026, 10), new BigDecimal("10"), 10,
                new BigDecimal("31.00")));
    }

    @Test
    void projectionOnFirstOfMonth() {
        answer(f -> f.from().equals(paris("2026-10-01")) && f.to().equals(paris("2026-11-01"))
                ? summary(1, 1, "123.45", 0, 0, 0) : summary(0, 0, "0", 0, 0, 0), f -> quality(0, 0));
        WeeklyReport report = reports("2026-11-01T06:00:00Z").latest("helpdesk");
        assertThat(report.lastDay()).isEqualTo(LocalDate.parse("2026-10-31"));
        assertThat(report.projection().daysElapsed()).isEqualTo(31);
        assertThat(report.projection().projectedCost()).isEqualByComparingTo("123.45");
    }

    @Test
    void unpricedTurnsAreCounted() {
        answer(f -> summary(1, 1, "1", 0, 0, 3), f -> quality(0, 0));
        assertThat(reports("2026-10-19T06:00:00Z").latest("helpdesk").current().unpricedTurns()).isEqualTo(3);
    }
}
